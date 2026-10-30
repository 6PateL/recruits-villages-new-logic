package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Siege and fall of Recruits Villages settlements. Works on the village data of the recruitsvillages mod (by reflection,
 * so nothing is patched): attackers inside a village build up a siege, a siege freezes regrowth, and when the garrison
 * is gone the village is removed.
 */
public class FallManager {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("villagefall");

    private static final class State {
        int siege;      // seconds of continuous attack
        int hold;       // seconds the fall condition has held
    }

    private final Map<UUID, State> states = new HashMap<>();
    private boolean broken = false;

    // reflection handles
    private Class<?> dataClass, factionClass, mgrClass;
    private Method dataGet, getFactions, removeFaction;
    private Method fId, fName, fCenter, fPlayer, fRecruits, fCivilians, fSetPop, fSetRecruitProg, fSetCivProg,
            fCapitalId, fSetCapitalId, fLeaderRef, villageBox, absorb;

    private void init() throws Exception {
        dataClass = Class.forName("com.diego.recruitsvillages.data.LivingVillagesSavedData");
        factionClass = Class.forName("com.diego.recruitsvillages.data.FactionData");
        mgrClass = Class.forName("com.diego.recruitsvillages.materialize.MaterializationManager");
        dataGet = dataClass.getMethod("get", MinecraftServer.class);
        getFactions = dataClass.getMethod("getFactions");
        removeFaction = dataClass.getMethod("removeFaction", UUID.class);
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
        villageBox = mgrClass.getMethod("villageBox", factionClass);
        absorb = mgrClass.getMethod("absorb", factionClass);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || broken || !FallConfig.ENABLED.get()
                || event.getServer().getTickCount() % 20 != 0 || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        try {
            if (dataClass == null) {
                init();
            }
            tick(event.getServer());
        } catch (Throwable t) {
            broken = true;
            LOG.error("[VillageFall] cannot talk to Recruits Villages, disabled: {}", t.toString());
        }
    }

    private void tick(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        Object data = dataGet.invoke(null, server);
        @SuppressWarnings("unchecked")
        Collection<Object> all = (Collection<Object>) getFactions.invoke(data);
        List<Object> factions = new ArrayList<>(all);
        Set<UUID> seen = new HashSet<>();

        for (Object f : factions) {
            UUID id = (UUID) fId.invoke(f);
            seen.add(id);
            if (Boolean.TRUE.equals(fPlayer.invoke(f)) && !FallConfig.PLAYER_FACTIONS.get()) {
                continue;
            }
            int recruits = (Integer) fRecruits.invoke(f);
            int civilians = (Integer) fCivilians.invoke(f);
            BlockPos center = (BlockPos) fCenter.invoke(f);
            State st = states.computeIfAbsent(id, k -> new State());

            // a completely empty village is dead
            if (FallConfig.WIPE_FALLS.get() && recruits == 0 && civilians == 0) {
                if (++st.hold >= FallConfig.FALL_GRACE_SECONDS.get() * 2) {
                    fall(server, level, data, factions, f, id, center);
                }
                continue;
            }

            // attackers only exist while the village is loaded
            if (!level.hasChunkAt(center)) {
                st.siege = Math.max(0, st.siege - 1);
                st.hold = 0;
                continue;
            }
            int attackers = countAttackers(level, f, center);
            if (attackers >= FallConfig.MIN_ATTACKERS.get()) {
                st.siege++;
            } else {
                st.siege = Math.max(0, st.siege - 2);
            }
            boolean besieged = st.siege >= FallConfig.SIEGE_SECONDS.get();
            if (!besieged) {
                st.hold = 0;
                continue;
            }
            if (FallConfig.FREEZE_REGROWTH.get()) {
                fSetPop.invoke(f, 0);
                fSetRecruitProg.invoke(f, 0);
                fSetCivProg.invoke(f, 0);
            }
            if (recruits <= FallConfig.FALL_GARRISON.get() && attackers > 0) {
                if (++st.hold >= FallConfig.FALL_GRACE_SECONDS.get()) {
                    fall(server, level, data, factions, f, id, center);
                }
            } else {
                st.hold = 0;
            }
        }
        states.keySet().retainAll(seen);
    }

    private int countAttackers(ServerLevel level, Object faction, BlockPos center) throws Exception {
        AABB box;
        try {
            box = (AABB) villageBox.invoke(null, faction);
        } catch (Throwable t) {
            box = null;
        }
        int r = FallConfig.SCAN_RADIUS.get();
        AABB scan = new AABB(center).inflate(r, 48.0D, r);
        int n = 0;
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, scan,
                m -> m instanceof Enemy && m.isAlive() && !isRecruitsMob(m))) {
            n++;
        }
        return n;
    }

    private static boolean isRecruitsMob(LivingEntity e) {
        String n = e.getClass().getName();
        return n.startsWith("com.talhanation.recruits.") || n.startsWith("com.diego.recruitsvillages.");
    }

    @SuppressWarnings("unchecked")
    private void fall(MinecraftServer server, ServerLevel level, Object data, List<Object> factions, Object f, UUID id,
            BlockPos center) throws Exception {
        String name = String.valueOf(fName.invoke(f));

        // if this was a capital, the first vassal becomes the new capital (same as the mod does for conquests)
        if (fCapitalId.invoke(f) == null) {
            Object newCapital = null;
            for (Object o : factions) {
                if (o != f && id.equals(fCapitalId.invoke(o))) {
                    if (newCapital == null) {
                        newCapital = o;
                        fSetCapitalId.invoke(o, (Object) null);
                    } else {
                        fSetCapitalId.invoke(o, fId.invoke(newCapital));
                    }
                }
            }
        }
        try {
            absorb.invoke(null, f); // removes its soldiers and villagers from the world
        } catch (Throwable t) {
            LOG.warn("[VillageFall] absorb failed: {}", t.toString());
        }
        removeFaction.invoke(data, id);
        ((net.minecraft.world.level.saveddata.SavedData) data).setDirty();
        states.remove(id);
        LOG.info("[VillageFall] village '{}' at {} has fallen", name, center);
        if (FallConfig.ANNOUNCE.get()) {
            server.getPlayerList().broadcastSystemMessage(Component.translatable("msg.villagefall.fallen",
                    name, center.getX(), center.getY(), center.getZ()), false);
        }
    }
}
