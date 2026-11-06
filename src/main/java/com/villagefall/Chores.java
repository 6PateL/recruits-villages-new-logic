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
            torch(level, v, box);
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

    private void torch(ServerLevel level, Villager v, AABB box) {
        BlockPos base = v.blockPosition();
        if (level.getBrightness(LightLayer.BLOCK, base) >= 6 || level.getMaxLocalRawBrightness(base) > 7) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            BlockPos pos = base.offset(level.random.nextInt(9) - 4, level.random.nextInt(3) - 1, level.random.nextInt(9) - 4);
            if (box.contains(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D) && level.isEmptyBlock(pos)
                    && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP)
                    && level.getBrightness(LightLayer.BLOCK, pos) < 6) {
                level.setBlock(pos, Blocks.TORCH.defaultBlockState(), 3);
                v.swing(InteractionHand.MAIN_HAND);
                level.playSound(null, pos, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
                return;
            }
        }
    }
}
