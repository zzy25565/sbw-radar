package com.zzy.sbwradar;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Client-side mirror of the radar mode state (fed by StateSync payload). */
public final class ClientRadarState {

    public record State(String mode, int targetId, String targetName, int trackedCount, int widthDeg,
                        int guidedCount) {}

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    public static void set(RadarPayloads.StateSync p) {
        STATES.put(p.vehicleId(), new State(p.mode(), p.targetId(), p.targetName(), p.trackedCount(),
                p.widthDeg(), p.guidedCount()));
    }

    public static State get(int vehicleId) {
        return STATES.get(vehicleId);
    }

    public static void clear() {
        STATES.clear();
    }

    private ClientRadarState() {}
}
