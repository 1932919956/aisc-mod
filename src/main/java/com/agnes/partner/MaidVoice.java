package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Her voice.
 *
 * Two jobs live here and nothing else does:
 *
 * 1. Decide, locally, whether she has something to say right now and what it is. This does not need
 *    the network, so she keeps talking when the API is slow, refused or unconfigured. The model may
 *    still contribute a line, but it is no longer the only thing that can make her speak -- that was
 *    the actual reason she felt mute.
 * 2. Keep every line inside the same honesty rule as the rest of the mod: a spoken line is never
 *    evidence of a physical action. Lines about work only describe what the executor already did.
 *
 * All lines are plain Chinese strings with no format placeholders, because they are built at random
 * and not always formatted.
 */
final class MaidVoice {
    private MaidVoice() {}

    private static final long EVENT_COOLDOWN = 6000;       // ~5 minutes per topic
    private static final long URGENT_COOLDOWN = 1800;      // ~90 seconds for repeated warnings
    private static final long IDLE_COOLDOWN_HIGH = 2400;   // ~2 minutes when the owner is close by
    private static final long IDLE_COOLDOWN_LOW = 4800;    // ~4 minutes while working further away
    private static final double NEAR_DISTANCE = 10.0;
    private static final Map<UUID, Long> LAST_SEEN = new HashMap<>();

    /** A moment worth a sentence, plus the reason the executor must always be able to explain. */
    private record Event(String id, String why) {}

    static void clear() { LAST_SEEN.clear(); }

    /**
     * Everything she can legitimately know about this moment. Collected once per attempt so every
     * line and the model prompt see the same facts.
     */
    static final class Context {
        final EntityMaid maid;
        final ServerPlayer owner;
        final CompoundTag mind;
        final CompoundTag personality;
        final long now;
        final long dayTime;
        final long day;
        final float health;
        final float maxHealth;
        final float ownerHealth;
        final boolean danger;
        final boolean raining;
        final boolean thundering;
        final boolean resting;
        final boolean working;
        final boolean nearby;
        final long lastOwnerChat;
        final long reunionGap;
        final int food;
        final int affection;
        final String mood;
        final String petName;
        final String goalName;

        Context(EntityMaid maid, ServerPlayer owner, long now, long reunionGap) {
            this.maid = maid; this.owner = owner; this.now = now; this.reunionGap = reunionGap;
            this.mind = MaidBridge.mind(maid);
            this.personality = MaidPersonality.data(mind, maid.getUUID(), now);
            this.dayTime = maid.level().getDayTime() % 24000L;
            this.day = Math.floorDiv(maid.level().getDayTime(), 24000L);
            this.health = maid.getHealth(); this.maxHealth = maid.getMaxHealth();
            this.ownerHealth = owner.getHealth();
            this.danger = maid.getTarget() != null
                || !maid.level().getEntitiesOfClass(Monster.class, maid.getBoundingBox().inflate(12)).isEmpty();
            boolean wet = false, storm = false;
            try {
                BlockPos pos = maid.blockPosition();
                wet = maid.level().isRainingAt(pos);
                storm = maid.level().isThundering() && maid.level().canSeeSky(pos);
            } catch (RuntimeException ignored) { /* weather is cosmetic: never let it break a tick */ }
            this.raining = wet; this.thundering = storm;
            this.resting = maid.isSleeping() || maid.isOrderedToSit();
            this.working = MaidFieldwork.active(maid) || MaidPlan.active(maid) || MaidWorkshop.active(maid)
                || MaidBuilder.active(maid) || !maid.getTask().getUid().equals(
                    com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager.getIdleTask().getUid());
            this.nearby = maid.distanceToSqr(owner) <= 128 * 128;
            this.lastOwnerChat = personality.contains("LastOwnerChat") ? personality.getLong("LastOwnerChat") : Long.MIN_VALUE;
            this.food = MaidSurvival.foodCount(maid);
            this.affection = personality.getInt("Affection");
            this.mood = personality.getString("Mood");
            this.petName = personality.getString("CallOwner");
            this.goalName = MaidPersonality.goalName(personality.getString("DailyGoal"));
        }

        /** Nothing social happens while she is sneaking, fighting, recovering or asleep. */
        boolean socialSafe() {
            return !maid.isSleeping() && !maid.isOrderedToSit() && maid.getTarget() == null
                && !maid.isCrouching() && health >= maxHealth * 0.7f && !mind.getBoolean("Recovering")
                && owner.isAlive() && maid.level() == owner.level()
                && maid.distanceToSqr(owner) <= MaidBridge.ACTIVITY_RANGE * MaidBridge.ACTIVITY_RANGE;
        }

        /** Only events whose reasons come from real game data may be spoken about. */
        Event event() {
            long time = dayTime;
            String goal = personality.getString("DailyGoal");
            if (health < maxHealth * 0.45f && elapsed("low_health") >= URGENT_COOLDOWN)
                return new Event("low_health", "生命值低于 45%");
            if (danger && elapsed("danger") >= URGENT_COOLDOWN)
                return new Event("danger", "附近有敌对生物或正在战斗");
            if (reunionGap >= 48000 || reunionGap < 0)
                return new Event("returned", "主人离线较久后回到游戏");
            if (affectionTierCrossed()) return new Event("affection", "亲密度刚跨过一档");
            if (food <= 0 && elapsed("hungry") >= EVENT_COOLDOWN)
                return new Event("hungry", "背包里没有食物");
            if (time >= 13000 && time < 23000 && lastTopicDay("nightfall") != day)
                return new Event("nightfall", "游戏进入夜晚");
            if ((time >= 23000 || time < 1000) && lastTopicDay("dawn") != day)
                return new Event("dawn", "游戏进入新的一天");
            if (thundering && elapsed("storm") >= EVENT_COOLDOWN) return new Event("storm", "正在雷雨");
            if (raining && elapsed("rain") >= EVENT_COOLDOWN) return new Event("rain", "正在下雨");
            if (health < maxHealth * 0.7f && elapsed("hurt") >= EVENT_COOLDOWN)
                return new Event("hurt", "生命值低于 70%");
            if (ownerHealth < owner.getMaxHealth() * 0.5f && elapsed("owner_hurt") >= EVENT_COOLDOWN)
                return new Event("owner_hurt", "主人生命值偏低");
            if (MaidBridge.maidHasShelter(maid) && elapsed("shelter") >= EVENT_COOLDOWN)
                return new Event("shelter", "已经有了自己盖的住处");
            if (elapsed("idle") >= (maid.distanceToSqr(owner) <= NEAR_DISTANCE * NEAR_DISTANCE
                    ? IDLE_COOLDOWN_HIGH : IDLE_COOLDOWN_LOW))
                return new Event("idle", goal.isBlank() ? "一段时间没有新的安排" : "当前目标：" + MaidPersonality.goalName(goal));
            return null;
        }

        long elapsed(String topic) {
            if (!mind.contains("Voice." + topic)) return Long.MAX_VALUE / 4;
            return now - mind.getLong("Voice." + topic);
        }
        /** The game day a one-per-day topic was last spoken, or a value that never equals a real day. */
        private long lastTopicDay(String topic) {
            return mind.contains("Voice." + topic) ? Math.floorDiv(mind.getLong("Voice." + topic), 24000L) : Long.MIN_VALUE / 8;
        }
        private boolean affectionTierCrossed() {
            if (elapsed("affection") < EVENT_COOLDOWN) return false;
            int tier = MaidPersonality.affectionTier(affection);
            return tier > 0 && mind.getInt("Voice.AffectionTier") < tier;
        }
        String ownerName() { return petName.isBlank() ? "主人" : petName; }
    }

    /** True when the local driver may produce a line at all; also reported to the model. */
    static boolean maySpeak(EntityMaid maid, ServerPlayer owner, long now) {
        CompoundTag p = MaidPersonality.data(MaidBridge.mind(maid), maid.getUUID(), now);
        return MaidPersonality.mayChat(p, now, basicSafe(maid, owner));
    }

    /** The cheap half of the safety rules, usable before a full Context is built. */
    private static boolean basicSafe(EntityMaid maid, ServerPlayer owner) {
        return !maid.isSleeping() && !maid.isOrderedToSit() && maid.getTarget() == null
            && !maid.isCrouching() && !MaidBridge.mind(maid).getBoolean("Recovering")
            && maid.isAlive() && owner.isAlive() && maid.level() == owner.level()
            && maid.getHealth() >= maid.getMaxHealth() * 0.7f
            && maid.distanceToSqr(owner) <= MaidBridge.ACTIVITY_RANGE * MaidBridge.ACTIVITY_RANGE;
    }

    /**
     * Called once per second for every bound maid. Returns true when she actually said something.
     * Silence is always a valid answer; she is allowed to just keep working.
     */
    static boolean tick(EntityMaid maid, ServerPlayer owner) {
        long now = maid.level().getGameTime();
        UUID id = maid.getUUID();
        Long seen = LAST_SEEN.put(id, now);
        long reunionGap = seen == null ? -1L : now - seen;
        if (seen != null && reunionGap < 100) return false; // same session, nothing to greet

        CompoundTag personality = MaidPersonality.data(MaidBridge.mind(maid), id, now);
        Context context = new Context(maid, owner, now, reunionGap);
        boolean urgent = context.reunionGap >= 48000 || context.reunionGap < 0
            || context.health < context.maxHealth * 0.45f || context.danger;
        if (!MaidPersonality.mayChat(personality, now, context.socialSafe()) && !urgent) return false;
        if (!urgent && context.health < context.maxHealth * 0.7f) return false;
        Event event = context.event();
        if (event == null) return false;
        String line = line(event, context);
        if (line.isBlank()) return false;
        String accepted = MaidPersonality.acceptLine(personality, line, now, urgent);
        if (accepted.isBlank()) return false;
        context.mind.putLong("Voice." + event.id(), now);
        context.mind.putLong("Voice.last", now);
        if (event.id().equals("affection")) context.mind.putInt("Voice.AffectionTier", MaidPersonality.affectionTier(context.affection));
        return MaidBubble.speak(maid, accepted);
    }

    /**
     * A short, in-character reaction to what the executor actually just reported. This is what makes
     * her feel present in the world instead of a chatbot answering a prompt.
     */
    static boolean react(EntityMaid maid, String result) {
        if (result == null || result.isBlank()) return false;
        long now = maid.level().getGameTime();
        CompoundTag mind = MaidBridge.mind(maid);
        CompoundTag p = MaidPersonality.data(mind, maid.getUUID(), now);
        if (!p.getBoolean("Chatter")) return false;
        if (mind.contains("Voice.reacted") && now - mind.getLong("Voice.reacted") < 2400) return false;
        String key;
        List<String> pool;
        if (result.startsWith("未") || result.contains("失败") || result.contains("无法到达")
            || result.contains("超时") || result.contains("缺少") || result.contains("没有找到")
            || result.contains("不支持") || result.contains("不可用")) {
            key = "react_fail"; pool = FAIL_REACTIONS;
        } else if (result.contains("受伤") || result.contains("修整") || result.contains("撤退")) {
            key = "react_hurt"; pool = HURT_REACTIONS;
        } else if (result.startsWith("实际采集完成") || result.startsWith("已实际合成")
            || result.startsWith("实际烧炼完成") || result.startsWith("小屋建造完成")
            || result.startsWith("已到达") || result.startsWith("已交给你")) {
            key = "react_done"; pool = DONE_REACTIONS;
        } else {
            return false;
        }
        String line = pick(maid, pool);
        String accepted = MaidPersonality.acceptLine(p, line, now, false);
        if (accepted.isBlank()) return false;
        mind.putLong("Voice.reacted", now);
        mind.putString("Voice.react." + key, accepted);
        return MaidBubble.speak(maid, accepted);
    }

    /**
     * A greeting for the first moment after login, so coming back feels noticed. It goes through the
     * timeout-guarded bubble so a slow join or a dimension change cannot stack three goodbyes.
     */
    static boolean greeting(EntityMaid maid, ServerPlayer owner, long offlineTicks) {
        Context context = new Context(maid, owner, maid.level().getGameTime(), offlineTicks);
        String prefix = context.petName.isBlank() ? "" : context.petName + "，";
        String line = offlineTicks >= 0 && offlineTicks < 24000
            ? prefix + "欢迎回来。"
            : prefix + (context.dayTime >= 13000 ? "你回来了，天已经黑了。" : "你回来了，我一直在这里。");
        if (!context.goalName.isBlank()) line = line + "我在做：" + context.goalName;
        if (!MaidBubble.speak(maid, line)) return false;
        MaidBridge.mind(maid).putLong("Voice.last", maid.level().getGameTime());
        return true;
    }

    /** Her own summary of how she feels, used when the model cannot answer. */
    static String fallbackReply(Context context) {
        String name = context.ownerName();
        return switch (context.mood) {
            case "紧张" -> name + "，我现在有点紧张，先把自己护好再安排别的事。";
            case "有点受挫" -> name + "，刚才那件事不太顺，我想换个办法再试。";
            case "想换点事情做" -> name + "，手头这件事有点做腻了，换一件怎么样？";
            case "开心" -> name + "，我现在挺好的，手上有事做，也不缺什么。";
            case "平静" -> name + "，我在歇一会儿，你说吧。";
            default -> name + "，我在听。现在想做的还是：" + (context.goalName.isBlank() ? "看看周围有什么可做的" : context.goalName);
        };
    }

    private static String line(Event event, Context c) {
        String name = c.ownerName();
        String prefix = c.petName.isBlank() ? "" : c.petName + "，";
        boolean close = c.affection >= 32;
        return switch (event.id()) {
            case "low_health" -> pick(c.maid, List.of(
                name + "，我受伤了，先退到你身边。",
                name + "，我血量不太够，先躲一下再干活。"));
            case "danger" -> pick(c.maid, List.of(
                "有东西过来了，我盯着它。",
                "附近不太安全，你别走太远。"));
            case "returned" -> pick(c.maid, List.of(
                prefix + "你回来了，我把周围看了一圈，没什么危险。",
                prefix + "你回来了。我一直在这个世界等你回来。",
                prefix + "你回来了，欢迎回来。")); 
            case "affection" -> pick(c.maid, close
                ? List.of("和你一起走这么久，我已经很习惯你在旁边了。",
                    "我好像越来越懂你的习惯了，这种感觉挺好的。")
                : List.of("跟你一起待了一会儿，感觉没那么陌生了。",
                    "我开始记得你喜欢什么了，慢慢来吧。"));
            case "hungry" -> pick(c.maid, List.of(
                "我背包里没有吃的了，得去找点食物。",
                name + "，我肚子空了，先去找吃的。"));
            case "nightfall" -> pick(c.maid, List.of(
                "天黑了，晚上容易出事，我打算先把火把和住处准备好。",
                "入夜了。我看看附近安不安全，再决定要不要收工。"));
            case "dawn" -> pick(c.maid, List.of(
                "天亮了，今天想做的是：" + c.goalName + "。",
                "新的一天。我把工具检查一遍，就去干活。"));
            case "storm" -> pick(c.maid, List.of(
                "打雷了。雷雨天在外面太危险，我先躲一会儿。",
                "这雷声有点吓人，我们离高点的地方远一些。"));
            case "rain" -> pick(c.maid, List.of(
                "下雨了，视线不好，我慢一点。",
                "下雨了。趁这个机会整理一下背包也不错。"));
            case "hurt" -> pick(c.maid, List.of(
                "我状态不是很好，先养一下再继续。",
                "受了点伤。你放心，我会先把自己弄好。"));
            case "owner_hurt" -> pick(c.maid, List.of(
                name + "，你伤得不轻，先吃点东西或者退开一点。",
                prefix + "你血不多了，我看着你，别硬撑。"));
            case "shelter" -> pick(c.maid, List.of(
                "小屋总算盖好了，今晚不用在外面过夜了。",
                "有屋顶的感觉真好，我可以在这里整理东西。"));
            default -> fallbackReply(c);
        };
    }

    private static String pick(EntityMaid maid, List<String> pool) {
        return pool.get(maid.getRandom().nextInt(pool.size()));
    }

    private static final List<String> DONE_REACTIONS = List.of(
        "搞定一件。",
        "嗯，这步顺利。",
        "做完了，心里踏实一点。",
        "又攒下一点东西，继续。",
        "比想象中顺利。");

    private static final List<String> FAIL_REACTIONS = List.of(
        "这次没成，我换个思路。",
        "被挡住了，我记一下，等会儿再试。",
        "还差条件，先把缺的补上。",
        "没走到，路不好走。",
        "不成也没关系，我先做别的。");

    private static final List<String> HURT_REACTIONS = List.of(
        "有点疼，我先退一步。",
        "先保命，活明天再干。",
        "我得去你旁边待一会儿。");
}
