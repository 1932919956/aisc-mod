package com.agnes.partner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.NonNullList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.ContainerHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Queue;

public class AgnesCompanion extends PathfinderMob {
    private String ownerName = "";
    private boolean following = true;
    private int thinkCooldown = 0;
    private boolean requestPending = false;
    private int pickupCooldown = 0;
    private int reviveTicks = 0;
    private int foodCooldown = 0;
    private int protectCooldown = 0;
    private int autonomousPlanCooldown = 0;
    private int activeActionTicks = 0;
    private int activeActionPulse = 0;
    private String activeAction = "idle";
    private String activeTarget = "log";
    private boolean lastPlanWasAutonomous = false;
    private boolean autonomousEnabled;
    private final Queue<String> instructionQueue = new ArrayDeque<>();
    private final SimpleContainer inventory = new SimpleContainer(27);

    protected AgnesCompanion(EntityType<? extends PathfinderMob> type, Level level) { super(type, level); autonomousEnabled = PartnerConfig.isAutonomousMode(); setPersistenceRequired(); }
    public static AttributeSupplier.Builder attributes() { return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 30.0).add(Attributes.MOVEMENT_SPEED, 0.32).add(Attributes.ATTACK_DAMAGE, 5.0).add(Attributes.FOLLOW_RANGE, 32.0); }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.15, true));
        goalSelector.addGoal(4, new FollowOwnerGoal(this, 1.05, 3.0f, 18.0f));
        goalSelector.addGoal(7, new RandomStrollGoal(this, 0.8));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(9, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Monster.class, true));
    }

    @Override public void tick() {
        if (!level().isClientSide && level().getServer() != null) {
            ServerPlayer owner = level().getServer().getPlayerList().getPlayerByName(ownerName);
            if (owner != null && AgnesPartnerMod.maidBound(owner)) {
                if (!getPersistentData().getBoolean("PausedForMaid")) {
                    getPersistentData().putBoolean("PausedForMaid", true);
                    getPersistentData().putBoolean("PreviousNoAI", isNoAi());
                }
                setNoAi(true); getNavigation().stop(); setTarget(null);
                super.tick();
                return;
            }
            if (owner != null && getPersistentData().getBoolean("PausedForMaid")) {
                setNoAi(getPersistentData().getBoolean("PreviousNoAI"));
                getPersistentData().remove("PausedForMaid");
            }
        }
        super.tick();
        if (!level().isClientSide) {
            if (reviveTicks > 0) {
                reviveTicks--;
                getNavigation().stop();
                setTarget(null);
                if (reviveTicks == 0) {
                    setNoAi(false);
                    setInvulnerable(false);
                    setHealth(getMaxHealth());
                    following = true;
                    thinkCooldown = 20 * 5;
                    notifyOwner("我已经恢复，可以继续陪你冒险了。");
                }
                return;
            }
            if (--foodCooldown <= 0) {
                foodCooldown = 20;
                eatFoodIfNeeded();
            }
            if (--protectCooldown <= 0) {
                protectCooldown = 10;
                protectOwner();
            }
            if (level().getServer() != null && distanceToOwnerSqr() > 40.0 * 40.0) {
                setActiveAction("follow", "", 30);
            }
            runActiveAction();
            if (--pickupCooldown <= 0) {
                pickupCooldown = 5;
                pickupNearbyItems();
            }
            if (autonomousEnabled && --autonomousPlanCooldown <= 0) {
                autonomousPlanCooldown = 20 * 35;
                if (!requestPending) askAgnes("自主生活：不要只跟着主人。请自己选择一个安全、有用或有趣的短期活动，并持续执行一段时间；可以探索、采集、整理物资、保护主人，偶尔用一句自然的中文说说你在做什么。");
            } else if (--thinkCooldown <= 0) {
                thinkCooldown = 20 * 20;
                if (!requestPending && !PartnerConfig.isAutonomousMode()) askAgnes("自主观察环境并选择下一步。");
            }
        }
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("AgnesOwner", ownerName);
        tag.putInt("AgnesReviveTicks", reviveTicks);
        tag.putInt("AgnesActiveActionTicks", activeActionTicks);
        tag.putString("AgnesActiveAction", activeAction);
        tag.putString("AgnesActiveTarget", activeTarget);
        tag.putBoolean("AgnesAutonomous", autonomousEnabled);
        CompoundTag inventoryTag = new CompoundTag();
        NonNullList<ItemStack> storedItems = NonNullList.withSize(inventory.getContainerSize(), ItemStack.EMPTY);
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) storedItems.set(slot, inventory.getItem(slot));
        ContainerHelper.saveAllItems(inventoryTag, storedItems);
        tag.put("AgnesInventory", inventoryTag);
    }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        ownerName = tag.getString("AgnesOwner");
        reviveTicks = tag.getInt("AgnesReviveTicks");
        activeActionTicks = tag.getInt("AgnesActiveActionTicks");
        activeAction = tag.getString("AgnesActiveAction");
        activeTarget = tag.getString("AgnesActiveTarget");
        if (tag.contains("AgnesAutonomous")) autonomousEnabled = tag.getBoolean("AgnesAutonomous");
        if (activeAction.isBlank()) activeAction = "idle";
        if (reviveTicks > 0) {
            setNoAi(true);
            setInvulnerable(true);
        }
        if (tag.contains("AgnesInventory")) {
            NonNullList<ItemStack> storedItems = NonNullList.withSize(inventory.getContainerSize(), ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag.getCompound("AgnesInventory"), storedItems);
            for (int slot = 0; slot < storedItems.size(); slot++) inventory.setItem(slot, storedItems.get(slot));
        }
    }

    public void hear(String instruction) {
        if (instruction == null || instruction.isBlank()) return;
        autonomousPlanCooldown = 20 * 35;
        thinkCooldown = 20 * 20;
        if (requestPending) {
            if (instructionQueue.size() < 20) instructionQueue.offer(instruction);
            else notifyOwner("我现在还有很多话要处理，稍等我一下。");
            return;
        }
        askAgnes(instruction);
    }

    private void askAgnes(String instruction) {
        String key = PartnerConfig.getApiKey(); if (key.isBlank() || level().getServer() == null) return;
        ServerPlayer owner = level().getServer().getPlayerList().getPlayerByName(ownerName); if (owner == null) return;
        if (AgnesPartnerMod.maidBound(owner)) return;
        requestPending = true;
        lastPlanWasAutonomous = instruction.startsWith("自主");
        String screenshot = PartnerConfig.readScreenshotBase64();
        requestAgnesText(instruction, key, screenshot);
    }

    private void requestAgnesText(String instruction, String key, String screenshot) {
        if (level().getServer() == null) { requestPending = false; return; }
        level().getServer().execute(() -> requestAgnesTextOnServer(instruction, key, screenshot));
    }

    private void requestAgnesTextOnServer(String instruction, String key, String screenshot) {
        String nearby = level().getEntitiesOfClass(Monster.class, getBoundingBox().inflate(12)).stream().limit(5).map(m -> m.getType().toShortString()).reduce((a,b) -> a + "," + b).orElse("none");
        ServerPlayer player = level().getServer().getPlayerList().getPlayerByName(ownerName);
        String knowledge = ModpackKnowledge.describe((net.minecraft.server.level.ServerLevel) level(), blockPosition(), inventorySummary(),
            lastPlanWasAutonomous ? "" : instruction, player == null ? ItemStack.EMPTY : player.getMainHandItem());
        String prompt = "You are a Minecraft teammate with a distinct, curious personality. Player message=" + instruction + ". Owner=" + ownerName + ", companion health=" + getHealth() + ", nearby hostile mobs=" + nearby + ", current activity=" + activeAction + ". Observed game data=" + knowledge
            + ". Use observed recipe ingredients and outputs when answering crafting questions. Repeated ingredient slots require repeated materials; alternatives within one slot are choices. Report missing data honestly: no match does not prove no recipe exists. You cannot yet craft, equip gear, operate machines, read quest progress or execute a boss progression plan; never claim to have done those actions. The available executable actions are only those in the JSON schema. An attached screenshot is the owner's screen, not your own viewpoint. For autonomous requests choose a safe short activity, preferably exploration or basic gathering. For ordinary conversation always reply naturally in say. For recipe questions give up to four concise Chinese sentences explaining the materials and recipe type. Use share when asked for items. Return only JSON {\"say\":\"Chinese reply\",\"action\":\"follow|stop|attack|gather|share|explore|idle\",\"target\":\"log|stone|food or empty\",\"duration\":30}. duration is seconds from 15 to 120.";
        JsonObject message = new JsonObject(); message.addProperty("role", "user");
        if (PartnerConfig.isVisionEnabled() && !screenshot.isBlank()) {
            com.google.gson.JsonArray content = new com.google.gson.JsonArray();
            JsonObject textPart = new JsonObject(); textPart.addProperty("type", "text"); textPart.addProperty("text", prompt); content.add(textPart);
            JsonObject imagePart = new JsonObject(); imagePart.addProperty("type", "image_url");
            JsonObject imageUrl = new JsonObject(); imageUrl.addProperty("url", "data:image/png;base64," + screenshot); imagePart.add("image_url", imageUrl); content.add(imagePart);
            message.add("content", content);
        } else {
            message.addProperty("content", prompt);
        }
        JsonObject body = new JsonObject(); body.addProperty("model", PartnerConfig.getModel()); com.google.gson.JsonArray messages = new com.google.gson.JsonArray(); messages.add(message); body.add("messages", messages); body.addProperty("max_tokens", 600);
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://apihub.agnes-ai.com/v1/chat/completions")).timeout(java.time.Duration.ofSeconds(45)).header("Authorization", "Bearer " + key).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpClient.newHttpClient().sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300 || !hasChoices(response.body())) {
                if (!screenshot.isBlank()) requestAgnesText(instruction, key, "");
                else {
                    failRequest("这次 Agnes 请求没有成功（HTTP " + response.statusCode() + "），请稍后再试。");
                }
                return;
            }
            applyPlan(response.body(), ownerName);
        }).exceptionally(error -> {
            failRequest("这次连接 Agnes 超时或网络异常，请稍后再试。");
            return null;
        });
    }

    private boolean hasChoices(String raw) {
        try { return JsonParser.parseString(raw).getAsJsonObject().has("choices"); }
        catch (Exception ignored) { return false; }
    }

    private void applyPlan(String raw, String owner) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            String content = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString().replace("```json", "").replace("```", "").trim();
            JsonObject plan = JsonParser.parseString(content).getAsJsonObject();
            String say = plan.has("say") ? plan.get("say").getAsString() : "";
            String action = plan.has("action") ? plan.get("action").getAsString() : "idle";
            String target = plan.has("target") ? plan.get("target").getAsString() : "log";
            int duration = plan.has("duration") ? Math.max(15, Math.min(120, plan.get("duration").getAsInt())) : (lastPlanWasAutonomous ? 45 : 20);
            if (lastPlanWasAutonomous && action.equals("idle")) action = "explore";
            final String chosenAction = action;
            if (level().getServer() == null) return;
            level().getServer().execute(() -> {
                requestPending = false;
                ServerPlayer boundOwner = level().getServer().getPlayerList().getPlayerByName(owner);
                if (boundOwner != null && AgnesPartnerMod.maidBound(boundOwner)) { instructionQueue.clear(); return; }
                if (chosenAction.equals("follow")) { following = true; setActiveAction("follow", "", duration); }
                else if (chosenAction.equals("stop")) { following = false; setActiveAction("stop", "", duration); getNavigation().stop(); }
                else if (chosenAction.equals("explore")) { following = false; setActiveAction("explore", "", duration); }
                else if (chosenAction.equals("attack")) {
                    following = false; setActiveAction("attack", "", duration);
                    Monster enemy = level().getEntitiesOfClass(Monster.class, getBoundingBox().inflate(16)).stream().findFirst().orElse(null);
                    if (enemy != null) setTarget(enemy);
                }
                else if (chosenAction.equals("gather")) { following = false; setActiveAction("gather", target, duration); gatherNearby(target); }
                else if (chosenAction.equals("share")) { following = false; setActiveAction("share", "", duration); shareWithOwner(); }
                else { setActiveAction("idle", "", duration); }
                if (!say.isBlank()) { ServerPlayer player = level().getServer().getPlayerList().getPlayerByName(owner); if (player != null) player.sendSystemMessage(Component.literal("[Agnes] " + say)); }
                processQueuedInstruction();
            });
        } catch (Exception ignored) {
            failRequest("这次 Agnes 返回的回复格式不完整，请再问我一次。");
        }
    }

    private void failRequest(String message) {
        if (level().getServer() == null) return;
        level().getServer().execute(() -> {
            if (!lastPlanWasAutonomous) notifyOwner(message);
            requestPending = false;
            processQueuedInstruction();
        });
    }

    private void processQueuedInstruction() {
        if (requestPending) return;
        String next = instructionQueue.poll();
        if (next == null || next.isBlank()) return;
        askAgnes(next);
    }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String name) { ownerName = name; }
    public boolean isFollowing() { return following; }

    public boolean isAutonomousMode() { return autonomousEnabled; }

    public void setAutonomousMode(boolean enabled) {
        autonomousEnabled = enabled;
        if (!enabled) {
            setActiveAction("follow", "", 0);
            following = true;
            getNavigation().stop();
        } else {
            autonomousPlanCooldown = 1;
        }
    }

    public String getActiveAction() { return activeAction; }

    public int getActiveActionSeconds() { return Math.max(0, activeActionTicks / 20); }

    private void setActiveAction(String action, String target, int seconds) {
        activeAction = action == null || action.isBlank() ? "idle" : action;
        activeTarget = target == null ? "log" : target;
        activeActionTicks = Math.max(0, seconds * 20);
        activeActionPulse = 0;
    }

    private void runActiveAction() {
        if (activeActionTicks > 0) activeActionTicks--;
        if (activeActionTicks == 0 && !activeAction.equals("follow")) {
            activeAction = autonomousEnabled ? "explore" : "idle";
            activeActionTicks = autonomousEnabled ? 20 * 20 : 0;
        }
        if (++activeActionPulse < 10) return;
        activeActionPulse = 0;
        if (activeAction.equals("explore")) {
            following = false;
            if (getNavigation().isDone() || getNavigation().isStuck()) {
                int dx = RandomSource.create().nextInt(17) - 8;
                int dz = RandomSource.create().nextInt(17) - 8;
                getNavigation().moveTo(getX() + dx, getY(), getZ() + dz, 0.85);
            }
        } else if (activeAction.equals("gather")) {
            following = false;
            gatherNearby(activeTarget);
        } else if (activeAction.equals("share")) {
            following = false;
            shareWithOwner();
        } else if (activeAction.equals("attack") && (getTarget() == null || !getTarget().isAlive())) {
            Monster enemy = level().getEntitiesOfClass(Monster.class, getBoundingBox().inflate(16)).stream().findFirst().orElse(null);
            if (enemy != null) setTarget(enemy);
        } else if (activeAction.equals("follow")) {
            following = true;
        }
    }

    private double distanceToOwnerSqr() {
        if (level().getServer() == null) return 0;
        ServerPlayer owner = level().getServer().getPlayerList().getPlayerByName(ownerName);
        return owner == null ? 0 : distanceToSqr(owner);
    }

    @Override public boolean isInvulnerableTo(DamageSource source) {
        return reviveTicks > 0 || super.isInvulnerableTo(source);
    }

    @Override public void die(DamageSource source) {
        if (reviveTicks > 0) return;
        reviveTicks = 20 * 5;
        setHealth(1.0f);
        setNoAi(true);
        setInvulnerable(true);
        getNavigation().stop();
        setTarget(null);
        notifyOwner("我倒下了，正在原地恢复，背包物资没有丢失。");
    }

    public String getInventorySummary() { return inventorySummary(); }

    private String inventorySummary() {
        StringBuilder summary = new StringBuilder();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            if (summary.length() > 0) summary.append(", ");
            summary.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append("x").append(stack.getCount());
        }
        return summary.length() == 0 ? "empty" : summary.toString();
    }

    private void pickupNearbyItems() {
        for (ItemEntity itemEntity : level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(3.0))) {
            if (!itemEntity.isAlive() || itemEntity.hasPickUpDelay()) continue;
            ItemStack stack = itemEntity.getItem();
            if (stack.isEmpty()) continue;
            ItemStack remainder = inventory.addItem(stack.copy());
            int moved = stack.getCount() - remainder.getCount();
            if (moved <= 0) continue;
            if (remainder.isEmpty()) itemEntity.discard();
            else itemEntity.setItem(remainder);
            itemEntity.setPickUpDelay(5);
        }
    }

    private void eatFoodIfNeeded() {
        if (getHealth() > getMaxHealth() * 0.55f) return;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !stack.getItem().isEdible()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (id.equals("rotten_flesh") || id.equals("spider_eye") || id.equals("poisonous_potato") || id.equals("pufferfish")) continue;
            FoodProperties food = stack.getItem().getFoodProperties();
            if (food == null) continue;
            stack.shrink(1);
            heal(Math.max(2.0f, food.getNutrition() * 1.5f));
            inventory.setChanged();
            notifyOwner("我受伤了，吃了食物恢复生命。");
            return;
        }
    }

    private void protectOwner() {
        if (level().getServer() == null) return;
        ServerPlayer owner = level().getServer().getPlayerList().getPlayerByName(ownerName);
        if (owner == null) return;
        Monster threat = level().getEntitiesOfClass(Monster.class, owner.getBoundingBox().inflate(12.0)).stream()
            .filter(monster -> monster.getTarget() == owner).findFirst().orElse(null);
        if (threat != null) setTarget(threat);
    }

    private void notifyOwner(String message) {
        if (level().getServer() == null) return;
        ServerPlayer owner = level().getServer().getPlayerList().getPlayerByName(ownerName);
        if (owner != null) owner.sendSystemMessage(Component.literal("[Agnes] " + message));
    }

    private void shareWithOwner() {
        if (level().getServer() == null) return;
        ServerPlayer owner = level().getServer().getPlayerList().getPlayerByName(ownerName);
        if (owner == null) return;
        if (distanceToSqr(owner) > 16.0) {
            getNavigation().moveTo(owner, 1.1);
            return;
        }
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stored = inventory.getItem(slot);
            if (stored.isEmpty()) continue;
            ItemStack gift = stored.split(Math.min(16, stored.getCount()));
            owner.getInventory().placeItemBackInInventory(gift);
            owner.sendSystemMessage(Component.literal("[Agnes] 已把 " + gift.getHoverName().getString() + " x" + gift.getCount() + " 交给你。"));
            inventory.setChanged();
            return;
        }
        owner.sendSystemMessage(Component.literal("[Agnes] 我的背包里暂时没有可分享的物资。"));
    }

    private boolean gatherNearby(String target) {
        String wanted = target == null ? "log" : target.toLowerCase();
        BlockPos origin = blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, -2, -4), origin.offset(4, 2, 4))) {
            String id = BuiltInRegistries.BLOCK.getKey(level().getBlockState(pos).getBlock()).getPath();
            boolean match = (wanted.contains("log") || wanted.contains("wood") || wanted.contains("木")) && id.endsWith("_log")
                || (wanted.contains("stone") || wanted.contains("石")) && (id.equals("stone") || id.equals("cobblestone"))
                || (wanted.contains("food") || wanted.contains("食物") || wanted.contains("粮"))
                    && (id.equals("wheat") || id.equals("carrots") || id.equals("potatoes") || id.equals("beetroots") || id.equals("sweet_berry_bush"));
            if (match) { level().destroyBlock(pos, true, this); return true; }
        }
        return false;
    }
}
