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
                || (v.tickCount + v.getId()) % 60 != 0 || mobilised(v) || TorchGoal.busy(v) || RepairGoal.busy(v)
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
            if (f != null) {
                centers.put(fid, RV.center(f));
            }
            if (box == null || !RV.onLand(level, f, v.blockPosition())) {
                return;
            }
            if (v.isSleeping()) {
                // torches first, then sleep: wake up only if the village has unlit spots and a free hand is needed
                if (!hasWork(level, fid, box)) {
                    return;
                }
            } else if (repairGolem(level, v, box, fid)) {
                return;
            }
            // priorities: golems, torches (dark), campfires (winter, see WinterCare), and only then the buildings
            if (torch(level, v, box, fid) || v.isSleeping()) {
                return;
            }
            if (v.goalSelector.getRunningGoals().anyMatch(g -> g.getGoal() instanceof WinterCare.CampfireGoal)) {
                return;
            }
            Repairs.work(level, v, box, fid, RV.name(f));
        } catch (Throwable t) {
            broken = true;
            com.mojang.logging.LogUtils.getLogger().warn("[VillageFall] chores disabled: {}", t.toString());
        }
    }

    /** Villagers heal only their own village's golems (never another faction's, nor one hunting a player). */
    private static boolean ours(IronGolem g, UUID fid) {
        String tag = g.getPersistentData().getString(Arsenal.GOLEM_TAG);
        return tag.isEmpty() || tag.equals(fid.toString());
    }

    private boolean repairGolem(ServerLevel level, Villager v, AABB box, UUID fid) {
        List<IronGolem> golems = level.getEntitiesOfClass(IronGolem.class, v.getBoundingBox().inflate(16.0D, 6.0D, 16.0D),
                g -> g.isAlive() && g.getHealth() < g.getMaxHealth() - 1.0F && box.inflate(24.0D).contains(g.position())
                        && !(g.getTarget() instanceof net.minecraft.world.entity.player.Player) && ours(g, fid));
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
        // scanned in rings from the village centre outwards, so the limit keeps the spots nearest to the centre
        // (a plain corner-to-corner scan filled the limit with one edge strip of the box: torches in a row far away)
        List<BlockPos> list = new java.util.ArrayList<>();
        BlockPos c = centers.getOrDefault(fid, BlockPos.containing(box.getCenter()));
        int step = 5;
        int rings = (int) Math.ceil(Math.max(box.getXsize(), box.getZsize()) / step) + 1;
        for (int r = 0; r <= rings && list.size() < 200; r++) {
            for (int i = -r; i <= r && list.size() < 200; i++) {
                for (int j = -r; j <= r; j++) {
                    if (Math.max(Math.abs(i), Math.abs(j)) != r) {
                        continue; // only the ring itself
                    }
                    int x = c.getX() + i * step;
                    int z = c.getZ() + j * step;
                    if (x < box.minX || x > box.maxX || z < box.minZ || z > box.maxZ || !level.hasChunkAt(new BlockPos(x, 0, z))) {
                        continue;
                    }
                    BlockPos pos = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
                    if (pos.getY() >= box.minY - 4 && pos.getY() <= box.maxY + 4 && darkSpot(level, pos)) {
                        list.add(pos);
                    }
                }
            }
        }
        spots.put(fid, new Spots(now, list));
        com.mojang.logging.LogUtils.getLogger().info("[VillageFall] torches: village {} centre {} has {} dark spots (box {}x{})",
                fid, c.toShortString(), list.size(), (int) box.getXsize(), (int) box.getZsize());
        return list;
    }

    /** Real centres of the villages (RV), the lighting spreads from there. */
    private final java.util.Map<UUID, BlockPos> centers = new java.util.HashMap<>();

    private boolean hasWork(ServerLevel level, UUID fid, AABB box) {
        return TorchGoal.workers(fid) < 2 && !darkSpots(level, fid, box).isEmpty();
    }

    private boolean torch(ServerLevel level, Villager v, AABB box, UUID fid) {
        if (TorchGoal.workers(fid) >= 2) {
            return false; // two lamplighters per village at a time, the rest go about their day
        }
        List<BlockPos> list = darkSpots(level, fid, box);
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        // first the dark spot nearest to the villager himself, but only close by (his own surroundings, ~a chunk);
        // if everything around him is lit, the unlit spot nearest to the village centre
        BlockPos c = centers.getOrDefault(fid, BlockPos.containing(box.getCenter()));
        BlockPos far = null;
        double fd = Double.MAX_VALUE;
        for (BlockPos p : list) {
            if (TorchGoal.claimed(p)) {
                continue;
            }
            double d = p.distSqr(v.blockPosition());
            if (d <= 16.0D * 16.0D && d < bd) {
                bd = d;
                best = p;
            }
            double dx = p.getX() - c.getX();
            double dz = p.getZ() - c.getZ();
            if (dx * dx + dz * dz < fd) {
                fd = dx * dx + dz * dz;
                far = p;
            }
        }
        if (best == null) {
            best = far;
        }
        if (best == null) {
            return false;
        }
        if (!darkSpot(level, best)) {
            list.remove(best); // somebody already lit it
            return false;
        }
        list.remove(best);
        TorchGoal.start(level, v, best, fid);
        return true;
    }
}
