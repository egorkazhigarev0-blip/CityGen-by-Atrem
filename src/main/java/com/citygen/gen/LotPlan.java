package com.citygen.gen;

import com.citygen.gen.GenSettings.CellType;
import net.minecraft.core.Direction;

/** Участок без здания: парк, площадь или парковка. */
public class LotPlan {
    public final CellType kind;
    public final int minX, minZ, maxX, maxZ;
    public final Direction facing;
    public final long seed;
    public int baseY;

    public LotPlan(CellType kind, int minX, int minZ, int maxX, int maxZ, Direction facing, long seed) {
        this.kind = kind; this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ;
        this.facing = facing; this.seed = seed;
    }
}
