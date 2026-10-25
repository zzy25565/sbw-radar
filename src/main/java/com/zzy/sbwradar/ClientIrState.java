package com.zzy.sbwradar;

/** Client-side mirror of the IR seeker state (drives the lock tone + HUD text). */
public final class ClientIrState {

    private static volatile boolean locked = false;
    private static volatile int distance = 0;
    private static volatile String targetName = "";
    private static int toneCooldown = 0;

    public static void set(RadarPayloads.IrState p) {
        boolean wasLocked = locked;
        locked = p.locked();
        distance = p.distance();
        targetName = p.targetName() == null ? "" : p.targetName();
        if (!wasLocked && locked) {
            toneCooldown = 0; // start the tone immediately on a fresh lock
        }
    }

    public static boolean isLocked() {
        return locked;
    }

    public static int getDistance() {
        return distance;
    }

    public static String getTargetName() {
        return targetName;
    }

    /** Called each client tick; returns true when a tone should be played now. */
    public static boolean tickTone() {
        if (!locked) return false;
        if (--toneCooldown > 0) return false;
        toneCooldown = 10; // ~0.5 s cadence
        return true;
    }

    public static void clear() {
        locked = false;
        distance = 0;
        targetName = "";
        toneCooldown = 0;
    }

    private ClientIrState() {}
}
