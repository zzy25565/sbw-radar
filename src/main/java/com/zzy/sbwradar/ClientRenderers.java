package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.client.ClientSyncedEntityHandler;
import com.atsuishio.superbwarfare.client.ClientSyncedEntityHandler.ClientSyncedEntity;
import com.atsuishio.superbwarfare.client.ClientSyncedEntityHandler.SyncedRadar;
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.List;

/**
 * Client-side radar HUD:
 *  - 2D radar scope (bottom-left) while riding a vehicle covered by a synced radar.
 *  - 3D lead reticle (world space): diamond showing where to aim at the nearest hostile,
 *    solved iteratively with the target velocity synced by SBW's BVR handler.
 */
public final class ClientRenderers {

    private static final int COL_BG = 0x5558C98A;          // 浅绿底（半透明）
    private static final int COL_SCAN_FILL = 0xCC0A3D1E;   // 深绿 = 本机当前扫描覆盖区
    private static final int COL_RING = 0xFF0F6E56;
    private static final int COL_SWEEP = 0xFF1D9E75;
    private static final int COL_TEXT = 0xFF5DCAA5;
    private static final int COL_DATALINK = 0xFF66CCFF;   // contact received over the datalink
    private static final int COL_HOSTILE = 0xFFE24B4A;
    private static final int COL_NEUTRAL = 0xFFB4B2A9;

    // ── entry registration helpers ──

    /** HUD shows while the player is inside any synced radar's coverage radius. */
    public static boolean ridingCoveredVehicle(Player player) {
        if (!(player.getVehicle() instanceof VehicleEntity v) || v.isWreck()) return false;
        List<SyncedRadar> radars = ClientSyncedEntityHandler.getSyncedRadars(player.level());
        if (radars.isEmpty()) return false;
        for (SyncedRadar r : radars) {
            if (r.getPos().distanceToSqr(player.position()) <= r.getRadius() * r.getRadius()) return true;
        }
        return false;
    }

    private static SyncedRadar nearestRadar(Player player) {
        SyncedRadar best = null;
        double bestD = Double.MAX_VALUE;
        for (SyncedRadar r : ClientSyncedEntityHandler.getSyncedRadars(player.level())) {
            double d = r.getPos().distanceToSqr(player.position());
            if (d < bestD) {
                bestD = d;
                best = r;
            }
        }
        return best;
    }

    // -- datalink target memory (1 s persistence) --

    /** A contact kept alive briefly after it drops out of the sync list. */
    private static final class MemoryTrack {
        double x, z;      // last known position
        double vx, vz;    // last known velocity (blocks per tick)
        long tick;        // game time of the last update
        boolean hostile;

        MemoryTrack(double x, double z, boolean hostile) {
            this.x = x;
            this.z = z;
            this.hostile = hostile;
        }
    }

    private static final java.util.Map<java.util.UUID, MemoryTrack> MEMORY = new java.util.HashMap<>();
    /** How many ticks a lost contact stays on the scope (20 ticks = 1 s). */
    private static final int MEMORY_TICKS = 20;

    // -- 2D radar scope --

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || !SbwRadarClientConfig.HUD_ENABLE.get()) return;
        if (!ridingCoveredVehicle(player)) return;

        SyncedRadar radar = nearestRadar(player);
        if (radar == null) return;

        GuiGraphics g = event.getGuiGraphics();
        Font font = mc.font;
        int r = SbwRadarClientConfig.HUD_SIZE.get();
        int cx = 24 + r;
        int cy = g.guiHeight() - 24 - r;
        drawCountermeasureLine(g, font, player, cx, cy + r + 14);

        // background disc (scanline fill)
        for (int dy = -r; dy <= r; dy++) {
            int dx = (int) Math.sqrt((double) r * r - (double) dy * dy);
            g.fill(cx - dx, cy + dy, cx + dx, cy + dy + 1, COL_BG);
        }
        // range rings
        ring(g, cx, cy, r, COL_RING);
        ring(g, cx, cy, r / 2, COL_RING);
        g.fill(cx - 1, cy - 1, cx + 1, cy + 1, COL_SWEEP);

        // scanned area: filled dark green wedge (whole disc when the antenna is omni)
        double sweep = radar.getSweepAngle();
        if (sweep >= 359.0) {
            fillDisc(g, cx, cy, r, COL_SCAN_FILL);
            g.fill(cx - 1, cy - 1, cx + 1, cy + 1, COL_SWEEP);
        } else {
            double step = 0.5;
            for (double a = -sweep / 2.0; a <= sweep / 2.0; a += step) {
                line(g, cx, cy, radar.getYRot() + a, r, COL_SCAN_FILL);
            }
            g.fill(cx - 1, cy - 1, cx + 1, cy + 1, COL_SWEEP);
        }
        line(g, cx, cy, radar.getYRot(), r, COL_SWEEP);
        // redraw the rings on top of the dark green fill so they stay readable
        ring(g, cx, cy, r, COL_RING);
        ring(g, cx, cy, r / 2, COL_RING);
        // sector boundaries: make the scanned cone obvious for non-omni antennas
        if (sweep < 359.0) {
            line(g, cx, cy, radar.getYRot() - sweep / 2.0, r, 0x6633DDAA);
            line(g, cx, cy, radar.getYRot() + sweep / 2.0, r, 0x6633DDAA);
        }

        // blips (north-up); returns how many came from the datalink
        List<Entity> hostiles = ClientSyncedEntityHandler.getSyncedHostileEntities(mc.level);
        List<Entity> neutrals = ClientSyncedEntityHandler.getSyncedNeutralEntities(mc.level);
        updateMemory(mc, hostiles, neutrals);
        int datalink = drawBlips(g, mc, cx, cy, r, radar, hostiles, neutrals);
        drawGhosts(g, mc, cx, cy, r, radar, hostiles, neutrals);

        // labels are handled by drawHeader()
        // radar mode / lock readout
        Entity riddenEntity = player.getVehicle();
        ClientRadarState.State st = riddenEntity != null ? ClientRadarState.get(riddenEntity.getId()) : null;

        // ammo interception readout: small AA only, one short line under the scope
        if (riddenEntity instanceof VehicleEntity iv && AmmoInterceptor.isInterceptor(iv)) {
            int cd = AmmoInterceptor.cooldown(iv.getId());
            int max = SbwRadarConfig.INTERCEPT_COOLDOWN.get();
            String text = AmmoInterceptor.justFired(iv.getId()) ? "INTERCEPT"
                    : (cd > 0 ? "RELOAD " + (cd / 20.0) + "s" : "CIWS READY");
            int col = AmmoInterceptor.justFired(iv.getId()) ? 0xFFE8B33C
                    : (cd > 0 ? 0xFFE0574F : 0xFF4FD07A);
            g.drawString(font, text, cx - r + 4, cy + r + 14, col);
            if (AmmoInterceptor.justFired(iv.getId())) AmmoInterceptor.clearFlash(iv.getId());
        }
        if (ClientIrState.isLocked()) {
            String ir = "IR LOCK  " + ClientIrState.getDistance() + "m";
            g.drawString(font, ir, cx - 28, cy - r - 12, 0xFFFF9D9D);
        }

        if (datalink > 0) {
            g.drawString(font, "DL " + datalink, cx + r + 4, cy - 4, COL_DATALINK);
        }

        if (st != null) {
            drawHeader(g, font, cx, cy, r, radar, st);
        }

        if (st != null) {
            int key = java.util.Objects.hash(st.mode(), st.trackedCount(), st.guidedCount(),
                    st.widthDeg(), st.targetName());
            if (key != modeKey || modeText == null) {
                modeKey = key;
                StringBuilder line = new StringBuilder();
                switch (st.mode()) {
                    case "TWS" -> {
                        line.append(st.trackedCount()).append(" tracks");
                        if (st.guidedCount() > 0) line.append("  ·  ").append(st.guidedCount()).append(" missiles");
                    }
                    case "TRK" -> line.append(st.targetName().isEmpty() ? "" : st.targetName());
                    default -> line.append("");
                }
                modeText = line.toString();
            }
            if (!modeText.isEmpty()) {
                g.drawString(font, modeText, cx - r + 4, cy + r + 4, 0xFFFAC775);
            }
        }
    }

    // cached HUD readouts (rebuilt only when the values change)
    private static String scanText = null;
    private static int scanRelDeg = Integer.MIN_VALUE;
    private static int scanHalfDeg = Integer.MIN_VALUE;
    private static String modeText = null;
    private static int modeKey = Integer.MIN_VALUE;

    private static int cachedRadius = -1;
    private static int[] cachedRowWidth = new int[0];

    /** Half width of the disc for every row, computed once per radius. */
    private static int[] discRows(int r) {
        if (r == cachedRadius) return cachedRowWidth;
        int[] rows = new int[2 * r + 1];
        for (int dy = -r; dy <= r; dy++) {
            rows[dy + r] = (int) Math.sqrt((double) r * r - (double) dy * dy);
        }
        cachedRadius = r;
        cachedRowWidth = rows;
        return rows;
    }

    /** Three small fields above the scope; the wedge and blips do the rest. */
    /**
     * 干扰弹两行：热诱弹贴在原生 UI 文案之后（数量取自 SBW 的 DECOY_COUNT），
     * 箔条是本附属独立的弹种，数量由 StateSync 带下来。
     */
    private static void drawCountermeasureLine(GuiGraphics g, Font font, Player player, int x, int y) {
        if (!(player.getVehicle() instanceof VehicleEntity vehicle)) return;
        var type = vehicle.getVehicleType();
        if (type != VehicleType.AIRPLANE && type != VehicleType.HELICOPTER) return;

        int flare = vehicle.getDecoyCount();
        String flareText = flare > 0 ? ("热诱弹 " + flare) : "热诱弹 无";
        g.drawString(font, flareText, x, y, flare > 0 ? 0xFFFFD27F : 0xFF8A8A8A);

        int chaff = ClientRadarState.get(vehicle.getId()) instanceof ClientRadarState.State st
                ? st.chaffCount() : 0;
        String chaffText = chaff > 0 ? ("箔条 " + chaff) : "箔条 无";
        g.drawString(font, chaffText, x, y + 10, chaff > 0 ? 0xFFBFD8FF : 0xFF8A8A8A);
    }

    private static void drawHeader(GuiGraphics g, Font font, int cx, int cy, int r,
                                   SyncedRadar radar, ClientRadarState.State st) {
        String mode = switch (st.mode()) {
            case "TWS" -> "TWS";
            case "TRK" -> "TRK";
            default -> "SRC";
        };
        int left = cx - r;
        int top = cy - r - 12;
        g.drawString(font, mode, left, top, 0xFF9DFFD0);

        String width = st.widthDeg() + "°";
        g.drawString(font, width, left + font.width(mode) + 8, top, COL_TEXT);

        String range = (int) radar.getRadius() + "m";
        g.drawString(font, range, cx + r - font.width(range), top, COL_RING);
    }

    private static void fillDisc(GuiGraphics g, int cx, int cy, int r, int color) {
        int[] rows = discRows(r);
        for (int dy = -r; dy <= r; dy++) {
            int dx = rows[dy + r];
            g.fill(cx - dx, cy + dy, cx + dx, cy + dy + 1, color);
        }
    }

    private static void ring(GuiGraphics g, int cx, int cy, int r, int color) {
        for (double a = 0; a < 360.0; a += 3.0) {
            int px = cx + (int) Math.round(Math.cos(Math.toRadians(a)) * r);
            int py = cy + (int) Math.round(Math.sin(Math.toRadians(a)) * r);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    private static void line(GuiGraphics g, int cx, int cy, double yRotDeg, int r, int color) {
        double rad = Math.toRadians(yRotDeg);
        // world yaw: 0 = +Z (south, screen down); screen: x = sin, y = cos
        int ex = cx + (int) Math.round(Mth.sin((float) rad) * r);
        int ey = cy + (int) Math.round(Mth.cos((float) rad) * r);
        int steps = r / 2;
        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            int px = cx + (int) Math.round((ex - cx) * f);
            int py = cy + (int) Math.round((ey - cy) * f);
            g.fill(px, py, px + 1, py + 1, color);
        }
    }

    private static int drawBlips(GuiGraphics g, Minecraft mc, int cx, int cy, int r, SyncedRadar radar,
                                 List<Entity> hostiles, List<Entity> neutrals) {
        int[] linked = {0};
        drawBlipGroup(g, mc, cx, cy, r, radar, hostiles, COL_HOSTILE, linked);
        drawBlipGroup(g, mc, cx, cy, r, radar, neutrals, COL_NEUTRAL, linked);
        return linked[0];
    }

    /** Called from the client tick so the memory expires even while the HUD is hidden. */
    public static void tickMemory() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!MEMORY.isEmpty()) MEMORY.clear();
            return;
        }
        long now = mc.level.getGameTime();
        MEMORY.entrySet().removeIf(e -> now - e.getValue().tick > MEMORY_TICKS);
    }

    /** Refresh the contact memory and expire entries older than MEMORY_TICKS. */
    private static void updateMemory(Minecraft mc, List<Entity> hostiles, List<Entity> neutrals) {
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        remember(mc, hostiles, true, now);
        remember(mc, neutrals, false, now);
        MEMORY.entrySet().removeIf(e -> now - e.getValue().tick > MEMORY_TICKS);
    }

    private static void remember(Minecraft mc, List<Entity> entities, boolean hostile, long now) {
        for (Entity e : entities) {
            java.util.UUID id = e.getUUID();
            Vec3 pos = ClientSyncedEntityHandler.getExtrapolatedPos(mc.level, e);
            MemoryTrack t = MEMORY.get(id);
            if (t == null) {
                t = new MemoryTrack(pos.x, pos.z, hostile);
            } else {
                long dt = Math.max(1, now - t.tick);
                t.vx = (pos.x - t.x) / dt;
                t.vz = (pos.z - t.z) / dt;
                t.x = pos.x;
                t.z = pos.z;
            }
            t.tick = now;
            MEMORY.put(id, t);
        }
    }

    /** Draw contacts that dropped out less than MEMORY_TICKS ago, extrapolated along their last vector. */
    private static void drawGhosts(GuiGraphics g, Minecraft mc, int cx, int cy, int r, SyncedRadar radar,
                                   List<Entity> hostiles, List<Entity> neutrals) {
        if (mc.level == null || MEMORY.isEmpty()) return;
        long now = mc.level.getGameTime();
        java.util.Set<java.util.UUID> alive = new java.util.HashSet<>();
        for (Entity e : hostiles) alive.add(e.getUUID());
        for (Entity e : neutrals) alive.add(e.getUUID());

        for (java.util.Map.Entry<java.util.UUID, MemoryTrack> en : MEMORY.entrySet()) {
            if (alive.contains(en.getKey())) continue;
            MemoryTrack t = en.getValue();
            long age = now - t.tick;
            if (age <= 0 || age > MEMORY_TICKS) continue;

            double px = t.x + t.vx * age;
            double pz = t.z + t.vz * age;
            double relX = px - radar.getPos().x;
            double relZ = pz - radar.getPos().z;
            double dist = Math.sqrt(relX * relX + relZ * relZ);
            if (dist > radar.getRadius()) continue;

            double scale = Math.min(dist, radar.getRadius()) / radar.getRadius() * r;
            double len = Math.max(dist, 0.001);
            int bx = cx + (int) Math.round(relX / len * scale);
            int by = cy + (int) Math.round(relZ / len * scale);

            int alpha = (int) (200 * (1.0 - (double) age / MEMORY_TICKS));
            int base = t.hostile ? 0x0066CCFF : 0x0088AACC;
            int color = (alpha << 24) | base;
            g.fill(bx - 2, by - 1, bx + 3, by, color);
            g.fill(bx - 1, by - 2, bx + 2, by + 1, color);
        }
    }

    /** Signed difference a-b normalised to [-180, 180]. */
    private static double angleDelta(double a, double b) {
        return ((a - b) % 360.0 + 540.0) % 360.0 - 180.0;
    }

    private static void drawBlipGroup(GuiGraphics g, Minecraft mc, int cx, int cy, int r,
                                      SyncedRadar radar, List<Entity> entities, int color, int[] linked) {
        double sweep = radar.getSweepAngle();
        boolean directional = sweep < 359.0;
        for (Entity e : entities) {
            Vec3 rel = ClientSyncedEntityHandler.getExtrapolatedPos(mc.level, e).subtract(radar.getPos());
            double dist = Math.sqrt(rel.x * rel.x + rel.z * rel.z);
            double scale = Math.min(dist, radar.getRadius()) / radar.getRadius() * r;
            double len = Math.max(dist, 0.001);
            int bx = cx + (int) Math.round(rel.x / len * scale);
            int by = cy + (int) Math.round(rel.z / len * scale);
            // outside the current scan cone => this contact can only come from the datalink
            boolean fromLink = false;
            if (directional) {
                double bearing = Math.toDegrees(Math.atan2(rel.x, rel.z));
                fromLink = Math.abs(angleDelta(bearing, radar.getYRot())) > sweep / 2.0;
            }
            if (fromLink) {
                linked[0]++;
                int dlColor = color == COL_HOSTILE ? COL_DATALINK : 0x8866CCFF;
                // hollow square: distinct from own-scan contacts
                g.fill(bx - 2, by - 2, bx + 3, by - 1, dlColor);
                g.fill(bx - 2, by + 2, bx + 3, by + 3, dlColor);
                g.fill(bx - 2, by - 2, bx - 1, by + 3, dlColor);
                g.fill(bx + 2, by - 2, bx + 3, by + 3, dlColor);
                continue;
            }
            if (color == COL_HOSTILE && dist <= radar.getRadius()) {
                // diamond for hostiles
                g.fill(bx - 2, by - 2, bx + 3, by + 3, 0x00000000);
                g.fill(bx, by - 3, bx + 1, by + 4, color);
                g.fill(bx - 3, by, bx + 4, by + 1, color);
            } else {
                g.fill(bx - 1, by - 1, bx + 2, by + 2, color);
            }
        }
    }

    // ── 3D lead reticle ──

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || !SbwRadarClientConfig.LEAD_ENABLE.get()) return;
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.isWreck()) return;

        Entity target = nearestHostile(player);
        if (target == null) return;

        Vec3 muzzle = player.getVehicle().position().add(0.0, 1.5, 0.0);
        Vec3 targetPos = ClientSyncedEntityHandler.getExtrapolatedPos(mc.level, target);
        Vec3 velocity = targetVelocity(mc, target);
        Vec3 lead = solveLead(muzzle, targetPos, velocity, SbwRadarClientConfig.LEAD_SPEED.get());
        if (lead == null) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);

        Vector3f right = rotateAxes(camera, new Vector3f(1, 0, 0));
        Vector3f up = rotateAxes(camera, new Vector3f(0, 1, 0));

        VertexConsumer buf = mc.renderBuffers().bufferSource().getBuffer(RenderType.lines());
        double size = Math.max(0.35, lead.distanceTo(camPos) * 0.012);

        // diamond outline (billboard)
        Vec3 p1 = lead.add(up.x * size, up.y * size, up.z * size);
        Vec3 p2 = lead.add(right.x * size, right.y * size, right.z * size);
        Vec3 p3 = lead.add(-up.x * size, -up.y * size, -up.z * size);
        Vec3 p4 = lead.add(-right.x * size, -right.y * size, -right.z * size);
        line3d(buf, pose, p1, p2);
        line3d(buf, pose, p2, p3);
        line3d(buf, pose, p3, p4);
        line3d(buf, pose, p4, p1);

        // dashed guide from target to lead position
        Vec3 mid = targetPos.add(lead).scale(0.5);
        line3d(buf, pose, targetPos, mid);

        mc.renderBuffers().bufferSource().endBatch(RenderType.lines());
        pose.popPose();
    }

    private static Entity nearestHostile(Player player) {
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : ClientSyncedEntityHandler.getSyncedHostileEntities(player.level())) {
            double d = e.distanceToSqr(player);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static Vec3 targetVelocity(Minecraft mc, Entity target) {
        Entity real = mc.level.getEntity(target.getId());
        if (real != null) return real.getDeltaMovement();
        ClientSyncedEntity entry = ClientSyncedEntityHandler.getSyncedEntry(mc.level, target.getId());
        return entry != null ? entry.getVelocity() : Vec3.ZERO;
    }

    /** Iterative flat-fire intercept: t = dist/speed, lead = target + vel * t. */
    public static Vec3 solveLead(Vec3 muzzle, Vec3 targetPos, Vec3 vel, double speed) {
        double t = targetPos.distanceTo(muzzle) / speed;
        Vec3 lead = null;
        for (int i = 0; i < 4; i++) {
            lead = targetPos.add(vel.scale(t));
            t = lead.distanceTo(muzzle) / speed;
        }
        return lead;
    }

    private static Vector3f rotateAxes(Camera camera, Vector3f vec) {
        Vector3f out = new Vector3f(vec);
        out.rotate(camera.rotation());
        return out;
    }

    private static void line3d(VertexConsumer buf, PoseStack pose, Vec3 a, Vec3 b) {
        double dx = b.x - a.x, dy = b.y - a.y, dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 0.001) return;
        float nx = (float) (dx / len), ny = (float) (dy / len), nz = (float) (dz / len);
        buf.addVertex(pose.last().pose(), (float) a.x, (float) a.y, (float) a.z)
                .setColor(0x9F, 0xE1, 0xCB, 0xC0).setNormal(nx, ny, nz);
        buf.addVertex(pose.last().pose(), (float) b.x, (float) b.y, (float) b.z)
                .setColor(0x9F, 0xE1, 0xCB, 0xC0).setNormal(nx, ny, nz);
    }

    private ClientRenderers() {}
}
