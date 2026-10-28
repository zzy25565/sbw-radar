package com.zzy.sbwradar;

import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SBW Radar - AA vehicle radar targeting addon for Superb Warfare.
 *
 * Provides:
 *  1. Server-side radar injection for stock AA vehicles without a native "Radar" JSON section
 *     (reuses SBW's RadarScanner, so third-party addon vehicles are detected automatically).
 *  2. Open API (RadarApi) for other addons: target filters + custom radar sources.
 *
 * Compatible with Superb Warfare 0.8.9.1 and 0.8.9.2 (radar API verified identical).
 */
@Mod(SbwRadarMod.MODID)
public final class SbwRadarMod {
    public static final String MODID = "sbw_radar";
    public static final Logger LOGGER = LoggerFactory.getLogger("SBW Radar");

    public SbwRadarMod(net.neoforged.bus.api.IEventBus bus) {
        CounterItems.init(bus);
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.SERVER, SbwRadarConfig.SPEC);
        ModLoadingContext.get().getActiveContainer().registerConfig(ModConfig.Type.CLIENT, SbwRadarClientConfig.SPEC);
        NeoForge.EVENT_BUS.register(ServerRadarHandler.class);
        NeoForge.EVENT_BUS.register(RadarMissileControl.class);
        NeoForge.EVENT_BUS.register(SplitAirDefense.class);   // 分体式防空：雷达车指挥发射车
        NeoForge.EVENT_BUS.register(IrMissileControl.class);
        NeoForge.EVENT_BUS.register(AmmoInterceptor.class);
        NeoForge.EVENT_BUS.register(AaProximityFuze.class);
        // ERA / APS live in the separate sbw_defense addon (主防与爆反).
        // Lead solve and HUD are pure client-side math on already-synced data - no server round trip.
        if (FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.register(ClientRenderers.class);
            NeoForge.EVENT_BUS.register(TacticalMapOverlay.class);
            NeoForge.EVENT_BUS.register(ClientInput.class);   // client tick only
            bus.addListener(ClientInput::registerKeys);      // key mappings live on the MOD bus
        }
    }
}
