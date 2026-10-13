package com.citygen.client;

import com.citygen.CityGen;
import com.citygen.gen.Layout;
import com.citygen.gen.Plan;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = CityGen.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    static final float[][] COLORS = {
            {0.2f, 0.8f, 1f}, {1f, 0.75f, 0.2f}, {0.5f, 1f, 0.4f}, {1f, 0.4f, 0.6f},
            {0.7f, 0.5f, 1f}, {1f, 1f, 0.4f}, {0.3f, 1f, 0.8f}, {1f, 0.55f, 0.3f}};

    private ClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        MapPreview.tick();
        if (mc.player == null) return;
        while (ClientSetup.OPEN_EDITOR.consumeClick()) {
            if (mc.screen == null) mc.setScreen(new EditorScreen());
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Layout layout = ClientState.layout;
        if (!ClientState.previewActive || layout == null) return;
        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = e.getPoseStack();
        Vec3 cam = e.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);
        for (Plan p : layout.plans) {
            float[] c = COLORS[p.index % COLORS.length];
            int top = p.baseY + p.estimatedHeight();
            LevelRenderer.renderLineBox(ps, vc, new AABB(p.minX, p.baseY + 1, p.minZ, p.maxX + 1, top, p.maxZ + 1),
                    c[0], c[1], c[2], 1f);
            double roof = p.baseY + p.roofLevel() + 1;
            LevelRenderer.renderLineBox(ps, vc, new AABB(p.minX, roof, p.minZ, p.maxX + 1, roof, p.maxZ + 1),
                    c[0], c[1], c[2], 0.6f);
            // белая рамка — сторона главного фасада (вход)
            BlockPos a = p.toWorld(0, 1, 0), b = p.toWorld(p.w - 1, 1, 0);
            AABB front = new AABB(Math.min(a.getX(), b.getX()), a.getY(), Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()) + 1, a.getY() + 3, Math.max(a.getZ(), b.getZ()) + 1);
            LevelRenderer.renderLineBox(ps, vc, front.inflate(0.05), 1f, 1f, 1f, 1f);
        }
        for (com.citygen.gen.LotPlan lp : layout.lots) {
            int col = lp.kind.color;
            LevelRenderer.renderLineBox(ps, vc, new AABB(lp.minX, lp.baseY + 1, lp.minZ, lp.maxX + 1, lp.baseY + 2, lp.maxZ + 1),
                    ((col >> 16) & 255) / 255f, ((col >> 8) & 255) / 255f, (col & 255) / 255f, 1f);
        }
        for (Layout.Strip s : layout.strips) {
            BlockPos a = layout.site(s.u0, s.v0, layout.streetY), b = layout.site(s.u1, s.v1, layout.streetY);
            AABB box = new AABB(Math.min(a.getX(), b.getX()), a.getY() + 1, Math.min(a.getZ(), b.getZ()),
                    Math.max(a.getX(), b.getX()) + 1, a.getY() + 1.05, Math.max(a.getZ(), b.getZ()) + 1);
            float g = s.kind == Layout.ROAD ? 0.45f : 0.8f;
            LevelRenderer.renderLineBox(ps, vc, box, g, g, g, 0.8f);
        }
        ps.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
