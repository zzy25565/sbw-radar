package com.zzy.sbwradar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ammo definitions for the radar addon, shipped as data inside this mod:
 *
 *   data/sbw_radar/ammo/specs.json
 *
 * Every entry describes one ammo (matched by the projectile/item id that the weapon fires)
 * and how the addon should guide it. Nothing about ammo lives in the vehicle addon, so a
 * server can retune or add missiles without touching code or the vehicle pack.
 *
 * Schema (all fields except "category" are optional, defaults come from the server config):
 * {
 *   "ammo": {
 *     "superbwarfare:ru_9m336": {
 *       "category": "RADAR",            // RADAR | IR
 *       "missileEntity": "superbwarfare:ru_9m336",
 *       "seekerRange": 96,              // terminal handover distance
 *       "climbTicks": 12,               // boost phase length
 *       "climbUpDeg": 10,               // upward bias on the illumination axis
 *       "maxPerRadar": 2,               // illumination channels
 *       "flareChance": 0.6,             // IR only
 *       "requireLock": true             // IR only
 *     }
 *   }
 * }
 */
public final class AmmoSpecs {

    public enum Category { RADAR, IR }

    public record Spec(String id, Category category, String missileEntity,
                       double seekerRange, int climbTicks, double climbUpDeg,
                       int maxPerRadar, double flareChance, boolean requireLock) {}

    private static final ResourceLocation FILE =
            ResourceLocation.fromNamespaceAndPath(SbwRadarMod.MODID, "ammo/specs.json");

    private static Map<String, Spec> specs = null;
    private static boolean failed = false;

    /** Look up by projectile id, then fall back to a suffix match (ids often carry a prefix). */
    public static Spec find(MinecraftServer server, String ammoId) {
        ensureLoaded(server);
        if (specs == null || ammoId == null) return null;
        String low = ammoId.toLowerCase();
        Spec exact = specs.get(low);
        if (exact != null) return exact;
        for (Map.Entry<String, Spec> e : specs.entrySet()) {
            String key = e.getKey();
            int colon = key.indexOf(':');
            String path = colon >= 0 ? key.substring(colon + 1) : key;
            if (low.contains(path)) return e.getValue();
        }
        return null;
    }

    public static Map<String, Spec> all(MinecraftServer server) {
        ensureLoaded(server);
        return specs == null ? Map.of() : specs;
    }

    public static void invalidate() {
        specs = null;
        failed = false;
    }

    private static void ensureLoaded(MinecraftServer server) {
        if (specs != null || failed || server == null) return;
        Map<String, Spec> map = new LinkedHashMap<>();
        try {
            Optional<Resource> res = server.getResourceManager().getResource(FILE);
            if (res.isEmpty()) {
                specs = map; // empty table: controllers fall back to config defaults
                return;
            }
            try (InputStreamReader r = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                JsonObject ammo = root.has("ammo") ? root.getAsJsonObject("ammo") : new JsonObject();
                for (String id : ammo.keySet()) {
                    JsonObject o = ammo.getAsJsonObject(id);
                    Category cat;
                    try {
                        cat = Category.valueOf(o.has("category")
                                ? o.get("category").getAsString().toUpperCase() : "RADAR");
                    } catch (IllegalArgumentException ex) {
                        cat = Category.RADAR;
                    }
                    map.put(id.toLowerCase(), new Spec(
                            id,
                            cat,
                            str(o, "missileEntity", null),
                            dbl(o, "seekerRange", 96.0),
                            i32(o, "climbTicks", 12),
                            dbl(o, "climbUpDeg", 10.0),
                            i32(o, "maxPerRadar", 2),
                            dbl(o, "flareChance", 0.6),
                            bool(o, "requireLock", true)));
                }
            }
            specs = map;
        } catch (Throwable t) {
            failed = true;
            SbwRadarMod.LOGGER.warn("ammo/specs.json load failed", t);
        }
    }

    private static String str(JsonObject o, String k, String def) {
        return o.has(k) ? o.get(k).getAsString() : def;
    }

    private static double dbl(JsonObject o, String k, double def) {
        return o.has(k) ? o.get(k).getAsDouble() : def;
    }

    private static int i32(JsonObject o, String k, int def) {
        return o.has(k) ? o.get(k).getAsInt() : def;
    }

    private static boolean bool(JsonObject o, String k, boolean def) {
        return o.has(k) ? o.get(k).getAsBoolean() : def;
    }

    /** Helper for tooling: list ids grouped by category. */
    public static List<String> idsByCategory(MinecraftServer server, Category cat) {
        List<String> out = new ArrayList<>();
        for (Spec s : all(server).values()) {
            if (s.category() == cat) out.add(s.id());
        }
        return out;
    }

    private AmmoSpecs() {}
}
