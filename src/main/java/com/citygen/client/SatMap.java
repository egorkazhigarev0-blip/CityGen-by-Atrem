package com.citygen.client;

import com.citygen.CityGen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * «Спутниковый» снимок местности вокруг игрока: цвета блоков поверхности как на карте Minecraft,
 * отмывка рельефа и глубина воды. Снимается по строкам из уже загруженных клиентом чанков,
 * незагруженные места заштрихованы. Хранит высоты — по ним редактор оценивает участок под квартал.
 */
public final class SatMap {
    @SuppressWarnings("removal")
    public static final ResourceLocation TEX = new ResourceLocation(CityGen.MODID, "sat_map");
    public static final byte WATER = 1, UNLOADED = 2, TREE = 4;

    private static NativeImage img;
    private static DynamicTexture tex;
    private static short[] heights = new short[0];
    private static byte[] flags = new byte[0];
    private static int row = -1;
    private static ResourceKey<Level> dim;
    public static int cx, cz, radius, size;

    private SatMap() {}

    public static boolean ready() { return tex != null && size > 0; }
    public static boolean busy() { return row >= 0; }
    public static float progress() { return row < 0 ? 1f : row / (float) Math.max(1, size); }
    public static boolean covers(int x, int z) { return ready() && x >= cx - radius && x < cx + radius && z >= cz - radius && z < cz + radius; }
    public static boolean sameWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.level.dimension() == dim;
    }

    /** Начать новый снимок с центром (x, z) и радиусом r блоков. */
    public static void start(int x, int z, int r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        cx = x; cz = z; radius = r; size = 2 * r;
        if (img == null || img.getWidth() != size) {
            img = new NativeImage(size, size, true); // старая текстура закрывается при перерегистрации
            tex = new DynamicTexture(img);
            mc.getTextureManager().register(TEX, tex);
        }
        heights = new short[size * size];
        flags = new byte[size * size];
        for (int j = 0; j < size; j++)
            for (int i = 0; i < size; i++) img.setPixelRGBA(i, j, abgr(hatch(i, j)));
        tex.upload();
        dim = mc.level.dimension();
        row = 0;
    }

    /** Снимает следующую порцию строк (вызывается каждый тик, пока открыт редактор). */
    public static void tick() {
        if (row < 0) return;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || level.dimension() != dim) { row = -1; return; }
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int end = Math.min(size, row + Math.max(4, 14000 / size));
        for (; row < end; row++)
            for (int i = 0; i < size; i++) sample(level, i, row, mp);
        tex.upload();
        if (row >= size) row = -1;
    }

    private static int hatch(int i, int j) { return ((i + j) & 7) < 4 ? 0xFF14181D : 0xFF1A1F26; }

    private static void sample(Level level, int i, int j, BlockPos.MutableBlockPos mp) {
        int wx = cx - radius + i, wz = cz - radius + j, idx = j * size + i;
        if (!level.hasChunk(wx >> 4, wz >> 4)) {
            flags[idx] = UNLOADED;
            heights[idx] = Short.MIN_VALUE;
            img.setPixelRGBA(i, j, abgr(hatch(i, j)));
            return;
        }
        int minY = level.getMinBuildHeight();
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
        BlockState st = level.getBlockState(mp.set(wx, y, wz));
        MapColor col = st.getMapColor(level, mp);
        while (col == MapColor.NONE && y > minY) {
            y--;
            st = level.getBlockState(mp.set(wx, y, wz));
            col = st.getMapColor(level, mp);
        }
        byte f = 0;
        float k;
        if (!st.getFluidState().isEmpty()) {
            f |= WATER;
            int depth = 0, yy = y - 1;
            while (yy > minY && depth < 12 && !level.getBlockState(mp.set(wx, yy, wz)).getFluidState().isEmpty()) { yy--; depth++; }
            k = 1.08f - Math.min(depth, 10) * 0.04f;
        } else {
            int hn = j > 0 && (flags[idx - size] & UNLOADED) == 0 ? heights[idx - size] : y;
            int hw = i > 0 && (flags[idx - 1] & UNLOADED) == 0 ? heights[idx - 1] : y;
            int d = (y - hn) + (y - hw); // свет с северо-запада
            k = 1f + Mth.clamp(d, -5, 5) * 0.055f;
            if (st.is(BlockTags.LEAVES)) { f |= TREE; k *= 0.9f; }
        }
        heights[idx] = (short) y;
        flags[idx] = f;
        int c = col.col;
        int r = Mth.clamp((int) (((c >> 16) & 255) * k), 0, 255);
        int g = Mth.clamp((int) (((c >> 8) & 255) * k), 0, 255);
        int b = Mth.clamp((int) ((c & 255) * k), 0, 255);
        img.setPixelRGBA(i, j, abgr(0xFF000000 | (r << 16) | (g << 8) | b));
    }

    private static int abgr(int argb) { return (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16); }

    public static int height(int x, int z) {
        if (!covers(x, z)) return Integer.MIN_VALUE;
        int idx = (z - cz + radius) * size + (x - cx + radius);
        return (flags[idx] & UNLOADED) != 0 ? Integer.MIN_VALUE : heights[idx];
    }

    public static boolean water(int x, int z) {
        if (!covers(x, z)) return false;
        return (flags[(z - cz + radius) * size + (x - cx + radius)] & WATER) != 0;
    }

    /** Оценка участка: перепад высот, доля воды и неизвестной (не снятой) местности. */
    public static final class Stats {
        public int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, samples, water, unknown;
        public int relief() { return max >= min ? max - min : 0; }
        public int waterPct() { return samples + unknown == 0 ? 0 : water * 100 / (samples + unknown); }
        public int unknownPct() { return samples + unknown == 0 ? 100 : unknown * 100 / (samples + unknown); }
    }

    public static Stats stats(int x0, int z0, int x1, int z1) {
        Stats s = new Stats();
        int step = Math.max(1, Math.max(x1 - x0, z1 - z0) / 40);
        for (int x = x0; x <= x1; x += step)
            for (int z = z0; z <= z1; z += step) {
                int h = height(x, z);
                if (h == Integer.MIN_VALUE) { s.unknown++; continue; }
                s.samples++;
                if (water(x, z)) { s.water++; continue; }
                s.min = Math.min(s.min, h); s.max = Math.max(s.max, h);
            }
        return s;
    }
}
