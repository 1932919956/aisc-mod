package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A staircase shaft straight into the ground.
 *
 * This is the first step of real mining, and it is deliberately the safest possible one: she digs a
 * one-wide staircase down in front of herself, so the way back up is always the same stairs she just
 * made. Nothing is ever dug below her own feet in a way that could drop her, and the moment a block
 * would open into water or lava she stops and comes back up instead of gambling.
 *
 * Scope, stated plainly: this gets her to stone and to exposed ore on the way down. It is not branch
 * mining, it does not follow a vein sideways, and it does not explore caves.
 */
final class MaidDig {
    private MaidDig() {}

    static final int MAX_DEPTH = 24;
    private static final long STEP_TIMEOUT = 200;     // ten seconds to reach the next step's spot
    private static final long TOTAL_TIMEOUT = 12000;  // ten minutes for one shaft
    private static final int TORCH_EVERY = 6;
    private static final Map<UUID, Shaft> SHAFTS = new HashMap<>();

    private static final class Shaft {
        final BlockPos origin;
        final int targetDepth;
        final boolean autonomous;
        final long started;
        final String schedule;
        BlockPos stepFrom;
        int depth;
        long stepStarted;
        int torches;
        Shaft(EntityMaid maid, int targetDepth, boolean autonomous) {
            this.origin = maid.blockPosition();
            this.targetDepth = targetDepth;
            this.autonomous = autonomous;
            this.started = maid.level().getGameTime();
            this.schedule = maid.getSchedule().name();
            this.stepFrom = maid.blockPosition();
            this.stepStarted = started;
        }
    }

    static boolean active(EntityMaid maid) { return SHAFTS.containsKey(maid.getUUID()); }
    static void clear() { SHAFTS.clear(); }
    static void cancel(EntityMaid maid, String reason) {
        if (SHAFTS.remove(maid.getUUID()) != null) {
            MaidBridge.mind(maid).putString("DigResult", reason);
            MaidBridge.outcome(maid, reason);
            CompoundTag data = MaidBridge.mind(maid);
            data.putBoolean("IdleWork", false);
            data.remove("IdleWorkName");
            MaidBridge.trace(maid, "shaft cancelled: " + reason);
        }
    }

    /**
     * Safety net mirroring the hunt's: if the main heartbeat never reaches the dig tick, a shaft must
     * not stay "in progress" forever, because an unfinished state blocks every later planning round.
     */
    static void expire(EntityMaid maid) {
        Shaft shaft = SHAFTS.get(maid.getUUID());
        if (shaft == null) return;
        if (maid.level().getGameTime() - shaft.started > TOTAL_TIMEOUT)
            cancel(maid, "挖了十分钟还没到目标深度，已自动停止");
    }

    // ---------------------------------------------------------------- start

    static String start(EntityMaid maid, int depth, boolean autonomous) {
        if (active(maid)) return "未启动：已经在挖一条竖井了";
        if (!PartnerConfig.mayEditWorld(maid.level(), maid)) return "未启动：世界规则禁止女仆改变方块";
        if (maid.getHealth() < maid.getMaxHealth() * 0.7f) return "未启动：血量不够，先养好再下矿";
        int want = Math.max(4, Math.min(MAX_DEPTH, depth <= 0 ? 12 : depth));
        BlockPos start = maid.blockPosition();
        // She needs a pickaxe for stone; without one she would only make a dirt hole.
        if (!hasPickaxe(maid)) return "未启动：背包里没有镐，挖不动石头";
        // Refuse to start on top of water or lava: the first step would be an immediate hazard.
        if (dangerous(maid.level(), start.below()) || dangerous(maid.level(), start.below(2))) {
            return "未启动：脚下就是水或岩浆，不能从这里下挖";
        }
        SHAFTS.put(maid.getUUID(), new Shaft(maid, want, autonomous));
        maid.setHomeModeEnable(true);
        equipPickaxe(maid);
        String result = "开始向下挖一条阶梯竖井，计划到地下 " + want + " 格，遇到水或岩浆立刻上来";
        MaidBridge.mind(maid).putString("DigResult", result);
        return result;
    }

    private static boolean hasPickaxe(EntityMaid maid) {
        var inventory = maid.getAvailableBackpackInv();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) return true;
        }
        // The held item counts too, even if it is not in the backpack handler.
        return !maid.getMainHandItem().isEmpty()
            && maid.getMainHandItem().isCorrectToolForDrops(Blocks.STONE.defaultBlockState());
    }

    private static boolean equipPickaxe(EntityMaid maid) {
        if (maid.getMainHandItem().isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) return true;
        var inventory = maid.getAvailableBackpackInv();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty() || !stack.isCorrectToolForDrops(Blocks.STONE.defaultBlockState())) continue;
            ItemStack taken = stack.copy();
            ItemStack old = maid.getMainHandItem().copy();
            maid.setItemSlot(EquipmentSlot.MAINHAND, taken);
            inventory.extractItem(i, taken.getCount(), false);
            if (!old.isEmpty()) {
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(inventory, old, false);
                if (!remainder.isEmpty()) maid.spawnAtLocation(remainder);
            }
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- tick

    /** Called once per second from the main heartbeat. */
    static void tick(EntityMaid maid, ServerPlayer player) {
        Shaft shaft = SHAFTS.get(maid.getUUID());
        if (shaft == null) return;
        CompoundTag data = MaidBridge.mind(maid);
        long now = maid.level().getGameTime();
        if (maid.getTarget() != null) { cancel(maid, "被战斗打断，先停止挖掘"); return; }
        if (maid.isSleeping() || maid.isOrderedToSit() || !maid.getSchedule().name().equals(shaft.schedule)) {
            cancel(maid, "被休息或作息变化打断，先停止挖掘"); return;
        }
        if (maid.getHealth() < maid.getMaxHealth() * 0.5f) { cancel(maid, "血量太低，停止挖掘先上来"); return; }
        if (now - shaft.started > TOTAL_TIMEOUT) { cancel(maid, "挖了十分钟还没到目标深度，先停下"); return; }
        if (shaft.depth >= shaft.targetDepth) { finish(maid, shaft, "挖到计划深度"); return; }

        // Where the next step lands: one block forward, one block down from where she stands.
        Direction facing = safeDirection(maid);
        BlockPos foot = shaft.stepFrom.relative(facing);
        BlockPos step = foot.below();
        BlockPos head = foot.above();
        BlockPos ceiling = foot.above(2);

        for (BlockPos pos : List.of(foot, step, head, ceiling)) {
            if (dangerous(maid.level(), pos)) {
                // The one rule that is never traded away: never open into water or lava.
                finish(maid, shaft, "前面是水或岩浆，安全起见停止下挖并回地面");
                return;
            }
        }
        // Everything below the floor must be diggable too, or the next step would be blocked anyway.
        for (BlockPos pos : List.of(foot, step, head, ceiling)) {
            if (maid.level().getBlockState(pos).isAir()) continue;
            String failure = dig(maid, pos);
            if (failure != null) { cancel(maid, failure); return; }
        }

        // Walk down onto the step she just opened. The spot to reach is the cleared block BELOW the one
        // in front of her, not the block in front itself: `foot` was just dug out and its own floor was
        // removed with it, so nothing can stand there. Comparing against `foot` was unreachable, and the
        // shaft stalled at depth 0 with "上不去了" after ten seconds every single time.
        if (maid.blockPosition().equals(step)) {
            shaft.stepFrom = step;
            shaft.depth++;
            shaft.stepStarted = now;
            data.putString("DigResult", "已经下到地下 " + shaft.depth + " / " + shaft.targetDepth + " 格");
            if (shaft.depth % TORCH_EVERY == 0) placeTorch(maid, shaft);
            afterStep(maid, shaft);
            return;
        }
        if (now - shaft.stepStarted > STEP_TIMEOUT) {
            // Could not get onto the step she just dug: rather than widen the hole, stop here.
            finish(maid, shaft, "挖到 " + shaft.depth + " 格就上不去了，停在这里");
            return;
        }
        maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(Vec3.atBottomCenterOf(step), 0.5f, 0));
        maid.getNavigation().moveTo(step.getX() + 0.5, step.getY(), step.getZ() + 0.5, 0.5);
        data.putString("DigResult", "正在挖第 " + (shaft.depth + 1) + " 段台阶，深度 " + shaft.depth + " 格");
    }

    private static void afterStep(EntityMaid maid, Shaft shaft) {
        // A little ore or a cave on the way down is worth reporting. The shaft itself is recorded once,
        // as a single mine landmark at the entrance, so the list does not fill up with one hole.
        BlockPos here = maid.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(here.offset(-2, -2, -2), here.offset(2, 1, 2))) {
            if (!maid.level().isLoaded(pos)) continue;
            String kind = MaidFieldwork.kind(maid.level().getBlockState(pos));
            if (kind.equals("iron") || kind.equals("coal")) {
                MaidBridge.mind(maid).putString("DigResult", "在 " + here.toShortString() + " 附近看到 "
                    + kind + "，这是第 " + shaft.depth + " 格，继续往下");
                return;
            }
        }
        MaidBridge.mind(maid).putString("DigResult", "正在挖第 " + (shaft.depth + 1) + " 段台阶，深度 " + shaft.depth + " 格");
    }

    private static void finish(EntityMaid maid, Shaft shaft, String why) {
        SHAFTS.remove(maid.getUUID());
        MaidLandmarks.remember(maid, "mine", shaft.origin, null);
        String result = "实际挖出阶梯竖井：" + why + "；从 " + shaft.origin.toShortString() + " 下到地下 " + shaft.depth + " 格，台阶就是回程的路";
        MaidBridge.mind(maid).putString("DigResult", result);
        MaidBridge.outcome(maid, result);
        MaidBubble.speakIfExpired(maid, "dig", "挖到 " + shaft.depth + " 格了，台阶留着，回去顺着走就行。", -1L);
        // Back up to the surface: the stairs she just made are the path.
        maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(Vec3.atBottomCenterOf(shaft.origin), 0.6f, 0));
        maid.getNavigation().moveTo(shaft.origin.getX() + 0.5, shaft.origin.getY(), shaft.origin.getZ() + 0.5, 0.6);
    }

    /** Keep the shaft in one direction; turning every step would waste time and make it a maze. */
    private static Direction safeDirection(EntityMaid maid) {
        return Direction.fromYRot(maid.getYRot());
    }

    // ---------------------------------------------------------------- world edits

    /** Break one block the safe way: protection events honoured, no fluids, real drops. */
    private static String dig(EntityMaid maid, BlockPos pos) {
        ServerLevel level = (ServerLevel)maid.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return null;
        if (!state.getFluidState().isEmpty()) return "前面是液体，停止挖掘";
        if (state.is(Blocks.BEDROCK) || state.getDestroySpeed(level, pos) < 0) return "遇到挖不动的方块，停止挖掘";
        if (!maid.getMainHandItem().isCorrectToolForDrops(state) && state.getDestroySpeed(level, pos) > 0) {
            if (!equipPickaxe(maid)) return "镐不够用或已经损坏，停止挖掘";
        }
        var actor = FakePlayerFactory.get(level, new GameProfile(maid.getUUID(), "[AgnesMaid]"));
        actor.setGameMode(GameType.SURVIVAL);
        actor.moveTo(maid.position());
        actor.setItemSlot(EquipmentSlot.MAINHAND, maid.getMainHandItem().copy());
        try {
            var event = new BlockEvent.BreakEvent(level, pos, state, actor);
            if (!level.mayInteract(actor, pos) || net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(event)) return "这个位置不允许女仆挖掘，停止挖掘";
            List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), maid, maid.getMainHandItem());
            if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3)) return "方块修改没有成功，停止挖掘";
            level.levelEvent(2001, pos, Block.getId(state));
            ItemStack tool = maid.getMainHandItem();
            if (!tool.isEmpty()) tool.getItem().mineBlock(tool, level, state, pos, maid);
            for (ItemStack stack : drops) {
                if (stack.isEmpty()) continue;
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), stack, false);
                if (!remainder.isEmpty()) {
                    ItemEntity dropped = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, remainder);
                    dropped.setPickUpDelay(10);
                    level.addFreshEntity(dropped);
                }
            }
            return null;
        } finally {
            actor.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    /** Water, lava or anything that would flood or collapse the shaft. */
    private static boolean dangerous(net.minecraft.world.level.Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) return true;
        // Falling blocks would bury the stairs behind her.
        return state.is(Blocks.SAND) || state.is(Blocks.GRAVEL) || state.is(Blocks.RED_SAND)
            || state.is(Blocks.SUSPICIOUS_SAND) || state.is(Blocks.SUSPICIOUS_GRAVEL);
    }

    private static void placeTorch(EntityMaid maid, Shaft shaft) {
        int slot = findItem(maid, Items.TORCH);
        if (slot < 0) return;
        ServerLevel level = (ServerLevel)maid.level();
        var actor = FakePlayerFactory.get(level, new GameProfile(maid.getUUID(), "[AgnesMaid]"));
        actor.setGameMode(GameType.SURVIVAL);
        actor.moveTo(maid.position());
        BlockPos base = maid.blockPosition();
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(base.offset(-2, 0, -2), base.offset(2, 2, 2))) {
            if (!level.isEmptyBlock(pos)) continue;
            if (!level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)) continue;
            candidates.add(pos.immutable());
        }
        candidates.sort(java.util.Comparator.comparingDouble(p -> p.distSqr(base)));
        for (BlockPos pos : candidates) {
            if (!level.mayInteract(actor, pos)) continue;
            ItemStack torch = maid.getAvailableBackpackInv().extractItem(slot, 1, false);
            if (torch.isEmpty()) return;
            actor.setItemSlot(EquipmentSlot.MAINHAND, torch);
            try {
                Vec3 face = Vec3.atCenterOf(pos.below()).add(0, 0.5, 0);
                torch.useOn(new net.minecraft.world.item.context.UseOnContext(actor, InteractionHand.MAIN_HAND,
                    new BlockHitResult(face, Direction.UP, pos.below(), false)));
                if (level.getBlockState(pos).is(Blocks.TORCH)) {
                    shaft.torches++;
                    MaidBridge.mind(maid).putString("DigResult", "在竖井里放了一支火把（第 " + shaft.depth + " 格）");
                    return;
                }
                // Put it back when the placement was refused.
                ItemStack leftover = actor.getMainHandItem().copy();
                actor.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
                if (!leftover.isEmpty()) {
                    ItemStack remainder = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), leftover, false);
                    if (!remainder.isEmpty()) maid.spawnAtLocation(remainder);
                }
                return;
            } finally {
                actor.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
        }
    }

    private static int findItem(EntityMaid maid, net.minecraft.world.item.Item item) {
        var inventory = maid.getAvailableBackpackInv();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(item)) return i;
        }
        return -1;
    }

    static String summary(EntityMaid maid) {
        CompoundTag data = MaidBridge.mind(maid);
        String last = data.getString("DigResult");
        return last.isBlank() ? "还没有挖过竖井" : last;
    }
}
