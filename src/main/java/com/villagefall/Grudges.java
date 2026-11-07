package com.villagefall;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Which villages hold a grudge against which player. While a grudge lasts further offences in that village are not
 * counted again (the village already considers the player an enemy); after the time runs out the village forgets.
 */
public class Grudges extends SavedData {
    record Entry(String name, int x, int z, long until) {}

    private final Map<UUID, Map<UUID, Entry>> data = new HashMap<>();

    static Grudges get(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(Grudges::load, Grudges::new, "villagefall_grudges");
    }

    static Grudges load(CompoundTag tag) {
        Grudges g = new Grudges();
        ListTag list = tag.getList("g", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag c = list.getCompound(i);
            g.data.computeIfAbsent(c.getUUID("p"), k -> new HashMap<>()).put(c.getUUID("v"),
                    new Entry(c.getString("n"), c.getInt("x"), c.getInt("z"), c.getLong("u")));
        }
        return g;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        data.forEach((p, m) -> m.forEach((v, e) -> {
            CompoundTag c = new CompoundTag();
            c.putUUID("p", p);
            c.putUUID("v", v);
            c.putString("n", e.name());
            c.putInt("x", e.x());
            c.putInt("z", e.z());
            c.putLong("u", e.until());
            list.add(c);
        }));
        tag.put("g", list);
        return tag;
    }

    boolean active(UUID player, UUID village, long now) {
        Map<UUID, Entry> m = data.get(player);
        Entry e = m == null ? null : m.get(village);
        if (e == null) {
            return false;
        }
        if (e.until() <= now) {
            m.remove(village);
            setDirty();
            return false;
        }
        return true;
    }

    /** Villages that are hostile to the player right now. */
    java.util.Set<UUID> hostile(UUID player, long now) {
        java.util.Set<UUID> out = new java.util.HashSet<>();
        Map<UUID, Entry> m = data.get(player);
        if (m != null) {
            for (Map.Entry<UUID, Entry> e : m.entrySet()) {
                if (e.getValue().until() > now) {
                    out.add(e.getKey());
                }
            }
        }
        return out;
    }

    void set(UUID player, UUID village, String name, BlockPos center, long until) {
        data.computeIfAbsent(player, k -> new HashMap<>())
                .put(village, new Entry(name, center.getX(), center.getZ(), until));
        setDirty();
    }

    /**
     * Lines for the baton info screen: {name, x, z, secondsLeft, state}; state "0" = the village is hostile and will
     * forget in secondsLeft, "1" = a warning (count in the name suffix is not needed, secondsLeft = window left).
     */
    public static List<String[]> report(ServerPlayer player) {
        List<String[]> out = new ArrayList<>();
        try {
            ServerLevel level = player.server.overworld();
            long now = level.getGameTime();
            Map<UUID, Entry> m = get(level).data.get(player.getUUID());
            if (m != null) {
                for (Entry e : new ArrayList<>(m.values())) {
                    if (e.until() > now) {
                        out.add(new String[]{e.name(), String.valueOf(e.x()), String.valueOf(e.z()),
                                String.valueOf((e.until() - now) / 20L), "0"});
                    }
                }
            }
            for (String[] w : PlayerAggression.warnings(level, player)) {
                out.add(w);
            }
        } catch (Throwable t) {
            // best effort
        }
        return out;
    }
}
