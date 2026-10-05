package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hunting.
 *
 * This is the missing food loop: sweet berries and fish are slow or unavailable, and a maid that only
 * eats berries will never get far. Hunting is deliberately built on the machinery that already works
 * -- walk there, swing, apply the real loot table into her own backpack -- so nothing here invents a
 * new combat system. The native maid combat still handles anything that attacks her first.
 *
 * The distinction matters and is kept: this is the deliberate act of going out to get food or leather,
 * not a change to how she defends herself.
 */
final class MaidHunt {
    private MaidHunt() {}

    /** Wandering animals she may hunt for food. Never the owner's pets, never a tamed animal. */
    private static final long HUNT_TIMEOUT = 2400;   // two minutes per animal
    private static final long HUNT_STALL = 600;      // thirty seconds without closing in
    /**
     * Attack range, measured to the target's HITBOX rather than to its feet. Vanilla mob reach is about
     * three blocks from the attacker's eye, and a cow is 1.4 blocks wide, so a feet-to-feet test of
     * three blocks often never triggered: she walked to the animal, stopped, and never swung. That was
     * the whole of "she goes to the cow and does not hit it".
     */
    private static final double REACH = 4.0;
    /** Within this distance but still out of reach: push straight forward instead of standing still. */
    private static final double NUDGE = 9.0;
    private static final Map<UUID, Hunt> HUNTS = new HashMap<>();

    private static final class Hunt {
        final int entityId;
        final ResourceLocation type;
        final boolean autonomous;
        final long started;
        final String schedule;
        double bestDistance = Double.MAX_VALUE;
        long lastProgress;
        long nextSwing;
        int swings;
        Hunt(EntityMaid maid, LivingEntity target, boolean autonomous) {
            this.entityId = target.getId();
            this.type = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
            this.autonomous = autonomous;
            this.started = maid.level().getGameTime();
            this.schedule = maid.getSchedule().name();
            this.lastProgress = started;
        }
    }

    static boolean active(EntityMaid maid) { return HUNTS.containsKey(maid.getUUID()); }
    static void clear() { HUNTS.clear(); }
    static void cancel(EntityMaid maid, String reason) {
        if (HUNTS.remove(maid.getUUID()) != null) {
            MaidBridge.mind(maid).putString("HuntResult", reason);
            MaidBridge.mind(maid).putLong("HuntCooldown", maid.level().getGameTime() + 600);
            releaseIdleWork(maid);
            MaidBridge.trace(maid, "hunt cancelled: " + reason);
        }
    }

    /** Cooldown after a finished attempt, so she does not immediately pick the same animal again. */
    private static void releaseIdleWork(EntityMaid maid) {
        CompoundTag data = MaidBridge.mind(maid);
        data.putBoolean("IdleWork", false);
        data.remove("IdleWorkName");
    }

    /**
     * Safety net for the case where the main heartbeat never reaches the hunt tick, for example while
     * she is being checked for a knockdown or a rescue by another mod. Without this the hunt stayed
     * "in progress" forever and every later attempt was refused with "already chasing something".
     */
    static void expire(EntityMaid maid) {
        Hunt hunt = HUNTS.get(maid.getUUID());
        if (hunt == null) return;
        long now = maid.level().getGameTime();
        if (now - hunt.started > HUNT_TIMEOUT) {
            cancel(maid, "追了两分钟没能得手，先放弃这只");
            return;
        }
        if (now - hunt.lastProgress > HUNT_STALL * 2) cancel(maid, "很久没有靠近猎物，先放弃这只");
    }

    /** One line describing the hunt, for the status and diagnostic commands. */
    static String describe(EntityMaid maid) {
        Hunt hunt = HUNTS.get(maid.getUUID());
        if (hunt == null) return "没有在打猎";
        Entity entity = maid.level().getEntity(hunt.entityId);
        long now = maid.level().getGameTime();
        String name = entity instanceof LivingEntity living ? living.getName().getString() : "已经不在了";
        return "正在追 " + name + "；已用 " + ((now - hunt.started) / 20) + " 秒，"
            + "最近一次靠近在 " + ((now - hunt.lastProgress) / 20) + " 秒（游戏时间），出手 " + hunt.swings + " 次";
    }

    // ---------------------------------------------------------------- choosing

    /** The nearest huntable animal she can actually see, or null. */
    static LivingEntity candidate(EntityMaid maid, String kind) {
        List<LivingEntity> pool = maid.level().getEntitiesOfClass(LivingEntity.class,
            maid.getBoundingBox().inflate(24), e -> huntable(maid, e));
        List<LivingEntity> matching = new ArrayList<>();
        for (LivingEntity entity : pool) if (matches(entity, kind)) matching.add(entity);
        return matching.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(maid))).orElse(null);
    }

    private static boolean matches(LivingEntity entity, String kind) {
        return switch (kind == null ? "animal" : kind) {
            case "monster" -> entity instanceof Monster;
            case "any" -> true;
            default -> entity instanceof Animal;
        };
    }

    /** Rules that keep hunting from being a griefing tool or a way to kill the player's own animals. */
    static boolean huntable(EntityMaid maid, LivingEntity entity) {
        if (entity == maid || !entity.isAlive() || entity.isInvulnerable()) return false;
        if (entity instanceof net.minecraft.world.entity.player.Player) return false;
        if (entity instanceof EntityMaid) return false;
        if (entity.getType().getCategory() != net.minecraft.world.entity.MobCategory.CREATURE
            && entity.getType().getCategory() != net.minecraft.world.entity.MobCategory.MONSTER
            && entity.getType().getCategory() != net.minecraft.world.entity.MobCategory.WATER_CREATURE
            && entity.getType().getCategory() != net.minecraft.world.entity.MobCategory.AMBIENT) return false;
        // Never a tamed animal or one that belongs to somebody, and never a named pet.
        if (entity instanceof net.minecraft.world.entity.TamableAnimal tamable && tamable.isTame()) return false;
        if (entity.hasCustomName()) return false;
        return true;
    }

    static JsonArray describe(EntityMaid maid, String kind) {
        JsonArray out = new JsonArray();
        List<LivingEntity> pool = maid.level().getEntitiesOfClass(LivingEntity.class,
            maid.getBoundingBox().inflate(24), e -> huntable(maid, e) && matches(e, kind));
        pool.sort(Comparator.comparingDouble(e -> e.distanceToSqr(maid)));
        for (LivingEntity entity : pool) {
            JsonObject entry = new JsonObject();
            entry.addProperty("target_id", targetId(entity));
            entry.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            entry.addProperty("distance", Math.round(Math.sqrt(entity.distanceToSqr(maid))));
            entry.addProperty("health", entity.getHealth());
            entry.addProperty("food_value", foodValue(entity));
            out.add(entry);
            if (out.size() >= 8) break;
        }
        return out;
    }

    static String targetId(LivingEntity entity) {
        return "hunt:" + entity.getId() + ":" + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
    }

    /** Rough nutrition, so the model can prefer a cow over a chicken. */
    static int foodValue(LivingEntity entity) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        return switch (id) {
            case "minecraft:cow", "minecraft:mooshroom" -> 6;
            case "minecraft:pig", "minecraft:sheep" -> 5;
            case "minecraft:chicken", "minecraft:rabbit" -> 3;
            case "minecraft:salmon", "minecraft:cod" -> 4;
            default -> entity instanceof Monster ? 0 : 2;
        };
    }

    // ---------------------------------------------------------------- running

    static String start(EntityMaid maid, String targetId, String kind, boolean autonomous) {
        if (active(maid)) { cancel(maid, "换一个新的猎取目标"); MaidBridge.mind(maid).putLong("HuntCooldown", 0); }
        if (!PartnerConfig.mayEditWorld(maid.level(), maid)) return "未启动：世界规则禁止女仆主动改变世界";
        CompoundTag data = MaidBridge.mind(maid);
        if (data.getLong("HuntCooldown") > maid.level().getGameTime()) return "刚刚失手过，先做别的事，等一会儿再试";
        LivingEntity target = null;
        int entityId = parseEntityId(targetId);
        if (entityId >= 0) {
            Entity found = maid.level().getEntity(entityId);
            if (found instanceof LivingEntity living && huntable(maid, living)) target = living;
        }
        if (target == null) target = candidate(maid, kind);
        if (target == null) return "未启动：附近 24 格里没有可以猎取的动物";
        // She must be able to hurt it; a bare-handed maid against an iron golem is not hunting.
        if (!canHurt(maid, target)) return "未启动：手上没有能造成伤害的物品，先准备武器或工具";
        // Hunting replaces whatever she was doing, like a player deciding to go get meat.
        MaidFieldwork.cancel(maid, "去猎取食物");
        MaidWorkshop.cancel(maid, "去猎取食物");
        MaidPlan.clear(maid, "去猎取食物");
        maid.setHomeModeEnable(true);
        HUNTS.put(maid.getUUID(), new Hunt(maid, target, autonomous));
        String name = target.getName().getString();
        String result = "正在追 " + name + "（距离 " + Math.round(Math.sqrt(target.distanceToSqr(maid))) + " 格），还没有得手";
        data.putString("HuntResult", result);
        return result;
    }

    private static int parseEntityId(String targetId) {
        if (targetId == null || !targetId.startsWith("hunt:")) return -1;
        String[] parts = targetId.split(":");
        if (parts.length < 2) return -1;
        try { return Integer.parseInt(parts[1]); } catch (NumberFormatException invalid) { return -1; }
    }

    /** Anything that can deal damage counts; a stick does not, a sword or an axe does. */
    private static boolean canHurt(EntityMaid maid, LivingEntity target) {
        ItemStack main = maid.getMainHandItem();
        if (!main.isEmpty()) return true;
        // Bare hands are enough against small animals, not against a monster.
        return !(target instanceof Monster);
    }

    /** True when she is carrying something to fight with, so a hunt is worth planning. */
    static boolean canFight(EntityMaid maid) { return !maid.getMainHandItem().isEmpty(); }

    /** Called once per second from the main heartbeat. */
    static void tick(EntityMaid maid, ServerPlayer player) {
        Hunt hunt = HUNTS.get(maid.getUUID());
        if (hunt == null) return;
        CompoundTag data = MaidBridge.mind(maid);
        if (maid.isSleeping() || maid.isOrderedToSit() || maid.getHealth() < maid.getMaxHealth() * 0.5f
            || !maid.getSchedule().name().equals(hunt.schedule)) {
            cancel(maid, "被休息、低血量或作息变化打断，放弃了这次猎取");
            return;
        }
        Entity entity = maid.level().getEntity(hunt.entityId);
        if (!(entity instanceof LivingEntity target) || !target.isAlive()) {
            HUNTS.remove(maid.getUUID());
            releaseIdleWork(maid);
            data.putString("HuntResult", "猎物已经不在了，换一个目标");
            MaidBridge.trace(maid, "hunt ended: target gone");
            return;
        }
        if (!huntable(maid, target)) { HUNTS.remove(maid.getUUID()); releaseIdleWork(maid); data.putString("HuntResult", "这个目标不适合猎取，停手了"); MaidBridge.trace(maid, "hunt ended: target no longer huntable"); return; }

        long now = maid.level().getGameTime();
        double distance = gapTo(maid, target);
        if (distance <= REACH) {
            maid.getNavigation().stop();
            maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
            if (now >= hunt.nextSwing) {
                hunt.nextSwing = now + attackCooldown(maid);
                strike(maid, target, data);
            } else {
                maid.getLookControl().setLookAt(target);
            }
            return;
        }
        // A maid can end up with her feet beside a large animal while the eye-to-center
        // distance is still just outside reach. Re-issue a direct movement every tick and
        // turn toward the hitbox so native idle navigation cannot leave her frozen in place.
        maid.getLookControl().setLookAt(target);
        if (now - hunt.started > HUNT_TIMEOUT) { cancel(maid, "追了两分钟没能靠近，先放弃这只"); return; }
        if (distance < hunt.bestDistance - 0.5) { hunt.bestDistance = distance; hunt.lastProgress = now; }
        else if (now - hunt.lastProgress > HUNT_STALL) { cancel(maid, "猎物一直在跑或路被挡住，先放弃这只"); return; }
        // Close but not in reach: the animal is between path nodes, so walk straight at it rather than
        // waiting for pathfinding to finish a route that already ended.
        if (distance <= NUDGE) closeIn(maid, target);
        else {
            maid.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target.position(), 0.7f, 0));
            maid.getNavigation().moveTo(target, 0.7);
        }
        data.putString("HuntResult", "正在追 " + target.getName().getString() + "，还差约 " + Math.round(distance * 10) / 10.0 + " 格");
    }

    /** Pace attacks like a real weapon while the target keeps moving. */
    private static long attackCooldown(EntityMaid maid) {
        ItemStack item = maid.getMainHandItem();
        if (item.isEmpty()) return 12;
        return item.getItem() instanceof net.minecraft.world.item.SwordItem ? 12
            : item.getItem() instanceof net.minecraft.world.item.AxeItem ? 18 : 15;
    }

    /** Distance from her eye to the nearest point of the target's hitbox, which is what reach means. */
    private static double gapTo(EntityMaid maid, LivingEntity target) {
        return Math.sqrt(maid.getEyePosition().distanceToSqr(target.getBoundingBox().getCenter()));
    }

    /** Short direct step toward the target's hitbox edge, used when pathfinding has already stopped. */
    private static void closeIn(EntityMaid maid, LivingEntity target) {
        Vec3 from = maid.position();
        Vec3 to = target.getBoundingBox().getCenter();
        Vec3 direction = to.subtract(from);
        if (direction.lengthSqr() < 0.0001) return;
        Vec3 step = from.add(direction.normalize().scale(1.2));
        maid.getNavigation().moveTo(step.x, step.y, step.z, 0.9);
        maid.getLookControl().setLookAt(target);
    }

    /** One swing with real loot rolls; the animal dies from the damage, not from a scripted removal. */
    private static void strike(EntityMaid maid, LivingEntity target, CompoundTag data) {
        ServerLevel level = (ServerLevel)maid.level();
        var actor = FakePlayerFactory.get(level, new GameProfile(maid.getUUID(), "[AgnesMaid]"));
        actor.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        actor.moveTo(maid.position());
        ItemStack weapon = maid.getMainHandItem().copy();
        actor.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        try {
            maid.getLookControl().setLookAt(target);
            maid.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, target.position().add(0, target.getBbHeight() / 2, 0));
            maid.swing(InteractionHand.MAIN_HAND);
            float before = target.getHealth();
            target.hurt(level.damageSources().mobAttack(maid), attackDamage(maid));
            if (target.getHealth() >= before) {
                // Nothing happened: protection, invulnerability or a mob that cannot be hurt by this.
                cancel(maid, "打不动这个目标，停手了");
                return;
            }
            huntRecord(maid, target, data);
            Hunt current = HUNTS.get(maid.getUUID());
            if (current != null && ++current.swings % 5 == 1)
                MaidBridge.trace(maid, "hunt swing " + current.swings + " on " + target.getName().getString()
                    + " health=" + Math.round(target.getHealth()) + " damage=" + attackDamage(maid));
            if (target.isAlive()) {
                data.putString("HuntResult", "正在攻击 " + target.getName().getString() + "（剩余 " + Math.round(target.getHealth()) + " 点生命）");
                counterAttack(maid, target);
                return;
            }
            // The animal really died: roll its own loot table and put what fits into her backpack.
            List<ItemStack> loot = new ArrayList<>();
            var table = level.getServer().getLootData().getLootTable(target.getLootTable());
            LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, target)
                .withParameter(LootContextParams.ORIGIN, target.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().mobAttack(maid))
                .withOptionalParameter(LootContextParams.KILLER_ENTITY, maid)
                .withOptionalParameter(LootContextParams.LAST_DAMAGE_PLAYER, actor)
                .create(LootContextParamSets.ENTITY);
            loot.addAll(table.getRandomItems(params));
            deliver(maid, level, target.blockPosition(), loot);
            HUNTS.remove(maid.getUUID());
            releaseIdleWork(maid);
            MaidBridge.trace(maid, "hunt succeeded on " + target.getName().getString());
            String name = target.getName().getString();
            if (foodValue(target) > 0) MaidLandmarks.remember(maid, "camp", target.blockPosition(), null);
            String result = "实际猎取完成 " + name + "；" + lootSummary(loot);
            data.putString("HuntResult", result);
            MaidBridge.outcome(maid, result);
            MaidBubble.speakIfExpired(maid, "hunt", pickLine(maid, name), -1L);
        } catch (RuntimeException unsupported) {
            cancel(maid, "这次攻击与当前模组规则不兼容，停手了");
        } finally {
            actor.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
    }

    private static float attackDamage(EntityMaid maid) {
        ItemStack main = maid.getMainHandItem();
        if (main.isEmpty()) return 1.0f;  // bare hands: enough for a chicken, not for a zombie
        float base = 1.0f;
        // The held item's own attack value decides, so a sword really is better than a stick.
        for (var entry : main.getAttributeModifiers(EquipmentSlot.MAINHAND).get(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)) {
            base = (float)entry.getAmount() + 1.0f;
        }
        return Math.max(1.0f, base);
    }

    /** Give the animal a moment to fight back on its own terms rather than scripting its defeat. */
    private static void counterAttack(EntityMaid maid, LivingEntity target) {
        if (target instanceof Mob mob && mob.getTarget() == null && mob.canAttack(maid)) mob.setTarget(maid);
    }

    private static void huntRecord(EntityMaid maid, LivingEntity target, CompoundTag data) {
        data.putString("HuntType", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
    }

    private static String lootSummary(List<ItemStack> loot) {
        if (loot.isEmpty()) return "没有掉落物";
        List<String> parts = new ArrayList<>();
        for (ItemStack stack : loot) if (!stack.isEmpty()) parts.add(stack.getHoverName().getString() + " ×" + stack.getCount());
        return parts.isEmpty() ? "没有掉落物" : String.join("、", parts);
    }

    private static void deliver(EntityMaid maid, ServerLevel level, BlockPos at, List<ItemStack> loot) {
        for (ItemStack stack : loot) {
            if (stack.isEmpty()) continue;
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), stack, false);
            if (!remainder.isEmpty()) {
                // Give the maid a chance to use a nearby chest/barrel before dropping valuable food.
                MaidStorage.autoDeposit(maid);
                remainder = ItemHandlerHelper.insertItemStacked(maid.getAvailableBackpackInv(), remainder, false);
            }
            if (!remainder.isEmpty()) {
                // She cannot walk onto an item entity in dev, so hand it back to her pickup logic.
                ItemEntity dropped = new ItemEntity(level, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, remainder);
                dropped.setPickUpDelay(10);
                level.addFreshEntity(dropped);
            }
        }
    }

    private static String pickLine(EntityMaid maid, String name) {
        List<String> lines = List.of(
            "打到一只" + name + "，今晚有吃的了。",
            "猎到" + name + "了，正好补一下食物。",
            name + "到手，我收进背包了。");
        return lines.get(maid.getRandom().nextInt(lines.size()));
    }

    /** Food she already has, used to decide whether hunting is worth it. */
    static boolean worthHunting(EntityMaid maid) {
        return MaidSurvival.foodCount(maid) < 8 && MaidBridge.mind(maid).getLong("HuntCooldown") <= maid.level().getGameTime();
    }

    static int meatCount(EntityMaid maid) {
        int count = 0;
        var inventory = maid.getAvailableBackpackInv();
        for (int i = 0; i < inventory.getSlots(); i++) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            if (stack.is(Items.BEEF) || stack.is(Items.PORKCHOP) || stack.is(Items.CHICKEN)
                || stack.is(Items.MUTTON) || stack.is(Items.RABBIT) || stack.is(Items.COOKED_BEEF)
                || stack.is(Items.COOKED_PORKCHOP) || stack.is(Items.COOKED_CHICKEN) || stack.is(Items.COOKED_MUTTON)
                || stack.is(Items.COOKED_RABBIT)) count += stack.getCount();
        }
        return count;
    }

    /** A hunting need line for the idle work loop: prefer her own food over fishing. */
    static String foodPlan(EntityMaid maid) {
        if (!worthHunting(maid)) return "";
        LivingEntity target = candidate(maid, "animal");
        if (target == null) return "";
        if (!canHurt(maid, target)) return "";
        return "去打一只" + target.getName().getString() + "弄点吃的";
    }
}
