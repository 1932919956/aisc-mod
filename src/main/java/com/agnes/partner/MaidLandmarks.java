package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Places she remembers.
 *
 * Without this she rediscovers the same water, the same ore, the same camp every time she walks past,
 * which is exactly what makes an agent look like it has no life: no continuity, no home ground. A
 * landmark is a small, bounded note in her own saved data -- kind, position, dimension, and how often
 * it proved to be still there.
 *
 * Two rules keep it honest:
 *  - A landmark is only written from an event that really happened (she harvested there, she built
 *    there, she stood next to real water). The model never writes one.
 *  - A remembered place is a hypothesis, not a fact. She walks to it by memory, and on arrival checks
 *    whether it is still there; three failures and the memory is dropped instead of sending her on
 *    the same pointless trip forever.
 */
final class MaidLandmarks {
    private MaidLandmarks() {}

    static final String KEY = "Landmarks";
    private static final int MAX = 24;
    /** A remembered place is worth the walk from here; beyond this the survey is the better tool. */
    private static final int NEAR_RANGE = 220;
    private static final long VISIT_TIMEOUT = 6000;      // five minutes without arriving
    private static final long VISIT_STALL = 300;         // fifteen seconds without getting closer
    private static final Map<UUID, Visit> VISITS = new ConcurrentHashMap<>();

    private static final class Visit {
        final int index;
        final BlockPos destination;
        final long started;
        double bestDistance = Double.MAX_VALUE;
        long lastProgress;
        Visit(int index, BlockPos destination, long now) {
            this.index = index; this.destination = destination; this.started = now; this.lastProgress = now;
        }
    }

    static void clear() { VISITS.clear(); }

    // ---------------------------------------------------------------- storage

    /** Upgrades older saves in place; a maid without landmarks simply has an empty memory. */
    private static ListTag list(CompoundTag mind) {
        if (!mind.contains(KEY, Tag.TAG_LIST)) mind.put(KEY, new ListTag());
        return mind.getList(KEY, Tag.TAG_COMPOUND);
    }

    private static List<CompoundTag> entries(CompoundTag mind) {
        List<CompoundTag> out = new ArrayList<>();
        for (Tag tag : list(mind)) if (tag instanceof CompoundTag entry) out.add(entry);
        return out;
    }

    private static void store(CompoundTag mind, List<CompoundTag> entries) {
        ListTag out = new ListTag();
        for (CompoundTag entry : entries) out.add(entry);
        mind.put(KEY, out);
    }

    // ---------------------------------------------------------------- remember

    /**
     * Record a place, or refresh it when she is already standing on a remembered one of the same kind.
     * Cheap enough to call from any successful action; writes nothing else and never talks to a model.
     */
    static boolean remember(EntityMaid maid, String kind, BlockPos pos, String name) {
        if (maid == null || pos == null || kind == null || kind.isBlank()) return false;
        CompoundTag mind = MaidBridge.mind(maid);
        List<CompoundTag> entries = entries(mind);
        String dimension = maid.level().dimension().location().toString();
        // The same kind of place within a few blocks is the same place; move the note, do not add one.
        for (CompoundTag entry : entries) {
            if (!entry.getString("kind").equals(kind) || !entry.getString("dimension").equals(dimension)) continue;
            if (Math.abs(entry.getInt("x") - pos.getX()) > 4 || Math.abs(entry.getInt("z") - pos.getZ()) > 4
                || Math.abs(entry.getInt("y") - pos.getY()) > 4) continue;
            entry.putInt("x", pos.getX()); entry.putInt("y", pos.getY()); entry.putInt("z", pos.getZ());
            entry.putInt("visits", entry.getInt("visits") + 1);
            entry.putLong("seen", maid.level().getGameTime());
            if (name != null && !name.isBlank()) entry.putString("name", MaidPersonality.clean(name, 40));
            store(mind, entries);
            return false;
        }
        CompoundTag entry = new CompoundTag();
        entry.putString("kind", kind);
        entry.putString("name", name == null ? defaultName(kind) : MaidPersonality.clean(name, 40));
        entry.putInt("x", pos.getX()); entry.putInt("y", pos.getY()); entry.putInt("z", pos.getZ());
        entry.putString("dimension", dimension);
        entry.putLong("seen", maid.level().getGameTime());
        entry.putInt("visits", 1); entry.putInt("failures", 0);
        entries.add(entry);
        // Bounded memory: when full, forget the oldest note with the fewest visits.
        while (entries.size() > MAX) {
            CompoundTag worst = entries.stream()
                .min(Comparator.comparingInt((CompoundTag e) -> e.getInt("visits")).thenComparingLong(e -> e.getLong("seen")))
                .orElse(entries.get(0));
            entries.remove(worst);
        }
        store(mind, entries);
        return true;
    }

    /** Forgets a place she visited and which is no longer there. */
    static void forget(EntityMaid maid, int index, String reason) {
        CompoundTag mind = MaidBridge.mind(maid);
        List<CompoundTag> entries = entries(mind);
        if (index < 0 || index >= entries.size()) return;
        CompoundTag entry = entries.remove(index);
        store(mind, entries);
        MaidPersonality.appendNote(mind, "忘了这个地方（" + reason + "）：" + entry.getString("name"));
    }

    private static String defaultName(String kind) {
        return switch (kind) {
            case "water" -> "我的水源";
            case "mine" -> "我挖过的矿点";
            case "house" -> "我盖的小屋";
            case "table" -> "我的工作台";
            case "furnace" -> "我的熔炉";
            case "storage" -> "我放东西的地方";
            case "farm" -> "我照看的农田";
            case "camp" -> "我的落脚点";
            default -> "我记得的地方";
        };
    }

    // ---------------------------------------------------------------- read

    /** Landmarks in this dimension, nearest first, as prompt data. */
    static JsonArray describe(EntityMaid maid, int limit) {
        JsonArray out = new JsonArray();
        String dimension = maid.level().dimension().location().toString();
        List<CompoundTag> candidates = new ArrayList<>();
        for (CompoundTag entry : entries(MaidBridge.mind(maid))) {
            if (!entry.getString("dimension").equals(dimension)) continue;
            if (entry.getInt("failures") >= 2) continue;
            candidates.add(entry);
        }
        candidates.sort(Comparator.comparingDouble(e -> new BlockPos(e.getInt("x"), e.getInt("y"), e.getInt("z")).distSqr(maid.blockPosition())));
        int index = 0;
        for (CompoundTag entry : candidates) {
            BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            JsonObject object = new JsonObject();
            object.addProperty("target_id", "landmark:" + index);
            object.addProperty("kind", entry.getString("kind"));
            object.addProperty("name", entry.getString("name"));
            object.addProperty("position", pos.getX() + "," + pos.getY() + "," + pos.getZ());
            object.addProperty("distance", Math.round(Math.sqrt(pos.distSqr(maid.blockPosition()))));
            object.addProperty("times_used", entry.getInt("visits"));
            if (entry.getInt("failures") > 0) object.addProperty("note", "上一次去时没有找到，可能已经没了");
            out.add(object);
            index++;
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** The position behind a "landmark:N" id, or null when the note no longer exists. */
    static BlockPos position(EntityMaid maid, int index) {
        List<CompoundTag> all = entries(MaidBridge.mind(maid));
        String dimension = maid.level().dimension().location().toString();
        int seen = 0;
        for (CompoundTag entry : all) {
            if (!entry.getString("dimension").equals(dimension) || entry.getInt("failures") >= 2) continue;
            if (seen == index) return new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            seen++;
        }
        return null;
    }

    static int parseIndex(String targetId) {
        if (targetId == null || !targetId.startsWith("landmark:")) return -1;
        try { return Integer.parseInt(targetId.substring("landmark:".length()).trim()); }
        catch (NumberFormatException invalid) { return -1; }
    }

    static String nameOf(EntityMaid maid, int index) {
        List<CompoundTag> all = entries(MaidBridge.mind(maid));
        String dimension = maid.level().dimension().location().toString();
        int seen = 0;
        for (CompoundTag entry : all) {
            if (!entry.getString("dimension").equals(dimension) || entry.getInt("failures") >= 2) continue;
            if (seen == index) return entry.getString("name");
            seen++;
        }
        return "那个地方";
    }

    static boolean isLandmark(String targetId) { return parseIndex(targetId) >= 0; }

    static int count(EntityMaid maid) { return entries(MaidBridge.mind(maid)).size(); }

    // ---------------------------------------------------------------- travel

    /** Start walking to a remembered place. No fresh observation is needed: that is the whole point. */
    static String visit(EntityMaid maid, String targetId, boolean autonomous) {
        int index = parseIndex(targetId);
        if (index < 0) return "没有匹配到记住的地方";
        BlockPos destination = position(maid, index);
        if (destination == null) return "这个地点已经不在我的记忆里了";
        if (maid.level().dimension().location().toString().isEmpty()) return "维度信息缺失，没有出发";
        String name = nameOf(maid, index);
        // Walking there replaces whatever she was doing, exactly like a player who changes plans.
        MaidFieldwork.cancel(maid, "出发去记住的地方");
        MaidWorkshop.cancel(maid, "出发去记住的地方");
        MaidPlan.clear(maid, "出发去记住的地方");
        CompoundTag data = MaidBridge.mind(maid);
        data.putBoolean("IdleWork", false);
        VISITS.put(maid.getUUID(), new Visit(index, destination, maid.level().getGameTime()));
        if (!maid.isHomeModeEnable()) maid.getSchedulePos().setHomeModeEnable(maid, maid.blockPosition());
        maid.setHomeModeEnable(true);
        return "正在走向我记得的「" + name + "」，大约 " + Math.round(Math.sqrt(destination.distSqr(maid.blockPosition()))) + " 格";
    }

    static boolean active(EntityMaid maid) { return VISITS.containsKey(maid.getUUID()); }

    static void cancel(EntityMaid maid) { VISITS.remove(maid.getUUID()); }

    /**
     * Moves her toward the remembered place in short segments, and on arrival checks whether the place
     * is still what she remembers. Called once per second from the main heartbeat.
     */
    static void tick(EntityMaid maid, ServerPlayer player) {
        Visit visit = VISITS.get(maid.getUUID());
        if (visit == null) return;
        long now = maid.level().getGameTime();
        if (maid.isSleeping() || maid.isOrderedToSit() || maid.getTarget() != null || maid.getHealth() < maid.getMaxHealth() * 0.5f) {
            VISITS.remove(maid.getUUID());
            MaidBridge.mind(maid).putString("LandmarkResult", "去「" + nameOf(maid, visit.index) + "」的路上被打断了");
            return;
        }
        double distance = Math.sqrt(maid.position().distanceToSqr(Vec3.atBottomCenterOf(visit.destination)));
        if (distance <= 3.5) { arrive(maid, visit); return; }
        if (now - visit.started > VISIT_TIMEOUT) {
            VISITS.remove(maid.getUUID());
            MaidBridge.mind(maid).putString("LandmarkResult", "去「" + nameOf(maid, visit.index) + "」没走到，先放弃这次");
            return;
        }
        if (distance < visit.bestDistance - 0.5) { visit.bestDistance = distance; visit.lastProgress = now; }
        else if (now - visit.lastProgress > VISIT_STALL) {
            VISITS.remove(maid.getUUID());
            MaidBridge.mind(maid).putString("LandmarkResult", "去「" + nameOf(maid, visit.index) + "」的路被挡住，先放弃这次");
            return;
        }
        // Segmented stepping, the same approach used for distant scouting.
        BlockPos waypoint = step(maid, visit.destination);
        if (waypoint == null) return;
        maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(Vec3.atBottomCenterOf(waypoint), 0.65f, 0));
        maid.getNavigation().moveTo(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5, 0.65);
        MaidBridge.mind(maid).putString("LandmarkResult", "正在去「" + nameOf(maid, visit.index) + "」，还差约 " + Math.round(distance) + " 格");
    }

    private static BlockPos step(EntityMaid maid, BlockPos destination) {
        int dx = destination.getX() - maid.blockPosition().getX();
        int dz = destination.getZ() - maid.blockPosition().getZ();
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 0.001) return null;
        int span = (int)Math.min(12, Math.max(1, length));
        return maid.blockPosition().offset((int)Math.round(dx / length * span), 0, (int)Math.round(dz / length * span));
    }

    /** On arrival: is the place still what she remembered? If not, the memory is corrected. */
    private static void arrive(EntityMaid maid, Visit visit) {
        VISITS.remove(maid.getUUID());
        CompoundTag mind = MaidBridge.mind(maid);
        List<CompoundTag> entries = entries(mind);
        String dimension = maid.level().dimension().location().toString();
        int seen = 0;
        for (CompoundTag entry : entries) {
            if (!entry.getString("dimension").equals(dimension) || entry.getInt("failures") >= 2) continue;
            if (seen != visit.index) { seen++; continue; }
            String name = entry.getString("name");
            if (stillThere(maid, entry.getString("kind"), visit.destination)) {
                entry.putInt("visits", entry.getInt("visits") + 1);
                entry.putInt("failures", 0);
                entry.putLong("seen", maid.level().getGameTime());
                store(mind, entries);
                mind.putString("LandmarkResult", "到了我记得的「" + name + "」，东西还在");
                MaidBubble.speakIfExpired(maid, "landmark", "回到「" + name + "」了。", -1L);
            } else {
                entry.putInt("failures", entry.getInt("failures") + 1);
                store(mind, entries);
                mind.putString("LandmarkResult", "到了「" + name + "」，但那里已经不是我记得的样子了");
                MaidBubble.speakIfExpired(maid, "landmark", "这里和我记得的不一样了……", -1L);
                if (entry.getInt("failures") >= 2) forget(maid, visit.index, "去过两次都不是原来的样子");
            }
            return;
        }
    }

    /** A remembered place is checked against the world, per kind, with a small tolerance. */
    static boolean stillThere(EntityMaid maid, String kind, BlockPos pos) {
        return switch (kind) {
            case "water" -> hasWaterNearby(maid, pos, 8);
            case "mine" -> hasOreOrStoneNearby(maid, pos, 6);
            case "house" -> hasAnySolidNearby(maid, pos, 4);
            case "table" -> hasBlockNearby(maid, pos, 4, Blocks.CRAFTING_TABLE);
            case "furnace" -> hasBlockNearby(maid, pos, 4, Blocks.FURNACE);
            case "storage" -> hasStorageNearby(maid, pos, 4);
            case "farm" -> hasFarmlandNearby(maid, pos, 8);
            default -> true;
        };
    }

    private static boolean hasWaterNearby(EntityMaid maid, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -3, -radius), center.offset(radius, 2, radius))) {
            if (maid.level().isLoaded(pos) && !maid.level().getFluidState(pos).isEmpty()) return true;
        }
        return false;
    }

    private static boolean hasOreOrStoneNearby(EntityMaid maid, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius))) {
            if (!maid.level().isLoaded(pos)) continue;
            var state = maid.level().getBlockState(pos);
            String kind = MaidFieldwork.kind(state);
            if (kind.equals("stone") || kind.equals("coal") || kind.equals("iron")) return true;
        }
        return false;
    }

    private static boolean hasAnySolidNearby(EntityMaid maid, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -2, -radius), center.offset(radius, 3, radius))) {
            if (!maid.level().isLoaded(pos)) continue;
            if (!maid.level().getBlockState(pos).getCollisionShape(maid.level(), pos).isEmpty()) return true;
        }
        return false;
    }

    private static boolean hasBlockNearby(EntityMaid maid, BlockPos center, int radius, net.minecraft.world.level.block.Block block) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -3, -radius), center.offset(radius, 3, radius))) {
            if (maid.level().isLoaded(pos) && maid.level().getBlockState(pos).is(block)) return true;
        }
        return false;
    }

    private static boolean hasStorageNearby(EntityMaid maid, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -3, -radius), center.offset(radius, 3, radius))) {
            if (maid.level().isLoaded(pos) && MaidStorage.storage(maid.level().getBlockState(pos))) return true;
        }
        return false;
    }

    private static boolean hasFarmlandNearby(EntityMaid maid, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -3, -radius), center.offset(radius, 3, radius))) {
            if (maid.level().isLoaded(pos) && maid.level().getBlockState(pos).is(Blocks.FARMLAND)) return true;
        }
        return false;
    }

    /**
     * Called when she finishes a survey scan: note water she has actually seen. This is the one place
     * a landmark comes from observation instead of from an action she performed, because a shoreline is
     * exactly the thing worth not rediscovering.
     */
    static void learnFromSurvey(EntityMaid maid) {
        String dimension = maid.level().dimension().location().toString();
        int already = 0;
        for (CompoundTag entry : entries(MaidBridge.mind(maid)))
            if (entry.getString("kind").equals("water") && entry.getString("dimension").equals(dimension)) already++;
        if (already >= 3) return;
        for (var spot : MaidSurvey.spots(maid)) {
            if (!spot.kind().equals("scout")) continue;
            if (!hasWaterNearby(maid, spot.pos(), 5)) continue;
            remember(maid, "water", spot.pos(), null);
            return;
        }
    }

    /** True when she has ever recorded a place of this kind, in any dimension. */
    static boolean hasNearbyOrRemembered(EntityMaid maid, String kind) {
        for (CompoundTag entry : entries(MaidBridge.mind(maid)))
            if (entry.getString("kind").equals(kind) && entry.getInt("failures") < 2) return true;
        return false;
    }

    /** True when a remembered place of this kind is close enough that going there is the obvious move. */
    static boolean hasNearby(EntityMaid maid, String kind, int range) {
        String dimension = maid.level().dimension().location().toString();
        for (CompoundTag entry : entries(MaidBridge.mind(maid))) {
            if (!entry.getString("kind").equals(kind) || !entry.getString("dimension").equals(dimension)) continue;
            BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            if (pos.distSqr(maid.blockPosition()) <= range * range) return true;
        }
        return false;
    }

    /** The whole memory as one line, for the status command. */
    static String summary(EntityMaid maid) {
        List<CompoundTag> all = entries(MaidBridge.mind(maid));
        if (all.isEmpty()) return "还没有记住任何地方";
        String dimension = maid.level().dimension().location().toString();
        List<String> parts = new ArrayList<>();
        for (CompoundTag entry : all) {
            if (!entry.getString("dimension").equals(dimension)) continue;
            BlockPos pos = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));
            parts.add(entry.getString("name") + "(" + Math.round(Math.sqrt(pos.distSqr(maid.blockPosition()))) + "格)");
        }
        return parts.isEmpty() ? "记住的地方都在其他维度" : String.join("、", parts);
    }

    /** Where she has her base: the most used remembered place, or null. */
    static BlockPos base(EntityMaid maid) {
        String dimension = maid.level().dimension().location().toString();
        return entries(MaidBridge.mind(maid)).stream()
            .filter(e -> e.getString("dimension").equals(dimension) && e.getInt("failures") < 2)
            .max(Comparator.comparingInt(e -> e.getInt("visits")))
            .map(e -> new BlockPos(e.getInt("x"), e.getInt("y"), e.getInt("z"))).orElse(null);
    }
}
