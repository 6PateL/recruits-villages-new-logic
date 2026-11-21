package com.villagefall;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Мережа Village Fall: межі власних поселень і креслення ремонту для жезла в режимі запам'ятовування. */
public final class VfNet {
    private static final String V = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(VillageFall.MODID, "main"), () -> V, s -> true, s -> true);

    private VfNet() {
    }

    static void register() {
        CHANNEL.registerMessage(0, MemoView.class, MemoView::encode, MemoView::decode, MemoView::handle);
    }

    public record Mark(long pos, int state) {
    }

    public record MemoView(List<AABB> boxes, List<Mark> marks) {
        static void encode(MemoView m, FriendlyByteBuf b) {
            b.writeVarInt(m.boxes.size());
            for (AABB a : m.boxes) {
                b.writeDouble(a.minX);
                b.writeDouble(a.minY);
                b.writeDouble(a.minZ);
                b.writeDouble(a.maxX);
                b.writeDouble(a.maxY);
                b.writeDouble(a.maxZ);
            }
            b.writeVarInt(m.marks.size());
            for (Mark k : m.marks) {
                b.writeLong(k.pos);
                b.writeVarInt(k.state);
            }
        }

        static MemoView decode(FriendlyByteBuf b) {
            int n = b.readVarInt();
            List<AABB> boxes = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                boxes.add(new AABB(b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble(), b.readDouble()));
            }
            int k = b.readVarInt();
            List<Mark> marks = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                marks.add(new Mark(b.readLong(), b.readVarInt()));
            }
            return new MemoView(boxes, marks);
        }

        static void handle(MemoView m, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> MemoClientState.set(m.boxes, m.marks));
            ctx.get().setPacketHandled(true);
        }
    }
}
