package com.agnes.partner;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.*;
import java.util.Optional;
import java.util.function.Supplier;

public final class GoContent {
    static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, AgnesPartnerMod.MOD_ID);
    static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(ForgeRegistries.MENU_TYPES, AgnesPartnerMod.MOD_ID);
    public static final RegistryObject<MenuType<GoMenu>> MENU = MENUS.register("go", () -> IForgeMenuType.create((id, inv, buf) -> new GoMenu(id)));
    public static final RegistryObject<MenuType<TableMenu>> TABLE = MENUS.register("table_controls", () -> IForgeMenuType.create((id, inv, buf) -> new TableMenu(id)));
    public static final RegistryObject<Item> BOOK = ITEMS.register("go_book", () -> new Item(new Item.Properties().stacksTo(1)) {
        @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
            if (player instanceof ServerPlayer serverPlayer) open(serverPlayer);
            return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide);
        }
    });
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(AgnesPartnerMod.MOD_ID, "go"), () -> "2", "2"::equals, "2"::equals);
    public record Action(int menuId, int action) {
        static void encode(Action msg, FriendlyByteBuf buf) { buf.writeInt(msg.menuId); buf.writeInt(msg.action); }
        static Action decode(FriendlyByteBuf buf) { return new Action(buf.readInt(), buf.readInt()); }
        static void handle(Action msg, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context ctx = supplier.get();
            ctx.enqueueWork(() -> {
                ServerPlayer player = ctx.getSender();
                if (player != null && player.containerMenu instanceof GoMenu menu && menu.containerId == msg.menuId)
                    menu.clickMenuButton(player, msg.action);
                else if (player != null && player.containerMenu instanceof TableMenu menu && menu.containerId == msg.menuId)
                    menu.clickMenuButton(player, msg.action);
            });
            ctx.setPacketHandled(true);
        }
    }
    static void register(IEventBus bus) {
        ITEMS.register(bus); MENUS.register(bus);
        // DeferredRegister owns the complete menu registry.  The table menu supplier is only
        // instantiated when the menu is opened, and MaidChessTable already guards the optional
        // Touhou Little Maid dependency, so it is safe to register it together with the book.
        CHANNEL.registerMessage(0, Action.class, Action::encode, Action::decode, Action::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }
    public static void send(int menuId, int action) { CHANNEL.sendToServer(new Action(menuId, action)); }
    static int open(ServerPlayer player) {
        if (!AgnesPartnerMod.hasMaidMod()) { player.sendSystemMessage(Component.literal("围棋需要安装车万女仆模组。")); return 0; }
        return MaidChessTable.openNearby(player);
    }
}
