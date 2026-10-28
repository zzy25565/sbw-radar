package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Split air defence: 雷达车指挥半径内的发射车开火.
 *
 * <p>Many AA systems are two vehicles - a radar/command vehicle and a launcher vehicle.
 * SBW treats them as independent entities, so a launcher on its own has no lock. This
 * controller closes that gap on the server:
 *
 * <ol>
 *   <li>a radar vehicle holds a TRK lock ({@link ServerRadarHandler#trackedTargetId});</li>
 *   <li>every {@code split.interval} ticks it looks for launcher vehicles inside
 *       {@code split.radius} (default 100 blocks) that carry missile-class ammo
 *       ({@link RadarMissileControl#missileGun});</li>
 *   <li>each eligible launcher is ordered to fire at the radar's lock through the same
 *       guided-missile path the player uses ({@link RadarMissileControl#launchFromLauncher});</li>
 *   <li>the launcher then goes on {@code split.cooldown}.</li>
 * </ol>
 *
 * <p>The missile is spawned at the <b>launcher</b> but keeps the radar vehicle as its
 * illumination/datalink owner, so channel limits and mid-course updates stay with the radar.
 *
 * <p>Manned launchers are skipped by default ({@code split.onlyUnmanned}) so a player inside
 * keeps full manual control.
 */
public final class SplitAirDefense {

    /** launcher entity id -> server tick at which it may fire again */
    private static final Map<Integer, Integer> READY_AT = new ConcurrentHashMap<>();

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SbwRadarConfig.SPEC.isLoaded()) return;
        if (!SbwRadarConfig.SPLIT_AA_ENABLE.get()) return;
        if (!SbwRadarConfig.MISSILE_ENABLE.get()) return;

        MinecraftServer server = event.getServer();
        int now = server.getTickCount();
        int interval = Math.max(1, SbwRadarConfig.SPLIT_AA_INTERVAL.get());
        try {
            if (now % interval == 0) {
                for (ServerLevel level : server.getAllLevels()) {
                    if (level.players().isEmpty()) continue;
                    tickLevel(level, now);
                }
            }
            pruneDead(server);
        } catch (Throwable t) {
            SbwRadarMod.LOGGER.warn("split AA tick error", t);
        }
    }

    private static void tickLevel(ServerLevel level, int now) {
        double radius = SbwRadarConfig.SPLIT_AA_RADIUS.get();
        int cooldown = Math.max(1, SbwRadarConfig.SPLIT_AA_COOLDOWN.get());
        boolean onlyUnmanned = SbwRadarConfig.SPLIT_AA_ONLY_UNMANNED.get();
        double radiusSq = radius * radius;

        for (int radarId : ServerRadarHandler.trackedRadars()) {
            if (!(level.getEntity(radarId) instanceof VehicleEntity radar) || radar.isWreck()) continue;

            int targetId = ServerRadarHandler.trackedTargetId(radarId);
            if (targetId < 0) continue;
            Entity target = level.getEntity(targetId);
            if (target == null || !target.isAlive()) continue;

            int fired = 0;
            for (VehicleEntity launcher : level.getEntitiesOfClass(
                    VehicleEntity.class, radar.getBoundingBox().inflate(radius))) {
                if (launcher == radar || launcher.isWreck() || !launcher.isAlive()) continue;
                if (launcher.distanceToSqr(radar) > radiusSq) continue;
                if (onlyUnmanned && launcher.getFirstPassenger() != null) continue;

                Integer readyAt = READY_AT.get(launcher.getId());
                if (readyAt != null && now < readyAt) continue;

                if (RadarMissileControl.launchFromLauncher(launcher, radar, target)) {
                    READY_AT.put(launcher.getId(), now + cooldown);
                    fired++;
                }
            }

            if (fired > 0 && radar.getFirstPassenger() instanceof ServerPlayer driver) {
                driver.displayClientMessage(
                        Component.literal("发射车 ×" + fired + " 已开火"), true);
            }
        }
    }

    private static void pruneDead(MinecraftServer server) {
        if (READY_AT.isEmpty()) return;
        READY_AT.keySet().removeIf(id -> {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(id) != null) return false;
            }
            return true;
        });
    }

    private SplitAirDefense() {}
}
