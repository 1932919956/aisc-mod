package com.agnes.partner;

import com.github.tartaricacid.touhoulittlemaid.api.ILittleMaid;
import com.github.tartaricacid.touhoulittlemaid.api.LittleMaidExtension;
import com.github.tartaricacid.touhoulittlemaid.ai.agent.tool.ToolRegister;
import com.mojang.logging.LogUtils;

/**
 * Adds read-only companion state reporting to Touhou Little Maid's native agent.
 *
 * Agnes autonomous survival planning currently uses the companion's own planning request and
 * executor. The maid's native agent is a separate conversation path; its registered tools do not
 * yet execute those autonomous survival actions.
 *
 * Loaded only when Touhou Little Maid is present: the annotation makes this a soft dependency.
 */
@LittleMaidExtension
public class AgnesMaidExtension implements ILittleMaid {

    @Override
    public void registerAITool(ToolRegister register) {
        register.register(new MaidSituationTool());
        // Promaid-compatible command surface. These tools delegate into Agnes' single executor,
        // so native TLM AI cannot start a second movement controller in free mode.
        register.register(new AgnesCommandTool());
        LogUtils.getLogger().info("[agnespartner] registered {} companion tool(s) with the maid agent",
            ToolRegister.getAllTools().size());
    }
}
