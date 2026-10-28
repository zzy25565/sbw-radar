package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.entity.projectile.FlareDecoyEntity;
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * IR (heat-seeking) missile support, aircraft and helicopters only.
 *
 *  - pre-launch lock: a target inside the seeker cone produces a lock; the lock tone is
 *    streamed to the client and the weapon cannot fire without it (ir.requireLock)
 *  - heat model is approximated: rear aspect gets full range, head-on only ir.frontRangeFactor
 *  - terminal seeker: after launch the missile tracks the entity itself; flares inside the
 *    seeker cone can distract it with a configurable probability
 */
public final class IrMissileControl {

    private static final class Guided {
        final int launcherVehicleId;
        final String ownerUuid;
        int lostTicks;
        double flareChance;
        final java.util.Set<java.util.UUID> seenFlares = new java.util.HashSet<>();

        Guided(int launcherVehicleId, String ownerUuid) {
            this.launcherVehicleId = launcherVehicleId;
            this.ownerUuid = ownerUuid;
        }
    }

    /** player uuid -> locked target entity id */
    private static final Map<String, Integer> LOCKS = new HashMap<>();
    private static final Map<Entity, Guided> GUIDED = new HashMap<>();
    private static final Random RNG = new Random();

    // ── per-tick: refresh player locks, tone sync ──

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SbwRadarConfig.IR_ENABLE.get()) return;
        int tick = event.getServer().getTickCount();

        if (tick % 5 == 0) {
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                refreshLock(player);
            }
        }
        if (!GUIDED.isEmpty()) {
            updateMissiles();
        }
    }

    private static void refreshLock(ServerPlayer player) {
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.isWreck()) {
            LOCKS.remove(player.getStringUUID());
            return;
        }
        if(!LoadoutIntegration.wantsIrLock(player,vehicle)) {
            LOCKS.remove(player.getStringUUID());
            PacketDistributor.sendToPlayer(player,new RadarPayloads.IrState(false,0,""));
            return;
        }
        VehicleType type = vehicle.getVehicleType();
        if (type != VehicleType.AIRPLANE && type != VehicleType.HELICOPTER) {
            // riding something else: drop any leftover lock so the tone cannot stay on
            if (LOCKS.remove(player.getStringUUID()) != null) {
                PacketDistributor.sendToPlayer(player, new RadarPayloads.IrState(false, 0, ""));
            }
            return;
        }

        Entity target = findHeatTarget(player, vehicle);
        if (target == null) {
            LOCKS.remove(player.getStringUUID());
            PacketDistributor.sendToPlayer(player, new RadarPayloads.IrState(false, 0, ""));
            return;
        }
        LOCKS.put(player.getStringUUID(), target.getId());
        PacketDistributor.sendToPlayer(player, new RadarPayloads.IrState(
                true,
                (int) Math.round(vehicle.distanceTo(target)),
                target.getName().getString()));
    }

    /** Best heat target inside the seeker cone. */
    private static Entity findHeatTarget(Player player, VehicleEntity vehicle) {
        Vec3 eye = vehicle.position().add(0.0, 1.5, 0.0);
        Vec3 look = vehicle.getLookAngle().normalize();
        double coneCos = Math.cos(Math.toRadians(SbwRadarConfig.IR_SEEKER_CONE.get()));

        double maxRange = Math.max(SbwRadarConfig.IR_RANGE_REAR.get(), SbwRadarConfig.IR_RANGE_FRONT.get());
        AABB box = new AABB(eye, eye).inflate(maxRange);
        List<Entity> candidates = vehicle.level().getEntities(vehicle, box, e ->
                e.isAlive() && !e.isSpectator()
                        && (e instanceof VehicleEntity || e instanceof LivingEntity));

        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : candidates) {
            if(e==player || e.getRootVehicle()==vehicle) continue;
            if (isFriendly(player, e)) continue;
            Vec3 to = e.position().add(0.0, 1.0, 0.0).subtract(eye);
            double dist = to.length();
            if (dist < 1.0 || dist > maxRange) continue;
            if (to.normalize().dot(look) < coneCos) continue;

            // head-on targets present a much smaller heat signature
            boolean headOn = e.getLookAngle().normalize().dot(to.normalize()) < -0.5;
            double allowed = headOn ? SbwRadarConfig.IR_RANGE_FRONT.get() : SbwRadarConfig.IR_RANGE_REAR.get();
            if (dist > allowed) continue;
            if(!net.minecraft.world.level.BlockGetter.traverseBlocks(eye,e.getBoundingBox().getCenter(),vehicle.level(),
                    (world,pos)->world.hasChunkAt(pos) ? null : Boolean.FALSE,world->Boolean.TRUE)) continue;
            var hit=vehicle.level().clip(new net.minecraft.world.level.ClipContext(eye,
                    e.getBoundingBox().getCenter(),net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,vehicle));
            if(hit.getType()!=net.minecraft.world.phys.HitResult.Type.MISS) continue;

            double score = dist;
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private static boolean isFriendly(Player owner, Entity candidate) {
        PlayerTeam team = owner.getTeam();
        if (team == null) return false;
        if (candidate instanceof LivingEntity living && living.getTeam() == team) return true;
        Entity controller = candidate.getControllingPassenger();
        return controller instanceof Player p && p.getTeam() == team;
    }

    // ── launch ──

    @SubscribeEvent
    public static void onShoot(com.atsuishio.superbwarfare.api.event.ShootEvent.Post event) {
        if (!SbwRadarConfig.IR_ENABLE.get()) return;
        Entity shooter = event.getShooter();
        if (!(shooter instanceof Player player)) return;
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.isWreck()) return;
        if(LoadoutIntegration.handles(player,vehicle)) return;

        VehicleType type = vehicle.getVehicleType();
        if (type != VehicleType.AIRPLANE && type != VehicleType.HELICOPTER) return;
        if (!RadarMissileControl.isMissileAmmoPublic(event.getData())) return;

        ServerLevel level = event.getLevel();
        String ammoId = RadarMissileControl.ammoId(event.getData());
        AmmoSpecs.Spec spec = AmmoSpecs.find(level.getServer(), ammoId);
        if (spec != null && spec.category() != AmmoSpecs.Category.IR) return; // radar ammo handled elsewhere
        boolean requireLock = spec != null ? spec.requireLock() : SbwRadarConfig.IR_REQUIRE_LOCK.get();

        Integer locked = LOCKS.get(player.getStringUUID());
        if (locked == null) {
            if (requireLock) return; // no tone, no shot
            locked = -1;
        }

        Entity target = locked >= 0 ? level.getEntity(locked) : null;

        String entityId = spec != null && spec.missileEntity() != null
                ? spec.missileEntity() : SbwRadarConfig.IR_ENTITY.get();
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        if (id == null) return;
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.get(id);
        Entity missile = entityType.create(level);
        if (missile == null) return;

        Vec3 spawn = vehicle.position().add(0.0, 1.2, 0.0);
        Vec3 dir = target != null
                ? target.getEyePosition().subtract(spawn).normalize()
                : vehicle.getLookAngle().normalize();

        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Math.asin(dir.y));
        missile.moveTo(spawn.x + dir.x * 1.4, spawn.y + dir.y * 1.4, spawn.z + dir.z * 1.4, yaw, pitch);
        missile.setDeltaMovement(dir.scale(SbwRadarConfig.MISSILE_SPEED.get()));

        if (missile instanceof ThrowableItemProjectile tip) tip.setOwner(player);
        if (target != null && missile instanceof MissileProjectile mp) {
            mp.setTargetUuid(target.getStringUUID());
            mp.setTargetVec(target.position());
            mp.setLost(false);
        }
        if (!level.addFreshEntity(missile)) return;
        Guided guided = new Guided(vehicle.getId(), player.getStringUUID());
        guided.flareChance = spec != null ? spec.flareChance() : SbwRadarConfig.IR_FLARE_CHANCE.get();
        GUIDED.put(missile, guided);
    }

    // ── guidance + flares ──

    private static void updateMissiles() {
        for (Iterator<Map.Entry<Entity, Guided>> it = GUIDED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Guided> entry = it.next();
            Entity missile = entry.getKey();
            Guided g = entry.getValue();

            if (missile.isRemoved() || !missile.isAlive() || missile.level() == null) {
                it.remove();
                continue;
            }

            // flare / decoy distraction: one roll for each NEW flare that appears in range,
            // not one roll per tick (a per-tick roll made the missile almost always decoyed)
            if (missile instanceof MissileProjectile mp && !mp.isDistracted()) {
                List<Entity> flares = missile.level().getEntities(missile,
                        missile.getBoundingBox().inflate(24.0),
                        e -> e instanceof FlareDecoyEntity && e.isAlive());
                for (Entity flare : flares) {
                    if (!g.seenFlares.add(flare.getUUID())) continue;
                    if (RNG.nextDouble() < g.flareChance) {
                        mp.setDistracted(true);
                        break;
                    }
                }
            }
            if (missile instanceof MissileProjectile mp && mp.isDistracted()) {
                if(missile.getPersistentData().contains("SBWRadarLoadoutIR"))
                    missile.getPersistentData().getCompound("SBWRadarLoadoutIR").putBoolean("Decoyed",true);
                continue;
            }

            // onboard seeker keeps tracking whatever it was pointed at
            Entity target = null;
            if (missile instanceof MissileProjectile mp) {
                String uuid = mp.getTargetUUID();
                if (uuid != null && !uuid.isEmpty()) {
                    try {
                        if (missile.level() instanceof ServerLevel sl) {
                            target = sl.getEntity(UUID.fromString(uuid));
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
            if (target != null && target.isAlive()) {
                g.lostTicks = 0;
                if (missile instanceof MissileProjectile mp) {
                    mp.setTargetVec(target.position());
                    mp.setLost(false);
                }
            } else {
                g.lostTicks++;
                if (g.lostTicks > SbwRadarConfig.MISSILE_LOST_TIMEOUT.get()) {
                    missile.discard();
                    it.remove();
                }
            }
        }
    }

    /**
     * Flare attempt: one roll per IR missile inside the radius.
     * Success distracts the missile so it stops tracking the aircraft.
     */
    public static int tryDecoy(ServerLevel level, Vec3 pos,
                               double radius, double chance, String ownerUuid) {
        int defeated = 0;
        for (Iterator<Map.Entry<Entity, Guided>> it = GUIDED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Guided> e = it.next();
            Entity missile = e.getKey();
            Guided g = e.getValue();
            if (missile.isRemoved() || !missile.isAlive() || missile.level() != level) continue;
            if (g.ownerUuid.equals(ownerUuid)) continue;
            if (missile.position().distanceTo(pos) > radius) continue;
            if (Math.random() >= chance) continue;
            if (missile instanceof MissileProjectile mp) {
                mp.setDistracted(true);
            }
            if(missile.getPersistentData().contains("SBWRadarLoadoutIR"))
                missile.getPersistentData().getCompound("SBWRadarLoadoutIR").putBoolean("Decoyed",true);
            it.remove();
            defeated++;
        }
        return defeated;
    }

    /** True when this player currently has an IR lock (for HUD / diagnostics). */
    public static boolean hasLock(String playerUuid) {
        return LOCKS.containsKey(playerUuid);
    }

    public static Entity lockedTarget(VehicleEntity vehicle) {
        if(!SbwRadarConfig.IR_ENABLE.get() || !(vehicle.getFirstPassenger() instanceof ServerPlayer p)
                || vehicle.getSeatIndex(p)!=0 || vehicle.isWreck()) return null;
        return findHeatTarget(p,vehicle);
    }

    /** Register the projectile already fired and paid for by loadout, without spawning another. */
    public static void trackLoadout(net.minecraft.world.entity.projectile.Projectile shot,ServerPlayer owner,
                                    VehicleEntity launcher,Entity target,String ammo) {
        if(!(shot instanceof MissileProjectile mp)) return;
        mp.setTargetUuid(target.getStringUUID());mp.setTargetVec(target.position());mp.setLost(false);
        var n=new net.minecraft.nbt.CompoundTag();
        n.putUUID("Source",launcher.getUUID());n.putUUID("Owner",owner.getUUID());n.putString("Ammo",ammo);
        if(owner.getTeam()!=null) n.putString("OwnerTeam",owner.getTeam().getName());
        shot.getPersistentData().put("SBWRadarLoadoutIR",n);
        Guided g=new Guided(launcher.getId(),owner.getStringUUID());
        g.flareChance=SbwRadarConfig.IR_FLARE_CHANCE.get();GUIDED.put(shot,g);
    }

    @SubscribeEvent
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if(event.getLevel().isClientSide() || !(event.getEntity() instanceof MissileProjectile mp)) return;
        var n=mp.getPersistentData().getCompound("SBWRadarLoadoutIR");
        if(!n.hasUUID("Owner") || GUIDED.containsKey(mp)) return;
        if(n.getBoolean("Decoyed")) mp.setDistracted(true);
        Guided g=new Guided(-1,n.getUUID("Owner").toString());
        g.flareChance=SbwRadarConfig.IR_FLARE_CHANCE.get();GUIDED.put(mp,g);
    }

    private IrMissileControl() {}
}
