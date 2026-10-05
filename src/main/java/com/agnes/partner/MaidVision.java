package com.agnes.partner;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable in-process handoff between the integrated server and render thread. No client classes. */
public final class MaidVision {
    public record Target(UUID owner, UUID maid, String dimension, int entityId, UUID session, long updated) {}
    public record Frame(Target target, long captured, Vec3 eye, float yaw, float pitch, String png) {
        public long ageMillis() { return Math.max(0, System.currentTimeMillis() - captured); }
        JsonObject describe() {
            JsonObject data = new JsonObject();
            data.addProperty("source", "maid_first_person");
            data.addProperty("maid_uuid", target.maid().toString());
            data.addProperty("dimension", target.dimension());
            data.addProperty("age_ms", ageMillis());
            data.addProperty("eye_position", eye.toString());
            data.addProperty("yaw", yaw); data.addProperty("pitch", pitch);
            data.addProperty("fov_degrees", 70);
            data.addProperty("scope", "client-loaded terrain only; image is a past observation, game state is authoritative");
            return data;
        }
    }
    private static final ConcurrentHashMap<UUID, Target> TARGETS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Frame> FRAMES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, String> STATUS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, java.util.concurrent.CompletableFuture<Void>> REQUESTS = new ConcurrentHashMap<>();
    private MaidVision() {}

    static void track(ServerPlayer owner, Entity maid) {
        if (owner.server.isDedicatedServer() || maid == null || !maid.isAlive() || maid.level() != owner.level()
            || !PartnerConfig.isVisionEnabled()) { remove(owner.getUUID()); return; }
        String dimension = maid.level().dimension().location().toString();
        TARGETS.compute(owner.getUUID(), (id, old) -> new Target(id, maid.getUUID(), dimension, maid.getId(),
            old != null && old.maid().equals(maid.getUUID()) && old.dimension().equals(dimension)
                && old.entityId() == maid.getId() ? old.session() : UUID.randomUUID(), System.currentTimeMillis()));
    }
    public static Target target(UUID owner) {
        Target target = TARGETS.get(owner);
        return target != null && System.currentTimeMillis() - target.updated() <= 2500 ? target : null;
    }
    public static void accept(Frame frame) {
        Target current = target(frame.target().owner());
        if (current != null && current.session().equals(frame.target().session())) {
            FRAMES.put(current.owner(), frame); STATUS.put(current.owner(), "女仆独立视角已就绪");
            var request = REQUESTS.remove(current.owner());
            if (request != null) request.complete(null);
        }
    }
    public static boolean requested(UUID owner) { return REQUESTS.containsKey(owner); }
    static java.util.concurrent.CompletableFuture<Void> request(ServerPlayer owner, Entity maid) {
        track(owner, maid);
        if (target(owner.getUUID()) == null) return java.util.concurrent.CompletableFuture.completedFuture(null);
        var ready = new java.util.concurrent.CompletableFuture<Void>();
        var previous = REQUESTS.put(owner.getUUID(), ready);
        if (previous != null) previous.complete(null);
        // Never block the server/render thread if the window is paused, minimized, or using shaders.
        ready.completeOnTimeout(null, 1500, java.util.concurrent.TimeUnit.MILLISECONDS);
        ready.whenComplete((unused, error) -> REQUESTS.remove(owner.getUUID(), ready));
        return ready;
    }
    public static void unavailable(UUID owner, String reason) { FRAMES.remove(owner); STATUS.put(owner, reason); }
    static Frame read(ServerPlayer owner, Entity maid) {
        if (!PartnerConfig.isVisionEnabled() || owner.server.isDedicatedServer()) return null;
        Target target = target(owner.getUUID());
        Frame frame = FRAMES.get(owner.getUUID());
        if (frame == null || target == null || !frame.target().session().equals(target.session())
            || !frame.target().maid().equals(maid.getUUID()) || !frame.target().dimension().equals(maid.level().dimension().location().toString())
            || maid.level() != owner.level() || !maid.isAlive()) return null;
        long age = System.currentTimeMillis() - frame.captured();
        // Large movements/teleports invalidate a recent image too. Rotation is supplied as capture metadata.
        return age >= 0 && age <= 20_000 && maid.getEyePosition().distanceToSqr(frame.eye()) <= 16 ? frame : null;
    }
    static String status(ServerPlayer owner) {
        if (!PartnerConfig.isVisionEnabled()) return "视觉已关闭";
        if (owner.server.isDedicatedServer()) return "独立服务器暂用游戏状态；女仆摄像头需要本机单人存档";
        return STATUS.getOrDefault(owner.getUUID(), "等待女仆独立视角；当前使用游戏状态");
    }
    static void remove(UUID owner) { TARGETS.remove(owner); FRAMES.remove(owner); STATUS.remove(owner); REQUESTS.remove(owner); }
    static void clear() { TARGETS.clear(); FRAMES.clear(); STATUS.clear(); REQUESTS.clear(); }
}
