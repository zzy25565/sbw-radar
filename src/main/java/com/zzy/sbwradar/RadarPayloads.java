package com.zzy.sbwradar;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Network payloads for radar mode control and state sync.
 * Strings are sent as UTF-8 with a varint length (bounded to keep packets small).
 */
public final class RadarPayloads {

    public static final int MAX_STR = 64;

    public static void writeStr(FriendlyByteBuf buf, String s) {
        String v = s == null ? "" : s;
        if (v.length() > MAX_STR) v = v.substring(0, MAX_STR);
        byte[] b = v.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        buf.writeByte(b.length);
        buf.writeBytes(b);
    }

    public static String readStr(FriendlyByteBuf buf) {
        int len = buf.readUnsignedByte();
        byte[] b = new byte[len];
        buf.readBytes(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Client -> server: cycle the radar mode of the ridden vehicle. */
    public record CycleMode(int vehicleId) implements CustomPacketPayload {
        public static final Type<CycleMode> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SbwRadarMod.MODID, "cycle_mode"));
        public static final StreamCodec<FriendlyByteBuf, CycleMode> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.vehicleId()),
                buf -> new CycleMode(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: cycle the locked target in TRK mode. */
    public record CycleTarget(int vehicleId) implements CustomPacketPayload {
        public static final Type<CycleTarget> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SbwRadarMod.MODID, "cycle_target"));
        public static final StreamCodec<FriendlyByteBuf, CycleTarget> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.vehicleId()),
                buf -> new CycleTarget(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: cycle the radar scan width. */
    public record CycleWidth(int vehicleId) implements CustomPacketPayload {
        public static final Type<CycleWidth> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SbwRadarMod.MODID, "cycle_width"));
        public static final StreamCodec<FriendlyByteBuf, CycleWidth> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeVarInt(p.vehicleId()),
                buf -> new CycleWidth(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: dispense a countermeasure (0 = flare, 1 = chaff). */
    public record Dispense(int kind) implements CustomPacketPayload {
        public static final Type<Dispense> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SbwRadarMod.MODID, "dispense"));
        public static final StreamCodec<FriendlyByteBuf, Dispense> CODEC = StreamCodec.of(
                (buf, p) -> buf.writeByte(p.kind()),
                buf -> new Dispense(buf.readUnsignedByte()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: IR seeker lock state (drives the lock tone). */
    public record IrState(boolean locked, int distance, String targetName) implements CustomPacketPayload {
        public static final Type<IrState> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SbwRadarMod.MODID, "ir_state"));
        public static final StreamCodec<FriendlyByteBuf, IrState> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.locked());
                    buf.writeVarInt(p.distance());
                    writeStr(buf, p.targetName());
                },
                buf -> new IrState(buf.readBoolean(), buf.readVarInt(), readStr(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: current mode / lock state for the player's vehicle. */
    public record StateSync(int vehicleId, String mode, int targetId, String targetName, int trackedCount,
                            int widthDeg, int guidedCount)
            implements CustomPacketPayload {
        public static final Type<StateSync> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(SbwRadarMod.MODID, "state"));
        public static final StreamCodec<FriendlyByteBuf, StateSync> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeVarInt(p.vehicleId());
                    writeStr(buf, p.mode());
                    buf.writeVarInt(p.targetId());
                    writeStr(buf, p.targetName());
                    buf.writeVarInt(p.trackedCount());
                    buf.writeVarInt(p.widthDeg());
                    buf.writeVarInt(p.guidedCount());
                },
                buf -> new StateSync(buf.readVarInt(), readStr(buf), buf.readVarInt(), readStr(buf),
                        buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private RadarPayloads() {}
}
