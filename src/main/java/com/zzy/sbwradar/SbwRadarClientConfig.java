package com.zzy.sbwradar;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-side config for the radar HUD and lead reticle. */
public final class SbwRadarClientConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue HUD_ENABLE = BUILDER
            .comment("Show the radar scope overlay while riding a radar-equipped vehicle")
            .define("hud.enable", true);

    public static final ModConfigSpec.IntValue HUD_SIZE = BUILDER
            .comment("Radar scope radius on screen (pixels)")
            .defineInRange("hud.size", 90, 40, 200);

    public static final ModConfigSpec.BooleanValue LEAD_ENABLE = BUILDER
            .comment("Draw the gun lead reticle showing where to aim at the nearest hostile")
            .define("lead.enable", true);

    public static final ModConfigSpec.DoubleValue LEAD_SPEED = BUILDER
            .comment("Assumed projectile speed in blocks per tick for the lead solution")
            .defineInRange("lead.projectileSpeed", 5.0, 1.0, 60.0);

    public static final ModConfigSpec.BooleanValue TACTICAL_INTEL = BUILDER
            .comment("Show radar coverage circles and target intel (engine count / health band)",
                    "on the tactical map")
            .define("tactical.intel", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SbwRadarClientConfig() {}
}
