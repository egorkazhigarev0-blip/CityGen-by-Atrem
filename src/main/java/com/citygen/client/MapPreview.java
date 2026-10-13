package com.citygen.client;

import com.citygen.CityGen;
import com.citygen.gen.GenSettings;
import com.citygen.gen.Layout;
import com.citygen.gen.PreviewRenderer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Мини-карта будущей застройки: считается в фоне, затем загружается в текстуру. */
public final class MapPreview {
    @SuppressWarnings("removal")
    public static final ResourceLocation TEX = new ResourceLocation(CityGen.MODID, "map_preview");
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CityGen-map");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private static final AtomicLong TOKEN = new AtomicLong();
    private static DynamicTexture texture;
    public static int texW, texH;
    public static volatile boolean busy;
    public static boolean wanted;
    private static boolean dirty = true;
    private static long changedAt;

    private MapPreview() {}

    public static boolean ready() { return texture != null && texW > 1; }

    public static void markDirty() { dirty = true; changedAt = System.currentTimeMillis(); }

    /** Вызывается каждый тик клиента: перерисовка через 0,35 с после последнего изменения. */
    public static void tick() {
        if (!wanted || !dirty || System.currentTimeMillis() - changedAt < 350) return;
        Layout layout = ClientState.layout;
        if (layout == null) return;
        dirty = false;
        long my = TOKEN.incrementAndGet();
        GenSettings s = ClientState.settings.copy();
        PreviewRenderer.Mode mode = ClientPrefs.get().mapIso ? PreviewRenderer.Mode.ISO : PreviewRenderer.Mode.TOP;
        busy = true;
        EXEC.submit(() -> {
            try {
                PreviewRenderer.Image img = PreviewRenderer.render(layout, s, mode, () -> TOKEN.get() != my);
                if (img == null || TOKEN.get() != my) return;
                Minecraft.getInstance().execute(() -> upload(img, my));
            } catch (Throwable t) {
                CityGen.LOG.error("CityGen: map preview failed", t);
                busy = false;
            }
        });
    }

    private static void upload(PreviewRenderer.Image img, long my) {
        if (TOKEN.get() != my) return;
        NativeImage ni = new NativeImage(img.w, img.h, false);
        for (int y = 0; y < img.h; y++)
            for (int x = 0; x < img.w; x++) {
                int c = img.argb[y * img.w + x];
                int abgr = (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16);
                ni.setPixelRGBA(x, y, abgr);
            }
        texture = new DynamicTexture(ni);
        Minecraft.getInstance().getTextureManager().register(TEX, texture);
        texW = img.w;
        texH = img.h;
        busy = false;
    }
}
