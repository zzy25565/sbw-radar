package com.zzy.sbwradar;

/** Radar work modes, War Thunder style. */
public enum RadarMode {
    /** Sector search only: directional sweep, fast refresh, no tracking. */
    SRC,
    /** Search while tracking: multiple targets, slower refresh. */
    TWS,
    /** Single-target lock: continuous tracking, narrow beam. */
    TRK;

    public RadarMode next(java.util.List<RadarMode> allowed) {
        int i = allowed.indexOf(this);
        if (i < 0) return allowed.get(0);
        return allowed.get((i + 1) % allowed.size());
    }
}
