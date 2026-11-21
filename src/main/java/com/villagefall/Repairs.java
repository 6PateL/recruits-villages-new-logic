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
        /** Блоки, поставлені гравцями на землі села: це не частина села — їх не відновлюють і їх ламання не злочин. */
        final Map<UUID, java.util.Set<Long>> placed = new HashMap<>();
        /** Креслення власних поселень: блоки, які рота забезпечення має завжди відновлювати (жезл, режим 5). */
        final Map<UUID, LinkedHashMap<BlockPos, BlockState>> marks = new HashMap<>();

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
            ListTag pl = tag.getList("placed", Tag.TAG_COMPOUND);
            for (int i = 0; i < pl.size(); i++) {
                CompoundTag vc = pl.getCompound(i);
                java.util.Set<Long> set = new java.util.HashSet<>();
                for (long l : vc.getLongArray("p")) {
                    set.add(l);
                }
                s.placed.put(vc.getUUID("id"), set);
            }
            ListTag mk = tag.getList("marks", Tag.TAG_COMPOUND);
            for (int i = 0; i < mk.size(); i++) {
                CompoundTag vc = mk.getCompound(i);
                LinkedHashMap<BlockPos, BlockState> map = new LinkedHashMap<>();
                ListTag blocks = vc.getList("b", Tag.TAG_COMPOUND);
                for (int j = 0; j < blocks.size(); j++) {
                    CompoundTag bc = blocks.getCompound(j);
                    BlockState st = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), bc.getCompound("s"));
                    if (!st.isAir()) {
                        map.put(BlockPos.of(bc.getLong("p")), st);
                    }
                }
                s.marks.put(vc.getUUID("id"), map);
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
            ListTag pl = new ListTag();
            placed.forEach((id, set) -> {
                if (set.isEmpty()) {
                    return;
                }
                CompoundTag vc = new CompoundTag();
                vc.putUUID("id", id);
                vc.putLongArray("p", set.stream().mapToLong(Long::longValue).toArray());
                pl.add(vc);
            });
            tag.put("placed", pl);
            ListTag mk = new ListTag();
            marks.forEach((id, map) -> {
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
                mk.add(vc);
            });
            tag.put("marks", mk);
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
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            return null;
        }
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
        java.util.Set<Long> pl = store.placed.get(a.id());
        if (pl != null && pl.remove(p.asLong())) {
            store.setDirty();
            return false; // поставив гравець — не частина села
        }
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
            if (a != null) {
                Store store = Store.get(level);
                java.util.Set<Long> pl = store.placed.get(a.id());
                if (pl != null && pl.remove(event.getPos().asLong())) {
                    store.setDirty();
                    return; // свій же поставлений блок (стовп-втеча, недобудована стіна): прибрати можна вільно
                }
            }
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

    // ------------------------------------------------------------------ player-placed blocks

    /** Гравець ставить блок на землі села — запам'ятати, що це його блок, а не сільський. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.isCanceled() || off(event.getLevel()) || !(event.getEntity() instanceof ServerPlayer)) {
            return;
        }
        ServerLevel level = (ServerLevel) event.getLevel();
        try {
            List<BlockPos> list = new ArrayList<>();
            if (event instanceof BlockEvent.EntityMultiPlaceEvent multi) {
                multi.getReplacedBlockSnapshots().forEach(snap -> list.add(snap.getPos()));
            } else {
                list.add(event.getPos());
            }
            Store store = Store.get(level);
            for (BlockPos p : list) {
                Area a = areaAt(level, p);
                if (a == null) {
                    continue;
                }
                LinkedHashMap<BlockPos, BlockState> mk = store.marks.get(a.id());
                if (mk != null && mk.containsKey(p)) {
                    continue; // поставив за кресленням — частина поселення
                }
                java.util.Set<Long> set = store.placed.computeIfAbsent(a.id(), k -> new java.util.HashSet<>());
                if (set.size() < 20000 && set.add(p.asLong())) {
                    store.setDirty();
                }
                // і з черги ремонту: на цьому місці тепер блок гравця
                LinkedHashMap<BlockPos, BlockState> q = store.data.get(a.id());
                if (q != null && q.remove(p) != null) {
                    store.setDirty();
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Для PlayerAggression: цей блок поставив гравець (у будь-якому селі) — ламати його не злочин. */
    static boolean playerPlaced(ServerLevel level, BlockPos p) {
        for (java.util.Set<Long> set : Store.get(level).placed.values()) {
            if (set.contains(p.asLong())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ blueprint of own villages (baton, mode 5)

    private static final net.minecraft.resources.ResourceLocation BATON = new net.minecraft.resources.ResourceLocation("trainrecruits", "commander_baton");
    private static final int MAX_MARKS = 1024;
    private static final net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> REPAIRABLE =
            net.minecraft.tags.BlockTags.create(new net.minecraft.resources.ResourceLocation("villagefall", "repairable"));
    private static final java.util.Set<net.minecraft.world.level.block.Block> CHEAP = java.util.Set.of(
            Blocks.COBBLESTONE, Blocks.STONE, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS,
            Blocks.MOSSY_COBBLESTONE, Blocks.SMOOTH_STONE, Blocks.BRICKS, Blocks.MUD_BRICKS, Blocks.PACKED_MUD, Blocks.DIRT,
            Blocks.COARSE_DIRT, Blocks.SANDSTONE, Blocks.SMOOTH_SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.RED_SANDSTONE,
            Blocks.ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.DIORITE, Blocks.POLISHED_DIORITE, Blocks.GRANITE,
            Blocks.POLISHED_GRANITE, Blocks.COBBLED_DEEPSLATE, Blocks.DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES,
            Blocks.POLISHED_DEEPSLATE, Blocks.TUFF, Blocks.GLASS, Blocks.GLASS_PANE, Blocks.HAY_BLOCK, Blocks.GRAVEL,
            Blocks.COBBLESTONE_STAIRS, Blocks.STONE_STAIRS, Blocks.STONE_BRICK_STAIRS, Blocks.BRICK_STAIRS, Blocks.MUD_BRICK_STAIRS,
            Blocks.SANDSTONE_STAIRS, Blocks.ANDESITE_STAIRS, Blocks.POLISHED_ANDESITE_STAIRS, Blocks.COBBLED_DEEPSLATE_STAIRS,
            Blocks.DEEPSLATE_BRICK_STAIRS, Blocks.COBBLESTONE_SLAB, Blocks.STONE_SLAB, Blocks.STONE_BRICK_SLAB, Blocks.BRICK_SLAB,
            Blocks.MUD_BRICK_SLAB, Blocks.SANDSTONE_SLAB, Blocks.SMOOTH_STONE_SLAB, Blocks.ANDESITE_SLAB,
            Blocks.POLISHED_ANDESITE_SLAB, Blocks.COBBLED_DEEPSLATE_SLAB, Blocks.DEEPSLATE_BRICK_SLAB, Blocks.LADDER,
            Blocks.IRON_BARS);

    /**
     * Що можна вносити в креслення: лише дешеві будівельні блоки (дерево, камінь, цегла, скло, огорожі, стіни, вовна) —
     * щоб «вічні» ресурси ремонту не ставали фермою дорогих блоків. Розширюється тегом villagefall:repairable.
     */
    static boolean allowedMark(BlockState st) {
        if (st.isAir() || st.hasBlockEntity() || st.getBlock() instanceof LiquidBlock) {
            return false;
        }
        return CHEAP.contains(st.getBlock()) || st.is(REPAIRABLE) || st.is(net.minecraft.tags.BlockTags.PLANKS)
                || st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS)
                || st.is(net.minecraft.tags.BlockTags.WOODEN_SLABS) || st.is(net.minecraft.tags.BlockTags.WOODEN_FENCES)
                || st.is(net.minecraft.tags.BlockTags.FENCE_GATES) || st.is(net.minecraft.tags.BlockTags.WALLS)
                || st.is(net.minecraft.tags.BlockTags.WOOL) || st.is(net.minecraft.tags.BlockTags.WOODEN_TRAPDOORS);
    }

    static boolean memorizeBaton(net.minecraft.world.item.ItemStack s) {
        return !s.isEmpty() && BATON.equals(BuiltInRegistries.ITEM.getKey(s.getItem())) && s.hasTag() && s.getTag().getInt("tr_mode") == 5;
    }

    /** Власне поселення гравця (його фракція або поселення гравця), у землі якого стоїть точка. */
    private Area ownAreaAt(ServerLevel level, ServerPlayer player, BlockPos p) throws Exception {
        Area a = areaAt(level, p);
        if (a == null) {
            return null;
        }
        Object own = RV.factionOfPlayer(level, player);
        return own != null && a.id().equals(RV.id(own)) || a.playerOwned() && own == null ? a : null;
    }

    /**
     * Жезл у режимі «запам'ятати блок»: у лівій руці блок, ПКМ — зелена проєкція з'являється там, де він став би
     * (або на самому блоці, якщо клацнути по такому ж блоку). Повторний ПКМ по позначці — прибрати її.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onMemorize(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND || !memorizeBaton(event.getItemStack())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        if (off(event.getLevel()) || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = (ServerLevel) event.getLevel();
        try {
            BlockPos clicked = event.getPos();
            BlockState clickedState = level.getBlockState(clicked);
            BlockPos ahead = clicked.relative(event.getFace() == null ? net.minecraft.core.Direction.UP : event.getFace());
            Store store = Store.get(level);
            // прибрати позначку
            for (BlockPos p : new BlockPos[]{clicked, ahead}) {
                Area a = ownAreaAt(level, player, p);
                LinkedHashMap<BlockPos, BlockState> mk = a == null ? null : store.marks.get(a.id());
                if (mk != null && mk.remove(p) != null) {
                    LinkedHashMap<BlockPos, BlockState> q = store.data.get(a.id());
                    if (q != null) {
                        q.remove(p);
                    }
                    store.setDirty();
                    player.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.villagefall.mark_removed"), true);
                    level.playSound(null, p, net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM, net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.0F);
                    sendView(player);
                    return;
                }
            }
            net.minecraft.world.item.ItemStack off = player.getOffhandItem();
            if (!(off.getItem() instanceof net.minecraft.world.item.BlockItem bi)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.villagefall.mark_need_block"), true);
                return;
            }
            BlockPos target;
            BlockState st;
            if (clickedState.is(bi.getBlock())) {
                target = clicked;
                st = clickedState; // уже стоїть — запам'ятати як є
            } else {
                target = clickedState.canBeReplaced() ? clicked : ahead;
                net.minecraft.world.item.context.BlockPlaceContext ctx = new net.minecraft.world.item.context.BlockPlaceContext(
                        level, player, net.minecraft.world.InteractionHand.OFF_HAND, off,
                        new net.minecraft.world.phys.BlockHitResult(event.getHitVec().getLocation(), event.getFace(), clicked, false));
                st = bi.getBlock().getStateForPlacement(ctx);
                if (st == null) {
                    st = bi.getBlock().defaultBlockState();
                }
            }
            if (!allowedMark(st)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.villagefall.mark_forbidden",
                        st.getBlock().getName()), true);
                return;
            }
            Area a = ownAreaAt(level, player, target);
            if (a == null) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.villagefall.mark_not_own"), true);
                return;
            }
            LinkedHashMap<BlockPos, BlockState> mk = store.marks.computeIfAbsent(a.id(), k -> new LinkedHashMap<>());
            if (mk.size() >= MAX_MARKS) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.villagefall.mark_full", MAX_MARKS), true);
                return;
            }
            mk.put(target.immutable(), st);
            java.util.Set<Long> pl = store.placed.get(a.id());
            if (pl != null) {
                pl.remove(target.asLong()); // тепер це частина поселення
            }
            if (!level.getBlockState(target).equals(st)) {
                store.data.computeIfAbsent(a.id(), k -> new LinkedHashMap<>()).put(target.immutable(), st);
            }
            store.setDirty();
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("msg.villagefall.mark_added",
                    st.getBlock().getName(), mk.size()), true);
            level.playSound(null, target, net.minecraft.sounds.SoundEvents.ITEM_FRAME_PLACE, net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 1.2F);
            sendView(player);
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Креслення живе: якщо позначений блок зник (зламали, згорів) — знову в чергу ремонту. */
    @SubscribeEvent
    public void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || broken || !FallConfig.REPAIRS.get()) {
            return;
        }
        int tick = event.getServer().getTickCount();
        ServerLevel level = event.getServer().overworld();
        try {
            if (tick % 10 == 0) {
                for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
                    boolean holding = memorizeBaton(p.getMainHandItem());
                    if (holding || VIEWING.contains(p.getUUID())) {
                        if (holding) {
                            VIEWING.add(p.getUUID());
                            sendView(p);
                        } else {
                            VIEWING.remove(p.getUUID());
                            VfNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p), new VfNet.MemoView(List.of(), List.of()));
                        }
                    }
                }
            }
            if (tick % 200 != 0) {
                return;
            }
            Store store = Store.get(level);
            store.marks.forEach((id, mk) -> {
                LinkedHashMap<BlockPos, BlockState> q = store.data.computeIfAbsent(id, k -> new LinkedHashMap<>());
                mk.forEach((p, st) -> {
                    if (level.isLoaded(p) && !q.containsKey(p)) {
                        BlockState cur = level.getBlockState(p);
                        if (cur.isAir() || cur.canBeReplaced()) {
                            q.put(p, st);
                            store.setDirty();
                        }
                    }
                });
            });
        } catch (Throwable t) {
            fail(t);
        }
    }

    private static final java.util.Set<UUID> VIEWING = new java.util.HashSet<>();

    /** Межі власних поселень і позначки креслення поруч — гравцю з жезлом у режимі запам'ятовування. */
    private void sendView(ServerPlayer player) throws Exception {
        ServerLevel level = player.serverLevel();
        if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
            VfNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player), new VfNet.MemoView(List.of(), List.of()));
            return;
        }
        Object own = RV.factionOfPlayer(level, player);
        List<AABB> boxes = new ArrayList<>();
        List<VfNet.Mark> marks = new ArrayList<>();
        Store store = Store.get(level);
        for (Area a : areas(level)) {
            if (!(own != null && a.id().equals(RV.id(own)) || a.playerOwned() && own == null)) {
                continue;
            }
            boxes.add(a.box());
            LinkedHashMap<BlockPos, BlockState> mk = store.marks.get(a.id());
            if (mk != null) {
                for (Map.Entry<BlockPos, BlockState> e : mk.entrySet()) {
                    if (e.getKey().distSqr(player.blockPosition()) < 96 * 96 && marks.size() < 2048) {
                        marks.add(new VfNet.Mark(e.getKey().asLong(), net.minecraft.world.level.block.Block.getId(e.getValue())));
                    }
                }
            }
        }
        VfNet.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player), new VfNet.MemoView(boxes, marks));
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
