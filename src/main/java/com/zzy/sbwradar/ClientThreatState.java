package com.zzy.sbwradar;

import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧的来袭弹药标记状态（由 {@link ThreatSync} 下发）。
 *
 * <p>只保留最近一次收到的威胁：骑乘载具 id、世界坐标、收到时的客户端 tick。
 * 渲染层每帧读取，超过存活窗口（约 2 秒没刷新）就不再绘制。
 */
public final class ClientThreatState {

    public record Threat(int vehicleId, Vec3 pos, long receivedTick) {}

    private static volatile Threat threat = null;
    private static long clientTick = 0;

    private ClientThreatState() {}

    public static void set(ThreatSync p) {
        threat = p.has() ? new Threat(p.vehicleId(), new Vec3(p.x(), p.y(), p.z()), clientTick) : null;
    }

    /** 每 tick 由 ClientRenderers 驱动，用于老化判断。 */
    public static void tick() {
        clientTick++;
    }

    /** 当前骑乘载具对应的、2 秒内有效的威胁；无则 null。 */
    public static Threat get(int vehicleId, long now) {
        Threat t = threat;
        if (t == null || t.vehicleId() != vehicleId) return null;
        return now - t.receivedTick() <= 40 ? t : null;
    }

    public static long now() {
        return clientTick;
    }

    public static void clear() {
        threat = null;
    }
}
