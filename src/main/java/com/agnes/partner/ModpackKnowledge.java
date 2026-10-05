package com.agnes.partner;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.fml.ModList;
import java.util.*;

/** Builds a compact, read-only description of the installed pack for Agnes. */
public final class ModpackKnowledge {
    private record Entry(Recipe<?> recipe, ItemStack output) {}
    private record Index(List<Entry> entries, int skipped, int total) {}
    private static final Map<RecipeManager, Index> INDEXES = new WeakHashMap<>();

    private ModpackKnowledge() {}

    public static String shortDescription(ServerLevel level, BlockPos center, String inventory) {
        return describe(level, center, inventory, "", ItemStack.EMPTY);
    }

    public static void invalidate() { INDEXES.clear(); }

    /** Called only on the server thread. Never trim the whole snapshot and lose later fields. */
    public static String describe(ServerLevel level, BlockPos center, String inventory, String query, ItemStack heldItem) {
        JsonObject state = new JsonObject();
        state.addProperty("dimension", level.dimension().location().toString());
        state.addProperty("position", center.toShortString());
        state.addProperty("biome", level.getBiome(center).unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
        state.addProperty("companion_backpack", inventory);
        state.add("owner_held_item", stack(heldItem));
        state.addProperty("nearby_block_sample", nearbyBlocks(level, center));
        JsonArray mods = new JsonArray();
        ModList.get().getMods().stream().sorted(Comparator.comparing(m -> m.getModId())).forEach(m -> mods.add(m.getModId()));
        state.add("loaded_mod_ids", mods);
        state.addProperty("recipe_count", index(level).total());
        state.add("recipe_lookup", query.isBlank() ? new JsonObject() : recipes(level, query, heldItem));
        return state.toString();
    }

    private static String nearbyBlocks(ServerLevel level, BlockPos center) {
        Set<String> ids = new LinkedHashSet<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-5, -2, -5), center.offset(5, 3, 5))) {
            if (!level.hasChunkAt(pos) || level.getBlockState(pos).isAir()) continue;
            ids.add(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
            if (ids.size() >= 28) break;
        }
        return ids.isEmpty() ? "none" : String.join(",", ids);
    }

    private static Index index(ServerLevel level) {
        return INDEXES.computeIfAbsent(level.getRecipeManager(), manager -> {
            List<Entry> entries = new ArrayList<>();
            int skipped = 0;
            Collection<Recipe<?>> recipes = manager.getRecipes();
            for (Recipe<?> recipe : recipes) {
                try {
                    ItemStack result = recipe.getResultItem(level.registryAccess());
                    if (result.isEmpty() || recipe.isSpecial()) { skipped++; continue; }
                    entries.add(new Entry(recipe, result.copy()));
                } catch (RuntimeException unsupported) { skipped++; }
            }
            entries.sort(Comparator.comparing(e -> e.recipe().getId().toString()));
            return new Index(List.copyOf(entries), skipped, recipes.size());
        });
    }

    // Minecraft's active language supplies Chinese names in the integrated server.
    // Registry IDs remain usable on a dedicated server or with any language pack.
    static int score(String query, String id, String name) {
        String q = query.toLowerCase(Locale.ROOT).trim();
        String n = name.toLowerCase(Locale.ROOT).trim();
        String path = id.substring(id.indexOf(':') + 1);
        if (q.equals(id) || q.equals(n)) return 10000 + n.length();
        if (q.contains(id)) return 9000 + id.length();
        if (n.length() >= 2 && q.contains(n)) return 8000 + n.length();
        if (path.length() >= 3 && (q.equals(path) || q.contains(path.replace('_', ' ')))) return 7000 + path.length();
        if (q.length() >= 2 && (n.contains(q) || path.contains(q))) return 1000;
        return 0;
    }

    public static JsonObject recipes(ServerLevel level, String query, ItemStack heldItem) {
        Index index = index(level);
        String boundedQuery = query.length() > 500 ? query.substring(0, 500) : query;
        List<Entry> matches = index.entries().stream().filter(e -> rank(boundedQuery, e) > 0)
            .sorted(Comparator.<Entry>comparingInt(e -> rank(boundedQuery, e)).reversed()
                .thenComparing(e -> e.recipe().getId().toString())).toList();
        if (!matches.isEmpty() && rank(boundedQuery, matches.get(0)) >= 7000) {
            int best = rank(boundedQuery, matches.get(0));
            matches = matches.stream().filter(e -> rank(boundedQuery, e) == best).toList();
        }
        boolean usedHeldItem = false;
        if (matches.isEmpty() && !heldItem.isEmpty()
            && (query.contains("手里") || query.contains("手上") || query.contains("这个") || query.contains("这把"))) {
            matches = index.entries().stream().filter(e -> e.output().is(heldItem.getItem())).toList();
            usedHeldItem = true;
        }
        JsonObject result = new JsonObject();
        result.addProperty("query", boundedQuery);
        result.addProperty("used_owner_held_item", usedHeldItem);
        result.addProperty("matching_recipes", matches.size());
        result.addProperty("unindexed_special_or_dynamic_recipes", index.skipped());
        result.addProperty("scope", "Read-only RecipeManager lookup: this query does not execute recipes. Available crafting actions are defined by the caller's action schema. No match does NOT mean unobtainable. Machines, rituals, NBT rules, fluids, energy, quests and boss progression may require mod-specific integration. Check JEI/quest book for full conditions.");
        JsonArray recipes = new JsonArray();
        for (Entry entry : matches.stream().limit(4).toList()) recipes.add(recipe(entry));
        result.add("recipes", recipes);
        result.addProperty("results_truncated", matches.size() > 4);
        return result;
    }

    private static int rank(String query, Entry entry) {
        return score(query, BuiltInRegistries.ITEM.getKey(entry.output().getItem()).toString(), entry.output().getHoverName().getString());
    }

    private static JsonObject recipe(Entry entry) {
        Recipe<?> recipe = entry.recipe();
        JsonObject result = new JsonObject();
        result.addProperty("id", recipe.getId().toString());
        result.addProperty("type", String.valueOf(BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType())));
        result.add("output", stack(entry.output()));
        boolean standard = recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe
            || recipe instanceof AbstractCookingRecipe || recipe instanceof StonecutterRecipe;
        result.addProperty("standard_item_recipe", standard);
        result.addProperty("conditions", standard ? "Standard item ingredients; cooking fuel is separate. Available materials have not been checked." : "Partial custom recipe: verify ingredient counts and extra conditions in JEI. Do not assume a crafting-table recipe.");
        if (recipe instanceof ShapedRecipe shaped) {
            result.addProperty("width", shaped.getWidth());
            result.addProperty("height", shaped.getHeight());
            result.addProperty("slot_order", "row-major; null=empty; alternatives are OR, slots are AND");
        }
        if (recipe instanceof AbstractCookingRecipe cooking) result.addProperty("cooking_ticks", cooking.getCookingTime());
        JsonArray ingredients = new JsonArray();
        try {
            var slots = recipe.getIngredients();
            for (var ingredient : slots.stream().limit(16).toList()) {
                if (ingredient.isEmpty()) { ingredients.add(com.google.gson.JsonNull.INSTANCE); continue; }
                JsonObject slot = new JsonObject();
                ItemStack[] options = ingredient.getItems();
                JsonArray alternatives = new JsonArray();
                for (int i = 0; i < Math.min(5, options.length); i++) alternatives.add(stack(options[i]));
                slot.add("alternatives", alternatives);
                slot.addProperty("alternative_count", options.length);
                slot.addProperty("alternatives_truncated", options.length > 5);
                slot.addProperty("custom_matching_rules", !ingredient.isSimple());
                ingredients.add(slot);
            }
            result.addProperty("slots_truncated", slots.size() > 16);
        } catch (RuntimeException unsupported) {
            result.addProperty("ingredient_error", "Custom ingredient could not be inspected; consult JEI.");
        }
        result.add("ingredient_slots", ingredients);
        return result;
    }

    public static List<String> recipeLines(JsonObject lookup) {
        List<String> lines = new ArrayList<>();
        int count = lookup.get("matching_recipes").getAsInt();
        lines.add("[Agnes 配方] 找到 " + count + " 条，最多显示 4 条；只查询，不消耗材料或自动合成。");
        for (var element : lookup.getAsJsonArray("recipes")) {
            JsonObject recipe = element.getAsJsonObject();
            JsonObject output = recipe.getAsJsonObject("output");
            lines.add(output.get("name").getAsString() + " ×" + output.get("count").getAsInt()
                + "；类型 " + recipe.get("type").getAsString() + "；配方 " + recipe.get("id").getAsString());
            if (recipe.has("width")) lines.add("排列：" + recipe.get("width").getAsInt() + "×" + recipe.get("height").getAsInt() + "，从左到右、从上到下：");
            List<String> slots = new ArrayList<>();
            for (var ingredient : recipe.getAsJsonArray("ingredient_slots")) {
                if (ingredient.isJsonNull()) { slots.add("空"); continue; }
                JsonObject slot = ingredient.getAsJsonObject();
                List<String> options = new ArrayList<>();
                for (var option : slot.getAsJsonArray("alternatives")) options.add(option.getAsJsonObject().get("name").getAsString());
                slots.add(String.join("/", options) + (slot.get("alternatives_truncated").getAsBoolean() ? "/…" : ""));
            }
            lines.add("材料格：" + String.join(" | ", slots));
            if (!recipe.get("standard_item_recipe").getAsBoolean() || recipe.has("ingredient_error") || recipe.get("slots_truncated").getAsBoolean())
                lines.add("此配方仅显示部分信息，额外条件、材料数量请以 JEI 为准。");
        }
        if (count == 0) lines.add("没有匹配到固定产物配方。请用物品完整名称或 modid:item_id 重试；也可能是掉落、任务、仪式或动态配方，请查 JEI/任务书。");
        return lines;
    }

    private static JsonObject stack(ItemStack stack) {
        JsonObject result = new JsonObject();
        if (stack.isEmpty()) { result.addProperty("empty", true); return result; }
        result.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        String name = stack.getHoverName().getString();
        result.addProperty("name", name.length() > 128 ? name.substring(0, 128) : name);
        result.addProperty("count", stack.getCount());
        result.addProperty("has_extra_nbt", stack.hasTag());
        return result;
    }
}
