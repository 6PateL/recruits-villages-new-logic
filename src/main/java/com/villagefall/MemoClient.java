package com.villagefall;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Жезл у режимі «запам'ятати блок»: межі власних поселень (золоті рамки), зелені проєкції позначених блоків і
 * біла проєкція там, куди стане блок з лівої руки.
 */
@Mod.EventBusSubscriber(modid = VillageFall.MODID, value = Dist.CLIENT)
public final class MemoClient {
    private MemoClient() {
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !Repairs.memorizeBaton(mc.player.getMainHandItem())) {
            return;
        }
        PoseStack pose = e.getPoseStack();
        Vec3 cam = e.getCamera().getPosition();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        VertexConsumer lines = buf.getBuffer(RenderType.lines());
        for (AABB box : MemoClientState.boxes) {
            // межа поселення: рамка від землі гравця на висоту 12 блоків, щоб її було видно
            double y0 = Math.max(box.minY, mc.player.getY() - 4);
            AABB shown = new AABB(box.minX, y0, box.minZ, box.maxX, Math.min(box.maxY, y0 + 12), box.maxZ);
            LevelRenderer.renderLineBox(pose, lines, shown, 1.0F, 0.8F, 0.2F, 0.9F);
        }
        for (VfNet.Mark m : MemoClientState.marks) {
            BlockPos p = BlockPos.of(m.pos());
            BlockState want = net.minecraft.world.level.block.Block.stateById(m.state());
            boolean done = mc.level != null && mc.level.getBlockState(p).equals(want);
            AABB a = new AABB(p).deflate(0.02);
            LevelRenderer.renderLineBox(pose, lines, a, 0.2F, 1.0F, 0.3F, done ? 0.35F : 1.0F);
        }
        // попередній перегляд: куди піде позначка
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK
                && mc.player.getOffhandItem().getItem() instanceof BlockItem bi) {
            BlockHitResult hit = (BlockHitResult) mc.hitResult;
            BlockState at = mc.level.getBlockState(hit.getBlockPos());
            BlockPos p = at.is(bi.getBlock()) || at.canBeReplaced() ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
            LevelRenderer.renderLineBox(pose, lines, new AABB(p).deflate(0.04), 1.0F, 1.0F, 1.0F, 0.8F);
        }
        buf.endBatch(RenderType.lines());
        // заливка ще не відновлених позначок — напівпрозорий зелений
        VertexConsumer fill = buf.getBuffer(RenderType.debugFilledBox());
        for (VfNet.Mark m : MemoClientState.marks) {
            BlockPos p = BlockPos.of(m.pos());
            BlockState want = net.minecraft.world.level.block.Block.stateById(m.state());
            if (mc.level != null && mc.level.getBlockState(p).equals(want)) {
                continue;
            }
            LevelRenderer.addChainedFilledBoxVertices(pose, fill, p.getX() + 0.05, p.getY() + 0.05, p.getZ() + 0.05,
                    p.getX() + 0.95, p.getY() + 0.95, p.getZ() + 0.95, 0.2F, 1.0F, 0.3F, 0.3F);
        }
        buf.endBatch(RenderType.debugFilledBox());
        pose.popPose();
    }
}
