package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.google.gson.JsonObject;

/** Promaid-style native-agent bridge. The tool exposes useful commands without creating a second AI
 * loop: all world-changing actions go through the already validated Agnes executor. */
public final class AgnesCommandTool implements ITool<AgnesCommandTool.Request> {
    private static final Codec<Request> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("action").forGetter(Request::action),
        Codec.STRING.optionalFieldOf("target_id", "").forGetter(Request::targetId),
        Codec.STRING.optionalFieldOf("target_kind", "").forGetter(Request::targetKind),
        Codec.STRING.optionalFieldOf("item_id", "").forGetter(Request::itemId),
        Codec.STRING.optionalFieldOf("recipe_id", "").forGetter(Request::recipeId),
        Codec.STRING.optionalFieldOf("task_id", "").forGetter(Request::taskId),
        Codec.STRING.optionalFieldOf("goal", "").forGetter(Request::goal)
    ).apply(i, Request::new));

    @Override public String id() { return "agnes_command"; }
    @Override public String summary(EntityMaid maid) {
        return "Execute one supported Agnes survival action through the authoritative executor. "
            + "Use action hunt, gather, craft, equip, place, smelt, task, approach, explore, follow, "
            + "stay, inspect_storage, withdraw or deposit. Always use observed IDs; never invent targets.";
    }
    @Override public ObjectParameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("action", StringParameter.create().setDescription("Action to execute"));
        root.addProperties("target_id", StringParameter.create().setDescription("Observed target ID"), false);
        root.addProperties("target_kind", StringParameter.create().setDescription("wood, berries, stone, coal, iron or animal"), false);
        root.addProperties("item_id", StringParameter.create().setDescription("Observed item ID"), false);
        root.addProperties("recipe_id", StringParameter.create().setDescription("Observed recipe ID"), false);
        root.addProperties("task_id", StringParameter.create().setDescription("Exact available task ID"), false);
        root.addProperties("goal", StringParameter.create().setDescription("Short ongoing goal"), false);
        return root;
    }
    @Override public Codec<Request> codec() { return CODEC; }
    @Override public LLMCallback onCall(String toolCallId, Request request, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (maid == null || maid.level().isClientSide) return callback.addToolResult("No server maid is available", toolCallId);
        JsonObject plan = new JsonObject();
        plan.addProperty("action", request.action());
        plan.addProperty("target_id", request.targetId());
        plan.addProperty("target_kind", request.targetKind());
        plan.addProperty("item_id", request.itemId());
        plan.addProperty("recipe_id", request.recipeId());
        plan.addProperty("task_id", request.taskId());
        plan.addProperty("goal", request.goal());
        var owner = maid.getServer() == null || maid.getOwnerUUID() == null ? null
            : maid.getServer().getPlayerList().getPlayer(maid.getOwnerUUID());
        if (owner == null) return callback.addToolResult("Owner is offline; action was not executed", toolCallId);
        String result = MaidBridge.execute(maid, owner, plan, false);
        return callback.addToolResult(result, toolCallId);
    }
    @Override public String invocationSummary(Request request) { return "agnes_command " + request.action(); }
    public record Request(String action, String targetId, String targetKind, String itemId,
                          String recipeId, String taskId, String goal) {}
}
