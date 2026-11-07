package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Special weapons for a share of the defenders of every village (counted from the soldiers, not from the whole
 * population) and iron golems for the whole population. Works for own, joined and NPC villages alike.
 */
public class Arsenal {
    private static final String TAG = "vf_special";
    private static final String GOLEM_TAG = "vf_golem";
    private static final String[] KINDS = {"bazooka", "flame", "potato", "molotov", "octo"};
    private boolean broken;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || broken || event.getServer().getTickCount() % 200 != 0
                || !ModList.get().isLoaded("recruitsvillages") || !(FallConfig.ARSENAL.get() || FallConfig.GOLEMS.get())) {
            return;
        }
        try {
            RV.init();
            run(event.getServer());
        } catch (Throwable t) {
            broken = true;
            com.mojang.logging.LogUtils.getLogger().warn("[VillageFall] arsenal disabled: {}", t.toString());
        }
    }

    private void run(MinecraftServer server) throws Exception {
        ServerLevel level = server.overworld();
        Object data = RV.data(server);
        for (Object f : RV.factions(data)) {
            BlockPos center = RV.center(f);
            AABB box = RV.box(f);
            // only villages whose chunks tick: entities placed into merely "loaded" chunks are invisible to queries and get lost
            if (center == null || box == null || !level.hasChunkAt(center) || !level.isPositionEntityTicking(center)) {
                continue;
            }
            AABB area = box.inflate(24.0D, 16.0D, 24.0D);
            AABB wide = box.inflate(80.0D, 32.0D, 80.0D);
            String tagId = id.toString();
            UUID id = RV.id(f);
            List<Mob> soldiers = new ArrayList<>();
            int golems = 0;
            for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, wide)) {
                if (!e.isAlive()) {
                    continue;
                }
                if (e instanceof IronGolem g) {
                    boolean mine = tagId.equals(g.getPersistentData().getString(GOLEM_TAG));
                    if (mine) {
                        golems++;
                        // a golem wandering off is brought back to its village
                        if (g.position().distanceToSqr(center.getX(), center.getY(), center.getZ()) > 40.0D * 40.0D && g.getTarget() == null) {
                            BlockPos back = homeSpot(level, center, box);
                            if (back != null) {
                                g.teleportTo(back.getX() + 0.5D, back.getY(), back.getZ() + 0.5D);
                            }
                        }
                    } else if (area.contains(g.position())) {
                        golems++;
                    }
                } else if (!area.contains(e.position())) {
                    continue;
                } else if (e instanceof Mob m && isRecruitsSoldier(m) && RV.tracked(m.getUUID())) {
                    soldiers.add(m);
                }
            }
            if (FallConfig.ARSENAL.get()) {
                arm(soldiers);
            }
            if (FallConfig.GOLEMS.get()) {
                int pop = RV.recruits(f) + RV.civilians(f);
                int want = Math.min(FallConfig.GOLEM_MAX.get(),
                        Math.max(FallConfig.GOLEM_MIN.get(), (pop * FallConfig.GOLEM_PERCENT.get() + 99) / 100));
                if (golems < want && pop > 0) {
                    int made = 0;
                    for (int k = golems; k < want && k < golems + 4; k++) {
                        if (!spawnGolem(level, center, box, id)) {
                            break;
                        }
                        made++;
                    }
                    com.mojang.logging.LogUtils.getLogger().info("[VillageFall] golems of '{}': {}/{}, spawned {}",
                            RV.name(f), golems, want, made);
                }
            }
        }
    }

    private BlockPos homeSpot(ServerLevel level, BlockPos center, AABB box) {
        for (int i = 0; i < 12; i++) {
            int x = center.getX() + level.random.nextInt(17) - 8;
            int z = center.getZ() + level.random.nextInt(17) - 8;
            BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (level.hasChunkAt(pos) && level.isEmptyBlock(pos) && level.getBlockState(pos.below()).isSolid()) {
                return pos;
            }
        }
        return null;
    }

    @SubscribeEvent
    public void onGolemDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity() instanceof IronGolem g && !g.getPersistentData().getString(GOLEM_TAG).isEmpty()) {
            com.mojang.logging.LogUtils.getLogger().info("[VillageFall] village golem died at {} ({} by {})",
                    g.blockPosition().toShortString(), event.getSource().getMsgId(),
                    event.getSource().getEntity() == null ? "nobody" : event.getSource().getEntity().getName().getString());
        }
    }

    @SubscribeEvent
    public void onGolemLeave(net.minecraftforge.event.entity.EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide && event.getEntity() instanceof IronGolem g
                && !g.getPersistentData().getString(GOLEM_TAG).isEmpty() && g.isAlive() && g.getRemovalReason() != null
                && g.getRemovalReason() != net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_TO_CHUNK
                && g.getRemovalReason() != net.minecraft.world.entity.Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            com.mojang.logging.LogUtils.getLogger().info("[VillageFall] village golem removed at {}: {}",
                    g.blockPosition().toShortString(), g.getRemovalReason());
        }
    }

    private static boolean isRecruitsSoldier(Mob m) {
        String n = m.getClass().getName();
        return n.startsWith("com.talhanation.recruits.") && !m.getType().equals(EntityType.VILLAGER);
    }

    /** Horsemen and anybody currently riding get no backpack (and no heavy weapons that need two free hands). */
    private static boolean rider(Mob m) {
        String n = m.getClass().getSimpleName().toLowerCase();
        if (m.isPassenger() || n.contains("horse") || n.contains("nomad") || n.contains("messenger") || n.contains("scout")) {
            return true;
        }
        try {
            Object mount = m.getClass().getMethod("getMountUUID").invoke(m);
            return mount instanceof java.util.Optional<?> o ? o.isPresent() : mount != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Only plain infantry: recruit, shieldman, bowman, crossbowman. */
    private static boolean footSoldier(Mob m) {
        String n = m.getClass().getSimpleName();
        return n.equals("RecruitEntity") || n.equals("RecruitShieldmanEntity") || n.equals("BowmanEntity") || n.equals("CrossBowmanEntity");
    }

    private static int share(String kind) {
        return switch (kind) {
            case "bazooka" -> FallConfig.SHARE_BAZOOKA.get();
            case "flame" -> FallConfig.SHARE_FLAME.get();
            case "potato" -> FallConfig.SHARE_POTATO.get();
            case "molotov" -> FallConfig.SHARE_MOLOTOV.get();
            default -> FallConfig.SHARE_OCTO.get();
        };
    }

    private static Item item(String ns, String path) {
        ResourceLocation rl = new ResourceLocation(ns, path);
        return ForgeRegistries.ITEMS.containsKey(rl) ? ForgeRegistries.ITEMS.getValue(rl) : null;
    }

    private static Item itemOf(String kind) {
        return switch (kind) {
            case "bazooka" -> item("hostile_civilization", "bazooka");
            case "flame" -> item("fromanotherworld", "flamethrower_mk_1");
            case "potato" -> item("create", "potato_cannon");
            case "molotov" -> item("hostile_civilization", "molotov");
            default -> {
                Item i = item("tentaworks", "netherite_tentacle_rig");
                yield i != null ? i : item("tentaworks", "copper_tentacle_rig");
            }
        };
    }

    private void arm(List<Mob> soldiers) {
        int defenders = soldiers.size();
        if (defenders < FallConfig.ARSENAL_MIN_DEFENDERS.get()) {
            return;
        }
        // keep what is already handed out, count it per kind
        for (Mob m : soldiers) {
            String k = m.getPersistentData().getString(TAG);
            if ("octo".equals(k) && rider(m)) {
                // a rider must not wear the backpack: take it off
                if (m.getItemBySlot(EquipmentSlot.CHEST).getItem() == itemOf("octo")) {
                    m.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                }
                m.getPersistentData().remove(TAG);
            } else if (!k.isEmpty()) {
                equip(m, k); // gives it back when lost
            }
        }
        // riders that got the backpack some other way (or before this rule): off it goes
        for (Mob m : soldiers) {
            ItemStack chest = m.getItemBySlot(EquipmentSlot.CHEST);
            if (rider(m) && !chest.isEmpty()
                    && "tentaworks".equals(ForgeRegistries.ITEMS.getKey(chest.getItem()).getNamespace())) {
                m.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            }
        }
        for (String kind : KINDS) {
            Item it = itemOf(kind);
            int pct = share(kind);
            if (it == null || pct <= 0) {
                continue;
            }
            int want = (defenders * pct + 99) / 100;
            int have = 0;
            for (Mob m : soldiers) {
                if (kind.equals(m.getPersistentData().getString(TAG))) {
                    have++;
                }
            }
            for (Mob m : soldiers) {
                if (have >= want) {
                    break;
                }
                CompoundTag d = m.getPersistentData();
                if (!d.getString(TAG).isEmpty() || rider(m)) {
                    continue;
                }
                if ("octo".equals(kind) && !footSoldier(m)) {
                    continue;
                }
                d.putString(TAG, kind);
                equip(m, kind);
                have++;
            }
        }
    }

    private static boolean holds(Mob m, Item it) {
        return m.getMainHandItem().is(it) || m.getOffhandItem().is(it);
    }

    private void equip(Mob m, String kind) {
        Item it = itemOf(kind);
        if (it == null) {
            return;
        }
        Container inv = null;
        try {
            inv = (Container) m.getClass().getMethod("getInventory").invoke(m);
        } catch (Throwable ignored) {
            // no inventory: ammo can't be given
        }
        switch (kind) {
            case "potato" -> {
                if (!m.getMainHandItem().is(it)) {
                    stash(m, inv);
                    m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(it));
                }
                if (inv instanceof SimpleContainer sc && !has(inv, Items.POTATO)) {
                    sc.addItem(new ItemStack(Items.POTATO, 64));
                }
            }
            case "flame" -> {
                if (!m.getMainHandItem().is(it)) {
                    stash(m, inv);
                    m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(it));
                }
            }
            case "bazooka" -> {
                if (!holds(m, it)) {
                    if (m.getOffhandItem().isEmpty()) {
                        m.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(it));
                    } else if (inv instanceof SimpleContainer sc && !has(inv, it)) {
                        sc.addItem(new ItemStack(it));
                    }
                }
            }
            case "molotov" -> {
                if (!holds(m, it) && inv instanceof SimpleContainer sc && !has(inv, it)) {
                    sc.addItem(new ItemStack(it, 16));
                }
            }
            default -> {
                if (!rider(m) && footSoldier(m) && m.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
                    ItemStack rig = new ItemStack(it);
                    rig.getOrCreateTag().putFloat("Air", 900.0F);
                    m.setItemSlot(EquipmentSlot.CHEST, rig);
                }
            }
        }
    }

    private static boolean has(Container inv, Item it) {
        if (inv == null) {
            return false;
        }
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(it)) {
                return true;
            }
        }
        return false;
    }

    /** The old main-hand weapon goes into the backpack instead of vanishing. */
    private static void stash(Mob m, Container inv) {
        ItemStack old = m.getMainHandItem();
        if (!old.isEmpty() && inv instanceof SimpleContainer sc) {
            sc.addItem(old.copy());
        }
    }

    private boolean spawnGolem(ServerLevel level, BlockPos center, AABB box, UUID fid) {
        for (int i = 0; i < 24; i++) {
            int x = i < 16 ? (int) (box.minX + level.random.nextDouble() * (box.maxX - box.minX)) : center.getX() + level.random.nextInt(21) - 10;
            int z = i < 16 ? (int) (box.minZ + level.random.nextDouble() * (box.maxZ - box.minZ)) : center.getZ() + level.random.nextInt(21) - 10;
            if (!level.hasChunkAt(new BlockPos(x, center.getY(), z)) || !level.isPositionEntityTicking(new BlockPos(x, center.getY(), z))) {
                continue;
            }
            BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (Math.abs(pos.getY() - center.getY()) > 16 || !level.getBlockState(pos.below()).isSolid()
                    || !level.isEmptyBlock(pos) || !level.isEmptyBlock(pos.above())) {
                continue;
            }
            IronGolem g = new IronGolem(EntityType.IRON_GOLEM, level);
            g.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, level.random.nextFloat() * 360.0F, 0.0F);
            g.setPlayerCreated(false);
            g.setPersistenceRequired();
            g.restrictTo(center, 32);
            g.getPersistentData().putString(GOLEM_TAG, fid.toString());
            if (level.addFreshEntity(g)) {
                return true;
            }
        }
        return false;
    }
}
