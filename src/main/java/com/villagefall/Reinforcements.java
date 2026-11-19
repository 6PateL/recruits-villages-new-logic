package com.villagefall;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Коли гравець іде у вимір плоті, села його фракції, відчувши загрозу, шлють йому на підмогу загін — контрольовано:
 * лише пішаки (мечник, щитоносець, лучник, арбалетник), у повній залізній броні, частина з нашою важкою зброєю
 * (базука, вогнемет, картопляна гармата, молотови). Загін належить гравцеві і йде за ним; не частіше ніж раз на
 * кілька хвилин. Мод flesh_spread пускає у вимір лише таких бійців (тег fs_guest) і рекрутів гравця.
 */
public class Reinforcements {
    private static final Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final ResourceLocation FLESH = new ResourceLocation("flesh_spread", "flesh");
    private static final String[] TYPES = {"recruit", "recruit", "recruit_shieldman", "bowman", "crossbowman"};
    private static final String[] HEAVY = {"bazooka", "flame", "potato", "molotov"};
    private final Map<UUID, Long> last = new HashMap<>();

    @SubscribeEvent
    public void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !FLESH.equals(event.getTo().location())
                || !FallConfig.REINFORCE.get() || player.isSpectator()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        long now = level.getServer().overworld().getGameTime();
        Long prev = last.get(player.getUUID());
        if (prev != null && now - prev < FallConfig.REINFORCE_COOLDOWN.get() * 1200L) {
            LOG.info("[VillageFall] reinforcements for {}: not yet, cooldown", player.getName().getString());
            return;
        }
        Object own;
        int villages = 0;
        try {
            RV.init();
            own = RV.factionOfPlayer(level, player);
            if (own != null) {
                UUID ownId = RV.id(own);
                for (Object f : RV.factions(RV.data(level.getServer()))) {
                    if (ownId.equals(RV.id(f)) || RV.player(f)) {
                        villages++;
                    }
                }
            }
        } catch (Throwable t) {
            own = null;
        }
        if (own == null) {
            LOG.info("[VillageFall] reinforcements for {}: no faction, nobody comes", player.getName().getString());
            return;
        }
        last.put(player.getUUID(), now);
        int count = Math.min(FallConfig.REINFORCE_MAX.get(), FallConfig.REINFORCE_PER_VILLAGE.get() * Math.max(1, villages));
        int made = 0;
        for (int i = 0; i < count; i++) {
            if (spawn(level, player, i)) {
                made++;
            }
        }
        if (made > 0) {
            player.sendSystemMessage(Component.literal("Ваші села відчули загрозу і прислали підмогу: " + made + " бійців.")
                    .withStyle(ChatFormatting.GOLD));
        }
        LOG.info("[VillageFall] reinforcements for {} in the flesh dimension: {} soldiers ({} villages)", player.getName().getString(),
                made, villages);
    }

    private boolean spawn(ServerLevel level, ServerPlayer player, int i) {
        ResourceLocation id = new ResourceLocation("recruits", TYPES[i % TYPES.length]);
        if (!ForgeRegistries.ENTITY_TYPES.containsKey(id)) {
            return false;
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(id);
        double ang = level.random.nextDouble() * Math.PI * 2.0D;
        BlockPos at = BlockPos.containing(player.getX() + Math.cos(ang) * 3.0D, player.getY(), player.getZ() + Math.sin(ang) * 3.0D);
        Entity e = type.create(level);
        if (!(e instanceof Mob m)) {
            return false;
        }
        m.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
        m.getPersistentData().putBoolean("fs_guest", true);
        try {
            m.getClass().getMethod("initSpawn").invoke(m);
        } catch (Throwable ignored) {
            // without it the soldier simply has his default kit
        }
        // повна броня
        m.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        m.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        m.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
        m.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        // наша зброя: кожен другий отримує важку
        if (i % 2 == 1) {
            String kind = HEAVY[(i / 2) % HEAVY.length];
            m.getPersistentData().putString("vf_special", kind);
            Arsenal.equip(m, kind);
        }
        try { // належить гравцеві й іде за ним
            Class<?> c = m.getClass();
            c.getMethod("setOwnerUUID", Optional.class).invoke(m, Optional.of(player.getUUID()));
            c.getMethod("setIsOwned", boolean.class).invoke(m, true);
            c.getMethod("setFollowState", int.class).invoke(m, 1);
            c.getMethod("setListen", boolean.class).invoke(m, true);
        } catch (Throwable t) {
            LOG.warn("[VillageFall] reinforcement could not be bound to {}: {}", player.getName().getString(), t.toString());
        }
        m.setPersistenceRequired();
        return level.addFreshEntity(m);
    }
}
