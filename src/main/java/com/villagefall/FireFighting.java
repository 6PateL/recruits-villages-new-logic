package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ordinary villagers put out fires: they run to a fire, pour a water source on it and, when nothing burns around
 * any more, pick that water up again. Lit campfires (their own) and fires on netherrack/soul soil are left alone.
 */
public class FireFighting {
    private record Puddle(ServerLevel level, BlockPos pos, long placed) {}

    private final List<Puddle> puddles = new ArrayList<>();
    private final Map<Integer, Long> lastPour = new HashMap<>();

    private static boolean isFire(BlockState s) {
        return s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE);
    }

    private static boolean eternal(ServerLevel level, BlockPos p) {
        BlockPos b = p.below();
        return level.getBlockState(b).isFireSource(level, b, Direction.UP);
    }

    @SubscribeEvent
    public void onVillager(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager v) || !FallConfig.FIREFIGHT.get() || v.level().isClientSide
                || (v.tickCount + v.getId()) % 20 != 0 || v.isSleeping() || v.getPersistentData().getBoolean("trainrecruits_gunner") || v.getPersistentData().getBoolean("trainrecruits_follow")
                || !(v.level() instanceof ServerLevel level)) {
            return;
        }
        int r = FallConfig.FIREFIGHT_RANGE.get();
        BlockPos me = v.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-r, -4, -r), me.offset(r, 4, r))) {
            if (isFire(level.getBlockState(p)) && !eternal(level, p)) {
                double d = p.distSqr(me);
                if (d < bd) {
                    bd = d;
                    best = p.immutable();
                }
            }
        }
        if (best == null) {
            return;
        }
        if (bd > 3.5D * 3.5D) {
            v.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(best, 0.7F, 2));
            v.getNavigation().moveTo(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D, 0.7D);
            return;
        }
        long now = level.getGameTime();
        Long last = lastPour.get(v.getId());
        if (last != null && now - last < 30L) {
            return;
        }
        lastPour.put(v.getId(), now);
        if (lastPour.size() > 256) {
            lastPour.values().removeIf(t -> now - t > 600L);
        }
        level.setBlock(best, Blocks.WATER.defaultBlockState(), 3);
        puddles.add(new Puddle(level, best, now));
        level.playSound(null, best, SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 1.0F, 1.0F);
        v.getLookControl().setLookAt(best.getX() + 0.5D, best.getY() + 0.5D, best.getZ() + 0.5D);
        v.swing(InteractionHand.MAIN_HAND);
    }

    /** Pick the water up once the fire is gone (or after a while anyway). */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || puddles.isEmpty() || event.getServer().getTickCount() % 20 != 0) {
            return;
        }
        puddles.removeIf(pd -> {
            ServerLevel level = pd.level();
            long age = level.getGameTime() - pd.placed();
            if (age < 100L || !level.isLoaded(pd.pos())) {
                return false;
            }
            boolean burning = false;
            for (BlockPos p : BlockPos.betweenClosed(pd.pos().offset(-4, -2, -4), pd.pos().offset(4, 2, 4))) {
                if (isFire(level.getBlockState(p))) {
                    burning = true;
                    break;
                }
            }
            if (burning && age < 1200L) {
                return false;
            }
            BlockState s = level.getBlockState(pd.pos());
            if (s.is(Blocks.WATER) && s.getFluidState().isSource()) {
                level.setBlock(pd.pos(), Blocks.AIR.defaultBlockState(), 3);
                level.playSound(null, pd.pos(), SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 1.0F, 1.0F);
            }
            return true;
        });
    }
}
