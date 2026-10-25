package com.zzy.sbwradar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-vehicle radar profiles (directional antennas, allowed modes, refresh rates).
 * Loaded server-side from assets/sbw_radar/radar_profiles.json, keyed by entity type path.
 * Vehicles without an entry fall back to the omni default profile.
 */
public final class RadarProfiles {

    public record Profile(boolean omni, double sectorDeg, List<RadarMode> modes,
                          int srcInterval, int twsInterval, int trkInterval, double sweepSpeed) {}

    private static final Profile DEFAULT =
            new Profile(true, 120.0, List.of(RadarMode.SRC, RadarMode.TWS, RadarMode.TRK), 4, 20, 1, 1.5);

    private static final ResourceLocation FILE =
            ResourceLocation.fromNamespaceAndPath(SbwRadarMod.MODID, "radar_profiles.json");

    private static Map<String, Profile> profiles = null;
    private static boolean failed = false;

    public static Profile get(MinecraftServer server, String entityTypePath) {
        ensureLoaded(server);
        return profiles != null && profiles.containsKey(entityTypePath) ? profiles.get(entityTypePath) : DEFAULT;
    }

    /** Airborne (nose-mounted) profile: forward sector only, faster TWS refresh. */
    public static Profile getAirborne(MinecraftServer server, String entityTypePath,
                                      double sectorDeg, double rangeIgnored) {
        ensureLoaded(server);
        if (profiles != null && profiles.containsKey(entityTypePath + "_air")) {
            return profiles.get(entityTypePath + "_air");
        }
        if (profiles != null && profiles.containsKey(entityTypePath)) {
            Profile p = profiles.get(entityTypePath);
            // reuse allowed modes / intervals but clamp to a forward sector
            return new Profile(false, sectorDeg, p.modes(), p.srcInterval(), p.twsInterval(),
                    p.trkInterval(), p.sweepSpeed());
        }
        return new Profile(false, sectorDeg,
                List.of(RadarMode.SRC, RadarMode.TWS, RadarMode.TRK), 4, 16, 1, 1.6);
    }

    public static Profile getDefault() {
        return DEFAULT;
    }

    /** Called on server stopping so a restart re-reads the JSON. */
    public static void invalidate() {
        profiles = null;
        failed = false;
    }

    private static void ensureLoaded(MinecraftServer server) {
        if (profiles != null || failed || server == null) return;
        Map<String, Profile> map = new HashMap<>();
        try {
            Optional<Resource> res = server.getResourceManager().getResource(FILE);
            if (res.isEmpty()) {
                failed = true;
                return;
            }
            try (InputStreamReader r = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                JsonObject vehicles = root.has("vehicles") ? root.getAsJsonObject("vehicles") : new JsonObject();
                for (String key : vehicles.keySet()) {
                    JsonObject o = vehicles.getAsJsonObject(key);
                    boolean omni = !o.has("Omni") || o.get("Omni").getAsBoolean();
                    double sector = o.has("Sector") ? o.get("Sector").getAsDouble() : 120.0;
                    double speed = o.has("SweepSpeed") ? o.get("SweepSpeed").getAsDouble() : 1.5;
                    int src = o.has("SrcInterval") ? o.get("SrcInterval").getAsInt() : 4;
                    int tws = o.has("TwsInterval") ? o.get("TwsInterval").getAsInt() : 20;
                    int trk = o.has("TrkInterval") ? o.get("TrkInterval").getAsInt() : 1;
                    List<RadarMode> modes = new ArrayList<>();
                    if (o.has("Modes")) {
                        for (var m : o.getAsJsonArray("Modes")) {
                            try {
                                modes.add(RadarMode.valueOf(m.getAsString().toUpperCase()));
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                    if (modes.isEmpty()) modes = List.of(RadarMode.SRC, RadarMode.TWS, RadarMode.TRK);
                    map.put(key, new Profile(omni, sector, modes, src, tws, trk, speed));
                }
            }
            profiles = map;
        } catch (Throwable t) {
            failed = true;
            SbwRadarMod.LOGGER.warn("radar_profiles.json load failed", t);
        }
    }

    private RadarProfiles() {}
}
