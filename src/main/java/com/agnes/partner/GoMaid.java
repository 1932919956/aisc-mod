package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/** Optional maid dependency isolated from client menus and registry initialization. */
final class GoMaid {
    static boolean chessSeat(EntityMaid maid) {
        return maid.getVehicle() instanceof com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit seat
            && maid.level().hasChunkAt(seat.getAssociatedBlockPos())
            && maid.level().getBlockState(seat.getAssociatedBlockPos()).getBlock() instanceof com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku;
    }
    static boolean playing(EntityMaid maid, ServerPlayer player) {
        return MaidChessTable.playing(maid,player) || (player != null && player.containerMenu instanceof GoMenu menu && menu.forMaid(maid.getUUID()) && menu.stillValid(player));
    }
    static boolean eligible(EntityMaid maid, Player player) {
        return maid.isAlive() && player.isAlive() && !maid.isRemoved() && maid.level() == player.level()
            && player.getUUID().equals(maid.getOwnerUUID()) && maid.distanceToSqr(player) <= 16 * 16
            && !MaidBridge.maidReformBusy(maid) && !maid.isSleeping() && (!maid.isPassenger() || chessSeat(maid)) && maid.getTarget() == null;
    }
    static void recover(EntityMaid maid, ServerPlayer player) {
        var tag = MaidBridge.mind(maid);
        if (tag.contains("GoPreviousSit") && !playing(maid, player) && !MaidBridge.maidReformBusy(maid)) {
            // Recovery also covers world reloads or a dimension-transfer copy of the maid.
            maid.setOrderedToSit(tag.getBoolean("GoPreviousSit"));
            tag.remove("GoPreviousSit");
        }
    }
    static int open(ServerPlayer player) {
        return open(player, null);
    }
    static int open(ServerPlayer player, net.minecraft.core.BlockPos table) {
        if (player.containerMenu instanceof GoMenu) return 0;
        EntityMaid selected = select(player, table);
        if (selected == null) { player.sendSystemMessage(Component.literal("请靠近自己的女仆（16 格内），等她结束睡眠、战斗或救援后再下棋。")); return 0; }
        EntityMaid maid = selected;
        var opened = player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            GoMenu menu = new GoMenu(id, session(maid, player, table));
            menu.showChooser(); return menu;
        }, Component.literal(maid.getName().getString() + " · 棋类")));
        return opened.isPresent() ? 1 : 0;
    }
    static EntityMaid select(ServerPlayer player, net.minecraft.core.BlockPos table) {
        // The maid sitting at this table takes precedence over another bound maid nearby.
        if (table != null && player.level().getBlockEntity(table) instanceof com.github.tartaricacid.touhoulittlemaid.tileentity.TileEntityGomoku tile
            && tile.getSitId() != null && player.serverLevel().getEntity(tile.getSitId()) instanceof com.github.tartaricacid.touhoulittlemaid.entity.item.EntitySit seat) {
            for (var passenger : seat.getPassengers()) if (passenger instanceof EntityMaid seated) {
                if (eligible(seated, player)) return seated;
                // Never detach somebody else's maid or substitute another maid at an occupied table.
                return null;
            }
        }
        EntityMaid selected = MaidBridge.find(player);
        if (selected == null || !eligible(selected, player)) selected = player.serverLevel()
            .getEntitiesOfClass(EntityMaid.class, player.getBoundingBox().inflate(16), m -> eligible(m, player))
            .stream().min(java.util.Comparator.comparingDouble(m -> m.distanceToSqr(player))).orElse(null);
        return selected;
    }
    static GoMenu.Session session(EntityMaid maid, ServerPlayer player) {
        return session(maid, player, null);
    }
    static GoMenu.Session session(EntityMaid maid, ServerPlayer player, net.minecraft.core.BlockPos table) {
        var tag = MaidBridge.mind(maid);
        if (!tag.contains("ChessMigrated")) {
            if (tag.getString("GoRecord").startsWith("15;")) { tag.putString("GomokuRecord", tag.getString("GoRecord")); tag.remove("GoRecord"); }
            tag.putBoolean("ChessMigrated", true);
            if (tag.getInt("GoMode") == 0 && !tag.getString("GoRecord").isBlank()) tag.putInt("GoMode", 1);
        }
        for (String key : new String[]{"GoRecord", "GomokuRecord"}) try {
            if (key.equals("GoRecord")) GoRules.load(tag.getString(key)); else GomokuRules.load(tag.getString(key));
        } catch (RuntimeException invalid) {
            tag.putString(key + "Backup", tag.getString(key)); tag.remove(key);
            player.sendSystemMessage(Component.literal("旧棋局记录异常，已备份并重置该棋类。"));
        }
        tag.putBoolean("GoPreviousSit", maid.isOrderedToSit());
        // Pause native seat-driven chess without altering its board or discarding its pieces.
        if (chessSeat(maid)) maid.stopRiding();
        tag.putInt("PlanRevision", tag.getInt("PlanRevision") + 1);
        MaidFieldwork.cancel(maid, "开始围棋对弈，暂时停止采集");
        maid.setOrderedToSit(true); maid.getNavigation().stop(); maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        return new GoMenu.Session() {
            public boolean valid(Player p) { return p == player && eligible(maid, p)
                && (table == null || (p.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(table)) <= 64
                && p.level().hasChunkAt(table) && p.level().getBlockState(table).getBlock() instanceof com.github.tartaricacid.touhoulittlemaid.block.BlockGomoku)); }
            public String load() { return load(mode()); }
            public String load(int mode) { return tag.getString(mode == 2 ? "GomokuRecord" : "GoRecord"); }
            public void save(String record) { tag.putString(mode() == 2 ? "GomokuRecord" : "GoRecord", record); }
            public java.util.UUID maidId() { return maid.getUUID(); }
            public int mode() { return tag.getInt("GoMode"); }
            public void setMode(int mode) { tag.putInt("GoMode", mode); }
            public void close() {
                if (tag.contains("GoPreviousSit") && !MaidBridge.maidReformBusy(maid)) {
                    maid.setOrderedToSit(tag.getBoolean("GoPreviousSit"));
                    tag.remove("GoPreviousSit");
                }
                tag.putInt("PlanRevision", tag.getInt("PlanRevision") + 1);
                tag.putLong("NextThink", maid.level().getGameTime() + 100);
            }
        };
    }
}
