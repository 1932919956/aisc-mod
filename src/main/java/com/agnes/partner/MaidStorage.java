package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.items.ItemHandlerHelper;
import java.util.*;

/** Local access to ordinary vanilla storage; no remote extraction, loot generation or lock bypass. */
final class MaidStorage {
    /** Promaid-style automatic deposit: use the nearest accessible ordinary chest/barrel when the
     * maid has no room for a newly acquired stack. Only untagged items are moved, so named/enchanted
     * gear is never silently put away. */
    static String autoDeposit(EntityMaid maid) {
        BlockPos nearest = nearestAccessible(maid);
        if (nearest == null) return "";
        Container container = access(maid, nearest);
        if (container == null) return "";
        var inv = maid.getAvailableBackpackInv();
        int moved = 0;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty() || stack.hasTag()) continue;
            for (int slot = 0; slot < container.getContainerSize() && !stack.isEmpty(); slot++) {
                ItemStack current = container.getItem(slot);
                if (!container.canPlaceItem(slot, stack)) continue;
                if (!current.isEmpty() && !ItemStack.isSameItemSameTags(current, stack)) continue;
                int limit = Math.min(stack.getMaxStackSize(), container.getMaxStackSize());
                int room = current.isEmpty() ? limit : limit - current.getCount();
                if (room <= 0) continue;
                int amount = Math.min(room, stack.getCount());
                ItemStack movedStack = inv.extractItem(i, amount, false);
                if (movedStack.isEmpty()) continue;
                if (current.isEmpty()) container.setItem(slot, movedStack);
                else current.grow(movedStack.getCount());
                moved += movedStack.getCount();
                stack = inv.getStackInSlot(i);
            }
        }
        if (moved == 0) return "";
        container.setChanged();
        String result = "背包空间不足，已自动存入附近仓库 " + nearest.toShortString() + " ×" + moved;
        MaidBridge.mind(maid).putString("StorageResult", result);
        MaidBridge.mind(maid).putBoolean("StorageComplete", true);
        return result;
    }

    private static BlockPos nearestAccessible(EntityMaid maid) {
        BlockPos origin = maid.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -3, -8), origin.offset(8, 3, 8))) {
            BlockPos candidate = pos.immutable();
            double dx = candidate.getX() + 0.5 - maid.getX();
            double dy = candidate.getY() + 0.5 - maid.getY();
            double dz = candidate.getZ() + 0.5 - maid.getZ();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance > 16 * 16 || distance >= bestDistance) continue;
            if (!storage(maid.level().getBlockState(candidate))) continue;
            if (access(maid, candidate) == null) continue;
            best = candidate;
            bestDistance = distance;
        }
        return best;
    }
    static boolean storage(net.minecraft.world.level.block.state.BlockState state) { return state.is(Blocks.CHEST)||state.is(Blocks.BARREL); }
    static String id(BlockPos pos) {return "storage:"+pos.getX()+":"+pos.getY()+":"+pos.getZ();}
    static BlockPos position(String id) {
        try {String[] parts=id.split(":");if(parts.length!=4 || !parts[0].equals("storage"))return null;
            return new BlockPos(Integer.parseInt(parts[1]),Integer.parseInt(parts[2]),Integer.parseInt(parts[3]));
        } catch(RuntimeException invalid) {return null;}
    }
    private static boolean unlocked(BlockEntity entity) {
        if(!(entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity))return false;
        var tag=entity.saveWithoutMetadata();return tag.getString("Lock").isEmpty()&&!tag.contains("LootTable");
    }
    private static Container access(EntityMaid maid,BlockPos pos) {
        if(pos==null || !MaidWorkshop.visible(maid,pos))return null;
        var level=(ServerLevel)maid.level();var state=level.getBlockState(pos);
        if(!storage(state)||!unlocked(level.getBlockEntity(pos)))return null;
        List<BlockPos> parts=new ArrayList<>();parts.add(pos);
        if(state.is(Blocks.CHEST)&&state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE) {
            BlockPos other=pos.relative(ChestBlock.getConnectedDirection(state));
            if(!level.hasChunkAt(other)||!unlocked(level.getBlockEntity(other)))return null;
            parts.add(other);
        }
        var actor=FakePlayerFactory.get(level,new GameProfile(maid.getUUID(),"[AgnesMaid]"));actor.moveTo(maid.position());
        actor.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
        for(BlockPos part:parts) {
            if(!level.mayInteract(actor,part))return null;
            var event=new PlayerInteractEvent.RightClickBlock(actor,InteractionHand.MAIN_HAND,part,new BlockHitResult(Vec3.atCenterOf(part),Direction.UP,part,false));
            if(MinecraftForge.EVENT_BUS.post(event)||event.getUseBlock()==Event.Result.DENY)return null;
        }
        if(state.is(Blocks.CHEST))return ChestBlock.getContainer((ChestBlock)state.getBlock(),state,level,pos,false);
        return (Container)level.getBlockEntity(pos);
    }
    static JsonArray describe(EntityMaid maid) {
        Set<BlockPos> positions=new HashSet<>();
        for(var spot:MaidSurvey.spots(maid))if(spot.kind().equals("storage"))positions.add(spot.pos());
        for(BlockPos pos:BlockPos.betweenClosed(maid.blockPosition().offset(-4,-2,-4),maid.blockPosition().offset(4,2,4)))
            if(maid.level().hasChunkAt(pos)&&storage(maid.level().getBlockState(pos)))positions.add(pos.immutable());
        JsonArray result=new JsonArray();
        positions.stream().filter(p -> p.distToCenterSqr(maid.position())<=100*100 && maid.level().hasChunkAt(p)&&storage(maid.level().getBlockState(p)))
            .sorted(Comparator.comparingDouble(p -> p.distToCenterSqr(maid.position()))).limit(12).forEach(pos -> {
                JsonObject info=new JsonObject();info.addProperty("target_id",id(pos));info.addProperty("position",pos.toShortString());
                Container container=access(maid,pos);info.addProperty("can_access_now",container!=null);
                info.addProperty("hint",container==null?"Approach first; locked, protected, obstructed or unopened loot containers are excluded":"withdraw/deposit allowed; actual observed contents below");
                if(container!=null) {
                    JsonArray items=new JsonArray();Map<String,Integer> counts=new TreeMap<>();
                    for(int i=0;i<container.getContainerSize();i++) {
                        ItemStack stack=container.getItem(i);
                        if(!stack.isEmpty()&&!stack.hasTag())counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),Integer::sum);
                    }
                    counts.forEach((item,count)->{JsonObject entry=new JsonObject();entry.addProperty("item_id",item);entry.addProperty("count",count);items.add(entry);});
                    info.add("ordinary_items",items);
                }
                result.add(info);
            });return result;
    }
    static String transfer(EntityMaid maid,String target,String itemId,int amount,boolean deposit) {
        BlockPos pos=position(target);Container container=access(maid,pos);
        if(container==null)return "未转移：需要走到四格内可见、未上锁且允许访问的箱子或木桶旁（不打开未生成的战利品箱）";
        ResourceLocation key=ResourceLocation.tryParse(itemId);Item item=key==null?Items.AIR:BuiltInRegistries.ITEM.get(key);
        if(item==Items.AIR)return "未转移：物品 ID 无效";
        int requested=Math.max(1,Math.min(64,amount)),moved=0;var inv=maid.getAvailableBackpackInv();
        if(!deposit) {
            for(int i=0;i<container.getContainerSize()&&moved<requested;i++) {
                ItemStack stack=container.getItem(i);if(!stack.is(item)||stack.hasTag())continue;
                int wanted=Math.min(requested-moved,stack.getCount());
                int capacity=wanted-ItemHandlerHelper.insertItemStacked(inv,stack.copyWithCount(wanted),true).getCount();
                if(capacity<=0)continue;
                ItemStack removed=container.removeItem(i,capacity);int removedCount=removed.getCount();
                ItemStack remainder=ItemHandlerHelper.insertItemStacked(inv,removed,false);
                moved+=removedCount-remainder.getCount();
                if(!remainder.isEmpty()) {ItemStack current=container.getItem(i);if(current.isEmpty())container.setItem(i,remainder);else current.grow(remainder.getCount());}
            }
        } else {
            for(int i=0;i<inv.getSlots()&&moved<requested;i++) {
                ItemStack stack=inv.getStackInSlot(i);if(!stack.is(item)||stack.hasTag())continue;
                for(int slot=0;slot<container.getContainerSize()&&moved<requested;slot++) {
                    ItemStack current=container.getItem(slot);
                    if(!container.canPlaceItem(slot,stack)||(!current.isEmpty()&&!ItemStack.isSameItemSameTags(current,stack)))continue;
                    int space=Math.min(stack.getMaxStackSize(),container.getMaxStackSize())-current.getCount();
                    int count=Math.min(space,Math.min(requested-moved,inv.getStackInSlot(i).getCount()));if(count<=0)continue;
                    ItemStack removed=inv.extractItem(i,count,false);if(removed.isEmpty())continue;
                    if(current.isEmpty())container.setItem(slot,removed);else current.grow(removed.getCount());moved+=removed.getCount();
                }
            }
        }
        container.setChanged();
        if(moved>0)maid.swing(InteractionHand.MAIN_HAND);
        String result=moved==0?"未转移：没有普通物品、目标库存不足或接收方已满":("已实际"+(deposit?"存入":"取出")+" "+itemId+" ×"+moved+"（请求 "+requested+"）"+(moved<requested?"；仅完成部分数量":""));
        MaidBridge.mind(maid).putBoolean("StorageComplete",moved==requested);
        MaidBridge.mind(maid).putString("StorageResult",result);return result;
    }
}
