package com.citygen.gen;

import com.citygen.gen.GenSettings.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Rotation;

/** Полностью определённые параметры одного здания и его место в мире. */
public class Plan {
    public int index;
    public BuildingType type;
    public StyleMode style = StyleMode.MODERN;
    public int paletteId, wallVariant, wall2Variant;
    public FacadeType facade;
    public Shape shape = Shape.RECT;
    public RoofType roof;
    public GroundFloor ground;
    public int balconies; // 0 нет, 1 местами, 2 везде
    public int windowWidth; // 0 = авто
    public boolean fireEscape;
    public boolean underConstruction; // строящееся здание: каркас, леса, кран
    public int quarter = -1;          // индекс квартала города
    public int floors, floorH, groundH;
    public int w, d;
    public boolean party; // соседи вплотную — боковые стены глухие
    public int yardFront, yardSide; // палисадник (для домов)
    public long seed;

    // мир
    public Direction facing = Direction.NORTH; // куда смотрит фасад
    public int minX, minZ, maxX, maxZ;
    public int originX, originZ, baseY;

    public boolean classic() { return style.traditional(); }

    /** Задаёт участок в мире и считает начало координат (левый передний угол). */
    public void place(int minX, int minZ, int maxX, int maxZ, Direction facing) {
        this.minX = minX; this.minZ = minZ; this.maxX = maxX; this.maxZ = maxZ; this.facing = facing;
        Direction r = facing.getClockWise(), b = facing.getOpposite();
        if (facing.getAxis() == Direction.Axis.Z) { w = maxX - minX + 1; d = maxZ - minZ + 1; }
        else { w = maxZ - minZ + 1; d = maxX - minX + 1; }
        originX = r.getStepX() > 0 ? minX : r.getStepX() < 0 ? maxX : (b.getStepX() > 0 ? minX : maxX);
        originZ = r.getStepZ() > 0 ? minZ : r.getStepZ() < 0 ? maxZ : (b.getStepZ() > 0 ? minZ : maxZ);
    }

    public BlockPos toWorld(int x, int y, int z) {
        Direction r = facing.getClockWise(), b = facing.getOpposite();
        return new BlockPos(originX + r.getStepX() * x + b.getStepX() * z, baseY + y,
                originZ + r.getStepZ() * x + b.getStepZ() * z);
    }

    public Rotation rotation() {
        return switch (facing) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** Высота верхней плиты (уровень крыши) от основания. */
    public int roofLevel() { return groundH + (floors - 1) * floorH; }

    /** Полная оценочная высота с крышей и надстройками. */
    public int estimatedHeight() {
        int h = roofLevel();
        int m = Math.min(w, d);
        switch (roof) {
            case GABLE, HIP -> h += m / 2 + 3;
            case CONE -> h += m + 4;
            case PEDIMENT -> h += m / 4 + 4;
            case DOME -> h += Math.min(15, m / 2) + 8;
            case MANSARD -> h += 4;
            default -> h += 6;
        }
        if (type == BuildingType.SKYSCRAPER && roof == RoofType.FLAT) h += 24;
        return h + 1;
    }
}
