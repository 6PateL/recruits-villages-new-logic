package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A player attacks a village by hitting its soldiers or by dismantling it (breaking many blocks inside it).
 * - Player in a faction: the normal war for conquest is declared automatically (Recruits Villages' own WarManager).
 * - Player without a faction: a war of annihilation (siege / fall / retreat in FallManager).
 */
public class PlayerAggression {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("villagefall");

    private final FallManager fall;
    private final Map<String, Long> lastTry = new HashMap<>();
    private final Map<UUID, int[]> breaks = new HashMap<>(); // player -> {count, window start tick, last lookup tick}
    private boolean broken = false;

    public PlayerAggression(FallManager fall) {
        this.fall = fall;
    }

    // ------------------------------------------------------------------ hitting soldiers

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onHurt(LivingHurtEvent event) {
        if (broken || !FallConfig.AUTO_WAR.get() || !(event.getEntity().level() instanceof ServerLevel level)
                || !(event.getSource().getEntity() instanceof ServerPlayer player)
                || !event.getEntity().getClass().getName().startsWith("com.talhanation.recruits.")
                || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        try {
            RV.init();
            UUID vid = RV.factionOf(event.getEntity().getUUID());
            if (vid == null) {
                return; // not a village soldier
            }
            Object target = RV.faction(RV.data(level.getServer()), vid);
            if (target != null) {
                attack(level, player, target);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ------------------------------------------------------------------ dismantling

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onBreak(BlockEvent.BreakEvent event) {
        if (broken || !FallConfig.AUTO_WAR.get() || !(event.getPlayer() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level) || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        long now = level.getGameTime();
        int[] st = breaks.computeIfAbsent(player.getUUID(), k -> new int[]{0, (int) now, (int) now - 100});
        if (now - st[1] > 1200L) {
            st[0] = 0;
            st[1] = (int) now;
        }
        st[0]++;
        if (st[0] < FallConfig.DISMANTLE_BLOCKS.get() || now - st[2] < 40L) {
            return;
        }
        st[2] = (int) now;
        try {
            RV.init();
            BlockPos pos = event.getPos();
            Object data = RV.data(level.getServer());
            for (Object f : RV.factions(data)) {
                if (RV.player(f)) {
                    continue;
                }
                AABB box = RV.box(f);
                if (box != null && box.contains(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)) {
                    attack(level, player, f);
                    return;
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ------------------------------------------------------------------ the rule

    private void attack(ServerLevel level, ServerPlayer player, Object target) throws Exception {
        UUID vid = RV.id(target);
        if (RV.player(target) || RV.atWar(vid)) {
            return;
        }
        Object own = RV.factionOfPlayer(level, player);
        long now = level.getGameTime();
        if (own == null) {
            // no faction: war of annihilation
            fall.markPlayerAggression(vid, now);
            return;
        }
        if (RV.factionGroupId(target).equals(RV.factionGroupId(own)) || RV.atWar(RV.id(own))
                || RV.allies(RV.stringId(own), RV.stringId(target))) {
            return;
        }
        String key = player.getUUID() + "|" + vid;
        Long last = lastTry.get(key);
        if (last != null && now - last < 1200L) {
            return;
        }
        lastTry.put(key, now);
        boolean started = RV.declareWar(level, own, target, player);
        LOG.info("[VillageFall] {} attacked '{}': war {}", player.getName().getString(), RV.name(target),
                started ? "declared" : "not possible");
        player.sendSystemMessage(Component.translatable(
                started ? "msg.villagefall.auto_war" : "msg.villagefall.auto_war_failed", RV.name(target)));
    }

    private void fail(Throwable t) {
        broken = true;
        LOG.error("[VillageFall] aggression tracking disabled: {}", t.toString());
    }
}
