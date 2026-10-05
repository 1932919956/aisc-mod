package com.agnes.partner;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.File;

@Mod.EventBusSubscriber(modid = AgnesPartnerMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AgnesVisionClient {
    private static int captureCooldown = 20;
    private static volatile boolean writing;
    private static final java.util.concurrent.ExecutorService ENCODER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Agnes-screenshot-encoder"); thread.setDaemon(true); return thread;
    });

    private AgnesVisionClient() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!PartnerConfig.isVisionEnabled()) return;
        Minecraft minecraft = Minecraft.getInstance();
        // The bound maid uses her own in-memory camera frames, never this legacy owner screenshot.
        if (minecraft.player != null && MaidVision.target(minecraft.player.getUUID()) != null) return;
        if (minecraft.level == null || minecraft.screen != null || writing || --captureCooldown > 0) return;
        captureCooldown = 20 * 15;
        String path = PartnerConfig.getScreenshotPath();
        if (path.isBlank()) return;
        try {
            NativeImage image = Screenshot.takeScreenshot(minecraft.getMainRenderTarget());
            writing = true;
            ENCODER.execute(() -> {
                try (image) {
                    File file = new File(path);
                    File parent = file.getParentFile();
                    if (parent != null) parent.mkdirs();
                    double scale = Math.min(1.0, 960.0 / image.getWidth());
                    try (NativeImage small = new NativeImage(Math.max(1, (int)(image.getWidth()*scale)), Math.max(1, (int)(image.getHeight()*scale)), false)) {
                        image.resizeSubRectTo(0, 0, image.getWidth(), image.getHeight(), small);
                        java.nio.file.Path temp = file.toPath().resolveSibling(file.getName()+".tmp");
                        small.writeToFile(temp);
                        java.nio.file.Files.move(temp, file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception ignored) {
                    // An optional screenshot failure must not affect the game thread.
                } finally { writing = false; }
            });
        } catch (Exception ignored) {
            // A screenshot is optional; gameplay continues if the graphics backend cannot capture it.
        }
    }
}
