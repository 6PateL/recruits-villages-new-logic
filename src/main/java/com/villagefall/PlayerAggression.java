package com.villagefall;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A player who belongs to a faction and attacks a village declares war on it (the normal war for conquest, through
 * Recruits Villages' own WarManager). Players without a faction are handled as "war of annihilation" by FallManager.
 */
public class PlayerAggression {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("villagefall");

    private final Map<String, Long> lastTry = new HashMap<>();
    private boolean broken = false;

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
            Object data = RV.data(level.getServer());
            Object target = RV.faction(data, vid);
            Object own = RV.factionOfPlayer(level, player);
            if (target == null || own == null || RV.player(target)) {
                return; // no faction: that is a war of annihilation, not a declared war
            }
            if (RV.factionGroupId(target).equals(RV.factionGroupId(own)) || RV.atWar(vid) || RV.atWar(RV.id(own))
                    || RV.allies(RV.stringId(own), RV.stringId(target))) {
                return;
            }
            String key = player.getUUID() + "|" + vid;
            long now = level.getGameTime();
            Long last = lastTry.get(key);
            if (last != null && now - last < 1200L) {
                return;
            }
            lastTry.put(key, now);
            boolean started = RV.declareWar(level, own, target, player);
            LOG.info("[VillageFall] {} attacked '{}': war {}", player.getName().getString(), RV.name(target),
                    started ? "declared" : "not possible");
            player.sendSystemMessage(Component.translatable(started ? "msg.villagefall.auto_war" : "msg.villagefall.auto_war_failed",
                    RV.name(target)));
        } catch (Throwable t) {
            broken = true;
            LOG.error("[VillageFall] auto war disabled: {}", t.toString());
        }
    }
}
