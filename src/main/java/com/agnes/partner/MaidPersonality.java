package com.agnes.partner;

import com.google.gson.*;
import net.minecraft.nbt.*;
import java.util.*;

/**
 * Persistent, bounded character state. No world access or model-authored completion flags.
 *
 * This is what makes her the same person across sessions: mood, mood reason, activity preferences,
 * a small number of real memories, an affection level and the owner's pet name. Every field is
 * written from an actual observation or an actual owner sentence, never invented by the model.
 */
final class MaidPersonality {
    static final String KEY = "Personality";
    /** Minimum game time between two local spontaneous lines that are not urgent events. */
    static final long CHAT_COOLDOWN = 1500;   // ~75 seconds
    /** Quiet time after the owner spoke, so she does not talk over the conversation she started. */
    static final long OWNER_QUIET_TICKS = 900;
    /** Affection tiers: 0 stranger, 1 familiar, 2 friend, 3 trusted, 4 close. */
    static final int[] AFFECTION_TIERS = {0, 12, 32, 58, 82};
    private static final List<String> INTERESTS = List.of("explore", "gather", "craft", "build", "farm");
    private static final List<String> GOALS = List.of("food", "wood", "stone", "iron", "shelter", "explore");
    record Observation(long tick, long dayTime, float health, float maxHealth, boolean danger,
                       boolean resting, int food, int wood, int stone, int iron, boolean shelter) {}

    static CompoundTag data(CompoundTag mind, UUID id, long now) {
        if (!mind.contains(KEY, Tag.TAG_COMPOUND)) {
            CompoundTag p = new CompoundTag();
            Random random = new Random(id.getMostSignificantBits() ^ id.getLeastSignificantBits());
            CompoundTag likes = new CompoundTag();
            for (String interest : INTERESTS) likes.putInt(interest, 35 + random.nextInt(31));
            p.put("Likes", likes); p.putInt("Spirit", 55); p.putInt("Boredom", 0);
            p.putBoolean("Chatter", true); p.putLong("NextChat", now + 1200);
            p.putLong("LastDecay", now); p.putString("Mood", "好奇");
            p.putString("Style", "好奇、有主见、偶尔俏皮，关心同伴；用自然短句交流，不刻意模仿某个人。");
            p.putInt("Affection", 0); p.putString("CallOwner", "");
            p.putLong("FirstMet", now); p.putInt("TogetherTicks", 0); p.putInt("TogetherDays", 0);
            p.putLong("MoodSince", now); p.putString("LastMood", "好奇");
            mind.put(KEY, p);
        }
        return mind.getCompound(KEY);
    }

    /** Older saves predate the affection fields; upgrade them in place, never reset a real personality. */
    static void migrate(CompoundTag p, long now) {
        if (!p.contains("Affection")) p.putInt("Affection", 0);
        if (!p.contains("CallOwner")) p.putString("CallOwner", "");
        if (!p.contains("FirstMet")) p.putLong("FirstMet", now);
        if (!p.contains("TogetherTicks")) p.putInt("TogetherTicks", 0);
        if (!p.contains("TogetherDays")) p.putInt("TogetherDays", 0);
        if (!p.contains("MoodSince")) p.putLong("MoodSince", now);
        if (!p.contains("LastMood")) p.putString("LastMood", p.getString("Mood"));
        if (!p.contains("Chatter")) p.putBoolean("Chatter", true);
        if (!p.contains("RecentChatter")) p.put("RecentChatter", new ListTag());
    }

    static String text(JsonObject object, String key, int limit) {
        try { return clean(object.get(key).getAsString(), limit); }
        catch (RuntimeException invalid) { return ""; }
    }
    static String clean(String text, int max) {
        String value = text.replaceAll("[\\p{Cntrl}§]", " ").strip();
        return value.length() > max ? value.substring(0, max) : value;
    }
    static boolean sensitive(String text) {
        return text.matches("(?is).*(sk-[a-z0-9_-]{8,}|bearer\\s+\\S+|api.?key|密钥|密匙|密码|token\\s*[:=]).*");
    }
    private static void append(CompoundTag p, String key, String value, int max) {
        ListTag list = p.getList(key, Tag.TAG_STRING);
        if (value.isBlank()) return;
        for (int i = list.size() - 1; i >= 0; i--) if (list.getString(i).equals(value)) list.remove(i);
        list.add(StringTag.valueOf(value));
        while (list.size() > max) list.remove(0);
        p.put(key, list);
    }
    static boolean remember(CompoundTag p, String statement) {
        if (statement.isBlank() || sensitive(statement)) return false;
        append(p, "OwnerFacts", clean(statement, 180), 24); return true;
    }

    /** A short note about what happened to her own plans, e.g. a place she no longer recognises. */
    static void appendNote(CompoundTag p, String note) {
        if (note == null || note.isBlank() || sensitive(note)) return;
        append(p, "Episodes", clean(note, 180), 16);
    }

    /**
     * A short pet name the owner explicitly asked for ("以后叫我小少爷"). Only an exact owner
     * sentence is accepted; the model cannot invent one.
     */
    static String petName(String ownerInput) {
        if (ownerInput == null || sensitive(ownerInput)) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("(?:以后|请|就)?叫(?:我|我作|我做)\\s*([^\\s，。,.！!？?；;、]{1,8})").matcher(ownerInput);
        if (!m.find()) return "";
        String name = clean(m.group(1), 8);
        return name.isBlank() || Set.of("我", "你", "他", "她", "什么", "啥").contains(name) ? "" : name;
    }

    /** Affection only moves on real interactions: the owner spoke, work succeeded, work failed. */
    static void addAffection(CompoundTag p, int delta) {
        int before = p.getInt("Affection");
        int after = Math.max(0, Math.min(100, before + delta));
        p.putInt("Affection", after);
        if (affectionTier(after) > affectionTier(before))
            append(p, "Episodes", "关系更近了一些：" + affectionName(after), 16);
    }
    static int affectionTier(int affection) {
        int tier = 0;
        for (int i = 0; i < AFFECTION_TIERS.length; i++) if (affection >= AFFECTION_TIERS[i]) tier = i;
        return tier;
    }
    static String affectionName(int affection) {
        return switch (affectionTier(affection)) {
            case 0 -> "刚认识"; case 1 -> "熟悉"; case 2 -> "朋友"; case 3 -> "信任"; default -> "亲近";
        };
    }

    static void ownerSpoke(CompoundTag p, long now) {
        boolean first = !p.contains("LastOwnerChat");
        p.putLong("LastOwnerChat", now);
        if (first || !p.contains("LastAffectionTick") || now - p.getLong("LastAffectionTick") >= 2400) {
            p.putLong("LastAffectionTick", now);
            addAffection(p, 1);
        }
    }

    /** Exact local controls work even when the API is unavailable. Empty means ordinary chat. */
    static String localChat(CompoundTag p, String input, long now) {
        ownerSpoke(p, now);
        String s = input.strip();
        if (Set.of("先别主动说话", "关闭主动聊天", "安静一点", "少说点", "别说话").contains(s)) {
            p.putBoolean("Chatter", false); return "好，我先不主动插话；你找我聊天时我仍会回应。想让我开口就说「可以主动聊天了」。";
        }
        if (Set.of("可以主动聊天了", "开启主动聊天", "多陪我聊聊", "多说点话").contains(s)) {
            p.putBoolean("Chatter", true); p.putLong("NextChat", now + 600);
            return "好，我会把看到的事、心里的想法说给你听，也会继续做自己的事。";
        }
        for (String prefix : List.of("记住：", "记住:", "请记住：", "请记住:")) {
            if (s.startsWith(prefix)) return remember(p, s.substring(prefix.length()))
                ? "我记住了。以后聊天和安排活动时会参考，不过当前指令和安全条件优先。"
                : "这条没有保存；请提供普通游戏偏好，不要把密钥或密码当作记忆。";
        }
        String pet = petName(s);
        if (!pet.isBlank()) {
            p.putString("CallOwner", pet);
            return "那我就叫你「" + pet + "」了，听起来很顺口。";
        }
        return "";
    }

    static String phase(long dayTime) {
        long time = Math.floorMod(dayTime, 24000);
        return time < 2000 ? "清晨：检查食物、工具和今天的小目标"
            : time < 10000 ? "白天：优先推进采集、制作和建设"
            : time < 13000 ? "傍晚：收尾、补给，考虑安全住处"
            : "夜间：注意安全，适合整理、交流或休息；尊重原生作息";
    }
    static String goalName(String id) {
        return switch (id) {
            case "food" -> "备好 12 份食物"; case "wood" -> "备好 8 个原木";
            case "stone" -> "备好 16 个圆石或深板岩圆石"; case "iron" -> "备好 3 个铁锭";
            case "shelter" -> "完成现有基础小屋"; case "explore" -> "实际到达 2 个新的观察点";
            default -> "待观察后选择";
        };
    }
    private static boolean satisfied(CompoundTag p, String goal, Observation o) {
        return switch (goal) {
            case "food" -> o.food >= 12; case "wood" -> o.wood >= 8; case "stone" -> o.stone >= 16;
            case "iron" -> o.iron >= 3; case "shelter" -> o.shelter;
            case "explore" -> p.getInt("Explored") - p.getInt("ExploreBaseline") >= 2;
            default -> false;
        };
    }
    static void observe(CompoundTag p, Observation o) {
        migrate(p, o.tick);
        long day = Math.floorDiv(o.dayTime, 24000);
        if (!p.contains("Day") || p.getLong("Day") != day) {
            if (p.contains("Day") && !p.getString("DailyGoal").isBlank())
                append(p, "Episodes", "第 " + p.getLong("Day") + " 天目标：" + goalName(p.getString("DailyGoal"))
                    + (p.getBoolean("GoalDone") ? "（已验证完成）" : "（未确认完成，留待以后）"), 16);
            p.putLong("Day", day); p.remove("DailyGoal"); p.putBoolean("GoalDone", false);
            p.putInt("DoneToday", 0); p.putInt("GoalFailures", 0); p.put("CompletedToday", new ListTag());
        }
        long elapsed = o.tick - p.getLong("LastDecay");
        if (elapsed >= 1200 || elapsed < 0) {
            int spirit = p.getInt("Spirit"), n = (int)Math.min(10, Math.max(1, elapsed / 1200));
            p.putInt("Spirit", spirit + Integer.signum(55 - spirit) * Math.min(n, Math.abs(55 - spirit)));
            p.putLong("LastDecay", o.tick);
            // Time actually spent together is affection evidence too, but only a slow trickle.
            int together = p.getInt("TogetherTicks") + (int)Math.min(24000, Math.max(0, elapsed));
            p.putInt("TogetherTicks", together);
            if (together / 24000 > p.getInt("TogetherDays")) {
                p.putInt("TogetherDays", together / 24000);
                addAffection(p, 1);
            }
        }
        boolean hurt = o.health < o.maxHealth * 0.4f;
        String mood = hurt || o.danger ? "紧张" : o.resting ? "平静"
            : p.getInt("Spirit") < 40 ? "有点受挫" : p.getInt("Boredom") >= 4 ? "想换点事情做"
            : p.getInt("Spirit") >= 65 ? "开心" : "好奇";
        if (!mood.equals(p.getString("LastMood"))) {
            p.putLong("MoodSince", o.tick); p.putString("LastMood", mood);
        }
        p.putString("Mood", mood);
        p.putString("MoodReason", hurt ? "生命值偏低，先保护自己" : o.danger ? "附近有威胁"
            : o.resting ? "正在休息" : "近期行动结果和空闲情况");
        String goal = p.getString("DailyGoal");
        if (!p.getBoolean("GoalDone") && !goal.isBlank() && satisfied(p, goal, o)) {
            p.putBoolean("GoalDone", true); p.putInt("DoneToday", p.getInt("DoneToday") + 1);
            append(p, "CompletedToday", goal, 3);
            append(p, "Episodes", "第 " + day + " 天：实际状态确认完成「" + goalName(goal) + "」", 16);
            p.putInt("Spirit", Math.min(85, p.getInt("Spirit") + 8));
            addAffection(p, 2);
        }
    }

    static void chooseGoal(CompoundTag p, String id, Observation o) {
        if (!GOALS.contains(id) || p.getInt("DoneToday") >= 3) return;
        if (p.getList("CompletedToday", Tag.TAG_STRING).contains(StringTag.valueOf(id))) return;
        if (!id.equals("explore") && satisfied(p, id, o)) return; // Don't manufacture already-completed goals.
        String current = p.getString("DailyGoal");
        if (id.equals(current)) return;
        if (!current.isBlank() && !p.getBoolean("GoalDone") && p.getInt("GoalFailures") < 3) return;
        if (!current.isBlank() && !p.getBoolean("GoalDone"))
            append(p, "Episodes", "目标「" + goalName(current) + "」多次受阻，先搁置", 16);
        p.putString("DailyGoal", id); p.putBoolean("GoalDone", false); p.putInt("GoalFailures", 0);
        p.putInt("ExploreBaseline", p.getInt("Explored"));
    }
    static void ensureGoal(CompoundTag p, Observation o) {
        if (p.getBoolean("GoalDone") || p.getString("DailyGoal").isBlank()) {
            String id = o.food < 12 ? "food" : o.wood < 8 ? "wood" : o.stone < 16 ? "stone"
                : o.iron < 3 ? "iron" : !o.shelter ? "shelter" : "explore";
            p.putString("DailyGoal", id); p.putBoolean("GoalDone", false); p.putInt("GoalFailures", 0);
            p.putInt("ExploreBaseline", p.getInt("Explored"));
        }
    }

    /** Called only with executor feedback, never with model say/goal text. */
    static void result(CompoundTag p, String result, long now) {
        if (result.isBlank() || result.equals(p.getString("LastResult"))) return;
        p.putString("LastResult", clean(result, 240));
        String interest = result.startsWith("实际采集完成") ? "gather"
            : result.startsWith("已实际合成") || result.startsWith("实际烧炼完成") ? "craft"
            : result.startsWith("已到达 scout:") ? "explore" : "";
        boolean success = !interest.isEmpty() || result.startsWith("救援完成") || result.startsWith("小屋建造完成");
        boolean failure = result.startsWith("未") || result.contains("失败") || result.contains("没有找到")
            || result.contains("超时") || result.contains("无法到达") || result.contains("缺少");
        // Transport trouble isn't a game event and must not make her dislike an activity.
        if (result.contains("API") || result.contains("网络") || result.contains("回复格式")) return;
        if (!success && !failure) return;
        if (success && interest.equals("explore")) {
            String target = result.split("，", 2)[0];
            if (!p.getList("Visited", Tag.TAG_STRING).contains(StringTag.valueOf(target))) {
                append(p, "Visited", target, 32); p.putInt("Explored", p.getInt("Explored") + 1);
            }
        }
        if (p.contains("LastReaction") && now - p.getLong("LastReaction") < 1200) return;
        p.putLong("LastReaction", now);
        append(p, "Episodes", "第 " + p.getLong("Day") + " 天行动记录：" + clean(result, 180), 16);
        p.putInt("Spirit", Math.max(25, Math.min(85, p.getInt("Spirit") + (success ? 4 : -3))));
        if (success) {
            p.putInt("Boredom", 0);
            addAffection(p, 1);
            if (!interest.isEmpty()) {
                CompoundTag likes = p.getCompound("Likes"); likes.putInt(interest, Math.min(85, likes.getInt(interest) + 1));
            }
        } else if (!p.getBoolean("GoalDone") && !p.getString("DailyGoal").isBlank())
            p.putInt("GoalFailures", Math.min(10, p.getInt("GoalFailures") + 1));
    }
    static void idle(CompoundTag p, boolean genuinelyIdle) {
        p.putInt("Boredom", genuinelyIdle ? Math.min(10, p.getInt("Boredom") + 1) : 0);
    }

    /**
     * May she open her mouth right now? This is the single place that answers that question for both
     * the local voice driver and the model prompt, so the two can never disagree.
     */
    static boolean mayChat(CompoundTag p, long now, boolean safeAndNearby) {
        if (!safeAndNearby || !p.getBoolean("Chatter")) return false;
        if (p.contains("LastOwnerChat") && now - p.getLong("LastOwnerChat") < OWNER_QUIET_TICKS) return false;
        return now >= p.getLong("NextChat");
    }

    /**
     * Accept a spontaneous line for display. Urgent lines (health, danger, reunion) skip the
     * ordinary cooldown; everything else waits, which is what keeps her from becoming noise.
     */
    static String acceptLine(CompoundTag p, String candidate, long now, boolean exemptFromCooldown) {
        String line = clean(candidate, 140);
        if (line.isBlank() || sensitive(line)) return "";
        // A social field must never serve as a second, unverified action report.
        if (line.matches("(?s).*(已经|完成|我去|我会|这就|马上|正在).*(采集|挖|砍|合成|建造|收集|制作|取|存|送|装备).*")) return "";
        if (p.getList("RecentChatter", Tag.TAG_STRING).contains(StringTag.valueOf(line))) return "";
        if (!exemptFromCooldown && p.contains("LastChatter") && now - p.getLong("LastChatter") < CHAT_COOLDOWN) return "";
        p.putLong("NextChat", now + (exemptFromCooldown ? CHAT_COOLDOWN / 2 : CHAT_COOLDOWN));
        if (!exemptFromCooldown) p.putLong("LastChatter", now);
        append(p, "RecentChatter", line, 6);
        return line;
    }

    /**
     * Backwards-compatible entry point: the model may still propose a line, but the deciding drive
     * is local now. Kept so older call sites keep working.
     */
    static String proactive(CompoundTag p, String candidate, long now, boolean safeAndNearby) {
        if (!mayChat(p, now, safeAndNearby)) return "";
        return acceptLine(p, candidate, now, true);
    }

    private static JsonArray strings(CompoundTag p, String key) {
        JsonArray result = new JsonArray(); for (Tag t : p.getList(key, Tag.TAG_STRING)) result.add(t.getAsString()); return result;
    }
    static JsonObject describe(CompoundTag p, Observation o, boolean mayChat, boolean socialOnly) {
        JsonObject out = new JsonObject(); out.addProperty("style", p.getString("Style"));
        out.addProperty("mood", p.getString("Mood")); out.addProperty("mood_reason", p.getString("MoodReason"));
        out.addProperty("mood_last_changed_ticks_ago", Math.max(0, o.tick - p.getLong("MoodSince")));
        JsonObject likes = new JsonObject(); for (String k : INTERESTS) likes.addProperty(k, p.getCompound("Likes").getInt(k));
        out.add("activity_preferences", likes); out.addProperty("daily_routine", phase(o.dayTime));
        out.addProperty("day", p.getLong("Day")); out.addProperty("daily_goal_id", p.getString("DailyGoal"));
        out.addProperty("daily_goal", goalName(p.getString("DailyGoal"))); out.addProperty("daily_goal_completed", p.getBoolean("GoalDone"));
        out.addProperty("goals_completed_today", p.getInt("DoneToday")); out.addProperty("goal_failures", p.getInt("GoalFailures"));
        out.addProperty("affection", p.getInt("Affection")); out.addProperty("relationship", affectionName(p.getInt("Affection")));
        out.addProperty("days_together", p.getInt("TogetherDays"));
        out.addProperty("owner_pet_name", p.getString("CallOwner"));
        out.add("owner_statements", strings(p, "OwnerFacts")); out.add("actual_memories", strings(p, "Episodes"));
        out.add("recent_proactive_lines", strings(p, "RecentChatter"));
        out.addProperty("may_speak_proactively", mayChat); out.addProperty("social_only_keep_working", socialOnly);
        return out;
    }
    static void acceptMemory(CompoundTag p, JsonObject response, String ownerInput) {
        String quote = text(response, "remember_quote", 180);
        // Model can select an exact owner quote, but cannot invent facts or learn from its own reply.
        if (quote.length() >= 4 && ownerInput.contains(quote)
            && (quote.contains("喜欢") || quote.contains("不喜欢") || quote.contains("叫我") || quote.contains("记住"))) remember(p, quote);
        String pet = petName(ownerInput);
        if (!pet.isBlank()) p.putString("CallOwner", pet);
    }
    static String summary(CompoundTag p) {
        String favorite = INTERESTS.stream().max(Comparator.comparingInt(k -> p.getCompound("Likes").getInt(k))).orElse("explore");
        String label = switch(favorite) {case "gather" -> "采集"; case "craft" -> "制作"; case "build" -> "建造"; case "farm" -> "农作"; default -> "探索";};
        String pet = p.getString("CallOwner");
        return "心情：" + p.getString("Mood") + "（" + p.getString("MoodReason") + "）；偏好：" + label
            + "；今天想做：" + goalName(p.getString("DailyGoal")) + (p.getBoolean("GoalDone") ? "（已完成）" : "")
            + "；亲密度：" + p.getInt("Affection") + "/100（" + affectionName(p.getInt("Affection")) + "）"
            + "；一起度过：" + p.getInt("TogetherDays") + " 天"
            + "；对你的称呼：" + (pet.isBlank() ? "还没有约定" : "「" + pet + "」")
            + "；主动聊天：" + (p.getBoolean("Chatter") ? "开启" : "关闭")
            + "；长期玩家记忆：" + p.getList("OwnerFacts", Tag.TAG_STRING).size() + " 条";
    }
}
