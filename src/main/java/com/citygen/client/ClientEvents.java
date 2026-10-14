package com.citygen.client;

import com.citygen.CityGen;
import com.citygen.gen.GenSettings;
import com.citygen.gen.GenSettings.Quarter;
import com.citygen.gen.Layout;
import com.citygen.gen.Plan;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = CityGen.MODID, value = Dist.CLIENT)
public final class ClientEvents {
    static final float[][] COLORS = {
            {0.2f, 0.8f, 1f}, {1f, 0.75f, 0.2f}, {0.5f, 1f, 0.4f}, {1f, 0.4f, 0.6f},
            {0.7f, 0.5f, 1f}, {1f, 1f, 0.4f}, {0.3f, 1f, 0.8f}, {1f, 0.55f, 0.3f}};

    private static String hudToast = "";
    private static long hudToastAt;

    private ClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        MapPreview.tick();
        ClientState.tick();
        if (mc.player == null) { ClientState.worldEdit = false; return; }
        while (ClientSetup.OPEN_EDITOR.consumeClick()) {
            if (mc.screen == null) {
                if (ClientState.worldEdit) { ClientState.worldEdit = false; mc.setScreen(new CityEditorScreen(new EditorScreen())); }
                else mc.setScreen(new EditorScreen());
            }
        }
    }

    // =====================================================================
    // Правка кварталов в мире
    // =====================================================================
    private static void toast(String s) { hudToast = s; hudToastAt = System.currentTimeMillis(); }

    @SubscribeEvent
    public static void onKey(InputEvent.Key e) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientState.worldEdit || mc.screen != null || mc.player == null) return;
        if (e.getAction() == GLFW.GLFW_RELEASE) return;
        GenSettings s = ClientState.settings;
        if (s.quarters.isEmpty()) { ClientState.worldEdit = false; return; }
        if (ClientState.selected < 0 || ClientState.selected >= s.quarters.size()) ClientState.selected = 0;
        Quarter q = s.quarters.get(ClientState.selected);
        boolean shift = (e.getModifiers() & GLFW.GLFW_MOD_SHIFT) != 0;
        int step = shift ? 8 : 1;
        Direction f = mc.player.getDirection(), r = f.getClockWise();
        switch (e.getKey()) {
            case GLFW.GLFW_KEY_UP -> move(q, f.getStepX() * step, f.getStepZ() * step);
            case GLFW.GLFW_KEY_DOWN -> move(q, -f.getStepX() * step, -f.getStepZ() * step);
            case GLFW.GLFW_KEY_RIGHT -> move(q, r.getStepX() * step, r.getStepZ() * step);
            case GLFW.GLFW_KEY_LEFT -> move(q, -r.getStepX() * step, -r.getStepZ() * step);
            case GLFW.GLFW_KEY_PAGE_UP -> { if (e.getAction() == GLFW.GLFW_PRESS || shift) { q.raise = Math.min(16, q.raise + 1); toast("Основание: " + raise(q)); ClientState.requestRecompute(); } }
            case GLFW.GLFW_KEY_PAGE_DOWN -> { if (e.getAction() == GLFW.GLFW_PRESS || shift) { q.raise = Math.max(-16, q.raise - 1); toast("Основание: " + raise(q)); ClientState.requestRecompute(); } }
            case GLFW.GLFW_KEY_R -> {
                if (e.getAction() != GLFW.GLFW_PRESS) return;
                q.facing = GenSettings.QFacing.values()[(q.facing.ordinal() + 1) % GenSettings.QFacing.values().length];
                toast("Фасады: " + q.facing.label());
                ClientState.requestRecompute();
            }
            case GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_TAB -> {
                if (e.getAction() != GLFW.GLFW_PRESS) return;
                ClientState.selected = Math.floorMod(ClientState.selected + (shift ? -1 : 1), s.quarters.size());
                toast("Выбран: " + s.quarters.get(ClientState.selected).name);
            }
            case GLFW.GLFW_KEY_V -> {
                if (e.getAction() != GLFW.GLFW_PRESS) return;
                HitResult hit = mc.player.pick(160, 1f, false);
                if (hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos p = ((BlockHitResult) hit).getBlockPos();
                    move(q, p.getX() - (q.x0 + q.w() / 2), p.getZ() - (q.z0 + q.d() / 2));
                    toast("Квартал перенесён к точке взгляда");
                } else toast("Посмотрите на землю, куда перенести квартал");
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (e.getAction() != GLFW.GLFW_PRESS) return;
                ClientState.worldEdit = false;
                mc.setScreen(new CityEditorScreen(new EditorScreen()));
            }
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (e.getAction() != GLFW.GLFW_PRESS) return;
                ClientState.worldEdit = false;
                ClientState.save();
                ClientState.recompute();
                toast("");
            }
            default -> {}
        }
    }

    private static String raise(Quarter q) { return q.raise == 0 ? "по рельефу" : (q.raise > 0 ? "+" : "") + q.raise + " м"; }

    private static void move(Quarter q, int dx, int dz) {
        if (dx == 0 && dz == 0) return;
        q.moveBy(dx, dz);
        ClientState.requestRecompute();
    }

    @SubscribeEvent
    public static void onHud(RenderGuiEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (!ClientState.worldEdit || mc.screen != null || mc.options.hideGui) return;
        Quarter q = ClientState.selectedQuarter();
        if (q == null) return;
        GuiGraphics g = e.getGuiGraphics();
        int w = mc.getWindow().getGuiScaledWidth();
        String title = "Правка в мире: «" + q.name + "»  " + q.w() + "×" + q.d() + " м";
        String keys1 = "↑↓←→ — сдвиг (Shift ×8)   PgUp/PgDn — выше/ниже   R — фасады: " + q.facing.label();
        String keys2 = "V — к точке взгляда   N — следующий квартал   Enter или G — редактор   Backspace — выйти";
        int pw = Math.max(Ui.width(title), Math.max(Ui.smallWidth(keys1), Ui.smallWidth(keys2))) + 24;
        int x = (w - pw) / 2, y = 6;
        Ui.card(g, x, y, pw, 42, 4, 0xE0101820, Ui.alpha(Ui.accent(), 0.8f));
        Ui.round(g, x + 6, y + 6, 4, 10, 2, q.kind.color);
        Ui.text(g, title, x + 14, y + 7, Ui.TEXT);
        Ui.small(g, keys1, x + 14, y + 20, Ui.SUB);
        Ui.small(g, keys2, x + 14, y + 29, Ui.SUB);
        String c = CityEditorScreen.conflict(ClientState.selected);
        String t = c != null ? "§c⚠ Квартал " + c : System.currentTimeMillis() - hudToastAt < 2500 ? hudToast : "";
        if (!t.isEmpty()) {
            int tw = Ui.width(t) + 12;
            Ui.card(g, (w - tw) / 2, y + 46, tw, 14, 3, 0xD0101820, Ui.BORDER);
            Ui.text(g, t, (w - tw) / 2 + 6, y + 49, Ui.WARN);
        }
    }

    // =====================================================================
    // Превью в мире
    // =====================================================================
    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Layout layout = ClientState.layout;
        if (!ClientState.previewActive || layout == null) return;
        Minecraft mc = Minecraft.getInstance();
        PoseStack ps = e.getPoseStack();
        Camera camera = e.getCamera();
        Vec3 cam = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        GenSettings s = ClientState.settings;
        boolean city = s.arrangement == GenSettings.Arrangement.CITY;
        ps.pushPose();
        ps.translate(-cam.x, -cam.y, -cam.z);

        // кварталы города: подсветка выбранного и контуры остальных
        if (city && ClientState.worldEdit) {
            VertexConsumer fill = buffers.getBuffer(RenderType.debugFilledBox());
            for (int i = 0; i < s.quarters.size(); i++) {
                if (i != ClientState.selected) continue;
                Quarter q = s.quarters.get(i);
                int y = groundOf(layout, i, q);
                int col = q.kind.color;
                LevelRenderer.addChainedFilledBoxVertices(ps, fill, q.x0, y + 1.02, q.z0, q.x1 + 1, y + 1.08, q.z1 + 1,
                        ((col >> 16) & 255) / 255f, ((col >> 8) & 255) / 255f, (col & 255) / 255f, 0.22f);
            }
            buffers.endBatch(RenderType.debugFilledBox());
        }

        if (city && ClientState.worldEdit) {
            // в режиме правки контуры кварталов видны сквозь деревья и рельеф
            VertexConsumer sv = buffers.getBuffer(SeeThrough.LINES);
            for (int i = 0; i < s.quarters.size(); i++) {
                Quarter q = s.quarters.get(i);
                int y = groundOf(layout, i, q);
                boolean selected = i == ClientState.selected;
                int col = selected ? 0xFFFFFFFF : q.kind.color;
                LevelRenderer.renderLineBox(ps, sv, new AABB(q.x0, y + 1, q.z0, q.x1 + 1, y + (selected ? 3 : 1.5), q.z1 + 1),
                        ((col >> 16) & 255) / 255f, ((col >> 8) & 255) / 255f, (col & 255) / 255f, selected ? 0.9f : 0.55f);
            }
            buffers.endBatch(SeeThrough.LINES);
        }
        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        if (city) {
            for (int i = 0; i < s.quarters.size(); i++) {
                Quarter q = s.quarters.get(i);
                int y = groundOf(layout, i, q);
                boolean selected = ClientState.worldEdit && i == ClientState.selected;
                int col = selected ? 0xFFFFFFFF : q.kind.color;
                float r = ((col >> 16) & 255) / 255f, g = ((col >> 8) & 255) / 255f, b = (col & 255) / 255f;
                double h = selected ? 8 : 4;
                LevelRenderer.renderLineBox(ps, vc, new AABB(q.x0, y + 1, q.z0, q.x1 + 1, y + 1 + h, q.z1 + 1), r, g, b, 1f);
                if (selected) {
                    int ring = CityEditorScreen.ring(q);
                    if (ring > 0)
                        LevelRenderer.renderLineBox(ps, vc, new AABB(q.x0 - ring, y + 1, q.z0 - ring, q.x1 + 1 + ring, y + 1.3, q.z1 + 1 + ring), 0.6f, 0.6f, 0.65f, 1f);
                }
            }
        }
        for (Plan p : layout.plans) {
            float[] c = COLORS[p.index % COLORS.length];
            if (city && p.quarter >= 0 && p.quarter < s.quarters.size()) {
                int col = p.underConstruction ? 0xFFFFC23D : s.quarters.get(p.quarter).kind.color;
                c = new float[]{((col >> 16) & 255) / 255f, ((col >> 8) & 255) / 255f, (col & 255) / 255f};
            }
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
        if (!city || !ClientState.worldEdit) {
            for (Layout.Strip st : layout.strips) {
                AABB box = new AABB(st.minX, st.y + 1, st.minZ, st.maxX + 1, st.y + 1.05, st.maxZ + 1);
                float gr = st.kind == Layout.ROAD ? 0.45f : 0.8f;
                LevelRenderer.renderLineBox(ps, vc, box, gr, gr, gr, 0.8f);
            }
        }
        ps.popPose();
        buffers.endBatch(RenderType.lines());

        // подписи кварталов над землёй
        if (city) {
            Font font = mc.font;
            for (int i = 0; i < s.quarters.size(); i++) {
                Quarter q = s.quarters.get(i);
                double x = (q.x0 + q.x1 + 1) / 2.0, z = (q.z0 + q.z1 + 1) / 2.0;
                double y = groundOf(layout, i, q) + 4.5;
                double dist = cam.distanceTo(new Vec3(x, y, z));
                if (dist > 260) continue;
                boolean selected = ClientState.worldEdit && i == ClientState.selected;
                label(ps, buffers, font, camera, x - cam.x, y - cam.y, z - cam.z, (selected ? "▶ " : "") + q.name, q.w() + "×" + q.d() + " м • " + q.kind.label(),
                        selected ? 0xFFFFFFFF : q.kind.color, (float) Math.max(1.0, dist / 9.0));
            }
            buffers.endBatch();
        }
    }

    private static int groundOf(Layout layout, int i, Quarter q) {
        return i < layout.quarterGround.length ? layout.quarterGround[i] : layout.streetY;
    }

    private static void label(PoseStack ps, MultiBufferSource buffers, Font font, Camera camera, double x, double y, double z,
                              String title, String sub, int color, float scale) {
        ps.pushPose();
        ps.translate(x, y, z);
        ps.mulPose(camera.rotation());
        float k = 0.025f * scale;
        ps.scale(-k, -k, k);
        Matrix4f m = ps.last().pose();
        Component t = Ui.c(title), st = Ui.c(sub);
        float tw = font.width(t), sw = font.width(st);
        font.drawInBatch(t, -tw / 2f, -10, color, false, m, buffers, Font.DisplayMode.SEE_THROUGH, 0x90101418, 0xF000F0);
        font.drawInBatch(st, -sw / 2f, 1, 0xFFC8D0DA, false, m, buffers, Font.DisplayMode.SEE_THROUGH, 0x90101418, 0xF000F0);
        ps.popPose();
    }

}
