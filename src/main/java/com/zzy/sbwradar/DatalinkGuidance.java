package com.zzy.sbwradar;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据链制导底层（原语层，不直接操纵弹体）。
 *
 * <p><b>设计目标（用户定义）</b>：雷达制导弹不依赖"实时无限精度"数据——它通过数据链接收
 * <b>任意一台能锁定目标的雷达车</b>上行的数据。主雷达脱锁时，自动寻找其它可以锁定同一目标的
 * 雷达车换主（handoff），弹按新雷达上行的数据继续追踪，直到自身导引头开机（终端阶段）。
 * <b>红外弹没有数据链</b>，不参与本层（{@link IrMissileControl} 的弹永远不会出现在订阅表里）。
 *
 * <p>三层概念：
 *
 * <ul>
 *   <li><b>信道 Channel</b> —— 「某台雷达 × 某个目标」的一条链路。同一目标可以同时被多台雷达
 *       各开一条信道（这正是换主的候选池）；一发弹同一时刻只订阅一条。</li>
 *   <li><b>订阅 Subscription</b> —— 弹 → 信道。弹必须在当前主雷达的数据链覆盖半径内。</li>
 *   <li><b>链路质量</b> —— 超过 {@code linkTimeout} 无上行 → DEGRADED（惯性外推）；
 *       超过 {@code hardTimeout} → LOST。DEGRADED/LOST 时触发<b>自动换主</b>：
 *       在 {@link ServerRadarHandler#radarsTracking(int)} 给出的候选雷达里找一台
 *       （活着、在弹的数据链覆盖内、信道配额未满）开新信道并切换。</li>
 * </ul>
 *
 * <p><b>刻意不做的事</b>：本层不转向弹体，只回答 {@link #get(Entity, Vec3, long)}——
 * "此刻这发弹手里的目标数据、多新、来自哪台雷达、可不可信"。转向仍由
 * {@link RadarMissileControl} 的制导循环执行。
 *
 * <p><b>终端语义</b>：导引头开机 = 上层的 terminal 阶段。本层不判断"导引头是否开机"，
 * 只暴露 {@code ageTicks} 与 {@code state}；上层在进入 terminal 后可以
 * {@link #unsubscribe(Entity)} 主动断链（弹已不再需要数据链）。
 *
 * <p>线程模型：全部方法只在服务端 tick 调用。
 */
public final class DatalinkGuidance {

    // ── 数据结构 ──

    public enum State { TRACKING, DEGRADED, LOST }

    /** 一条「雷达 × 目标」链路。 */
    public static final class Channel {
        public final int id;
        public final int radarVehicleId;
        public final UUID targetUuid;
        public int targetEntityId = -1;
        public Vec3 lastKnown = Vec3.ZERO;
        public Vec3 lastVelocity = Vec3.ZERO;   // 相邻两次上行差分，供惯性外推
        public long lastUplinkTick;
        public State state = State.TRACKING;
        public final Map<UUID, Boolean> subscribers = new ConcurrentHashMap<>();

        Channel(int id, int radarVehicleId, UUID targetUuid) {
            this.id = id;
            this.radarVehicleId = radarVehicleId;
            this.targetUuid = targetUuid;
        }
    }

    /** 一发弹的订阅。 */
    public static final class Subscription {
        public final int channelId;
        public final String ownerUuid;        // 发射者（敌我判定透传；"" = 雷达指挥发射）
        public long linkedSinceTick;
        public long lastHandoffTick = -1_000_000;
        Subscription(int channelId, String ownerUuid, long tick) {
            this.channelId = channelId;
            this.ownerUuid = ownerUuid;
            this.linkedSinceTick = tick;
        }
    }

    /** 弹查询到的目标数据快照——制导循环唯一该消费的东西。 */
    public record TargetData(State state, Vec3 position, Vec3 velocity,
                             int ageTicks, int channelId, int radarVehicleId) {
        public boolean hasFix() {
            return state != State.LOST && position != null;
        }
    }

    // ── 注册表 ──

    private static final Map<Integer, Channel> CHANNELS = new ConcurrentHashMap<>();
    private static final Map<UUID, Subscription> SUBS = new ConcurrentHashMap<>();     // key: 弹 uuid
    private static final Map<UUID, Entity> MISSILES = new ConcurrentHashMap<>();      // uuid → 弹实体
    private static int NEXT_ID = 1;

    private DatalinkGuidance() {}

    // ── 信道生命周期 ──

    /**
     * 打开（或复用）一条「雷达 × 目标」信道。
     *
     * @return 信道 id；该雷达信道配额已满返回 -1
     */
    public static int open(int radarVehicleId, Entity target, long nowTick) {
        int quota = Math.max(1, SbwRadarConfig.DATALINK_MAX_CHANNELS.get());
        int used = 0;
        for (Channel c : CHANNELS.values()) {
            if (c.radarVehicleId == radarVehicleId && c.state != State.LOST) used++;
        }
        for (Channel c : CHANNELS.values()) {
            if (c.radarVehicleId == radarVehicleId
                    && c.targetUuid.equals(target.getUUID()) && c.state != State.LOST) {
                refresh(c, target, nowTick);
                return c.id;
            }
        }
        if (used >= quota) return -1;
        Channel c = new Channel(NEXT_ID++, radarVehicleId, target.getUUID());
        CHANNELS.put(c.id, c);
        refresh(c, target, nowTick);
        return c.id;
    }

    /** 某台雷达完成一次扫描：刷新它对指定目标的信道（受上行间隔节流）。 */
    public static void refresh(int radarVehicleId, Entity target, long nowTick) {
        for (Channel c : CHANNELS.values()) {
            if (c.radarVehicleId != radarVehicleId || !c.targetUuid.equals(target.getUUID())) continue;
            refresh(c, target, nowTick);
        }
    }

    private static void refresh(Channel c, Entity target, long nowTick) {
        int interval = Math.max(1, SbwRadarConfig.DATALINK_UPLINK_INTERVAL.get());
        if (nowTick - c.lastUplinkTick < interval) return;    // 数据率节流
        Vec3 newPos = target.position();
        if (c.lastUplinkTick > 0) {
            long dt = Math.max(1, nowTick - c.lastUplinkTick);
            c.lastVelocity = new Vec3((newPos.x - c.lastKnown.x) / dt,
                                      (newPos.y - c.lastKnown.y) / dt,
                                      (newPos.z - c.lastKnown.z) / dt);
        }
        c.lastKnown = newPos;
        c.targetEntityId = target.getId();
        c.lastUplinkTick = nowTick;
        c.state = State.TRACKING;
    }

    // ── 订阅 ──

    public static boolean subscribe(Entity missile, int channelId, String ownerUuid, long nowTick) {
        Channel c = CHANNELS.get(channelId);
        if (c == null || c.state == State.LOST) return false;
        SUBS.put(missile.getUUID(), new Subscription(channelId, ownerUuid, nowTick));
        MISSILES.put(missile.getUUID(), missile);
        c.subscribers.put(missile.getUUID(), Boolean.TRUE);
        return true;
    }

    public static void unsubscribe(Entity missile) {
        Subscription s = SUBS.remove(missile.getUUID());
        MISSILES.remove(missile.getUUID());
        if (s != null) {
            Channel c = CHANNELS.get(s.channelId);
            if (c != null) c.subscribers.remove(missile.getUUID());
        }
    }

    /** 雷达车被毁/卸载时，把它名下的信道与订阅全部清掉。 */
    public static void dropRadar(int radarVehicleId) {
        for (Iterator<Map.Entry<UUID, Subscription>> it = SUBS.entrySet().iterator(); it.hasNext(); ) {
            Subscription s = it.next().getValue();
            Channel c = CHANNELS.get(s.channelId);
            if (c == null || c.radarVehicleId == radarVehicleId) it.remove();
        }
        CHANNELS.values().removeIf(c -> c.radarVehicleId == radarVehicleId);
    }

    // ── 查询（制导循环唯一入口）──

    /**
     * 弹此刻手里的目标数据。
     *
     * <ul>
     *   <li>弹距<b>当前主雷达</b>超过 {@code datalink.range} → 收不到更新，DEGRADED；</li>
     *   <li>超 {@code linkTimeout} 无上行 → DEGRADED，位置按 lastVelocity 惯性外推；</li>
     *   <li>超 {@code hardTimeout} → LOST（上层应转自寻的或自毁）。</li>
     * </ul>
     */
    public static TargetData get(Entity missile, Vec3 radarPos, long nowTick) {
        Subscription s = SUBS.get(missile.getUUID());
        if (s == null) return LOST_DATA(-1, -1);
        Channel c = CHANNELS.get(s.channelId);
        if (c == null) return LOST_DATA(-1, -1);

        int age = (int) (nowTick - c.lastUplinkTick);
        State state = c.state;

        if (radarPos != null) {
            Entity radar = findRadar(missile.level(), c.radarVehicleId);
            Vec3 rp = radar != null ? radar.position() : radarPos;
            if (missile.position().distanceTo(rp) > SbwRadarConfig.DATALINK_RANGE.get()) {
                state = State.DEGRADED;                        // 飞出数据链覆盖
            }
        }
        if (age > Math.max(1, SbwRadarConfig.DATALINK_HARD_TIMEOUT.get())) {
            state = State.LOST;
        } else if (age > Math.max(1, SbwRadarConfig.DATALINK_LINK_TIMEOUT.get())
                && state == State.TRACKING) {
            state = State.DEGRADED;
        }

        Vec3 pos = c.lastKnown;
        if (state != State.TRACKING && age > 0) {
            pos = pos.add(c.lastVelocity.scale(age));          // 惯性外推
        }
        return new TargetData(state, pos, c.lastVelocity, age, c.id, c.radarVehicleId);
    }

    private static TargetData LOST_DATA(int ch, int radar) {
        return new TargetData(State.LOST, null, Vec3.ZERO, Integer.MAX_VALUE, ch, radar);
    }

    private static Entity findRadar(net.minecraft.world.level.Level level, int id) {
        return level instanceof ServerLevel sl ? sl.getEntity(id) : null;
    }

    // ── tick：自动换主 + 老信道回收 ──

    /**
     * 每服务端 tick 调一次。
     *
     * <p><b>自动换主</b>：订阅的信道处于 DEGRADED/LOST 时，在
     * {@link ServerRadarHandler#radarsTracking(int)} 的候选里找一台<b>其它</b>雷达车——
     * 活着、不残骸、在弹的数据链覆盖内、信道配额未满——开新信道并切换。
     * 换主有冷却（{@code datalink.handoffCooldown}），避免反复跳台。
     */
    public static void tick(MinecraftServer server) {
        if (!SbwRadarConfig.SPEC.isLoaded()) return;
        long now = server.getTickCount();
        int hard = Math.max(1, SbwRadarConfig.DATALINK_HARD_TIMEOUT.get());

        // 1. 自动换主
        if (SbwRadarConfig.DATALINK_AUTO_HANDOFF.get()) {
            for (Map.Entry<UUID, Subscription> e : SUBS.entrySet()) {
                Subscription s = e.getValue();
                Channel c = CHANNELS.get(s.channelId);
                if (c == null || c.state == State.TRACKING) continue;   // 主链路健康，不折腾
                if (now - s.lastHandoffTick < Math.max(1, SbwRadarConfig.DATALINK_HANDOFF_COOLDOWN.get()))
                    continue;
                Entity missile = MISSILES.get(e.getKey());
                if (missile == null || !missile.isAlive()) continue;

                for (int rid : ServerRadarHandler.radarsTracking(c.targetEntityId)) {
                    if (rid == c.radarVehicleId) continue;              // 排除当前主雷达
                    Entity radar = findRadar(missile.level(), rid);
                    if (!(radar instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity rv)
                            || rv.isWreck()) continue;
                    if (missile.position().distanceTo(radar.position())
                            > SbwRadarConfig.DATALINK_RANGE.get()) continue;
                    Entity target = missile.level().getEntity(c.targetEntityId);
                    if (target == null || !target.isAlive()) continue;

                    int ch = open(rid, target, now);
                    if (ch > 0 && ch != s.channelId) {
                        String owner = s.ownerUuid;
                        unsubscribe(missile);
                        subscribe(missile, ch, owner, now);
                        Subscription ns = SUBS.get(missile.getUUID());
                        if (ns != null) ns.lastHandoffTick = now;
                        break;                                          // 本 tick 只换一次
                    }
                }
            }
        }

        // 2. 老信道回收
        CHANNELS.values().removeIf(c -> {
            Entity radar = null;
            for (ServerLevel level : server.getAllLevels()) {
                Entity e = level.getEntity(c.radarVehicleId);
                if (e != null) {
                    radar = e;
                    break;
                }
            }
            if (radar == null) {                                        // 雷达没了：订阅弹转自寻的
                for (UUID u : c.subscribers.keySet()) SUBS.remove(u);
                return true;
            }
            if (c.state == State.TRACKING && now - c.lastUplinkTick > hard) {
                c.state = State.DEGRADED;
            }
            return false;
        });
        CHANNELS.values().removeIf(c -> c.subscribers.isEmpty()
                && now - c.lastUplinkTick > hard);
    }

    // ── 信息展示 / 状态 ──

    public static int channelCount(int radarVehicleId) {
        int n = 0;
        for (Channel c : CHANNELS.values()) {
            if (c.radarVehicleId == radarVehicleId && c.state != State.LOST) n++;
        }
        return n;
    }

    /** 弹当前的数据链主雷达 id；无链路返回 -1（HUD 用）。 */
    public static int currentRadar(Entity missile) {
        Subscription s = SUBS.get(missile.getUUID());
        if (s == null) return -1;
        Channel c = CHANNELS.get(s.channelId);
        return c == null ? -1 : c.radarVehicleId;
    }

    public static boolean hasLink(Entity missile) {
        Subscription s = SUBS.get(missile.getUUID());
        return s != null && CHANNELS.containsKey(s.channelId)
                && CHANNELS.get(s.channelId).state != State.LOST;
    }
}
