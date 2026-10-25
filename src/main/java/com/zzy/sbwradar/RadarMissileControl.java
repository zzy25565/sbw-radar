package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.api.event.ShootEvent;
import com.atsuishio.superbwarfare.data.gun.AmmoConsumer;
import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.ProjectileInfo;
import com.atsuishio.superbwarfare.entity.projectile.MissileProjectile;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Radar-guided (ARH) missile control.
 *
 * Reuses SBW's own missile entity for rendering/sound/explosion and feeds it the radar
 * track every tick.
 *
 * 1.0.7 changes:
 *  - only missile-class ammo may launch (selectedAmmoConsumer projectile id keyword check),
 *    so machine guns / cannons no longer spawn missiles
 *  - terminal active-seeker acquisition: when the radar lock drops, the missile scans a
 *    forward cone inside seekerRange and re-acquires a target by itself
 */
public final class RadarMissileControl {

    private static final class Guided {
        final int radarVehicleId;
        final String ownerUuid;
        final Vec3 illumAxis;   // radar illumination axis at launch (climb reference)
        int lostTicks;
        int age;
        boolean terminal;
        Vec3 lastKnown;
        int climbTicks;
        double seekerRange;
        /** target assigned at launch (TWS); -1 = follow the radar's TRK target */
        int assignedTargetId = -1;

        Guided(int radarVehicleId, String ownerUuid, Vec3 lastKnown, Vec3 illumAxis) {
            this.radarVehicleId = radarVehicleId;
            this.ownerUuid = ownerUuid;
            this.lastKnown = lastKnown;
            this.illumAxis = illumAxis;
        }
    }

    private static final Map<Entity, Guided> GUIDED = new ConcurrentHashMap<>();

    // ── firing ──

    @SubscribeEvent
    public static void onShoot(ShootEvent.Post event) {
        if (!SbwRadarConfig.MISSILE_ENABLE.get()) return;

        Entity shooter = event.getShooter();
        if (!(shooter instanceof Player player)) return;
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.isWreck()) return;

        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString();
        if (!SbwRadarConfig.MISSILE_VEHICLES.get().contains(typeId)) return;

        // only missile-class ammo may launch a radar missile
        if (!isMissileAmmo(event.getData())) return;

        ServerLevel level0 = event.getLevel();
        String ammoId = ammoId(event.getData());
        AmmoSpecs.Spec spec = AmmoSpecs.find(level0.getServer(), ammoId);
        if (spec != null && spec.category() != AmmoSpecs.Category.RADAR) return; // IR ammo handled elsewhere


        ServerLevel level = event.getLevel();
        RadarMode radarMode = ServerRadarHandler.modeOf(vehicle.getId());

        int targetId = -1;
        if (radarMode == RadarMode.TWS) {
            targetId = pickTwsTarget(level, vehicle);
        }
        if (targetId < 0) {
            targetId = ServerRadarHandler.trackedTargetId(vehicle.getId());
        }
        if (targetId < 0) return;

        Entity target = level.getEntity(targetId);
        if (target == null || !target.isAlive()) return;

        int guiding = 0;
        for (Guided g : GUIDED.values()) {
            if (g.radarVehicleId == vehicle.getId()) guiding++;
        }
        int maxChannels = spec != null ? spec.maxPerRadar() : SbwRadarConfig.MISSILE_MAX_CONCURRENT.get();
        if (guiding >= maxChannels) return;

        String entityId = spec != null && spec.missileEntity() != null
                ? spec.missileEntity() : SbwRadarConfig.MISSILE_ENTITY.get();
        ResourceLocation id = ResourceLocation.tryParse(entityId);
        if (id == null) return;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
        Entity missile = type.create(level);
        if (missile == null) return;

        Vec3 spawn = vehicle.position().add(0.0, 1.6, 0.0);
        // illumination axis: radar (vehicle) -> target, tilted up a little
        Vec3 axis = target.getEyePosition().subtract(spawn);
        if (axis.lengthSqr() < 1.0E-4) axis = vehicle.getLookAngle();
        axis = axis.normalize();
        double climbUp = spec != null ? spec.climbUpDeg() : SbwRadarConfig.MISSILE_CLIMB_UP.get();
        double upRad = Math.toRadians(climbUp);
        Vec3 dir = axis.add(0.0, Math.tan(upRad), 0.0).normalize();

        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        float pitch = (float) -Math.toDegrees(Math.asin(dir.y));
        missile.moveTo(spawn.x + dir.x * 1.6, spawn.y + dir.y * 1.6, spawn.z + dir.z * 1.6, yaw, pitch);
        missile.setDeltaMovement(dir.scale(SbwRadarConfig.MISSILE_SPEED.get()));

        if (missile instanceof ThrowableItemProjectile tip) tip.setOwner(player);
        if (missile instanceof MissileProjectile mp) {
            mp.setTargetUuid(target.getStringUUID());
            mp.setTargetVec(target.position());
            mp.setLost(false);
        }

        if (!level.addFreshEntity(missile)) return;
        Guided guided = new Guided(vehicle.getId(), player.getStringUUID(), target.position(), dir);
        guided.assignedTargetId = radarMode == RadarMode.TWS ? targetId : -1;
        guided.climbTicks = spec != null ? spec.climbTicks() : SbwRadarConfig.MISSILE_CLIMB_TICKS.get();
        guided.seekerRange = spec != null ? spec.seekerRange() : SbwRadarConfig.MISSILE_SEEKER_RANGE.get();
        GUIDED.put(missile, guided);
    }

    /** Ammo id currently loaded in the weapon (projectile id, else item id). */
    public static String ammoId(GunData data) {
        if (data == null) return null;
        try {
            String proj = projectileId(data.selectedAmmoConsumer());
            if (proj != null) return proj;
            if (data.stack != null && !data.stack.isEmpty()) {
                return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(data.stack.getItem()).toString();
            }
            return data.item() != null ? String.valueOf(data.item()) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Choose a TWS track for a new missile: prefer a track that no other missile of this
     * radar is already assigned to, then the one closest to where the vehicle is aiming.
     */
    private static int pickTwsTarget(ServerLevel level, com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity vehicle) {
        java.util.List<Integer> tracks = ServerRadarHandler.twsTracks(vehicle.getId());
        if (tracks.isEmpty()) return -1;

        java.util.Set<Integer> taken = new java.util.HashSet<>();
        for (Guided g : GUIDED.values()) {
            if (g.radarVehicleId == vehicle.getId() && g.assignedTargetId >= 0) {
                taken.add(g.assignedTargetId);
            }
        }

        com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity v = vehicle;
        net.minecraft.world.phys.Vec3 look = v.getLookAngle();
        net.minecraft.world.phys.Vec3 origin = v.position();
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (int id : tracks) {
            if (taken.contains(id)) continue;
            Entity e = level.getEntity(id);
            if (e == null || !e.isAlive()) continue;
            net.minecraft.world.phys.Vec3 to = e.position().subtract(origin);
            double dist = to.length();
            if (dist < 1.0) continue;
            double align = to.normalize().dot(look);          // 1 = straight ahead
            double score = dist * (1.6 - align);              // closer + more aligned wins
            if (score < bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best;
    }

    /** Public wrapper used by the IR controller. */
    public static boolean isMissileAmmoPublic(GunData data) {
        return isMissileAmmo(data);
    }

    /** True when the ammo currently selected on this weapon is missile-like. */
    private static boolean isMissileAmmo(GunData data) {
        if (data == null) return false;
        try {
            AmmoConsumer consumer = data.selectedAmmoConsumer();
            String proj = projectileId(consumer);
            List<? extends String> kws = SbwRadarConfig.MISSILE_AMMO_KEYWORDS.get();
            if (proj != null) {
                String low = proj.toLowerCase();
                for (String k : kws) {
                    if (low.contains(k.toLowerCase())) return true;
                }
                return false;
            }
            // fall back to the weapon item / ammo item ids (some addon weapons carry no
            // ProjectileInfo, which previously blocked legitimate missile launches)
            StringBuilder ids = new StringBuilder();
            if (data.item() != null) ids.append(data.item()).append(' ');
            try {
                if (data.stack != null && !data.stack.isEmpty()) {
                    ids.append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(data.stack.getItem())).append(' ');
                }
            } catch (Throwable ignored) {
            }
            String hay = ids.toString().toLowerCase();
            if (!hay.isBlank()) {
                for (String k : kws) {
                    if (hay.contains(k.toLowerCase())) return true;
                }
            }
        } catch (Throwable ignored) {
            // never let a weapon-data quirk block the shoot event
        }
        return false;
    }

    private static String projectileId(AmmoConsumer consumer) {
        if (consumer == null) return null;
        try {
            var holder = consumer.getProjectile();
            if (holder == null || holder.value == null) return null;
            ProjectileInfo info = holder.value;
            String itemId = info.getItemId();
            return itemId != null ? itemId : info.getId();
        } catch (Throwable t) {
            return null;
        }
    }

    // ── guidance ──

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (GUIDED.isEmpty()) return;
        MinecraftServer server = event.getServer();
        if (server == null) return;
        int timeout = SbwRadarConfig.MISSILE_LOST_TIMEOUT.get();
        double seeker = SbwRadarConfig.MISSILE_SEEKER_RANGE.get();

        if (GUIDED.size() > 128) {
            // hard safety cap: drop the entries with the highest age (truly the oldest)
            while (GUIDED.size() > 128) {
                Entity oldest = null;
                int maxAge = -1;
                for (Map.Entry<Entity, Guided> en : GUIDED.entrySet()) {
                    if (en.getValue().age > maxAge) {
                        maxAge = en.getValue().age;
                        oldest = en.getKey();
                    }
                }
                if (oldest == null) break;
                GUIDED.remove(oldest);
            }
        }

        for (Iterator<Map.Entry<Entity, Guided>> it = GUIDED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Guided> entry = it.next();
            Entity missile = entry.getKey();
            Guided g = entry.getValue();

            if (missile.isRemoved() || !missile.isAlive() || missile.level() == null) {
                it.remove();
                continue;
            }

            g.age++;

            // 1) boost / climb: fly along the radar illumination axis, ignore the target
            if (g.age <= g.climbTicks) {
                if (missile instanceof MissileProjectile mp) {
                    mp.setTargetVec(missile.position().add(g.illumAxis.scale(64.0)));
                }
                continue;
            }

            // TWS: stick to the track assigned at launch while the radar still holds it
            Entity target = null;
            if (g.assignedTargetId >= 0) {
                boolean stillTracked = ServerRadarHandler.isTracked(g.radarVehicleId, g.assignedTargetId);
                Entity assigned = missile.level().getEntity(g.assignedTargetId);
                if (stillTracked && assigned != null && assigned.isAlive()) {
                    target = assigned;
                } else {
                    g.assignedTargetId = -1;   // track dropped: fall back below
                }
            }

            Entity radarVehicle = missile.level().getEntity(g.radarVehicleId);
            int targetId = target == null && radarVehicle instanceof VehicleEntity
                    ? ServerRadarHandler.trackedTargetId(g.radarVehicleId) : -1;
            if (target == null) {
                target = targetId >= 0 ? missile.level().getEntity(targetId) : null;
            }

            // 2) terminal phase: the missile's own radar seeker takes over (independent of the
            //    launching radar), so a dropped lock no longer matters inside seeker range
            if (target != null && target.isAlive()) {
                double dist = missile.position().distanceTo(target.position());
                if (dist <= g.seekerRange) g.terminal = true;
                g.lostTicks = 0;
                g.lastKnown = target.position();
                steer(missile, target);
                continue;
            }

            // radar lost: try terminal active-seeker acquisition before giving up
            Entity acquired = acquireTerminal(missile, g, g.seekerRange);
            if (acquired != null) {
                g.terminal = true; // onboard seeker owns the target from now on
                g.lostTicks = 0;
                g.lastKnown = acquired.position();
                steer(missile, acquired);
                continue;
            }

            g.lostTicks++;
            if (missile instanceof MissileProjectile mp) {
                mp.setTargetVec(g.lastKnown); // keep flying to the last known point
            }
            if (g.lostTicks > timeout) {
                missile.discard();
                it.remove();
            }
        }
    }

    private static void steer(Entity missile, Entity target) {
        if (missile instanceof MissileProjectile mp) {
            mp.setTargetUuid(target.getStringUUID());
            mp.setTargetVec(target.position());
            mp.setLost(false);
        }
    }

    /**
     * Active radar seeker: scan a forward cone around the missile's velocity inside
     * seekerRange and pick the best (smallest angle) non-friendly target.
     */
    private static Entity acquireTerminal(Entity missile, Guided g, double seekerRange) {
        Vec3 vel = missile.getDeltaMovement();
        if (vel.lengthSqr() < 1.0E-6) return null;
        Vec3 dir = vel.normalize();

        AABB box = missile.getBoundingBox().inflate(seekerRange);
        List<Entity> candidates = missile.level().getEntities(missile, box, e ->
                e.isAlive()
                        && !e.isSpectator()
                        && !e.getUUID().toString().equals(g.ownerUuid)
                        && (e instanceof VehicleEntity || e instanceof LivingEntity));

        Entity best = null;
        double bestDot = Math.cos(Math.toRadians(35.0)); // seeker half-angle
        for (Entity e : candidates) {
            if (isFriendly(missile, g, e)) continue;
            Vec3 to = e.getEyePosition().subtract(missile.position());
            double dist = to.length();
            if (dist < 1.0E-3 || dist > seekerRange) continue;
            double dot = to.normalize().dot(dir);
            if (dot > bestDot) {
                bestDot = dot;
                best = e;
            }
        }
        return best;
    }

    private static boolean isFriendly(Entity missile, Guided g, Entity candidate) {
        // never re-acquire the launching vehicle itself
        if (candidate.getId() == g.radarVehicleId) return true;

        if (!(missile.level() instanceof ServerLevel level)) return false;
        Player owner = level.getServer().getPlayerList()
                .getPlayer(java.util.UUID.fromString(g.ownerUuid));
        PlayerTeam ownerTeam = owner != null ? owner.getTeam() : null;
        if (ownerTeam == null) return false;

        if (candidate instanceof LivingEntity living && living.getTeam() == ownerTeam) return true;

        // vehicles: check the controlling passenger's team
        Entity controller = candidate.getControllingPassenger();
        if (controller instanceof Player p && p.getTeam() == ownerTeam) return true;
        return false;
    }

    /**
     * Chaff attempt: one roll per radar-guided missile inside the radius.
     * Success makes the missile lose the track (it keeps flying to the last known point).
     */
    public static int tryDecoy(net.minecraft.world.level.Level level, Vec3 pos,
                               double radius, double chance, String ownerUuid) {
        int defeated = 0;
        for (Iterator<Map.Entry<Entity, Guided>> it = GUIDED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Entity, Guided> e = it.next();
            Entity missile = e.getKey();
            Guided g = e.getValue();
            if (missile.isRemoved() || !missile.isAlive() || missile.level() != level) continue;
            if (g.ownerUuid.equals(ownerUuid)) continue; // do not defeat your own salvo
            if (missile.position().distanceTo(pos) > radius) continue;
            if (Math.random() >= chance) continue;
            if (missile instanceof MissileProjectile mp) {
                mp.setLost(true);
                mp.setTargetVec(g.lastKnown);
            }
            it.remove();
            defeated++;
        }
        return defeated;
    }

    /** Missiles currently guided by a radar (for diagnostics/HUD). */
    public static int activeFor(int vehicleId) {
        int n = 0;
        for (Guided g : GUIDED.values()) {
            if (g.radarVehicleId == vehicleId) n++;
        }
        return n;
    }

    private RadarMissileControl() {}
}
