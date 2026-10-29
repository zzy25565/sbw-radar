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
 * 分体式防空 / 自主发射: 雷达车锁定目标后，自己与半径内的发射车自动开火.
 *
 * <p>Many AA systems are two vehicles - a radar/command vehicle and a launcher vehicle.
 * SBW treats them as independent entities, so a launcher on its own has no lock. This
 * controller closes that gap on the server:
 *
 * <ol>
 *   <li>a radar vehicle holds a TRK lock ({@link ServerRadarHandler#trackedTargetId});</li>
 *   <li>the radar vehicle fires its <b>own</b> missiles when it carries any
 *       ({@code split.selfFire}, default on) - the driver only has to keep the lock,
 *       no trigger pull needed;</li>
 *   <li>every {@code split.interval} ticks it also orders launcher vehicles inside
 *       {@code split.radius} (default 100 blocks) that carry missile-class ammo to fire;</li>
 *   <li>each unit then goes on {@code split.cooldown}.</li>
 * </ol>
 *
 * <p>The missile is spawned at whoever launches it, but the radar vehicle stays the
 * illumination/datalink owner, so channel limits and mid-course updates follow the radar.
 *
 * <p>Manned launchers are skipped by default ({@code split.onlyUnmanned}) so a player inside
 * keeps manual control. The radar vehicle itself is exempt: the driver is the one holding
 * the lock, so auto-firing it is the expected behaviour.
 */
public final class SplitAirDefense {

    /** launcher entity id -> server tick at which it may fire again */
    private static final Map<Integer, Integer> READY_AT = new ConcurrentHashMap<>();

    /** how often the "why nothing fired" hint may be repeated (ticks) */
    private static final int REPORT_INTERVAL = 40;

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
        double radiusSq = radius * radius;
        int cooldown = Math.max(1, SbwRadarConfig.SPLIT_AA_COOLDOWN.get());
        boolean onlyUnmanned = SbwRadarConfig.SPLIT_AA_ONLY_UNMANNED.get();
        boolean selfFire = SbwRadarConfig.SPLIT_AA_SELF_FIRE.get();
        boolean report = SbwRadarConfig.SPLIT_AA_REPORT.get();

        for (int radarId : ServerRadarHandler.trackedRadars()) {
            if (!(level.getEntity(radarId) instanceof VehicleEntity radar) || radar.isWreck()) continue;
            // Custom aircraft loadouts keep manual launch, inventory and their own cooldown.
            if (LoadoutIntegration.ownsVehicle(radar)) continue;

            int targetId = ServerRadarHandler.trackedTargetId(radarId);
            if (targetId < 0) continue;
            Entity target = level.getEntity(targetId);
            if (target == null || !target.isAlive()) continue;

            int fired = 0;
            boolean sawLauncher = false;
            boolean sawLoaded = false;
            boolean blockedManned = false;
            boolean blockedCooldown = false;

            // ── 1. 雷达车自身 ──
            if (selfFire) {
                Integer readyAt = READY_AT.get(radar.getId());
                boolean ready = readyAt == null || now >= readyAt;
                if (ready && RadarMissileControl.missileGun(radar) != null) {
                    if (RadarMissileControl.launchFromLauncher(radar, radar, target)) {
                        READY_AT.put(radar.getId(), now + cooldown);
                        fired++;
                    }
                } else if (!ready) {
                    blockedCooldown = true;
                }
            }

            // ── 2. 半径内的发射车 ──
            for (VehicleEntity launcher : level.getEntitiesOfClass(
                    VehicleEntity.class, radar.getBoundingBox().inflate(radius))) {
                if (launcher == radar || launcher.isWreck() || !launcher.isAlive()) continue;
                if (LoadoutIntegration.ownsVehicle(launcher)) continue;
                if (launcher.distanceToSqr(radar) > radiusSq) continue;
                sawLauncher = true;

                if (RadarMissileControl.missileGun(launcher) == null) continue;
                sawLoaded = true;

                if (onlyUnmanned && launcher.getFirstPassenger() != null) {
                    blockedManned = true;
                    continue;
                }
                Integer readyAt = READY_AT.get(launcher.getId());
                if (readyAt != null && now < readyAt) {
                    blockedCooldown = true;
                    continue;
                }
                if (RadarMissileControl.launchFromLauncher(launcher, radar, target)) {
                    READY_AT.put(launcher.getId(), now + cooldown);
                    fired++;
                }
            }

            if (!(radar.getFirstPassenger() instanceof ServerPlayer driver)) continue;

            if (fired > 0) {
                driver.displayClientMessage(Component.literal("自主发射 ×" + fired), true);
            } else if (report && now % REPORT_INTERVAL == 0) {
                String why;
                if (!sawLauncher && !selfFire) {
                    why = "半径 " + (int) radius + " 格内没有载具";
                } else if (!sawLauncher && selfFire) {
                    why = "本车无导弹，且半径内没有其它载具";
                } else if (!sawLoaded) {
                    why = "半径内载具都没装导弹";
                } else if (blockedManned && onlyUnmanned) {
                    why = "发射车有乘员（split.onlyUnmanned=true）";
                } else if (blockedCooldown) {
                    why = "发射单位冷却中";
                } else {
                    why = "没有可发射单位";
                }
                driver.displayClientMessage(Component.literal("锁定中，未开火：" + why), true);
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
