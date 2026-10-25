package com.godwand;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;

public class GodWandMod implements ModInitializer {
    public static final String MOD_ID = "godwand";
    /** Pacote cliente -> servidor. action 0 = escolher habilidade (value = 0..4), action 1 = cancelar Singularidade. */
    public static final Identifier SKILL_PACKET = new Identifier(MOD_ID, "skill");

    public static final Item GOD_WAND = new GodWandItem(new FabricItemSettings().maxCount(1).rarity(Rarity.EPIC).fireproof());

    public static final ItemGroup GROUP = FabricItemGroup.builder()
            .icon(() -> new ItemStack(GOD_WAND))
            .displayName(Text.translatable("itemGroup.godwand"))
            .entries((ctx, entries) -> entries.add(GOD_WAND))
            .build();

    @Override
    public void onInitialize() {
        Registry.register(Registries.ITEM, new Identifier(MOD_ID, "god_wand"), GOD_WAND);
        Registry.register(Registries.ITEM_GROUP, new Identifier(MOD_ID, "main"), GROUP);

        ServerTickEvents.END_SERVER_TICK.register(SkillManager::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(SkillManager::shutdown);

        ServerPlayNetworking.registerGlobalReceiver(SKILL_PACKET, (server, player, handler, buf, sender) -> {
            int action = buf.readInt();
            int value = buf.readInt();
            server.execute(() -> {
                if (action == 0) SkillManager.selectSkill(player, value);
                else if (action == 1) SkillManager.collapseSingularity(player);
            });
        });

        // Clique esquerdo (bloco / entidade) com a varinha desfaz a Singularidade antecipadamente
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity sp && player.getStackInHand(hand).isOf(GOD_WAND)
                    && SkillManager.collapseSingularity(sp)) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!world.isClient && player instanceof ServerPlayerEntity sp && player.getStackInHand(hand).isOf(GOD_WAND)
                    && SkillManager.collapseSingularity(sp)) return ActionResult.FAIL;
            return ActionResult.PASS;
        });
    }
}
