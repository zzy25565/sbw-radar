package com.zzy.sbwradar;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config for the injected AA radar. */
public final class SbwRadarConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue RADAR_RANGE = BUILDER
            .comment("Injected AA radar detection range (blocks)")
            .defineInRange("radar.range", 512.0, 64.0, 2048.0);

    public static final ModConfigSpec.DoubleValue SWEEP_ANGLE = BUILDER
            .comment("Radar sweep angle in degrees (360 = omni)")
            .defineInRange("radar.sweepAngle", 120.0, 30.0, 360.0);

    public static final ModConfigSpec.DoubleValue ROTATION_SPEED = BUILDER
            .comment("Sweep rotation speed (degrees per tick)")
            .defineInRange("radar.rotationSpeed", 1.5, 0.1, 12.0);

    public static final ModConfigSpec.DoubleValue MIN_TARGET_HEIGHT = BUILDER
            .comment("Minimum target height (absolute Y) - keeps the radar airborne-only")
            .defineInRange("radar.minTargetHeight", 5.0, -64.0, 320.0);

    public static final ModConfigSpec.DoubleValue MAX_TARGET_HEIGHT = BUILDER
            .comment("Maximum target height (absolute Y)")
            .defineInRange("radar.maxTargetHeight", 400.0, 0.0, 2032.0);

    public static final ModConfigSpec.DoubleValue VERTICAL_SWEEP = BUILDER
            .comment("Default vertical scan half-range in degrees, measured from the radar boresight.",
                    "Runtime value is clamped to radar.verticalSweepMax; players cycle it in game with the",
                    "vertical-range key. 89 = essentially no vertical blind spot.")
            .defineInRange("radar.verticalSweep", 45.0, 5.0, 89.0);

    public static final ModConfigSpec.DoubleValue VERTICAL_SWEEP_MAX = BUILDER
            .comment("Upper limit for the vertical scan range (degrees). Raise this to allow wider cones;",
                    "89 lets the radar see almost straight up.")
            .defineInRange("radar.verticalSweepMax", 45.0, 5.0, 89.0);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> VERTICAL_STEPS = BUILDER
            .comment("Vertical scan steps (degrees) cycled by the vertical-range key, ascending")
            .defineList("radar.verticalSweepSteps", java.util.List.of("10", "20", "30", "45"),
                    o -> o instanceof String);

    public static final ModConfigSpec.IntValue SCAN_INTERVAL = BUILDER
            .comment("Scan every N ticks")
            .defineInRange("radar.scanInterval", 5, 1, 100);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PER_TICK_VEHICLES = BUILDER
            .comment("Vehicle entity ids whose radar data refresh rate is aligned with the sweep rotation",
                    "(scanned every tick). Format: namespace:path",
                    "Default covers the two dedicated radar vehicles: Buk-M3 and 625E")
            .defineList("radar.perTickVehicles",
                    java.util.List.of("sbw_vls:buk_m3", "sbw_vls:625e"),
                    o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue SHARE_WITH_TEAMMATES = BUILDER
            .comment("Share radar picture with teammates")
            .define("radar.shareWithTeammates", true);

    // ── radar-guided missile (ARH) ──

    public static final ModConfigSpec.BooleanValue MISSILE_ENABLE = BUILDER
            .comment("Fire radar-guided (ARH) missiles from radar-locked vehicles")
            .define("missile.enable", true);

    public static final ModConfigSpec.ConfigValue<String> MISSILE_ENTITY = BUILDER
            .comment("Entity id spawned as the missile (reuses SBW's own missile entity for rendering/sound)")
            .define("missile.entityId", "superbwarfare:ru_9m336");

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> MISSILE_VEHICLES = BUILDER
            .comment("Vehicle entity ids allowed to fire radar-guided missiles")
            .defineList("missile.vehicles",
                    java.util.List.of("sbw_vls:buk_m3", "sbw_vls:625e", "sbw_vls:tunguska",
                            "zzyvehicles:s400_launcher", "zzyvehicles:vls", "zzyvehicles:buk_radar",
                            "zzyvehicles:s400_radar"),
                    o -> o instanceof String);

    public static final ModConfigSpec.IntValue MISSILE_MAX_CONCURRENT = BUILDER
            .comment("Max missiles guided by one radar at the same time (illumination channels)")
            .defineInRange("missile.maxConcurrent", 2, 1, 8);

    public static final ModConfigSpec.DoubleValue MISSILE_SPEED = BUILDER
            .comment("Initial missile speed (blocks per tick)")
            .defineInRange("missile.initialSpeed", 2.0, 0.5, 10.0);

    public static final ModConfigSpec.IntValue MISSILE_LOST_TIMEOUT = BUILDER
            .comment("Ticks without radar lock before the missile goes ballistic and self-destructs")
            .defineInRange("missile.lockLostTimeout", 120, 20, 1200);

    public static final ModConfigSpec.DoubleValue MISSILE_SEEKER_RANGE = BUILDER
            .comment("Terminal active-seeker range: inside this distance the missile tracks the entity itself")
            .defineInRange("missile.seekerRange", 96.0, 16.0, 512.0);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> MISSILE_AMMO_KEYWORDS = BUILDER
            .comment("Only weapon ammo whose projectile id contains one of these keywords may launch a radar missile",
                    "(prevents machine guns / cannons from firing missiles)")
            .defineList("missile.ammoKeywords",
                    java.util.List.of("missile", "rocket", "agm", "aam"),
                    o -> o instanceof String);

    // ── airborne radar ──

    public static final ModConfigSpec.BooleanValue AIRBORNE_ENABLE = BUILDER
            .comment("Give aircraft / helicopters a nose-mounted radar")
            .define("airborne.enable", true);

    public static final ModConfigSpec.DoubleValue AIRBORNE_RANGE = BUILDER
            .comment("Airborne radar range (blocks)")
            .defineInRange("airborne.range", 320.0, 64.0, 1024.0);

    public static final ModConfigSpec.DoubleValue AIRBORNE_SECTOR = BUILDER
            .comment("Airborne radar search sector in degrees, centred on the nose (forward only)")
            .defineInRange("airborne.sector", 60.0, 20.0, 180.0);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> AIRBORNE_SECTORS = BUILDER
            .comment("Fixed scan sectors (degrees) for the airborne radar, cycled with the width key.",
                    "Narrower sector = faster refresh (see airborne.refreshBase)")
            .defineList("airborne.sectors", java.util.List.of("30", "60", "120"),
                    o -> o instanceof String);

    public static final ModConfigSpec.IntValue AIRBORNE_REFRESH_BASE = BUILDER
            .comment("Scan interval (ticks) at the widest sector; narrower sectors scale down proportionally")
            .defineInRange("airborne.refreshBase", 10, 1, 100);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> WIDTH_STEPS = BUILDER
            .comment("Selectable radar scan widths in degrees (cycled with the width key)")
            .defineList("radar.widthSteps", java.util.List.of("30", "60", "120"), o -> o instanceof String);

    // ── ammunition interception (small AA vehicles only) ──

    public static final ModConfigSpec.BooleanValue INTERCEPT_ENABLE = BUILDER
            .comment("Small air-defence vehicles can shoot down incoming projectiles")
            .define("interception.enable", true);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> INTERCEPT_VEHICLES = BUILDER
            .comment("Vehicles allowed to intercept (entity ids). Large launcher systems like Buk are NOT included on purpose.")
            .defineList("interception.vehicles", java.util.List.of("sbw_vls:tunguska", "vvp:pantsir_s1"),
                    o -> o instanceof String);

    public static final ModConfigSpec.DoubleValue INTERCEPT_RADIUS = BUILDER
            .comment("How far from the vehicle incoming rounds are engaged")
            .defineInRange("interception.radius", 32.0, 8.0, 96.0);

    public static final ModConfigSpec.IntValue INTERCEPT_PER_BURST = BUILDER
            .comment("Threats engaged per burst")
            .defineInRange("interception.perBurst", 4, 1, 8);

    public static final ModConfigSpec.IntValue INTERCEPT_COOLDOWN = BUILDER
            .comment("Ticks between bursts (20 = 1 s)")
            .defineInRange("interception.cooldown", 20, 5, 200);

    // ── AA missile proximity fuze ──

    public static final ModConfigSpec.BooleanValue PROX_ENABLE = BUILDER
            .comment("Anti-air missiles detonate near airborne targets without a direct hit")
            .define("proximity.enable", true);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PROX_HEAVY_MISSILES = BUILDER
            .comment("Heavy AA missiles (Buk / IRIS-T class): wide fuze")
            .defineList("proximity.heavyMissiles",
                    java.util.List.of("ru_9m336_missile", "aa_missile"),
                    o -> o instanceof String);

    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PROX_LIGHT_MISSILES = BUILDER
            .comment("Light AA missiles (MANPADS class): tight fuze")
            .defineList("proximity.lightMissiles",
                    java.util.List.of("fim_92_missile", "igla_9k38_missile"),
                    o -> o instanceof String);

    public static final ModConfigSpec.DoubleValue PROX_HEAVY_RADIUS = BUILDER
            .comment("Fuze radius for heavy AA missiles (blocks)")
            .defineInRange("proximity.heavyRadius", 8.0, 1.0, 24.0);

    public static final ModConfigSpec.DoubleValue PROX_LIGHT_RADIUS = BUILDER
            .comment("Fuze radius for light AA missiles (blocks)")
            .defineInRange("proximity.lightRadius", 4.0, 1.0, 24.0);

    public static final ModConfigSpec.IntValue MISSILE_CLIMB_TICKS = BUILDER
            .comment("Boost/climb phase length in ticks; the missile flies along the radar illumination",
                    "axis during this phase before homing on the target")
            .defineInRange("missile.climbTicks", 12, 0, 120);

    public static final ModConfigSpec.DoubleValue MISSILE_CLIMB_UP = BUILDER
            .comment("Upward bias (degrees) added to the radar illumination axis during the climb phase")
            .defineInRange("missile.climbUpDeg", 10.0, -30.0, 60.0);

    // ── infrared (heat-seeking) missiles: aircraft / helicopters only ──

    public static final ModConfigSpec.BooleanValue IR_ENABLE = BUILDER
            .comment("Enable IR missiles for aircraft and helicopters")
            .define("ir.enable", true);

    public static final ModConfigSpec.ConfigValue<String> IR_ENTITY = BUILDER
            .comment("Missile entity used for IR launches")
            .define("ir.entityId", "superbwarfare:fim_92_missile");

    public static final ModConfigSpec.DoubleValue IR_SEEKER_CONE = BUILDER
            .comment("IR seeker field of view (half-angle, degrees) for acquiring a lock")
            .defineInRange("ir.seekerCone", 30.0, 5.0, 90.0);

    public static final ModConfigSpec.DoubleValue IR_RANGE_REAR = BUILDER
            .comment("IR lock range against a receding target (hot exhaust visible)")
            .defineInRange("ir.rangeRear", 320.0, 32.0, 1024.0);

    public static final ModConfigSpec.DoubleValue IR_RANGE_FRONT = BUILDER
            .comment("IR lock range against a head-on target (much smaller heat signature)")
            .defineInRange("ir.rangeFront", 120.0, 16.0, 1024.0);

    public static final ModConfigSpec.BooleanValue IR_REQUIRE_LOCK = BUILDER
            .comment("Require a lock tone before the missile can be fired")
            .define("ir.requireLock", true);

    public static final ModConfigSpec.DoubleValue IR_FLARE_CHANCE = BUILDER
            .comment("Probability weight that a flare inside the seeker view distracts the missile")
            .defineInRange("ir.flareChance", 0.6, 0.0, 1.0);

    // ── countermeasures (chaff / flare cartridges) ──

    public static final ModConfigSpec.BooleanValue COUNTER_ENABLE = BUILDER
            .comment("Allow dispensing chaff / flare cartridges")
            .define("counter.enable", true);

    public static final ModConfigSpec.DoubleValue COUNTER_RADIUS = BUILDER
            .comment("Radius in blocks in which a dispensed countermeasure can affect missiles")
            .defineInRange("counter.radius", 48.0, 8.0, 128.0);

    public static final ModConfigSpec.DoubleValue COUNTER_FLARE_CHANCE = BUILDER
            .comment("Per-missile success chance of one flare against IR missiles")
            .defineInRange("counter.flareChance", 0.10, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue COUNTER_CHAFF_CHANCE = BUILDER
            .comment("Per-missile success chance of one chaff bundle against radar missiles")
            .defineInRange("counter.chaffChance", 0.10, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue COUNTER_ESCALATION_STEP = BUILDER
            .comment("Extra success chance per consecutive dispense in the same burst (0 disables escalation)")
            .defineInRange("counter.escalationStep", 0.05, 0.0, 0.5);

    public static final ModConfigSpec.DoubleValue COUNTER_ESCALATION_MAX = BUILDER
            .comment("Upper bound for the escalated success chance")
            .defineInRange("counter.escalationMax", 0.50, 0.0, 1.0);

    public static final ModConfigSpec.IntValue COUNTER_ESCALATION_WINDOW = BUILDER
            .comment("Ticks within which another dispense counts as the same burst")
            .defineInRange("counter.escalationWindow", 40, 5, 200);

    public static final ModConfigSpec.IntValue COUNTER_CHAFF_SMOKE = BUILDER
            .comment("Chaff smoke particle count per dispense (0 = invisible; vanilla particles",
                    "have a fixed alpha, so lower count is how you make it fainter)")
            .defineInRange("counter.chaffSmoke", 6, 0, 60);

    // ── split air defence (radar vehicle commands a separate launcher) ──

    public static final ModConfigSpec.BooleanValue SPLIT_AA_ENABLE = BUILDER
            .comment("Split air defence: a radar vehicle orders nearby launcher vehicles to fire at its lock")
            .define("split.enable", true);

    public static final ModConfigSpec.DoubleValue SPLIT_AA_RADIUS = BUILDER
            .comment("Max distance (blocks) between the radar vehicle and a launcher for remote fire")
            .defineInRange("split.radius", 100.0, 8.0, 512.0);

    public static final ModConfigSpec.IntValue SPLIT_AA_INTERVAL = BUILDER
            .comment("How often the radar vehicle looks for launchers (ticks)")
            .defineInRange("split.interval", 10, 1, 100);

    public static final ModConfigSpec.IntValue SPLIT_AA_COOLDOWN = BUILDER
            .comment("Per-launcher cooldown after a remote launch (ticks)")
            .defineInRange("split.cooldown", 100, 10, 1200);

    public static final ModConfigSpec.BooleanValue SPLIT_AA_SELF_FIRE = BUILDER
            .comment("The radar vehicle also fires its own missiles while locked (ignores onlyUnmanned,",
                    "because the driver is the one holding the lock)")
            .define("split.selfFire", true);

    public static final ModConfigSpec.BooleanValue SPLIT_AA_REPORT = BUILDER
            .comment("Tell the radar driver why nothing fired (no launcher in range, no missile ammo, ...)")
            .define("split.report", true);

    public static final ModConfigSpec.BooleanValue SPLIT_AA_ONLY_UNMANNED = BUILDER
            .comment("Only remote-fire launchers that have no passenger (leave manned ones under player control)")
            .define("split.onlyUnmanned", true);

    // ── 数据链制导（底层原语，默认关闭，接线后由 datalink.enable 打开）──

    public static final ModConfigSpec.BooleanValue DATALINK_ENABLE = BUILDER
            .comment("Master switch for the datalink guidance layer (inert until wired into the steering loop)")
            .define("datalink.enable", true);

    public static final ModConfigSpec.IntValue DATALINK_UPLINK_INTERVAL = BUILDER
            .comment("Ticks between two successful uplinks on one channel - models the datalink data rate")
            .defineInRange("datalink.uplinkInterval", 5, 1, 100);

    public static final ModConfigSpec.IntValue DATALINK_LINK_TIMEOUT = BUILDER
            .comment("Ticks without uplink before a channel degrades to inertial flight")
            .defineInRange("datalink.linkTimeout", 40, 5, 600);

    public static final ModConfigSpec.IntValue DATALINK_HARD_TIMEOUT = BUILDER
            .comment("Ticks without uplink before the channel is lost (missile must rely on its own seeker)")
            .defineInRange("datalink.hardTimeout", 120, 10, 1200);

    public static final ModConfigSpec.IntValue DATALINK_MAX_CHANNELS = BUILDER
            .comment("Max simultaneous lock channels per radar vehicle (independent of missile count -",
                    "several missiles may share one channel, semi-active style)")
            .defineInRange("datalink.maxChannels", 4, 1, 16);

    public static final ModConfigSpec.BooleanValue DATALINK_AUTO_HANDOFF = BUILDER
            .comment("When the current datalink source degrades/loses the target, automatically",
                    "re-target the missile onto another radar vehicle that can still lock it")
            .define("datalink.autoHandoff", true);

    public static final ModConfigSpec.IntValue DATALINK_HANDOFF_COOLDOWN = BUILDER
            .comment("Ticks between handoff attempts per missile (prevents channel thrashing)")
            .defineInRange("datalink.handoffCooldown", 20, 1, 600);

    public static final ModConfigSpec.DoubleValue DATALINK_RANGE = BUILDER
            .comment("Datalink coverage radius around the radar vehicle (blocks); missiles outside get no updates")
            .defineInRange("datalink.range", 256.0, 16.0, 2048.0);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private SbwRadarConfig() {}
}
