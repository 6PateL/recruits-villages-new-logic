package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.state.BlockState;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A villager walks to a destroyed block of his village and puts it back (see {@link Repairs}). */
public class RepairGoal extends Goal {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final Map<UUID, Integer> WORKERS = new HashMap<>();
    private static final Set<BlockPos> CLAIMED = new HashSet<>();

    private final Villager v;
    private final BlockPos spot;
    private final BlockState state;
    private final UUID fid;
    private int ticks;
    private boolean finished;

    private RepairGoal(Villager v, BlockPos spot, BlockState state, UUID fid) {
        this.v = v;
        this.spot = spot;
        this.state = state;
        this.fid = fid;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    static boolean busy(Villager v) {
        v.goalSelector.removeAllGoals(g -> g instanceof RepairGoal r && r.finished);
        return v.goalSelector.getAvailableGoals().stream().anyMatch(g -> g.getGoal() instanceof RepairGoal);
    }

    static int workers(UUID fid) {
        return Math.max(0, WORKERS.getOrDefault(fid, 0));
    }

    static boolean claimed(BlockPos p) {
        return CLAIMED.contains(p);
    }

    static void start(Villager v, BlockPos spot, BlockState state, UUID fid) {
        v.goalSelector.addGoal(0, new RepairGoal(v, spot, state, fid));
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
        if (!v.isAlive() || v.isSleeping()) {
            finish(level, "villager unavailable");
            return;
        }
        double dx = v.getX() - (spot.getX() + 0.5D);
        double dz = v.getZ() - (spot.getZ() + 0.5D);
        double flat = dx * dx + dz * dz;
        double dy = Math.abs(v.getY() - spot.getY());
        // close enough to reach; roofs and upper walls are reached from the ground below
        boolean reach = flat <= 3.5D * 3.5D && dy <= 7.0D;
        if (!reach && ticks > 400) {
            Repairs.SKIP.put(spot, level.getGameTime() + 2400L);
            finish(level, "could not reach " + spot.toShortString() + " (dist " + (int) Math.sqrt(flat) + ", dy " + (int) dy + "), retry in 2 min");
            return;
        }
        if (!reach) {
            if (ticks % 4 == 1) {
                v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(spot, 0.75F, 2));
            }
            if (ticks % 10 == 1 || v.getNavigation().isDone()) {
                v.getNavigation().moveTo(spot.getX() + 0.5D, spot.getY(), spot.getZ() + 0.5D, 0.75D);
            }
            return;
        }
        BlockState cur = level.getBlockState(spot);
        if (cur.isAir() || cur.canBeReplaced()) {
            if (!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new net.minecraft.world.phys.AABB(spot)).isEmpty()) {
                if (ticks < 500) {
                    return; // somebody stands in the hole: wait a little
                }
                finish(level, "spot " + spot.toShortString() + " is occupied");
                return;
            }
            level.setBlock(spot, state, 3);
            v.swing(InteractionHand.MAIN_HAND);
            level.playSound(null, spot, state.getSoundType().getPlaceSound(), SoundSource.BLOCKS, 0.9F, 1.0F);
            LOG.info("[VillageFall] repairs: {} rebuilt {} at {}", v.getName().getString(), state.getBlock().getName().getString(), spot.toShortString());
        }
        Repairs.placed(level, fid, spot);
        finish(level, null);
    }

    private void finish(ServerLevel level, String problem) {
        if (finished) {
            return;
        }
        finished = true;
        CLAIMED.remove(spot);
        WORKERS.merge(fid, -1, Integer::sum);
        v.getNavigation().stop();
        v.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        if (problem != null) {
            LOG.info("[VillageFall] repairs: {} gave up: {}", v.getName().getString(), problem);
        }
    }
}
