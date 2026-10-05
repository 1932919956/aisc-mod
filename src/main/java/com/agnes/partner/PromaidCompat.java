package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.api.task.FunctionCallSwitchResult;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.ModList;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Soft integration with Promaid. Both extensions attach behaviors to the same TLM maid; Agnes keeps
 * its authoritative survival executor while Promaid's independent safety, equipment, lighting and
 * handbook features continue to run. Promaid remains optional and is never a hard dependency.
 */
final class PromaidCompat {
    private PromaidCompat() {}

    private static final String AUTO_EQUIP = "com.maidsmart.task.MaidToolAutoEquip";
    private static final String COMBAT_COMPAT = "com.maidsmart.combat.CombatTaskCompat";
    private static final String SELF_PRESERVATION = "com.maidsmart.combat.SelfPreservationBehavior";
    private static final String WORK_AREA = "com.maidsmart.follow.WorkAreaClamp";
    private static final String BD_DEPOSIT = "com.maidsmart.bd.MaidBdDeposit";
    private static volatile Method autoEquipMethod;
    private static volatile Method prepareSwitchMethod;
    private static volatile Method selfPreservingMethod;
    private static volatile Method workAreaDescribeMethod;
    private static volatile Method bdEnabledMethod;

    static boolean loaded() {
        return ModList.get().isLoaded("promaid");
    }

    /** Kept as a compatibility reader for old configs; execution is cooperative in both settings. */
    static boolean yieldControl() { return false; }

    static String status() {
        if (!loaded()) return "未检测到 Promaid，Agnes 独立控制";
        return "已检测到 Promaid，采用协作模式：自保、自动装备、照明、补种、仓储与跨维度跟随由 Promaid 运行；Agnes 负责规划和具体生存目标";
    }

    /** Detailed state is deliberately data-only; the AI can choose around Promaid's real status. */
    static JsonObject describe(EntityMaid maid) {
        JsonObject state = new JsonObject();
        state.addProperty("installed", loaded());
        state.addProperty("mode", loaded() ? "cooperative" : "agnes_only");
        if (!loaded() || maid == null) return state;
        state.addProperty("promaid_task", isPromaidTask(maid));
        state.addProperty("self_preserving", invokeBoolean(SELF_PRESERVATION, "isSelfPreserving", selfPreservingMethod, maid));
        state.addProperty("overflow_storage", invokeBoolean(BD_DEPOSIT, "isOn", bdEnabledMethod, maid));
        String area = invokeString(WORK_AREA, "describe", workAreaDescribeMethod, maid);
        if (!area.isBlank()) state.addProperty("work_area", area);
        state.addProperty("automatic_features", "自保逃生、自动换装、持灯、补种、压缩/溢出仓储、跨维度跟随（由 Promaid 事件系统按其配置运行）");
        state.addProperty("control_rule", "Agnes 正在采集/竖井/建造时不让 Promaid 的导航任务抢路；危险、自保、装备和存储安全行为仍可介入");
        return state;
    }

    /** Promaid's own work tasks use the stable maid_smart namespace. */
    static boolean isPromaidTask(EntityMaid maid) {
        if (!loaded() || maid == null || maid.getTask() == null) return false;
        try {
            ResourceLocation uid = maid.getTask().getUid();
            return uid != null && "maid_smart".equals(uid.getNamespace());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Let Promaid native tasks keep their own autonomy instead of being mistaken for manual TLM work. */
    static void assistNativeTask(EntityMaid maid) {
        if (!loaded() || !isPromaidTask(maid) || maid.getTask() == null) return;
        invokeVoid(AUTO_EQUIP, "ensureForTask", autoEquipMethod, maid);
    }

    /** Reuse Promaid's combat preflight when it is present, with a complete fallback when absent. */
    static FunctionCallSwitchResult prepareSwitch(EntityMaid maid, IMaidTask task) {
        if (!loaded() || maid == null || task == null) return null;
        try {
            Method method = prepareSwitchMethod;
            if (method == null) {
                Class<?> type = Class.forName(COMBAT_COMPAT);
                method = type.getMethod("prepareSwitch", EntityMaid.class, IMaidTask.class);
                prepareSwitchMethod = method;
            }
            Object result = method.invoke(null, maid, task);
            return result instanceof FunctionCallSwitchResult value ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean invokeBoolean(String className, String methodName, Method cached, EntityMaid maid) {
        try {
            Method method = cached;
            if (method == null) {
                method = Class.forName(className).getMethod(methodName, EntityMaid.class);
                if (className.equals(SELF_PRESERVATION)) selfPreservingMethod = method;
                if (className.equals(BD_DEPOSIT)) bdEnabledMethod = method;
            }
            Object value = method.invoke(null, maid);
            return value instanceof Boolean b && b;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String invokeString(String className, String methodName, Method cached, EntityMaid maid) {
        try {
            Method method = cached;
            if (method == null) {
                method = Class.forName(className).getMethod(methodName, EntityMaid.class);
                if (className.equals(WORK_AREA)) workAreaDescribeMethod = method;
            }
            Object value = method.invoke(null, maid);
            return value == null ? "" : value.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void invokeVoid(String className, String methodName, Method cached, EntityMaid maid) {
        try {
            Method method = cached;
            if (method == null) {
                method = Class.forName(className).getMethod(methodName, EntityMaid.class);
                if (className.equals(AUTO_EQUIP)) autoEquipMethod = method;
            }
            method.invoke(null, maid);
        } catch (Throwable ignored) {
            // Optional integration must never make an Agnes tick fail.
        }
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
