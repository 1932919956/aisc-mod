package com.agnes.partner;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import java.util.EnumSet;

public class FollowOwnerGoal extends Goal {
    private final AgnesCompanion companion; private final double speed; private final float stop; private final float start;
    public FollowOwnerGoal(AgnesCompanion companion, double speed, float stop, float start) { this.companion = companion; this.speed = speed; this.stop = stop; this.start = start; setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }
    private ServerPlayer owner() { return companion.level().getServer() == null ? null : companion.level().getServer().getPlayerList().getPlayerByName(companion.getOwnerName()); }
    @Override public boolean canUse() { ServerPlayer p = owner(); return companion.isFollowing() && p != null && companion.distanceToSqr(p) > start * start; }
    @Override public boolean canContinueToUse() { ServerPlayer p = owner(); return companion.isFollowing() && p != null && companion.distanceToSqr(p) > stop * stop && !companion.isAggressive(); }
    @Override public void tick() { ServerPlayer p = owner(); if (p != null) { companion.getLookControl().setLookAt(p, 10, companion.getMaxHeadXRot()); companion.getNavigation().moveTo(p, speed); } }
}
