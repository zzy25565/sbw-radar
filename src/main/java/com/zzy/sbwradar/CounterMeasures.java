package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.entity.projectile.FlareDecoyEntity;
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

        Item needed = kind == Kind.FLARE ? CounterItems.FLARE.get() : CounterItems.CHAFF.get();
        if (!consume(player, needed)) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(
                            kind == Kind.FLARE ? "没有热诱弹了" : "没有箔条了"), true);
            return;
        }

        Vec3 pos = vehicle.position();
        if (kind == Kind.FLARE) {
            // real decoy entity: gives SBW's own visuals/sound and can also bait native missiles
            FlareDecoyEntity decoy = new FlareDecoyEntity(level);
            decoy.moveTo(pos.x, pos.y + 0.5, pos.z, RNG.nextFloat() * 360.0F, 0.0F);
            decoy.setDeltaMovement(vehicle.getDeltaMovement().scale(0.4).add(
                    (RNG.nextDouble() - 0.5) * 0.3, -0.15, (RNG.nextDouble() - 0.5) * 0.3));
            level.addFreshEntity(decoy);
            level.playSound(null, pos.x, pos.y, pos.z, SoundEvents.FIREWORK_ROCKET_BLAST,
                    SoundSource.PLAYERS, 0.6F, 1.6F);
        } else {
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

        if (defeated > 0) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    (kind == Kind.FLARE ? "热诱成功干扰 " : "箔条成功干扰 ") + defeated + " 发"), true);
        }
    }

    private static boolean consume(Player player, Item item) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    private CounterMeasures() {}
}
