package com.godwand;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

public class GodWandItem extends Item {
    public static final int SKILLS = 5;
    public static final int SUPERNOVA_TICKS = 140; // 7 segundos

    public GodWandItem(Settings settings) {
        super(settings);
    }

    // ---------- estado salvo no NBT da varinha ----------
    public static int getSkill(ItemStack s) {
        NbtCompound n = s.getNbt();
        return n == null ? 0 : MathHelper.clamp(n.getInt("Skill"), 0, SKILLS - 1);
    }
    public static void setSkill(ItemStack s, int v) { s.getOrCreateNbt().putInt("Skill", v); }
    public static boolean isActive(ItemStack s) { NbtCompound n = s.getNbt(); return n != null && n.getBoolean("Active"); }
    public static void setActive(ItemStack s, boolean v) { s.getOrCreateNbt().putBoolean("Active", v); }
    public static boolean isBridge(ItemStack s) { NbtCompound n = s.getNbt(); return n != null && n.getBoolean("Bridge"); }
    public static void setBridge(ItemStack s, boolean v) { s.getOrCreateNbt().putBoolean("Bridge", v); }

    @Override public boolean hasGlint(ItemStack stack) { return true; }
    @Override public int getMaxUseTime(ItemStack stack) { return 72000; }
    @Override public UseAction getUseAction(ItemStack stack) { return UseAction.BOW; }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        // Shift + botão direito: próxima habilidade
        if (user.isSneaking()) {
            if (!world.isClient && user instanceof ServerPlayerEntity sp) {
                SkillManager.setSkill(sp, stack, (getSkill(stack) + 1) % SKILLS);
                SkillManager.markSneakUsed(sp);
            }
            return TypedActionResult.success(stack, world.isClient);
        }

        // Singularidade ativa: clique direito desfaz antecipadamente
        if (!world.isClient && user instanceof ServerPlayerEntity sp && SkillManager.collapseSingularity(sp)) {
            return TypedActionResult.success(stack);
        }

        // Habilidade 1: liga/desliga
        if (getSkill(stack) == 0) {
            if (!world.isClient) {
                boolean on = !isActive(stack);
                setActive(stack, on);
                setBridge(stack, false);
                user.sendMessage(Text.translatable(on ? "msg.godwand.on" : "msg.godwand.off"), true);
            }
            return TypedActionResult.success(stack, world.isClient);
        }

        // Habilidades 2..5: carregar segurando o botão direito
        user.setCurrentHand(hand);
        return TypedActionResult.consume(stack);
    }

    @Override
    public void usageTick(World world, LivingEntity user, ItemStack stack, int remaining) {
        if (world.isClient || !(user instanceof ServerPlayerEntity p)) return;
        int skill = getSkill(stack);
        int charged = getMaxUseTime(stack) - remaining;
        SkillManager.chargeTick(p, skill, charged);
        if (skill == 3 && charged >= SUPERNOVA_TICKS) p.stopUsingItem(); // dispara onStoppedUsing
    }

    @Override
    public void onStoppedUsing(ItemStack stack, World world, LivingEntity user, int remaining) {
        if (world.isClient || !(user instanceof ServerPlayerEntity p)) return;
        int skill = getSkill(stack);
        int charged = getMaxUseTime(stack) - remaining;
        int cooldown = 0;
        switch (skill) {
            case 1 -> { if (charged >= 6) { SkillManager.fireSingularity(p); cooldown = 10; } }
            case 2 -> { if (charged >= 6 && SkillManager.fireDrill(p, level(charged, 12))) cooldown = 20; }
            case 3 -> { if (charged >= SUPERNOVA_TICKS && SkillManager.fireSupernova(p)) cooldown = 200; }
            case 4 -> { if (charged >= 6) { SkillManager.fireCannon(p, level(charged, 12)); cooldown = 15; } }
            default -> { }
        }
        SkillManager.endCharge(p);
        if (cooldown > 0) p.getItemCooldownManager().set(this, cooldown);
    }

    /** Nível de carga 1..4 */
    public static int level(int charged, int ticksPerLevel) {
        return MathHelper.clamp(1 + charged / ticksPerLevel, 1, 4);
    }
}
