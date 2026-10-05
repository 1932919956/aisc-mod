package com.agnes.verify;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;

/**
 * Runtime verification harness. Runs inside a real Forge dedicated server with a real Touhou Little
 * Maid, so every check exercises actual entities, an actual world, and the actual mod code.
 *
 * It reaches the companion's package-private classes through reflection instead of compiling against
 * a helper class inside com.agnes.partner: two mod jars cannot both own the same package (the JPMS
 * split-package rule), and this keeps the release jar free of any test scaffolding.
 */
@Mod("agnesverify")
public final class VerifyMod {
    private static final String P = "com.agnes.partner.";
    private int passed, failed;

    private net.minecraft.server.MinecraftServer server;
    private ServerLevel level;
    private Object maid;
    /** 0 = finished, 1 = walking and mining, 2 = digging the staircase. */
    private int phase;
    private int ticks;
    private int pulses;
    private BlockPos arena;
    private BlockPos gatherStone;
    private BlockPos digFloor;

    public VerifyMod() { MinecraftForge.EVENT_BUS.register(this); }

    @SubscribeEvent
    public void started(ServerStartedEvent event) {
        server = event.getServer();
        level = server.overworld();
        System.out.println("===== AGNES VERIFY BEGIN =====");
        maid = spawnMaid(level);
        if (maid == null) {
            System.out.println("VERIFY FAIL spawn :: could not create a maid entity");
            failed++;
            finish();
            return;
        }
        // The platform height must be read BEFORE the other checks place their own blocks: measuring it
        // afterwards put the floor on top of a harness-placed block, thirty blocks above the terrain,
        // and she simply walked off the edge and fell.
        arena = platform();
        // These need no running clock: they are one-shot observations and real world mutations.
        check("bubbles", () -> bubbles(level, maid));
        check("personality", () -> personality(level, maid));
        check("landmarks", () -> landmarks(level, maid));
        check("sight", () -> sight(level, maid));
        check("hunting-rules", () -> hunting(level, maid));
        check("blueprints", () -> blueprints(level, maid));
        check("agent-tools", () -> agentTools(level, maid));
        check("farm-availability", () -> farmingAvailability(level, maid));
        check("crafting", () -> crafting(maid));
        gatherSetup();
    }

    /**
     * The walking and mining part runs on real server ticks, because it has to.
     *
     * The first version called the per-second machines in a tight loop from ServerStartedEvent, where
     * the server has not begun ticking and the game clock is frozen - the situation report printed
     * day_time=0, which is the proof. The fieldwork machine refuses a second call inside the same 20
     * game ticks, so with a frozen clock every call after the first was rejected, her progress stayed
     * at "1/2 seconds" forever and the stone was never mined. That failure was the harness, not the mod.
     * Driving the same scenario from the tick event fixes it and additionally exercises real walking.
     */
    @SubscribeEvent
    public void ticking(TickEvent.ServerTickEvent event) {
        if (phase == 0 || event.phase != TickEvent.Phase.END) return;
        if (++ticks % 20 != 0) return;
        try {
            pulses++;
            if (phase == 1) gatherStep();
            else if (phase == 2) digStep();
        } catch (Throwable error) {
            failed++;
            System.out.println("VERIFY THREW phase" + phase + " :: " + error.getClass().getSimpleName()
                + ": " + error.getMessage());
            for (StackTraceElement frame : error.getStackTrace()) {
                if (frame.getClassName().startsWith("com.agnes")) { System.out.println("   at " + frame); break; }
            }
            finish();
        }
    }

    /** A flat, fully controlled platform on real ground, so "walk over there and mine it" is a fair test. */
    private BlockPos platform() {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8);
        BlockPos base = new BlockPos(8, y, 8);
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-6, -2, -2), base.offset(6, -1, 2)))
            level.setBlockAndUpdate(p, Blocks.DIRT.defaultBlockState());
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-6, 0, -2), base.offset(6, 3, 2)))
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        // Anchor her schedule here, or the idle task wanders her off the platform mid-test.
        ((com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid) maid)
            .getSchedulePos().setHomeModeEnable(
                (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid) maid, base);
        return base;
    }

    private void gatherSetup() {
        try {
            for (String machine : new String[]{"MaidFieldwork", "MaidDig"}) {
                invokeStatic(machine, "cancel", new Class<?>[]{maidClass(), String.class}, maid, "harness begins");
            }
            net.minecraft.world.entity.Entity entity = (net.minecraft.world.entity.Entity) maid;
            BlockPos base = arena;
            // She was spawned high in the air so the sight scan would have open space; put her on the
            // floor before the first tick or she would fall thirty blocks and die.
            entity.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5, 0f, 0f);
            ((com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid) maid)
                .setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_PICKAXE));
            gatherStone = base.offset(5, 0, 0);
            level.setBlockAndUpdate(gatherStone, Blocks.STONE.defaultBlockState());
            String id = "stone:" + gatherStone.getX() + ":" + gatherStone.getY() + ":" + gatherStone.getZ();
            Object gather = invokeStatic("MaidFieldwork", "start",
                new Class<?>[]{maidClass(), String.class, String.class, boolean.class}, maid, id, "gather", false);
            System.out.println("VERIFY gather start=[" + gather + "] stone=" + gatherStone + " standing=" + base);
            if (!(gather instanceof String text) || text.startsWith("未启动")) {
                failed++;
                System.out.println("VERIFY FAIL executor-gather :: refused to start: " + gather);
                digSetup();
                return;
            }
            pulses = 0;
            phase = 1;
        } catch (Throwable error) {
            failed++;
            System.out.println("VERIFY THREW executor-gather setup :: " + error);
            finish();
        }
    }

    private void gatherStep() throws Exception {
        recoverIfLost();
        pulse();
        if (!level.getBlockState(gatherStone).is(Blocks.STONE)) {
            passed++;
            System.out.println("VERIFY PASS executor-gather :: she walked to " + gatherStone
                + " and mined it, in " + pulses + "s");
            digSetup();
            return;
        }
        if (pulses == 1 || pulses % 5 == 0)
            System.out.println("VERIFY gather t=" + pulses + "s " + where() + " fieldwork=[" + mind("Fieldwork") + "]");
        if (pulses >= 40) {
            failed++;
            System.out.println("VERIFY FAIL executor-gather :: stone still at " + gatherStone + " after 40s; "
                + where() + " fieldwork=[" + mind("Fieldwork") + "]");
            digSetup();
        }
    }

    /**
     * If she ends up somewhere the test did not intend (off the platform, or below it), say so and put
     * her back. Being honest about an intervention is better than a failure caused by the harness.
     */
    private void recoverIfLost() {
        net.minecraft.world.entity.Entity entity = (net.minecraft.world.entity.Entity) maid;
        BlockPos at = entity.blockPosition();
        if (at.getY() < arena.getY() - 3 || at.distSqr(arena) > 400) {
            System.out.println("VERIFY note :: she wandered to " + at + ", recovered to the test platform");
            entity.moveTo(arena.getX() + 0.5, arena.getY(), arena.getZ() + 0.5, 0f, 0f);
        }
    }

    private void digSetup() {
        try {
            invokeStatic("MaidFieldwork", "cancel", new Class<?>[]{maidClass(), String.class}, maid, "dig check begins");
            net.minecraft.world.entity.Entity entity = (net.minecraft.world.entity.Entity) maid;
            BlockPos room = mineRoom();
            entity.moveTo(room.getX() + 0.5, room.getY(), room.getZ() + 0.5, 0f, 0f);
            ((com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid) maid)
                .getSchedulePos().setHomeModeEnable(
                    (com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid) maid, room);
            digFloor = entity.blockPosition();
            // Say out loud what surrounds the first step: a refusal to dig is only acceptable evidence if
            // the reason is visible, and the first attempt stopped on water that flowed into the hole.
            net.minecraft.core.Direction facing = net.minecraft.core.Direction.fromYRot(entity.getYRot());
            BlockPos foot = digFloor.relative(facing);
            System.out.println("VERIFY dig site " + digFloor + " facing=" + facing
                + " foot=" + blockAt(foot) + " step=" + blockAt(foot.below())
                + " head=" + blockAt(foot.above()) + " ceiling=" + blockAt(foot.above(2)));
            // Refuse to accept a result measured in the void: a hundred air blocks below her is a pass
            // only if they were stone a moment ago.
            int solidBefore = 0;
            for (BlockPos p : BlockPos.betweenClosed(digFloor.offset(-2, -4, -2), digFloor.offset(2, -1, 2)))
                if (!level.getBlockState(p).isAir()) solidBefore++;
            if (solidBefore < 50) {
                failed++;
                System.out.println("VERIFY FAIL executor-dig :: the test pocket was not built (only "
                    + solidBefore + "/100 solid blocks below her); refusing to call empty space a staircase");
                finish();
                return;
            }
            Object shaft = invokeStatic("MaidDig", "start",
                new Class<?>[]{maidClass(), int.class, boolean.class}, maid, 4, false);
            System.out.println("VERIFY dig start=[" + shaft + "]");
            pulses = 0;
            phase = 2;
        } catch (Throwable error) {
            failed++;
            System.out.println("VERIFY THREW executor-dig setup :: " + error);
            finish();
        }
    }

    /**
     * A sealed stone pocket to dig in, built high in the air.
     *
     * Two earlier attempts were wasted here. Digging on the surface stopped at once because the shaft
     * opened into water, which flowed in - the mod correctly refused, so the test needed dry ground.
     * The next attempt put the pocket twenty blocks BELOW the surface and every block was "Void Air":
     * this world's surface is at y=-60 and the minimum build height is -64, so the stone was never
     * placed and a staircase of pure void counted as success. The pocket is therefore built in the sky,
     * and digSetup refuses to trust the result unless the ground under her really is solid.
     */
    private BlockPos mineRoom() {
        BlockPos center = arena.above(20);
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-4, -8, -3), center.offset(4, 3, 7)))
            level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-2, 0, -1), center.offset(2, 2, 4)))
            level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        return center;
    }

    private String blockAt(BlockPos pos) {
        return level.getBlockState(pos).getBlock().getName().getString();
    }

    private void digStep() throws Exception {
        pulse();
        int air = 0;
        for (BlockPos p : BlockPos.betweenClosed(digFloor.offset(-2, -4, -2), digFloor.offset(2, -1, 2)))
            if (level.getBlockState(p).isAir()) air++;
        if (air >= 3) {
            passed++;
            System.out.println("VERIFY PASS executor-dig :: a real staircase (" + air + " air blocks below her) in " + pulses + "s");
            finish();
            return;
        }
        if (pulses == 1 || pulses % 5 == 0)
            System.out.println("VERIFY dig t=" + pulses + "s " + where() + " dig=[" + mind("DigResult") + "]");
        if (pulses >= 40) {
            failed++;
            System.out.println("VERIFY FAIL executor-dig :: no staircase after 40s; " + where()
                + " dig=[" + mind("DigResult") + "]");
            finish();
        }
    }

    /** Exactly what the live heartbeat does once per second, with no owner online. */
    private void pulse() throws Exception {
        invokeStatic("MaidFieldwork", "tick",
            new Class<?>[]{maidClass(), net.minecraft.server.level.ServerPlayer.class, boolean.class}, maid, null, true);
        invokeStatic("MaidDig", "tick",
            new Class<?>[]{maidClass(), net.minecraft.server.level.ServerPlayer.class}, maid, null);
        invokeStatic("MaidHunt", "tick",
            new Class<?>[]{maidClass(), net.minecraft.server.level.ServerPlayer.class}, maid, null);
    }

    private String where() throws Exception {
        net.minecraft.world.entity.Mob entity = (net.minecraft.world.entity.Mob) maid;
        return "pos=" + entity.blockPosition() + " gap=" + Math.round(entity.getEyePosition()
            .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(gatherStone == null ? entity.blockPosition() : gatherStone)) * 10) / 10.0
            + " active=" + invokeStatic("MaidFieldwork", "active", new Class<?>[]{maidClass()}, maid)
            + " nav=" + entity.getNavigation().isInProgress();
    }

    private String mind(String key) throws Exception {
        Object mind = invokeStatic("MaidBridge", "mind", new Class<?>[]{maidClass()}, maid);
        Object value = callNamed(mind, "getString", "m_128461_", key);
        return value == null ? "" : value.toString();
    }

    /** Every result is reported before the server stops, so the log is the evidence. */
    private void finish() {
        phase = 0;
        System.out.println("===== AGNES VERIFY END passed=" + passed + " failed=" + failed + " =====");
        System.out.flush();
        if (server != null) server.halt(false);
    }

    // ---------------------------------------------------------------- individual checks

    /** Both native bubble kinds must reach a real maid, and must not pile up. */
    private String bubbles(ServerLevel level, Object maid) throws Exception {
        Object manager = callNamed(maid, "getChatBubbleManager", "m_269533_");
        if (manager == null) return "maid has no chat bubble manager";
        int before = bubbleCount(manager);
        Object spoke = invokeStatic("MaidBubble", "speak", new Class<?>[]{maidClass(), String.class}, maid, "验证：我能自己开口说话。");
        Object thought = invokeStatic("MaidBubble", "think", new Class<?>[]{maidClass(), String.class}, maid, "验证：这是我在想的事情。");
        int after = bubbleCount(manager);
        if (!Boolean.TRUE.equals(spoke)) return "spoken bubble refused";
        if (!Boolean.TRUE.equals(thought)) return "thinking bubble refused";
        if (after <= before) return "no bubble reached the maid: " + before + " -> " + after;
        if (after > 6) return "bubbles piled up: " + after;
        invokeStatic("MaidBubble", "clearThinking", new Class<?>[]{maidClass()}, maid);
        return null;
    }

    /** Personality defaults, pet name, affection, and the chatter guards. */
    private String personality(ServerLevel level, Object maid) throws Exception {
        long now = level.getGameTime();
        Object mind = invokeStatic("MaidBridge", "mind", new Class<?>[]{maidClass()}, maid);
        Object p = invokeStatic("MaidPersonality", "data",
            new Class<?>[]{nbtClass(), java.util.UUID.class, long.class}, mind, uuidOf(maid), now);
        if (!Boolean.TRUE.equals(callNamed(p, "getBoolean", "m_128471_", "Chatter"))) return "proactive chat should default on";
        if (!"刚认识".equals(invokeStatic("MaidPersonality", "affectionName", new Class<?>[]{int.class}, 0)))
            return "wrong starter relationship label";
        if (!"亲近".equals(invokeStatic("MaidPersonality", "affectionName", new Class<?>[]{int.class}, 100)))
            return "wrong maximum relationship label";
        if (!"".equals(invokeStatic("MaidPersonality", "petName", new Class<?>[]{String.class}, "今天天气不错")))
            return "an ordinary sentence produced a pet name";
        invokeStatic("MaidPersonality", "localChat", new Class<?>[]{nbtClass(), String.class, long.class}, p, "以后叫我小少爷", now);
        if (!"小少爷".equals(callNamed(p, "getString", "m_128461_", "CallOwner"))) return "pet name not learned";
        int before = ((Number) callNamed(p, "getInt", "m_128459_", "Affection")).intValue();
        invokeStatic("MaidPersonality", "ownerSpoke", new Class<?>[]{nbtClass(), long.class}, p, now + 3000);
        int after = ((Number) callNamed(p, "getInt", "m_128459_", "Affection")).intValue();
        if (after <= before) return "affection did not move after the owner spoke";
        Object first = invokeStatic("MaidPersonality", "acceptLine",
            new Class<?>[]{nbtClass(), String.class, long.class, boolean.class}, p, "我在看周围的情况。", now, true);
        Object second = invokeStatic("MaidPersonality", "acceptLine",
            new Class<?>[]{nbtClass(), String.class, long.class, boolean.class}, p, "我在看周围的情况。", now + 1, true);
        if (first == null || ((String) first).isBlank()) return "a fresh line was rejected";
        if (second != null && !((String) second).isBlank()) return "the same line was accepted twice";
        Object report = invokeStatic("MaidPersonality", "acceptLine",
            new Class<?>[]{nbtClass(), String.class, long.class, boolean.class}, p, "我已经完成采集了", now + 5000, true);
        if (report != null && !((String) report).isBlank()) return "an action-report line was accepted as chatter";
        return null;
    }

    /** Landmarks are stored, listed, resolved by id, and verified against the real world. */
    private String landmarks(ServerLevel level, Object maid) throws Exception {
        BlockPos pos = blockPosOf(maid);
        Object mind = invokeStatic("MaidBridge", "mind", new Class<?>[]{maidClass()}, maid);
        Class<?> blockPos = BlockPos.class;
        Object recorded = invokeStatic("MaidLandmarks", "remember",
            new Class<?>[]{maidClass(), String.class, blockPos, String.class}, maid, "water", pos, null);
        if (!Boolean.TRUE.equals(recorded)) return "landmark was not recorded";
        if ((int) invokeStatic("MaidLandmarks", "count", new Class<?>[]{maidClass()}, maid) != 1)
            return "expected one landmark";
        Object list = invokeStatic("MaidLandmarks", "describe", new Class<?>[]{maidClass(), int.class}, maid, 8);
        if (((com.google.gson.JsonArray) list).size() != 1) return "landmark was not listed";
        String id = ((com.google.gson.JsonArray) list).get(0).getAsJsonObject().get("target_id").getAsString();
        if (!id.equals("landmark:0")) return "unexpected landmark id " + id;
        if (invokeStatic("MaidLandmarks", "position", new Class<?>[]{maidClass(), int.class}, maid, 0) == null)
            return "landmark position unresolved";
        if ((int) invokeStatic("MaidLandmarks", "parseIndex", new Class<?>[]{String.class}, "landmark:3") != 3)
            return "landmark id parsing wrong";
        // Real water: the remembered place must count as still present.
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -1, -2), pos.offset(2, -1, 2)))
            level.setBlockAndUpdate(p, Blocks.WATER.defaultBlockState());
        Object water = invokeStatic("MaidLandmarks", "stillThere",
            new Class<?>[]{maidClass(), String.class, blockPos}, maid, "water", pos);
        if (!Boolean.TRUE.equals(water)) return "water landmark not confirmed despite real water";
        Object tableAbsent = invokeStatic("MaidLandmarks", "stillThere",
            new Class<?>[]{maidClass(), String.class, blockPos}, maid, "table", pos);
        if (Boolean.TRUE.equals(tableAbsent)) return "a crafting table was reported with none nearby";
        level.setBlockAndUpdate(pos.offset(2, 0, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
        Object table = invokeStatic("MaidLandmarks", "stillThere",
            new Class<?>[]{maidClass(), String.class, blockPos}, maid, "table", pos);
        if (!Boolean.TRUE.equals(table)) return "crafting table landmark not confirmed";
        return null;
    }

    /** The sight scan must read the real world, including blocks placed next to her. */
    private String sight(ServerLevel level, Object maid) throws Exception {
        BlockPos pos = blockPosOf(maid);
        level.setBlockAndUpdate(pos.offset(2, 0, 0), Blocks.CRAFTING_TABLE.defaultBlockState());
        level.setBlockAndUpdate(pos.offset(-2, 0, 0), Blocks.FURNACE.defaultBlockState());
        level.setBlockAndUpdate(pos.offset(0, 2, -2), Blocks.OAK_LOG.defaultBlockState());
        JsonObject sight = (JsonObject) invokeStatic("MaidSight", "describe", new Class<?>[]{maidClass()}, maid);
        for (String key : new String[]{"forward_ray", "visible_columns", "walkable_ground_ahead", "nearby_notable_blocks"})
            if (!sight.has(key)) return "sight has no " + key;
        boolean table = false, furnace = false;
        for (var element : sight.getAsJsonArray("nearby_notable_blocks")) {
            String block = element.getAsJsonObject().get("block").getAsString();
            if (block.contains("crafting_table")) table = true;
            if (block.contains("furnace")) furnace = true;
        }
        if (!table) return "sight missed a crafting table two blocks away";
        if (!furnace) return "sight missed a furnace two blocks away";
        for (int i = 0; i < 8; i++) invokeStatic("MaidSight", "describe", new Class<?>[]{maidClass()}, maid);
        return null;
    }

    /** Hunting must ignore pets and maids, and offer real wild animals. */
    private String hunting(ServerLevel level, Object maid) throws Exception {
        LivingEntity cow = EntityType.COW.create(level);
        if (cow == null) return "could not create a cow";
        cow.moveTo(((net.minecraft.world.entity.Entity) maid).getX() + 3, ((net.minecraft.world.entity.Entity) maid).getY(), ((net.minecraft.world.entity.Entity) maid).getZ(), 0f, 0f);
        level.addFreshEntity(cow);
        Class<?> living = LivingEntity.class;
        Object wild = invokeStatic("MaidHunt", "huntable", new Class<?>[]{maidClass(), living}, maid, cow);
        if (!Boolean.TRUE.equals(wild)) return "a plain wild cow must be huntable";
        cow.setCustomName(net.minecraft.network.chat.Component.literal("小牛"));
        Object named = invokeStatic("MaidHunt", "huntable", new Class<?>[]{maidClass(), living}, maid, cow);
        if (Boolean.TRUE.equals(named)) return "a named animal must never be hunted";
        Object self = invokeStatic("MaidHunt", "huntable", new Class<?>[]{maidClass(), living}, maid, maid);
        if (Boolean.TRUE.equals(self)) return "a maid must never be hunted";
        cow.setCustomName(null);
        Object list = invokeStatic("MaidHunt", "describe", new Class<?>[]{maidClass(), String.class}, maid, "animal");
        if (((com.google.gson.JsonArray) list).isEmpty()) return "huntable list is empty with a cow present";

        // The real check: starting a hunt next to a cow must actually damage it. This is the part that
        // was broken -- she walked up to the animal and never swung, so nothing happened.
        Object started = invokeStatic("MaidHunt", "start",
            new Class<?>[]{maidClass(), String.class, String.class, boolean.class}, maid, "", "animal", false);
        if (!(started instanceof String text) || text.startsWith("未启动")) return "hunt refused to start: " + started;
        float before = cow.getHealth();
        for (int i = 0; i < 6 && cow.isAlive(); i++) {
            invokeStatic("MaidHunt", "tick",
                new Class<?>[]{maidClass(), net.minecraft.server.level.ServerPlayer.class}, maid, null);
        }
        if (cow.isAlive() && cow.getHealth() >= before) {
            double gap = Math.sqrt(((net.minecraft.world.entity.Entity) maid).getEyePosition()
                .distanceToSqr(cow.getBoundingBox().getCenter()));
            return "she started a hunt but never damaged the cow (health " + before + " -> " + cow.getHealth()
                + ", gap " + Math.round(gap * 10) / 10.0 + " blocks)";
        }
        // Leave no hunt running: a stale hunt kept ticking during the executor check and cancelled the
        // gather it was trying to verify, which looked exactly like "gathering does not work".
        invokeStatic("MaidHunt", "cancel", new Class<?>[]{maidClass(), String.class}, maid, "check finished");
        return null;
    }

    /** Blueprint data must be well formed and its plan-step actions supported by the executor. */
    private String blueprints(ServerLevel level, Object maid) throws Exception {
        BlockPos origin = blockPosOf(maid);
        Class<?> blockPos = BlockPos.class;
        Class<?> item = net.minecraft.world.item.Item.class;
        String[] kinds = {"hut", "cottage", "farm"};
        Object[] materials = {net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.COBBLESTONE, net.minecraft.world.item.Items.OAK_FENCE};
        for (int i = 0; i < kinds.length; i++) {
            Object plan = invokeStatic("MaidBuilder", "blueprint", new Class<?>[]{String.class, blockPos, item}, kinds[i], origin, materials[i]);
            if (plan == null) return kinds[i] + ": blueprint missing";
            var pieces = (java.util.List<?>) callNamed(plan, "pieces", "pieces");
            if (pieces.isEmpty()) return kinds[i] + ": blueprint is empty";
            int sizeX = ((Number) callNamed(plan, "sizeX", "sizeX")).intValue(), sizeZ = ((Number) callNamed(plan, "sizeZ", "sizeZ")).intValue();
            HashSet<BlockPos> seen = new HashSet<>();
            int solid = 0, doors = 0, torches = 0;
            for (Object piece : pieces) {
                BlockPos at = (BlockPos) callNamed(piece, "pos", "pos");
                if (!seen.add(at)) return kinds[i] + ": duplicate cell " + at;
                int dx = at.getX() - origin.getX(), dz = at.getZ() - origin.getZ();
                if (dx < 0 || dz < 0 || dx >= sizeX || dz >= sizeZ) return kinds[i] + ": piece outside footprint " + at;
                Object mat = callNamed(piece, "item", "item");
                if (mat == net.minecraft.world.item.Items.COBBLESTONE) solid++;
                if (mat == net.minecraft.world.item.Items.OAK_DOOR) doors++;
                if (mat == net.minecraft.world.item.Items.TORCH) torches++;
            }
            if (kinds[i].equals("cottage") && (solid < 150 || doors != 1 || torches != 2))
                return "cottage data wrong: solid=" + solid + " doors=" + doors + " torches=" + torches;
            if (kinds[i].equals("hut") && (solid < 70 || doors != 1 || torches != 1))
                return "hut data wrong: solid=" + solid + " doors=" + doors + " torches=" + torches;
            String siteId = (String) invokeStatic("MaidBuilder", "siteIdFor", new Class<?>[]{String.class, blockPos}, kinds[i], origin);
            if (!siteId.startsWith(kinds[i] + ":")) return "site id lost its blueprint kind: " + siteId;
        }
        return null;
    }

    /**
     * Crafting: give her planks and demand a crafting table, then look in her backpack.
     *
     * The earlier checks prove the data is well formed, which is exactly why "the plan was fine but
     * nothing happened" got through: a hunt that never swings is data-correct and functionally broken.
     * These checks exist so an action that does not really act fails the harness, not the player.
     * The walking and mining halves of this live in gatherStep/digStep, which need real ticks.
     */
    private String crafting(Object maid) throws Exception {
        give(maid, net.minecraft.world.item.Items.OAK_PLANKS, 4);
        Object made = invokeStatic("MaidSurvival", "craft",
            new Class<?>[]{maidClass(), String.class}, maid, "minecraft:crafting_table");
        if (!(made instanceof String craftText) || !craftText.startsWith("已实际合成"))
            return "crafting did not produce an item: " + made;
        if (countOf(maid, net.minecraft.world.item.Items.CRAFTING_TABLE) <= 0)
            return "crafting reported success but no crafting table is in her backpack";
        return null;
    }

    /**
     * The point of the new architecture: the maid's own agent must really see the companion's tool.
     * A tool that silently fails to register looks exactly like a model that ignores it, and that
     * ambiguity is what made earlier failures so hard to read.
     */
    private String agentTools(ServerLevel level, Object maid) throws Exception {
        Class<?> register = Class.forName("com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister");
        java.util.Map<?, ?> tools = (java.util.Map<?, ?>) register.getMethod("getAllTools").invoke(null);
        if (!tools.containsKey("maid_situation"))
            return "maid_situation is missing; the maid agent only has " + tools.keySet();
        Object tool = tools.get("maid_situation");
        if (tool == null) return "maid_situation is registered as null";

        // This report is exactly what the model gets back, so it must render and carry real state.
        String report = (String) invokeStatic("MaidSituationTool", "report",
            new Class<?>[]{maidClass(), String.class}, maid, "all");
        if (report == null || report.isBlank()) return "the situation report came back empty";
        for (String field : new String[]{"work_mode=", "running_action=", "position=",
                "companion_world_edits_allowed=", "farm_work_available=", "remembered_places="}) {
            if (!report.contains(field)) return "the situation report is missing " + field + ": " + report;
        }

        // The schema the model is offered must build as well, or the tool can never be called.
        Class<?> objectParameter = Class.forName(
            "com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter");
        Object root = objectParameter.getMethod("create").invoke(null);
        Object schema = tool.getClass().getMethod("parameters", objectParameter, maidClass())
            .invoke(tool, root, maid);
        if (schema == null) return "the situation tool offers the model no parameter schema";

        System.out.println("VERIFY situation :: " + report);
        return null;
    }

    /** Agnes must only be offered farming when TLM's real farm task can work the nearby blocks. */
    private String farmingAvailability(ServerLevel level, Object maid) throws Exception {
        // Earlier sight checks intentionally spawn her thirty blocks above the surface. Farming
        // must be tested from the work position, or TLM's own search radius correctly sees no field.
        ((net.minecraft.world.entity.Entity) maid).moveTo(
            arena.getX() + 0.5, arena.getY(), arena.getZ() + 0.5, 0f, 0f);
        give(maid, net.minecraft.world.item.Items.WHEAT_SEEDS, 1);
        give(maid, net.minecraft.world.item.Items.BREAD, 6);
        invokeStatic("MaidSurvival", "invalidateFarmProbe", new Class<?>[]{maidClass()}, maid);
        if (hasSurvivalTask(maid, "farm")) return "farm was offered even though no nearby field or crop exists";

        net.minecraft.nbt.CompoundTag mind =
            (net.minecraft.nbt.CompoundTag) invokeStatic("MaidBridge", "mind", new Class<?>[]{maidClass()}, maid);
        mind.putBoolean("Autonomy", true);
        String idle = (String) invokeStatic("MaidSurvival", "idleWork",
            new Class<?>[]{maidClass(), net.minecraft.server.level.ServerPlayer.class}, maid, null);
        if (idle.contains("照看") || idle.contains("农田")) return "idle work still chose farming without a real field: " + idle;
        if (hasSurvivalTask(maid, "farm")) return "farm reappeared without a field after idle work";

        BlockPos ground = arena.offset(3, -1, 0);
        var original = level.getBlockState(ground);
        level.setBlockAndUpdate(ground, Blocks.FARMLAND.defaultBlockState());
        invokeStatic("MaidSurvival", "invalidateFarmProbe", new Class<?>[]{maidClass()}, maid);
        boolean offeredWithField = hasSurvivalTask(maid, "farm");
        level.setBlockAndUpdate(ground, original);
        invokeStatic("MaidSurvival", "invalidateFarmProbe", new Class<?>[]{maidClass()}, maid);
        mind.putBoolean("Autonomy", false);
        if (!offeredWithField) return "farm was not offered with reachable farmland and an actual seed";
        return null;
    }

    private boolean hasSurvivalTask(Object maid, String path) throws Exception {
        var tasks = (java.util.List<?>) invokeStatic("MaidBridge", "availableSurvivalTasks",
            new Class<?>[]{maidClass()}, maid);
        for (Object task : tasks) {
            Object uid = call(task, "getUid");
            if (uid instanceof net.minecraft.resources.ResourceLocation id && id.getPath().equals(path)) return true;
        }
        return false;
    }

    private void give(Object maid, net.minecraft.world.item.Item item, int count) {
        var inventory = ((com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid) maid).getAvailableBackpackInv();
        net.minecraft.world.item.ItemStack remainder = new net.minecraft.world.item.ItemStack(item, count);
        for (int slot = 0; slot < inventory.getSlots() && !remainder.isEmpty(); slot++)
            remainder = inventory.insertItem(slot, remainder, false);
        if (!remainder.isEmpty()) throw new IllegalStateException("could not hand her " + item);
    }

    private int countOf(Object maid, net.minecraft.world.item.Item item) {
        var inventory = ((com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid) maid).getAvailableBackpackInv();
        int total = 0;
        for (int i = 0; i < inventory.getSlots(); i++) {
            net.minecraft.world.item.ItemStack stack = inventory.getStackInSlot(i);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    // ---------------------------------------------------------------- plumbing

    private interface Check { String run() throws Exception; }

    private void check(String name, Check check) {
        try {
            String failure = check.run();
            if (failure == null) { passed++; System.out.println("VERIFY PASS " + name); }
            else { failed++; System.out.println("VERIFY FAIL " + name + " :: " + failure); }
        } catch (Throwable error) {
            failed++;
            StringBuilder text = new StringBuilder(error.getClass().getSimpleName() + ": " + error.getMessage());
            for (StackTraceElement frame : error.getStackTrace()) {
                if (frame.getClassName().startsWith("com.agnes")) { text.append(" at ").append(frame); break; }
            }
            System.out.println("VERIFY THREW " + name + " :: " + text);
        }
    }

    private static Class<?> maidClass() throws ClassNotFoundException {
        return Class.forName("com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid");
    }
    private static Class<?> nbtClass() throws ClassNotFoundException { return Class.forName("net.minecraft.nbt.CompoundTag"); }

    private Object spawnMaid(ServerLevel level) {
        try {
            Class<?> init = Class.forName("com.github.tartaricacid.touhoulittlemaid.init.InitEntities");
            Object holder = init.getField("MAID").get(null);
            Object type = holder.getClass().getMethod("get").invoke(holder);
            Object maid = ((net.minecraft.world.entity.EntityType<?>) type).create(level);
            if (maid == null) { System.out.println("VERIFY spawn: EntityType.create returned null"); return null; }
            // Stand on the real surface: the test world may be flat or normal.
            int surface = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, 8, 8);
            // Stand in open air: below the surface there is no view to test the sight scan with.
            int groundY = surface + 30;
            net.minecraft.world.entity.Entity entity = (net.minecraft.world.entity.Entity) maid;
            entity.moveTo(8.5, groundY, 8.5, 0f, 0f);
            boolean added = level.addFreshEntity(entity);
            System.out.println("VERIFY spawn: groundY=" + groundY + " added=" + added + " alive=" + entity.isAlive());
            return maid;
        } catch (Throwable error) {
            System.out.println("VERIFY spawn threw " + error.getClass().getName() + ": " + error.getMessage());
            for (StackTraceElement frame : error.getStackTrace()) {
                if (frame.getClassName().startsWith("com.agnes")) { System.out.println("   at " + frame); break; }
            }
            return null;
        }
    }

    private static java.util.UUID uuidOf(Object maid) throws Exception {
        return (java.util.UUID) callNamed(maid, "getUUID", "m_20148_");
    }
    private static BlockPos blockPosOf(Object maid) throws Exception {
        return (BlockPos) callNamed(maid, "blockPosition", "m_20183_");
    }
    private static int bubbleCount(Object manager) throws Exception {
        Object collection = callNamed(manager, "getChatBubbleDataCollection", "m_20148_");
        return ((Number) callNamed(collection, "size", "size")).intValue();
    }

    /**
     * Calls a method by its Mojang name, falling back to the SRG name.
     * A production runtime uses SRG names, so reflection written with Mojang names alone fails there.
     */
    private static Object callNamed(Object target, String official, String srg, Object... args) throws Exception {
        Method method = find(target.getClass(), official, args.length);
        if (method == null) method = find(target.getClass(), srg, args.length);
        if (method == null) throw new NoSuchMethodException(target.getClass().getName() + "." + official + "/" + srg + " arity " + args.length);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static Method find(Class<?> owner, String name, int arity) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == arity) return method;
            }
        }
        return null;
    }

    private static Object call(Object target, String name, Class<?>... types) throws Exception {
        Method method = target.getClass().getMethod(name, types);
        return method.invoke(target);
    }
    private static Object call(Object target, String name, Class<?> type, Object value) throws Exception {
        Method method = target.getClass().getMethod(name, type);
        return method.invoke(target, value);
    }
    private static Object call(Object target, String name) throws Exception {
        Method method = target.getClass().getMethod(name);
        return method.invoke(target);
    }

    private static Object invokeStatic(String simpleName, String name, Class<?>[] types, Object... args) throws Exception {
        Class<?> owner = Class.forName(P + simpleName);
        Method method = owner.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            // The real cause is what matters for a failure report; an InvocationTargetException
            // hides it and made an earlier diagnosis much slower than it needed to be.
            Throwable cause = wrapped.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException(name + " threw " + cause, cause);
        }
    }
}
