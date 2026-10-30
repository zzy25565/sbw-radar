package com.zzy.sbwradar;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/** Registers the addon's own payloads (mode cycling + state sync). */
@EventBusSubscriber(modid = SbwRadarMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class RadarNetwork {

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");

        registrar.playToServer(RadarPayloads.CycleMode.TYPE, RadarPayloads.CycleMode.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerRadarHandler.cycleMode(context.player(), payload.vehicleId())));

        registrar.playToServer(RadarPayloads.CycleTarget.TYPE, RadarPayloads.CycleTarget.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerRadarHandler.cycleTarget(context.player(), payload.vehicleId())));

        registrar.playToServer(RadarPayloads.CycleWidth.TYPE, RadarPayloads.CycleWidth.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerRadarHandler.cycleWidth(context.player(), payload.vehicleId())));

        registrar.playToServer(RadarPayloads.CycleVertical.TYPE, RadarPayloads.CycleVertical.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        ServerRadarHandler.cycleVertical(context.player(), payload.vehicleId())));

        registrar.playToServer(RadarPayloads.LockAmmo.TYPE, RadarPayloads.LockAmmo.CODEC,
                (payload, context) -> context.enqueueWork(() ->
                        CannonAirDefense.lockIncoming(context.player(), payload.vehicleId())));

        registrar.playToServer(RadarPayloads.Dispense.TYPE, RadarPayloads.Dispense.CODEC,
                (payload, context) -> context.enqueueWork(() -> CounterMeasures.dispense(
                        context.player(),
                        payload.kind() == 0 ? CounterMeasures.Kind.FLARE : CounterMeasures.Kind.CHAFF)));

        registrar.playToClient(RadarPayloads.IrState.TYPE, RadarPayloads.IrState.CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientIrState.set(payload)));

        registrar.playToClient(ThreatSync.TYPE, ThreatSync.CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientThreatState.set(payload)));

        registrar.playToClient(RadarPayloads.StateSync.TYPE, RadarPayloads.StateSync.CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientRadarState.set(payload)));
    }

    private RadarNetwork() {}
}
