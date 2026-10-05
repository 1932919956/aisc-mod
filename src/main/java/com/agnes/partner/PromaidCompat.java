package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraftforge.fml.ModList;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import java.util.Optional;

/**
 * Soft integration with Promaid. Both extensions attach behaviors to the same TLM maid; Agnes keeps
 * its authoritative survival executor while Promaid's independent safety, equipment, lighting and
 * handbook features continue to run. Promaid remains optional and is never a hard dependency.
 */
final class PromaidCompat {
    private PromaidCompat() {}

    static boolean loaded() {
        return ModList.get().isLoaded("promaid");
    }

    /** Kept as a compatibility reader for old configs; execution is cooperative in both settings. */
    static boolean yieldControl() { return false; }

    static String status() {
        if (!loaded()) return "未检测到 Promaid，Agnes 独立控制";
        return "已检测到 Promaid，采用协作模式：Promaid 保留增强功能，Agnes 保留生存采集与挖掘执行";
    }

    /**
     * TLM's snowball behavior stores its victim in ATTACK_TARGET and stops navigation after the
     * throw animation. During an Agnes fieldwork step the owner is a friendly victim, not combat
     * work. Clear only that target so real hostile targets still interrupt and trigger self-defense.
     */
    static void protectAgnesWork(EntityMaid maid) {
        if (!MaidFieldwork.active(maid)) return;
        Optional<LivingEntity> brainTarget = maid.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET);
        if (brainTarget.isPresent() && isOwner(maid, brainTarget.get())) {
            maid.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
            maid.getNavigation().stop();
        }
        if (maid.getTarget() != null && isOwner(maid, maid.getTarget())) {
            maid.setTarget(null);
        }
    }

    private static boolean isOwner(EntityMaid maid, LivingEntity target) {
        return maid.getOwnerUUID() != null && maid.getOwnerUUID().equals(target.getUUID());
    }
}
