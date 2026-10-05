package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What her eyes can actually see right now, as game data.
 *
 * A single flat screenshot cannot tell a model how far away something is, what it is called, whether
 * it can be walked to, or what it can be turned into. So the picture and this list are sent together:
 * the image shows the situation, the list makes it exact and lets the model pick a real target id
 * instead of guessing from pixels.
 *
 * Everything here is derived from the loaded world on the server thread, needs no network, and is
 * deliberately small: one forward ray, a bounded fan of columns inside her view cone, and her
 * immediate neighbours. Nothing is invented; a column with no visible block is reported as unknown
 * rather than as empty ground.
 */
final class MaidSight {
    private MaidSight() {}

    /** Beyond this the useful detail is gone anyway; the 100-block survey covers far targets. */
    private static final int RANGE = 24;
    /** A person notices what is inside their view cone, not a full sphere. */
    private static final double VIEW_COS = Math.cos(Math.toRadians(55));

    static JsonObject describe(EntityMaid maid) {
        JsonObject out = new JsonObject();
        Vec3 eye = maid.getEyePosition();
        out.addProperty("origin", describeBlock(maid.blockPosition()));
        out.addProperty("facing_yaw", Math.round(maid.getYRot()));
        out.addProperty("facing_pitch", Math.round(maid.getXRot()));
        out.addProperty("range", RANGE);
        out.addProperty("scope", "Loaded terrain inside her view cone. It shows what is in front of her, "
            + "not what is hidden behind it, and not the underground. A block with reachable=true has "
            + "walkable ground next to it and is close enough to be worked on. Use target_id verbatim; "
            + "never invent one.");

        out.add("forward_ray", forwardRay(maid, eye));

        List<Column> columns = new ArrayList<>();
        boolean unobstructed = false;
        for (int i = 0; i < 24; i++) {
            double angle = Math.toRadians(maid.getYRot() + i * 15 - 172.5);
            int dx = (int)Math.round(Math.sin(angle) * 3);
            int dz = (int)Math.round(Math.cos(angle) * 3);
            if (dx == 0 && dz == 0) continue;
            Column column = column(maid, eye, dx, dz);
            if (column == null) continue;
            columns.add(column);
            if (!column.blocked() && column.air()) unobstructed = true;
        }
        columns.sort(Comparator.comparingDouble(Column::distance));
        JsonArray fan = new JsonArray();
        for (int i = 0; i < Math.min(16, columns.size()); i++) fan.add(columns.get(i).json());
        out.add("visible_columns", fan);
        out.addProperty("walkable_ground_ahead", unobstructed);

        JsonArray nearby = new JsonArray();
        BlockPos center = maid.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-4, -3, -4), center.offset(4, 3, 4))) {
            BlockState state = maid.level().getBlockState(pos);
            if (state.isAir()) continue;
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if (!interesting(id)) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("block", id);
            entry.addProperty("position", describeBlock(pos));
            entry.addProperty("distance", round(Math.sqrt(pos.distSqr(center))));
            nearby.add(entry);
            if (nearby.size() >= 24) break;
        }
        out.add("nearby_notable_blocks", nearby);
        return out;
    }

    /** What she would touch if she reached straight ahead. This is not a walk path. */
    private static JsonObject forwardRay(EntityMaid maid, Vec3 eye) {
        JsonObject out = new JsonObject();
        Vec3 look = maid.getViewVector(1.0f);
        Vec3 far = eye.add(look.scale(RANGE));
        BlockHitResult hit = maid.level().clip(new ClipContext(eye, far, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, maid));
        if (hit.getType() == HitResult.Type.MISS) {
            out.addProperty("block", "none within range");
            out.addProperty("note", "open view; nothing solid directly in the line of sight");
            return out;
        }
        BlockPos pos = hit.getBlockPos();
        BlockState state = maid.level().getBlockState(pos);
        out.addProperty("block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        out.addProperty("position", describeBlock(pos));
        out.addProperty("distance", round(eye.distanceTo(hit.getLocation())));
        out.addProperty("face", hit.getDirection().getName());
        out.addProperty("reachable", reachable(maid, pos));
        String kind = MaidFieldwork.kind(state);
        if (!kind.isEmpty()) out.addProperty("gatherable_as", kind);
        return out;
    }

    private record Column(String block, String position, double distance, String direction, boolean air,
                          boolean blocked, boolean reachable, String gatherableAs, String targetId) {
        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("block", block);
            out.addProperty("position", position);
            out.addProperty("distance", round(distance));
            out.addProperty("direction", direction);
            if (air) out.addProperty("note", "clear");
            if (gatherableAs != null) { out.addProperty("gatherable_as", gatherableAs); out.addProperty("target_id", targetId); }
            if (reachable) out.addProperty("reachable", true);
            return out;
        }
    }

    /** The tallest solid block in one view-cone column: the thing she would actually bump into. */
    private static Column column(EntityMaid maid, Vec3 eye, int dx, int dz) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int baseX = maid.blockPosition().getX() + dx, baseZ = maid.blockPosition().getZ() + dz;
        int top = maid.blockPosition().getY() + 3;
        for (int y = top; y >= maid.blockPosition().getY() - 4; y--) {
            cursor.set(baseX, y, baseZ);
            BlockState state = maid.level().getBlockState(cursor);
            if (state.isAir()) continue;
            boolean solid = !state.getCollisionShape(maid.level(), cursor).isEmpty();
            boolean liquid = !state.getFluidState().isEmpty();
            String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            String kind = MaidFieldwork.kind(state);
            String targetId = kind.isEmpty() ? null : kind + ":" + cursor.getX() + ":" + cursor.getY() + ":" + cursor.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            String direction = describeDirection(dx, dz, maid.getYRot());
            return new Column(id, describeBlock(cursor), distance, direction, false, solid || liquid,
                !kind.isEmpty() && reachable(maid, cursor.immutable()), kind.isEmpty() ? null : kind, targetId);
        }
        // Nothing at all in this column: report it as clear instead of pretending it is floor.
        cursor.set(baseX, maid.blockPosition().getY(), baseZ);
        return new Column("air", describeBlock(cursor), Math.sqrt(dx * dx + dz * dz),
            describeDirection(dx, dz, maid.getYRot()), true, false, false, null, null);
    }

    /** Walkable ground beside the block, so the model knows whether it can actually be worked on. */
    private static boolean reachable(EntityMaid maid, BlockPos pos) {
        if (pos.distSqr(maid.blockPosition()) > 5 * 5) return false;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = pos.relative(side);
            BlockState foot = maid.level().getBlockState(next);
            BlockState head = maid.level().getBlockState(next.above());
            if (foot.isAir() && head.isAir() && !maid.level().getBlockState(next.below()).getCollisionShape(maid.level(), next.below()).isEmpty()) return true;
        }
        return false;
    }

    private static boolean interesting(String id) {
        return id.contains("table") || id.contains("furnace") || id.contains("chest") || id.contains("barrel")
            || id.contains("torch") || id.contains("door") || id.contains("crafting")
            || id.contains("water") || id.contains("lava") || id.contains("bed") || id.contains("farmland")
            || id.contains("sweet_berry") || id.contains("crop") || id.endsWith("_log") || id.endsWith("_stem")
            || id.contains("ore") || id.contains("cobblestone") || id.contains("stone")
            || id.contains("plank") || id.contains("dirt") || id.contains("grass_block") || id.contains("sand");
    }

    private static String describeDirection(int dx, int dz, float yaw) {
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double delta = ((target - yaw + 540) % 360) - 180;
        if (Math.abs(delta) <= 30) return "straight ahead";
        if (Math.abs(delta) >= 150) return "behind her";
        return delta > 0 ? "to her left" : "to her right";
    }

    /** Short, stable, and exactly the format the executor's target ids use. */
    private static String describeBlock(BlockPos pos) { return pos.getX() + "," + pos.getY() + "," + pos.getZ(); }
    private static double round(double value) { return Math.round(value * 10) / 10.0; }
}
