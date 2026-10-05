package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskManager;
import com.google.gson.*;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;

/** Runs bounded steps selected by Agnes, stopping on failed prerequisites or human intervention. */
final class MaidPlan {
    private static final Map<UUID,Run> RUNS=new HashMap<>();
    /**
     * Actions a single plan step may use. This must stay in step with the executor: a whitelist that
     * lags behind makes the model's plan invalid, the whole plan is dropped, and she announces work
     * that then never happens. hunt, dig_shaft and visit_landmark were missing exactly that way.
     */
    private static final Set<String> ACTIONS=Set.of("craft","equip","gather","approach","place","smelt",
        "withdraw","deposit","build_house","hunt","dig_shaft","visit_landmark","look","scan_area");
    private static final Set<String> KINDS=Set.of("wood","berries","stone","coal","iron","animal","monster","any");
    private static final class Run {
        final Deque<JsonObject> steps;
        final long started;
        final String dimension;
        String stamp;
        String waiting="";
        Run(EntityMaid maid,Deque<JsonObject> steps) {
            this.steps=steps; started=maid.level().getGameTime(); dimension=maid.level().dimension().location().toString(); stamp=MaidBridge.stamp(maid);
        }
    }
    static boolean active(EntityMaid maid) { return RUNS.containsKey(maid.getUUID()); }
    static void clearAll() { RUNS.clear(); MaidBuilder.clear(); }
    static void clear(EntityMaid maid,String reason) {
        MaidBuilder.cancel(maid,reason);
        if(RUNS.remove(maid.getUUID())!=null) MaidBridge.mind(maid).putString("SurvivalPlan",reason);
    }
    static String start(EntityMaid maid,JsonObject plan) {
        Deque<JsonObject> steps=new ArrayDeque<>();
        try {
            JsonArray list=plan.getAsJsonArray("steps");
            if(list==null || list.isEmpty() || list.size()>8) return "未执行：生存计划需要 1～8 个步骤";
            for(JsonElement element:list) {
                JsonObject step=element.getAsJsonObject().deepCopy(); String action=field(step,"action");
                if(!ACTIONS.contains(action)) return "未执行：连续计划里有不支持的行动「"+action+"」";
                String required=switch(action) {
                    case "craft" -> "recipe_id";
                    case "equip","place","smelt" -> "item_id";
                    case "build_house" -> "site_id";
                    case "dig_shaft","scan_area" -> "";                       // depth / nothing needed
                    case "hunt" -> "target_id";                               // target_kind is the fallback
                    case "look" -> "";                                        // yaw/pitch or target_id, checked at run time
                    default -> "target_id";
                };
                if(!required.isEmpty() && field(step,required).isBlank()
                    && !(action.equals("gather") && KINDS.contains(field(step,"target_kind")))
                    && !(action.equals("hunt") && KINDS.contains(field(step,"target_kind"))))
                    return "未执行：生存步骤缺少材料、配方或目标 ID";
                if(Set.of("withdraw","deposit","build_house").contains(action)&&field(step,"item_id").isBlank())return "未执行：存取或建造步骤缺少物品 ID";
                int count=step.has("count")?step.get("count").getAsInt():1;
                if(count<1 || count>8 || steps.size()+count>16) return "未执行：单轮最多 16 次实际动作，每步最多重复 8 次";
                for(int i=0;i<count;i++) steps.add(step.deepCopy());
            }
        } catch(RuntimeException invalid) { return "未执行：生存步骤格式错误"; }
        clear(maid,"已替换旧生存计划"); RUNS.put(maid.getUUID(),new Run(maid,steps));
        MaidBridge.mind(maid).putString("SurvivalPlan","Agnes 已安排 "+steps.size()+" 次动作，逐步检查实际条件");
        return MaidBridge.mind(maid).getString("SurvivalPlan");
    }
    static String field(JsonObject object,String key) {
        try { return object.has(key)?object.get(key).getAsString():""; } catch(RuntimeException invalid) {return "";}
    }
    static boolean succeeded(String action,String result) {
        return switch(action) {
            case "craft" -> result.startsWith("已实际合成");
            case "equip" -> result.startsWith("已装备");
            case "place" -> result.startsWith("已放置");
            case "smelt" -> result.startsWith("已放入真实熔炉");
            case "gather","approach" -> result.startsWith("正在走向");
            case "withdraw","deposit" -> result.startsWith("已实际")&&!result.contains("仅完成部分数量");
            case "build_house" -> result.startsWith("正在建造")||result.startsWith("正在续建");
            case "hunt" -> result.startsWith("正在追")||result.startsWith("实际猎取完成")||result.startsWith("猎物已经不在");
            case "dig_shaft" -> result.startsWith("开始向下挖")||result.startsWith("已经下到地下")||result.startsWith("正在挖第");
            case "visit_landmark" -> result.startsWith("正在走向我记得的");
            case "look","scan_area" -> result.startsWith("已转向")||result.startsWith("开始环视");
            default -> false;
        };
    }
    static boolean tick(EntityMaid maid,ServerPlayer owner) {
        Run run=RUNS.get(maid.getUUID()); if(run==null) return false;
        if(!MaidBridge.mind(maid).getBoolean("Autonomy") || MaidBridge.find(owner)!=maid || !maid.isAlive() || !owner.isAlive()
            || maid.level()!=owner.level() || !run.dimension.equals(maid.level().dimension().location().toString())
            || maid.distanceToSqr(owner)>MaidBridge.ACTIVITY_RANGE*MaidBridge.ACTIVITY_RANGE || maid.isSleeping() || maid.isOrderedToSit()
            || maid.getTarget()!=null || MaidBridge.maidReformBusy(maid) || GoMaid.playing(maid,owner)
            || MaidBridge.mind(maid).getBoolean("Recovering") || maid.getHealth()<maid.getMaxHealth()*0.35f
            || !MaidBridge.stamp(maid).equals(run.stamp) || maid.level().getGameTime()-run.started>6000) {
            stop(maid,"生存计划被手动操作、休息、战斗、距离变化或超时中断"); return true;
        }
        if(MaidFieldwork.active(maid) || MaidWorkshop.active(maid) || MaidBuilder.active(maid)) return true;
        if(!run.waiting.isEmpty()) {
            String success=run.waiting.equals("build")?"BuildSuccess":run.waiting.equals("smelt")?"WorkshopSuccess":"FieldworkSuccess";
            if(!MaidBridge.mind(maid).getBoolean(success)) { stop(maid,"上一步未完成，停止剩余步骤；"+MaidBridge.mind(maid).getString("Outcome")); return true; }
            run.waiting="";
        }
        JsonObject step=run.steps.poll();
        if(step==null) { clear(maid,"本轮 Agnes 生存步骤已全部完成，等待下一轮观察"); MaidBridge.scheduleNext(maid); return false; }
        String action=field(step,"action");
        if(action.equals("gather") && !field(step,"target_kind").isBlank()) {
            String kind=field(step,"target_kind"); String target="";
            for(JsonElement element:MaidFieldwork.describe(maid).getAsJsonArray("observed_targets")) {
                JsonObject option=element.getAsJsonObject();
                if(field(option,"kind").equals(kind) && option.get("tool_ready").getAsBoolean()) { target=field(option,"target_id");break; }
            }
            if(target.isEmpty()) {stop(maid,"没有找到当前可采集的 "+kind+"，或镐等级不够；停止剩余步骤等待 Agnes 重新规划");return true;}
            step.addProperty("target_id",target);
        }
        String result=MaidBridge.execute(maid,owner,step,true);
        MaidBridge.outcome(maid,result);
        if(!succeeded(action,result)) {stop(maid,"生存步骤失败："+result);return true;}
        run.stamp=MaidBridge.stamp(maid);
        if(MaidFieldwork.active(maid)) run.waiting="fieldwork";
        if(MaidWorkshop.active(maid)) run.waiting="smelt";
        if(MaidBuilder.active(maid)) run.waiting="build";
        MaidBridge.mind(maid).putString("SurvivalPlan",result+"；剩余 "+run.steps.size()+" 次动作");
        return true;
    }
    private static void stop(EntityMaid maid,String reason) {
        clear(maid,reason); MaidFieldwork.cancel(maid,reason); MaidWorkshop.cancel(maid,reason);
        MaidBridge.outcome(maid,reason); MaidBridge.scheduleNext(maid);
    }
}
