package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.api.task.FunctionCallSwitchResult;
import com.github.tartaricacid.touhoulittlemaid.api.task.IFarmTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IAttackTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData;
import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.EntityTeleportEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.items.IItemHandler;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Optional integration. Loaded only when Touhou Little Maid is present. World access is server-thread only. */
public final class MaidBridge {
    /**
     * Default spacing of autonomous planning. The real value is configurable; 45 seconds is the
     * default because a shorter gap is what makes her look present, and it costs nothing while she is
     * busy: planning is skipped entirely whenever something is already in progress.
     */
    static final int AUTONOMOUS_INTERVAL = 900;
    /** A request that has not answered in 90 seconds is treated as lost and cancelled. */
    static final int REQUEST_WATCHDOG = 1800;
    static final int ACTIVITY_RANGE = 128;
    private static final String BINDING = "AgnesBoundMaid";
    private static final String DATA = "AgnesCompanionMind";
    /** Arrival grace period: scan and refresh vision before Agnes is allowed to plan movement. */
    private static final String ARRIVAL_UNTIL = "DimensionArrivalUntil";
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, OwnerPosition> OWNER_POSITIONS = new HashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private record OwnerPosition(ResourceLocation dimension, Vec3 position) {}

    private static final class Session {
        boolean pending;
        long lastSeen;
        /** Game time the current request started, so a request that never completes cannot wedge her. */
        long pendingSince;
        final Queue<String> queue = new ArrayDeque<>();
        String shareId = "";
        int shareCount;
        long shareUntil;
    }

    private static CompoundTag playerData(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        if (!root.contains(Player.PERSISTED_NBT_TAG)) root.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        return root.getCompound(Player.PERSISTED_NBT_TAG);
    }

    public static boolean hasBinding(ServerPlayer player) { return playerData(player).hasUUID(BINDING); }

    public static EntityMaid find(ServerPlayer player) {
        if (!hasBinding(player)) return null;
        UUID id = playerData(player).getUUID(BINDING);
        for (ServerLevel level : player.server.getAllLevels()) {
            if (level.getEntity(id) instanceof EntityMaid maid && player.getUUID().equals(maid.getOwnerUUID())) return maid;
        }
        return null;
    }

    static CompoundTag mind(EntityMaid maid) {
        if (!maid.getPersistentData().contains(DATA)) maid.getPersistentData().put(DATA, new CompoundTag());
        return maid.getPersistentData().getCompound(DATA);
    }

    private static boolean valid(EntityMaid maid, ServerPlayer player) {
        return maid.isAlive() && player.isAlive() && player.getUUID().equals(maid.getOwnerUUID())
            && hasBinding(player) && playerData(player).getUUID(BINDING).equals(maid.getUUID());
    }

    static boolean maidReformBusy(EntityMaid maid) {
        CompoundTag tag = maid.getPersistentData();
        return MaidRescue.busy(maid) || (MaidRescue.externalInstalled()
            && (tag.getBoolean("isKnockDown") || tag.getBoolean("isRescuing") || tag.getBoolean("isPlayerRescuing")
            || tag.getInt("assignedRescueMaidId") > 0 || tag.getInt("rescueMaidTargetId") > 0));
    }

    /** Native defense is deliberately independent of Agnes: combat must react on the same tick. */
    private static void nativeDefense(EntityMaid maid) {
        if (maid.isSleeping() || maid.isOrderedToSit() || maid.isPassenger() || maidReformBusy(maid)
            || mind(maid).getBoolean("Recovering")) return;
        Monster threat = maid.level().getEntitiesOfClass(Monster.class, maid.getBoundingBox().inflate(12), mob ->
            mob.isAlive() && mob.canAttack(maid) && maid.hasLineOfSight(mob))
            .stream().min(Comparator.comparingDouble(maid::distanceToSqr)).orElse(null);
        if (threat == null) return;
        threat.setTarget(maid);
        if (maid.getTarget() != threat) maid.setTarget(threat);
        if (!(maid.getTask() instanceof IAttackTask)) {
            TaskManager.getTaskIndex().stream().filter(t -> t instanceof IAttackTask && t.isEnable(maid) && !t.isHidden(maid))
                .findFirst().ifPresent(maid::setTask);
        }
        MaidPlan.clear(maid, "遭到敌对生物攻击，交给女仆原生战斗");
        MaidFieldwork.cancel(maid, "遭到敌对生物攻击，交给女仆原生战斗");
        MaidWorkshop.cancel(maid, "遭到敌对生物攻击，交给女仆原生战斗");
        MaidHunt.cancel(maid, "遭到敌对生物攻击，停止主动狩猎");
        mind(maid).putString("Outcome", "敌对生物靠近，女仆原生战斗中");
    }

    private static void prepareAfterTeleport(ServerPlayer player, EntityMaid maid, String reason) {
        if (maid == null || !maid.isAlive()) return;
        MaidPlan.clear(maid, reason + "，清除旧计划");
        MaidFieldwork.cancel(maid, reason + "，清除旧采集");
        MaidWorkshop.cancel(maid, reason + "，清除旧烧炼");
        MaidHunt.cancel(maid, reason + "，清除旧狩猎");
        MaidDig.cancel(maid, reason + "，清除旧挖掘");
        MaidLandmarks.cancel(maid);
        maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        if (!maid.isSleeping() && !maid.isOrderedToSit()) maid.setTask(TaskManager.getIdleTask());
        CompoundTag data = mind(maid);
        long now = maid.level().getGameTime();
        data.putLong(ARRIVAL_UNTIL, now + 80);
        data.putLong("NextThink", now + 100);
        data.putLong("PlanRevision", data.getLong("PlanRevision") + 1);
        data.putString("Outcome", "已抵达新位置，先重新识别周围环境");
        MaidSurvey.remove(maid);
        MaidVision.remove(player.getUUID());
        MaidVision.track(player, maid);
        MaidVision.request(player, maid);
    }

    static boolean bringToOwner(ServerPlayer player, EntityMaid maid, String reason) {
        if (maid == null || !maid.isAlive()) return false;
        if (maidReformBusy(maid)) {
            mind(maid).putBoolean("FollowOwnerAfterReform", true);
            return false;
        }
        mind(maid).remove("FollowOwnerAfterReform");
        if (maid.level() == player.level()) {
            if (maid.distanceToSqr(player) > 12 * 12) {
                maid.teleportTo(player.getX() + 1.5, player.getY(), player.getZ() + 1.5);
                prepareAfterTeleport(player, maid, reason + "，已传送到主人身边");
                return true;
            }
            return false;
        }
        ServerLevel oldLevel = (ServerLevel)maid.level();
        MaidPlan.clear(maid,"切换维度，重新观察环境"); MaidWorkshop.cancel(maid,"切换维度");
        CompoundTag saved = maid.saveWithoutId(new CompoundTag());
        EntityMaid moved = (EntityMaid)maid.getType().create(player.serverLevel());
        if (moved == null) return false;
        moved.load(saved);
        moved.setOwnerUUID(player.getUUID());
        moved.moveTo(player.getX() + 1.5, player.getY(), player.getZ() + 1.5, maid.getYRot(), maid.getXRot());
        if (!player.serverLevel().addFreshEntity(moved)) return false;

        // EntityMaid deliberately disables vanilla changeDimension. Move a fully serialized clone, then
        // suppress its unloaded-maid record so the native recall system cannot create a duplicate later.
        MaidWorldData oldData = MaidWorldData.get(oldLevel);
        if (oldData != null) oldData.removeInfo(maid);
        SESSIONS.remove(maid.getUUID());
        playerData(player).putUUID(BINDING, moved.getUUID());
        maid.setOwnerUUID(null);
        maid.remove(net.minecraft.world.entity.Entity.RemovalReason.CHANGED_DIMENSION);
        prepareAfterTeleport(player, moved, reason + "，已跟随主人切换维度");
        return true;
    }

    @SubscribeEvent public void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && hasBinding(player)) {
            bringToOwner(player, find(player), "主人切换维度");
        }
    }

    @SubscribeEvent public void loggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !hasBinding(player)) return;
        EntityMaid maid = find(player);
        bringToOwner(player, find(player), "主人重新进入存档");
        if (maid == null) return;
        // Coming back into the save should be noticed out loud, in her own speech bubble.
        CompoundTag data = mind(maid);
        long offline = data.contains("LastOnlineTick") ? maid.level().getGameTime() - data.getLong("LastOnlineTick") : -1L;
        if (partnerSaidRecently(maid, 200)) return;
        try {
            if (MaidVoice.greeting(maid, player, offline)) {
                data.putLong("NextChat", maid.level().getGameTime() + 1200);
            }
        } catch (RuntimeException ignored) { /* a greeting must never break login */ }
    }

    /** True when she spoke less than the given number of ticks ago, used to avoid double greetings. */
    private static boolean partnerSaidRecently(EntityMaid maid, long ticks) {
        CompoundTag data = mind(maid);
        return data.contains("Voice.last") && maid.level().getGameTime() - data.getLong("Voice.last") < ticks;
    }

    @SubscribeEvent public void teleported(EntityTeleportEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !hasBinding(player) || event.isCanceled()) return;
        Vec3 target = event.getTarget();
        player.server.execute(() -> {
            if (player.isAlive() && player.position().distanceToSqr(target) <= 16) {
                bringToOwner(player, find(player), "主人传送");
                OWNER_POSITIONS.put(player.getUUID(), new OwnerPosition(player.level().dimension().location(), player.position()));
            }
        });
    }

    @SubscribeEvent public void playerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)
            || player.tickCount % 5 != 0) return;
        if (!hasBinding(player)) { MaidVision.remove(player.getUUID()); return; }
        EntityMaid maid = find(player);
        MaidVision.track(player, maid);
        // Heartbeat for how long the save has actually been played, so a returning owner can be greeted
        // with the real absence instead of a guess.
        if (maid != null) mind(maid).putLong("LastOnlineTick", maid.level().getGameTime());
        OwnerPosition current = new OwnerPosition(player.level().dimension().location(), player.position());
        OwnerPosition previous = OWNER_POSITIONS.put(player.getUUID(), current);
        if (previous == null) return;
        boolean dimensionChanged = !previous.dimension().equals(current.dimension());
        boolean jumped = !dimensionChanged && previous.position().distanceToSqr(current.position()) > 16 * 16;
        if (dimensionChanged || jumped || (maid != null && (maid.level() != player.level()
            || mind(maid).getBoolean("FollowOwnerAfterReform")))) {
            bringToOwner(player, maid, dimensionChanged ? "主人切换维度" : "主人传送");
        }
    }

    static void scheduleNext(EntityMaid maid) {
        var data = mind(maid);
        long due = data.contains("LastAutonomousRequest")
            ? Math.max(maid.level().getGameTime() + 20, data.getLong("LastAutonomousRequest") + PartnerConfig.planningInterval())
            : maid.level().getGameTime() + PartnerConfig.planningInterval();
        data.putLong("NextThink", Math.max(due, data.getLong("ManualUntil")));
    }

    public static void commands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("aipartner").then(Commands.literal("maid")
            .then(Commands.literal("bind").executes(ctx -> bindNear(ctx.getSource().getPlayerOrException())))
            .then(Commands.literal("unbind").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                EntityMaid maid = find(player);
                if (hasBinding(player)) SESSIONS.remove(playerData(player).getUUID(BINDING));
                playerData(player).remove(BINDING);
                MaidVision.remove(player.getUUID());
                if (maid != null) { MaidPlan.clear(maid,"已解除绑定"); MaidWorkshop.cancel(maid,"已解除绑定"); MaidFieldwork.cancel(maid, "已解除绑定"); MaidSurvey.remove(maid); mind(maid).putBoolean("Autonomy", false); }
                player.sendSystemMessage(Component.literal("已解除 Agnes 绑定，女仆继续使用原本的任务系统。"));
                return 1;
            }))
            .then(Commands.literal("status").executes(ctx -> { status(ctx.getSource().getPlayerOrException()); return 1; }))
            .then(Commands.literal("diag").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException(); EntityMaid maid = find(player);
                if (maid == null) { unavailable(player); return 0; }
                // One line that answers "why is she not acting". Everything the planning gate checks.
                CompoundTag d = mind(maid);
                Session s = session(maid);
                long now = maid.level().getGameTime();
                long next = d.contains("RetryAt") && d.getLong("RetryAt") > 0 ? d.getLong("RetryAt") : d.getLong("NextThink");
                List<String> acting = new ArrayList<>();
                if (MaidFieldwork.active(maid)) acting.add("fieldwork");
                if (MaidPlan.active(maid)) acting.add("plan");
                if (MaidWorkshop.active(maid)) acting.add("smelt");
                if (MaidBuilder.active(maid)) acting.add("building");
                if (MaidLandmarks.active(maid)) acting.add("walking-to-memory");
                if (MaidHunt.active(maid)) acting.add("hunting");
                if (MaidDig.active(maid)) acting.add("digging");
                StringBuilder line = new StringBuilder();
                line.append("自主=").append(d.getBoolean("Autonomy") ? "开" : "关");
                line.append(" 密钥=").append(PartnerConfig.getApiKey().isBlank() ? "没有" : "有");
                line.append(" 维度一致=").append(maid.level() == player.level());
                line.append(" 距离=").append(Math.round(Math.sqrt(maid.distanceToSqr(player)))).append("格");
                line.append(" 请求中=").append(s.pending).append(s.pending && s.pendingSince > 0 ? "(" + Math.max(0, (now - s.pendingSince) / 20) + "秒)" : "");
                line.append(" 排队=").append(s.queue.size());
                line.append(" 下次规划=").append(next <= now ? "立即" : Math.max(0, (next - now) / 20) + "秒后");
                line.append(" 空转轮次=").append(d.getInt("IdleNoAction"));
                line.append(" 进行中=").append(acting.isEmpty() ? "无" : String.join("+", acting));
                line.append(" 走路目标=").append(maid.getNavigation().isInProgress());
                line.append(" 坐下=").append(maid.isOrderedToSit());
                line.append(" 睡觉=").append(maid.isSleeping());
                line.append(" 有敌人=").append(maid.getTarget() != null);
                line.append(" 修整=").append(d.getBoolean("Recovering"));
                line.append(" 血量=").append(Math.round(maid.getHealth())).append("/").append(Math.round(maid.getMaxHealth()));
                line.append(" 食物=").append(MaidSurvival.foodCount(maid));
                line.append(" 可改世界=").append(PartnerConfig.mayEditWorld(maid.level(), maid)
                    ? "可以" : "被mobGriefing禁止(配置里开 allowWorldEditsWhenMobGriefingOff 可解)");
                line.append(" 任务=").append(maid.getTask().getUid());
                tell(maid, player, "诊断：" + line);
                player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · 诊断] 上次结果：" + limit(d.getString("Outcome"), 160)));
                player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · 诊断] 视觉：" + MaidVision.status(player)
                    + "；计划=" + limit(d.getString("SurvivalPlan"), 120) + "；受阻=" + limit(d.getString("StuckReason"), 120)));
                player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · 诊断] 手上物品=" + MaidBuilder.describe(maid).get("active")
                    + "；记住的地方=" + MaidLandmarks.count(maid) + "；空闲工作=" + d.getString("IdleWorkName")));
                return 1;
            }))
            .then(Commands.literal("landmarks").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException(); EntityMaid maid = find(player);
                if (maid == null) { unavailable(player); return 0; }
                tell(maid, player, MaidLandmarks.summary(maid));
                for (var element : MaidLandmarks.describe(maid, 24)) {
                    var place = element.getAsJsonObject();
                    player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · 记忆] "
                        + place.get("target_id").getAsString() + " " + place.get("name").getAsString()
                        + "（" + place.get("kind").getAsString() + "）距离 " + place.get("distance").getAsString()
                        + " 格，去过 " + place.get("times_used").getAsString() + " 次"
                        + (place.has("note") ? "；" + place.get("note").getAsString() : "")));
                }
                return 1;
            }))
            .then(Commands.literal("sight").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException(); EntityMaid maid = find(player);
                if (maid == null) { unavailable(player); return 0; }
                // What she can see right now, straight from the game data. No API call, no cost.
                JsonObject sight = MaidSight.describe(maid);
                player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · 视觉] 镜头：" + MaidVision.status(player)));
                MaidVision.Frame frame = MaidVision.read(player, maid);
                player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · 视觉] 画面："
                    + (frame == null ? "本轮没有可用画面（改用游戏状态与视野清单）"
                        : "约 " + (frame.ageMillis() / 1000) + " 秒前拍摄，朝向 yaw " + Math.round(frame.yaw()) + "° pitch " + Math.round(frame.pitch()) + "°")));
                JsonObject ray = sight.getAsJsonObject("forward_ray");
                player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · 视觉] 正前方："
                    + ray.get("block").getAsString() + (ray.has("distance") ? "，约 " + ray.get("distance").getAsString() + " 格" : "")
                    + (ray.has("gatherable_as") ? "，可采集为 " + ray.get("gatherable_as").getAsString() : "")));
                int columns = sight.getAsJsonArray("visible_columns").size();
                player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · 视觉] 视野内可见列："
                    + columns + " 条；正前有可走地面：" + sight.get("walkable_ground_ahead").getAsBoolean()
                    + "；身边显著方块：" + sight.getAsJsonArray("nearby_notable_blocks").size() + " 个"));
                for (var element : sight.getAsJsonArray("visible_columns")) {
                    JsonObject column = element.getAsJsonObject();
                    if (!column.has("target_id")) continue;
                    player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · 视觉] " + column.get("block").getAsString()
                        + " 距离 " + column.get("distance").getAsString() + " 格 " + column.get("direction").getAsString()
                        + (column.has("reachable") ? "，可走到" : "") + "，目标 ID " + column.get("target_id").getAsString()));
                }
                return 1;
            }))
            .then(Commands.literal("personality").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException(); EntityMaid maid = find(player);
                if (maid == null) { unavailable(player); return 0; }
                tell(maid, player, MaidPersonality.summary(MaidPersonality.data(mind(maid), maid.getUUID(), maid.level().getGameTime())));
                return 1;
            }))
            .then(Commands.literal("speak").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException(); EntityMaid maid = find(player);
                if (maid == null) { unavailable(player); return 0; }
                // A local, no-API way to check that bubbles render: she states mood and current intent.
                CompoundTag p = MaidPersonality.data(mind(maid), maid.getUUID(), maid.level().getGameTime());
                String mood = p.getString("Mood");
                String goal = p.getString("DailyGoal");
                MaidBubble.think(maid, goal.isBlank() ? "看看周围有什么可做的……" : "在想：" + MaidPersonality.goalName(goal));
                if (!MaidBubble.speak(maid, "我现在" + mood + "。"
                    + (goal.isBlank() ? "还没有定下今天要做什么。" : "今天想做的是：" + MaidPersonality.goalName(goal) + "。"))) {
                    tell(maid, player, "气泡没有显示出来，请确认车万女仆版本为 1.5.3 且未关闭气泡显示。"); return 0;
                }
                return 1;
            }))
            .then(Commands.literal("needs").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException(); EntityMaid maid = find(player);
                if (maid == null) { unavailable(player); return 0; }
                tell(maid, player, "基础照料：" + (MaidSurvival.careEnabled(maid) ? "开启" : "关闭") + "；背包食物 " + MaidSurvival.foodCount(maid)
                    + "；附近工作台：" + (MaidSurvival.hasTable(maid) ? "可用" : "没有可见工作台")
                    + "；照料结果：" + mind(maid).getString("CareResult") + "；最近受阻原因：" + mind(maid).getString("BlockedReason"));
                return 1;
            }))
            .then(Commands.literal("prepare").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException(); EntityMaid maid = find(player);
                if (maid == null) { unavailable(player); return 0; }
                if (maidReformBusy(maid)) { tell(maid, player, "女仆正在倒地或救援，暂不整理装备。"); return 0; }
                String result = MaidSurvival.prepare(maid); tell(maid, player, result.isBlank() ? "当前没有可自动升级的普通护甲。" : result); return 1;
            }))
            .then(Commands.literal("care").then(Commands.literal("on").executes(ctx -> care(ctx.getSource().getPlayerOrException(), true)))
                .then(Commands.literal("off").executes(ctx -> care(ctx.getSource().getPlayerOrException(), false))))
            .then(Commands.literal("craft").then(Commands.argument("recipe", com.mojang.brigadier.arguments.StringArgumentType.word()).executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException(); EntityMaid maid = find(player);
                if (maid == null) { unavailable(player); return 0; }
                if (maidReformBusy(maid)) { tell(maid, player, "女仆正在倒地或救援，暂不合成。"); return 0; }
                if (maid.level() != player.level() || maid.distanceToSqr(player) > ACTIVITY_RANGE * ACTIVITY_RANGE) { tell(maid, player, "请先来到女仆附近再安排合成。"); return 0; }
                tell(maid, player, MaidSurvival.craft(maid, com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "recipe"))); return 1;
            })))
            .then(Commands.literal("tasks").executes(ctx -> {
                ServerPlayer player = ctx.getSource().getPlayerOrException();
                EntityMaid maid = find(player);
                if (maid == null) { unavailable(player); return 0; }
                for (IMaidTask task : availableTasks(maid)) player.sendSystemMessage(Component.literal(task.getName().getString() + "：" + task.getUid()));
                return 1;
            }))));
    }

    private static int bindNear(ServerPlayer player) {
        List<EntityMaid> nearby = player.serverLevel().getEntitiesOfClass(EntityMaid.class, player.getBoundingBox().inflate(8),
            m -> m.isAlive() && player.getUUID().equals(m.getOwnerUUID()) && player.hasLineOfSight(m));
        var start = player.getEyePosition();
        var end = start.add(player.getLookAngle().scale(9));
        EntityMaid selected = nearby.stream().filter(m -> m.getBoundingBox().inflate(0.3).clip(start, end).isPresent())
            .min(Comparator.comparingDouble(m -> m.distanceToSqr(player))).orElse(nearby.size() == 1 ? nearby.get(0) : null);
        if (selected == null) {
            player.sendSystemMessage(Component.literal("请靠近并看向你已驯服的女仆（8 格内），再输入 /aipartner maid bind。"));
            return 0;
        }
        bind(player, selected);
        tell(selected, player, "现在由我陪你一起冒险。直接聊天就可以；输入 /aipartner autonomy off 可暂停自主安排。");
        if (PartnerConfig.getApiKey().isBlank()) tell(selected, player, "还没有读到 Agnes 密钥，请检查原来的配置文件。");
        return 1;
    }

    static void bind(ServerPlayer player, EntityMaid maid) {
        if (!player.getUUID().equals(maid.getOwnerUUID())) throw new IllegalArgumentException("Not your maid");
        MaidVision.remove(player.getUUID());
        if (hasBinding(player)) SESSIONS.remove(playerData(player).getUUID(BINDING));
        EntityMaid previous = find(player);
        if (previous != null) { MaidPlan.clear(previous,"重新绑定"); MaidWorkshop.cancel(previous,"重新绑定"); MaidFieldwork.cancel(previous, "重新绑定，中止上一步"); }
        playerData(player).putUUID(BINDING, maid.getUUID());
        CompoundTag data = mind(maid);
        if (data.hasUUID("MemoryOwner") && !data.getUUID("MemoryOwner").equals(player.getUUID())) {
            data.remove("Memory"); data.remove("Goal"); data.remove("Outcome");
            data.remove("RecentSteps"); data.remove("Fieldwork"); data.remove("ManualUntil");
        }
        data.putUUID("MemoryOwner", player.getUUID());
        data.putBoolean("Autonomy", true);
        MaidPersonality.data(data, maid.getUUID(), maid.level().getGameTime());
        data.putLong("NextThink", maid.level().getGameTime() + 200);
        if (!data.contains("Goal")) data.putString("Goal", "观察环境，选择适合自己的短期活动");
    }

    public static void status(ServerPlayer player) {
        EntityMaid maid = find(player);
        if (maid == null) { unavailable(player); return; }
        var data = mind(maid);
        tell(maid, player, "Agnes " + (PartnerConfig.getApiKey().isBlank() ? "未配置密钥" : "已配置密钥")
            + "；自主活动" + (data.getBoolean("Autonomy") ? "开启" : "关闭")
            + "；工作：" + maid.getTask().getName().getString()
            + "；" + (maid.isHomeModeEnable() ? "驻留/自由工作" : "跟随")
            + "；目标：" + data.getString("Goal") + "；上次结果：" + data.getString("Outcome")
            + "；照料：" + data.getString("CareResult")
            + "；生存步骤：" + data.getString("Fieldwork") + "；连续计划：" + data.getString("SurvivalPlan")
            + "；烧炼：" + data.getString("WorkshopResult") + "；摄像头：" + MaidVision.status(player)
            + "；上次视觉请求：" + data.getString("LastVision")
            + "；仓库：" + data.getString("StorageResult") + "；建房：" + data.getString("BuildResult")
            + "；观察半径：100 格；扫描进度：" + MaidSurvey.describe(maid).get("scan_percent").getAsInt() + "%"
            + "；自主规划间隔：" + (PartnerConfig.planningInterval() / 20) + " 秒（可在配置文件中调整）"
            + "；最近受阻：" + data.getString("StuckReason")
            + "；记住的地方：" + MaidLandmarks.count(maid) + " 处"
            + "；人格：" + MaidPersonality.summary(MaidPersonality.data(data, maid.getUUID(), maid.level().getGameTime()))
            + (maidReformBusy(maid) ? "；倒地/救援中，Agnes 已暂停" : "")
            + (maid.level() != player.level() ? "；当前不在同一维度" : ""));
    }

    private static void unavailable(ServerPlayer player) {
        player.sendSystemMessage(Component.literal(hasBinding(player)
            ? "已绑定的女仆当前未加载、已死亡或不再属于你。靠近她后重试；换女仆可重新 bind。"
            : "还未绑定女仆。靠近你已驯服的女仆，输入 /aipartner maid bind。"));
    }

    /** Written to the game log so a problem is diagnosable without asking the player to reproduce it. */
    static void trace(EntityMaid maid, String message) {
        LogUtils.getLogger().info("[agnespartner] maid={} {} {}", maid.getName().getString(), message, mind(maid).getString("Goal"));
    }

    public static void autonomy(ServerPlayer player, boolean enabled) {
        EntityMaid maid = find(player);
        if (maid == null) { unavailable(player); return; }
        mind(maid).putBoolean("Autonomy", enabled);
        mind(maid).putLong("NextThink", maid.level().getGameTime() + 200);
        if (!enabled) { MaidPlan.clear(maid, "自主活动已关闭"); MaidWorkshop.cancel(maid,"自主活动已关闭"); MaidFieldwork.cancel(maid, "自主活动已关闭"); MaidHunt.cancel(maid, "自主活动已关闭"); MaidDig.cancel(maid, "自主活动已关闭"); MaidLandmarks.cancel(maid); follow(maid); }
        mind(maid).putInt("PlanRevision", mind(maid).getInt("PlanRevision") + 1);
        trace(maid, "autonomy=" + enabled + " key=" + !PartnerConfig.getApiKey().isBlank() + " sameLevel=" + (maid.level() == player.level())
            + " distance=" + Math.round(Math.sqrt(maid.distanceToSqr(player))) + " mayEditWorld=" + PartnerConfig.mayEditWorld(maid.level(), maid));
        tell(maid, player, enabled ? "自主活动已开启。" : "自主活动已关闭，我会跟着你，仍可聊天和接收你的请求。");
    }

    private static int care(ServerPlayer player, boolean enabled) {
        EntityMaid maid = find(player);
        if (maid == null) { unavailable(player); return 0; }
        mind(maid).putBoolean("AutoCare", enabled);
        if (!enabled) mind(maid).putBoolean("Recovering", false);
        tell(maid, player, enabled ? "基础照料已开启：自动整理普通护甲，并在受伤时暂停工作修整。" : "基础照料已关闭，不再自动换护甲或接管修整行为。");
        return 1;
    }

    public static void inventory(ServerPlayer player, boolean knowledge) {
        EntityMaid maid = find(player);
        if (maid == null) { unavailable(player); return; }
        tell(maid, player, knowledge ? ModpackKnowledge.describe((ServerLevel)maid.level(), maid.blockPosition(), inventorySummary(maid), "", player.getMainHandItem()) : inventorySummary(maid));
    }

    public static boolean chat(ServerPlayer player, String text) {
        if (!hasBinding(player)) return false;
        EntityMaid maid = find(player);
        if (maid == null) { unavailable(player); return true; }
        if (maid.level() != player.level() || maid.distanceToSqr(player) > ACTIVITY_RANGE * ACTIVITY_RANGE) {
            tell(maid, player, "我现在离你太远了，回到同一维度、128 格以内再和我聊天吧。"); return true;
        }
        CompoundTag personality = MaidPersonality.data(mind(maid), maid.getUUID(), maid.level().getGameTime());
        String local = MaidPersonality.localChat(personality, text, maid.level().getGameTime());
        if (!local.isBlank()) { tell(maid, player, local); return true; }
        if (PartnerConfig.getApiKey().isBlank()) { tell(maid, player, "没有读到 Agnes 密钥，请检查原来的配置文件。"); return true; }
        Session session = session(maid);
        if (session.pending) {
            if (session.queue.size() >= 20) tell(maid, player, "还有很多话没处理完，稍等我一下。");
            else session.queue.add(limit(text, 600));
        } else request(maid, player, session, limit(text, 600), false);
        return true;
    }

    private static Session session(EntityMaid maid) {
        Session session = SESSIONS.computeIfAbsent(maid.getUUID(), key -> new Session());
        session.lastSeen = System.currentTimeMillis();
        return session;
    }

    @SubscribeEvent public void stop(ServerStoppedEvent event) { SESSIONS.clear(); OWNER_POSITIONS.clear(); MaidFieldwork.clear(); MaidPlan.clearAll(); MaidVision.clear(); MaidVoice.clear(); MaidHunt.clear(); MaidDig.clear(); MaidLandmarks.clear(); MaidSurvival.clearFarmProbes(); }

    @SubscribeEvent public void leave(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.getEntity() instanceof EntityMaid maid) {
            MaidPlan.clear(maid,"实体卸载，重新观察后规划");
            SESSIONS.remove(maid.getUUID()); MaidFieldwork.cancel(maid, "实体卸载，上一步已停止"); MaidSurvey.remove(maid);
        }
    }

    @SubscribeEvent public void logout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        MaidVision.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player && hasBinding(player)) {
            OWNER_POSITIONS.remove(player.getUUID());
            SESSIONS.remove(playerData(player).getUUID(BINDING));
            EntityMaid maid = find(player); if (maid != null) { MaidPlan.clear(maid,"主人离线，暂停连续计划"); MaidWorkshop.cancel(maid,"主人离线"); MaidFieldwork.cancel(maid, "主人离线，上一步已停止"); MaidSurvey.remove(maid); }
        }
    }

    @SubscribeEvent public void tick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid) || maid.level().isClientSide) return;
        if (maid.getServer() == null) return;
        ServerPlayer player = maid.getOwnerUUID() == null ? null : maid.getServer().getPlayerList().getPlayer(maid.getOwnerUUID());
        if (maid.tickCount % 20 == 0) GoMaid.recover(maid, player);
        if (GoMaid.playing(maid, player)) { MaidPlan.clear(maid,"开始下棋，暂停生存计划"); MaidWorkshop.cancel(maid,"开始下棋"); return; }
        // Logout/unload handlers cancel in-flight work; no world actions run without an online owner.
        if (player == null) return;
        // Self-healing for in-flight work runs BEFORE the gates below, so a state that cannot finish
        // (for example while another mod reports her as knocked down) can never wedge her forever.
        MaidHunt.expire(maid);
        MaidDig.expire(maid);
        if (!valid(maid, player)) { MaidBuilder.cancel(maid,"绑定已失效"); MaidFieldwork.cancel(maid, "绑定已失效"); return; }
        if (player.level() != maid.level()) { MaidBuilder.cancel(maid,"主人已切换维度"); MaidFieldwork.cancel(maid, "主人已切换维度"); return; }
        // TLM's snowball task may target the owner and stop navigation after its throw animation.
        // Agnes work owns movement, so discard only that friendly target while a fieldwork step is active.
        PromaidCompat.protectAgnesWork(maid);
        nativeDefense(maid);
        if (maidReformBusy(maid)) {
            MaidPlan.clear(maid,"倒地或救援，暂停生存计划"); MaidWorkshop.cancel(maid,"倒地或救援");
            MaidFieldwork.cancel(maid, "女仆正在倒地或救援，暂停 Agnes 行动");

            return;
        }
        try {
            if (mind(maid).getBoolean("Autonomy") && maid.distanceToSqr(player) <= ACTIVITY_RANGE * ACTIVITY_RANGE) {
                MaidSurvey.tick(maid);
                // Once the scan has seen real water, keep that as a place worth remembering.
                MaidLandmarks.learnFromSurvey(maid);
            }
        }
        catch (RuntimeException incompatibleBlock) { MaidSurvey.remove(maid); mind(maid).putString("Fieldwork", "扩大范围观察遇到不兼容方块，稍后重新扫描"); }
        // A freshly arrived maid must not resume a stale path or native idle roaming. Let the
        // incremental survey and the camera refresh finish first; native combat still has priority.
        long arrivalUntil = mind(maid).getLong(ARRIVAL_UNTIL);
        if (arrivalUntil > maid.level().getGameTime()) {
            if (maid.getTarget() == null && !maid.isSleeping() && !maid.isOrderedToSit()) {
                maid.setTask(TaskManager.getIdleTask());
                maid.getNavigation().stop();
                maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }
            if (maid.tickCount % 20 == 0) MaidVision.request(player, maid);
            return;
        }
        // Native movement guards and hunting need every server tick; planning remains throttled below.
        if (maid.tickCount % 20 != 0) {
            // Keep an active fieldwork path alive between the one-second state-machine updates.
            // This matters when TLM's snowball animation calls navigation.stop().
            if (MaidFieldwork.active(maid)) {
                try { MaidFieldwork.tick(maid, player); }
                catch (RuntimeException unsupported) { MaidFieldwork.cancel(maid, "此处采集与模组规则不兼容，停止本步并重新规划"); }
            }
            CompoundTag live = mind(maid);
            if (live.getBoolean("Autonomy") && !live.getBoolean("Recovering")
                && !MaidFieldwork.active(maid) && !MaidPlan.active(maid) && !MaidWorkshop.active(maid)
                && !MaidBuilder.active(maid) && !MaidLandmarks.active(maid) && !MaidHunt.active(maid)
                && !MaidDig.active(maid) && maid.getTarget() == null && !maid.isSleeping() && !maid.isOrderedToSit()
                && maid.getTask() != TaskManager.getIdleTask()
                && maid.level().getGameTime() >= live.getLong("AgnesDecisionUntil")) {
                maid.setTask(TaskManager.getIdleTask());
                maid.getNavigation().stop();
                maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            }
            return;
        }
        if (maid.tickCount % 1200 == 0) SESSIONS.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().lastSeen > 180_000);
        // Watchdog: a request that never comes back used to wedge her for the rest of the session,
        // because the planning gate skips everything while one is pending. This frees the gate and says
        // so, so a hung request can never look like "she simply refuses to act".
        Session watchdog = session(maid);
        if (watchdog.pending && maid.level().getGameTime() - watchdog.pendingSince > REQUEST_WATCHDOG) {
            watchdog.pending = false;
            watchdog.queue.clear();
            outcome(maid, "上一次请求超过三分钟没有返回，已取消它并继续安排");
            mind(maid).putLong("NextThink", maid.level().getGameTime() + 100);
            tell(maid, player, "刚才那次请求超过 90 秒没有返回，我已经取消它，马上重新安排。");
        }
        MaidSurvival.tick(maid, player);
        updatePersonality(maid);
        applyLook(maid);
        // Her voice is local and drives its own cooldowns, so she can open a conversation instead of
        // only answering one. Lines appear in the maid's own speech bubble, not in chat.
        try { MaidVoice.tick(maid, player); }
        catch (RuntimeException brokenVoice) { MaidVoice.clear(); }
        try { MaidFieldwork.tick(maid, player); }
        catch (RuntimeException unsupported) { MaidFieldwork.cancel(maid, "此处采集与模组规则不兼容，停止本步并重新规划"); }
        try { MaidBuilder.tick(maid,player); } catch(RuntimeException incompatible) { MaidBuilder.cancel(maid,"建造与当前方块规则不兼容"); }
        if (maid.isSleeping() || maid.isOrderedToSit() || maid.getTarget()!=null || mind(maid).getBoolean("Recovering")) MaidWorkshop.cancel(maid,"休息、战斗或修整中断烧炼等待");
        else try { MaidWorkshop.tick(maid); } catch(RuntimeException incompatible) { MaidWorkshop.cancel(maid,"熔炉规则不兼容，停止取物"); }
        Session session = session(maid);
        long now = maid.level().getGameTime();
        if (!session.pending && session.queue.isEmpty()) MaidPlan.tick(maid,player);
        if (!session.pending && !session.queue.isEmpty() && maid.distanceToSqr(player) <= ACTIVITY_RANGE * ACTIVITY_RANGE) {
            request(maid, player, session, session.queue.poll(), false);
        }
        if (!session.shareId.isBlank()) {
            if (now > session.shareUntil) {
                session.shareId = ""; outcome(maid, "分享超时，没有扣除物品"); tell(maid, player, "我没能走到你身边，先保管着物品，靠近后再叫我给你吧。");
            } else if (maid.distanceToSqr(player) <= 16) {
                String result = share(maid, player, session.shareId, session.shareCount);
                session.shareId = ""; outcome(maid, result); tell(maid, player, result);
            }
            return;
        }
        CompoundTag data = mind(maid);
        if (!data.getBoolean("Autonomy") || data.getBoolean("Recovering")) return;
        if (maid.getHealth() < maid.getMaxHealth() * 0.35f || maid.distanceToSqr(player) > ACTIVITY_RANGE * ACTIVITY_RANGE) {
            if (maid.isHomeModeEnable()) {
                follow(maid); outcome(maid, "受伤或离主人太远，已恢复跟随");
                scheduleNext(maid);
            }
            if (maid.getHealth() < maid.getMaxHealth() * 0.35f) return;
        }
        if (maid.distanceToSqr(player) > ACTIVITY_RANGE * ACTIVITY_RANGE) return;
        // Respect native sleeping, sitting and combat instead of interrupting them for a new plan.
        if (maid.isSleeping() || maid.isOrderedToSit() || maid.getTarget() != null || session.pending || !session.queue.isEmpty() || MaidFieldwork.active(maid) || MaidPlan.active(maid) || MaidWorkshop.active(maid) || MaidBuilder.active(maid) || MaidLandmarks.active(maid) || MaidHunt.active(maid) || MaidDig.active(maid) || now < data.getLong("ManualUntil")) return;
        // In free mode Agnes owns movement. Stop a native idle task from taking over between plans.
        if (maid.getTask() != TaskManager.getIdleTask()
            && now >= data.getLong("AgnesDecisionUntil")) {
            maid.setTask(TaskManager.getIdleTask());
            maid.getNavigation().stop();
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            data.putString("Outcome", "自主模式已收回原生闲逛，等待 Agnes 下一步安排");
        }
        long due = data.contains("RetryAt") && data.getLong("RetryAt") > 0
            ? data.getLong("RetryAt")
            : data.getLong("NextThink");
        if (now >= due) {
            data.remove("RetryAt");
            data.putLong("NextThink", now + PartnerConfig.planningInterval());
            if (!PartnerConfig.getApiKey().isBlank()) {
                // Whatever she is turning over belongs in the native thinking bubble, so the owner can
                // watch her reason even though the whole round trip takes tens of seconds.
                String goal = data.getString("Goal");
                MaidBubble.think(maid, goal.isBlank() ? "看看周围，想一件现在能做的事……" : "在想怎么推进：" + goal);
                // A decision made on a stale picture is a guess, so ask for a fresh one first.
                MaidVision.request(player, maid);
                trace(maid, "planning autonomous round; food=" + MaidSurvival.foodCount(maid) + " idle=" + data.getInt("IdleNoAction")
                    + " task=" + maid.getTask().getUid());
                request(maid, player, session, "继续按你自己的生存节奏玩下去。看当前观察、背包和上一步真实结果，决定这段游戏时间要做什么，优先让自己不饿、工具够用、有地方过夜；能顺手做的事就安排上，不要站在原地下指令。如果眼前的画面看不清想处理的东西，先用 look 或 scan_area 看一眼。", true);
            } else {
                outcome(maid, "没有配置 Agnes API Key，无法进行自主决策");
                MaidBubble.think(maid, "还没有 API Key，暂时无法替你规划行动。");
                data.putLong("NextThink", now + 200);
            }
            return;
        }
        // Between decisions she stays still. Movement must come from a current Agnes plan or
        // an explicitly selected native task; local idle roaming is disabled in free mode.
        try { MaidLandmarks.tick(maid, player); }
        catch (RuntimeException unsupported) { MaidLandmarks.cancel(maid); }
        // Deliberate hunting and a staircase shaft, both one-second state machines.
        try { MaidHunt.tick(maid, player); }
        catch (RuntimeException unsupported) { MaidHunt.cancel(maid, "猎取与当前模组规则不兼容，停手了"); }
        try { MaidDig.tick(maid, player); }
        catch (RuntimeException unsupported) { MaidDig.cancel(maid, "挖掘与当前模组规则不兼容，停止挖掘"); }
    }

    /**
     * Tasks Agnes may choose for survival work.
     *
     * Board games are deliberately excluded. Sitting at a gomoku table keeps the maid in a state where
     * every world action is refused, so offering that task let the model pick something that could
     * never finish anything: she would sit down to play chess and then report "I am playing chess" for
     * the rest of the session. The player still starts a game on purpose through /aipartner go.
     */
    static boolean survivalTask(IMaidTask task) {
        try {
            ResourceLocation key = task.getUid();
            if (key != null && key.getPath().equals("board_games")) return false;
            String name = task.getName().getString();
            return name == null || !name.toLowerCase(java.util.Locale.ROOT).contains("chess");
        } catch (RuntimeException brokenTask) { return false; }
    }

    static List<IMaidTask> availableTasks(EntityMaid maid) {
        return TaskManager.getTaskIndex().stream().filter(t -> {
            try { return t.isEnable(maid) && !t.isHidden(maid) && survivalTask(t); } catch (RuntimeException e) { return false; }
        }).toList();
    }

    /** Survival choices include native tasks only when their real world prerequisites are present. */
    static List<IMaidTask> availableSurvivalTasks(EntityMaid maid) {
        return availableTasks(maid).stream().filter(task -> {
            if (!(task instanceof IFarmTask farmTask)) return true;
            return MaidSurvival.farmWorkAvailable(maid, farmTask);
        }).toList();
    }

    private static JsonArray taskCatalog(EntityMaid maid) {
        JsonArray result = new JsonArray();
        for (IMaidTask task : availableSurvivalTasks(maid).stream().limit(64).toList()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", task.getUid().toString()); entry.addProperty("name", task.getName().getString());
            entry.addProperty("temporarily_blocked", MaidSurvival.blocked(maid, task.getUid().toString()));
            entry.addProperty("summary", limit(task.getMaidActionSummary(), 240));
            JsonArray conditions = new JsonArray();
            try {
                for (var condition : task.getConditionDescription(maid)) conditions.add(condition.getFirst() + "=" + condition.getSecond().test(maid));
            } catch (RuntimeException e) { conditions.add("unknown; require runtime check"); }
            entry.add("conditions", conditions); result.add(entry);
        }
        return result;
    }

    static boolean maidHasShelter(EntityMaid maid) {
        // Any completed building counts, and so does a remembered house or cottage landmark.
        JsonObject building = MaidBuilder.describe(maid);
        if (building.has("completed") && building.get("completed").getAsBoolean()) return true;
        return MaidLandmarks.hasNearbyOrRemembered(maid, "house");
    }

    private static MaidPersonality.Observation personalityObservation(EntityMaid maid) {
        var inv = maid.getAvailableBackpackInv(); int food=0, wood=0, stone=0, iron=0;
        for (int i=0;i<inv.getSlots();i++) { ItemStack s=inv.getStackInSlot(i); if (s.isEmpty()) continue;
            var id=BuiltInRegistries.ITEM.getKey(s.getItem()).toString(); int n=s.getCount();
            if (s.isEdible()) food+=n; if (id.endsWith("_log") || id.endsWith("_stem")) wood+=n;
            if (id.equals("minecraft:cobblestone") || id.equals("minecraft:cobbled_deepslate") || id.equals("minecraft:stone")) stone+=n;
            if (id.equals("minecraft:iron_ingot")) iron+=n; }
        boolean danger=maid.getTarget()!=null || maid.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, maid.getBoundingBox().inflate(12)).size()>0;
        boolean shelter=maidHasShelter(maid);
        return new MaidPersonality.Observation(maid.level().getGameTime(), maid.level().getDayTime(), maid.getHealth(), maid.getMaxHealth(), danger, maid.isSleeping() || maid.isOrderedToSit(), food, wood, stone, iron, shelter);
    }

    private static void updatePersonality(EntityMaid maid) {
        var p=MaidPersonality.data(mind(maid), maid.getUUID(), maid.level().getGameTime());
        var o=personalityObservation(maid); MaidPersonality.observe(p,o);
        if (o.health() < o.maxHealth()*0.4f || o.danger()) MaidPersonality.idle(p,false);
        else MaidPersonality.idle(p, !MaidFieldwork.active(maid) && !MaidPlan.active(maid) && !MaidWorkshop.active(maid) && !MaidBuilder.active(maid));
    }

    static String inventorySummary(EntityMaid maid) {
        IItemHandler inventory = maid.getAvailableInv(true);
        List<String> contents = new ArrayList<>();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty()) contents.add(BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount());
        }
        return contents.isEmpty() ? "empty" : String.join(", ", contents);
    }

    static String stamp(EntityMaid maid) {
        return maid.getTask().getUid() + ":" + maid.isHomeModeEnable() + ":" + maid.isOrderedToSit() + ":"
            + maid.getSchedule() + ":" + mind(maid).getInt("PlanRevision") + ":reform=" + maidReformBusy(maid);
    }

    private static void request(EntityMaid maid, ServerPlayer player, Session session, String input, boolean autonomous) {
        String beforeCapture = stamp(maid);
        session.pending = true; session.pendingSince = maid.level().getGameTime();
        MaidVision.request(player, maid).whenComplete((unused, error) -> player.server.execute(() -> {
            if (SESSIONS.get(maid.getUUID()) != session || !valid(maid, player)) { session.pending = false; return; }
            if (autonomous && (!mind(maid).getBoolean("Autonomy") || !stamp(maid).equals(beforeCapture))) {
                session.pending = false;
                String next = session.queue.poll();
                if (next != null) request(maid, player, session, next, false);
                return;
            }
            requestAfterCapture(maid, player, session, input, autonomous, 0);
        }));
    }

    /** A decision built on a picture this old is a guess, so she tries once more for a fresh one. */
    private static final long FRESH_FRAME_MS = 5000;

    private static void requestAfterCapture(EntityMaid maid, ServerPlayer player, Session session, String input, boolean autonomous, int attempts) {
        session.pending = false;
        if (autonomous && attempts < 2) {
            MaidVision.Frame frame = MaidVision.read(player, maid);
            // A missed capture is not a failure: planning on game state is fully supported, so this
            // costs one extra screenshot at most and never blocks her.
            if (frame == null || frame.ageMillis() > FRESH_FRAME_MS) {
                if (frame == null) {
                    mind(maid).putString("LastVision", "本次没有拿到女仆画面，改用游戏状态");
                } else {
                    MaidBubble.think(maid, "先把眼前的画面看清楚……");
                    MaidVision.request(player, maid).whenComplete((unused, error) -> player.server.execute(() -> {
                        if (SESSIONS.get(maid.getUUID()) != session || !valid(maid, player)) { session.pending = false; return; }
                        requestAfterCapture(maid, player, session, input, autonomous, attempts + 1);
                    }));
                    return;
                }
            }
        }
        try { requestInternal(maid, player, session, input, autonomous); }
        catch (RuntimeException preparationError) {
            session.pending = false;
            outcome(maid, "读取任务或环境时出错，未发送请求");
            mind(maid).remove("LastAutonomousRequest");
            mind(maid).putLong("NextThink", maid.level().getGameTime() + Math.min(PartnerConfig.planningInterval(), 200));
            if (!autonomous) tell(maid, player, "这次没能读取女仆的任务或环境，请稍后再试。");
        }
    }

    private static void requestInternal(EntityMaid maid, ServerPlayer player, Session session, String input, boolean autonomous) {
        if (!valid(maid, player)) return;
        if (autonomous && mind(maid).contains("LastAutonomousRequest")) {
            long now = maid.level().getGameTime();
            long retryAt = mind(maid).getLong("RetryAt");
            long regularDue = mind(maid).getLong("LastAutonomousRequest") + PartnerConfig.planningInterval();
            if (now < regularDue && (retryAt <= 0 || now < retryAt)) {
                mind(maid).putLong("NextThink", regularDue); return;
            }
        }
        if (autonomous) mind(maid).putLong("LastAutonomousRequest", maid.level().getGameTime());
        if (!autonomous) {
            // Ordinary chat is also her chance to say something she has been holding in. An urgent
            // line about her own state goes out first; the conversation follows in the next bubble.
            try { MaidVoice.tick(maid, player); } catch (RuntimeException brokenVoice) { MaidVoice.clear(); }
        }
        session.pending = true; session.pendingSince = maid.level().getGameTime();
        if (autonomous) MaidSurvival.observeProgress(maid);
        String stateStamp = stamp(maid);
        JsonObject state = new JsonObject();
        state.addProperty("autonomous_request", autonomous);
        MaidVision.Frame frame = MaidVision.read(player, maid);
        String screenshot = frame == null ? "" : frame.png();
        state.addProperty("screenshot_source", frame == null ? "unavailable; use actual maid state" : "maid_first_person");
        if (frame != null) state.add("camera_observation", frame.describe());
        else state.addProperty("camera_status", MaidVision.status(player));
        state.addProperty("vision_freshness", frame == null ? "no usable image this round; rely on sight and game state"
            : frame.ageMillis() <= 4000 ? "fresh" : "older than four seconds; trust sight and game state for positions");
        state.addProperty("last_blocked_reason", mind(maid).getString("StuckReason"));
        // Deliberate hunting is separate from the native combat that only answers an attack.
        state.add("huntable_animals", MaidHunt.describe(maid, "animal"));
        state.addProperty("hunt_rule", MaidHunt.canFight(maid)
            ? "she is holding something to fight with, hunting is ready"
            : "she is EMPTY HANDED and can only kill small animals; craft or equip a sword or axe before planning a hunt");
        state.addProperty("hunt_in_progress", MaidHunt.describe(maid));
        state.addProperty("last_hunt_result", mind(maid).getString("HuntResult"));
        state.addProperty("dig_result", MaidDig.summary(maid));
        state.addProperty("shaft_depth_remaining", MaidDig.active(maid) ? "正在挖掘" : "没有进行中的竖井");
        // Her own memory of places. A remembered site needs no fresh observation, which is what gives
        // her continuity between visits instead of rediscovering the same water and camp every time.
        JsonObject memory = new JsonObject();
        memory.add("remembered_places", MaidLandmarks.describe(maid, 12));
        memory.addProperty("total_remembered", MaidLandmarks.count(maid));
        memory.addProperty("scope", "Places SHE recorded herself from actions she really performed, or water "
            + "she really saw while scanning. They persist in the save. A remembered place is a hypothesis: "
            + "she walks there from memory and verifies it on arrival, and forgets it after two failed visits.");
        state.add("memory", memory);
        state.add("landmarks", MaidLandmarks.describe(maid, 12));
        // The picture shows the situation; this list makes it exact and lets her choose a real target.
        state.add("sight", MaidSight.describe(maid));
        state.addProperty("maid_position", maid.blockPosition().toShortString());
        state.addProperty("owner_position", player.blockPosition().toShortString());
        state.addProperty("dimension", maid.level().dimension().location().toString());
        state.addProperty("day_time", maid.level().getDayTime() % 24000);
        state.addProperty("survival_plan", mind(maid).getString("SurvivalPlan"));
        state.addProperty("consecutive_idle_decisions_without_action", mind(maid).getInt("IdleNoAction"));
        CompoundTag personality = MaidPersonality.data(mind(maid), maid.getUUID(), maid.level().getGameTime());
        state.addProperty("goal", mind(maid).getString("Goal"));
        state.addProperty("last_result", mind(maid).getString("Outcome"));
        JsonArray actionMemory = new JsonArray();
        for (var tag : mind(maid).getList("ActionMemory", 10)) {
            if (tag instanceof CompoundTag episode) {
                JsonObject item = new JsonObject();
                item.addProperty("tick", episode.getLong("tick"));
                item.addProperty("success", episode.getBoolean("success"));
                item.addProperty("result", episode.getString("result"));
                actionMemory.add(item);
            }
        }
        state.add("action_memory", actionMemory);
        state.addProperty("current_task", maid.getTask().getUid().toString());
        state.addProperty("home_mode", maid.isHomeModeEnable());
        state.addProperty("health", maid.getHealth()); state.addProperty("max_health", maid.getMaxHealth());
        state.addProperty("schedule", maid.getSchedule().name());
        state.addProperty("sleeping", maid.isSleeping());
        state.addProperty("maid_reform_knockdown_or_rescue", maidReformBusy(maid));
        state.add("survival", MaidSurvival.describe(maid));
        state.add("workshop", MaidWorkshop.describe(maid));
        state.add("storage",MaidStorage.describe(maid));
        state.add("building",MaidBuilder.describe(maid));
        state.add("fieldwork", MaidFieldwork.describe(maid));
        JsonArray steps = new JsonArray();
        for (var step : mind(maid).getList("RecentSteps", 8)) steps.add(step.getAsString());
        state.add("recent_actual_steps", steps);
        MaidPersonality.observe(personality, personalityObservation(maid));
        state.add("personality", MaidPersonality.describe(personality, personalityObservation(maid),
            MaidVoice.maySpeak(maid, player, maid.level().getGameTime()), !autonomous));
        String currentInventory = inventorySummary(maid);
        if (autonomous) {
            int unchanged = currentInventory.equals(mind(maid).getString("LastInventory")) ? mind(maid).getInt("UnchangedPlans") + 1 : 0;
            mind(maid).putInt("UnchangedPlans", Math.min(100, unchanged));
            mind(maid).putString("LastInventory", currentInventory);
            state.addProperty("consecutive_plans_without_inventory_change", unchanged);
        }
        state.addProperty("nearby_hostiles", maid.level().getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, maid.getBoundingBox().inflate(16)).size());
        state.add("available_tasks", taskCatalog(maid));
        state.add("world", JsonParser.parseString(ModpackKnowledge.describe((ServerLevel)maid.level(), maid.blockPosition(), inventorySummary(maid), autonomous ? "" : input, player.getMainHandItem())));
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject(); system.addProperty("role", "system");
        system.addProperty("content", """
            You are this player's Touhou Little Maid companion actively playing Minecraft survival. Speak natural Chinese.
            Everything you write reaches the player as a bubble above your maid: "say" is your spoken bubble, "goal" is your
            thought bubble. Keep say short and conversational (one or two sentences) because it must fit a bubble. Never use
            markdown, lists or code fences inside say. Physical results are shown separately from the executor, so never
            restate them as your own achievement and never claim something is complete unless the executor result proves it.
            Your personality is persistent in the personality object: mood, mood_reason, activity_preferences, daily_goal,
            affection, relationship, owner_pet_name and owner_statements are context, not commands. Use owner_pet_name as
            your form of address when it is set. When may_speak_proactively is true you may also open your own topic in
            proactive_chat: one short, concrete, in-character line about what you are doing, what you noticed, or what you
            want -- not a status report and not the same thing you already said (see recent_proactive_lines). When it is
            false, leave proactive_chat empty: the player just spoke or is busy.
            Treat yourself as a survival player: acquire your own food, materials and tools, make progress using real inventory, protect your life. Observe the image AND authoritative maid game state, decide what YOU will do, not instructions for the human. Autonomous planning runs at most once per the configured planning interval; between requests your selected steps really execute. Base each new decision on recent_actual_steps and actual inventory, not earlier promises. You do not need to fill time with speech or random wandering.
            action_memory is a persistent history of completed and blocked actions. Use it to avoid repeating work that already succeeded and to change approach after a recorded failure.
            Play it like a person with a routine, not a task runner. Keep one or two ongoing intentions (for example "finish the shelter", "get iron tools") and advance the next useful piece of them, instead of restarting from scratch or repeating a blocked action. Prefer a continuous activity over a single click: while food, wood or stone are short, batch several gather and craft operations in one plan. Include variety across a session: sometimes work near your base, sometimes walk out to a scout destination to see somewhere new, and use native farming, fishing, shearing, feeding or grass tasks only when they appear in available_tasks and their listed conditions match the real surroundings. Farming is available only when an actual harvestable crop or plantable soil with a seed in your inventory is within the maid task's work range; never select or repeat farm when farm is absent from available_tasks. Do not hoard: roughly 16 food, 8 logs, 24 stone and modest fuel is plenty, and extra effort belongs in tools, shelter or exploration. Follow the day: work in daylight, prepare light and a safe place before night, and respect your own sleep schedule. When you truly cannot do anything useful here, say the concrete missing condition instead of pretending, and consider moving somewhere more promising.
            fieldwork.area_survey incrementally scans a horizontal radius of 100 blocks in LOADED chunks, the nearby height band and surface. This is game-data knowledge, not visual line-of-sight or a guarantee of a path. Read scan_percent and skipped_unloaded_columns. An attached screenshot is YOUR MAID'S first-person camera at camera_observation.eye_position/yaw/pitch, NOT the owner's screen. It contains only terrain loaded by the owner's client. It is a past observation: use its age and capture pose; your current direction may have changed. Darkness, occlusion and missing terrain are not proof of an empty safe path. The authoritative game state and executable observed target IDs take priority over image guesses. If no image is attached do not pretend to see one. Do not say your observation range is four blocks; four blocks is the local workstation/container interaction distance, not your observation radius.
            Return ONLY a JSON object with these fields:
            {"say":"Chinese conversation reply or empty","proactive_chat":"short optional social line or empty","remember_quote":"exact short owner preference quote or empty","action":"plan|keep|follow|stay|explore|task|share|inspect_storage|craft|equip|gather|approach|place|smelt|withdraw|deposit|build_house|look|scan_area|visit_landmark|dig_shaft","steps":[],"site_id":"observed house site ID","amount":1,"target_id":"EXACT observed target ID or landmark:N from memory.remembered_places","yaw":0,"pitch":0,"task_id":"EXACT available task id","recipe_id":"EXACT observed recipe id","item_id":"EXACT item ID","goal":"concrete ongoing survival goal","count":1}
            Do not choose hunt during autonomous planning. Moving animals are unreliable targets and active hunting is disabled in this mode; use gathered food, berries, fishing or an actually available native farm task instead. Hostile defense is handled immediately by the maid's native Attack task and never waits for Agnes.
            dig_shaft makes a one-wide staircase straight down to get stone and iron, with count as the target depth (4-24, default 12). The stairs she digs ARE the way back up, so she never traps herself. She stops and returns to the surface the moment a block would open into water or lava, if she is low on health, if she is attacked, or if she cannot reach the next step. The shaft is slow: one step at a time, placing a torch every six blocks. She records the shaft as her mine when it is done. She cannot mine sideways into a vein, cannot dig into bedrock, and cannot clear gravel or sand that would fall on her.
            memory.remembered_places are places SHE recorded herself: her water, the ore she dug, the shelter she built, her table and furnace, her farm, her camp. Each has a target_id like landmark:3, a name, a distance and how often it was used. She wrote these from actions that really happened, so they are the best available guess about where things are, but they are still only a memory. Use action=visit_landmark with that target_id (or approach with it) to go back to one: no fresh observation is needed, she walks from memory and checks on arrival whether it is still what she remembers. A place marked as not found last time may be gone; if you go anyway, expect her to forget it after another failure. Prefer a remembered water or camp over wandering when you need food or a safe spot, and prefer visiting over scanning when something you need is already in the list.
            sight is what her own eyes can see right now: forward_ray is the first solid thing in her line of sight, visible_columns lists the nearest thing in each direction inside her view cone with its block id, distance, whether it is reachable and whether it has a gatherable_as kind plus the exact target_id to use, and walkable_ground_ahead tells you whether the way in front is open. Use it together with the picture: the image shows the situation, sight makes it exact. Report what you see honestly; never claim to see something that is not in sight, the survey or the game state.
            look turns her head to a direction (target_id, or yaw/pitch in degrees) and holds it about four seconds; scan_area sweeps a full circle taking a fresh observation. Looking changes nothing in the world and is allowed even while resting, so use look or scan_area first when the current view does not show what you need to decide about, then act on the next round. Do not use look as a substitute for a real action you already promised.
            For idle survival prefer action=plan with 1-8 steps (max 16 operations total). Each step is {"action":"gather|approach|craft|equip|place|smelt|withdraw|deposit|build_house","target_kind":"wood|berries|stone|coal|iron","target_id":"observed ID when needed","recipe_id":"observed recipe ID","item_id":"item ID","count":1}. A gather step may use target_kind instead of target_id; executor selects a fresh observed reachable candidate of ONLY your chosen kind each time. count repeats 1-8 times, allowing collecting several logs or cobblestone and crafting multiple batches without waiting for the next planning interval between each. An exact target_id will NOT magically replenish: do not repeat a removed block. All prerequisites are checked before each step; first failure stops the rest and feeds you the reason next round. No new activities are invented locally. Native work, conversation, share, rest and follow must be single actions, not inside steps.
            Physical intentions MUST have a matching action and its required ID, not merely say or goal. gather supports berries, natural tree logs, exposed stone/deepslate/coal/iron; mining requires a correct held pickaxe (iron ore needs stone or better). For approach select table, furnace, storage or scout. For explore select a scout destination; this is actual walking. Never invent coordinates or IDs. Each gather operation walks to and collects ONE resource and spends tool durability. It takes time and may fail. Never announce completion before the executor confirms it.
            During autonomy, if idle, healthy and not resting, select a useful feasible action when one exists. When food is low prefer a listed berry target; for missing wood use a listed wood target; then craft real supplies using actual ingredients. Avoid excessive stocks and repeating a blocked action. If nothing is feasible explain the actual missing condition, or approach a listed scout destination. Keep productive native tasks running. Keep is appropriate for ongoing activity or rest, not as a substitute for a promised new action.
            Never answer an autonomy round with only talk. If continuous_idle_decisions_without_action is above zero you have already failed to act that many times in a row: stop deliberating and pick the single most useful executable action this round, even a small one (gather one log, walk to a remembered water, place a torch). Saying what you plan to do without choosing action and steps is treated as no action at all, and the player is told how many rounds you have been talking instead of working.
            craft consumes ONE batch of an ordinary shaped/shapeless recipe from YOUR backpack. A grid larger than 2x2 requires a visible crafting table within four blocks; approach a listed table first if necessary. Use recipe_id from survival.basic_craft_options or world.recipe_lookup. Preserve special/enchanted gear on autonomous equip. task must use an enabled available_tasks ID, with tools and location conditions; blocked tasks must not be selected.
            Survival progression: secure food first when needed; gather several wood logs; craft planks, sticks and a crafting table; place that table from your backpack; craft and equip a wooden pickaxe; gather exposed stone; craft and equip stone tools; craft/place a furnace; gather coal and raw iron with the right pickaxe; smelt raw iron, then craft iron gear. Plan only the feasible next segment; save extra building blocks for a planned house, otherwise keep supplies modest (roughly 16 food, 8 logs, 24 cobblestone, 8 coal/iron), don't endlessly make tables/tools you already own. Use ingredient quantities in basic_craft_options and account for every batch. Ingredients made by earlier steps may satisfy a later recipe currently not ready. If food sources are absent, use farm only if the real farm task is listed in available_tasks; otherwise choose a listed fishing task or scout, and report when neither is feasible. Keep productive native work running.
            What she can and cannot do, so requests stay realistic: she CAN gather surface wood, berries and exposed stone/coal/iron, craft ordinary recipes, equip gear, place a table/furnace/torch, smelt one item at a time, use nearby chests, build one fixed 5x5 shelter, walk to scouted places or remembered landmarks, work the native farm/fishing/shearing/feeding tasks, and sink a staircase shaft for stone and iron. She CANNOT actively hunt moving animals in autonomous mode, dig sideways along a vein, tunnel through bedrock, clear falling gravel safely, design her own building, enchant, brew, trade with villagers, fight her way through the Nether or the End, or operate modded machines. Hostile mobs are handled by the native maid Attack task. Do not promise unsupported actions.
            place uses item_id minecraft:crafting_table, minecraft:furnace or minecraft:torch ONLY. It places ONE actual backpack item on a nearby valid empty floor, respecting protection; use build_house for a shelter blueprint. If an existing table is observed prefer approaching it. smelt item_id is an actual raw ingredient from workshop.smelt_options (or minecraft:raw_iron/minecraft:oak_log after gathering); it consumes ONE ingredient and ONE coal/charcoal/plank fuel and waits for an actual vanilla furnace to finish before continuing the plan. Only a furnace THIS maid placed, visible within four blocks and with empty item slots is used; place one if needed. Each smelt repetition waits for its own completion. Use accessible ordinary storage through withdraw/deposit, never bypass the container access rules.
            Additional permitted plan steps are withdraw, deposit and build_house, using the same fields as the single action. The owner authorizes accessing nearby ordinary chests/barrels for survival needs through these storage actions. This expands the earlier inventory restriction: withdraw from accessible ordinary storage is allowed; do not access other inventories or take unrelated supplies. storage lists actual accessible contents; distant containers require approach to their exact storage:... target first and a new observation if contents are unknown. approach supports storage as well as table/furnace/scout. withdraw/deposit uses target_id, item_id and amount (1-64 items); count still means repeats, NOT transfer amount. Take only material for your next needed tools/food/house; do not empty unrelated supplies. Locked/protected containers, unopened loot chests, ender chests, modded machines and special NBT/named/enchanted items are excluded. Partial transfer stops a plan and reports actual amount.
            build_house uses site_id from building.clear_site_ids_by_kind and item_id for ONE type of vanilla planks/cobblestone/cobbled_deepslate/stone/stone_bricks/bricks. Three fixed blueprints exist, chosen by the site id prefix: hut is the 5x5 shelter (about 80 matching blocks, 1 oak door, 1 torch); cottage is a 7x7 stone cottage with a storage room (about 175 matching blocks, 1 oak door, 2 torches, one internal partition so the back room can hold chests and a furnace); farm is a 6x6 fenced garden (about 20 fence pieces plus 16 farmland, and she only builds the soil she actually carries). All are fixed layouts, not free design, and she says so if asked for something else. She builds one block at a time with real placement and can resume an interrupted job from saved_site_id; completed=true means stop building and do something else. Gather, craft or withdraw the materials first; check building.remaining_materials for a job in progress. After food and tools, build the hut first, then the cottage when she has the stone, and a farm when she has a hoe or farmland to work with.
            For a player asking what is in a nearby chest or barrel, use action=inspect_storage; it needs no target_id or item_id and returns observed ordinary contents. Do not turn “看看仓库有什么、你用得上什么” into withdraw until the player explicitly asks to take a named item and amount. If the player asks to gather but target_id is unavailable, provide target_kind=wood|berries|stone|coal|iron; the executor chooses one fresh observed candidate. Never emit a blank target_id for approach, withdraw, deposit or build_house.\r\n            No arbitrary tunnels, arbitrary custom architectural blueprints, complex machines, rituals, quests or boss progression. If a request is unsupported, explain the limitation, do not claim it is underway. No sharing without an explicit player request. Ordinary casual conversation uses keep and preserves work. A clear request to do something uses the appropriate executable action. Respect sleep, combat, sitting and recovery. Physical replies are displayed from the executor's real result; say is for conversation, not fabricated action reports.
            """);
        messages.add(system);
        for (var tag : mind(maid).getList("Memory", 8)) {
            JsonObject history = new JsonObject(); history.addProperty("role", "user"); history.addProperty("content", "Earlier conversation/outcome: " + tag.getAsString()); messages.add(history);
        }
        JsonObject user = new JsonObject(); user.addProperty("role", "user");
        String prompt = "Current observations=" + state + "\n" + (autonomous ? "Autonomous planning: " : "Player says: ") + input;
        user.addProperty("content", prompt); messages.add(user);
        JsonObject body = new JsonObject(); body.addProperty("model", PartnerConfig.getModel()); body.addProperty("max_tokens", 1600); body.add("messages", messages);
        JsonObject textBody = body.deepCopy();
        var imageAccepted = new java.util.concurrent.atomic.AtomicBoolean(false);
        boolean primarySupportsVision = PartnerConfig.isVisionEnabled() && PartnerConfig.supportsVision(PartnerConfig.getModel());
        if (primarySupportsVision && !screenshot.isBlank()) {
            JsonArray content = new JsonArray();
            JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", prompt); content.add(text);
            JsonObject image = new JsonObject(); image.addProperty("type", "image_url");
            JsonObject url = new JsonObject(); url.addProperty("url", "data:image/png;base64," + screenshot); image.add("image_url", url); content.add(image);
            user.add("content", content);
        }
        String key = PartnerConfig.getApiKey();
        send(body, key, "https://apihub.agnes-ai.com/v1/chat/completions", PartnerConfig.getModel()).thenCompose(response -> {
            if (primarySupportsVision && !screenshot.isBlank() && (response.statusCode() < 200 || response.statusCode() >= 300)) {
                imageAccepted.set(false);
                textBody.getAsJsonArray("messages").get(messages.size()-1).getAsJsonObject().addProperty("content", prompt + "\nImage upload was rejected; no screenshot is attached to this request. Use game state only.");
                return send(textBody, key, "https://apihub.agnes-ai.com/v1/chat/completions", PartnerConfig.getModel());
            }
            return java.util.concurrent.CompletableFuture.completedFuture(response);
        }).handle((response, error) -> {
            if ((error != null || response == null || response.statusCode() < 200 || response.statusCode() >= 300)
                && PartnerConfig.isZhipuFallback()) {
                JsonObject zhipuBody = fallbackBody(body, PartnerConfig.getZhipuModel());
                return send(zhipuBody, PartnerConfig.getZhipuApiKey(), "https://open.bigmodel.cn/api/paas/v4/chat/completions", PartnerConfig.getZhipuModel())
                    .handle((zhipuResponse, zhipuError) -> {
                        if ((zhipuError != null || zhipuResponse == null || zhipuResponse.statusCode() < 200 || zhipuResponse.statusCode() >= 300)
                            && PartnerConfig.isCustomFallback()) {
                            return send(fallbackBody(body, PartnerConfig.getCustomModel()), PartnerConfig.getCustomApiKey(), PartnerConfig.getCustomApiUrl(), PartnerConfig.getCustomModel());
                        }
                        if (zhipuError != null) return java.util.concurrent.CompletableFuture.<HttpResponse<String>>failedFuture(zhipuError);
                        return java.util.concurrent.CompletableFuture.completedFuture(zhipuResponse);
                    }).thenCompose(future -> future);
            }
            if ((error != null || response == null || response.statusCode() < 200 || response.statusCode() >= 300)
                && PartnerConfig.isCustomFallback()) {
                return send(fallbackBody(body, PartnerConfig.getCustomModel()), PartnerConfig.getCustomApiKey(), PartnerConfig.getCustomApiUrl(), PartnerConfig.getCustomModel());
            }
            if (error != null) return java.util.concurrent.CompletableFuture.<HttpResponse<String>>failedFuture(error);
            return java.util.concurrent.CompletableFuture.completedFuture(response);
        }).thenCompose(future -> future).whenComplete((response, error) -> player.server.execute(() -> {
            if (SESSIONS.get(maid.getUUID()) != session || !valid(maid, player)) return;
            session.pending = false;
            try {
            if (error != null) throw new IllegalStateException("Agnes、智谱和通用备用请求都失败：连接超时或网络异常");
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("模型 HTTP " + response.statusCode());
                mind(maid).putString("LastVision", imageAccepted.get() ? "本轮已发送女仆第一人称画面＋游戏状态"
                    : !PartnerConfig.isVisionEnabled() ? "视觉已关闭，本轮使用女仆游戏状态"
                    : screenshot.isBlank() ? "本轮无有效女仆画面，使用女仆游戏状态"
                    : "当前模型不支持视觉，本轮使用女仆游戏状态");
                String content = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("choices").get(0)
                    .getAsJsonObject().getAsJsonObject("message").get("content").getAsString().replace("```json", "").replace("```", "").trim();
                JsonObject plan = JsonParser.parseString(content).getAsJsonObject();
                String say = string(plan, "say", 1000);
                MaidPersonality.acceptMemory(personality, plan, input);
                // The plan she just decided on, as a thought in her own bubble. Only the model's goal
                // wording or her persisted goal is used here, never a claim about work being done.
                String thought = MaidPersonality.clean(string(plan, "goal", 120), 120);
                if (thought.isBlank()) thought = MaidPersonality.clean(mind(maid).getString("Goal"), 120);
                String result;
                boolean executed;
                if (maid.level() != player.level() || maid.distanceToSqr(player) > ACTIVITY_RANGE * ACTIVITY_RANGE || !stamp(maid).equals(stateStamp)) {
                    result = "环境或手动任务已改变，本次计划未执行";
                    executed = false;
                    mind(maid).putLong("NextThink", maid.level().getGameTime() + Math.min(PartnerConfig.planningInterval(), 200));
                } else { result = execute(maid, player, plan, autonomous); executed = true; }
                if (!result.equals("保持当前活动")) outcome(maid, result);
                if (autonomous && blockedProgress(result)) noteStuck(maid);
                if (!thought.isBlank()) MaidBubble.think(maid, thought);
                else MaidBubble.clearThinking(maid);
                if (executed && !result.equals("保持当前活动")) {
                    // Her short reaction to what the executor really reported.
                    MaidVoice.react(maid, result);
                }
                // The verified action result stays on the action bar: it is executor data, not dialogue,
                // and it must not be confused with something she said in a bubble.
                String display = displayReply(plan, result, autonomous);
                if (!display.isBlank()) {
                    if (!autonomous) remember(maid, "玩家：" + input + "；女仆：" + display + "；执行结果：" + result);
                    feedback(maid, player, display, autonomous);
                }
                // Her conversational reply belongs in the speech bubble, not in chat. When the model
                // sent nothing usable she still answers with her own persisted mood and goal.
                if (say.isBlank() && !autonomous)
                    say = MaidVoice.fallbackReply(new MaidVoice.Context(maid, player, maid.level().getGameTime(), -1));
                if (!say.isBlank()) MaidBubble.speak(maid, say);
                // A line the model wrote is still routed through the local gate, so she cannot start
                // talking over the owner or repeat herself just because the model felt chatty.
                String proposed = string(plan, "proactive_chat", 180);
                if (!proposed.isBlank() && MaidVoice.maySpeak(maid, player, maid.level().getGameTime())) {
                    String spontaneous = MaidPersonality.acceptLine(personality, proposed, maid.level().getGameTime(), true);
                    if (!spontaneous.isBlank()) MaidBubble.speak(maid, spontaneous);
                }
            } catch (Exception failure) {
                String detail = failure.getMessage();
                if (detail == null || detail.isBlank()) detail = failure.getClass().getSimpleName();
                detail = limit(detail.replaceAll("(?i)(Bearer\\s+)[^\\s]+", "$1***"), 180);
                outcome(maid, "API 请求失败或回复格式异常：" + detail);
                mind(maid).remove("LastAutonomousRequest");
                mind(maid).putLong("NextThink", maid.level().getGameTime() + Math.min(PartnerConfig.planningInterval(), 200));
                // Replace the pending thought with the honest reason instead of leaving a stale bubble.
                MaidBubble.think(maid, autonomous ? "这一轮没想明白，过一会儿再试。" : "这次没接上话，再说一遍好吗？");
                // Even with no answer from the service she still reacts in her own bubble rather than
                // going silent, which is what a companion standing next to the player would do.
                if (!autonomous) MaidBubble.speak(maid, MaidVoice.fallbackReply(new MaidVoice.Context(
                    maid, player, maid.level().getGameTime(), -1)));
                if (!autonomous) tell(maid, player, "这次请求没有完成：" + detail + "。可以稍后再试。");
            }
            if (maid.level() == player.level() && maid.distanceToSqr(player) <= ACTIVITY_RANGE * ACTIVITY_RANGE) {
                String next = session.queue.poll();
                if (next != null) request(maid, player, session, next, false);
            }
        }));
    }

    private static java.util.concurrent.CompletableFuture<HttpResponse<String>> send(JsonObject body, String key, String endpoint, String model) {
        JsonObject requestBody = body.deepCopy();
        requestBody.addProperty("model", model);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(endpoint))
            .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json");
        if (key != null && !key.isBlank()) requestBuilder.header("Authorization", "Bearer " + key);
        HttpRequest request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody.toString())).build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject fallbackBody(JsonObject original, String fallbackModel) {
        JsonObject copy = original.deepCopy();
        copy.addProperty("model", fallbackModel);
        if (!PartnerConfig.isVisionEnabled() || !PartnerConfig.supportsVision(fallbackModel)) {
            JsonArray messages = copy.getAsJsonArray("messages");
            if (messages != null && messages.size() > 0) {
                JsonObject last = messages.get(messages.size() - 1).getAsJsonObject();
                if (last.has("content") && last.get("content").isJsonArray()) {
                    JsonArray parts = last.getAsJsonArray("content");
                    StringBuilder text = new StringBuilder();
                    for (var partElement : parts) {
                        if (!partElement.isJsonObject()) continue;
                        JsonObject part = partElement.getAsJsonObject();
                        if ("text".equals(part.has("type") ? part.get("type").getAsString() : "") && part.has("text")) {
                            if (text.length() > 0) text.append('\n');
                            text.append(part.get("text").getAsString());
                        }
                    }
                    last.addProperty("content", text.toString());
                }
            }
        }
        return copy;
    }

    /**
     * Did the world actually refuse to let her do the thing she chose? Transport and format failures
     * are excluded on purpose: those are not reasons to look at the world differently.
     */
    static boolean blockedProgress(String result) {
        if (result == null || result.isBlank()) return false;
        if (result.contains("API") || result.contains("网络") || result.contains("回复格式")) return false;
        return result.contains("无法到达") || result.contains("被地形阻挡") || result.contains("没有找到")
            || result.contains("超时") || result.contains("已变化") || result.contains("不支持")
            || result.contains("不可用") || result.contains("缺少") || result.contains("未启动");
    }

    /**
     * The executor could not make progress, so the next decision should not wait the full cycle. This
     * only ever shortens her own autonomous lane, never an owner instruction, and the upcoming request
     * also asks for a fresh look first.
     */
    static void noteStuck(EntityMaid maid) {
        CompoundTag data = mind(maid);
        long now = maid.level().getGameTime();
        long due = now + PartnerConfig.stuckInterval();
        long current = data.contains("RetryAt") && data.getLong("RetryAt") > 0 ? data.getLong("RetryAt") : Long.MAX_VALUE;
        if (due < current) data.putLong("RetryAt", due);
        data.putString("StuckReason", limit(data.getString("Outcome"), 120));
    }

    static String execute(EntityMaid maid, ServerPlayer player, JsonObject plan, boolean autonomous) {
        Session session = session(maid);
        if (GoMaid.playing(maid, player)) {
            // A plan that arrived while she is at a board means survival work outranks the game. End the
            // game and continue, instead of refusing the plan and leaving her seated at the table for
            // the whole session, which is what made her talk about work and never do any.
            if (maid.isPassenger()) maid.stopRiding();
            maid.setOrderedToSit(false);
            maid.getNavigation().stop();
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            maid.setTask(TaskManager.getIdleTask());
            outcome(maid, "先停下棋局，开始按安排行动");
            tell(maid, player, "我先放下这局棋去做事；想继续下棋再说一声就行。");
        }
        if (maidReformBusy(maid)) return "女仆正在倒地或救援，本次行动没有执行";
        if (autonomous && !mind(maid).getBoolean("Autonomy")) return "自主活动已关闭，计划未执行";
        String action = string(plan, "action", 24);
        if (action.isBlank() || action.equals("keep")) {
            if (autonomous) scheduleNext(maid);
            if (autonomous && maid.getTask()==TaskManager.getIdleTask() && !MaidFieldwork.active(maid) && !MaidWorkshop.active(maid) && !MaidPlan.active(maid) && !MaidBuilder.active(maid)) {
                int idle = Math.min(100,mind(maid).getInt("IdleNoAction")+1);
                mind(maid).putInt("IdleNoAction", idle);
                if (MaidHunt.worthHunting(maid) && MaidHunt.candidate(maid, "animal") != null
                    && maid.getHealth() >= maid.getMaxHealth() * 0.5f) {
                    String huntResult = MaidHunt.start(maid, "", "animal", true);
                    outcome(maid, huntResult);
                    scheduleNext(maid);
                    return huntResult;
                }
                // Talking about work while doing nothing is the worst outcome, so the retry gets louder
                // and clearer instead of letting her repeat the same promise every planning interval.
                if (idle < 4) {
                    mind(maid).putLong("NextThink", maid.level().getGameTime() + 200);
                    return "本轮没有实际动作，马上重新判断（第 " + idle + " 次）";
                }
                noteStuck(maid);
                if (idle == 4) tell(maid, player, "我这两轮只在想事情，没有真的动手。给我一件具体的事，或者说「你自己看着办」，我就直接去做。");
                return "已经连续 " + idle + " 轮没有执行任何实际动作；她需要一件具体的行动或更明确的指示";
            }
            return "保持当前活动";
        }
        if (action.equals("inspect_storage")) {
            StringBuilder summary = new StringBuilder("附近可访问仓库：");
            var entries = MaidStorage.describe(maid);
            if (entries.isEmpty()) summary.append("没有已观察到的普通箱子或木桶");
            else for (var element : entries) {
                var object = element.getAsJsonObject();
                summary.append(" ").append(object.get("target_id").getAsString()).append(" ")
                    .append(object.get("ordinary_items") == null ? "需走近后查看" : object.get("ordinary_items").toString());
            }
            return summary.toString();
        }
        if (autonomous && (maid.isSleeping() || maid.isOrderedToSit() || maid.getTarget() != null || maid.getHealth() < maid.getMaxHealth() * 0.35f)) return "正在休息、战斗或受伤，未执行新计划";
        if (mind(maid).getBoolean("Recovering") && Set.of("task", "explore", "craft", "gather", "approach", "plan", "place", "smelt","withdraw","deposit","build_house").contains(action)) return "我还在修整，等生命恢复到七成再继续工作";
        if (!Set.of("follow", "stay", "explore", "task", "share", "craft", "equip", "gather", "approach", "plan", "place", "smelt","withdraw","deposit","build_house","look","scan_area","visit_landmark","hunt","dig_shaft").contains(action)) return "不支持这个行动，保持当前活动";
        if (Set.of("look", "scan_area").contains(action)) {
            // Looking, and turning around to look, costs nothing and changes nothing in the world, so
            // it is allowed even while she is resting: it is how she gathers information.
            String observation = action.equals("look") ? look(maid, plan) : scanArea(maid);
            mind(maid).putString("Outcome", observation);
            return observation;
        }
        if (action.equals("plan") && !mind(maid).getBoolean("Autonomy")) return "连续生存计划需要先开启自主活动";
        // A new explicit activity replaces a pending delivery; casual conversation does not.
        if (!autonomous) session.shareId = "";
        if (action.equals("share") && autonomous) return "自主活动不主动送出物资";
        mind(maid).putInt("IdleNoAction",0);
        // Any instruction, from the owner or from a new plan, replaces the light self-chosen work.
        mind(maid).putBoolean("IdleWork", false);
        mind(maid).remove("IdleWorkName");
        if (!autonomous || action.equals("plan")) MaidPlan.clear(maid,"收到新的行动安排");
        MaidWorkshop.cancel(maid,"收到新的行动安排");
        MaidFieldwork.cancel(maid, "收到新的行动安排，停止上一步");
        if (!autonomous) mind(maid).putLong("ManualUntil", maid.level().getGameTime() + PartnerConfig.planningInterval());
        scheduleNext(maid);
        mind(maid).putInt("PlanRevision", mind(maid).getInt("PlanRevision") + 1);
        mind(maid).putLong("AgnesDecisionUntil", maid.level().getGameTime() + PartnerConfig.planningInterval());
        String result;
        switch (action) {
            case "follow" -> { follow(maid); result = "已切换为跟随"; }
            case "stay" -> {
                home(maid);
                if (autonomous) { maid.setTask(TaskManager.getIdleTask()); result = "暂时休闲，之后继续自主安排"; }
                else { maid.setOrderedToSit(true); result = "已坐下休息；起身请叫我跟随"; }
            }
            case "explore" -> { result = MaidFieldwork.explore(maid, string(plan, "target_id", 160), autonomous); }
            case "gather" -> {
                String target = string(plan, "target_id", 160);
                result = target.isBlank() ? MaidFieldwork.startKind(maid, string(plan, "target_kind", 32), autonomous)
                    : MaidFieldwork.start(maid, target, action, autonomous);
            }
            case "approach" -> {
                String id = string(plan, "target_id", 160);
                // A remembered place needs no fresh observation, so it travels by memory instead.
                result = MaidLandmarks.isLandmark(id) ? MaidLandmarks.visit(maid, id, autonomous)
                    : MaidFieldwork.start(maid, id, action, autonomous);
            }
            case "visit_landmark" -> { result = MaidLandmarks.visit(maid, string(plan, "target_id", 160), autonomous); }
            case "hunt" -> result = "主动狩猎已关闭；敌对生物由女仆原生 Attack 任务处理，食物请使用采集、浆果、钓鱼或农务";
            case "dig_shaft" -> {
                int depth = 12;
                try { depth = plan.get("count").getAsInt(); } catch (RuntimeException ignored) {}
                result = MaidDig.start(maid, depth, autonomous);
                if (MaidDig.active(maid)) maid.setTask(TaskManager.getIdleTask());
            }
            case "task" -> {
                String id = string(plan, "task_id", 160);
                if (autonomous && MaidSurvival.blocked(maid, id)) return "这个任务刚刚受阻，等待物资变化或稍后再试";
                if (autonomous) {
                    ResourceLocation taskId = ResourceLocation.tryParse(id);
                    IMaidTask requested = taskId == null ? null : TaskManager.findTask(taskId).orElse(null);
                    if (requested instanceof IFarmTask farmTask && !MaidSurvival.farmWorkAvailable(maid, farmTask))
                        return "当前工作范围内没有可收获作物，也没有带种子的可播种耕地，未切换农田任务";
                }
                result = switchTask(maid, id);
            }
            case "craft" -> { result = MaidSurvival.craft(maid, string(plan, "recipe_id", 160)); }
            case "equip" -> { result = MaidSurvival.equip(maid, string(plan, "item_id", 160), autonomous); }
            case "place" -> { result = MaidWorkshop.place(maid,string(plan,"item_id",160)); }
            case "smelt" -> {
                result = MaidWorkshop.smelt(maid,string(plan,"item_id",160));
                if (MaidWorkshop.active(maid)) { maid.setTask(TaskManager.getIdleTask()); home(maid); }
            }
            case "plan" -> { result = MaidPlan.start(maid,plan); }
            case "withdraw","deposit" -> {
                int amount=1;try {amount=plan.get("amount").getAsInt();}catch(RuntimeException ignored) {}
                result=MaidStorage.transfer(maid,string(plan,"target_id",160),string(plan,"item_id",160),amount,action.equals("deposit"));
            }
            case "build_house" -> {result=MaidBuilder.start(maid,string(plan,"site_id",160),string(plan,"item_id",160),autonomous);}
            case "share" -> {
                String id = string(plan, "item_id", 160);
                int count = 1;
                try { count = Math.max(1, Math.min(64, plan.get("count").getAsInt())); } catch (RuntimeException ignored) {}
                if (maid.distanceToSqr(player) <= 16) result = share(maid, player, id, count);
                else {
                    follow(maid); session.shareId = id; session.shareCount = count; session.shareUntil = maid.level().getGameTime() + 400;
                    result = "正在走近你，尚未交付物品";
                }
            }
            default -> { return "不支持这个行动，保持当前活动"; }
        }
        String goal = string(plan, "goal", 240);
        if (!goal.isBlank()) mind(maid).putString("Goal", goal);
        return result;
    }

    static String switchTask(EntityMaid maid, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        IMaidTask task = key == null ? null : TaskManager.findTask(key).orElse(null);
        if (task == null || !availableTasks(maid).contains(task)) return "这个任务当前不可用，保持原任务";
        IMaidTask old = maid.getTask();
        try {
            maid.setTask(task);
            FunctionCallSwitchResult result = task.onFunctionCallSwitch(maid);
            if (result == FunctionCallSwitchResult.MISSING_REQUIRED_ITEM) {
                MaidSurvival.blockTask(maid, id, "缺少工具或材料");
                maid.setTask(old); return "缺少任务工具或材料，已恢复原任务";
            }
            home(maid);
            return "已选择工作：" + task.getName().getString() + (result == FunctionCallSwitchResult.PARTIAL_OK ? "；部分条件尚未满足" : "；是否开始工作还取决于地点、材料和作息");
        } catch (RuntimeException error) {
            MaidSurvival.blockTask(maid, id, "附属任务切换失败");
            maid.setTask(old); return "附属任务切换失败，已恢复原任务";
        }
    }

    /**
     * Turn her head and keep it there for a moment. Her first-person camera follows head rotation, so
     * this is how she gets to look at something before deciding about it, instead of only ever seeing
     * whichever direction she happened to be facing.
     */
    static String look(EntityMaid maid, JsonObject plan) {
        float yaw, pitch;
        BlockPos aim = targetPos(string(plan, "target_id", 160));
        if (aim != null) {
            Vec3 eye = maid.getEyePosition();
            Vec3 delta = Vec3.atCenterOf(aim).subtract(eye);
            double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
            yaw = (float)(Math.toDegrees(Math.atan2(-delta.x, delta.z)));
            pitch = (float)(-Math.toDegrees(Math.atan2(delta.y, Math.max(0.001, horizontal))));
        } else {
            try { yaw = plan.get("yaw").getAsFloat(); }
            catch (RuntimeException missing) { return "没有说明要往哪看：请给出 yaw/pitch 角度，或一个已观察到的 target_id"; }
            try { pitch = plan.get("pitch").getAsFloat(); } catch (RuntimeException missing) { pitch = 0; }
        }
        CompoundTag data = mind(maid);
        data.putFloat("LookYaw", yaw);
        data.putFloat("LookPitch", Math.max(-80f, Math.min(80f, pitch)));
        data.putLong("LookUntil", maid.level().getGameTime() + 80);
        data.putBoolean("Scanning", false);
        applyLook(maid);
        return "已转向 " + Math.round(yaw) + "°、俯仰 " + Math.round(pitch) + "°；下一轮观察会带上这个方向";
    }

    /** Begin a deliberate sweep so she builds up a picture of her surroundings. */
    static String scanArea(EntityMaid maid) {
        CompoundTag data = mind(maid);
        data.putBoolean("Scanning", true);
        data.putInt("ScanStep", 0);
        data.putLong("ScanLast", 0);
        data.putLong("LookUntil", 0);
        return "开始环视一圈，先看清四周再决定";
    }

    /** Applied every tick while a sweep is running or an explicit look is still held. */
    static void applyLook(EntityMaid maid) {
        CompoundTag data = mind(maid);
        long now = maid.level().getGameTime();
        if (data.getBoolean("Scanning")) {
            if (now < data.getLong("ScanLast") + 15) { applyRotation(maid, data); return; }
            int step = data.getInt("ScanStep");
            if (step >= 8) {
                data.putBoolean("Scanning", false);
                // One fresh frame as the sweep settles, so the next decision sees all of it. If the
                // client cannot deliver one, the game state and sight list still carry the decision.
                if (maid.getServer() != null && maid.getOwnerUUID() != null) {
                    ServerPlayer owner = maid.getServer().getPlayerList().getPlayer(maid.getOwnerUUID());
                    if (owner != null) MaidVision.request(owner, maid);
                }
                return;
            }
            data.putFloat("LookYaw", step * 45f);
            data.putFloat("LookPitch", 0f);
            data.putLong("ScanLast", now);
            applyRotation(maid, data);
            data.putInt("ScanStep", step + 1);
            return;
        }
        if (now <= data.getLong("LookUntil")) applyRotation(maid, data);
    }

    private static void applyRotation(EntityMaid maid, CompoundTag data) {
        maid.setYRot(data.getFloat("LookYaw"));
        maid.setYHeadRot(data.getFloat("LookYaw"));
        maid.setXRot(data.getFloat("LookPitch"));
    }

    /** Parse a short "x,y,z" or "kind:x:y:z" target into a position for head aiming. */
    private static BlockPos targetPos(String id) {
        if (id == null || id.isBlank()) return null;
        String[] parts = id.split(":");
        if (parts.length < 3) return null;
        try {
            int offset = parts.length - 3;
            return new BlockPos(Integer.parseInt(parts[offset].trim()), Integer.parseInt(parts[offset + 1].trim()), Integer.parseInt(parts[offset + 2].trim()));
        } catch (NumberFormatException invalid) { return null; }
    }

    private static void home(EntityMaid maid) {
        maid.setOrderedToSit(false);
        if (!maid.isHomeModeEnable()) maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
        maid.setHomeModeEnable(true);
    }

    private static void follow(EntityMaid maid) {
        maid.setOrderedToSit(false); maid.setHomeModeEnable(false); maid.clearRestriction();
    }

    static String share(EntityMaid maid, ServerPlayer player, String id, int requested) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) return "没有匹配到要分享的物品 ID";
        IItemHandler inventory = maid.getAvailableBackpackInv();
        int delivered = 0;
        for (int i = 0; i < inventory.getSlots() && delivered < requested; i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(key)) continue;
            ItemStack removed = inventory.extractItem(i, Math.min(64, requested) - delivered, false);
            int moved = removed.getCount();
            if (moved == 0) continue;
            player.getInventory().add(removed);
            if (!removed.isEmpty()) player.drop(removed, false);
            delivered += moved;
        }
        return delivered == 0 ? "背包里没有可交付的这种物品（不会取下已装备物品）" : "已交给你 " + id + " ×" + delivered + "；背包放不下的物品落在你脚边";
    }

    static void outcome(EntityMaid maid, String result) {
        long now = maid.level().getGameTime();
        MaidPersonality.result(MaidPersonality.data(mind(maid), maid.getUUID(), now), result, now);
        mind(maid).putString("Outcome", limit(result, 300));
        ListTag steps = mind(maid).getList("RecentSteps", 8);
        steps.add(StringTag.valueOf(limit(result, 300)));
        while (steps.size() > 8) steps.remove(0);
        mind(maid).put("RecentSteps", steps);
        // Keep a compact, structured action history for future planning. Unlike RecentSteps, this
        // survives many planning rounds and distinguishes completed work from a blocked attempt.
        ListTag history = mind(maid).getList("ActionMemory", 10);
        CompoundTag episode = new CompoundTag();
        episode.putLong("tick", now);
        episode.putString("result", limit(result, 240));
        episode.putBoolean("success", !blockedProgress(result));
        history.add(episode);
        while (history.size() > 32) history.remove(0);
        mind(maid).put("ActionMemory", history);
    }

    /** Never present a model's promised physical action as evidence that the executor did it. */
    static String displayReply(JsonObject plan, String result, boolean autonomous) {
        String action = string(plan, "action", 24);
        if (!result.equals("保持当前活动")) return "行动反馈：" + result;
        if (autonomous) return "本轮继续当前活动，没有启动新的采集或合成；两分钟后再判断。";
        String say = string(plan, "say", 1000);
        if (say.isBlank()) say = "我听到了。";
        if ((action.isBlank() || action.equals("keep")) && say.matches("(?s).*(我去|我会|马上|这就|已经|正在|现在就|开始).*(采集|砍|挖|合成|建造|收集|制作|种田|钓鱼|工作台|交给|装备).*$"))
            return say + "\n行动核对：本轮模型只选择了保持当前活动，没有启动上述新行动。";
        return say;
    }
    private static void remember(EntityMaid maid, String memory) {
        ListTag history = mind(maid).getList("Memory", 8);
        history.add(StringTag.valueOf(limit(memory, 600)));
        while (history.size() > 10) history.remove(0);
        mind(maid).put("Memory", history);
    }
    private static String string(JsonObject object, String key, int max) {
        try { return object.has(key) ? limit(object.get(key).getAsString(), max) : ""; } catch (RuntimeException e) { return ""; }
    }
    private static String limit(String value, int max) { return value.length() > max ? value.substring(0, max) : value; }
    private static void tell(EntityMaid maid, ServerPlayer player, String text) { player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · Agnes] " + text)); }
    private static void feedback(EntityMaid maid, ServerPlayer player, String text, boolean autonomous) {
        Component message = Component.literal("[" + maid.getName().getString() + " · Agnes] " + text);
        if (autonomous) player.displayClientMessage(message, true); else player.sendSystemMessage(message);
    }
}
