package com.agnes.partner;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

@Mod(AgnesPartnerMod.MOD_ID)
public final class AgnesPartnerMod {
    public static final String MOD_ID = "agnespartner";
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, MOD_ID);
    public static final RegistryObject<EntityType<AgnesCompanion>> COMPANION = ENTITIES.register("companion", () -> EntityType.Builder.of(AgnesCompanion::new, MobCategory.CREATURE).sized(0.6f, 1.8f).clientTrackingRange(10).build("companion"));

    public AgnesPartnerMod() {
        IEventBus bus = net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();
        ENTITIES.register(bus);
        GoContent.register(bus);
        bus.addListener(this::attributes);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, PartnerConfig.SPEC, PartnerConfig.configFileName());
        MinecraftForge.EVENT_BUS.register(this);
        if (hasMaidMod()) {
            MinecraftForge.EVENT_BUS.register(new MaidBridge());
            MinecraftForge.EVENT_BUS.register(new MaidRescue());
            MinecraftForge.EVENT_BUS.register(new MaidChessTable());
        }
    }

    private void attributes(EntityAttributeCreationEvent event) { event.put(COMPANION.get(), AgnesCompanion.attributes().build()); }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        if (hasMaidMod()) MaidBridge.commands(dispatcher);
        dispatcher.register(Commands.literal("aipartner")
            .then(Commands.literal("go").executes(ctx -> GoContent.open(ctx.getSource().getPlayerOrException())))
            .then(Commands.literal("summon").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                AgnesCompanion companion = COMPANION.get().create((ServerLevel) player.level());
                if (companion == null) return 0;
                companion.moveTo(player.getX() + 1.5, player.getY(), player.getZ() + 1.5, player.getYRot(), 0);
                companion.setOwnerName(player.getGameProfile().getName());
                companion.setCustomName(Component.literal("Agnes伙伴"));
                companion.setCustomNameVisible(true);
                player.level().addFreshEntity(companion);
                player.sendSystemMessage(Component.literal("Agnes AI 伙伴已加入这个世界。"));
                return 1;
            }))
            .then(Commands.literal("remove").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException(); int removed = 0;
                for (AgnesCompanion companion : player.serverLevel().getEntitiesOfClass(AgnesCompanion.class, player.getBoundingBox().inflate(128))) {
                    if (companion.getOwnerName().equals(player.getGameProfile().getName())) { companion.discard(); removed++; }
                }
                player.sendSystemMessage(Component.literal("已移除 " + removed + " 个 AI 伙伴。")); return removed;
            }))
            .then(Commands.literal("status").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                AgnesCompanion current = findCompanion(player);
                if (hasMaidMod() && MaidBridge.hasBinding(player)) { MaidBridge.status(player); return 1; }
                String text = "Agnes 伙伴：" + (PartnerConfig.getApiKey().isBlank() ? "未配置 API Key" : "已配置 API Key")
                    + "；视觉模型：" + (PartnerConfig.isVisionEnabled() ? "Agnes 2.5 Flash" : "已关闭")
                    + "；自主模式：" + (current == null ? (PartnerConfig.isAutonomousMode() ? "默认开启" : "默认关闭") : (current.isAutonomousMode() ? "开启" : "关闭"))
                    + (current == null ? "" : "；当前活动：" + current.getActiveAction() + "（" + current.getActiveActionSeconds() + "秒）")
                    + "；Promaid：" + PromaidCompat.status()
                    + "，使用 /aipartner summon 召唤。";
                player.sendSystemMessage(Component.literal(text)); return 1;
            }))
            .then(Commands.literal("inventory").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                if (hasMaidMod() && MaidBridge.hasBinding(player)) { MaidBridge.inventory(player, false); return 1; }
                AgnesCompanion found = player.serverLevel().getEntitiesOfClass(AgnesCompanion.class, player.getBoundingBox().inflate(128)).stream()
                    .filter(companion -> companion.getOwnerName().equals(player.getGameProfile().getName())).findFirst().orElse(null);
                if (found == null) {
                    player.sendSystemMessage(Component.literal("附近没有你的 Agnes 伙伴。"));
                    return 0;
                }
                player.sendSystemMessage(Component.literal("Agnes 背包：" + found.getInventorySummary()));
                return 1;
            }))
            .then(Commands.literal("knowledge").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                if (hasMaidMod() && MaidBridge.hasBinding(player)) { MaidBridge.inventory(player, true); return 1; }
                AgnesCompanion found = findCompanion(player);
                if (found == null) {
                    player.sendSystemMessage(Component.literal("附近没有你的 Agnes 伙伴。"));
                    return 0;
                }
                String knowledge = ModpackKnowledge.describe(player.serverLevel(), found.blockPosition(), found.getInventorySummary(), "", player.getMainHandItem());
                player.sendSystemMessage(Component.literal("Agnes 已读取整合包知识：" + knowledge));
                return 1;
            }))
            .then(Commands.literal("recipe").then(Commands.argument("item", StringArgumentType.greedyString()).executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                var result = ModpackKnowledge.recipes(player.serverLevel(), StringArgumentType.getString(ctx, "item"), player.getMainHandItem());
                for (String line : ModpackKnowledge.recipeLines(result)) player.sendSystemMessage(Component.literal(line));
                return 1;
            })))
            .then(Commands.literal("autonomy").then(Commands.literal("on").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                if (hasMaidMod() && MaidBridge.hasBinding(player)) { MaidBridge.autonomy(player, true); return 1; }
                AgnesCompanion found = findCompanion(player);
                if (found == null) { player.sendSystemMessage(Component.literal("附近没有你的 Agnes 伙伴。")); return 0; }
                found.setAutonomousMode(true);
                player.sendSystemMessage(Component.literal("Agnes 自主模式已开启，它会开始自己选择活动。"));
                return 1;
            })).then(Commands.literal("off").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                if (hasMaidMod() && MaidBridge.hasBinding(player)) { MaidBridge.autonomy(player, false); return 1; }
                AgnesCompanion found = findCompanion(player);
                if (found == null) { player.sendSystemMessage(Component.literal("附近没有你的 Agnes 伙伴。")); return 0; }
                found.setAutonomousMode(false);
                player.sendSystemMessage(Component.literal("Agnes 自主模式已关闭，它会回到跟随和保护模式。"));
                return 1;
            })))); 
    }

    @SubscribeEvent
    public void datapackSync(net.minecraftforge.event.OnDatapackSyncEvent event) {
        // Fired after datapack reload, also on login; rebuilding is safe in either case.
        ModpackKnowledge.invalidate();
    }

    public static boolean hasMaidMod() { return net.minecraftforge.fml.ModList.get().isLoaded("touhou_little_maid"); }

    public static boolean maidBound(ServerPlayer player) { return hasMaidMod() && MaidBridge.hasBinding(player); }

    private AgnesCompanion findCompanion(ServerPlayer player) {
        return player.serverLevel().getEntitiesOfClass(AgnesCompanion.class, player.getBoundingBox().inflate(128)).stream()
            .filter(companion -> companion.getOwnerName().equals(player.getGameProfile().getName())).findFirst().orElse(null);
    }

    @SubscribeEvent
    public void chat(ServerChatEvent event) {
        String text = event.getRawText().trim();
        ServerPlayer player = event.getPlayer();
        if (hasMaidMod() && MaidBridge.chat(player, text)) return;
        for (AgnesCompanion companion : player.serverLevel().getEntitiesOfClass(AgnesCompanion.class, player.getBoundingBox().inflate(96))) {
            if (companion.getOwnerName().equals(player.getGameProfile().getName())) { companion.hear(text); break; }
        }
    }
}
