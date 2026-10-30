package com.zzy.sbwradar;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import static com.zzy.sbwradar.SbwRadarMod.MODID;

/**
 * 服务端 → 客户端：当前载具探测到的来袭弹药（机炮防空的屏幕标记用）。
 *
 * <p>每 5 tick 随检测节拍下发；威胁消失时发一包 has=false 清掉标记。
 */
public record ThreatSync(int vehicleId, boolean has, float x, float y, float z)
        implements CustomPacketPayload {
    public static final Type<ThreatSync> TYPE = new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(MODID, "threat_sync"));
    public static final StreamCodec<FriendlyByteBuf, ThreatSync> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.vehicleId());
                buf.writeBoolean(p.has());
                if (p.has()) {
                    buf.writeFloat(p.x());
                    buf.writeFloat(p.y());
                    buf.writeFloat(p.z());
                }
            },
            buf -> {
                int vid = buf.readVarInt();
                boolean has = buf.readBoolean();
                if (has) {
                    float x = buf.readFloat(), y = buf.readFloat(), z = buf.readFloat();
                    return new ThreatSync(vid, true, x, y, z);
                }
                return new ThreatSync(vid, false, 0, 0, 0);
            });

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
