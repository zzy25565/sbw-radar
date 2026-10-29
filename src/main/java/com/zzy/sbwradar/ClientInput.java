package com.zzy.sbwradar;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * Client keybinds for radar control:
 *   K - cycle radar mode (SRC / TWS / TRK)
 *   J - cycle locked target (TRK only)
 * (B is taken by the ZZY vehicles launch animation, R by SBW reload.)
 */
public final class ClientInput {

    public static final KeyMapping CYCLE_MODE = new KeyMapping(
            "key.sbw_radar.cycle_mode", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.sbw_radar");

    public static final KeyMapping CYCLE_TARGET = new KeyMapping(
            "key.sbw_radar.cycle_target", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.sbw_radar");

    public static final KeyMapping DISPENSE_FLARE = new KeyMapping(
            "key.sbw_radar.flare", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.sbw_radar");

    public static final KeyMapping DISPENSE_CHAFF = new KeyMapping(
            "key.sbw_radar.chaff", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, "key.categories.sbw_radar");

    public static final KeyMapping CYCLE_WIDTH = new KeyMapping(
            "key.sbw_radar.cycle_width", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_L, "key.categories.sbw_radar");

    public static final KeyMapping CYCLE_VERTICAL = new KeyMapping(
            "key.sbw_radar.cycle_vertical", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SEMICOLON, "key.categories.sbw_radar");

    /** MOD-bus listener: registered explicitly from the mod constructor (not the game bus). */
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(CYCLE_MODE);
        event.register(CYCLE_TARGET);
        event.register(CYCLE_WIDTH);
        event.register(CYCLE_VERTICAL);
        event.register(DISPENSE_FLARE);
        event.register(DISPENSE_CHAFF);
    }

    private static Object lastLevel = null;
    private static int onFootTicks = 0;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            ClientRadarState.clear(); // drop stale modes after dimension change / respawn
            ClientIrState.clear();
        }
        if (mc.player == null) return;

        // keep the contact memory from growing while no radar HUD is drawn
        ClientRenderers.tickMemory();

        // on foot for a while: drop the last vehicle's radar state (no stale mode text)
        if (mc.player.getVehicle() == null) {
            if (++onFootTicks > 40) ClientRadarState.clear();
        } else {
            onFootTicks = 0;
        }

        // IR seeker lock tone
        if (ClientIrState.tickTone() && mc.player != null) {
            mc.player.playSound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 0.35F, 1.9F);
        }

        boolean modePressed = consume(CYCLE_MODE);
        boolean targetPressed = consume(CYCLE_TARGET);
        boolean widthPressed = consume(CYCLE_WIDTH);
        boolean verticalPressed = consume(CYCLE_VERTICAL);
        boolean flarePressed = consume(DISPENSE_FLARE);
        boolean chaffPressed = consume(DISPENSE_CHAFF);
        // 修既有 bug：早退条件漏了诱饵弹，只按 G/H 时会被提前 return 掉
        if (!modePressed && !targetPressed && !widthPressed && !verticalPressed
                && !flarePressed && !chaffPressed) return;

        Entity ridden = mc.player.getVehicle();
        if (!(ridden instanceof VehicleEntity vehicle)) return;

        if (modePressed) {
            PacketDistributor.sendToServer(new RadarPayloads.CycleMode(vehicle.getId()));
        }
        if (targetPressed) {
            PacketDistributor.sendToServer(new RadarPayloads.CycleTarget(vehicle.getId()));
        }
        if (widthPressed) {
            PacketDistributor.sendToServer(new RadarPayloads.CycleWidth(vehicle.getId()));
        }
        if (verticalPressed) {
            PacketDistributor.sendToServer(new RadarPayloads.CycleVertical(vehicle.getId()));
        }
        if (flarePressed) {
            PacketDistributor.sendToServer(new RadarPayloads.Dispense(0));
        }
        if (chaffPressed) {
            PacketDistributor.sendToServer(new RadarPayloads.Dispense(1));
        }
    }

    private static boolean consume(KeyMapping key) {
        while (key.consumeClick()) {
            return true;
        }
        return false;
    }

    private ClientInput() {}
}
