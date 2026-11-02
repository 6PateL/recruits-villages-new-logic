package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Ordinary villagers (recruits are not touched) look after themselves in winter: when it snows they light a
 * campfire near their gathering place. A campfire per area, with a pause, so villages do not turn into bonfires.
 */
public class WinterCare {
    private static final Map<Long, Long> AREA_COOLDOWN = new HashMap<>();

    @SubscribeEvent
    public void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Villager v) || !FallConfig.CAMPFIRES.get()) {
            return;
        }
        for (var g : v.goalSelector.getAvailableGoals()) {
            if (g.getGoal() instanceof CampfireGoal) {
                return;
            }
        }
        v.goalSelector.addGoal(9, new CampfireGoal(v));
    }

    static boolean cold(ServerLevel level, BlockPos pos) {
        boolean snowing = level.isRaining() && level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.SNOW;
        if (snowing) {
            return true;
        }
        return !FallConfig.CAMPFIRE_REQUIRE_SNOW.get() && sereneWinter(level)
                && level.getBiome(pos).value().coldEnoughToSnow(pos);
    }

    private static boolean sereneWinter(ServerLevel level) {
        try {
            Class<?> h = Class.forName("sereneseasons.api.season.SeasonHelper");
            Object state = h.getMethod("getSeasonState", net.minecraft.world.level.Level.class).invoke(null, level);
            Object season = state.getClass().getMethod("getSeason").invoke(state);
            return "WINTER".equals(String.valueOf(season));
        } catch (Throwable t) {
            return false;
        }
    }

    static final class CampfireGoal extends Goal {
        private final Villager villager;
        private long lastTry;

        CampfireGoal(Villager villager) {
            this.villager = villager;
            setFlags(EnumSet.noneOf(Flag.class));
        }

        @Override
        public boolean canUse() {
            if (!(villager.level() instanceof ServerLevel level) || villager.tickCount % 100 != villager.getId() % 100
                    || villager.isSleeping() || level.getGameTime() - lastTry < 400L) {
                return false;
            }
            return cold(level, villager.blockPosition());
        }

        @Override
        public boolean canContinueToUse() {
            return false;
        }

        @Override
        public void start() {
            ServerLevel level = (ServerLevel) villager.level();
            lastTry = level.getGameTime();
            BlockPos anchor = anchor();
            if (anchor == null || villager.blockPosition().distSqr(anchor) > 14 * 14) {
                return; // away from the gathering place: nothing to do here
            }
            long area = ((long) (anchor.getX() >> 5) << 32) ^ (anchor.getZ() >> 5);
            Long last = AREA_COOLDOWN.get(area);
            long now = level.getGameTime();
            if (last != null && now - last < FallConfig.CAMPFIRE_COOLDOWN_SECONDS.get() * 20L) {
                return;
            }
            if (burning(level, anchor) >= FallConfig.CAMPFIRE_MAX_NEAR.get()) {
                AREA_COOLDOWN.put(area, now);
                return;
            }
            BlockPos site = findSite(level, anchor);
            if (site == null) {
                return;
            }
            BlockState fire = Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true)
                    .setValue(CampfireBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(level.random));
            level.setBlock(site, fire, 3);
            level.playSound(null, site, SoundEvents.FLINTANDSTEEL_USE, SoundSource.NEUTRAL, 1.0F, 1.0F);
            villager.getLookControl().setLookAt(site.getX() + 0.5D, site.getY() + 0.3D, site.getZ() + 0.5D);
            villager.swing(InteractionHand.MAIN_HAND);
            AREA_COOLDOWN.put(area, now);
            if (AREA_COOLDOWN.size() > 512) {
                AREA_COOLDOWN.values().removeIf(t -> now - t > 24000L);
            }
        }

        /** Gathering place of the village (bell), otherwise the villager's workplace or bed. */
        private BlockPos anchor() {
            var brain = villager.getBrain();
            for (MemoryModuleType<GlobalPos> m : java.util.List.of(MemoryModuleType.MEETING_POINT, MemoryModuleType.JOB_SITE, MemoryModuleType.HOME)) {
                Optional<GlobalPos> p = brain.getMemory(m);
                if (p.isPresent() && p.get().dimension() == villager.level().dimension()) {
                    return p.get().pos();
                }
            }
            return null;
        }

        private int burning(ServerLevel level, BlockPos c) {
            int n = 0;
            for (BlockPos p : BlockPos.betweenClosed(c.offset(-10, -3, -10), c.offset(10, 3, 10))) {
                BlockState s = level.getBlockState(p);
                if (s.is(Blocks.CAMPFIRE) && s.getValue(CampfireBlock.LIT) || s.is(Blocks.SOUL_CAMPFIRE) && s.getValue(CampfireBlock.LIT)) {
                    n++;
                }
            }
            return n;
        }

        private BlockPos findSite(ServerLevel level, BlockPos anchor) {
            for (int i = 0; i < 24; i++) {
                BlockPos p = villager.blockPosition().offset(level.random.nextInt(9) - 4, level.random.nextInt(3) - 1, level.random.nextInt(9) - 4);
                if (p.distSqr(anchor) > 12 * 12 || !level.getBlockState(p).canBeReplaced()
                        || !level.getFluidState(p).isEmpty() || !level.canSeeSky(p)) {
                    continue;
                }
                BlockPos below = p.below();
                if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                        || level.getBlockState(below).isFlammable(level, below, Direction.UP)) {
                    continue;
                }
                boolean flammableNear = false;
                for (Direction d : Direction.values()) {
                    BlockPos n = p.relative(d);
                    if (level.getBlockState(n).isFlammable(level, n, d.getOpposite())) {
                        flammableNear = true;
                        break;
                    }
                }
                if (!flammableNear) {
                    return p;
                }
            }
            return null;
        }
    }
}
