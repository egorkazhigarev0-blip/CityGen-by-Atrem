package com.citygen.gen;

import com.citygen.server.OpList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

import java.util.function.BooleanSupplier;

/**
 * «Вид с высоты птичьего полёта»: генерирует здания без интерьеров и рисует карту
 * (сверху с отмывкой рельефа или в изометрии с z-буфером). Работает в фоновом потоке.
 */
public final class PreviewRenderer {
    public enum Mode { TOP, ISO }

    public static final class Image {
        public final int w, h;
        public final int[] argb;
        Image(int w, int h) { this.w = w; this.h = h; this.argb = new int[w * h]; }
    }

    private static final int BG = 0xFF151B23;

    private PreviewRenderer() {}

    /** Воксели: u (вправо), v (вперёд), y и цвет. */
    private static final class Vox {
        final IntArrayList u = new IntArrayList(), v = new IntArrayList(), y = new IntArrayList(), c = new IntArrayList();
        int uMin = Integer.MAX_VALUE, uMax = Integer.MIN_VALUE, vMin = Integer.MAX_VALUE, vMax = Integer.MIN_VALUE,
                yMin = Integer.MAX_VALUE, yMax = Integer.MIN_VALUE;
        void add(int uu, int vv, int yy, int col) {
            u.add(uu); v.add(vv); y.add(yy); c.add(col);
            uMin = Math.min(uMin, uu); uMax = Math.max(uMax, uu); vMin = Math.min(vMin, vv); vMax = Math.max(vMax, vv);
            yMin = Math.min(yMin, yy); yMax = Math.max(yMax, yy);
        }
        int size() { return u.size(); }
    }

    public static Image render(Layout L, GenSettings src, Mode mode, BooleanSupplier cancelled) {
        GenSettings s = src.copy();
        s.interior = GenSettings.Interior.NONE;
        Vox vx = new Vox();
        int ax = L.anchor.getX(), az = L.anchor.getZ();
        int rx = L.right.getStepX(), rz = L.right.getStepZ(), lx = L.look.getStepX(), lz = L.look.getStepZ();

        OpList clears = new OpList(), founds = new OpList(), sets = new OpList();
        StreetGenerator.addOps(L, s, clears, founds, sets);
        LotGenerator.addOps(L, s, clears, founds, sets);
        for (int i = 0; i < sets.size(); i++) {
            BlockState st = sets.states.get(i);
            if (st.isAir()) continue;
            long p = sets.pos.getLong(i);
            int dx = BlockPos.getX(p) - ax, dz = BlockPos.getZ(p) - az;
            vx.add(dx * rx + dz * rz, dx * lx + dz * lz, BlockPos.getY(p), color(st));
        }
        for (Plan p : L.plans) {
            if (cancelled.getAsBoolean()) return null;
            Blueprint bp = BuildingGenerator.generate(p, s);
            for (int y = 0; y < bp.h; y++)
                for (int z = bp.minZ(); z <= bp.maxZ(); z++)
                    for (int x = bp.minX(); x <= bp.maxX(); x++) {
                        BlockState st = bp.get(x, y, z);
                        if (st == null || st.isAir()) continue;
                        BlockPos w = p.toWorld(x, y, z);
                        int dx = w.getX() - ax, dz = w.getZ() - az;
                        vx.add(dx * rx + dz * rz, dx * lx + dz * lz, w.getY(), color(st));
                    }
        }
        if (vx.size() == 0) return new Image(1, 1);
        int pad = 4;
        int u0 = vx.uMin - pad, u1 = vx.uMax + pad, v0 = Math.min(vx.vMin - pad, -2), v1 = vx.vMax + pad;
        int ground = L.streetY;
        // земля
        for (int u = u0; u <= u1; u++)
            for (int v = v0; v <= v1; v++) {
                int g = ((u + v) & 1) == 0 ? 0xFF5E8A33 : 0xFF628E36;
                vx.add(u, v, ground - 1, g);
            }
        // маркер игрока
        vx.add(0, 0, ground, 0xFFFF3B3B);
        vx.add(0, 0, ground + 1, 0xFFFF3B3B);
        if (cancelled.getAsBoolean()) return null;
        return mode == Mode.TOP ? top(vx, u0, u1, v0, v1) : iso(vx, u0, u1, v0, v1);
    }

    private static Image top(Vox vx, int u0, int u1, int v0, int v1) {
        int w = u1 - u0 + 1, h = v1 - v0 + 1;
        Image img = new Image(w, h);
        int[] hb = new int[w * h];
        java.util.Arrays.fill(hb, Integer.MIN_VALUE);
        for (int i = 0; i < vx.size(); i++) {
            int sx = vx.u.getInt(i) - u0, sy = v1 - vx.v.getInt(i);
            if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
            int k = sy * w + sx, y = vx.y.getInt(i);
            if (y >= hb[k]) { hb[k] = y; img.argb[k] = vx.c.getInt(i); }
        }
        int span = Math.max(1, vx.yMax - vx.yMin);
        for (int sy = 0; sy < h; sy++)
            for (int sx = 0; sx < w; sx++) {
                int k = sy * w + sx;
                if (hb[k] == Integer.MIN_VALUE) { img.argb[k] = BG; continue; }
                int nb = (sx > 0 && sy > 0) ? hb[k - w - 1] : hb[k];
                if (nb == Integer.MIN_VALUE) nb = hb[k];
                double f = 0.78 + 0.32 * (hb[k] - vx.yMin) / (double) span;
                if (hb[k] > nb) f *= 1.12; else if (hb[k] < nb) f *= 0.72;
                img.argb[k] = shade(img.argb[k], f);
            }
        return img;
    }

    private static Image iso(Vox vx, int u0, int u1, int v0, int v1) {
        // x' = u, z' = -v (фасады к игроку видны), глубина = x' + y + z'
        int yMin = vx.yMin, yMax = vx.yMax;
        int s = 4;
        int W, H, sxMin, syMin;
        while (true) {
            sxMin = (u0 + v0) * s - s;
            int sxMax = (u1 + v1) * s + s;
            syMin = (int) Math.floor((u0 - v1) * s / 2.0) - (yMax + 1) * s;
            int syMax = (int) Math.ceil((u1 - v0) * s / 2.0) - yMin * s + 2 * s;
            W = sxMax - sxMin + 1; H = syMax - syMin + 1;
            if ((W <= 1000 && H <= 1000) || s == 1) break;
            s--;
        }
        W = Math.min(W, 2048); H = Math.min(H, 2048);
        Image img = new Image(W, H);
        java.util.Arrays.fill(img.argb, BG);
        int[] zb = new int[W * H];
        java.util.Arrays.fill(zb, Integer.MIN_VALUE);
        int sw = 2 * s, sh = 2 * s;
        byte[] sprite = sprite(s);
        for (int i = 0; i < vx.size(); i++) {
            int x = vx.u.getInt(i), z = -vx.v.getInt(i), y = vx.y.getInt(i);
            int depth = x + y + z;
            int bx = (x - z) * s - sxMin, by = (int) Math.floor((x + z) * s / 2.0) - (y + 1) * s - syMin;
            int col = vx.c.getInt(i);
            int cTop = shade(col, 1.0), cL = shade(col, 0.82), cR = shade(col, 0.62);
            for (int py = 0; py < sh; py++) {
                int yy = by + py;
                if (yy < 0 || yy >= H) continue;
                for (int px = 0; px < sw; px++) {
                    byte f = sprite[py * sw + px];
                    if (f == 0) continue;
                    int xx = bx + px - s;
                    if (xx < 0 || xx >= W) continue;
                    int k = yy * W + xx;
                    if (depth < zb[k]) continue;
                    zb[k] = depth;
                    img.argb[k] = f == 1 ? cTop : f == 2 ? cL : cR;
                }
            }
        }
        return img;
    }

    /** Маска граней куба размером 2s x 2s: 1 — верх, 2 — левая грань (фасад), 3 — правая. */
    private static byte[] sprite(int s) {
        int sw = 2 * s, sh = 2 * s;
        byte[] m = new byte[sw * sh];
        double[] top = {0, 0, s, s / 2.0, 0, s, -s, s / 2.0};
        double[] left = {-s, s / 2.0, 0, s, 0, 2.0 * s, -s, 1.5 * s};
        double[] right = {s, s / 2.0, s, 1.5 * s, 0, 2.0 * s, 0, s};
        for (int py = 0; py < sh; py++)
            for (int px = 0; px < sw; px++) {
                double x = px - s + 0.5, y = py + 0.5;
                byte f = inside(top, x, y) ? (byte) 1 : inside(left, x, y) ? (byte) 2 : inside(right, x, y) ? (byte) 3 : 0;
                if (f == 0 && s == 1) f = y < 1 ? (byte) 1 : x < 0 ? (byte) 2 : (byte) 3; // мелкий масштаб без щелей
                m[py * sw + px] = f;
            }
        return m;
    }

    private static boolean inside(double[] q, double x, double y) {
        boolean pos = false, neg = false;
        for (int i = 0; i < 4; i++) {
            double x1 = q[i * 2], y1 = q[i * 2 + 1], x2 = q[(i * 2 + 2) % 8], y2 = q[(i * 2 + 3) % 8];
            double c = (x2 - x1) * (y - y1) - (y2 - y1) * (x - x1);
            if (c > 1e-9) pos = true; else if (c < -1e-9) neg = true;
        }
        return !(pos && neg);
    }

    private static int shade(int argb, double f) {
        int r = Math.min(255, (int) (((argb >> 16) & 255) * f));
        int g = Math.min(255, (int) (((argb >> 8) & 255) * f));
        int b = Math.min(255, (int) ((argb & 255) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int color(BlockState st) {
        if (st.is(Blocks.WATER)) return 0xFF3F76E4;
        MapColor mc = st.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (mc == null || mc == MapColor.NONE || mc.col == 0) {
            String id = st.getBlock().getDescriptionId();
            if (id.contains("glass")) return 0xFFA9D2EA;
            if (id.contains("iron_bars")) return 0xFF505050;
            if (id.contains("lantern") || id.contains("end_rod")) return 0xFFFFE08A;
            return 0xFFBFBFBF;
        }
        int c = 0xFF000000 | mc.col;
        if (st.getBlock().getDescriptionId().contains("glass")) c = blend(c, 0xFFBFE3F5, 0.45);
        return c;
    }

    private static int blend(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }
}
