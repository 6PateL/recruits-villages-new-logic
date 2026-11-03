package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Reflection bridge to Recruits Villages / Recruits (no compile-time dependency). Methods throw on failure. */
final class RV {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("villagefall");

    private static boolean ready = false;
    private static Class<?> dataClass, factionClass, mgrClass, warClass, popClass, civClass;
    private static Method dataGet, getFactions, removeFaction, getFaction;
    private static Method fId, fName, fCenter, fPlayer, fRecruits, fCivilians, fSetPop, fSetRecruitProg, fSetCivProg,
            fCapitalId, fSetCapitalId, fLeaderRef, fRoster, fAddCivilians, fStringId;
    private static Method factionOfPlayer, declareWar, fFactionId, fIsPlayerFaction2;
    private static Method villageBox, absorb, factionOf, unitOf, isTracked, entityIdForUnit, isAtWar, civilianType,
            trimToCapacity, unitId;

    private RV() {}

    static synchronized void init() throws Exception {
        if (ready) {
            return;
        }
        dataClass = Class.forName("com.diego.recruitsvillages.data.LivingVillagesSavedData");
        factionClass = Class.forName("com.diego.recruitsvillages.data.FactionData");
        mgrClass = Class.forName("com.diego.recruitsvillages.materialize.MaterializationManager");
        warClass = Class.forName("com.diego.recruitsvillages.war.WarManager");
        popClass = Class.forName("com.diego.recruitsvillages.sim.Population");
        civClass = Class.forName("com.diego.recruitsvillages.materialize.CivilianMaterializer");
        Class<?> unitClass = Class.forName("com.diego.recruitsvillages.data.RecruitUnit");
        dataGet = dataClass.getMethod("get", MinecraftServer.class);
        getFactions = dataClass.getMethod("getFactions");
        removeFaction = dataClass.getMethod("removeFaction", UUID.class);
        getFaction = dataClass.getMethod("getFaction", UUID.class);
        fId = factionClass.getMethod("getId");
        fName = factionClass.getMethod("getName");
        fCenter = factionClass.getMethod("getCenterPos");
        fPlayer = factionClass.getMethod("isPlayerFaction");
        fRecruits = factionClass.getMethod("getRecruitCount");
        fCivilians = factionClass.getMethod("getCivilianCount");
        fSetPop = factionClass.getMethod("setPopulationStepProgress", int.class);
        fSetRecruitProg = factionClass.getMethod("setRecruitProgress", int.class);
        fSetCivProg = factionClass.getMethod("setCivilianProgress", int.class);
        fCapitalId = factionClass.getMethod("getCapitalId");
        fSetCapitalId = factionClass.getMethod("setCapitalId", UUID.class);
        fLeaderRef = factionClass.getMethod("getLeaderRef");
        fRoster = factionClass.getMethod("getRoster");
        fAddCivilians = factionClass.getMethod("addCivilians", int.class);
        fStringId = factionClass.getMethod("getStringID");
        villageBox = mgrClass.getMethod("villageBox", factionClass);
        absorb = mgrClass.getMethod("absorb", factionClass);
        factionOf = mgrClass.getMethod("factionOf", UUID.class);
        unitOf = mgrClass.getMethod("unitOf", factionClass, UUID.class);
        isTracked = mgrClass.getMethod("isTrackedRecruit", UUID.class);
        entityIdForUnit = mgrClass.getMethod("entityIdForUnit", UUID.class, UUID.class);
        isAtWar = warClass.getMethod("isAtWar", UUID.class);
        civilianType = civClass.getMethod("civilianType");
        trimToCapacity = popClass.getMethod("trimToCapacity", factionClass);
        unitId = unitClass.getMethod("getId");
        Class<?> pfs = Class.forName("com.diego.recruitsvillages.player.PlayerFactionService");
        factionOfPlayer = pfs.getMethod("factionOfPlayer", ServerLevel.class, net.minecraft.server.level.ServerPlayer.class);
        declareWar = warClass.getMethod("declareWar", ServerLevel.class, factionClass, factionClass,
                net.minecraft.server.level.ServerPlayer.class, java.util.Set.class, java.util.Set.class);
        fFactionId = factionClass.getMethod("getFactionId");
        ready = true;
    }

    static Object factionOfPlayer(ServerLevel level, net.minecraft.server.level.ServerPlayer p) throws Exception {
        return factionOfPlayer.invoke(null, level, p);
    }

    static boolean declareWar(ServerLevel level, Object attacker, Object defender, net.minecraft.server.level.ServerPlayer p)
            throws Exception {
        return Boolean.TRUE.equals(declareWar.invoke(null, level, attacker, defender, p, null, null));
    }

    static UUID factionGroupId(Object f) throws Exception { return (UUID) fFactionId.invoke(f); }
    static String stringId(Object f) throws Exception { return String.valueOf(fStringId.invoke(f)); }

    /** Recruits diplomacy: are the two teams allies? Best effort (false when unsure). */
    static boolean allies(String teamA, String teamB) {
        try {
            Class<?> fe = Class.forName("com.talhanation.recruits.FactionEvents");
            Object dm = fe.getField("recruitsDiplomacyManager").get(null);
            if (dm == null || teamA == null || teamB == null || teamA.isEmpty() || teamB.isEmpty()) {
                return false;
            }
            Object rel = dm.getClass().getMethod("getRelation", String.class, String.class).invoke(dm, teamA, teamB);
            return rel != null && "ALLY".equals(String.valueOf(rel));
        } catch (Throwable t) {
            return false;
        }
    }

    static Object data(MinecraftServer s) throws Exception { return dataGet.invoke(null, s); }

    @SuppressWarnings("unchecked")
    static List<Object> factions(Object data) throws Exception {
        return new ArrayList<>((Collection<Object>) getFactions.invoke(data));
    }

    static Object faction(Object data, UUID id) throws Exception { return getFaction.invoke(data, id); }
    static UUID id(Object f) throws Exception { return (UUID) fId.invoke(f); }
    static String name(Object f) throws Exception { return String.valueOf(fName.invoke(f)); }
    static BlockPos center(Object f) throws Exception { return (BlockPos) fCenter.invoke(f); }
    static boolean player(Object f) throws Exception { return Boolean.TRUE.equals(fPlayer.invoke(f)); }
    static int recruits(Object f) throws Exception { return (Integer) fRecruits.invoke(f); }
    static int civilians(Object f) throws Exception { return (Integer) fCivilians.invoke(f); }
    static UUID capitalId(Object f) throws Exception { return (UUID) fCapitalId.invoke(f); }
    static UUID leaderRef(Object f) throws Exception { return (UUID) fLeaderRef.invoke(f); }
    static boolean atWar(UUID id) throws Exception { return Boolean.TRUE.equals(isAtWar.invoke(null, id)); }
    static UUID factionOf(UUID entity) throws Exception { return (UUID) factionOf.invoke(null, entity); }
    static boolean tracked(UUID entity) throws Exception { return Boolean.TRUE.equals(isTracked.invoke(null, entity)); }
    static EntityType<?> civilianType() throws Exception { return (EntityType<?>) civilianType.invoke(null); }

    @SuppressWarnings("unchecked")
    static List<Object> roster(Object f) throws Exception { return (List<Object>) fRoster.invoke(f); }

    static Object unitOf(Object f, UUID entity) throws Exception { return unitOf.invoke(null, f, entity); }
    static UUID unitId(Object u) throws Exception { return (UUID) unitId.invoke(u); }
    static void addCivilians(Object f, int n) throws Exception { fAddCivilians.invoke(f, n); }
    static void trim(Object f) throws Exception { trimToCapacity.invoke(null, f); }

    static void freezeRegrowth(Object f) throws Exception {
        fSetPop.invoke(f, 0);
        fSetRecruitProg.invoke(f, 0);
        fSetCivProg.invoke(f, 0);
    }

    static boolean claimsBroken = false;

    /** UUID of the Recruits claim that covers the chunk of the position, or null. */
    static UUID claimAt(ServerLevel level, BlockPos p) {
        if (claimsBroken) {
            return null;
        }
        try {
            Class<?> ce = Class.forName("com.talhanation.recruits.ClaimEvents");
            Object cm = ce.getField("recruitsClaimManager").get(null);
            Object claim = cm.getClass().getMethod("getClaim", net.minecraft.world.level.ChunkPos.class)
                    .invoke(cm, new net.minecraft.world.level.ChunkPos(p));
            return claim == null ? null : (UUID) claim.getClass().getMethod("getUUID").invoke(claim);
        } catch (Throwable t) {
            claimsBroken = true;
            LOG.warn("[VillageFall] claims unavailable, falling back to village boxes: {}", t.toString());
            return null;
        }
    }

    static net.minecraft.world.phys.AABB box(Object f) {
        try {
            return (net.minecraft.world.phys.AABB) villageBox.invoke(null, f);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Removes the village completely: soldiers and villagers, data record, Recruits claim and team. Vassals get a new capital. */
    static void removeVillage(ServerLevel level, Object data, List<Object> all, Object f) throws Exception {
        UUID id = id(f);
        if (capitalId(f) == null) {
            Object newCapital = null;
            for (Object o : all) {
                if (o != f && id.equals(capitalId(o))) {
                    if (newCapital == null) {
                        newCapital = o;
                        fSetCapitalId.invoke(o, (Object) null);
                    } else {
                        fSetCapitalId.invoke(o, id(newCapital));
                    }
                }
            }
        }
        BlockPos center = center(f);
        String stringId = String.valueOf(fStringId.invoke(f));
        try {
            absorb.invoke(null, f);
        } catch (Throwable t) {
            LOG.warn("[VillageFall] absorb failed: {}", t.toString());
        }
        removeFaction.invoke(data, id);
        ((SavedData) data).setDirty();
        // the claim and team that Recruits Villages created for the village (the /remove command leaves them behind)
        try {
            Class<?> ce = Class.forName("com.talhanation.recruits.ClaimEvents");
            Object cm = ce.getField("recruitsClaimManager").get(null);
            if (cm != null) {
                Object claim = cm.getClass().getMethod("getClaim", ChunkPos.class).invoke(cm, new ChunkPos(center));
                if (claim != null) {
                    UUID cid = (UUID) claim.getClass().getMethod("getUUID").invoke(claim);
                    cm.getClass().getMethod("removeClaim", ServerLevel.class, UUID.class).invoke(cm, level, cid);
                }
            }
        } catch (Throwable t) {
            LOG.warn("[VillageFall] could not remove the claim: {}", t.toString());
        }
        try {
            if (!stringId.isEmpty()) {
                Class<?> fe = Class.forName("com.talhanation.recruits.FactionEvents");
                fe.getMethod("removeTeam", ServerLevel.class, String.class).invoke(null, level, stringId);
            }
        } catch (Throwable t) {
            LOG.warn("[VillageFall] could not remove the team: {}", t.toString());
        }
    }
}
