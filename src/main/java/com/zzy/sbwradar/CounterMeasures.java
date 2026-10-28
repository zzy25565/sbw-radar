package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Chaff / flare dispensing.
 *
 * A dispense costs one cartridge and then rolls ONCE per missile in range:
 *  - flare only affects IR missiles, chaff only affects radar missiles
 *  - success probability is intentionally low (default 10%) so one press rarely saves you;
 *    carrying more cartridges is the real countermeasure
 *  - rounds are ordinary items, so they are finite and never auto-replenished
 */
public final class CounterMeasures {

    public enum Kind { FLARE, CHAFF }

    private static final Random RNG = new Random();

    /** player uuid + kind -> {lastDispenseTick, burstCount} */
    private static final java.util.Map<String, int[]> BURSTS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Escalating chance: base for the first cartridge, +step for each extra one in the burst. */
    private static double rolledChance(Player player, Kind kind, double base, ServerLevel level) {
        int now = level.getServer().getTickCount();
        int window = SbwRadarConfig.COUNTER_ESCALATION_WINDOW.get();
        if (BURSTS.size() > 64) {
            BURSTS.entrySet().removeIf(e -> now - e.getValue()[0] > window * 4);
        }
        String key = player.getStringUUID() + "|" + kind.name();
        int[] st = BURSTS.computeIfAbsent(key, k -> new int[]{-10000, 0});
        if (now - st[0] <= window) {
            st[1]++;
        } else {
            st[1] = 0;
        }
        st[0] = now;
        double chance = base + st[1] * SbwRadarConfig.COUNTER_ESCALATION_STEP.get();
        return Math.min(chance, SbwRadarConfig.COUNTER_ESCALATION_MAX.get());
    }

    public static void dispense(Player player, Kind kind) {
        if (!SbwRadarConfig.COUNTER_ENABLE.get()) return;
        Entity ridden = player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle) || vehicle.isWreck()) return;

        VehicleType type = vehicle.getVehicleType();
        if (type != VehicleType.AIRPLANE && type != VehicleType.HELICOPTER) return;
        if (!(player.level() instanceof ServerLevel level)) return;

        Vec3 pos = vehicle.position();
        if (kind == Kind.FLARE) {
            // 热诱弹走 SBW 自带的载具诱饵池：发射源、冷却、数量都由它管，
            // 弹药就是储物空间里的 superbwarfare:flying_flare_ammo
            if (!vehicle.hasDecoy()) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("没有热诱弹了"), true);
                return;
            }
            vehicle.releaseDecoy();
        } else {
            if (!consumeFromVehicle(vehicle, CounterItems.CHAFF.get(), player)) {
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("没有箔条了"), true);
                return;
            }
            int smoke = SbwRadarConfig.COUNTER_CHAFF_SMOKE.get();
            if (smoke > 0) {
                // few particles + tiny spread: vanilla particles cannot be made translucent,
                // so we keep the cloud as sparse as possible
                level.sendParticles(ParticleTypes.CLOUD, pos.x, pos.y + 0.3, pos.z,
                        smoke, 0.9, 0.25, 0.9, 0.005);
            }
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.WOOL_BREAK,
                    SoundSource.PLAYERS, 0.7F, 0.7F);
        }

        double radius = SbwRadarConfig.COUNTER_RADIUS.get();
        double base = kind == Kind.FLARE
                ? SbwRadarConfig.COUNTER_FLARE_CHANCE.get()
                : SbwRadarConfig.COUNTER_CHAFF_CHANCE.get();
        double chance = rolledChance(player, kind, base, level);

        int defeated = 0;
        if (kind == Kind.FLARE) {
            defeated = IrMissileControl.tryDecoy(level, pos, radius, chance, player.getStringUUID());
        } else {
            defeated = RadarMissileControl.tryDecoy(level, pos, radius, chance, player.getStringUUID());
        }
        if(kind==Kind.CHAFF)
            defeated += LoadoutIntegration.tryDecoy(level,pos,radius,chance,player.getStringUUID());

        if (defeated > 0) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    (kind == Kind.FLARE ? "热诱成功干扰 " : "箔条成功干扰 ") + defeated + " 发"), true);
        }
    }

    /** 箔条数量：以载具储物空间为准（找不到容器时回落到玩家背包）。 */
    public static int chaffCount(VehicleEntity vehicle) {
        return countIn(vehicle, CounterItems.CHAFF.get());
    }

    private static int countIn(VehicleEntity vehicle, Item item) {
        int n = 0;
        try {
            if (vehicle.hasContainer()) {
                var inv = vehicle.getInventory();
                for (int i = 0; i < inv.getSlots(); i++) {
                    var st = inv.getStackInSlot(i);
                    if (!st.isEmpty() && st.is(item)) n += st.getCount();
                }
            }
        } catch (Throwable ignored) {
        }
        return n;
    }

    /**
     * 先从载具储物空间扣一个，没有容器时回落到玩家背包。
     * 这样"内置"的语义成立：弹药随车走，人换了车照样有。
     */
    private static boolean consumeFromVehicle(VehicleEntity vehicle, Item item, Player player) {
        try {
            if (vehicle.hasContainer()) {
                var inv = vehicle.getInventory();
                for (int i = 0; i < inv.getSlots(); i++) {
                    var st = inv.getStackInSlot(i);
                    if (!st.isEmpty() && st.is(item)) {
                        inv.extractItem(i, 1, false);
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        var pinv = player.getInventory();
        for (int i = 0; i < pinv.getContainerSize(); i++) {
            var st = pinv.getItem(i);
            if (!st.isEmpty() && st.is(item)) {
                st.shrink(1);
                return true;
            }
        }
        return false;
    }

    private CounterMeasures() {}
}
