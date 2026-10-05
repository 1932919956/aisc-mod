package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.github.tartaricacid.touhoulittlemaid.api.task.IFarmTask;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.*;

/** Local survival and inventory operations. Never use the owner's inventory or create free items. */
final class MaidSurvival {
    private MaidSurvival() {}
    private record FarmProbe(Level level, BlockPos center, long checkedAt, boolean available) {}
    private static final Map<UUID, FarmProbe> FARM_PROBES = new HashMap<>();

    static boolean careEnabled(EntityMaid maid) {
        return !MaidBridge.mind(maid).contains("AutoCare") || MaidBridge.mind(maid).getBoolean("AutoCare");
    }

    static void tick(EntityMaid maid, ServerPlayer player) {
        CompoundTag data = MaidBridge.mind(maid);
        if (!careEnabled(maid)) { data.putBoolean("Recovering", false); return; }
        long now = maid.level().getGameTime();
        if (now >= data.getLong("NextEquipmentCheck")) {
            data.putLong("NextEquipmentCheck", now + 200);
            String equipped = prepare(maid);
            if (!equipped.isBlank()) data.putString("CareResult", equipped);
        }
        if (maid.getHealth() <= maid.getMaxHealth() * 0.35f && !data.getBoolean("Recovering")) {
            data.putBoolean("Recovering", true);
            data.putString("CareResult", "受伤了，暂停工作回到主人附近修整");
            maid.setTask(TaskManager.getIdleTask());
            maid.setOrderedToSit(false); maid.setHomeModeEnable(false); maid.clearRestriction();
            tell(maid, player, "我受伤了，先停下工作，回你附近修整。" + (foodCount(maid) == 0 ? "我的背包里没有合适的食物了。" : ""));
        }
        if (data.getBoolean("Recovering")) {
            if (maid.getHealth() >= maid.getMaxHealth() * 0.7f) {
                data.putBoolean("Recovering", false);
                data.putString("CareResult", "生命已恢复到七成，可以重新安排活动");
                data.putLong("NextThink", now + 100);
                tell(maid, player, "恢复得差不多了，我可以继续活动了。");
            } else {
                maid.setTarget(null);
                if (maid.getTask() != TaskManager.getIdleTask()) maid.setTask(TaskManager.getIdleTask());
                // A short escape path helps the native movement brain leave an immediate threat.
                if (now >= data.getLong("NextRetreat")) {
                    data.putLong("NextRetreat", now + 60);
                    Monster enemy = maid.level().getEntitiesOfClass(Monster.class, maid.getBoundingBox().inflate(8))
                        .stream().filter(Monster::isAlive).min(Comparator.comparingDouble(maid::distanceToSqr)).orElse(null);
                    if (enemy != null) {
                        Vec3 away = DefaultRandomPos.getPosAway(maid, 8, 3, enemy.position());
                        if (away != null) {
                            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(away, 1.15f, 1));
                            maid.getNavigation().moveTo(away.x, away.y, away.z, 1.15);
                        }
                    }
                }
            }
        }
    }

    private static boolean plainVanilla(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace().equals("minecraft")
            && (!stack.hasTag() || stack.getTag().getAllKeys().stream().allMatch(k -> k.equals("Damage")));
    }

    static String prepare(EntityMaid maid) {
        List<String> changes = new ArrayList<>();
        var inventory = maid.getAvailableBackpackInv();
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            ItemStack worn = maid.getItemBySlot(slot);
            if (!worn.isEmpty() && (!plainVanilla(worn) || !(worn.getItem() instanceof ArmorItem))) continue;
            int bestIndex = -1;
            double bestScore = armorScore(worn);
            for (int i = 0; i < inventory.getSlots(); i++) {
                ItemStack candidate = inventory.getStackInSlot(i);
                if (!candidate.isEmpty() && plainVanilla(candidate) && candidate.getItem() instanceof ArmorItem armor
                    && armor.getEquipmentSlot() == slot && armorScore(candidate) > bestScore + 0.01) {
                    bestIndex = i; bestScore = armorScore(candidate);
                }
            }
            if (bestIndex >= 0 && moveToEquipment(maid, bestIndex, slot)) changes.add(slot.getName());
        }
        return changes.isEmpty() ? "" : "已从自己的背包整理基础护甲：" + String.join("、", changes);
    }

    private static double armorScore(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof ArmorItem armor)) return -1;
        double remaining = stack.isDamageableItem() ? (double)(stack.getMaxDamage() - stack.getDamageValue()) / stack.getMaxDamage() : 1;
        if (remaining < 0.1) return -0.5;
        return armor.getDefense() * 100 + armor.getToughness() * 10 + remaining;
    }

    static String equip(EntityMaid maid, String itemId, boolean autonomous) {
        var inventory = maid.getAvailableBackpackInv();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty() || !BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) continue;
            EquipmentSlot slot = stack.getItem() instanceof ArmorItem armor ? armor.getEquipmentSlot()
                : stack.getItem() instanceof ShieldItem ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
            ItemStack current = maid.getItemBySlot(slot);
            if (autonomous && (!plainVanilla(stack) || (!current.isEmpty() && !plainVanilla(current))))
                return "自动整理不替换特殊、附魔或自定义装备，请明确指定后再换装";
            if (!current.isEmpty() && EnchantmentHelper.hasBindingCurse(current)) return "原装备有绑定诅咒，不能取下";
            String name = stack.getHoverName().getString();
            return moveToEquipment(maid, i, slot) ? "已装备 " + name + "，原物品已放回自己的背包" : "背包无法容纳换下的物品，没有更换装备";
        }
        return "自己的背包里没有这种物品，未更换装备";
    }

    private static boolean moveToEquipment(EntityMaid maid, int index, EquipmentSlot slot) {
        var inventory = maid.getAvailableBackpackInv();
        List<ItemStack> before = snapshot(inventory), after = copy(before);
        ItemStack chosen = after.get(index).split(1);
        if (chosen.isEmpty()) return false;
        ItemStack old = maid.getItemBySlot(slot).copy();
        if (!put(inventory, after, old)) return false;
        if (!commit(inventory, before, after)) return false;
        maid.setItemSlot(slot, chosen);
        return true;
    }

    static int foodCount(EntityMaid maid) {
        int count = 0;
        var inventory = maid.getAvailableBackpackInv();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack item = inventory.getStackInSlot(i);
            if (item.isEdible() && !Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH).contains(item.getItem())) count += item.getCount();
        }
        return count;
    }

    /**
     * Give her something to do while the configured planning cycle is not running.
     *
     * This keeps her from standing at attention between plans, but it must not seize a strategic
     * choice from Agnes. Farming is therefore never selected as an automatic idle chore; when real
     * farm work is possible, Agnes can choose it from the offered tasks as part of her goal.
     */
    static String idleWork(EntityMaid maid, ServerPlayer player) {
        CompoundTag data = MaidBridge.mind(maid);
        if (!data.getBoolean("Autonomy") || data.getBoolean("Recovering")) return "";
        if (maid.isSleeping() || maid.isOrderedToSit() || maid.getTarget() != null || maid.isPassenger()) return "";
        if (maid.getHealth() < maid.getMaxHealth() * 0.6f) return "";
        if (MaidFieldwork.active(maid) || MaidPlan.active(maid) || MaidWorkshop.active(maid) || MaidBuilder.active(maid)
            || MaidHunt.active(maid) || MaidDig.active(maid) || MaidLandmarks.active(maid)) return "";
        long now = maid.level().getGameTime();

        // Protect the current assignment for a while: constant job switching would look frantic.
        if (!maid.getTask().getUid().equals(TaskManager.getIdleTask().getUid())
            && !data.getBoolean("IdleWork") && now < data.getLong("IdleWorkUntil")) return "";

        String chosen = "";
        String label = "";
        if (foodCount(maid) < 6) {
            // Meat is the best early food, and hunting needs no extra tool: before berries, before fishing.
            String hunt = MaidHunt.foodPlan(maid);
            if (!hunt.isBlank() && MaidHunt.start(maid, "", "animal", true).startsWith("正在追")) {
                data.putBoolean("IdleWork", true);
                data.putString("IdleWorkName", "hunt");
                data.putLong("IdleWorkUntil", now + 6000);
                return hunt;
            }
            if (hasRipeBerriesNearby(maid)) { chosen = "berries"; label = "附近有成熟的甜浆果，先摘一些当食物"; }
            else if (assignable(maid, "fishing")) { chosen = "fishing"; label = "去水边钓鱼，给自己准备口粮"; }
        }
        if (chosen.isEmpty() && assignable(maid, "fishing")) { chosen = "fishing"; label = "在水边安静地钓一会儿鱼"; }
        if (chosen.isEmpty()) {
            String roam = roam(maid, data, now);
            if (!roam.isBlank()) return roam;
        }
        // Nothing productive is possible here, which is honest information for the next plan.
        if (chosen.isEmpty()) {
            if (!maid.getTask().getUid().equals(TaskManager.getIdleTask().getUid()) && data.getBoolean("IdleWork")) {
                maid.setTask(TaskManager.getIdleTask());
                data.putBoolean("IdleWork", false);
                data.remove("IdleWorkName");
                return "这里的活干完了，等下一步安排";
            }
            return "";
        }
        if (chosen.equals(data.getString("IdleWorkName"))) { data.putLong("IdleWorkUntil", now + 6000); return ""; }

        boolean switched = false;
        if (chosen.equals("berries")) switched = MaidFieldwork.startKind(maid, "berries", true).startsWith("正在走向");
        else {
            var task = nativeTask(maid, chosen);
            if (task == null) return "";
            switched = MaidBridge.switchTask(maid, task.getUid().toString()).startsWith("已选择工作");
        }
        if (!switched) return "";
        data.putBoolean("IdleWork", true);
        data.putString("IdleWorkName", chosen);
        data.putLong("IdleWorkUntil", now + 6000);
        return label;
    }

    /**
     * Find a native task by its short name. The resource namespace differs between maid versions, so
     * the lookup walks the tasks the maid can actually see instead of guessing an id.
     */
    private static com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask nativeTask(EntityMaid maid, String shortName) {
        for (var task : MaidBridge.availableSurvivalTasks(maid)) {
            ResourceLocation key = task.getUid();
            if (key.getPath().equals(shortName) && !blocked(maid, key.toString())
                && (key.getNamespace().equals("touhou_little_maid") || key.getNamespace().equals("minecraft"))) return task;
        }
        return null;
    }

    /** True when such a native task exists and its own conditions allow it to run now. */
    private static boolean assignable(EntityMaid maid, String shortName) {
        return nativeTask(maid, shortName) != null;
    }

    /**
     * A farm task is useful only when its own IFarmTask rules find something real to do in the
     * maid's work radius: a harvestable crop, or suitable soil with a seed she actually carries.
     * TLM's isEnable() only says the task is unlocked; it does not mean a field exists.
     */
    static boolean farmWorkAvailable(EntityMaid maid, IFarmTask farmTask) {
        if (maid == null || farmTask == null || maid.level().isClientSide) return false;
        AABB search;
        try {
            search = farmTask.searchDimension(maid);
        } catch (RuntimeException unsupported) {
            return false;
        }
        if (search == null) return false;
        BlockPos center = BlockPos.containing(
            (search.minX + search.maxX) * 0.5,
            (search.minY + search.maxY) * 0.5,
            (search.minZ + search.maxZ) * 0.5
        );
        long now = maid.level().getGameTime();
        FarmProbe cached = FARM_PROBES.get(maid.getUUID());
        if (cached != null && cached.level() == maid.level() && cached.center().equals(center)
            && now >= cached.checkedAt() && now - cached.checkedAt() < 20) {
            return cached.available();
        }

        boolean available = scanFarmWork(maid, farmTask, search, center);
        FARM_PROBES.put(maid.getUUID(), new FarmProbe(maid.level(), center, now, available));
        if (FARM_PROBES.size() > 256) FARM_PROBES.clear();
        return available;
    }

    /** Explicit invalidation is used after a world edit and by the runtime test harness. */
    static void invalidateFarmProbe(EntityMaid maid) {
        if (maid != null) FARM_PROBES.remove(maid.getUUID());
    }

    static void clearFarmProbes() { FARM_PROBES.clear(); }

    private static boolean scanFarmWork(EntityMaid maid, IFarmTask farmTask, AABB search, BlockPos center) {
        int localX = maid.blockPosition().getX(), localY = maid.blockPosition().getY(), localZ = maid.blockPosition().getZ();
        // TLM 1.5.3 may return a stale schedule-centered AABB (often around world spawn after
        // free-mode movement). The actual farm task evaluates blocks near the maid, so use her
        // current position as the authoritative center for the availability probe.
        int minX = localX - 64;
        int maxX = localX + 64;
        // TLM's farm behavior searches at most two blocks above/below its work point; ground targets
        // are one block below that point. Keep this scan aligned with its real vertical search.
        int minY = localY - 3;
        int maxY = localY + 1;
        int minZ = localZ - 64;
        int maxZ = localZ + 64;
        long volume = (long)(maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume <= 0 || volume > 100_000) return false;

        List<ItemStack> seeds = new ArrayList<>();
        var inventory = maid.getAvailableInv(true);
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty() && farmTask.isSeed(stack)) seeds.add(stack);
        }

        try {
            for (BlockPos ground : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
                // searchDimension is already the task's authoritative work area. Applying the
                // maid's separate schedule restriction here can reject a reachable field after
                // free-mode movement changes its anchor, which made valid farmland disappear
                // from the autonomous task list.
                if (!maid.level().isLoaded(ground)) continue;
                BlockPos cropPos = ground.above();
                if (!maid.level().isLoaded(cropPos)) continue;
                var groundState = maid.level().getBlockState(ground);
                var cropState = maid.level().getBlockState(cropPos);
                if (farmTask.canHarvest(maid, cropPos, cropState)) return true;
                if (!seeds.isEmpty()) {
                    for (ItemStack seed : seeds) {
                        if (farmTask.canPlant(maid, ground, groundState, seed)) return true;
                        // TLM 1.5.3 can report a vanilla farm task as unavailable while its
                        // search point is being rebuilt, even though the ordinary wheat rule is
                        // valid. Keep the fallback deliberately narrow: no modded crops, no
                        // occupied soil, and the seed must really be in the maid's inventory.
                        if (seed.is(Items.WHEAT_SEEDS) && groundState.is(Blocks.FARMLAND) && cropState.isAir()) return true;
                    }
                }
            }
        } catch (RuntimeException unsupportedCrop) {
            return false;
        }
        return false;
    }

    /**
     * With the basics covered, go and see somewhere new.
     *
     * This is the part that stops her from circling the same clearing forever: reaching a scout point
     * also moves her free-roaming anchor (MaidFieldwork.finish does that), so she naturally settles
     * where she walked to and works there, exactly like a player who wanders off and builds a new
     * camp. It only starts when she is fed, has wood and stone, and has not been out for a while.
     */
    private static String roam(EntityMaid maid, CompoundTag data, long now) {
        if (now < data.getLong("NextRoam")) return "";
        if (foodCount(maid) < 8) return "";
        if (maid.getHealth() < maid.getMaxHealth() * 0.75f) return "";
        int cobble = countOf(maid, Items.COBBLESTONE) + countOf(maid, Items.COBBLED_DEEPSLATE) + countOf(maid, Items.STONE);
        if (countOf(maid, Items.OAK_LOG) < 4 || cobble < 8) return "";
        // A lit base and enough supplies are worth more than the next hill at night.
        long time = Math.floorMod(maid.level().getDayTime(), 24000L);
        if (time >= 12000 && time < 23000) return "";

        JsonArray scouts = new JsonArray();
        try {
            for (var spot : MaidSurvey.spots(maid)) if (spot.kind().equals("scout")) scouts.add("scout:" + spot.pos().toShortString());
        } catch (RuntimeException unsupported) { return ""; }
        if (scouts.isEmpty()) return "";
        String id = scouts.get(maid.getRandom().nextInt(scouts.size())).getAsString();
        if (id == null || id.isBlank()) return "";
        String result = MaidFieldwork.explore(maid, id, true);
        if (!result.startsWith("正在走向")) return "";
        data.putLong("NextRoam", now + 24000 * 2);
        return "想吃的东西和材料都够一阵子了，我往远处走走，看看那边有什么";
    }

    private static int countOf(EntityMaid maid, Item item) {
        int count = 0;
        var inventory = maid.getAvailableBackpackInv();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty() && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    /** A ripe sweet berry bush she could walk to, so she does not idle beside an empty bush. */
    private static boolean hasRipeBerriesNearby(EntityMaid maid) {
        BlockPos origin = maid.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-8, -3, -8), origin.offset(8, 3, 8))) {
            if (!maid.level().isLoaded(pos)) continue;
            var state = maid.level().getBlockState(pos);
            if (state.is(Blocks.SWEET_BERRY_BUSH)
                && state.getValue(net.minecraft.world.level.block.SweetBerryBushBlock.AGE) == 3) return true;
        }
        return false;
    }

    static JsonObject describe(EntityMaid maid) {
        JsonObject result = new JsonObject();
        result.addProperty("auto_care", careEnabled(maid));
        result.addProperty("recovering", MaidBridge.mind(maid).getBoolean("Recovering"));
        result.addProperty("care_result", MaidBridge.mind(maid).getString("CareResult"));
        result.addProperty("food_in_backpack", foodCount(maid));
        result.addProperty("farm_work_available", farmWorkAvailable(maid));
        result.addProperty("crafting_table_within_four_blocks", hasTable(maid));
        JsonArray equipment = new JsonArray();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack stack = maid.getItemBySlot(slot);
            JsonObject entry = new JsonObject(); entry.addProperty("slot", slot.getName());
            entry.addProperty("item", stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (!stack.isEmpty()) {
                entry.addProperty("name", stack.getHoverName().getString());
                entry.addProperty("remaining_durability", stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : -1);
                entry.addProperty("max_durability", stack.getMaxDamage());
                entry.addProperty("special_or_enchanted", !plainVanilla(stack));
            }
            equipment.add(entry);
        }
        result.add("equipment", equipment);
        JsonArray craft = new JsonArray();
        Set<Item> useful = Set.of(Items.OAK_PLANKS, Items.STICK, Items.CRAFTING_TABLE, Items.BREAD, Items.TORCH,
            Items.WOODEN_PICKAXE, Items.WOODEN_AXE, Items.WOODEN_HOE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.STONE_AXE, Items.IRON_AXE,
            Items.IRON_HOE, Items.FISHING_ROD, Items.IRON_SWORD, Items.SHIELD, Items.IRON_HELMET,
            Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS, Items.FURNACE, Items.STONE_SWORD, Items.STONE_HOE, Items.OAK_DOOR, Items.CHEST, Items.BARREL);
        for (Recipe<?> recipe : maid.level().getRecipeManager().getRecipes().stream()
            .sorted(Comparator.comparing(r -> r.getId().toString())).toList()) {
            if (!ordinary(recipe) || !recipe.getId().getNamespace().equals("minecraft")) continue;
            ItemStack output = recipe.getResultItem(maid.level().registryAccess());
            if (output.isEmpty() || (!useful.contains(output.getItem()) && !output.is(net.minecraft.tags.ItemTags.PLANKS))) continue;
            Attempt attempt = prepareCraft(maid, recipe);
            JsonObject entry = new JsonObject(); entry.addProperty("recipe_id", recipe.getId().toString());
            entry.addProperty("output", BuiltInRegistries.ITEM.getKey(output.getItem()).toString());
            entry.addProperty("count", output.getCount()); entry.addProperty("ready", attempt.error().isEmpty());
            entry.addProperty("reason", attempt.error()); craft.add(entry);
            JsonArray ingredients = new JsonArray();
            for (Ingredient ingredient : recipe.getIngredients()) if (!ingredient.isEmpty()) {
                JsonArray choices = new JsonArray();
                Arrays.stream(ingredient.getItems()).limit(8).forEach(s -> choices.add(BuiltInRegistries.ITEM.getKey(s.getItem()).toString()));
                ingredients.add(choices);
            }
            entry.add("ingredients_per_batch", ingredients);
            if (craft.size() >= 64) break;
        }
        result.add("basic_craft_options", craft);
        return result;
    }

    /** Resolve the registered farm task so state reports and autonomous guards share one test. */
    static boolean farmWorkAvailable(EntityMaid maid) {
        for (IMaidTask task : TaskManager.getTaskIndex()) {
            if (task instanceof IFarmTask farmTask) {
                try {
                    if (task.isEnable(maid) && !task.isHidden(maid)) return farmWorkAvailable(maid, farmTask);
                } catch (RuntimeException ignored) {
                    return false;
                }
            }
        }
        return false;
    }

    static boolean hasTable(EntityMaid maid) {
        for (BlockPos pos : BlockPos.betweenClosed(maid.blockPosition().offset(-4, -2, -4), maid.blockPosition().offset(4, 2, 4))) {
            if (!maid.level().hasChunkAt(pos) || !maid.level().getBlockState(pos).is(Blocks.CRAFTING_TABLE) || pos.distToCenterSqr(maid.position()) > 16) continue;
            var hit = maid.level().clip(new ClipContext(maid.getEyePosition(), Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, maid));
            if (hit.getBlockPos().equals(pos)) return true;
        }
        return false;
    }

    private static boolean ordinary(Recipe<?> recipe) {
        return !recipe.isSpecial() && (recipe.getClass() == ShapedRecipe.class || recipe.getClass() == ShapelessRecipe.class);
    }

    private record Attempt(String error, List<ItemStack> before, List<ItemStack> after, ItemStack result) {}
    private static Attempt failure(String reason) { return new Attempt(reason, List.of(), List.of(), ItemStack.EMPTY); }

    static String craft(EntityMaid maid, String recipeId) {
        ResourceLocation id = ResourceLocation.tryParse(recipeId);
        Recipe<?> recipe = id == null ? null : maid.level().getRecipeManager().byKey(id).orElse(null);
        if (recipe == null) return "未找到这个配方，没有消耗物品";
        Attempt attempt = prepareCraft(maid, recipe);
        if (!attempt.error().isEmpty()) return attempt.error() + "；没有消耗物品";
        if (!commit(maid.getAvailableBackpackInv(), attempt.before(), attempt.after())) return "背包内容已变化，本次合成取消";
        return "已实际合成 " + attempt.result().getHoverName().getString() + " ×" + attempt.result().getCount() + "，放入自己的背包";
    }

    private static Attempt prepareCraft(EntityMaid maid, Recipe<?> raw) {
        if (!ordinary(raw)) return failure("只支持普通有序/无序合成；机器、仪式和动态配方尚未支持");
        try {
            @SuppressWarnings("unchecked") Recipe<net.minecraft.world.inventory.CraftingContainer> recipe = (Recipe<net.minecraft.world.inventory.CraftingContainer>)raw;
            var ingredients = recipe.getIngredients();
            if (ingredients.isEmpty() || ingredients.size() > 9) return failure("配方格数不受支持");
            int width = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : ingredients.size() <= 4 ? 2 : 3;
            int height = recipe instanceof ShapedRecipe shaped ? shaped.getHeight() : width;
            if (width > 3 || height > 3) return failure("不能使用超过 3×3 的配方");
            int gridSize = Math.max(width, height) > 2 ? 3 : 2;
            if (gridSize == 3 && !hasTable(maid)) return failure("需要身边四格内、可见的工作台");
            var inventory = maid.getAvailableBackpackInv();
            if (inventory.getSlots() > 512) return failure("背包规模超过本次合成支持范围");
            List<ItemStack> before = snapshot(inventory), after = copy(before);
            int[] sources = new int[ingredients.size()]; Arrays.fill(sources, -1);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < ingredients.size(); i++) if (!ingredients.get(i).isEmpty()) order.add(i);
            order.sort(Comparator.comparingLong(i -> before.stream().filter(s -> !s.isEmpty() && ingredients.get(i).test(s)).count()));
            if (!allocate(ingredients, order, 0, after, sources, new int[]{8192})) return failure("背包材料不足，或材料组合超出搜索范围");
            NonNullList<ItemStack> grid = NonNullList.withSize(gridSize * gridSize, ItemStack.EMPTY);
            for (int i = 0; i < sources.length; i++) {
                if (sources[i] < 0) continue;
                int index = recipe instanceof ShapedRecipe ? (i / width) * gridSize + i % width : i;
                grid.set(index, before.get(sources[i]).copyWithCount(1));
            }
            AbstractContainerMenu menu = new AbstractContainerMenu(null, -1) {
                @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
                @Override public boolean stillValid(Player player) { return true; }
            };
            var container = new TransientCraftingContainer(menu, gridSize, gridSize, grid);
            if (!recipe.matches(container, maid.level())) return failure("实际材料不符合配方条件");
            ItemStack output = recipe.assemble(container, maid.level().registryAccess());
            if (output.isEmpty()) return failure("配方没有固定可用产物");
            if (!put(inventory, after, output.copy())) return failure("背包空间不足");
            for (ItemStack remainder : recipe.getRemainingItems(container)) if (!put(inventory, after, remainder.copy())) return failure("背包放不下空桶等合成剩余物");
            return new Attempt("", before, after, output.copy());
        } catch (RuntimeException unsupported) { return failure("此配方的物品规则尚不能可靠处理"); }
    }

    private static boolean allocate(NonNullList<Ingredient> ingredients, List<Integer> order, int depth,
                                    List<ItemStack> stock, int[] sources, int[] budget) {
        if (--budget[0] < 0) return false;
        if (depth == order.size()) return true;
        int ingredient = order.get(depth);
        List<ItemStack> tried = new ArrayList<>();
        for (int i = 0; i < stock.size(); i++) {
            ItemStack candidate = stock.get(i);
            if (candidate.isEmpty() || !ingredients.get(ingredient).test(candidate)
                || tried.stream().anyMatch(s -> ItemStack.isSameItemSameTags(s, candidate))) continue;
            tried.add(candidate.copy());
            ItemStack saved = candidate.copy(); candidate.shrink(1); sources[ingredient] = i;
            if (allocate(ingredients, order, depth + 1, stock, sources, budget)) return true;
            stock.set(i, saved); sources[ingredient] = -1;
        }
        return false;
    }

    private static List<ItemStack> snapshot(IItemHandlerModifiable inventory) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < inventory.getSlots(); i++) result.add(inventory.getStackInSlot(i).copy());
        return result;
    }
    private static List<ItemStack> copy(List<ItemStack> source) { return source.stream().map(ItemStack::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new)); }

    private static boolean put(IItemHandlerModifiable inventory, List<ItemStack> stock, ItemStack item) {
        if (item.isEmpty()) return true;
        for (int pass = 0; pass < 2; pass++) for (int i = 0; i < stock.size(); i++) {
            ItemStack existing = stock.get(i);
            if (!inventory.isItemValid(i, item) || (pass == 0 && existing.isEmpty()) || (pass == 1 && !existing.isEmpty())) continue;
            if (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, item)) continue;
            int space = Math.min(item.getMaxStackSize(), inventory.getSlotLimit(i)) - existing.getCount();
            int move = Math.min(space, item.getCount());
            if (move <= 0) continue;
            if (existing.isEmpty()) stock.set(i, item.copyWithCount(move)); else existing.grow(move);
            item.shrink(move);
            if (item.isEmpty()) return true;
        }
        return false;
    }

    private static boolean same(ItemStack a, ItemStack b) {
        return (a.isEmpty() && b.isEmpty()) || (a.getCount() == b.getCount() && ItemStack.isSameItemSameTags(a, b));
    }
    private static boolean commit(IItemHandlerModifiable inventory, List<ItemStack> before, List<ItemStack> after) {
        if (inventory.getSlots() != before.size()) return false;
        for (int i = 0; i < before.size(); i++) if (!same(inventory.getStackInSlot(i), before.get(i))) return false;
        for (int i = 0; i < after.size(); i++) if (!same(before.get(i), after.get(i))) inventory.setStackInSlot(i, after.get(i).copy());
        return true;
    }

    static void blockTask(EntityMaid maid, String id, String reason) {
        var data = MaidBridge.mind(maid);
        data.putString("BlockedTask", id); data.putString("BlockedReason", reason);
        data.putString("BlockedInventory", MaidBridge.inventorySummary(maid));
        data.putLong("BlockedUntil", maid.level().getGameTime() + 2400);
    }

    static void observeProgress(EntityMaid maid) {
        var data = MaidBridge.mind(maid);
        String task = maid.getTask().getUid().toString();
        String inventory = MaidBridge.inventorySummary(maid);
        boolean production = maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.api.task.IFarmTask
            || maid.getTask() instanceof com.github.tartaricacid.touhoulittlemaid.entity.task.TaskFishing;
        int unchanged = production && maid.isHomeModeEnable() && task.equals(data.getString("ObservedTask"))
            && inventory.equals(data.getString("ObservedInventory")) ? data.getInt("NoProgressPlans") + 1 : 0;
        data.putString("ObservedTask", task); data.putString("ObservedInventory", inventory);
        data.putInt("NoProgressPlans", unchanged);
        if (unchanged >= 3) {
            blockTask(maid, task, "连续三轮农务/钓鱼安排，背包没有变化，暂时换个活动");
            data.putString("Outcome", data.getString("BlockedReason"));
            maid.setTask(TaskManager.getIdleTask());
            data.putInt("NoProgressPlans", 0);
        }
    }

    static boolean blocked(EntityMaid maid, String id) {
        var data = MaidBridge.mind(maid);
        return id.equals(data.getString("BlockedTask")) && maid.level().getGameTime() < data.getLong("BlockedUntil")
            && MaidBridge.inventorySummary(maid).equals(data.getString("BlockedInventory"));
    }

    private static void tell(EntityMaid maid, ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[" + maid.getName().getString() + " · Agnes] " + text));
    }
}
