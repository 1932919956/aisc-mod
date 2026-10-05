package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.api.event.MaidDeathEvent;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.Comparator;

/** Independent rescue, deliberately uses TLM's pre-death hook after armor/totems/baubles. */
final class MaidRescue {
    static final String DATA = "AgnesRescue";
    static CompoundTag state(EntityMaid maid) {
        var root = maid.getPersistentData();
        if (!root.contains(DATA)) root.put(DATA, new CompoundTag());
        return root.getCompound(DATA);
    }
    static boolean down(EntityMaid maid) { return maid.getPersistentData().getCompound(DATA).getBoolean("Down"); }
    static boolean busy(EntityMaid maid) {
        var tag = maid.getPersistentData().getCompound(DATA);
        return tag.getBoolean("Down") || tag.hasUUID("Helping");
    }
    private static final class External {
        static final boolean INSTALLED = net.minecraftforge.fml.ModList.get().getMods().stream()
            .anyMatch(m -> m.getModId().replace("_", "").equalsIgnoreCase("maidreform"));
    }
    static boolean externalInstalled() { return External.INSTALLED; }
    @SubscribeEvent public void loaded(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof EntityMaid maid && down(maid)) {
            state(maid).remove("Rescuer"); state(maid).remove("Started");
        }
    }
    private static boolean exceptional(net.minecraft.world.damagesource.DamageSource source) {
        return source.is(DamageTypes.GENERIC_KILL) || source.is(DamageTypes.FELL_OUT_OF_WORLD);
    }
    @SubscribeEvent(priority=EventPriority.LOWEST)
    public void death(MaidDeathEvent event) {
        EntityMaid maid = event.getMaid();
        if (maid.level().isClientSide || !maid.isTame() || maid.getOwnerUUID() == null || externalInstalled() || exceptional(event.getSource())) return;
        event.setCanceled(true); knockDown(maid);
    }
    static void knockDown(EntityMaid maid) {
        var tag = state(maid);
        if (!tag.getBoolean("Down")) {
            stopHelping(maid);
            tag.putBoolean("OldSit", maid.isOrderedToSit()); tag.putBoolean("OldNoAI", maid.isNoAi());
            tag.putBoolean("Down", true); tag.remove("Rescuer"); tag.remove("Started");
            MaidBridge.mind(maid).putInt("PlanRevision", MaidBridge.mind(maid).getInt("PlanRevision") + 1);
            MaidFieldwork.cancel(maid, "女仆倒地，已停止工作，靠近并右键可救援");
            var owner = maid.getServer().getPlayerList().getPlayer(maid.getOwnerUUID());
            if (owner != null) owner.sendSystemMessage(Component.literal("[" + maid.getName().getString() + "] 倒地了！靠近到 4 格内右键，停留 5 秒救援；附近空闲的自有女仆也会帮忙。"));
        }
        maid.setHealth(1); maid.setTarget(null); maid.stopRiding(); maid.stopSleeping();
        maid.setNoAi(true); maid.setOrderedToSit(true); maid.getNavigation().stop();
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET); maid.clearFire();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void attack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid) || maid.level().isClientSide || exceptional(event.getSource())) return;
        if (down(maid) || maid.getPersistentData().getCompound(DATA).getLong("ProtectedUntil") > maid.level().getGameTime()) event.setCanceled(true);
    }
    @SubscribeEvent public void hurt(LivingHurtEvent event) {
        if (event.getEntity().level().isClientSide || !(event.getEntity() instanceof EntityMaid || event.getEntity() instanceof ServerPlayer)) return;
        event.getEntity().getPersistentData().putLong("AgnesLastHurt", event.getEntity().level().getGameTime());
        if (event.getEntity() instanceof EntityMaid maid && busy(maid) && !down(maid)) stopHelping(maid);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof EntityMaid maid) || !(event.getEntity() instanceof ServerPlayer player) || !down(maid)) return;
        event.setCanceled(true); event.setCancellationResult(InteractionResult.SUCCESS);
        beginPlayer(maid, player);
    }
    static boolean beginPlayer(EntityMaid maid, ServerPlayer player) {
        if (!down(maid) || !player.isAlive() || player.level() != maid.level() || !player.getUUID().equals(maid.getOwnerUUID())
            || player.distanceToSqr(maid) > 16 || !player.hasLineOfSight(maid)) return false;
        var tag = state(maid);
        if (tag.hasUUID("Rescuer") && tag.getUUID("Rescuer").equals(player.getUUID())) return true;
        releaseHelper(maid);
        tag.putUUID("Rescuer", player.getUUID()); tag.putBoolean("Player", true);
        tag.putLong("Started", maid.level().getGameTime());
        player.displayClientMessage(Component.literal("救援中：留在女仆 4 格内 5 秒；离开、受伤会中断。"), true);
        return true;
    }
    private static void stopHelping(EntityMaid helper) {
        var tag = state(helper);
        if (!tag.hasUUID("Helping")) return;
        tag.remove("Helping"); helper.getNavigation().stop(); helper.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        helper.setNoAi(tag.getBoolean("HelperOldNoAI")); tag.remove("HelperOldNoAI");
        var previous = net.minecraft.resources.ResourceLocation.tryParse(tag.getString("HelperOldTask"));
        if (previous != null) com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager.findTask(previous).ifPresent(helper::setTask);
        helper.setHomeModeEnable(tag.getBoolean("HelperOldHome"));
        helper.setOrderedToSit(tag.getBoolean("HelperOldSit"));
        tag.remove("HelperOldTask"); tag.remove("HelperOldHome"); tag.remove("HelperOldSit");
    }
    private static void releaseHelper(EntityMaid maid) {
        var tag = state(maid);
        if (!tag.getBoolean("Player") && tag.hasUUID("Rescuer")
            && ((ServerLevel)maid.level()).getEntity(tag.getUUID("Rescuer")) instanceof EntityMaid helper) stopHelping(helper);
        tag.remove("Rescuer"); tag.remove("Started");
    }
    @SubscribeEvent public void tick(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof EntityMaid maid) || maid.level().isClientSide) return;
        if (maid.tickCount % 10 != 0) return;
        tickMaid(maid);
    }
    static void tickMaid(EntityMaid maid) {
        var existing = maid.getPersistentData().getCompound(DATA);
        if (!existing.getBoolean("Down")) {
            if (existing.hasUUID("Helping")) {
                var target = ((ServerLevel)maid.level()).getEntity(existing.getUUID("Helping"));
                if (!(target instanceof EntityMaid patient) || !down(patient) || !state(patient).hasUUID("Rescuer")
                    || !state(patient).getUUID("Rescuer").equals(maid.getUUID())) stopHelping(maid);
            }
            return;
        }
        var tag = state(maid); long now = maid.level().getGameTime();
        maid.setHealth(1); maid.setTarget(null); maid.setNoAi(true); maid.setOrderedToSit(true); maid.clearFire(); maid.setAirSupply(maid.getMaxAirSupply());
        if (maid.tickCount % 40 == 0) ((ServerLevel)maid.level()).sendParticles(ParticleTypes.HEART, maid.getX(), maid.getY()+1, maid.getZ(), 1, 0.1, 0.1, 0.1, 0);
        if (!tag.hasUUID("Rescuer") && maid.tickCount % 40 == 0) {
            var helper = maid.level().getEntitiesOfClass(EntityMaid.class, maid.getBoundingBox().inflate(16), m -> m != maid && m.isAlive()
                && maid.getOwnerUUID() != null && maid.getOwnerUUID().equals(m.getOwnerUUID()) && !MaidBridge.maidReformBusy(m)
                && !m.isNoAi() && !m.isOrderedToSit() && !m.isSleeping() && !m.isPassenger() && m.getTarget() == null
                && m.getHealth() > m.getMaxHealth() * 0.5f)
                .stream().min(Comparator.comparingDouble(m -> m.distanceToSqr(maid))).orElse(null);
            if (helper != null) {
                tag.putUUID("Rescuer", helper.getUUID()); tag.putBoolean("Player", false); tag.putLong("Assigned", now);
                state(helper).putUUID("Helping", maid.getUUID()); state(helper).putBoolean("HelperOldNoAI", helper.isNoAi());
                state(helper).putString("HelperOldTask", helper.getTask().getUid().toString());
                state(helper).putBoolean("HelperOldHome", helper.isHomeModeEnable());
                state(helper).putBoolean("HelperOldSit", helper.isOrderedToSit());
                helper.setTask(com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager.getIdleTask());
                helper.setHomeModeEnable(true); MaidFieldwork.cancel(helper, "前往救援同伴");
            }
        }
        if (!tag.hasUUID("Rescuer")) return;
        net.minecraft.world.entity.LivingEntity rescuer;
        if (tag.getBoolean("Player")) rescuer = maid.getServer().getPlayerList().getPlayer(tag.getUUID("Rescuer"));
        else rescuer = ((ServerLevel)maid.level()).getEntity(tag.getUUID("Rescuer")) instanceof EntityMaid helper ? helper : null;
        advance(maid, rescuer, now);
    }
    static void advance(EntityMaid maid, net.minecraft.world.entity.LivingEntity rescuer, long now) {
        var tag = state(maid);
        if (rescuer == null || !rescuer.isAlive() || rescuer.level() != maid.level()
            || (tag.getBoolean("Player") ? !rescuer.getUUID().equals(maid.getOwnerUUID())
                : !(rescuer instanceof EntityMaid helper) || !java.util.Objects.equals(helper.getOwnerUUID(), maid.getOwnerUUID()) || down(helper)
                || !state(helper).hasUUID("Helping") || !state(helper).getUUID("Helping").equals(maid.getUUID()))
            || (rescuer.getPersistentData().contains("AgnesLastHurt") && rescuer.getPersistentData().getLong("AgnesLastHurt") >= (tag.contains("Started") ? tag.getLong("Started") : tag.getLong("Assigned")))) {
            releaseHelper(maid); return;
        }
        double distance = rescuer.distanceToSqr(maid);
        if (!tag.getBoolean("Player") && rescuer instanceof EntityMaid helper && (distance > 16 || !helper.hasLineOfSight(maid))) {
            helper.setOrderedToSit(false);
            tag.remove("Started");
            if (distance > 24*24 || now - tag.getLong("Assigned") > 400) { releaseHelper(maid); return; }
            helper.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new net.minecraft.world.entity.ai.memory.WalkTarget(maid, 0.65f, 1));
            if (maid.tickCount % 20 == 0) helper.getNavigation().moveTo(maid, 0.65);
            return;
        }
        if (distance > 16 || !rescuer.hasLineOfSight(maid)) { releaseHelper(maid); return; }
        if (rescuer instanceof EntityMaid helper) { helper.setOrderedToSit(true); helper.getNavigation().stop(); helper.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET); }
        if (!tag.contains("Started")) tag.putLong("Started", now);
        if (rescuer instanceof ServerPlayer player) player.displayClientMessage(Component.literal("救援中… " + Math.min(100, (now-tag.getLong("Started"))) + "%"), true);
        if (now - tag.getLong("Started") >= 100) revive(maid);
    }
    static void revive(EntityMaid maid) {
        var tag = state(maid); releaseHelper(maid);
        tag.putBoolean("Down", false); maid.setNoAi(tag.getBoolean("OldNoAI")); maid.setOrderedToSit(tag.getBoolean("OldSit"));
        maid.setHealth(Math.max(1, maid.getMaxHealth() * 0.45f)); maid.clearFire();
        tag.putLong("ProtectedUntil", maid.level().getGameTime() + 100);
        MaidBridge.outcome(maid, "救援完成，恢复 45% 生命，获得 5 秒保护");
        ((ServerLevel)maid.level()).sendParticles(ParticleTypes.HAPPY_VILLAGER, maid.getX(), maid.getY()+1, maid.getZ(), 8, 0.3, 0.3, 0.3, 0);
    }
}
