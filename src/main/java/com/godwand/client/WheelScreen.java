package com.godwand.client;

import com.godwand.GodWandItem;
import com.godwand.GodWandMod;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/** Roda de habilidades: enquanto V está pressionado, aponte o mouse; ao soltar, a habilidade é escolhida. */
public class WheelScreen extends Screen {
    private int hovered = -1;
    private final int keyCode;

    public WheelScreen() {
        super(Text.translatable("screen.godwand.wheel"));
        this.keyCode = KeyBindingHelper.getBoundKeyOf(GodWandClient.WHEEL).getCode();
    }

    @Override public boolean shouldPause() { return false; }

    private int[] slot(int i) {
        double a = Math.toRadians(-90 + i * 72);
        return new int[]{(int) (width / 2 + Math.cos(a) * 95), (int) (height / 2 + Math.sin(a) * 75)};
    }

    private int current() {
        if (client == null || client.player == null) return 0;
        ItemStack st = client.player.getMainHandStack().isOf(GodWandMod.GOD_WAND) ? client.player.getMainHandStack()
                : client.player.getOffHandStack();
        return st.isOf(GodWandMod.GOD_WAND) ? GodWandItem.getSkill(st) : 0;
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        hovered = -1;
        if (Math.hypot(mx - width / 2.0, my - height / 2.0) > 25) {
            double best = Double.MAX_VALUE;
            for (int i = 0; i < GodWandItem.SKILLS; i++) {
                int[] p = slot(i);
                double d = Math.hypot(mx - p[0], my - p[1]);
                if (d < best) { best = d; hovered = i; }
            }
        }
        int cur = current();
        for (int i = 0; i < GodWandItem.SKILLS; i++) {
            int[] p = slot(i);
            int color = i == hovered ? 0xDD3FB7FF : (i == cur ? 0xDD6B3FD4 : 0xAA101018);
            ctx.fill(p[0] - 46, p[1] - 12, p[0] + 46, p[1] + 12, color);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.translatable("skill.godwand." + i), p[0], p[1] - 4, 0xFFFFFF);
        }
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 4, 0xAAAAAA);
    }

    @Override
    public void tick() {
        if (client == null) return;
        if (!InputUtil.isKeyPressed(client.getWindow().getHandle(), keyCode)) {
            if (hovered >= 0) GodWandClient.send(0, hovered);
            close();
        }
    }
}
