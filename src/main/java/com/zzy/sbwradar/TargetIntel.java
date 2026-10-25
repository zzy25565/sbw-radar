package com.zzy.sbwradar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tactical-map intel: target classification labels.
 *
 *  - Aircraft: engine count (single / twin / multi) from target_intel.json, keyed by entity type path.
 *  - Living entities: health band (<30 / 30-50 / 50-100 / >100).
 *
 * Third-party addons can ship their own entries into target_intel.json (paths are global).
 */
public final class TargetIntel {

    private static final ResourceLocation INTEL_FILE =
            ResourceLocation.fromNamespaceAndPath(SbwRadarMod.MODID, "target_intel.json");

    private static Map<String, Integer> ENGINES = null;
    private static boolean loadFailed = false;

    private static void ensureLoaded() {
        if (ENGINES != null || loadFailed) return;
        ENGINES = new HashMap<>();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.getResourceManager() == null) return;
            Optional<Resource> res = mc.getResourceManager().getResource(INTEL_FILE);
            if (res.isEmpty()) {
                loadFailed = true;
                return;
            }
            try (InputStreamReader r = new InputStreamReader(res.get().open(), StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                JsonObject vehicles = root.has("vehicles") ? root.getAsJsonObject("vehicles") : new JsonObject();
                for (String key : vehicles.keySet()) {
                    ENGINES.put(key, vehicles.get(key).getAsInt());
                }
            }
        } catch (Throwable t) {
            loadFailed = true;
            SbwRadarMod.LOGGER.warn("target_intel.json load failed", t);
        }
    }

    /** @return engine label ("单发"/"双发"/"多发") or null when unknown. */
    public static String engineLabel(Entity entity) {
        ensureLoaded();
        if (ENGINES == null) return null;
        String path = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(entity.getType()).getPath();
        Integer n = ENGINES.get(path);
        if (n == null) return null;
        if (n <= 1) return "单发";
        if (n == 2) return "双发";
        return "多发";
    }

    /** Health band label for living targets. */
    public static String healthBand(LivingEntity living) {
        float hp = living.getHealth();
        if (hp < 30) return "血<30";
        if (hp < 50) return "30-50";
        if (hp < 100) return "50-100";
        return "血>100";
    }

    private TargetIntel() {}
}
