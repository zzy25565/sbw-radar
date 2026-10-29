package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.RadarScanner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side radar mode machine.
 *
 *  SRC  sector search only: directional sweep, fast refresh, no tracking
 *  TWS  search while tracking: several targets, slow refresh
 *  TRK  single-target lock: narrow beam follows one target, fastest refresh
 *
 * Directional vehicles (Omni=false, e.g. the Buk launcher) sweep inside a fixed sector
 * centred on the hull heading - they physically cannot search all round.
 */
public final class ServerRadarHandler {

    private static final Set<Integer> PROCESSED_VEHICLES_THIS_TICK = new HashSet<>();
    private static final Map<Integer, VehicleState> STATES = new ConcurrentHashMap<>();

    private static final int STATE_SYNC_INTERVAL = 10;

    private static final class VehicleState {
        RadarMode mode;
        double widthDeg = -1;
        double verticalDeg = -1;
        int targetId = -1;
        double phase;
        List<Integer> lastHostiles = new ArrayList<>();
        int syncCooldown;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!SbwRadarConfig.SPEC.isLoaded()) return;

        try {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.players().isEmpty()) continue;
                PROCESSED_VEHICLES_THIS_TICK.clear();
                for (Player player : level.players()) {
                    handlePlayer(level, player);
                }
            }
            pruneDeadStates(server);
        } catch (Throwable t) {
            SbwRadarMod.LOGGER.warn("sbw_radar tick error", t);
        }
    }

    /** Drop states for vehicles that no longer exist (destroyed / unloaded / never respawned). */
    private static void pruneDeadStates(MinecraftServer server) {
        if (STATES.isEmpty()) return;
        STATES.keySet().removeIf(id -> {
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(id) != null) return false;
            }
            return true;
        });
    }

    private static void handlePlayer(ServerLevel level, Player player) {
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.isWreck()) return;
        if (!PROCESSED_VEHICLES_THIS_TICK.add(vehicle.getId())) return;

        RadarApi.VehicleRadarContext ctx = new RadarApi.VehicleRadarContext(
                level, player, new RadarApi.VehicleEntityRef(
                        vehicle.getId(),
                        vehicle.getVehicleType().name(),
                        vehicle.position(),
                        !vehicle.computed().getRadar().isEmpty()));

        // third-party providers first
        RadarScanner.RadarConfig provided = null;
        for (RadarApi.RadarSourceProvider provider : RadarApi.sourceProviders()) {
            try {
                provided = provider.provide(ctx);
            } catch (Throwable t) {
                SbwRadarMod.LOGGER.warn("radar source provider failed", t);
            }
            if (provided != null) break;
        }
        boolean airborne = false;
        if (provided == null) {
            if (ctx.vehicle().hasNativeRadar()) return;
            if (vehicle.getVehicleType() == VehicleType.AA) {
                // ground AA radar
            } else if (SbwRadarConfig.AIRBORNE_ENABLE.get()
                    && (vehicle.getVehicleType() == VehicleType.AIRPLANE
                        || vehicle.getVehicleType() == VehicleType.HELICOPTER)) {
                airborne = true; // nose-mounted airborne radar
            } else {
                return;
            }
        }

        String typePath = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).getPath();
        RadarProfiles.Profile profile = airborne
                ? RadarProfiles.getAirborne(level.getServer(), typePath,
                        SbwRadarConfig.AIRBORNE_SECTOR.get(), SbwRadarConfig.AIRBORNE_RANGE.get())
                : RadarProfiles.get(level.getServer(), typePath);
        VehicleState state = STATES.computeIfAbsent(vehicle.getId(), id -> {
            VehicleState s = new VehicleState();
            s.mode = profile.modes().get(0);
            s.widthDeg = profile.sectorDeg();
            return s;
        });
        if (state.widthDeg <= 0) state.widthDeg = airborne
                ? airborneSectors().get(airborneSectors().size() - 1)
                : profile.sectorDeg();
        if (!profile.modes().contains(state.mode)) {
            state.mode = profile.modes().get(0);
        }

        RadarScanner.RadarConfig config = buildConfig(level, player, vehicle, profile, state, airborne);
        RadarScanner.INSTANCE.sendRadarConfig(config, level);

        // 机载雷达：扫描角度越窄，刷新越快（间隔与宽度成正比）
        // TRK 用的是 8° 窄波束，不该跟着扇区宽度变慢，仍走 profile 的 trkInterval
        int interval = (airborne && state.mode != RadarMode.TRK)
                ? airborneInterval(state.widthDeg) : switch (state.mode) {
            case SRC -> Math.max(1, profile.srcInterval());
            case TWS -> Math.max(1, profile.twsInterval());
            case TRK -> Math.max(1, profile.trkInterval());
        };
        if (level.getServer().getTickCount() % interval == 0) {
            RadarScanner.ScanResult result = RadarScanner.INSTANCE.scan(level, config);
            result = applyVerticalLimit(level, vehicle, state, result);
            result.sendToClients(player, level, SbwRadarConfig.SHARE_WITH_TEAMMATES.get());
            state.lastHostiles = new ArrayList<>(result.getHostileIds());
        }

        if (--state.syncCooldown <= 0) {
            state.syncCooldown = STATE_SYNC_INTERVAL;
            Entity target = state.targetId >= 0 ? level.getEntity(state.targetId) : null;
            if (player instanceof ServerPlayer sp) {
                PacketDistributor.sendToPlayer(sp, new RadarPayloads.StateSync(
                        vehicle.getId(),
                        state.mode.name(),
                        target == null ? -1 : target.getId(),
                        target == null ? "" : target.getName().getString(),
                        state.lastHostiles.size(),
                        (int) Math.round(state.widthDeg > 0 ? state.widthDeg : 60.0),
                        RadarMissileControl.activeFor(vehicle.getId())));
            }
        }
    }

    private static RadarScanner.RadarConfig buildConfig(ServerLevel level, Player player, VehicleEntity vehicle,
                                                        RadarProfiles.Profile profile, VehicleState state,
                                                        boolean airborne) {
        Vec3 center = vehicle.position().add(0.0, 1.5, 0.0);
        // SBW radar yaw: 0 points to -Z (north). MC entity yaw: 0 points to +Z -> +180
        double hullYaw = vehicle.getYRot() + 180.0;

        double yRot;
        double sweep;
        switch (state.mode) {
            case SRC -> {
                sweep = profile.omni() ? SbwRadarConfig.SWEEP_ANGLE.get()
                        : (state.widthDeg > 0 ? state.widthDeg : profile.sectorDeg());
                if (profile.omni()) {
                    yRot = level.getServer().getTickCount() * profile.sweepSpeed();
                } else {
                    state.phase += 0.045 * profile.sweepSpeed();
                    yRot = hullYaw + Math.sin(state.phase) * (sweep / 2.0);
                }
            }
            case TWS -> {
                sweep = profile.omni() ? Math.min(360.0, profile.sectorDeg() * 1.5)
                        : (state.widthDeg > 0 ? state.widthDeg : profile.sectorDeg());
                if (profile.omni()) {
                    yRot = level.getServer().getTickCount() * profile.sweepSpeed() * 0.5;
                } else {
                    state.phase += 0.02 * profile.sweepSpeed();
                    yRot = hullYaw + Math.sin(state.phase) * (sweep / 2.0);
                }
            }
            default -> {
                sweep = 8.0;
                Entity target = state.targetId >= 0 ? level.getEntity(state.targetId) : null;
                if (target == null || !target.isAlive()) {
                    state.targetId = pickNearestHostile(level, vehicle, state.lastHostiles);
                    target = state.targetId >= 0 ? level.getEntity(state.targetId) : null;
                }
                if (target == null) {
                    state.targetId = -1;
                    yRot = hullYaw;
                } else {
                    yRot = bearingTo(center, target.position());
                }
            }
        }

        return new RadarScanner.RadarConfig(
                player,
                center,
                airborne ? SbwRadarConfig.AIRBORNE_RANGE.get() : SbwRadarConfig.RADAR_RANGE.get(),
                sweep,
                yRot,
                0.0, // external yaw control for every mode
                RadarScanner.SearchType.VEHICLES,
                SbwRadarConfig.MIN_TARGET_HEIGHT.get(),
                SbwRadarConfig.MAX_TARGET_HEIGHT.get(),
                SbwRadarConfig.SHARE_WITH_TEAMMATES.get(),
                false,
                "sbr_inject_" + vehicle.getId(),
                true);
    }

    private static int pickNearestHostile(ServerLevel level, VehicleEntity vehicle, List<Integer> candidates) {
        double best = Double.MAX_VALUE;
        int bestId = -1;
        for (int id : candidates) {
            Entity e = level.getEntity(id);
            if (e == null || !e.isAlive()) continue;
            double d = e.distanceToSqr(vehicle);
            if (d < best) {
                best = d;
                bestId = id;
            }
        }
        return bestId;
    }

    /**
     * Drop contacts outside the vertical scan cone.
     *
     * Elevation is measured from the radar boresight (vehicle pitch), so an aircraft whose nose
     * is pitched up keeps its cone pointed up. Ground vehicles sit near 0 deg, which makes this
     * equivalent to "above/below the horizon by at most verticalSweep degrees".
     */
    private static RadarScanner.ScanResult applyVerticalLimit(ServerLevel level, VehicleEntity vehicle,
                                                              VehicleState state,
                                                              RadarScanner.ScanResult result) {
        double ceiling = SbwRadarConfig.VERTICAL_SWEEP_MAX.get();
        double wanted = state.verticalDeg > 0 ? state.verticalDeg : SbwRadarConfig.VERTICAL_SWEEP.get();
        double limit = Math.max(1.0, Math.min(wanted, ceiling));
        Vec3 center = vehicle.position().add(0.0, 1.5, 0.0);
        double boresight = -vehicle.getXRot();            // MC: xRot<0 = nose up

        java.util.List<Integer> hostiles = filterByElevation(level, center, boresight, limit,
                result.getHostileIds());
        java.util.List<Integer> neutrals = filterByElevation(level, center, boresight, limit,
                result.getNeutralIds());
        if (hostiles == result.getHostileIds() && neutrals == result.getNeutralIds()) return result;
        return new RadarScanner.ScanResult(result.getDim(), hostiles, neutrals, result.getPlayerInfos());
    }

    private static java.util.List<Integer> filterByElevation(ServerLevel level, Vec3 center,
                                                             double boresight, double limit,
                                                             java.util.List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return ids;
        java.util.List<Integer> kept = new ArrayList<>(ids.size());
        for (int id : ids) {
            Entity e = level.getEntity(id);
            if (e == null) continue;
            Vec3 d = e.position().subtract(center);
            double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
            double elevation = Math.toDegrees(Math.atan2(d.y, horizontal));
            if (Math.abs(elevation - boresight) <= limit) kept.add(id);
        }
        return kept;
    }

    /** Vertical scan steps from config, ascending and capped by the configured ceiling. */
    public static java.util.List<Double> verticalSteps() {
        double ceiling = SbwRadarConfig.VERTICAL_SWEEP_MAX.get();
        java.util.List<Double> out = new java.util.ArrayList<>();
        for (String raw : SbwRadarConfig.VERTICAL_STEPS.get()) {
            try {
                double v = Double.parseDouble(raw.trim());
                if (v > 0 && v <= ceiling) out.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        if (out.isEmpty()) out.add(Math.min(45.0, ceiling));
        out.sort(Double::compare);
        return out;
    }

    /** Cycle the vertical scan range (in-game key). */
    public static void cycleVertical(Player player, int vehicleId) {
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.getId() != vehicleId) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        VehicleState state = STATES.computeIfAbsent(vehicleId, id -> {
            VehicleState s = new VehicleState();
            s.mode = RadarMode.SRC;
            return s;
        });
        java.util.List<Double> steps = verticalSteps();
        double cur = state.verticalDeg > 0 ? state.verticalDeg : SbwRadarConfig.VERTICAL_SWEEP.get();
        double next = steps.get(0);
        for (double s : steps) {
            if (s > cur + 1e-6) {
                next = s;
                break;
            }
        }
        state.verticalDeg = next;
        state.syncCooldown = 0;
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "雷达竖向范围: ±" + (int) Math.round(next) + "°"), true);
    }

    /** Fixed airborne sectors, ascending. */
    public static java.util.List<Double> airborneSectors() {
        java.util.List<Double> out = new java.util.ArrayList<>();
        for (String raw : SbwRadarConfig.AIRBORNE_SECTORS.get()) {
            try {
                out.add(Double.parseDouble(raw.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (out.isEmpty()) out.add(120.0);
        out.sort(Double::compare);
        return out;
    }

    /** Narrower sector -> shorter interval, linear against the widest sector. */
    public static int airborneInterval(double widthDeg) {
        java.util.List<Double> sectors = airborneSectors();
        double widest = sectors.get(sectors.size() - 1);
        int base = Math.max(1, SbwRadarConfig.AIRBORNE_REFRESH_BASE.get());
        double w = widthDeg > 0 ? Math.min(widthDeg, widest) : widest;
        return Math.max(1, (int) Math.round(base * (w / widest)));
    }

    /** Ground-vehicle width steps from config. */
    private static java.util.List<Double> widthSteps() {
        java.util.List<Double> steps = new java.util.ArrayList<>();
        for (String raw : SbwRadarConfig.WIDTH_STEPS.get()) {
            try {
                steps.add(Double.parseDouble(raw.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
        if (steps.isEmpty()) steps.add(60.0);
        return steps;
    }

    /** Bearing in SBW radar yaw space (0 = north / -Z). */
    public static double bearingTo(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        return (deg + 360.0) % 360.0;
    }

    // ── query API (used by the radar missile controller) ──

    /** Locked target entity id, or -1 when not in TRK / no lock. */
    public static int trackedTargetId(int vehicleId) {
        VehicleState s = STATES.get(vehicleId);
        if (s == null || s.mode != RadarMode.TRK) return -1;
        return s.targetId;
    }

    public static boolean isTracking(int vehicleId) {
        return trackedTargetId(vehicleId) >= 0;
    }

    /** Tracks from the latest scan (TWS picture); empty when the vehicle has no radar. */
    public static java.util.List<Integer> twsTracks(int vehicleId) {
        VehicleState s = STATES.get(vehicleId);
        return s == null ? java.util.List.of() : new java.util.ArrayList<>(s.lastHostiles);
    }

    public static RadarMode modeOf(int vehicleId) {
        VehicleState s = STATES.get(vehicleId);
        return s == null ? null : s.mode;
    }

    /** Vehicles currently holding a TRK lock (used by the split air-defence controller). */
    public static java.util.Set<Integer> trackedRadars() {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        STATES.forEach((id, s) -> {
            if (s.mode == RadarMode.TRK && s.targetId >= 0) out.add(id);
        });
        return out;
    }

    public static boolean isTracked(int vehicleId, int entityId) {
        VehicleState s = STATES.get(vehicleId);
        return s != null && s.lastHostiles.contains(entityId);
    }

    // ── client requests ──

    public static void cycleMode(Player player, int vehicleId) {
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.getId() != vehicleId) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        String typePath = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).getPath();
        boolean isAir = vehicle.getVehicleType() == VehicleType.AIRPLANE
                || vehicle.getVehicleType() == VehicleType.HELICOPTER;
        RadarProfiles.Profile profile = isAir
                ? RadarProfiles.getAirborne(level.getServer(), typePath,
                        SbwRadarConfig.AIRBORNE_SECTOR.get(), SbwRadarConfig.AIRBORNE_RANGE.get())
                : RadarProfiles.get(level.getServer(), typePath);
        VehicleState state = STATES.computeIfAbsent(vehicleId, id -> {
            VehicleState s = new VehicleState();
            s.mode = profile.modes().get(0);
            return s;
        });
        state.mode = state.mode.next(profile.modes());
        if (state.mode != RadarMode.TRK) state.targetId = -1;
        state.syncCooldown = 0;
        player.displayClientMessage(
                net.minecraft.network.chat.Component.literal("雷达模式: " + state.mode.name()), true);
    }

    public static void cycleWidth(Player player, int vehicleId) {
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.getId() != vehicleId) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        String typePath = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).getPath();
        RadarProfiles.Profile profile = RadarProfiles.get(level.getServer(), typePath);
        VehicleState state = STATES.computeIfAbsent(vehicleId, id -> {
            VehicleState s = new VehicleState();
            s.mode = profile.modes().get(0);
            s.widthDeg = profile.sectorDeg();
            return s;
        });
        boolean isAir = vehicle.getVehicleType() == VehicleType.AIRPLANE
                || vehicle.getVehicleType() == VehicleType.HELICOPTER;
        java.util.List<Double> steps = isAir ? airborneSectors() : widthSteps();
        double cur = state.widthDeg > 0 ? state.widthDeg : steps.get(0);
        int idx = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < steps.size(); i++) {
            double d = Math.abs(steps.get(i) - cur);
            if (d < best) {
                best = d;
                idx = i;
            }
        }
        state.widthDeg = steps.get((idx + 1) % steps.size());
        state.syncCooldown = 0;
        String suffix = isAir
                ? "（" + airborneInterval(state.widthDeg) + " tick 刷新）" : "";
        player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                "雷达扫描宽度: " + (int) Math.round(state.widthDeg) + "°" + suffix), true);
    }

    public static void cycleTarget(Player player, int vehicleId) {
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.getId() != vehicleId) return;
        if (!(player.level() instanceof ServerLevel level)) return;
        VehicleState state = STATES.get(vehicleId);
        if (state == null || state.mode != RadarMode.TRK) return;

        List<Integer> pool = new ArrayList<>();
        for (int id : state.lastHostiles) {
            Entity e = level.getEntity(id);
            if (e != null && e.isAlive()) pool.add(id);
        }
        if (pool.isEmpty()) return;
        int idx = pool.indexOf(state.targetId);
        state.targetId = pool.get((idx + 1) % pool.size());
        state.syncCooldown = 0;
    }

    private ServerRadarHandler() {}
}
