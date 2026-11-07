package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A villager goes to an unlit spot of his village and puts a torch there. While on duty he ignores the daily
 * schedule (no bed, no wandering back and forth); afterwards his normal brain is restored.
 */
public class TorchGoal extends Goal {
    private static final Map<UUID, Integer> WORKERS = new HashMap<>();
    private static final Set<BlockPos> CLAIMED = new HashSet<>();

    private final Villager v;
    private final BlockPos spot;
    private final UUID fid;
    private int ticks;
    private boolean finished;

    private TorchGoal(Villager v, BlockPos spot, UUID fid) {
        this.v = v;
        this.spot = spot;
        this.fid = fid;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    static boolean busy(Villager v) {
        return v.goalSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof TorchGoal);
    }

    static int workers(UUID fid) {
        return WORKERS.getOrDefault(fid, 0);
    }

    static boolean claimed(BlockPos p) {
        return CLAIMED.contains(p);
    }

    static void start(ServerLevel level, Villager v, BlockPos spot, UUID fid) {
        if (v.isSleeping()) {
            v.stopSleeping();
        }
        v.getBrain().removeAllBehaviors();
        v.goalSelector.addGoal(0, new TorchGoal(v, spot, fid));
        WORKERS.merge(fid, 1, Integer::sum);
        CLAIMED.add(spot);
    }

    @Override
    public boolean canUse() {
        return !finished;
    }

    @Override
    public boolean canContinueToUse() {
        return !finished;
    }

    @Override
    public void tick() {
        if (!(v.level() instanceof ServerLevel level)) {
            return;
        }
        ticks++;
        if (!v.isAlive() || ticks > 400) {
            finish(level);
            return;
        }
        if (v.isSleeping()) {
            v.stopSleeping();
        }
        double d = v.position().distanceToSqr(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D);
        if (d > 2.5D * 2.5D) {
            if (ticks % 10 == 1 || v.getNavigation().isDone()) {
                v.getNavigation().moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 0.75D);
            }
            return;
        }
        if (level.isEmptyBlock(spot) && level.getBlockState(spot.below()).isFaceSturdy(level, spot.below(), net.minecraft.core.Direction.UP)) {
            level.setBlock(spot, Blocks.TORCH.defaultBlockState(), 3);
            v.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, spot, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
        }
        finish(level);
    }

    private void finish(ServerLevel level) {
        if (finished) {
            return;
        }
        finished = true;
        CLAIMED.remove(spot);
        WORKERS.merge(fid, -1, Integer::sum);
        v.getNavigation().stop();
        v.goalSelector.removeAllGoals(g -> g == this);
        if (v.isAlive()) {
            v.refreshBrain(level);
        }
    }
}
