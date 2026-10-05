package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.items.ItemHandlerHelper;
import java.util.*;

/** Small real-world workstations. Uses real items, placement hooks and vanilla furnace ticking. */
final class MaidWorkshop {
    static final String OWNER = "AgnesWorkshopOwner";
    private static final String JOB = "AgnesSmeltJob";
    private static final Set<Item> PLACEABLE = Set.of(Items.CRAFTING_TABLE, Items.FURNACE, Items.TORCH);
    static boolean active(EntityMaid maid) { return MaidBridge.mind(maid).contains(JOB); }
    static boolean visible(EntityMaid maid, BlockPos pos) {
        return pos.distToCenterSqr(maid.position()) <= 16 && maid.level().hasChunkAt(pos)
            && maid.level().clip(new ClipContext(maid.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, maid)).getBlockPos().equals(pos);
    }
    private static List<FurnaceBlockEntity> furnaces(EntityMaid maid) {
        List<FurnaceBlockEntity> result = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(maid.blockPosition().offset(-4,-2,-4), maid.blockPosition().offset(4,2,4))) {
            if (visible(maid,p) && maid.level().getBlockEntity(p) instanceof FurnaceBlockEntity f
                && f.getPersistentData().hasUUID(OWNER) && f.getPersistentData().getUUID(OWNER).equals(maid.getUUID())) result.add(f);
        }
        return result;
    }
    static JsonObject describe(EntityMaid maid) {
        JsonObject result = new JsonObject();
        result.addProperty("active", active(maid));
        result.addProperty("last_result", MaidBridge.mind(maid).getString("WorkshopResult"));
        result.addProperty("own_furnace_in_reach", !furnaces(maid).isEmpty());
        JsonArray place = new JsonArray(); PLACEABLE.stream().sorted(Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).toString()))
            .forEach(i -> place.add(BuiltInRegistries.ITEM.getKey(i).toString()));
        result.add("placeable_item_ids", place);
        JsonArray options = new JsonArray();
        var inv = maid.getAvailableBackpackInv();
        Set<Item> seen = new HashSet<>();
        for (int i=0;i<inv.getSlots();i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty() || !seen.add(stack.getItem()) || stack.hasTag()) continue;
            var recipe = maid.level().getRecipeManager().getRecipeFor(RecipeType.SMELTING, new SimpleContainer(stack), maid.level());
            if (recipe.isEmpty() || !recipe.get().getId().getNamespace().equals("minecraft")) continue;
            JsonObject option = new JsonObject();
            option.addProperty("input", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            option.addProperty("output", BuiltInRegistries.ITEM.getKey(recipe.get().getResultItem(maid.level().registryAccess()).getItem()).toString());
            options.add(option);
        }
        result.add("smelt_options", options); return result;
    }
    static String place(EntityMaid maid, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Item item = key == null ? Items.AIR : BuiltInRegistries.ITEM.get(key);
        if (!PLACEABLE.contains(item)) return "未放置：仅支持自己的工作台、熔炉和火把";
        if (!PartnerConfig.mayEditWorld(maid.level(),maid)) return "未放置：世界规则禁止改变方块";
        int slot = find(maid,item); if (slot < 0) return "未放置：背包没有 " + id;
        ServerLevel level = (ServerLevel)maid.level();
        var actor = FakePlayerFactory.get(level,new GameProfile(maid.getUUID(),"[AgnesMaid]"));
        actor.setGameMode(GameType.SURVIVAL); actor.moveTo(maid.position());
        try {
            List<BlockPos> candidates = new ArrayList<>();
            for (BlockPos p : BlockPos.betweenClosed(maid.blockPosition().offset(-3,-1,-3),maid.blockPosition().offset(3,1,3)))
                if (p.distToCenterSqr(maid.position()) <= 12 && p.distToCenterSqr(maid.position()) >= 2) candidates.add(p.immutable());
            candidates.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(maid.position())));
            for (BlockPos p : candidates) {
                if (!level.hasChunkAt(p) || !level.hasChunkAt(p.below()) || !level.isEmptyBlock(p) || !level.isEmptyBlock(p.above())
                    || !level.getBlockState(p.below()).isFaceSturdy(level,p.below(),Direction.UP)
                    || !level.getEntities(maid,new net.minecraft.world.phys.AABB(p)).isEmpty()
                    || !level.mayInteract(actor,p) || !level.mayInteract(actor,p.below())) continue;
                Vec3 face = Vec3.atCenterOf(p.below()).add(0,0.5,0);
                var hit = level.clip(new ClipContext(maid.getEyePosition(),face,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,maid));
                if (!hit.getBlockPos().equals(p.below()) && !hit.getBlockPos().equals(p)) continue;
                ItemStack held = maid.getAvailableBackpackInv().extractItem(slot,1,false);
                if (held.isEmpty()) return "未放置：材料已变化";
                actor.setItemSlot(EquipmentSlot.MAINHAND,held);
                try {
                    held.useOn(new UseOnContext(actor,InteractionHand.MAIN_HAND,new BlockHitResult(face,Direction.UP,p.below(),false)));
                    if (level.getBlockState(p).is(((BlockItem)item).getBlock())) {
                        if (level.getBlockEntity(p) instanceof FurnaceBlockEntity furnace) {
                            furnace.getPersistentData().putUUID(OWNER,maid.getUUID()); furnace.setChanged();
                        }
                        maid.swing(InteractionHand.MAIN_HAND);
                        // She placed it herself, so she should be able to find it again later.
                        String kind = id.endsWith("crafting_table") ? "table" : id.endsWith("furnace") ? "furnace" : "";
                        if (!kind.isEmpty()) MaidLandmarks.remember(maid, kind, p, null);
                        return "已放置 " + id + " 于 " + p.toShortString();
                    }
                    return "未放置：领地保护或方块放置规则拒绝了操作";
                } finally {
                    ItemStack remainder = actor.getMainHandItem().copy(); actor.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY);
                    ItemStack dropped = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(),remainder,false);
                    if (!dropped.isEmpty()) maid.spawnAtLocation(dropped);
                }
            }
            return "未放置：身边没有可见、平整且不挡住实体的空位";
        } finally { actor.setItemSlot(EquipmentSlot.MAINHAND,ItemStack.EMPTY); }
    }
    private static int find(EntityMaid maid, Item item) {
        var inv=maid.getAvailableBackpackInv();
        for(int i=0;i<inv.getSlots();i++) if(inv.getStackInSlot(i).is(item) && !inv.getStackInSlot(i).hasTag()) return i;
        return -1;
    }
    static String smelt(EntityMaid maid, String inputId) {
        if (active(maid)) return "未启动：已有烧炼正在进行";
        ResourceLocation id=ResourceLocation.tryParse(inputId);
        Item item=id==null?Items.AIR:BuiltInRegistries.ITEM.get(id);
        int inputSlot=find(maid,item);
        if(inputSlot<0 || item==Items.AIR) return "未启动：背包没有这种烧炼材料";
        var recipe=maid.level().getRecipeManager().getRecipeFor(RecipeType.SMELTING,new SimpleContainer(new ItemStack(item)),maid.level());
        if(recipe.isEmpty() || !recipe.get().getId().getNamespace().equals("minecraft")) return "未启动：只支持原版熔炉配方";
        var furnace=furnaces(maid).stream().filter(f -> f.isEmpty()).findFirst().orElse(null);
        if(furnace==null) return "未启动：需要四格内可见、自己放置且物品槽为空的熔炉";
        // Use a single batch; no accelerated ticking, no free heat, no taking another player's output.
        int fuelSlot=find(maid,Items.COAL); if(fuelSlot<0) fuelSlot=find(maid,Items.CHARCOAL);
        if(fuelSlot<0) for(Item fuel : List.of(Items.OAK_PLANKS,Items.SPRUCE_PLANKS,Items.BIRCH_PLANKS,Items.JUNGLE_PLANKS,Items.ACACIA_PLANKS,Items.DARK_OAK_PLANKS,Items.MANGROVE_PLANKS,Items.CHERRY_PLANKS)) {
            fuelSlot=find(maid,fuel); if(fuelSlot>=0) break;
        }
        if(fuelSlot<0 || fuelSlot==inputSlot) return "未启动：需要煤、木炭或额外木板作燃料";
        var inv=maid.getAvailableBackpackInv();
        ItemStack input=inv.extractItem(inputSlot,1,false), fuel=inv.extractItem(fuelSlot,1,false);
        if(input.isEmpty() || fuel.isEmpty()) {
            ItemHandlerHelper.insertItemStacked(inv,input,false); ItemHandlerHelper.insertItemStacked(inv,fuel,false);
            return "未启动：材料提取失败";
        }
        ItemStack output=recipe.get().getResultItem(maid.level().registryAccess());
        furnace.setItem(0,input); furnace.setItem(1,fuel); furnace.setChanged();
        CompoundTag job=new CompoundTag(); job.putLong("pos",furnace.getBlockPos().asLong());
        job.putString("dimension",maid.level().dimension().location().toString());
        job.putString("output",BuiltInRegistries.ITEM.getKey(output.getItem()).toString()); job.putInt("count",output.getCount());
        job.putUUID("id",UUID.randomUUID()); job.putLong("started",maid.level().getGameTime());
        furnace.getPersistentData().putUUID(JOB,job.getUUID("id"));
        MaidBridge.mind(maid).put(JOB,job); MaidBridge.mind(maid).putBoolean("WorkshopSuccess",false);
        maid.getNavigation().stop(); maid.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
        return "已放入真实熔炉：" + inputId + "，等待原版烧炼完成后取回";
    }
    static void cancel(EntityMaid maid,String reason) {
        if(!active(maid)) return;
        var data=MaidBridge.mind(maid);
        String location=BlockPos.of(data.getCompound(JOB).getLong("pos")).toShortString();
        data.remove(JOB); data.putBoolean("WorkshopSuccess",false);
        data.putString("WorkshopResult",reason+"；已投入物品保留在熔炉 "+location);
        MaidBridge.outcome(maid,data.getString("WorkshopResult"));
    }
    static void tick(EntityMaid maid) {
        if(!active(maid)) return;
        var data=MaidBridge.mind(maid); var job=data.getCompound(JOB); BlockPos pos=BlockPos.of(job.getLong("pos"));
        if(!maid.level().dimension().location().toString().equals(job.getString("dimension")) || !maid.level().hasChunkAt(pos)
            || !(maid.level().getBlockEntity(pos) instanceof FurnaceBlockEntity)) { cancel(maid,"熔炉已失效或未加载"); return; }
        FurnaceBlockEntity furnace=(FurnaceBlockEntity)maid.level().getBlockEntity(pos);
        if(!furnace.getPersistentData().hasUUID(JOB) || !furnace.getPersistentData().getUUID(JOB).equals(job.getUUID("id"))) { cancel(maid,"熔炉已被替换"); return; }
        ItemStack output=furnace.getItem(2);
        // Reassert a stationary walk target while attending the furnace; native combat still interrupts.
        if(visible(maid,pos)) {
            maid.getNavigation().stop();
            maid.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                new net.minecraft.world.entity.ai.memory.WalkTarget(maid.position(),0.6f,1));
            maid.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        }
        if(visible(maid,pos) && BuiltInRegistries.ITEM.getKey(output.getItem()).toString().equals(job.getString("output")) && output.getCount()>=job.getInt("count")) {
            ItemStack claim=output.copyWithCount(job.getInt("count"));
            if(!ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(),claim.copy(),true).isEmpty()) { cancel(maid,"背包装不下烧炼产物"); return; }
            ItemStack taken=furnace.removeItem(2,claim.getCount());
            ItemStack rest=ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(),taken,false);
            if(!rest.isEmpty()) maid.spawnAtLocation(rest);
            // Return remaining fuel; preserve furnace's already burning heat.
            ItemStack fuel=furnace.getItem(1);
            if(!fuel.isEmpty() && ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(),fuel.copy(),true).isEmpty())
                ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(),furnace.removeItem(1,fuel.getCount()),false);
            furnace.getPersistentData().remove(JOB); furnace.setChanged(); data.remove(JOB);
            data.putBoolean("WorkshopSuccess",true); data.putString("WorkshopResult","实际烧炼完成并取回 "+claim.getHoverName().getString()+" ×"+claim.getCount());
            MaidBridge.outcome(maid,data.getString("WorkshopResult")); MaidBridge.scheduleNext(maid);
        } else if(maid.level().getGameTime()-job.getLong("started")>1200) cancel(maid,"烧炼或取回超过一分钟未完成");
    }
}
