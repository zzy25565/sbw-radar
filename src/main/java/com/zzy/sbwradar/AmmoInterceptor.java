package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.entity.projectile.DestroyableProjectile;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Ammunition interception for small air-defence vehicles (ZSU-23-4, Tunguska, ...).
 *
 * Large launcher systems (Buk and friends) are explicitly out of scope: their whole point
 * is stand-off engagement, so giving them a last-ditch gun layer would make them immune
 * to saturation. Small AA vehicles, on the other hand, already are last-ditch platforms.
 *
 * Mechanics:
 *  - every tick the vehicle looks for incoming projectiles that are closing on it and
 *    inside the configured radius;
 *  - a burst costs one cooldown cycle and can engage up to {@code perBurst} threats;
 *  - each threat is destroyed (or damaged) with a visible tracer + impact effect;
 *  - the vehicle must be crewed, otherwise nothing runs.
 */
public final class AmmoInterceptor {

    private static final Map<Integer, Integer> COOLDOWN = new HashMap<>();
    /** interceptor entity id -> vehicle entity id, for the HUD */
    private static final Map<Integer, Integer> ACTIVE = new HashMap<>();

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SbwRadarConfig.INTERCEPT_ENABLE.get()) return;
        int cdTicks = SbwRadarConfig.INTERCEPT_COOLDOWN.get();

        for (var level : event.getServer().getAllLevels()) {
            if (level.players().isEmpty()) continue;
            for (Player player : level.players()) {
                if (!(player.getVehicle() instanceof VehicleEntity v) || v.isWreck()) continue;
                if (!isInterceptor(v)) continue;

                int cd = COOLDOWN.merge(v.getId(), 0, (a, b) -> a);
                if (cd > 0) {
                    COOLDOWN.put(v.getId(), cd - 1);
                    continue;
                }

                List<Entity> threats = findThreats(level, v);
                if (threats.isEmpty()) continue;

                COOLDOWN.put(v.getId(), cdTicks);
                fire(level, v, threats);
            }
        }
    }

    private static List<Entity> findThreats(ServerLevel level, VehicleEntity v) {
        double radius = SbwRadarConfig.INTERCEPT_RADIUS.get();
        AABB box = v.getBoundingBox().inflate(radius);
        Vec3 center = v.position().add(0.0, 1.2, 0.0);

        return level.getEntities(v, box, e -> e instanceof Projectile && e.isAlive()).stream()
                .filter(e -> {
                    Vec3 rel = e.position().subtract(center);
                    double dist = rel.length();
                    if (dist < 2.0 || dist > radius) return false;
                    double closing = e.getDeltaMovement().dot(rel.normalize().scale(-1));
                    return closing > 0;
                })
                .sorted((a, b) -> Double.compare(a.distanceTo(v), b.distanceTo(v)))
                .limit(Math.max(1, SbwRadarConfig.INTERCEPT_PER_BURST.get()))
                .map(e -> (Entity) e)
                .toList();
    }

    private static void fire(ServerLevel level, VehicleEntity v, List<Entity> threats) {
        Vec3 muzzle = v.position().add(0.0, 2.2, 0.0);
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.0F, 1.6F);

        for (Entity t : threats) {
            // tracer line from the vehicle to the threat
            Vec3 to = t.position().subtract(muzzle);
            int steps = Math.max(4, (int) (to.length() * 1.5));
            for (int i = 0; i < steps; i++) {
                double f = (double) i / steps;
                level.sendParticles(ParticleTypes.CRIT,
                        muzzle.x + to.x * f, muzzle.y + to.y * f, muzzle.z + to.z * f,
                        1, 0, 0, 0, 0);
            }

            if (t instanceof DestroyableProjectile dp) {
                dp.setHealth(0.0F);
                dp.discard();
            } else {
                t.discard();
            }
            level.sendParticles(ParticleTypes.EXPLOSION, t.getX(), t.getY(), t.getZ(), 2, 0.2, 0.2, 0.2, 0);
            ACTIVE.put(v.getId(), v.getId());
        }
    }

    /** true when this vehicle type is allowed to intercept (small AA only). */
    public static boolean isInterceptor(VehicleEntity v) {
        List<? extends String> list = SbwRadarConfig.INTERCEPT_VEHICLES.get();
        if (list.isEmpty()) return false;
        if (list.contains("*")) return true;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(v.getType());
        return list.contains(id.toString()) || list.contains(id.getPath());
    }

    /** Remaining cooldown in ticks, or 0 when ready. */
    public static int cooldown(int vehicleId) {
        return COOLDOWN.getOrDefault(vehicleId, 0);
    }

    /** True right after a burst, so the HUD can flash a confirmation. */
    public static boolean justFired(int vehicleId) {
        return ACTIVE.containsKey(vehicleId);
    }

    public static void clearFlash(int vehicleId) {
        ACTIVE.remove(vehicleId);
    }

    private AmmoInterceptor() {}
}
