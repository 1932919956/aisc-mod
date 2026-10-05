package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ITool;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.LLMCallback;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.ObjectParameter;
import com.github.tartaricacid.touhoulittlemaid.ai.service.function.schema.parameter.StringParameter;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Reports what this companion is really doing and what is stopping her.
 *
 * This is the first tool registered with the maid's native agent. It exists because the hardest
 * failure so far was silent: a plan that looked reasonable but where nothing happened, with no
 * visible reason. One authoritative way to ask "what is my real state" removes that guesswork, and
 * being read-only it can never itself damage the world.
 */
public final class MaidSituationTool implements ITool<MaidSituationTool.Query> {

    private static final Codec<Query> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.optionalFieldOf("topic", "all").forGetter(Query::topic)
    ).apply(instance, Query::new));

    @Override
    public String id() { return "maid_situation"; }

    @Override
    public String summary(EntityMaid maid) {
        return "Report what this maid is really doing and what is stopping her: work mode, whether a "
            + "gather/hunt/dig/smelt/build action is running, health, food, position, whether she is "
            + "allowed to change the world, and the result of her last real action. Call this BEFORE "
            + "planning work and again after a plan seemed to have no effect, so you act on her real "
            + "state instead of assuming what happened.";
    }

    @Override
    public ObjectParameter parameters(ObjectParameter root, EntityMaid maid) {
        root.addProperties("topic", StringParameter.create()
            .setDescription("Which part to report: all, work, needs, or world. Defaults to all.")
            .addEnumValues("all", "work", "needs", "world"), false);
        return root;
    }

    @Override
    public Codec<Query> codec() { return CODEC; }

    @Override
    public LLMCallback onCall(String toolCallId, Query query, LLMCallback callback) {
        EntityMaid maid = callback.getMaid();
        if (maid == null) return callback.addToolResult("No maid is available for this call.", toolCallId);
        return callback.addToolResult(report(maid, query.topic()), toolCallId);
    }

    @Override
    public String invocationSummary(Query query) { return "maid_situation " + query.topic(); }

    /** The authoritative state report, written for the model rather than for a human log. */
    static String report(EntityMaid maid, String topic) {
        String wanted = topic == null ? "all" : topic.trim().toLowerCase(java.util.Locale.ROOT);
        StringBuilder text = new StringBuilder();

        text.append("work_mode=").append(maid.getTask().getUid());
        text.append(" following=").append(!maid.isHomeModeEnable());
        text.append(" sitting=").append(maid.isOrderedToSit());
        text.append(" schedule=").append(maid.getSchedule().name());
        text.append(" running_action=").append(runningAction(maid));
        text.append(" last_action_result=").append(trim(MaidBridge.mind(maid).getString("Outcome")));

        if (wanted.equals("all") || wanted.equals("needs")) {
            text.append(" health=").append(Math.round(maid.getHealth())).append("/").append(Math.round(maid.getMaxHealth()));
            text.append(" food_in_backpack=").append(MaidSurvival.foodCount(maid));
            text.append(" farm_work_available=").append(MaidSurvival.farmWorkAvailable(maid));
            text.append(" holding=").append(itemName(maid));
            text.append(" recovering=").append(MaidBridge.mind(maid).getBoolean("Recovering"));
        }
        if (wanted.equals("all") || wanted.equals("world")) {
            BlockPos pos = maid.blockPosition();
            text.append(" position=").append(pos.getX()).append(",").append(pos.getY()).append(",").append(pos.getZ());
            text.append(" dimension=").append(maid.level().dimension().location());
            text.append(" mob_griefing_rule=")
                .append(net.minecraftforge.event.ForgeEventFactory.getMobGriefingEvent(maid.level(), maid));
            text.append(" companion_world_edits_allowed=").append(PartnerConfig.mayEditWorld(maid.level(), maid));
            text.append(" day_time=").append(maid.level().getDayTime() % 24000L);
        }
        text.append(" remembered_places=").append(MaidLandmarks.count(maid));
        return text.toString();
    }

    /** Which of the companion's own actions is in flight, told apart for the model. */
    private static String runningAction(EntityMaid maid) {
        if (MaidFieldwork.active(maid)) return "gather";
        if (MaidHunt.active(maid)) return "hunt";
        if (MaidDig.active(maid)) return "dig";
        if (MaidWorkshop.active(maid)) return "smelt";
        if (MaidBuilder.active(maid)) return "build";
        if (MaidLandmarks.active(maid)) return "walking_to_remembered_place";
        return "none";
    }

    /** An execution failure must reach the model, so an empty history never reads as success. */
    private static String trim(String value) {
        if (value == null || value.isBlank()) return "none_yet";
        return value.length() > 160 ? value.substring(0, 160) + "..." : value;
    }

    private static String itemName(EntityMaid maid) {
        ItemStack stack = maid.getMainHandItem();
        if (stack.isEmpty()) return "empty_hand";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    /** Only the optional topic; every field is optional so a bare call returns the full report. */
    public record Query(String topic) {}
}
