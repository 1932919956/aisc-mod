package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;

/** Main-thread incremental survey. Never requests/generates chunks or scans an entire 201^3 cube. */
final class MaidSurvey {
    static final int RADIUS = 100;
    private static final int WIDTH = RADIUS * 2 + 1;
    static final int COLUMNS_PER_STEP = 64;
    private static final Map<UUID, Scan> SCANS = new HashMap<>();
    record Spot(BlockPos pos, String kind) {}
    private static final class Scan {
        final BlockPos center;
        final String dimension;
        final List<Spot> spots = new ArrayList<>();
        final Map<String, List<Spot>> buckets = new HashMap<>();
        final Map<String, Integer> blocks = new HashMap<>();
        int cursor, loaded, skipped;
        long finished;
        Scan(EntityMaid maid) { center = maid.blockPosition(); dimension = maid.level().dimension().location().toString(); }
    }
    static void clear() { SCANS.clear(); }
    static void remove(EntityMaid maid) { SCANS.remove(maid.getUUID()); }
    static void tick(EntityMaid maid) {
        Scan s = SCANS.get(maid.getUUID());
        if (s == null || !s.dimension.equals(maid.level().dimension().location().toString())
            || s.center.distSqr(maid.blockPosition()) > 24 * 24
            || (s.cursor == WIDTH * WIDTH && maid.level().getGameTime() - s.finished >= 2400)) {
            s = new Scan(maid); SCANS.put(maid.getUUID(), s);
        }
        if (s.cursor == WIDTH * WIDTH) return;
        // Up to 64 columns each game tick, rather than 1024 columns in a one-second burst.
        long deadline = System.nanoTime() + 750_000;
        int end = Math.min(WIDTH * WIDTH, s.cursor + COLUMNS_PER_STEP);
        for (; s.cursor < end; s.cursor++) {
            if (s.cursor % 8 == 0 && System.nanoTime() >= deadline) break;
            int dx = s.cursor % WIDTH - RADIUS, dz = s.cursor / WIDTH - RADIUS;
            if (dx * dx + dz * dz > RADIUS * RADIUS) continue;
            int x = s.center.getX() + dx, z = s.center.getZ() + dz;
            LevelChunk chunk = ((net.minecraft.server.level.ServerLevel)maid.level()).getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) { s.skipped++; continue; }
            s.loaded++;
            int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);
            for (int y = s.center.getY() - 8; y <= s.center.getY() + 8; y++) sample(s, chunk, new BlockPos(x, y, z));
            for (int y = surface - 8; y <= surface + 2; y++) {
                if (y < s.center.getY() - 8 || y > s.center.getY() + 8) sample(s, chunk, new BlockPos(x, y, z));
            }
            // Sparse destinations for purposeful walking instead of repeated idle wandering.
            if (dx % 24 == 0 && dz % 24 == 0 && dx * dx + dz * dz >= 24 * 24) {
                BlockPos floor = new BlockPos(x, surface, z);
                if (chunk.getBlockState(floor).isAir()) floor = floor.below();
                if (!chunk.getBlockState(floor).getCollisionShape(maid.level(), floor).isEmpty()
                    && chunk.getBlockState(floor).getFluidState().isEmpty()
                    && chunk.getBlockState(floor.above()).isAir() && chunk.getBlockState(floor.above(2)).isAir()) add(s, new Spot(floor.above(), "scout"));
            }
        }
        if (s.cursor == WIDTH * WIDTH) s.finished = maid.level().getGameTime();
    }
    private static void sample(Scan s, LevelChunk chunk, BlockPos p) {
        var state = chunk.getBlockState(p);
        if (state.isAir()) return;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        s.blocks.merge(id, 1, Integer::sum);
        String kind = MaidFieldwork.kind(state);
        if (Set.of("stone", "coal", "iron").contains(kind)) {
            // No neighbor chunk loads; filter buried rock before it displaces useful surface targets.
            boolean exposed = false;
            for (var direction : net.minecraft.core.Direction.values()) {
                BlockPos next = p.relative(direction);
                if ((next.getX() >> 4) == chunk.getPos().x && (next.getZ() >> 4) == chunk.getPos().z
                    && chunk.getBlockState(next).isAir()) { exposed = true; break; }
            }
            if (!exposed) return;
        }
        if (!kind.isEmpty()) add(s, new Spot(p, kind));
    }
    private static void add(Scan s, Spot spot) {
        // Bound memory while retaining resources in each direction, not just the first forest scanned.
        int sector = sector(s.center, spot.pos());
        var bucket = s.buckets.computeIfAbsent(spot.kind() + sector, key -> new ArrayList<>(8));
        for (Spot present : bucket) if (present.pos().equals(spot.pos())) return;
        if (bucket.size() >= 8) {
            Spot farthest = bucket.stream().max(Comparator.comparingDouble(p -> p.pos().distSqr(s.center))).orElseThrow();
            if (spot.pos().distSqr(s.center) >= farthest.pos().distSqr(s.center)) return;
            s.spots.remove(farthest);
            bucket.remove(farthest);
        }
        s.spots.add(spot); bucket.add(spot);
    }
    private static int sector(BlockPos center, BlockPos p) {
        return Math.floorMod((int)Math.floor(Math.atan2(p.getZ() - center.getZ(), p.getX() - center.getX()) * 4 / Math.PI), 8);
    }
    static List<Spot> spots(EntityMaid maid) {
        Scan s = SCANS.get(maid.getUUID());
        return s == null ? List.of() : List.copyOf(s.spots);
    }
    static JsonObject describe(EntityMaid maid) {
        JsonObject out = new JsonObject(); Scan s = SCANS.get(maid.getUUID());
        out.addProperty("horizontal_radius", RADIUS);
        out.addProperty("scope", "Loaded chunks only; same-height +/-8 blocks plus surface band. Not all underground blocks, not visual line-of-sight. Targets require walking and local checks.");
        out.addProperty("scan_percent", s == null ? 0 : s.cursor * 100 / (WIDTH * WIDTH));
        com.google.gson.JsonArray entities = new com.google.gson.JsonArray();
        maid.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, maid.getBoundingBox().inflate(RADIUS),
            e -> e != maid && e.isAlive() && e.distanceToSqr(maid) <= RADIUS * RADIUS).stream()
            .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(maid))).limit(24).forEach(e -> {
                JsonObject entry = new JsonObject();
                entry.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
                entry.addProperty("position", e.blockPosition().toShortString());
                entry.addProperty("distance", Math.round(Math.sqrt(e.distanceToSqr(maid))));
                entities.add(entry);
            });
        out.add("nearby_loaded_entities", entities);
        if (s != null) {
            out.addProperty("center", s.center.toShortString()); out.addProperty("loaded_columns", s.loaded);
            out.addProperty("skipped_unloaded_columns", s.skipped);
            JsonObject types = new JsonObject();
            s.blocks.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(32).forEach(e -> types.addProperty(e.getKey(), e.getValue()));
            out.add("sampled_block_counts", types);
        }
        return out;
    }
}
