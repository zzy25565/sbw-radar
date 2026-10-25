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
    private static final Map<Integer, Double> TRACKED = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof Projectile p)) return;
        if (event.getLevel().isClientSide()) return;

        String id = BuiltInRegistries.ENTITY_TYPE.getKey(p.getType()).getPath();
        Double radius = radiusFor(id);
        if (radius != null) {
            TRACKED.put(p.getId(), radius);
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SbwRadarConfig.PROX_ENABLE.get() || TRACKED.isEmpty()) return;

        for (var level : event.getServer().getAllLevels()) {
            if (level.players().isEmpty()) continue;
            for (int id : TRACKED.keySet()) {
                Entity e = level.getEntity(id);
                if (!(e instanceof Projectile missile) || !missile.isAlive()) {
                    TRACKED.remove(id);
                    continue;
                }
                Double radius = TRACKED.get(id);
                if (radius == null) continue;
                check(level, missile, radius);
            }
        }
    }

    private static void check(ServerLevel level, Projectile missile, double radius) {
        Vec3 pos = missile.position();
        AABB box = new AABB(pos, pos).inflate(radius);

        for (Entity target : level.getEntities(missile, box, AaProximityFuze::isAirborne)) {
            if (target == missile.getOwner()) continue;
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
        float explosion = (float) (radius * 0.9);

        level.explode(missile, pos.x, pos.y, pos.z, explosion,
                Level.ExplosionInteraction.MOB);
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, pos.x, pos.y, pos.z, 2, 0.3, 0.3, 0.3, 0);
        level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.GENERIC_EXPLODE.value(),
                SoundSource.PLAYERS, 1.0F, 1.0F);
        missile.discard();
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
