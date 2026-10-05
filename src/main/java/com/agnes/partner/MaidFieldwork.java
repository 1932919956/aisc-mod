package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.*;

/** Executes ONE locally observed step chosen by Agnes; never invents its own survival plan. */
final class MaidFieldwork {
    private static final Map<UUID, Work> WORK = new HashMap<>();
    private record Target(BlockPos pos, String kind, BlockState state) {
        String id() { return kind + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ(); }
    }
    private static final class Work {
        final Target target;
        final long started;
        final boolean autonomous;
        final String schedule;
        int secondsWorking;
        long lastTick = Long.MIN_VALUE;
        long lastProgress;
        double bestDistance = Double.MAX_VALUE;
        Work(Target target, EntityMaid maid, boolean autonomous) {
            this.target = target; this.autonomous = autonomous;
            started = maid.level().getGameTime(); schedule = maid.getSchedule().name();
            lastProgress = started;
        }
    }

    static boolean active(EntityMaid maid) { return WORK.containsKey(maid.getUUID()); }
    static void clear() { WORK.clear(); MaidSurvey.clear(); }
    static void cancel(EntityMaid maid, String reason) {
        Work work = WORK.remove(maid.getUUID());
        if (work == null) return;
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.level().destroyBlockProgress(maid.getId(), work.target.pos(), -1);
        MaidBridge.mind(maid).putString("Fieldwork", reason);
        MaidBridge.mind(maid).putBoolean("FieldworkSuccess", false);
        MaidBridge.outcome(maid, reason);
        MaidBridge.scheduleNext(maid);
        if (maid.getServer() != null && maid.getOwnerUUID() != null) {
            var owner = maid.getServer().getPlayerList().getPlayer(maid.getOwnerUUID());
            if (owner != null && MaidBridge.find(owner) == maid) owner.sendSystemMessage(net.minecraft.network.chat.Component.literal("[" + maid.getName().getString() + " · 行动结果] " + reason));
        }
    }

    static JsonObject describe(EntityMaid maid) {
        JsonObject result = new JsonObject();
        result.addProperty("active", active(maid));
        result.addProperty("last_result", MaidBridge.mind(maid).getString("Fieldwork"));
        result.addProperty("world_changes_allowed", PartnerConfig.mayEditWorld(maid.level(), maid));
        result.add("area_survey", MaidSurvey.describe(maid));
        JsonArray options = new JsonArray();
        for (Target target : targets(maid)) {
            JsonObject option = new JsonObject();
            option.addProperty("target_id", target.id()); option.addProperty("kind", target.kind());
            option.addProperty("block", BuiltInRegistries.BLOCK.getKey(target.state().getBlock()).toString());
            option.addProperty("distance", Math.round(Math.sqrt(target.pos().distToCenterSqr(maid.position()))));
            option.addProperty("action", Set.of("table", "furnace", "scout", "storage").contains(target.kind()) ? "approach" : "gather");
            option.addProperty("tool_ready", !Set.of("stone", "coal", "iron").contains(target.kind()) || maid.getMainHandItem().isCorrectToolForDrops(target.state()));
            options.add(option);
        }
        result.add("observed_targets", options);
        return result;
    }

    private static List<Target> targets(EntityMaid maid) {
        List<Target> result = new ArrayList<>();
        boolean change = PartnerConfig.mayEditWorld(maid.level(), maid);
        int logs = 0;
        var inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) if (inv.getStackInSlot(i).is(ItemTags.LOGS)) logs += inv.getStackInSlot(i).getCount();
        boolean food = MaidSurvival.foodCount(maid) < 24;
        BlockPos center = maid.blockPosition();
        Map<BlockPos, String> candidates = new HashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-12, -3, -12), center.offset(12, 5, 12))) {
            if (pos.distToCenterSqr(maid.position()) > 144 || !maid.level().hasChunkAt(pos)) continue;
            BlockState state = maid.level().getBlockState(pos);
            String kind = kind(state);
            if (!kind.isEmpty()) candidates.put(pos.immutable(), kind);
        }
        for (var spot : MaidSurvey.spots(maid)) candidates.putIfAbsent(spot.pos(), spot.kind());
        List<BlockPos> nearby = new ArrayList<>(candidates.keySet());
        nearby.sort(Comparator.comparingDouble(p -> p.distToCenterSqr(maid.position())));
        Map<String, Integer> counts = new HashMap<>();
        for (BlockPos pos : nearby) {
            double dx = pos.getX() + 0.5 - maid.getX(), dz = pos.getZ() + 0.5 - maid.getZ();
            if (!maid.level().hasChunkAt(pos) || dx * dx + dz * dz > 100 * 100) continue;
            BlockState state = maid.level().getBlockState(pos);
            String kind = candidates.get(pos);
            if (counts.getOrDefault(kind, 0) >= 8) continue;
            if (kind.equals("table") && !state.is(Blocks.CRAFTING_TABLE)) continue;
            if (kind.equals("furnace") && !state.is(Blocks.FURNACE)) continue;
            if (kind.equals("storage") && !MaidStorage.storage(state)) continue;
            if (Set.of("stone", "coal", "iron").contains(kind) && (!change || !kind.equals(kind(state)) || !exposed(maid, pos))) continue;
            if (kind.equals("berries") && (!change || !food || !ripe(state))) continue;
            if (kind.equals("wood") && (!change || logs >= 16 || !log(state))) continue;
            if (kind.equals("scout") && (!standing(maid, pos) || pos.distToCenterSqr(maid.position()) < 16 * 16)) continue;
            if (kind.equals("wood") && !naturalTree(maid, pos)) continue;
            Target target = new Target(pos, kind, state);
            var data = MaidBridge.mind(maid);
            if (target.id().equals(data.getString("BlockedFieldTarget")) && maid.level().getGameTime() < data.getLong("BlockedFieldUntil")) continue;
            if (data.getCompound("BlockedFieldTargets").getLong(target.id()) > maid.level().getGameTime()) continue;
            result.add(target);
            counts.merge(kind, 1, Integer::sum);
        }
        return result;
    }

    static String kind(BlockState state) {
        if (state.is(Blocks.CRAFTING_TABLE)) return "table";
        if (state.is(Blocks.FURNACE)) return "furnace";
        if (MaidStorage.storage(state)) return "storage";
        if (ripe(state)) return "berries";
        if (log(state)) return "wood";
        if (state.is(Blocks.STONE) || state.is(Blocks.DEEPSLATE)) return "stone";
        if (state.is(Blocks.COAL_ORE) || state.is(Blocks.DEEPSLATE_COAL_ORE)) return "coal";
        if (state.is(Blocks.IRON_ORE) || state.is(Blocks.DEEPSLATE_IRON_ORE)) return "iron";
        return "";
    }
    static boolean exposed(EntityMaid maid, BlockPos pos) {
        // Only exposed ordinary resources, never the floor below the maid or submerged blocks.
        if (pos.equals(maid.blockPosition().below())) return false;
        for (var direction : net.minecraft.core.Direction.values()) {
            BlockPos next = pos.relative(direction);
            if (maid.level().hasChunkAt(next) && maid.level().getBlockState(next).isAir()) return true;
        }
        return false;
    }
    private static boolean ripe(BlockState state) {
        return state.is(Blocks.SWEET_BERRY_BUSH) && state.getValue(SweetBerryBushBlock.AGE) == 3;
    }
    private static boolean log(BlockState state) {
        return state.is(BlockTags.LOGS_THAT_BURN) && BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace().equals("minecraft");
    }
    // A conservative heuristic, not a guarantee that a tree was not planted by a player.
    private static boolean naturalTree(EntityMaid maid, BlockPos pos) {
        BlockPos root = pos;
        for (int i = 0; i < 5 && log(maid.level().getBlockState(root.below())); i++) root = root.below();
        if (!maid.level().getBlockState(root.below()).is(BlockTags.DIRT)) return false;
        int leaves = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, 0, -2), pos.offset(2, 5, 2))) {
            if (!maid.level().hasChunkAt(p)) continue;
            BlockState s = maid.level().getBlockState(p);
            if (s.hasBlockEntity() || s.is(BlockTags.PLANKS)) return false;
            if (s.getBlock() instanceof LeavesBlock && !s.getValue(LeavesBlock.PERSISTENT)) leaves++;
        }
        return leaves >= 5;
    }
    private static boolean visible(EntityMaid maid, BlockPos pos) {
        var hit = maid.level().clip(new ClipContext(maid.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, maid));
        return hit.getBlockPos().equals(pos);
    }

    static String start(EntityMaid maid, String id, String action, boolean autonomous) {
        if (maid.isSleeping() || maid.isOrderedToSit() || maid.getTarget() != null || MaidBridge.mind(maid).getBoolean("Recovering"))
            return "正在睡眠、坐下、战斗或修整，没有开始采集";
        if (autonomous && !MaidBridge.mind(maid).getBoolean("Autonomy")) return "自主活动已关闭";
        Target chosen = targets(maid).stream().filter(t -> t.id().equals(id)).findFirst().orElse(null);
        if (chosen == null) return "未启动：目标 ID 缺失、目标已变化或不在已观察的 100 格范围内";
        if (action.equals("approach") != (Set.of("table", "furnace", "scout", "storage").contains(chosen.kind()))) return "未启动：此目标不支持这个行动";
        if (Set.of("stone", "coal", "iron").contains(chosen.kind()) && !maid.getMainHandItem().isCorrectToolForDrops(chosen.state()))
            return "未启动：需要先装备能采集此矿石的镐（铁矿至少石镐）";
        cancel(maid, "已替换上一步行动");
        maid.setTask(TaskManager.getIdleTask());
        if (!maid.isHomeModeEnable()) maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
        maid.setHomeModeEnable(true);
        WORK.put(maid.getUUID(), new Work(chosen, maid, autonomous));
        String result = "正在走向 " + chosen.id() + "；尚未取得物品";
        MaidBridge.mind(maid).putString("Fieldwork", result);
        return result;
    }

    static String startKind(EntityMaid maid, String kind, boolean autonomous) {
        String wanted = kind == null ? "" : kind;
        Target target = targets(maid).stream().filter(t -> t.kind().equals(wanted)).findFirst().orElse(null);
        return target == null ? "未启动：没有找到当前可采集的 " + wanted + " 目标，等待重新观察" : start(maid, target.id(), "gather", autonomous);
    }

    static String explore(EntityMaid maid, String id, boolean autonomous) {
        if (id.isBlank()) id = targets(maid).stream().filter(t -> t.kind().equals("scout")).map(Target::id).findFirst().orElse("");
        if (id.isBlank()) return "未启动探索：100 格扫描尚未找到合适落脚点，不再用原地闲逛假装探索";
        return start(maid, id, "approach", autonomous);
    }

    /** Called once per second, including while a casual chat request is in flight. */
    static void tick(EntityMaid maid, ServerPlayer owner) {
        tick(maid, owner, false);
    }

    /**
     * @param ignoreOwner when true the owner-dependent guards are skipped. The runtime verification
     *     harness uses this so the movement and harvesting logic can be exercised on a maid that has no
     *     online owner; live gameplay always calls the two-argument form.
     */
    static void tick(EntityMaid maid, ServerPlayer owner, boolean ignoreOwner) {
        Work work = WORK.get(maid.getUUID());
        if (work == null) return;
        long now = maid.level().getGameTime();
        if (!maid.isAlive()
            || (!ignoreOwner && owner != null && (!owner.isAlive() || owner.level() != maid.level()
                || !owner.getUUID().equals(maid.getOwnerUUID()) || MaidBridge.find(owner) != maid
                || maid.distanceToSqr(owner) > MaidBridge.ACTIVITY_RANGE * MaidBridge.ACTIVITY_RANGE))
            || (work.autonomous && !MaidBridge.mind(maid).getBoolean("Autonomy"))
            || maid.isSleeping() || maid.isOrderedToSit() || maid.getTarget() != null || !maid.isHomeModeEnable()
            || maid.getTask() != TaskManager.getIdleTask() || !work.schedule.equals(maid.getSchedule().name())
            || MaidBridge.mind(maid).getBoolean("Recovering") || maid.getHealth() <= maid.getMaxHealth() * 0.35f) {
            cancel(maid, "上一步被主人操作、战斗、休息或距离变化中断，没有继续采集"); return;
        }
        if (now - work.started > 2400) { fail(maid, work, "两分钟内没有完成寻路/采集，暂时跳过此目标"); return; }
        Target target = work.target;
        if (!maid.level().hasChunkAt(target.pos()) || !maid.level().getBlockState(target.pos()).equals(target.state())) {
            fail(maid, work, "目标方块已经变化，请重新选择下一步"); return;
        }
        // Re-issue movement every server tick because TLM's snowball animation can stop navigation.
        // Mining progress itself is still counted at most once per second.
        boolean workDue = work.lastTick == Long.MIN_VALUE || now - work.lastTick >= 20;
        double distance = maid.getEyePosition().distanceToSqr(Vec3.atCenterOf(target.pos()));
        if (distance > 9 || (!target.kind().equals("scout") && !visible(maid, target.pos()))) {
            if (workDue) work.secondsWorking = 0;
            maid.level().destroyBlockProgress(maid.getId(), target.pos(), -1);
            if (Math.sqrt(distance) < work.bestDistance - 0.5) { work.bestDistance = Math.sqrt(distance); work.lastProgress = now; }
            if (now - work.lastProgress >= 300) { fail(maid, work, "连续 15 秒没有接近目标，可能被地形阻挡，已停止转圈并跳过目标"); return; }
            BlockPos waypoint = waypoint(maid, target.pos());
            if (waypoint != null) {
                maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(Vec3.atBottomCenterOf(waypoint), 0.65f, 0));
                maid.getNavigation().moveTo(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, 0.65);
            }
            MaidBridge.mind(maid).putString("Fieldwork", "正在前往 " + target.id() + "，还差约 " + Math.round(Math.sqrt(distance)) + " 格" + (waypoint == null ? "；尚未找到可走的路径" : ""));
            return;
        }
        if (!workDue) return;
        work.lastTick = now;
        maid.getNavigation().stop(); maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        maid.getLookControl().setLookAt(Vec3.atCenterOf(target.pos()));
        if (Set.of("table", "furnace", "scout", "storage").contains(target.kind())) {
            // Reaching a place is a small moment a real player would remark on; keep it short and only
            // for real movement, and never as evidence of an item being obtained.
            if (target.kind().equals("scout")) {
                String line = work.autonomous
                    ? "到这里了，先看看周围有什么。"
                    : "到了，就在这附近看看。";
                MaidBubble.speakIfExpired(maid, "scout", line, -1L);
            }
            finish(maid, "已到达 " + target.id() + "，可以继续检查合成、烧炼或观察条件"); return;
        }
        if (!PartnerConfig.mayEditWorld(maid.level(), maid)) { fail(maid, work, "世界规则或模组禁止女仆改变方块"); return; }
        maid.swing(InteractionHand.MAIN_HAND);
        boolean mining = Set.of("stone", "coal", "iron").contains(target.kind());
        if (mining && !maid.getMainHandItem().isCorrectToolForDrops(target.state())) { fail(maid, work, "镐已损坏或等级不够，停止挖矿"); return; }
        int seconds = target.kind().equals("berries") ? 2 : Math.max(2, (int)Math.ceil((mining ? target.state().getDestroySpeed(maid.level(), target.pos()) * 1.5 : 6) / Math.max(1, maid.getMainHandItem().getDestroySpeed(target.state()))));
        if (++work.secondsWorking < seconds) {
            if (!target.kind().equals("berries")) maid.level().destroyBlockProgress(maid.getId(), target.pos(), Math.min(9, work.secondsWorking * 10 / seconds));
            MaidBridge.mind(maid).putString("Fieldwork", "正在采集 " + target.id() + "（" + work.secondsWorking + "/" + seconds + " 秒）");
            return;
        }
        harvest(maid, work);
    }

    private static void finish(EntityMaid maid, String result) {
        // Move the default free-roaming anchor forward; native API preserves explicitly configured schedules.
        maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
        cancel(maid, result);
        MaidBridge.mind(maid).putBoolean("FieldworkSuccess", true);
    }

    private static boolean standing(EntityMaid maid, BlockPos pos) {
        if (!maid.level().hasChunkAt(pos) || !maid.level().hasChunkAt(pos.below())) return false;
        return maid.level().getBlockState(pos).isAir() && maid.level().getBlockState(pos.above()).isAir()
            && maid.level().getBlockState(pos.below()).getFluidState().isEmpty()
            && !maid.level().getBlockState(pos.below()).getCollisionShape(maid.level(), pos.below()).isEmpty();
    }

    private static BlockPos waypoint(EntityMaid maid, BlockPos destination) {
        Vec3 delta = Vec3.atBottomCenterOf(destination).subtract(maid.position());
        Vec3 next = delta.length() > 12 ? maid.position().add(delta.normalize().scale(12)) : Vec3.atBottomCenterOf(destination);
        BlockPos center = BlockPos.containing(next);
        List<BlockPos> choices = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-2, -4, -2), center.offset(2, 4, 2))) {
            if (standing(maid, p)) choices.add(p.immutable());
        }
        choices.sort(Comparator.comparingDouble(p -> p.distSqr(destination)));
        for (BlockPos p : choices.stream().limit(10).toList()) {
            var path = maid.getNavigation().createPath(p, 0);
            if (path != null && path.canReach()) return p;
        }
        return null;
    }

    private static void fail(EntityMaid maid, Work work, String message) {
        MaidBridge.mind(maid).putString("BlockedFieldTarget", work.target.id());
        MaidBridge.mind(maid).putLong("BlockedFieldUntil", maid.level().getGameTime() + 2400);
        var failures = MaidBridge.mind(maid).getCompound("BlockedFieldTargets");
        for (String id : new ArrayList<>(failures.getAllKeys())) if (failures.getLong(id) <= maid.level().getGameTime()) failures.remove(id);
        if (failures.getAllKeys().size() >= 32) failures.getAllKeys().stream().min(Comparator.comparingLong(failures::getLong)).ifPresent(failures::remove);
        failures.putLong(work.target.id(), maid.level().getGameTime() + 4800);
        MaidBridge.mind(maid).put("BlockedFieldTargets", failures);
        cancel(maid, message);
    }

    private static void harvest(EntityMaid maid, Work work) {
        Target t = work.target;
        ServerLevel level = (ServerLevel)maid.level();
        var actor = FakePlayerFactory.get(level, new GameProfile(maid.getUUID(), "[AgnesMaid]"));
        actor.moveTo(maid.position()); actor.setItemSlot(EquipmentSlot.MAINHAND, maid.getMainHandItem().copy());
        try {
            // Conservative: even berry picking honors block-break protection integrations.
            var event = new BlockEvent.BreakEvent(level, t.pos(), t.state(), actor);
            if (!level.mayInteract(actor, t.pos()) || MinecraftForge.EVENT_BUS.post(event)) {
                fail(maid, work, "该位置不允许女仆采集，已保留方块"); return;
            }
            if (!level.getBlockState(t.pos()).equals(t.state())) { fail(maid, work, "采集前方块已变化"); return; }
            List<ItemStack> loot;
            BlockState replacement;
            if (t.kind().equals("berries")) {
                loot = List.of(new ItemStack(Items.SWEET_BERRIES, 2 + level.random.nextInt(2)));
                replacement = t.state().setValue(SweetBerryBushBlock.AGE, 1);
            } else {
                if ((t.kind().equals("wood") ? !naturalTree(maid, t.pos()) : !exposed(maid, t.pos()) || !maid.getMainHandItem().isCorrectToolForDrops(t.state()))
                    || !maid.getMainHandItem().getItem().canAttackBlock(t.state(), level, t.pos(), actor)) {
                    fail(maid, work, "当前工具或树木条件不适合采集"); return;
                }
                loot = Block.getDrops(t.state(), level, t.pos(), null, maid, maid.getMainHandItem());
                replacement = Blocks.AIR.defaultBlockState();
            }
            if (!level.setBlock(t.pos(), replacement, 3)) { fail(maid, work, "方块修改未成功，没有生成物品"); return; }
            if (!t.kind().equals("berries")) {
                level.levelEvent(2001, t.pos(), Block.getId(t.state()));
                ItemStack tool = maid.getMainHandItem();
                tool.getItem().mineBlock(tool, level, t.state(), t.pos(), maid);
            }
            List<String> received = new ArrayList<>();
            for (ItemStack stack : loot) {
                int total = stack.getCount();
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), stack, false);
                int stored = total - remainder.getCount();
                received.add(stack.getHoverName().getString() + "：入包 " + stored + "，落地 " + remainder.getCount());
                if (!remainder.isEmpty()) Block.popResource(level, t.pos(), remainder);
            }
            finish(maid, "实际采集完成 " + t.id() + "；" + String.join("；", received));
            // Remember the place she actually worked, so she does not rediscover it next time.
            String landmark = switch (t.kind()) {
                case "stone", "coal", "iron" -> "mine";
                case "berries" -> "farm";
                default -> "";
            };
            if (!landmark.isEmpty()) MaidLandmarks.remember(maid, landmark, t.pos(), null);
        } finally { actor.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY); }
    }
}
