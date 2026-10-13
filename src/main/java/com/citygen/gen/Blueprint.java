package com.citygen.gen;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Локальная 3D-модель здания. x — вдоль фасада, z — вглубь (фасад смотрит в -z), y — вверх.
 * null = «не трогать». Вокруг участка есть поле M блоков для выступов (балконы, карнизы, козырьки).
 */
public class Blueprint {
    public static final int M = 3;
    public final int w, d, h;
    private final int sx, sz;
    private final BlockState[] data;

    public Blueprint(int w, int d, int h) {
        this.w = w; this.d = d; this.h = h;
        this.sx = w + 2 * M; this.sz = d + 2 * M;
        this.data = new BlockState[sx * sz * h];
    }

    public boolean inside(int x, int y, int z) {
        return x >= -M && x < w + M && z >= -M && z < d + M && y >= 0 && y < h;
    }

    private int idx(int x, int y, int z) { return (y * sz + (z + M)) * sx + (x + M); }

    public void set(int x, int y, int z, BlockState s) { if (inside(x, y, z)) data[idx(x, y, z)] = s; }

    public void setIfEmpty(int x, int y, int z, BlockState s) {
        if (inside(x, y, z) && data[idx(x, y, z)] == null) data[idx(x, y, z)] = s;
    }

    public BlockState get(int x, int y, int z) { return inside(x, y, z) ? data[idx(x, y, z)] : null; }

    public void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++)
            for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++)
                for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) set(x, y, z, s);
    }

    public int minX() { return -M; }
    public int maxX() { return w + M - 1; }
    public int minZ() { return -M; }
    public int maxZ() { return d + M - 1; }
}
