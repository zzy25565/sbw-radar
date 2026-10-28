package com.zzy.sbwradar;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proximity fuze for anti-air missiles.
 *
 * A direct hit is not required: when an AA missile comes inside its fuze radius of an
 * airborne target it detonates on its own. Heavy systems (Buk, IRIS-T class) carry a large
 * warhead and therefore a wide fuze; MANPADS class missiles get a tight one.
 *
 * Only missiles of the configured types are tracked, so the per-tick cost stays flat no
 * matter how many entities are in the world.
 */
public final class AaProximityFuze {

    /** entity id -> fuze radius (blocks) */
    private static final Map<Projectile, Double> TRACKED = new java.util.WeakHashMap<>();

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Projectile p)) return;
        if (event.getLevel().isClientSide()) return;

        String id = BuiltInRegistries.ENTITY_TYPE.getKey(p.getType()).getPath();
        Double radius = radiusFor(id);
        if (managed(p)) radius = Math.min(SbwRadarConfig.PROX_HEAVY_RADIUS.get(),
                ((com.atsuishio.superbwarfare.entity.projectile.IBulletProperties)p).getExplosionRadius());
        if (radius != null) {
            TRACKED.put(p, radius);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SbwRadarConfig.PROX_ENABLE.get() || TRACKED.isEmpty()) return;

        for (var entry : new java.util.ArrayList<>(TRACKED.entrySet())) {
            Projectile missile=entry.getKey();
            if(missile.isRemoved() || !missile.isAlive()) { TRACKED.remove(missile); continue; }
            if(missile.level() instanceof ServerLevel level) check(level,missile,entry.getValue());
        }
    }

    private static void check(ServerLevel level, Projectile missile, double radius) {
        Vec3 pos = missile.position();
        AABB box = new AABB(pos, pos).inflate(radius);

        for (Entity target : level.getEntities(missile, box, AaProximityFuze::isAirborne)) {
            if (target == missile.getOwner()) continue;
            if (managed(missile) && !managedCandidate(level,missile,target,radius)) continue;
            detonate(level, missile, radius);
            return;
        }
    }

    /** alive, not a projectile, and genuinely in the air (nothing solid just below) */
    private static boolean isAirborne(Entity e) {
        if (!e.isAlive() || e instanceof Projectile) return false;
        if (e.onGround()) return false;
        AABB below = e.getBoundingBox().move(0.0, -1.2, 0.0).inflate(0.05, 0.0, 0.05);
        boolean anySolid = e.level().getBlockStates(below).anyMatch(s -> !s.isAir());
        return !anySolid;
    }

    private static void detonate(ServerLevel level, Projectile missile, double radius) {
        Vec3 pos = missile.position();
        if (managed(missile) && missile instanceof com.atsuishio.superbwarfare.entity.projectile.FastThrowableProjectile fast) {
            // Use the ammo's SBW blast damage/radius, including loadout balance changes.
            fast.causeExplode(pos);
            missile.discard();
            return;
        }
        float explosion = (float) (radius * 0.9);

        level.explode(missile, pos.x, pos.y, pos.z, explosion,
                Level.ExplosionInteraction.MOB);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 2, 0.3, 0.3, 0.3, 0);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.PLAYERS, 1.0F, 1.0F);
        missile.discard();
    }

    private static boolean managed(Projectile p) {
        String mode=p.getPersistentData().getCompound("SBWLoadoutSeeker").getString("Mode");
        return (mode.equals("RADAR") || p.getPersistentData().contains("SBWRadarLoadoutIR"))
                && p instanceof com.atsuishio.superbwarfare.entity.projectile.IBulletProperties;
    }

    private static boolean managedCandidate(ServerLevel level,Projectile p,Entity target,double radius) {
        var n=p.getPersistentData().getCompound(p.getPersistentData().contains("SBWRadarLoadoutIR")
                ? "SBWRadarLoadoutIR" : "SBWLoadoutSeeker");
        if(p.getPersistentData().contains("SBWRadarLoadoutIR")
                && p instanceof com.atsuishio.superbwarfare.entity.projectile.MissileProjectile mp
                && mp.isDistracted()) return false;
        if(n.getBoolean("Decoyed") || n.getString("Phase").equals("DECOYED")) return false;
        if(!(target instanceof net.minecraft.world.entity.LivingEntity)
                && !(target instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)) return false;
        if(target.isSpectator() || n.hasUUID("Source") && target.getUUID().equals(n.getUUID("Source"))) return false;
        Entity owner=p.getOwner();
        if(owner!=null && (owner.isAlliedTo(target) || target.getPassengers().stream()
                .anyMatch(e->e==owner || owner.isAlliedTo(e)))) return false;
        if(n.hasUUID("Source") && target.getRootVehicle().getUUID().equals(n.getUUID("Source"))) return false;
        String team=n.getString("OwnerTeam");
        if(!team.isEmpty() && (target.getTeam()!=null && target.getTeam().getName().equals(team)
                || target.getPassengers().stream().anyMatch(e->e.getTeam()!=null && e.getTeam().getName().equals(team)))) return false;
        Vec3 closest=new Vec3(net.minecraft.util.Mth.clamp(p.getX(),target.getBoundingBox().minX,target.getBoundingBox().maxX),
                net.minecraft.util.Mth.clamp(p.getY(),target.getBoundingBox().minY,target.getBoundingBox().maxY),
                net.minecraft.util.Mth.clamp(p.getZ(),target.getBoundingBox().minZ,target.getBoundingBox().maxZ));
        if(p.position().distanceToSqr(closest)>radius*radius) return false;
        var hit=level.clip(new net.minecraft.world.level.ClipContext(p.position(),target.getBoundingBox().getCenter(),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,p));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS;
    }

    private static Double radiusFor(String id) {
        if (SbwRadarConfig.PROX_HEAVY_MISSILES.get().contains(id)) {
            return SbwRadarConfig.PROX_HEAVY_RADIUS.get();
        }
        if (SbwRadarConfig.PROX_LIGHT_MISSILES.get().contains(id)) {
            return SbwRadarConfig.PROX_LIGHT_RADIUS.get();
        }
        return null;
    }

    private AaProximityFuze() {}
}
