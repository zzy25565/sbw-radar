package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.entity.projectile.DestroyableProjectile;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 机炮防空：雷达"锁定"来袭弹药后，机炮自动换装对空炮弹（近炸）拦截；
 * 距离超出机炮有效射程时改打小型导弹（驾束制导——弹沿雷达波束飞向目标方向，
 * 无需锁定，波束随雷达跟踪实时摆动）。
 *
 * <p>与 {@link AmmoInterceptor} 的区别：那个是"最后手段"的抽象拦截（粒子 + 直接销毁），
 * 只给小型 AA；这个是<b>物理拦截</b>——真弹丸、真弹道、真引信，给所有带机炮的防空车。
 *
 * <p>流程（每车每 tick）：
 * <ol>
 *   <li>找来袭弹药：在探测半径内、正在逼近、非本车发射的投射物，取最近的一个；</li>
 *   <li>威胁距离 ≤ 机炮射程 → 机炮模式：解算提前量 → 自动把选中弹药切到
 *       {@code small_shell_aa}（找不到就保持原弹，由本类的近炸引信兜底）→
 *       {@code GunData.shoot} 真实开火；弹丸注册进近炸追踪；</li>
 *   <li>威胁距离 > 机炮射程 → 驾束导弹模式：冷却好了就发射一枚小导弹（不设 targetUuid），
 *       每 tick 把弹的目标点更新为「发射器 + 波束方向 × 64」，波束方向 = 车 → 威胁当前位置；
 *       弹进入引信半径即起爆。</li>
 * </ol>
 *
 * <p>近炸：拦截弹/导弹在威胁引信半径内自爆，并摧毁（或重创）来袭弹药。
 * 只跟踪本类注册的弹，world 里其它投射物零开销。
 */
public final class CannonAirDefense {

    /** 拦截弹 → 它追的威胁（近炸判定用） */
    private static final Map<Projectile, Entity> FUZE = new java.util.WeakHashMap<>();
    /** 驾束导弹 → 它追的威胁 */
    private static final Map<Projectile, Entity> BEAM = new java.util.WeakHashMap<>();
    /** 本车自己发射的拦截弹（威胁扫描时要排除） */
    private static final java.util.Set<Projectile> OWN = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new WeakHashMap<Projectile, Boolean>()));
    private static final Map<Integer, Integer> GUN_CD = new HashMap<>();
    private static final Map<Integer, Integer> SAM_CD = new HashMap<>();

    // ── 主循环 ──

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!SbwRadarConfig.SPEC.isLoaded()) return;
        if (!SbwRadarConfig.CAD_ENABLE.get()) return;

        for (var level : event.getServer().getAllLevels()) {
            if (level.players().isEmpty()) continue;
            for (Player player : level.players()) {
                if (!(player.getVehicle() instanceof VehicleEntity v) || v.isWreck()) continue;
                if (!isCannonAA(v)) continue;

                Entity threat = findThreat(level, v);
                if (threat == null) continue;

                double dist = threat.distanceTo(v);
                if (dist <= SbwRadarConfig.CAD_GUN_RANGE.get()) {
                    gunEngage(level, v, threat);
                } else if (SbwRadarConfig.BEAM_ENABLE.get() && dist <= SbwRadarConfig.BEAM_MAX_RANGE.get()) {
                    beamEngage(level, v, threat);
                }
            }
        }
        // 近炸判定 + 驾束波束更新
        if (level(event) instanceof ServerLevel sl) {
            tickFuzes(sl);
            tickBeams(sl);
        }
    }

    private static net.minecraft.world.level.Level level(ServerTickEvent.Post e) {
        // 威胁/弹可能分布在多个维度；这里只处理主世界（拦截战都发生在主世界）
        return e.getServer().overworld();
    }

    // ── 威胁探测 ──

    private static Entity findThreat(ServerLevel level, VehicleEntity v) {
        double radius = SbwRadarConfig.CAD_DETECT_RADIUS.get();
        Vec3 center = v.position().add(0.0, 1.5, 0.0);
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity e : level.getEntities(v, v.getBoundingBox().inflate(radius),
                e -> e instanceof Projectile && e.isAlive() && !OWN.contains(e))) {
            Projectile p = (Projectile) e;
            if (p.getOwner() == v) continue;
            Vec3 rel = e.position().subtract(center);
            double dist = rel.length();
            if (dist < 4.0 || dist > radius) continue;
            Vec3 dir = rel.scale(1.0 / dist);
            double closing = -e.getDeltaMovement().dot(dir);      // >0 = 正在逼近
            if (closing <= 0) continue;
            double score = dist - closing * 10;                   // 越近、越快的优先
            if (score < bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    // ── 机炮模式 ──

    private static void gunEngage(ServerLevel level, VehicleEntity v, Entity threat) {
        int cd = GUN_CD.getOrDefault(v.getId(), 0);
        if (cd > 0) {
            GUN_CD.put(v.getId(), cd - 1);
            return;
        }

        // 选一门机炮（名字含 gun/cannon 的第一门）
        GunData gun = null;
        for (GunData d : v.getGunDataMap().values()) {
            if (isCannonWeapon(d)) {
                gun = d;
                break;
            }
        }
        if (gun == null) return;

        Vec3 muzzle = gun.firePosition();
        Vec3 threatPos = threat.position();
        Vec3 threatVel = threat.getDeltaMovement();
        double shellSpeed = 48.0;                                  // 近似弹速（方块/秒→tick 内换算在解算里统一）

        // 两遍迭代解提前量（弹速按 tick 计：48/20 = 2.4 块/tick）
        double shellSpeedTick = shellSpeed / 20.0;
        Vec3 aim = threatPos;
        for (int i = 0; i < 2; i++) {
            double t = aim.distanceTo(muzzle) / Math.max(1.0, shellSpeedTick);
            aim = threatPos.add(threatVel.scale(t));                // 威胁 t tick 后的位置（vel 单位 块/tick）
        }
        Vec3 dir = aim.subtract(muzzle).normalize();
        if (dir.lengthSqr() < 1.0E-6) return;

        // 自动切换对空弹（近炸引信）：扫弹药索引，找到 small_shell_aa 就切；找不到保持原弹
        switchToAaShell(gun);

        try {
            gun.shoot(new ShootParameters(v, v, level, muzzle, dir,
                    gun, 0.6, false, null, aim));
        } catch (Throwable t2) {
            return;
        }
        level.playSound(null, muzzle.x, muzzle.y, muzzle.z,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.5F, 1.9F);
        registerOwnShells(level, muzzle, threat);
        GUN_CD.put(v.getId(), Math.max(1, SbwRadarConfig.CAD_FIRE_INTERVAL.get()));
    }

    /** 拦截弹入库：把枪口附近新生成的投射物登记为"自己的拦截弹"并挂近炸。 */
    private static void registerOwnShells(ServerLevel level, Vec3 muzzle, Entity threat) {
        for (Entity e : level.getEntities((Entity) null,
                new net.minecraft.world.phys.AABB(muzzle, muzzle).inflate(3.0),
                e -> e instanceof Projectile p && p.isAlive() && !OWN.contains(p) && !FUZE.containsKey(p))) {
            OWN.add((Projectile) e);
            FUZE.put((Projectile) e, threat);
        }
    }

    /** 扫弹药索引找对空弹；找不到返回 false（保持原弹，近炸兜底）。 */
    private static void switchToAaShell(GunData gun) {
        try {
            int cur = gun.selectedAmmoType.get();
            for (int i = 0; i < 8; i++) {
                gun.selectedAmmoType.set(i);
                String pid = ammoIdOf(gun);
                if (pid != null && pid.contains("aa")) return;       // 已切到对空弹
            }
            gun.selectedAmmoType.set(cur);                           // 没有 aa 弹，恢复
        } catch (Throwable ignored) {
        }
    }

    private static String ammoIdOf(GunData gun) {
        try {
            String proj = null;
            try {
                var c = gun.selectedAmmoConsumer();
                if (c != null) proj = RadarMissileControl.projectileIdOf(c);
            } catch (Throwable ignored) {
            }
            if (proj != null) return proj;
            if (gun.stack != null && !gun.stack.isEmpty()) {
                return BuiltInRegistries.ITEM.getKey(gun.stack.getItem()).getPath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isCannonWeapon(GunData d) {
        try {
            String id = String.valueOf(d.item());
            String pid = ammoIdOf(d);
            String hay = (id + ' ' + (pid == null ? "" : pid)).toLowerCase();
            return hay.contains("cannon") || hay.contains("gun") || hay.contains("aa")
                    || hay.contains("shell");
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 驾束导弹模式 ──

    private static void beamEngage(ServerLevel level, VehicleEntity v, Entity threat) {
        int cd = SAM_CD.getOrDefault(v.getId(), 0);
        if (cd > 0) {
            SAM_CD.put(v.getId(), cd - 1);
            return;
        }
        for (Map.Entry<Projectile, Entity> en : BEAM.entrySet()) {
            if (en.getValue() == threat) return;                     // 已有导弹在追这个威胁
        }

        try {
            ResourceLocation rl = ResourceLocation.tryParse(SbwRadarConfig.BEAM_ENTITY.get());
            if (rl == null) return;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(rl);
            Entity missile = type.create(level);
            if (missile == null) return;

            Vec3 launch = v.position().add(0.0, 2.4, 0.0);
            Vec3 dir = threat.position().add(0.0, 0.5, 0.0).subtract(launch).normalize();
            missile.moveTo(launch.x + dir.x, launch.y + dir.y, launch.z + dir.z,
                    (float) (Math.toDegrees(Math.atan2(-dir.x, dir.z))),
                    (float) (-Math.toDegrees(Math.asin(dir.y))));
            missile.setDeltaMovement(dir.scale(2.4));
            if (!level.addFreshEntity(missile)) return;

            if (missile instanceof Projectile p) {
                p.setOwner(v);
                BEAM.put(p, threat);
            }
            level.playSound(null, launch.x, launch.y, launch.z,
                    SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.PLAYERS, 0.8F, 1.2F);
            SAM_CD.put(v.getId(), Math.max(1, SbwRadarConfig.BEAM_COOLDOWN.get()));
        } catch (Throwable ignored) {
        }
    }

    /** 驾束：每 tick 把弹的目标点更新为「发射器 → 威胁当前位置」的波束延长线；近炸起爆。 */
    private static void tickBeams(ServerLevel level) {
        if (BEAM.isEmpty()) return;
        for (Iterator<Map.Entry<Projectile, Entity>> it = BEAM.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Projectile, Entity> en = it.next();
            Projectile missile = en.getKey();
            Entity threat = en.getValue();
            if (missile.isRemoved() || !missile.isAlive()) {
                it.remove();
                continue;
            }
            if (threat == null || !threat.isAlive()) {                // 威胁没了：弹自毁
                detonate(level, missile, 1.2);
                it.remove();
                continue;
            }
            Entity launcher = missile.getOwner();
            Vec3 from = launcher != null && launcher.isAlive() ? launcher.position() : missile.position();
            Vec3 beam = threat.position().add(0.0, 0.4, 0.0).subtract(from).normalize();
            if (missile instanceof com.atsuishio.superbwarfare.entity.projectile.MissileProjectile mp) {
                mp.setTargetVec(from.add(beam.scale(80.0)));          // 沿波束飞
                mp.setLost(false);
            }
            if (missile.position().distanceTo(threat.position())
                    <= SbwRadarConfig.BEAM_FUZE_RADIUS.get()) {
                detonate(level, missile, 2.0);
                killThreat(level, threat);
                it.remove();
            }
        }
    }

    // ── 近炸（机炮弹丸 vs 来袭弹药）──

    private static void tickFuzes(ServerLevel level) {
        if (FUZE.isEmpty()) return;
        double radius = SbwRadarConfig.CAD_FUZE_RADIUS.get();
        for (Iterator<Map.Entry<Projectile, Entity>> it = FUZE.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Projectile, Entity> en = it.next();
            Projectile shell = en.getKey();
            Entity threat = en.getValue();
            if (shell.isRemoved() || !shell.isAlive()) {
                it.remove();
                continue;
            }
            if (threat == null || !threat.isAlive()) {                // 威胁已由别的弹处理
                it.remove();
                continue;
            }
            if (shell.position().distanceTo(threat.position()) <= radius) {
                detonate(level, shell, 1.0);
                killThreat(level, threat);
                it.remove();
            }
        }
    }

    private static void detonate(ServerLevel level, Entity e, double scale) {
        level.sendParticles(ParticleTypes.EXPLOSION, e.getX(), e.getY(), e.getZ(),
                (int) (2 * scale), 0.2, 0.2, 0.2, 0);
        level.sendParticles(ParticleTypes.POOF, e.getX(), e.getY(), e.getZ(), 4, 0.3, 0.3, 0.3, 0.01);
        e.discard();
    }

    private static void killThreat(ServerLevel level, Entity threat) {
        if (threat instanceof DestroyableProjectile dp) {
            dp.setHealth(0.0F);
        }
        threat.discard();
        level.sendParticles(ParticleTypes.EXPLOSION, threat.getX(), threat.getY(), threat.getZ(),
                3, 0.3, 0.3, 0.3, 0);
        level.playSound(null, threat.getX(), threat.getY(), threat.getZ(),
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8F, 1.4F);
    }

    // ── 判定 ──

    /** 所有带机炮武器的防空车（Type=AA）。 */
    public static boolean isCannonAA(VehicleEntity v) {
        return v.getVehicleType() == com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType.AA;
    }
}
