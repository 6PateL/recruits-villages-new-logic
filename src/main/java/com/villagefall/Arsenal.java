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
            if (center == null || box == null || !level.hasChunkAt(center)) {
                continue;
            }
            AABB area = box.inflate(8.0D, 16.0D, 8.0D);
            UUID id = RV.id(f);
            List<Mob> soldiers = new ArrayList<>();
            int golems = 0;
            for (net.minecraft.world.entity.LivingEntity e : level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, area)) {
                if (!e.isAlive()) {
                    continue;
                }
                if (e instanceof IronGolem) {
                    golems++;
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
                    spawnGolem(level, center, box);
                }
            }
        }
    }

    private static boolean isRecruitsSoldier(Mob m) {
        String n = m.getClass().getName();
        return n.startsWith("com.talhanation.recruits.") && !m.getType().equals(EntityType.VILLAGER);
    }

    /** Horsemen and anybody currently riding get no backpack (and no heavy weapons that need two free hands). */
    private static boolean rider(Mob m) {
        if (m.isPassenger() || m.getClass().getSimpleName().toLowerCase().contains("horse")) {
            return true;
        }
        try {
            Object mount = m.getClass().getMethod("getMountUUID").invoke(m);
            return mount instanceof java.util.Optional<?> o ? o.isPresent() : mount != null;
        } catch (Throwable t) {
            return false;
        }
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
            if (!k.isEmpty()) {
                equip(m, k); // gives it back when lost
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
                if (!rider(m) && m.getItemBySlot(EquipmentSlot.CHEST).isEmpty()) {
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

    private void spawnGolem(ServerLevel level, BlockPos center, AABB box) {
        for (int i = 0; i < 8; i++) {
            int x = (int) (box.minX + level.random.nextDouble() * (box.maxX - box.minX));
            int z = (int) (box.minZ + level.random.nextDouble() * (box.maxZ - box.minZ));
            if (!level.hasChunkAt(new BlockPos(x, center.getY(), z))) {
                continue;
            }
            BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (Math.abs(pos.getY() - center.getY()) > 12 || !level.getBlockState(pos.below()).isSolid()
                    || !level.getBlockState(pos).isAir()) {
                continue;
            }
            IronGolem g = EntityType.IRON_GOLEM.spawn(level, pos, MobSpawnType.MOB_SUMMONED);
            if (g != null) {
                g.setPlayerCreated(false);
                g.setPersistenceRequired();
            }
            return;
        }
    }
}
