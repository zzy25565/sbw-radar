package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.tools.RadarScanner;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Open API for the SBW Radar addon.
 *
 * Third-party addons can:
 *  - {@link #registerTargetFilter(RadarTargetFilter)}: veto or force-accept targets
 *    (return {@code false} to hide, {@code true} to force-show, {@code null} to defer
 *    to the next filter / default behaviour).
 *  - {@link #registerSourceProvider(RadarSourceProvider)}: supply radar configs for
 *    their own vehicles (e.g. non-AA vehicles, custom ranges/sectors).
 *
 * Detection of third-party aircraft/vehicles needs NO registration: scanning goes
 * through SBW's VehicleEntity hierarchy, so any addon vehicle/aircraft is picked up
 * automatically as long as it flies above the configured height window.
 */
public final class RadarApi {

    /** Veto/force hook applied to every candidate target. */
    @FunctionalInterface
    public interface RadarTargetFilter {
        /**
         * @return {@code false} = reject target, {@code true} = force-accept,
         *         {@code null} = no opinion (defer to default behaviour).
         */
        Boolean accept(VehicleRadarContext ctx, Entity target);
    }

    /** Lets other addons provide radar configs for their own vehicles. */
    @FunctionalInterface
    public interface RadarSourceProvider {
        /**
         * @return a ready {@link RadarScanner.RadarConfig} if this provider wants
         *         to run radar for the given vehicle, otherwise {@code null}.
         */
        RadarScanner.RadarConfig provide(VehicleRadarContext ctx);
    }

    /** Context passed to hooks. */
    public record VehicleRadarContext(ServerLevel level, Player driver, VehicleEntityRef vehicle) {
        public Vec3 center() {
            return vehicle.pos();
        }
    }

    /** Minimal vehicle view so hooks don't need to link against SBW classes. */
    public record VehicleEntityRef(int entityId, String vehicleTypeId, Vec3 pos, boolean hasNativeRadar) {}

    private static final List<RadarTargetFilter> TARGET_FILTERS = new CopyOnWriteArrayList<>();
    private static final List<RadarSourceProvider> SOURCE_PROVIDERS = new CopyOnWriteArrayList<>();

    public static void registerTargetFilter(RadarTargetFilter filter) {
        TARGET_FILTERS.add(filter);
    }

    public static void registerSourceProvider(RadarSourceProvider provider) {
        SOURCE_PROVIDERS.add(provider);
    }

    public static List<RadarTargetFilter> targetFilters() {
        return TARGET_FILTERS;
    }

    public static List<RadarSourceProvider> sourceProviders() {
        return SOURCE_PROVIDERS;
    }

    private RadarApi() {}
}
