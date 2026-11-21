package com.villagefall;

import net.minecraft.world.phys.AABB;

import java.util.List;

/** Що показує клієнт у режимі запам'ятовування (без клієнтських класів — його пише обробник пакета). */
public final class MemoClientState {
    public static volatile List<AABB> boxes = List.of();
    public static volatile List<VfNet.Mark> marks = List.of();

    private MemoClientState() {
    }

    static void set(List<AABB> b, List<VfNet.Mark> m) {
        boxes = b;
        marks = m;
    }
}
