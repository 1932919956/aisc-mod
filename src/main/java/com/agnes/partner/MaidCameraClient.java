package com.agnes.partner;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.lang.reflect.Field;
import java.util.Base64;
import java.util.concurrent.Executors;

/** One offscreen world render before the owner's normal frame. Never presents the maid framebuffer. */
@Mod.EventBusSubscriber(modid = AgnesPartnerMod.MOD_ID, value = Dist.CLIENT)
public final class MaidCameraClient {
    private static final java.util.concurrent.ExecutorService ENCODER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Agnes-maid-camera-encoder"); thread.setDaemon(true); return thread;
    });
    private static volatile boolean encoding;
    private static TextureTarget target;
    private static long nextCapture;
    private static long lastCapture;
    private static boolean failed;
    private static Object lastLevel;
    // Lazy reflection keeps optional capture failures out of class initialization and supports SRG production names.
    private static Field framebufferField, cameraField;
    private MaidCameraClient() {}

    private static final class MaidCamera extends Camera {
        @Override public void setup(BlockGetter level, Entity maid, boolean detached, boolean mirrored, float partial) {
            super.setup(level, maid, false, false, partial);
            // A new vanilla camera otherwise starts at feet height until its tick interpolation settles.
            setPosition(maid.getEyePosition(partial));
            setRotation(maid.getViewYRot(partial), maid.getViewXRot(partial));
        }
    }

    @SubscribeEvent public static void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        if (lastLevel != mc.level) {
            lastLevel = mc.level; failed = false; nextCapture = System.currentTimeMillis() + 5000;
            if (target != null) { target.destroyBuffers(); target = null; }
        }
        if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null
            || mc.screen != null || mc.isPaused() || !PartnerConfig.isVisionEnabled()) return;
        MaidVision.Target request = MaidVision.target(mc.player.getUUID());
        if (request == null || encoding || failed) return;
        long now = System.currentTimeMillis();
        if (now < nextCapture && (!MaidVision.requested(request.owner()) || now - lastCapture < 2000)) return;
        lastCapture = now;
        nextCapture = System.currentTimeMillis() + 15_000;
        if (!mc.level.dimension().location().toString().equals(request.dimension())) return;
        Entity maid = mc.level.getEntity(request.entityId());
        if (maid == null || !maid.isAlive() || !maid.getUUID().equals(request.maid())) {
            MaidVision.unavailable(request.owner(), "女仆未在客户端加载，暂用游戏状态"); return;
        }
        try {
            if (Minecraft.useShaderTransparency() || shadersActive()) {
                MaidVision.unavailable(request.owner(), "光影或极佳画质启用中，独立摄像头暂停；关闭光影并选流畅/高品质后自动恢复"); return;
            }
            if (framebufferField == null) framebufferField = ObfuscationReflectionHelper.findField(Minecraft.class, "f_91042_");
            if (cameraField == null) cameraField = ObfuscationReflectionHelper.findField(GameRenderer.class, "f_109054_");
            capture(mc, maid, request, event.renderTickTime);
        } catch (Exception | LinkageError error) {
            failed = true;
            mc.getMainRenderTarget().bindWrite(true);
            MaidVision.unavailable(request.owner(), "女仆摄像头与当前渲染环境不兼容，本次进入世界改用游戏状态");
            LogUtils.getLogger().warn("Agnes maid camera disabled for this level; game-state planning remains available", error);
        }
    }

    private static boolean shadersActive() throws ReflectiveOperationException {
        if (!net.minecraftforge.fml.ModList.get().isLoaded("oculus") && !net.minecraftforge.fml.ModList.get().isLoaded("iris")) return false;
        Class<?> api;
        try { api = Class.forName("net.irisshaders.iris.api.v0.IrisApi"); }
        catch (ClassNotFoundException olderVersion) { api = Class.forName("net.coderbot.iris.api.v0.IrisApi"); }
        Object instance = api.getMethod("getInstance").invoke(null);
        return (boolean)api.getMethod("isShaderPackInUse").invoke(instance);
    }

    static void capture(Minecraft mc, Entity maid, MaidVision.Target request, float partial) throws Exception {
        int width = 640;
        int height = Math.max(240, Math.min(640, (int)(width * (double)mc.getWindow().getHeight() / Math.max(1, mc.getWindow().getWidth()))));
        if (target == null || target.height != height) {
            if (target != null) target.destroyBuffers();
            target = new TextureTarget(width, height, true, Minecraft.ON_OSX);
        }
        RenderTarget originalTarget = mc.getMainRenderTarget();
        Entity originalEntity = mc.cameraEntity;
        Camera originalCamera = mc.gameRenderer.getMainCamera();
        CameraType originalType = mc.options.getCameraType();
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting sorting = RenderSystem.getVertexSorting();
        Matrix3f inverse = new Matrix3f(RenderSystem.getInverseViewRotationMatrix());
        PoseStack modelView = RenderSystem.getModelViewStack();
        MaidCamera camera = new MaidCamera();
        camera.setup(mc.level, maid, false, false, partial);
        NativeImage image = null;
        long captured = System.currentTimeMillis();
        modelView.pushPose();
        try {
            framebufferField.set(mc, target);
            cameraField.set(mc.gameRenderer, camera);
            mc.cameraEntity = maid;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            target.setClearColor(0, 0, 0, 1); target.clear(Minecraft.ON_OSX); target.bindWrite(true);
            RenderSystem.enableDepthTest(); RenderSystem.enableCull();
            // Fixed independent optics: no owner hand, HUD, hurt bob, nausea, zoom or crosshair picking.
            Matrix4f lens = new Matrix4f().perspective((float)Math.toRadians(70), (float)width / height,
                0.05f, Math.max(64, mc.options.getEffectiveRenderDistance() * 64));
            RenderSystem.setProjectionMatrix(lens, VertexSorting.DISTANCE_TO_ORIGIN);
            PoseStack view = new PoseStack();
            view.mulPose(Axis.XP.rotationDegrees(camera.getXRot()));
            view.mulPose(Axis.YP.rotationDegrees(camera.getYRot() + 180));
            RenderSystem.setInverseViewRotationMatrix(new Matrix3f(view.last().normal()).invert());
            mc.gameRenderer.lightTexture().updateLightTexture(partial);
            mc.levelRenderer.prepareCullFrustum(view, camera.getPosition(), lens);
            mc.levelRenderer.renderLevel(view, partial, System.nanoTime(), false, camera,
                mc.gameRenderer, mc.gameRenderer.lightTexture(), lens);
            image = Screenshot.takeScreenshot(target);
        } finally {
            // Restore even when a mod renderer throws. The normal owner render runs immediately after this event.
            mc.cameraEntity = originalEntity;
            mc.options.setCameraType(originalType);
            cameraField.set(mc.gameRenderer, originalCamera);
            framebufferField.set(mc, originalTarget);
            RenderSystem.setProjectionMatrix(projection, sorting);
            RenderSystem.setInverseViewRotationMatrix(inverse);
            modelView.popPose(); RenderSystem.applyModelViewMatrix();
            originalTarget.bindWrite(true);
        }
        NativeImage pixels = image;
        if (pixels == null) return;
        encoding = true;
        ENCODER.execute(() -> {
            try (pixels) {
                String png = Base64.getEncoder().encodeToString(pixels.asByteArray());
                MaidVision.accept(new MaidVision.Frame(request, captured, camera.getPosition(), camera.getYRot(), camera.getXRot(), png));
            } catch (Exception error) {
                MaidVision.unavailable(request.owner(), "女仆画面编码失败，暂用游戏状态");
            } finally { encoding = false; }
        });
    }
}
