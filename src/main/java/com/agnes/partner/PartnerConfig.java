package com.agnes.partner;

import net.minecraftforge.common.ForgeConfigSpec;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;

public final class PartnerConfig {
    public static final boolean PORTABLE = PartnerConfig.class.getResource("/agnespartner-portable.flag") != null;
    public static String configFileName() { return PORTABLE ? "agnespartner-portable.toml" : "agnespartner-common.toml"; }
    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> apiKey;
    public static final ForgeConfigSpec.ConfigValue<String> model;
    public static final ForgeConfigSpec.ConfigValue<String> zhipuApiKey;
    public static final ForgeConfigSpec.ConfigValue<String> zhipuModel;
    public static final ForgeConfigSpec.ConfigValue<Boolean> zhipuFallback;
    public static final ForgeConfigSpec.ConfigValue<String> customApiUrl;
    public static final ForgeConfigSpec.ConfigValue<String> customApiKey;
    public static final ForgeConfigSpec.ConfigValue<String> customModel;
    public static final ForgeConfigSpec.ConfigValue<Boolean> customFallback;
    public static final ForgeConfigSpec.ConfigValue<Boolean> visionEnabled;
    public static final ForgeConfigSpec.ConfigValue<Boolean> autonomousMode;
    public static final ForgeConfigSpec.ConfigValue<Boolean> yieldToPromaid;
    public static final ForgeConfigSpec.ConfigValue<Boolean> allowWorldEdits;
    public static final ForgeConfigSpec.ConfigValue<Integer> planningSeconds;
    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("agnes_ai");
        apiKey = builder.comment("Agnes API Key. Keep this file private.").define("apiKey", "");
        model = builder.define("model", "agnes-2.5-flash");
        zhipuApiKey = builder.comment("Optional Zhipu GLM API Key, used only when Agnes fails. Keep this private.")
            .define("zhipuApiKey", "");
        zhipuModel = builder.define("zhipuModel", "GLM-4.6V-Flash");
        zhipuFallback = builder.comment("Retry failed Agnes requests through Zhipu GLM when a Zhipu key is configured.")
            .define("zhipuFallback", true);
        customApiUrl = builder.comment(
            "Optional OpenAI-compatible Chat Completions URL, used after Agnes and Zhipu fail.",
            "Enter the complete HTTPS or HTTP endpoint, for example https://api.openai.com/v1/chat/completions.")
            .define("customApiUrl", "https://api.openai.com/v1/chat/completions");
        customApiKey = builder.comment("Optional API key for the custom OpenAI-compatible endpoint. Keep this private.")
            .define("customApiKey", "");
        customModel = builder.define("customModel", "");
        customFallback = builder.comment("Retry failed requests through the custom OpenAI-compatible endpoint after Agnes and Zhipu.")
            .define("customFallback", true);
        visionEnabled = builder.comment("Enable optional screenshot vision for the companion.").define("visionEnabled", true);
        autonomousMode = builder.comment("Let the companion choose and continue its own activities between player commands.").define("autonomousMode", true);
        yieldToPromaid = builder.comment(
            "When Promaid is installed, let its task and movement controller own TLM maids only when explicitly enabled.",
            "Keep this false for Agnes autonomous survival: Promaid's takeover cancels Agnes fieldwork and shaft actions.",
            "Enable it only when you intentionally want Promaid to control the maid's low-level actions.")
            .define("yieldToPromaid", false);
        allowWorldEdits = builder.comment(
            "Allow her to gather, place and build even when the world rule mobGriefing is false.",
            "mobGriefing is a rule about MOBS damaging the world, and many modpacks ship with it disabled.",
            "A player is not limited by that rule, so a companion meant to act like one should not be either.",
            "If this stays false on such a modpack she will refuse every world change with",
            "'the world rules forbid a maid from changing blocks', which looks like she does nothing at all.",
            "Forge break/place protection events and land claims are honoured either way.")
            .define("allowWorldEditsWhenMobGriefingOff", false);
        planningSeconds = builder.comment(
            "Seconds between two autonomous planning requests, 10 to 300 (default 30).",
            "Every planning round is one request to Agnes, so this is the main cost dial as well as the pacing",
            "dial. She only asks while she is genuinely idle with nothing in progress, so a busy stretch costs",
            "nothing extra.")
            .defineInRange("planningIntervalSeconds", 30, 10, 300);
        builder.pop();
        SPEC = builder.build();
    }
    private PartnerConfig() {}

    public static String getApiKey() {
        if (PORTABLE) return apiKey.get();
        if (!apiKey.get().isBlank()) return apiKey.get();
        try {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null) return "";
            Path path = Paths.get(localAppData, "MinecraftAIPartner", "config.json");
            if (!Files.exists(path)) return "";
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("apiKey").getAsString();
        } catch (Exception ignored) { return ""; }
    }

    public static String getModel() {
        if (PORTABLE) return model.get();
        try {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData == null) return model.get();
            Path path = Paths.get(localAppData, "MinecraftAIPartner", "config.json");
            if (!Files.exists(path)) return model.get();
            return JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("model").getAsString();
        } catch (Exception ignored) { return model.get(); }
    }

    public static String getZhipuApiKey() { return zhipuApiKey.get(); }
    public static String getZhipuModel() {
        String value = zhipuModel.get();
        // Migrate the old built-in default while leaving an explicitly chosen model untouched.
        return value == null || value.isBlank() || value.equalsIgnoreCase("glm-4-flash")
            ? "GLM-4.6V-Flash" : value;
    }
    public static boolean isZhipuFallback() { return zhipuFallback.get() && !getZhipuApiKey().isBlank(); }
    public static String getCustomApiUrl() { return customApiUrl.get() == null ? "" : customApiUrl.get().trim(); }
    public static String getCustomApiKey() { return customApiKey.get() == null ? "" : customApiKey.get().trim(); }
    public static String getCustomModel() { return customModel.get() == null ? "" : customModel.get().trim(); }
    public static boolean isCustomFallback() {
        if (!customFallback.get() || getCustomModel().isBlank() || getCustomApiUrl().isBlank()) return false;
        try {
            java.net.URI endpoint = java.net.URI.create(getCustomApiUrl());
            String scheme = endpoint.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && endpoint.getHost() != null;
        } catch (RuntimeException invalid) { return false; }
    }

    public static boolean supportsVision(String modelName) {
        if (modelName == null) return false;
        String normalized = modelName.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("vision") || normalized.contains("-vl") || normalized.contains("glm-4v")
            || normalized.contains("glm-4.6v")
            || normalized.contains("qwen2.5-vl") || normalized.contains("qwen-vl") || normalized.contains("qwen2-vl");
    }

    public static boolean isVisionEnabled() { return visionEnabled.get(); }

    public static boolean isAutonomousMode() { return autonomousMode.get(); }

    /** Ticks between two autonomous planning requests. She still only asks while genuinely idle. */
    public static int planningInterval() {
        try { return Math.max(200, planningSeconds.get() * 20); }
        catch (RuntimeException notLoadedYet) { return 600; }
    }

    /** A blocked round waits the same amount as the configured planning interval. */
    public static int stuckInterval() {
        return planningInterval();
    }

    /**
     * Whether she may change the world while the mobGriefing world rule is false.
     * The rule targets mobs damaging terrain; a player is not bound by it, so a companion acting like a
     * player usually should not be either. Modpacks often disable it, so this is a real switch, and the
     * protection events the mod already fires are respected regardless of the answer.
     */
    public static boolean mayEditWorld(net.minecraft.world.level.Level level, net.minecraft.world.entity.Entity actor) {
        if (allowWorldEdits.get()) return true;
        return net.minecraftforge.event.ForgeEventFactory.getMobGriefingEvent(level, actor);
    }

    public static String getScreenshotPath() {
        if (PORTABLE) return net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("agnespartner-cache/minecraft-view.png").toString();
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData == null) return "";
        return Paths.get(localAppData, "MinecraftAIPartner", "minecraft-view.png").toString();
    }

    public static String readScreenshotBase64() {
        try {
            Path path = Paths.get(getScreenshotPath());
            if (!Files.exists(path) || Files.getLastModifiedTime(path).toMillis() < System.currentTimeMillis() - 20_000) return "";
            return Base64.getEncoder().encodeToString(Files.readAllBytes(path));
        } catch (Exception ignored) { return ""; }
    }
}
