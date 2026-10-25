import io

p = "ClientRenderers.java"
s = io.open(p, encoding="utf-8").read()

if "MemoryTrack" not in s:
    s = s.replace("    // \u2500\u2500 2D radar scope \u2500\u2500",
"""    // -- datalink target memory (1 s persistence) --

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

    // -- 2D radar scope --""")

s = s.replace("""        // blips (north-up); returns how many came from the datalink
        int datalink = drawBlips(g, mc, cx, cy, r, radar);""",
"""        // blips (north-up); returns how many came from the datalink
        List<Entity> hostiles = ClientSyncedEntityHandler.getSyncedHostileEntities(mc.level);
        List<Entity> neutrals = ClientSyncedEntityHandler.getSyncedNeutralEntities(mc.level);
        updateMemory(mc, hostiles, neutrals);
        int datalink = drawBlips(g, mc, cx, cy, r, radar, hostiles, neutrals);
        drawGhosts(g, mc, cx, cy, r, radar, hostiles, neutrals);""")

s = s.replace("""    private static int drawBlips(GuiGraphics g, Minecraft mc, int cx, int cy, int r, SyncedRadar radar) {
        int[] linked = {0};
        drawBlipGroup(g, mc, cx, cy, r, radar,
                ClientSyncedEntityHandler.getSyncedHostileEntities(mc.level), COL_HOSTILE, linked);
        drawBlipGroup(g, mc, cx, cy, r, radar,
                ClientSyncedEntityHandler.getSyncedNeutralEntities(mc.level), COL_NEUTRAL, linked);
        return linked[0];
    }""",
"""    private static int drawBlips(GuiGraphics g, Minecraft mc, int cx, int cy, int r, SyncedRadar radar,
                                 List<Entity> hostiles, List<Entity> neutrals) {
        int[] linked = {0};
        drawBlipGroup(g, mc, cx, cy, r, radar, hostiles, COL_HOSTILE, linked);
        drawBlipGroup(g, mc, cx, cy, r, radar, neutrals, COL_NEUTRAL, linked);
        return linked[0];
    }""")

s = s.replace("""    /** Signed difference a-b normalised to [-180, 180]. */""",
"""    /** Refresh the contact memory and expire entries older than MEMORY_TICKS. */
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

    /** Signed difference a-b normalised to [-180, 180]. */""")

io.open(p, "w", encoding="utf-8").write(s)
print("memory:", "MEMORY_TICKS" in s, "ghosts:", "drawGhosts" in s, "sig:", "hostiles, List<Entity> neutrals) {" in s)
