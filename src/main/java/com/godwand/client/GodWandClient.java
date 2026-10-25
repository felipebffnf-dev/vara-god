package com.godwand.client;

import com.godwand.GodWandMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.network.PacketByteBuf;
import org.lwjgl.glfw.GLFW;

public class GodWandClient implements ClientModInitializer {
    public static KeyBinding WHEEL;
    private static boolean lastAttack = false;

    @Override
    public void onInitializeClient() {
        WHEEL = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.godwand.wheel", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, "category.godwand"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;
            boolean holding = client.player.getMainHandStack().isOf(GodWandMod.GOD_WAND)
                    || client.player.getOffHandStack().isOf(GodWandMod.GOD_WAND);

            // Segurar V abre a roda de habilidades
            if (holding && client.currentScreen == null && WHEEL.isPressed()) client.setScreen(new WheelScreen());

            // Clique esquerdo no ar: desfaz a Singularidade antecipadamente
            boolean attack = client.options.attackKey.isPressed();
            if (holding && attack && !lastAttack && client.currentScreen == null) send(1, 0);
            lastAttack = attack;
        });
    }

    public static void send(int action, int value) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(action);
        buf.writeInt(value);
        ClientPlayNetworking.send(GodWandMod.SKILL_PACKET, buf);
    }
}
