package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "War of annihilation" for Recruits Villages: attacks by things that are not another village at war (hostile mobs,
 * barbarians, infected, a player without a faction) can besiege a village, make its survivors flee, or wipe it out.
 * Village-vs-village wars and player-faction wars are never touched: every village at war is skipped.
 */
public class FallManager {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("villagefall");

    private static final class State {
        int siege;
        int hold;
        int baseline;
        long flaggedUntil;
        long lastPlayerKill = -999999L;
    }

    private static final class Retreat {
        UUID village;
        String name;
        BlockPos dest;
        UUID destVillage;     // null = mercenary flight
        String destName = "";
        long started;
        List<UUID> recruits = new ArrayList<>();
        List<UUID> civilians = new ArrayList<>();
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Retreat> retreats = new HashMap<>();
    private boolean broken = false;

    // ------------------------------------------------------------------ helpers

    private static boolean isRecruitsMob(Entity e) {
        String n = e.getClass().getName();
        return n.startsWith("com.talhanation.recruits.") || n.startsWith("com.diego.recruitsvillages.");
    }

    /** Things that make war on a village without declaring one. */
    private static boolean isAnnihilator(ServerLevel level, Entity src) {
        if (src == null) {
            return false;
        }
        if (src instanceof ServerPlayer p) {
            return !hasFaction(level, p); // a player who belongs to no faction
        }
        return src instanceof Enemy && !isRecruitsMob(src);
    }

    /** A player "has a faction" when Recruits Villages knows his team. Such players go to war by the rules (PlayerAggression). */
    static boolean hasFaction(ServerLevel level, ServerPlayer p) {
        if (p.getTeam() == null) {
            return false;
        }
        try {
            RV.init();
            return RV.factionOfPlayer(level, p) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A player without a faction is attacking this village (killing its soldiers or dismantling it). */
    void markPlayerAggression(UUID village, long now) {
        states.computeIfAbsent(village, k -> new State()).lastPlayerKill = now;
    }

    private void fail(Throwable t) {
        broken = true;
        LOG.error("[VillageFall] cannot talk to Recruits Villages, disabled: {}", t.toString());
    }

    // ------------------------------------------------------------------ leader / player kills

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onDeath(LivingDeathEvent event) {
        if (broken || !FallConfig.ENABLED.get() || !(event.getEntity().level() instanceof ServerLevel level)
                || !isRecruitsMob(event.getEntity()) || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        try {
            RV.init();
            UUID vid = RV.factionOf(event.getEntity().getUUID());
            if (vid == null || !isAnnihilator(level, event.getSource().getEntity()) || RV.atWar(vid)) {
                return;
            }
            Object data = RV.data(level.getServer());
            Object f = RV.faction(data, vid);
            if (f == null || (RV.player(f) && !FallConfig.PLAYER_FACTIONS.get())) {
                return;
            }
            State st = states.computeIfAbsent(vid, k -> new State());
            long now = level.getGameTime();
            if (event.getSource().getEntity() instanceof ServerPlayer) {
                st.lastPlayerKill = now;
            }
            Object unit = RV.unitOf(f, event.getEntity().getUUID());
            UUID leader = RV.leaderRef(f);
            if (unit != null && leader != null && leader.equals(RV.unitId(unit))) {
                st.flaggedUntil = now + FallConfig.LEADER_FLAG_MINUTES.get() * 1200L;
                LOG.info("[VillageFall] the leader of '{}' was killed by an enemy", RV.name(f));
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ------------------------------------------------------------------ main loop

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || broken || !FallConfig.ENABLED.get()
                || event.getServer().getTickCount() % 20 != 0 || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        try {
            RV.init();
            tick(event.getServer());
        } catch (Throwable t) {
            fail(t);
        }
    }

    private void tick(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        long now = level.getGameTime();
        Object data = RV.data(server);
        List<Object> factions = RV.factions(data);
        Set<UUID> seen = new HashSet<>();

        for (Object f : factions) {
            UUID id = RV.id(f);
            seen.add(id);
            if (RV.atWar(id) || (RV.player(f) && !FallConfig.PLAYER_FACTIONS.get())) {
                retreats.remove(id); // wars are handled by Recruits Villages itself
                continue;
            }
            BlockPos center = RV.center(f);
            Retreat rt = retreats.get(id);
            if (rt != null) {
                stepRetreat(server, level, data, factions, f, rt);
                continue;
            }
            int recruits = RV.recruits(f);
            int civilians = RV.civilians(f);
            int total = recruits + civilians;
            State st = states.computeIfAbsent(id, k -> new State());
            if (now >= st.flaggedUntil) {
                st.baseline = Math.max(st.baseline, total);
            }

            if (FallConfig.WIPE_FALLS.get() && total == 0) {
                if (++st.hold >= FallConfig.FALL_GRACE_SECONDS.get() * 2) {
                    fall(server, level, data, factions, f, id);
                }
                continue;
            }
            if (!level.hasChunkAt(center)) {
                st.siege = Math.max(0, st.siege - 1);
                st.hold = 0;
                continue;
            }
            int attackers = countAttackers(level, f, center, st, now);
            if (attackers >= FallConfig.MIN_ATTACKERS.get()) {
                st.siege++;
            } else {
                st.siege = Math.max(0, st.siege - 2);
            }
            boolean besieged = st.siege >= FallConfig.SIEGE_SECONDS.get();
            if (besieged && FallConfig.FREEZE_REGROWTH.get()) {
                RV.freezeRegrowth(f);
            }

            // the leader fell and the village is down to a few: the survivors run away
            if (FallConfig.RETREAT_ENABLED.get() && now < st.flaggedUntil && st.baseline > 0
                    && total * 100 < FallConfig.RETREAT_PERCENT.get() * st.baseline) {
                startRetreat(server, level, data, factions, f, st);
                continue;
            }
            if (!besieged) {
                st.hold = 0;
                continue;
            }
            if (recruits <= FallConfig.FALL_GARRISON.get() && attackers > 0) {
                if (++st.hold >= FallConfig.FALL_GRACE_SECONDS.get()) {
                    fall(server, level, data, factions, f, id);
                }
            } else {
                st.hold = 0;
            }
        }
        states.keySet().retainAll(seen);
        retreats.keySet().retainAll(seen);
    }

    private int countAttackers(ServerLevel level, Object faction, BlockPos center, State st, long now) {
        int r = FallConfig.SCAN_RADIUS.get();
        AABB scan = new AABB(center).inflate(r, 48.0D, r);
        int n = level.getEntitiesOfClass(LivingEntity.class, scan,
                m -> m instanceof Enemy && m.isAlive() && !isRecruitsMob(m)).size();
        if (now - st.lastPlayerKill < 2400L) { // a player without a faction recently killed villagers
            for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, scan, p -> p.isAlive() && !hasFaction(level, p))) {
                n += FallConfig.MIN_ATTACKERS.get();
                break;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ fall

    private void fall(MinecraftServer server, ServerLevel level, Object data, List<Object> factions, Object f, UUID id)
            throws Exception {
        String name = RV.name(f);
        BlockPos center = RV.center(f);
        RV.removeVillage(level, data, factions, f);
        states.remove(id);
        retreats.remove(id);
        LOG.info("[VillageFall] village '{}' at {} has fallen", name, center);
        if (FallConfig.ANNOUNCE.get()) {
            server.getPlayerList().broadcastSystemMessage(Component.translatable("msg.villagefall.fallen",
                    name, center.getX(), center.getY(), center.getZ()), false);
        }
    }

    // ------------------------------------------------------------------ retreat

    private void startRetreat(MinecraftServer server, ServerLevel level, Object data, List<Object> factions, Object f,
            State st) throws Exception {
        UUID id = RV.id(f);
        BlockPos center = RV.center(f);
        Retreat rt = new Retreat();
        rt.village = id;
        rt.name = RV.name(f);
        rt.started = level.getGameTime();

        // nearest other village to run to
        double best = (double) FallConfig.REFUGE_RANGE.get() * FallConfig.REFUGE_RANGE.get();
        Object dest = null;
        for (Object o : factions) {
            if (o == f || RV.atWar(RV.id(o)) || RV.player(o)) {
                continue;
            }
            BlockPos c = RV.center(o);
            double dx = c.getX() - center.getX();
            double dz = c.getZ() - center.getZ();
            double d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                dest = o;
            }
        }

        int r = FallConfig.SCAN_RADIUS.get();
        AABB scan = new AABB(center).inflate(r, 48.0D, r);
        Vec3 away = Vec3.ZERO;
        int na = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, scan)) {
            if (!e.isAlive()) {
                continue;
            }
            if (isRecruitsMob(e) && e instanceof Mob && RV.tracked(e.getUUID())) {
                rt.recruits.add(e.getUUID());
            } else if (e.getType() == RV.civilianType()) {
                rt.civilians.add(e.getUUID());
            } else if (e instanceof Enemy && !isRecruitsMob(e)) {
                away = away.add(e.position());
                na++;
            }
        }

        if (dest != null) {
            rt.dest = RV.center(dest);
            rt.destVillage = RV.id(dest);
            rt.destName = RV.name(dest);
        } else {
            Vec3 dir = na > 0 ? new Vec3(center.getX() - away.x / na, 0, center.getZ() - away.z / na)
                    : new Vec3(level.random.nextDouble() - 0.5D, 0, level.random.nextDouble() - 0.5D);
            if (dir.lengthSqr() < 1.0E-4D) {
                dir = new Vec3(1, 0, 0);
            }
            dir = dir.normalize();
            double d = FallConfig.FLEE_DISTANCE.get();
            rt.dest = new BlockPos((int) (center.getX() + dir.x * d), center.getY(), (int) (center.getZ() + dir.z * d));
        }
        retreats.put(id, rt);
        LOG.info("[VillageFall] '{}' retreats ({} recruits, {} civilians) {}", rt.name, rt.recruits.size(),
                rt.civilians.size(), dest != null ? "to '" + rt.destName + "'" : "as mercenaries");
        if (FallConfig.ANNOUNCE.get()) {
            server.getPlayerList().broadcastSystemMessage(dest != null
                    ? Component.translatable("msg.villagefall.retreat_refuge", rt.name, rt.destName,
                            rt.dest.getX(), rt.dest.getZ())
                    : Component.translatable("msg.villagefall.retreat_flee", rt.name,
                            center.getX(), center.getY(), center.getZ()), false);
        }
    }

    private void stepRetreat(MinecraftServer server, ServerLevel level, Object data, List<Object> factions, Object f,
            Retreat rt) throws Exception {
        long elapsed = level.getGameTime() - rt.started;
        boolean chunkLoaded = level.hasChunkAt(RV.center(f));
        List<Entity> alive = new ArrayList<>();
        boolean arrived = true;
        for (UUID u : rt.recruits) {
            Entity e = level.getEntity(u);
            if (e != null && e.isAlive()) {
                alive.add(e);
            }
        }
        for (UUID u : rt.civilians) {
            Entity e = level.getEntity(u);
            if (e != null && e.isAlive()) {
                alive.add(e);
            }
        }
        for (Entity e : alive) {
            double dx = e.getX() - rt.dest.getX();
            double dz = e.getZ() - rt.dest.getZ();
            double d2 = dx * dx + dz * dz;
            boolean there = rt.destVillage != null ? d2 < 14.0D * 14.0D : d2 < 20.0D * 20.0D;
            if (!there) {
                arrived = false;
                moveToward(level, e, rt.dest);
            }
        }
        if (chunkLoaded && !alive.isEmpty() && !arrived && elapsed < FallConfig.FLEE_SECONDS.get() * 20L) {
            return; // still running
        }
        finishRetreat(server, level, data, factions, f, rt, alive, chunkLoaded);
    }

    private void moveToward(ServerLevel level, Entity e, BlockPos dest) {
        Vec3 pos = e.position();
        double dx = dest.getX() + 0.5D - pos.x;
        double dz = dest.getZ() + 0.5D - pos.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0D || !(e instanceof Mob mob)) {
            return;
        }
        double step = Math.min(len, 16.0D);
        double wx = pos.x + dx / len * step;
        double wz = pos.z + dz / len * step;
        int wy = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(wx, pos.y, wz)).getY();
        if (isRecruitsMob(e)) {
            try {
                e.getClass().getMethod("setShouldHoldPos", boolean.class).invoke(e, false);
            } catch (Throwable ignored) {
                // optional
            }
            try {
                e.getClass().getMethod("setMovePos", BlockPos.class).invoke(e, BlockPos.containing(wx, wy, wz));
                e.getClass().getMethod("setShouldMovePos", boolean.class).invoke(e, true);
            } catch (Throwable ignored) {
                // falls back to plain navigation below
            }
        }
        mob.getNavigation().moveTo(wx, wy, wz, 1.3D);
    }

    private void finishRetreat(MinecraftServer server, ServerLevel level, Object data, List<Object> factions, Object f,
            Retreat rt, List<Entity> alive, boolean chunkLoaded) throws Exception {
        retreats.remove(rt.village);
        states.remove(rt.village);
        List<Entity> survRecruits = new ArrayList<>();
        int survCivilians = 0;
        for (Entity e : alive) {
            if (rt.recruits.contains(e.getUUID())) {
                survRecruits.add(e);
            } else {
                survCivilians++;
            }
        }

        if (rt.destVillage != null) {
            Object dest = RV.faction(data, rt.destVillage);
            if (dest != null) {
                List<Object> destRoster = RV.roster(dest);
                int moved = 0;
                for (Entity e : survRecruits) {
                    Object unit = RV.unitOf(f, e.getUUID());
                    if (unit != null && !destRoster.contains(unit)) {
                        destRoster.add(unit);
                        moved++;
                    }
                }
                RV.addCivilians(dest, survCivilians);
                RV.trim(dest);
                RV.removeVillage(level, data, factions, f);
                LOG.info("[VillageFall] refugees of '{}' joined '{}' ({} recruits, {} civilians)", rt.name, rt.destName, moved, survCivilians);
                if (FallConfig.ANNOUNCE.get()) {
                    server.getPlayerList().broadcastSystemMessage(Component.translatable("msg.villagefall.refugees",
                            rt.name, rt.destName, moved, survCivilians), false);
                }
                return;
            }
        }

        // mercenaries: loaded recruits become free (hireable) recruits, everything else vanishes; the land is left dead
        List<Entity> copies = new ArrayList<>();
        if (chunkLoaded) {
            for (Entity e : survRecruits) {
                try {
                    CompoundTag tag = new CompoundTag();
                    e.save(tag);
                    tag.putUUID("UUID", UUID.randomUUID());
                    Entity copy = EntityType.loadEntityRecursive(tag, level, x -> x);
                    if (copy != null) {
                        try {
                            copy.getClass().getMethod("setGroupUUID", UUID.class).invoke(copy, (Object) null);
                        } catch (Throwable ignored) {
                            // optional
                        }
                        try {
                            copy.getClass().getMethod("setShouldMovePos", boolean.class).invoke(copy, false);
                        } catch (Throwable ignored) {
                            // optional
                        }
                        copies.add(copy);
                    }
                } catch (Throwable t) {
                    LOG.warn("[VillageFall] could not free a recruit: {}", t.toString());
                }
            }
        }
        RV.removeVillage(level, data, factions, f);
        for (Entity c : copies) {
            level.addFreshEntity(c);
        }
        LOG.info("[VillageFall] '{}' dissolved: {} mercenaries", rt.name, copies.size());
        if (FallConfig.ANNOUNCE.get()) {
            server.getPlayerList().broadcastSystemMessage(Component.translatable("msg.villagefall.mercenaries",
                    rt.name, copies.size()), false);
        }
    }
}
