package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.living.LivingDestroyBlockEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Damage done to a village (explosions, mobs breaking blocks, strangers breaking blocks) is remembered block by
 * block, and ordinary villagers of that village put the blocks back. It is their LOWEST priority chore: golems,
 * torches and winter campfires come first (see {@link Chores}).
 */
public class Repairs {
    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final int MAX_PER_VILLAGE = 3000;
    private boolean broken;

    // ------------------------------------------------------------------ storage

    public static class Store extends SavedData {
        final Map<UUID, LinkedHashMap<BlockPos, BlockState>> data = new HashMap<>();

        static Store get(ServerLevel level) {
            return level.getServer().overworld().getDataStorage().computeIfAbsent(Store::load, Store::new, "villagefall_repairs");
        }

        static Store load(CompoundTag tag) {
            Store s = new Store();
            ListTag villages = tag.getList("v", Tag.TAG_COMPOUND);
            for (int i = 0; i < villages.size(); i++) {
                CompoundTag vc = villages.getCompound(i);
                LinkedHashMap<BlockPos, BlockState> map = new LinkedHashMap<>();
                ListTag blocks = vc.getList("b", Tag.TAG_COMPOUND);
                for (int j = 0; j < blocks.size(); j++) {
                    CompoundTag bc = blocks.getCompound(j);
                    BlockState st = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), bc.getCompound("s"));
                    if (!st.isAir()) {
                        map.put(BlockPos.of(bc.getLong("p")), st);
                    }
                }
                s.data.put(vc.getUUID("id"), map);
            }
            return s;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            ListTag villages = new ListTag();
            data.forEach((id, map) -> {
                if (map.isEmpty()) {
                    return;
                }
                CompoundTag vc = new CompoundTag();
                vc.putUUID("id", id);
                ListTag blocks = new ListTag();
                map.forEach((p, st) -> {
                    CompoundTag bc = new CompoundTag();
                    bc.putLong("p", p.asLong());
                    bc.put("s", NbtUtils.writeBlockState(st));
                    blocks.add(bc);
                });
                vc.put("b", blocks);
                villages.add(vc);
            });
            tag.put("v", villages);
            return tag;
        }
    }

    // ------------------------------------------------------------------ recording the damage

    private record Area(UUID id, String name, AABB box, boolean playerOwned, UUID claim) {}

    private List<Area> areas = new ArrayList<>();
    private long areasTime = -100000L;

    private List<Area> areas(ServerLevel level) throws Exception {
        long now = level.getGameTime();
        if (now - areasTime > 200L || now < areasTime) {
            RV.init();
            List<Area> list = new ArrayList<>();
            for (Object f : RV.factions(RV.data(level.getServer()))) {
                AABB box = RV.box(f);
                if (box != null) {
                    list.add(new Area(RV.id(f), RV.name(f), box.inflate(6.0D), RV.player(f), RV.claimAt(level, RV.center(f))));
                }
            }
            areas = list;
            areasTime = now;
        }
        return areas;
    }

    private Area areaAt(ServerLevel level, BlockPos p) throws Exception {
        for (Area a : areas(level)) {
            if (a.box().contains(p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D)) {
                return a;
            }
        }
        // outside every box: the village's claimed chunks are its land too
        UUID claim = RV.claimAt(level, p);
        if (claim != null) {
            for (Area a : areas(level)) {
                if (claim.equals(a.claim())) {
                    return a;
                }
            }
        }
        return null;
    }

    private static boolean worth(ServerLevel level, BlockPos p, BlockState st) {
        return !st.isAir() && !st.canBeReplaced() && !st.hasBlockEntity() && !(st.getBlock() instanceof LiquidBlock)
                && !st.is(Blocks.TNT) && !st.is(Blocks.FIRE) && !st.is(Blocks.TORCH) && !st.is(Blocks.WALL_TORCH)
                && !st.is(Blocks.CAMPFIRE) && st.getDestroySpeed(level, p) >= 0.0F;
    }

    /** @return true if the block was remembered */
    private boolean record(ServerLevel level, Area a, BlockPos p, BlockState st) {
        if (a == null || !worth(level, p, st)) {
            return false;
        }
        Store store = Store.get(level);
        LinkedHashMap<BlockPos, BlockState> map = store.data.computeIfAbsent(a.id(), k -> new LinkedHashMap<>());
        if (map.size() >= MAX_PER_VILLAGE || map.containsKey(p)) {
            return false;
        }
        map.put(p.immutable(), st);
        store.setDirty();
        return true;
    }

    private boolean off(net.minecraft.world.level.LevelAccessor l) {
        return broken || !FallConfig.REPAIRS.get() || !(l instanceof ServerLevel) || !ModList.get().isLoaded("recruitsvillages");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onExplosion(ExplosionEvent.Detonate event) {
        if (off(event.getLevel()) || event.getAffectedBlocks().isEmpty()) {
            return;
        }
        ServerLevel level = (ServerLevel) event.getLevel();
        try {
            int n = 0;
            Area last = null;
            for (BlockPos p : event.getAffectedBlocks()) {
                Area a = areaAt(level, p);
                if (record(level, a, p, level.getBlockState(p))) {
                    n++;
                    last = a;
                }
            }
            if (n > 0) {
                LOG.info("[VillageFall] repairs: explosion damaged {} blocks of '{}' (queue {})", n, last.name(), queue(level, last.id()));
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onMobBreak(LivingDestroyBlockEvent event) {
        if (event.isCanceled() || off(event.getEntity().level())) {
            return;
        }
        ServerLevel level = (ServerLevel) event.getEntity().level();
        try {
            Area a = areaAt(level, event.getPos());
            if (record(level, a, event.getPos(), event.getState())) {
                LOG.info("[VillageFall] repairs: {} broke {} at {} in '{}' (queue {})", event.getEntity().getName().getString(),
                        event.getState().getBlock().getName().getString(), event.getPos().toShortString(), a.name(), queue(level, a.id()));
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** A player breaking blocks in a village that is not his own (his own village he may rebuild as he likes). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlayerBreak(BlockEvent.BreakEvent event) {
        if (event.isCanceled() || off(event.getLevel()) || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = (ServerLevel) event.getLevel();
        try {
            Area a = areaAt(level, event.getPos());
            if (a == null) {
                LOG.info("[VillageFall] repairs: {} broke a block at {}: no village land there, not recorded",
                        player.getName().getString(), event.getPos().toShortString());
                return;
            }
            Object own = RV.factionOfPlayer(level, player);
            if (a.playerOwned() || (own != null && a.id().equals(RV.id(own)))) {
                LOG.info("[VillageFall] repairs: {} broke a block in his own village '{}': not recorded",
                        player.getName().getString(), a.name());
                return;
            }
            if (record(level, a, event.getPos(), event.getState())) {
                LOG.info("[VillageFall] repairs: player {} broke {} at {} in '{}' (queue {})", player.getName().getString(),
                        event.getState().getBlock().getName().getString(), event.getPos().toShortString(), a.name(), queue(level, a.id()));
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static int queue(ServerLevel level, UUID id) {
        Map<BlockPos, BlockState> m = Store.get(level).data.get(id);
        return m == null ? 0 : m.size();
    }

    private void fail(Throwable t) {
        broken = true;
        LOG.error("[VillageFall] repairs disabled: {}", t.toString());
    }

    // ------------------------------------------------------------------ the chore (called from Chores, lowest priority)

    /** Spots a villager could not reach: left alone for a while. */
    static final Map<BlockPos, Long> SKIP = new HashMap<>();
    private static final Map<UUID, Long> LAST_LOG = new HashMap<>();

    /** @return true if the villager took a repair job */
    static boolean work(ServerLevel level, Villager v, AABB box, UUID fid, String village) {
        if (!FallConfig.REPAIRS.get() || RepairGoal.workers(fid) >= FallConfig.REPAIR_WORKERS.get()) {
            return false;
        }
        Store store = Store.get(level);
        LinkedHashMap<BlockPos, BlockState> map = store.data.get(fid);
        if (map == null || map.isEmpty()) {
            return false;
        }
        long now = level.getGameTime();
        // not while the village is under attack: the walls would be blown up again
        if (!level.getEntitiesOfClass(Monster.class, v.getBoundingBox().inflate(24.0D, 10.0D, 24.0D), Monster::isAlive).isEmpty()) {
            note(fid, now, "[VillageFall] repairs: '" + village + "' waits, monsters near (queue " + map.size() + ")");
            return false;
        }
        BlockPos best = null;
        double bs = Double.MAX_VALUE;
        List<BlockPos> done = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : map.entrySet()) {
            BlockPos p = e.getKey();
            if (!level.isLoaded(p) || RepairGoal.claimed(p) || SKIP.getOrDefault(p, 0L) > now) {
                continue;
            }
            BlockState cur = level.getBlockState(p);
            if (!cur.isAir() && !cur.canBeReplaced()) {
                done.add(p); // already rebuilt (or something else stands there now)
                continue;
            }
            // bottom-up, so every block has something to stand on; among equals the nearest one
            double score = p.getY() * 10000.0D + p.distSqr(v.blockPosition());
            if (score < bs) {
                bs = score;
                best = p;
            }
        }
        if (!done.isEmpty()) {
            done.forEach(map::remove);
            store.setDirty();
        }
        if (best == null) {
            return false;
        }
        LOG.info("[VillageFall] repairs: {} of '{}' goes to rebuild {} at {} (queue {})", v.getName().getString(), village,
                map.get(best).getBlock().getName().getString(), best.toShortString(), map.size());
        RepairGoal.start(v, best, map.get(best), fid);
        return true;
    }

    private static void note(UUID fid, long now, String msg) {
        Long last = LAST_LOG.get(fid);
        if (last == null || now - last > 1200L || now < last) {
            LAST_LOG.put(fid, now);
            LOG.info(msg);
        }
    }

    static void placed(ServerLevel level, UUID fid, BlockPos p) {
        Store store = Store.get(level);
        Map<BlockPos, BlockState> map = store.data.get(fid);
        if (map != null && map.remove(p) != null) {
            store.setDirty();
            if (map.isEmpty()) {
                LOG.info("[VillageFall] repairs: village {} is fully rebuilt", fid);
            }
        }
    }
}
