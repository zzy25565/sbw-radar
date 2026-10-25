package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.client.ClientSyncedEntityHandler;
import com.atsuishio.superbwarfare.client.ClientSyncedEntityHandler.SyncedRadar;
import com.atsuishio.superbwarfare.client.map.CoordinateConverter;
import com.atsuishio.superbwarfare.client.screens.TacticalMapScreen;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Draws radar coverage circles and per-target intel on SBW's tactical map.
 *
 * Transform is read from TacticalMapScreen via reflection (mojmap names are preserved
 * in the SBW production jar):
 *   screenX = mapCenterX + (worldX - viewBlockX) * scale
 *   screenY = mapCenterY + (worldZ - viewBlockZ) * scale
 *   scale   = CoordinateConverter.scaleFromZoom(zoom)
 */
public final class TacticalMapOverlay {

    private static Field fMapCenterX, fMapCenterY, fViewBlockX, fViewBlockZ, fZoom;
    private static boolean reflectFailed = false;

    private static final int COL_COVER_FILL = 0x5558C98A;   // friendly radar coverage (light green)
    private static final int COL_COVER_EDGE = 0xAA2FBF7A;
    private static final int COL_SWEEP_LINE = 0xFF0A3D1E;   // current sweep direction (dark green)
    private static final int COL_RANGE = 0xFF1D9E75;
    private static final int COL_LABEL_HOSTILE = 0xFFFFC7C7;
    private static final int COL_LABEL_NEUTRAL = 0xFFD3D1C7;
    private static final int COL_RANGE_TEXT = 0xFF5DCAA5;

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!SbwRadarClientConfig.TACTICAL_INTEL.get()) return;
        if (!(event.getScreen() instanceof TacticalMapScreen map)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        if (!initReflection(map.getClass())) return;

        try {
            float mapCenterX = fMapCenterX.getFloat(map);
            float mapCenterY = fMapCenterY.getFloat(map);
            double viewBlockX = fViewBlockX.getDouble(map);
            double viewBlockZ = fViewBlockZ.getDouble(map);
            double zoom = fZoom.getDouble(map);
            double scale = CoordinateConverter.INSTANCE.scaleFromZoom(zoom);
            if (scale <= 0) return;

            GuiGraphics g = event.getGuiGraphics();
            Font font = mc.font;

            List<SyncedRadar> radars = ClientSyncedEntityHandler.getSyncedRadars(mc.level);
            if (radars.isEmpty()) return;

            // 1) coverage area + live sweep line for every friendly radar
            for (SyncedRadar r : radars) {
                double cx = mapCenterX + (r.getPos().x - viewBlockX) * scale;
                double cy = mapCenterY + (r.getPos().z - viewBlockZ) * scale;
                double rp = r.getRadius() * scale;
                if (rp < 4) continue;

                double sweep = r.getSweepAngle();
                if (sweep >= 359.0) {
                    fillCircle(g, cx, cy, rp, COL_COVER_FILL);
                } else {
                    // filled wedge: radial lines are cheap and need no clipping
                    for (double a = -sweep / 2.0; a <= sweep / 2.0; a += 1.0) {
                        drawRadial(g, cx, cy, r.getYRot() + a, rp, COL_COVER_FILL);
                    }
                    drawRadial(g, cx, cy, r.getYRot() - sweep / 2.0, rp, COL_COVER_EDGE);
                    drawRadial(g, cx, cy, r.getYRot() + sweep / 2.0, rp, COL_COVER_EDGE);
                }

                drawRing(g, cx, cy, rp, COL_RANGE);
                // live sweep direction
                drawRadial(g, cx, cy, r.getYRot(), rp, COL_SWEEP_LINE);
                g.drawString(font, (int) r.getRadius() + "m", (int) (cx + rp) + 3, (int) cy - 4, COL_RANGE_TEXT);
            }

            // 2) target intel labels
            drawIntel(g, font, mc, radars, mapCenterX, mapCenterY, viewBlockX, viewBlockZ, scale,
                    ClientSyncedEntityHandler.getSyncedHostileEntities(mc.level), COL_LABEL_HOSTILE);
            drawIntel(g, font, mc, radars, mapCenterX, mapCenterY, viewBlockX, viewBlockZ, scale,
                    ClientSyncedEntityHandler.getSyncedNeutralEntities(mc.level), COL_LABEL_NEUTRAL);

        } catch (Throwable t) {
            reflectFailed = true;
            SbwRadarMod.LOGGER.warn("tactical map overlay failed", t);
        }
    }

    private static void drawIntel(GuiGraphics g, Font font, Minecraft mc, List<SyncedRadar> radars,
                                  float mapCenterX, float mapCenterY,
                                  double viewBlockX, double viewBlockZ, double scale,
                                  List<Entity> entities, int color) {
        for (Entity e : entities) {
            Vec3 pos = ClientSyncedEntityHandler.getExtrapolatedPos(mc.level, e);
            if (!insideAnyRadar(radars, pos)) continue;

            String label = labelFor(e);
            if (label == null) continue;

            int sx = (int) Math.round(mapCenterX + (pos.x - viewBlockX) * scale);
            int sy = (int) Math.round(mapCenterY + (pos.z - viewBlockZ) * scale);
            g.drawString(font, label, sx + 6, sy - 3, color);
        }
    }

    private static String labelFor(Entity e) {
        if (e instanceof VehicleEntity) {
            String engines = TargetIntel.engineLabel(e);
            if (engines != null) return engines;
            return null;
        }
        if (e instanceof LivingEntity living) {
            return TargetIntel.healthBand(living);
        }
        return null;
    }

    private static boolean insideAnyRadar(List<SyncedRadar> radars, Vec3 pos) {
        for (SyncedRadar r : radars) {
            double dx = pos.x - r.getPos().x;
            double dz = pos.z - r.getPos().z;
            if (dx * dx + dz * dz <= r.getRadius() * r.getRadius()) return true;
        }
        return false;
    }

    /** Radial line from (cx,cy) towards a world yaw angle (0 = +Z, i.e. screen down). */
    private static void drawRadial(GuiGraphics g, double cx, double cy, double yawDeg, double len, int color) {
        double rad = Math.toRadians(yawDeg);
        double ex = cx + Math.sin(rad) * len;
        double ey = cy + Math.cos(rad) * len;
        drawLine(g, (int) cx, (int) cy, (int) ex, (int) ey, color);
    }

    private static void drawLine(GuiGraphics g, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int steps = Math.max(dx, dy);
        if (steps == 0) {
            g.fill(x0, y0, x0 + 1, y0 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x0 + (x1 - x0) * i / steps;
            int y = y0 + (y1 - y0) * i / steps;
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void fillCircle(GuiGraphics g, double cx, double cy, double r, int color) {
        int ri = (int) Math.round(r);
        for (int dy = -ri; dy <= ri; dy++) {
            int dx = (int) Math.sqrt((double) ri * ri - (double) dy * dy);
            g.fill((int) cx - dx, (int) cy + dy, (int) cx + dx, (int) cy + dy + 1, color);
        }
    }

    private static void drawRing(GuiGraphics g, double cx, double cy, double radius, int color) {
        int steps = Math.max(48, (int) (radius * 1.2));
        steps = Math.min(steps, 360);
        for (int i = 0; i < steps; i++) {
            double a = Math.PI * 2 * i / steps;
            int px = (int) Math.round(cx + Math.cos(a) * radius);
            int py = (int) Math.round(cy + Math.sin(a) * radius);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    private static boolean initReflection(Class<?> cls) {
        if (reflectFailed) return false;
        if (fZoom != null) return true;
        try {
            fMapCenterX = field(cls, "mapCenterX");
            fMapCenterY = field(cls, "mapCenterY");
            fViewBlockX = field(cls, "viewBlockX");
            fViewBlockZ = field(cls, "viewBlockZ");
            fZoom = field(cls, "zoom");
            return true;
        } catch (Throwable t) {
            reflectFailed = true;
            SbwRadarMod.LOGGER.warn("TacticalMapScreen reflection failed - overlay disabled", t);
            return false;
        }
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private TacticalMapOverlay() {}
}
