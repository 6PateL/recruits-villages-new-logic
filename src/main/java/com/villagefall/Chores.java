package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.UUID;

/**
 * Ordinary villagers (not soldiers, not mobilised ones) repair the iron golems of their village and put torches
 * where the village (its box) is dark, so the settlement keeps to the light.
 */
public class Chores {
    private boolean broken;

    private static boolean mobilised(Villager v) {
        var d = v.getPersistentData();
        return d.getBoolean("trainrecruits_gunner") || d.getBoolean("trainrecruits_follow") || d.getBoolean("trainrecruits_supply");
    }

    @SubscribeEvent
    public void onVillager(LivingEvent.LivingTickEvent event) {
        if (!(event.getEntity() instanceof Villager v) || broken || !FallConfig.CHORES.get() || v.level().isClientSide
                || (v.tickCount + v.getId()) % 60 != 0 || v.isSleeping() || mobilised(v)
                || !(v.level() instanceof ServerLevel level) || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        try {
            RV.init();
            UUID fid = RV.memberFaction(v);
            if (fid == null) {
                return;
            }
            Object f = RV.faction(RV.data(level.getServer()), fid);
            AABB box = f == null ? null : RV.box(f);
            if (box == null || !box.inflate(4.0D).contains(v.position())) {
                return;
            }
            if (repairGolem(level, v, box)) {
                return;
            }
            torch(level, v, box, fid);
        } catch (Throwable t) {
            broken = true;
            com.mojang.logging.LogUtils.getLogger().warn("[VillageFall] chores disabled: {}", t.toString());
        }
    }

    private boolean repairGolem(ServerLevel level, Villager v, AABB box) {
        List<IronGolem> golems = level.getEntitiesOfClass(IronGolem.class, v.getBoundingBox().inflate(16.0D, 6.0D, 16.0D),
                g -> g.isAlive() && g.getHealth() < g.getMaxHealth() - 1.0F && box.inflate(4.0D).contains(g.position()));
        IronGolem best = null;
        for (IronGolem g : golems) {
            if (best == null || g.distanceToSqr(v) < best.distanceToSqr(v)) {
                best = g;
            }
        }
        if (best == null) {
            return false;
        }
        if (v.distanceToSqr(best) > 3.5D * 3.5D) {
            v.getNavigation().moveTo(best, 0.7D);
            return true;
        }
        best.heal(best.getMaxHealth() * 0.2F);
        v.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, best.blockPosition(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, best.getX(), best.getY() + 1.5D, best.getZ(), 5, 0.4D, 0.5D, 0.4D, 0.0D);
        return true;
    }

    /** Dark spots of every village (recomputed every 10 s), so villagers light up the WHOLE village, not only their own path. */
    private record Spots(long time, List<BlockPos> list) {}

    private final java.util.Map<UUID, Spots> spots = new java.util.HashMap<>();

    private static boolean darkSpot(ServerLevel level, BlockPos pos) {
        return level.isEmptyBlock(pos) && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                && level.getBrightness(LightLayer.BLOCK, pos) < 6 && level.getMaxLocalRawBrightness(pos) <= 7;
    }

    private List<BlockPos> darkSpots(ServerLevel level, UUID fid, AABB box) {
        long now = level.getGameTime();
        Spots cached = spots.get(fid);
        if (cached != null && now - cached.time() < 200L) {
            return cached.list();
        }
        List<BlockPos> list = new java.util.ArrayList<>();
        int step = 5;
        for (int x = (int) box.minX; x <= (int) box.maxX && list.size() < 200; x += step) {
            for (int z = (int) box.minZ; z <= (int) box.maxZ && list.size() < 200; z += step) {
                if (!level.hasChunkAt(new BlockPos(x, 0, z))) {
                    continue;
                }
                BlockPos pos = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
                if (pos.getY() >= box.minY - 4 && pos.getY() <= box.maxY + 4 && darkSpot(level, pos)) {
                    list.add(pos);
                }
            }
        }
        spots.put(fid, new Spots(now, list));
        return list;
    }

    private void torch(ServerLevel level, Villager v, AABB box, UUID fid) {
        List<BlockPos> list = darkSpots(level, fid, box);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : list) {
            double d = p.distSqr(v.blockPosition());
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        if (best == null) {
            return;
        }
        if (!darkSpot(level, best)) {
            list.remove(best); // somebody already lit it
            return;
        }
        if (bd > 2.5D * 2.5D) {
            v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET,
                    new net.minecraft.world.entity.ai.memory.WalkTarget(best, 0.6F, 1));
            v.getNavigation().moveTo(best.getX() + 0.5D, best.getY(), best.getZ() + 0.5D, 0.6D);
            return;
        }
        level.setBlock(best, Blocks.TORCH.defaultBlockState(), 3);
        list.remove(best);
        v.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, best, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
    }
}
