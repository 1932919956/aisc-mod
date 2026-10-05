package com.agnes.partner;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = AgnesPartnerMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AgnesPartnerClient {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(AgnesPartnerMod.COMPANION.get(), CompanionRenderer::new);
    }

    private static final class CompanionRenderer extends MobRenderer<AgnesCompanion, HumanoidModel<AgnesCompanion>> {
        private static final ResourceLocation TEXTURE = new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");
        private CompanionRenderer(EntityRendererProvider.Context context) { super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5f); }
        @Override public ResourceLocation getTextureLocation(AgnesCompanion entity) { return TEXTURE; }
        @Override protected void scale(AgnesCompanion entity, PoseStack stack, float partialTick) { stack.scale(0.95f, 0.95f, 0.95f); }
    }
}
