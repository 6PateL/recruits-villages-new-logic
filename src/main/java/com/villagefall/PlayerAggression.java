package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
                || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        boolean soldier = event.getEntity().getClass().getName().startsWith("com.talhanation.recruits.");
        boolean villager = event.getEntity() instanceof net.minecraft.world.entity.npc.Villager;
        if (!soldier && !villager) {
            return;
        }
        try {
            RV.init();
            UUID vid = RV.memberFaction(event.getEntity());
            if (vid == null) {
                return; // not a village member
            }
            Object target = RV.faction(RV.data(level.getServer()), vid);
            if (target != null && !RV.player(target)) {
                offence(level, player, target, "hit", soldier ? 1 : FallConfig.HIT_ATTEMPTS.get());
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ------------------------------------------------------------------ territory helpers

    private static final net.minecraft.resources.ResourceLocation BAZOOKA = new net.minecraft.resources.ResourceLocation("hostile_civilization", "bazooka");
    private static final net.minecraft.resources.ResourceLocation FLAMETHROWER = new net.minecraft.resources.ResourceLocation("fromanotherworld", "flamethrower_mk_1");
    private static final net.minecraft.resources.ResourceLocation MOLOTOV_PROJ = new net.minecraft.resources.ResourceLocation("hostile_civilization", "molotov_projectile");

    /** Non-player village whose claimed chunks (Recruits claim) contain the point; falls back to the village box. */
    private Object villageAt(ServerLevel level, double x, double y, double z) throws Exception {
        RV.init();
        BlockPos p = BlockPos.containing(x, y, z);
        UUID claim = RV.claimAt(level, p);
        for (Object f : RV.factions(RV.data(level.getServer()))) {
            if (RV.player(f)) {
                continue;
            }
            if (claim != null && claim.equals(RV.claimAt(level, RV.center(f)))) {
                return f;
            }
            // no claim there (or a village without one): the village's own box counts as its land
            AABB box = RV.box(f);
            if (box != null && box.contains(x, y, z)) {
                return f;
            }
        }
        return null;
    }

    /** Village whose member (villager or soldier) stands within r blocks of the point, or null. */
    private Object villageMemberNear(ServerLevel level, Vec3 pos, double r) throws Exception {
        RV.init();
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                new AABB(pos, pos).inflate(r))) {
            if (e instanceof net.minecraft.world.entity.npc.Villager
                    || e.getClass().getName().startsWith("com.talhanation.recruits.")) {
                UUID vid = RV.memberFaction(e);
                if (vid != null) {
                    Object f = RV.faction(RV.data(level.getServer()), vid);
                    if (f != null && !RV.player(f)) {
                        return f;
                    }
                }
            }
        }
        return null;
    }

    /** Hostile mobs nearby: then shooting/burning there counts as defence, not as an attack. */
    private boolean defending(ServerLevel level, Vec3 pos) {
        return !level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, new AABB(pos, pos).inflate(32.0D),
                m -> m instanceof net.minecraft.world.entity.monster.Enemy && m.isAlive()
                        && !m.getClass().getName().startsWith("com.talhanation.recruits.")
                        && !m.getClass().getName().startsWith("com.diego.recruitsvillages.")).isEmpty();
    }

    // ------------------------------------------------------------------ weapons

    /** Bazooka fired while standing in the village's chunks (and not defending against monsters). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (broken || !FallConfig.AUTO_WAR.get() || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level) || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        var item = event.getItemStack().getItem();
        if (!BuiltInRegistries.ITEM.getKey(item).equals(BAZOOKA) || player.getCooldowns().isOnCooldown(item)) {
            return;
        }
        try {
            Object v = villageAt(level, player.getX(), player.getY(), player.getZ());
            if (v != null && !defending(level, player.position())) {
                offence(level, player, v, "bazooka", 1);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** The bazooka shell explodes in the village's chunks or next to its people. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onExplosion(net.minecraftforge.event.level.ExplosionEvent.Start event) {
        if (broken || !FallConfig.AUTO_WAR.get() || !(event.getLevel() instanceof ServerLevel level)
                || !(event.getExplosion().getExploder() instanceof net.minecraft.world.entity.projectile.LargeFireball ball)
                || !(ball.getOwner() instanceof ServerPlayer player) || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        try {
            Vec3 at = event.getExplosion().getPosition();
            Object v = villageAt(level, at.x, at.y, at.z);
            if (v == null) {
                v = villageMemberNear(level, at, 6.0D);
            }
            if (v != null && !defending(level, at)) {
                offence(level, player, v, "bazooka", 1);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Flamethrower used while standing in (or pointing into) the village's chunks. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onFlame(net.minecraftforge.event.entity.living.LivingEntityUseItemEvent.Tick event) {
        if (broken || !FallConfig.AUTO_WAR.get() || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getEntity().level() instanceof ServerLevel level) || event.getDuration() % 10 != 0
                || !BuiltInRegistries.ITEM.getKey(event.getItem().getItem()).equals(FLAMETHROWER)
                || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        try {
            Vec3 ahead = player.getEyePosition().add(player.getLookAngle().scale(7.0D));
            Object v = villageAt(level, player.getX(), player.getY(), player.getZ());
            if (v == null) {
                v = villageAt(level, ahead.x, ahead.y, ahead.z);
            }
            if (v == null) {
                v = villageMemberNear(level, ahead, 6.0D);
            }
            if (v != null && !defending(level, player.position())) {
                offence(level, player, v, "flame", 1);
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Molotov lands in the village's chunks or next to its people. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onMolotovImpact(net.minecraftforge.event.entity.ProjectileImpactEvent event) {
        if (broken || !FallConfig.AUTO_WAR.get() || !(event.getProjectile().level() instanceof ServerLevel level)
                || !(event.getProjectile().getOwner() instanceof ServerPlayer player)
                || !BuiltInRegistries.ENTITY_TYPE.getKey(event.getProjectile().getType()).equals(MOLOTOV_PROJ)
                || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        try {
            Vec3 at = event.getRayTraceResult().getLocation();
            Object v = villageAt(level, at.x, at.y, at.z);
            if (v == null) {
                v = villageMemberNear(level, at, 5.0D);
            }
            if (v != null && !defending(level, at)) {
                offence(level, player, v, "molotov", 1);
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
                    if (ownVillage(level, player, f) || remembered(level, player, f)) {
                        return;
                    }
                    attack(level, player, f);
                    return;
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ------------------------------------------------------------------ arson, TNT

    private final Map<UUID, Long> lastFlame = new HashMap<>();
    private static final Map<String, int[]> arson = new HashMap<>(); // player|village|kind -> {count, window start}

    /** Fire is lit by a player in a village's chunks (flint and steel, fire charge, lava). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onIgnite(PlayerInteractEvent.RightClickBlock event) {
        if (broken || !FallConfig.AUTO_WAR.get() || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level) || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        var item = event.getItemStack().getItem();
        if (item != Items.FLINT_AND_STEEL && item != Items.FIRE_CHARGE && item != Items.LAVA_BUCKET) {
            return;
        }
        try {
            BlockPos pos = event.getPos();
            Object v = villageAt(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
            if (v != null) {
                offence(level, player, v, "fire", FallConfig.ARSON_ATTEMPTS.get());
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** TNT placed by a player in a village's chunks. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlaceTnt(BlockEvent.EntityPlaceEvent event) {
        if (broken || !FallConfig.AUTO_WAR.get() || event.getPlacedBlock().getBlock() != Blocks.TNT
                || !(event.getEntity() instanceof ServerPlayer player) || !(event.getLevel() instanceof ServerLevel level)
                || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        try {
            BlockPos p = event.getPos();
            Object v = villageAt(level, p.getX() + 0.5D, p.getY() + 0.5D, p.getZ() + 0.5D);
            if (v != null) {
                offence(level, player, v, "tnt", FallConfig.TNT_ATTEMPTS.get());
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** TNT lit by a player in a village's chunks: immediate. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onJoin(EntityJoinLevelEvent event) {
        if (broken || !FallConfig.AUTO_WAR.get() || !(event.getLevel() instanceof ServerLevel level)
                || !ModList.get().isLoaded("recruitsvillages")) {
            return;
        }
        try {
            // flamethrower puff (From Another World): independent of use-item events
            if (event.getEntity() instanceof Projectile fire && fire.getOwner() instanceof ServerPlayer fp
                    && BuiltInRegistries.ENTITY_TYPE.getKey(fire.getType()).toString().equals("fromanotherworld:flamethrower_fire")) {
                long now = level.getGameTime();
                Long last = lastFlame.get(fp.getUUID());
                if (last == null || now - last >= 10L) {
                    lastFlame.put(fp.getUUID(), now);
                    Vec3 at = fire.position();
                    Vec3 ahead = at.add(fp.getLookAngle().scale(6.0D));
                    Object v = villageAt(level, fp.getX(), fp.getY(), fp.getZ());
                    if (v == null) v = villageAt(level, ahead.x, ahead.y, ahead.z);
                    if (v == null) v = villageMemberNear(level, ahead, 6.0D);
                    boolean def = v != null && defending(level, fp.position());
                    LOG.info("[VillageFall] flame by {}: village={} defending={}", fp.getName().getString(),
                            v == null ? "none" : RV.name(v), def);
                    if (v != null && !def) {
                        offence(level, fp, v, "flame", 1);
                    }
                }
                return;
            }
            if (event.getEntity() instanceof PrimedTnt tnt && tnt.getOwner() instanceof ServerPlayer player) {
                Object v = villageAt(level, tnt.getX(), tnt.getY(), tnt.getZ());
                if (v != null) {
                    offence(level, player, v, "tnt_lit", 1);
                }
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    // ------------------------------------------------------------------ the rule

    /**
     * One unlawful act against a village. With {@code threshold} 1 the reaction is immediate; otherwise the player
     * gets a warning (n/threshold) and the attack is declared once the threshold is reached within the window.
     */
    /** The player belongs to this village's faction (founded it or joined it): it is his own land. */
    private boolean ownVillage(ServerLevel level, ServerPlayer player, Object village) throws Exception {
        Object own = RV.factionOfPlayer(level, player);
        return own != null && RV.factionGroupId(own).equals(RV.factionGroupId(village));
    }

    /** Pending warnings of the player (kind, count) that have not reached the attack threshold yet. */
    static List<String[]> warnings(ServerLevel level, ServerPlayer player) throws Exception {
        List<String[]> out = new ArrayList<>();
        long now = level.getGameTime();
        long window = FallConfig.ARSON_WINDOW_SECONDS.get() * 20L;
        String prefix = player.getUUID() + "|";
        Object data = null;
        for (Map.Entry<String, int[]> e : arson.entrySet()) {
            if (!e.getKey().startsWith(prefix) || e.getValue()[0] <= 0 || now - e.getValue()[1] > window) {
                continue;
            }
            String[] parts = e.getKey().split("\\|");
            if (parts.length < 3) {
                continue;
            }
            if (data == null) {
                data = RV.data(level.getServer());
            }
            Object f = RV.faction(data, UUID.fromString(parts[1]));
            if (f == null) {
                continue;
            }
            BlockPos c = RV.center(f);
            out.add(new String[]{RV.name(f), String.valueOf(c.getX()), String.valueOf(c.getZ()),
                    String.valueOf((window - (now - e.getValue()[1])) / 20L), "1", parts[2] + " " + e.getValue()[0]});
        }
        return out;
    }

    /** The village already holds a grudge: the offence is not counted again, the village just stays hostile. */
    private boolean remembered(ServerLevel level, ServerPlayer player, Object village) throws Exception {
        ServerLevel ow = level.getServer().overworld();
        if (Grudges.get(ow).active(player.getUUID(), RV.id(village), ow.getGameTime())) {
            react(level, player, village, true);
            return true;
        }
        return false;
    }

    private void offence(ServerLevel level, ServerPlayer player, Object village, String kind, int threshold) throws Exception {
        if (ownVillage(level, player, village)) {
            return; // arson, TNT, hits in the faction you belong to are no offence
        }
        if (remembered(level, player, village)) {
            return;
        }
        UUID vid = RV.id(village);
        long now = level.getGameTime();
        String key = player.getUUID() + "|" + vid + "|" + kind;
        int[] st = arson.computeIfAbsent(key, k -> new int[]{0, (int) now});
        if (now - st[1] > FallConfig.ARSON_WINDOW_SECONDS.get() * 20L) {
            st[0] = 0;
            st[1] = (int) now;
        }
        st[0]++;
        if (threshold > 1 && st[0] < threshold) {
            react(level, player, village, false);
            player.displayClientMessage(Component.translatable("msg.villagefall.warn_" + kind, RV.name(village), st[0], threshold), true);
            return;
        }
        st[0] = 0;
        st[1] = (int) now;
        LOG.info("[VillageFall] {} commits '{}' against '{}'", player.getName().getString(), kind, RV.name(village));
        player.displayClientMessage(Component.translatable("msg.villagefall.alert_" + kind, RV.name(village)), true);
        attack(level, player, village);
    }

    private void attack(ServerLevel level, ServerPlayer player, Object target) throws Exception {
        UUID vid = RV.id(target);
        react(level, player, target, true);
        ServerLevel ow = level.getServer().overworld();
        Grudges.get(ow).set(player.getUUID(), vid, RV.name(target), RV.center(target),
                ow.getGameTime() + FallConfig.GRUDGE_MINUTES.get() * 1200L);
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

    /**
     * Visible reaction of the village: nearby villagers get angry and run, its soldiers and golems pick the
     * offender as a target (only when {@code hostile}, i.e. the real attack threshold is reached).
     */
    private void react(ServerLevel level, ServerPlayer player, Object village, boolean hostile) throws Exception {
        UUID vid = RV.id(village);
        AABB area = player.getBoundingBox().inflate(hostile ? 40.0D : 18.0D);
        for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, area)) {
            if (e instanceof net.minecraft.world.entity.npc.Villager vill) {
                if (!vid.equals(RV.memberFaction(vill))) {
                    continue;
                }
                level.broadcastEntityEvent(vill, (byte) 13); // angry particles
                vill.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HURT_BY_ENTITY, player);
                if (hostile || vill.tickCount % 40 == 0) {
                    // low-pitched, angry-sounding "no" of the villagers
                    vill.playSound(net.minecraft.sounds.SoundEvents.VILLAGER_NO, 1.2F, hostile ? 0.6F : vill.getVoicePitch());
                }
            } else if (hostile && (e instanceof net.minecraft.world.entity.animal.IronGolem
                    || e.getClass().getName().startsWith("com.talhanation.recruits."))
                    && vid.equals(RV.memberFaction(e)) && e instanceof net.minecraft.world.entity.Mob m) {
                m.setTarget(player);
            }
        }
    }

    private void fail(Throwable t) {
        broken = true;
        LOG.error("[VillageFall] aggression tracking disabled: {}", t.toString());
    }
}
