package com.citygen.gen;

import com.citygen.gen.GenSettings.*;
import com.citygen.server.OpList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

import static com.citygen.gen.Decor.st;

/** Парки, площади, парковки и палисадники частных домов. */
public final class LotGenerator {
    private LotGenerator() {}

    public static StyleMode decorStyle(Layout L, GenSettings s) {
        if (s.style != StyleMode.AUTO) return s.style;
        return L.plans.isEmpty() ? StyleMode.MODERN : L.plans.get(0).style;
    }

    public static void addOps(Layout L, GenSettings s, OpList clears, OpList founds, OpList sets) {
        StyleMode style = decorStyle(L, s);
        for (LotPlan lp : L.lots) {
            Random r = new Random(lp.seed);
            int y = lp.baseY;
            for (int x = lp.minX; x <= lp.maxX; x++)
                for (int z = lp.minZ; z <= lp.maxZ; z++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (s.foundation) founds.add(p.below(), st(Blocks.DIRT), OpList.FOUNDATION);
                    if (s.clearSite) for (int k = 1; k <= 12; k++) clears.add(p.above(k), st(Blocks.AIR), OpList.CLEAR);
                }
            switch (lp.kind) {
                case PARK -> park(lp, s, style, r, sets);
                case PLAZA -> plaza(lp, s, style, r, sets);
                case PARKING -> parking(lp, style, r, sets);
                default -> {}
            }
        }
        if (s.yards) for (Plan p : L.plans) if (p.yardFront > 0) yard(p, s, r(p), clears, founds, sets);
    }

    private static Random r(Plan p) { return new Random(p.seed ^ 0xFA11L); }

    private static void park(LotPlan lp, GenSettings s, StyleMode style, Random r, OpList sets) {
        int y = lp.baseY, cx = (lp.minX + lp.maxX) / 2, cz = (lp.minZ + lp.maxZ) / 2;
        BlockState path = st(style == StyleMode.ANTIQUE ? Blocks.SMOOTH_SANDSTONE : style == StyleMode.FANTASY ? Blocks.END_STONE_BRICKS : Blocks.DIRT_PATH);
        for (int x = lp.minX; x <= lp.maxX; x++)
            for (int z = lp.minZ; z <= lp.maxZ; z++) {
                boolean onPath = Math.abs(x - cx) <= 1 || Math.abs(z - cz) <= 1;
                sets.add(new BlockPos(x, y, z), onPath ? path : st(Blocks.GRASS_BLOCK), OpList.SET);
                boolean border = x == lp.minX || x == lp.maxX || z == lp.minZ || z == lp.maxZ;
                if (border && !onPath && s.greenery != Greenery.NONE)
                    sets.add(new BlockPos(x, y + 1, z), Decor.leaves(style == StyleMode.FANTASY ? Blocks.AZALEA_LEAVES : Blocks.OAK_LEAVES), OpList.SET);
                else if (!onPath && !border && r.nextInt(9) == 0) sets.add(new BlockPos(x, y + 1, z), Decor.flower(r), OpList.SET);
            }
        int w = lp.maxX - lp.minX + 1, d = lp.maxZ - lp.minZ + 1;
        if (w >= 11 && d >= 11) Decor.fountain(sets, cx, y, cz, style);
        int trees = Math.max(2, w * d / 60);
        for (int k = 0, tries = 0; k < trees && tries < 60; tries++) {
            int x = lp.minX + 2 + r.nextInt(Math.max(1, w - 4)), z = lp.minZ + 2 + r.nextInt(Math.max(1, d - 4));
            if (Math.abs(x - cx) <= 3 || Math.abs(z - cz) <= 3) continue;
            Decor.tree(sets, x, y, z, style, r);
            k++;
        }
        Block bs = style == StyleMode.FANTASY ? Blocks.WARPED_STAIRS : Blocks.SPRUCE_STAIRS;
        Block bt = style == StyleMode.FANTASY ? Blocks.WARPED_TRAPDOOR : Blocks.SPRUCE_TRAPDOOR;
        if (d >= 9) {
            Decor.bench(sets, cx - 4, y, cz - 2, Direction.EAST, Direction.NORTH, bs, bt);
            Decor.bench(sets, cx + 3, y, cz + 2, Direction.WEST, Direction.SOUTH, bs, bt);
        }
        Decor.lamp(sets, cx + 2, y, lp.minZ + 2, style);
        Decor.lamp(sets, cx - 2, y, lp.maxZ - 2, style);
    }

    private static void plaza(LotPlan lp, GenSettings s, StyleMode style, Random r, OpList sets) {
        int y = lp.baseY, cx = (lp.minX + lp.maxX) / 2, cz = (lp.minZ + lp.maxZ) / 2;
        Block a, b;
        switch (style) {
            case ANTIQUE -> { a = Blocks.SMOOTH_SANDSTONE; b = Blocks.CUT_SANDSTONE; }
            case FANTASY -> { a = Blocks.POLISHED_BLACKSTONE; b = Blocks.PURPUR_BLOCK; }
            case CLASSIC -> { a = Blocks.STONE_BRICKS; b = Blocks.POLISHED_ANDESITE; }
            default -> { a = Blocks.POLISHED_ANDESITE; b = Blocks.SMOOTH_STONE; }
        }
        for (int x = lp.minX; x <= lp.maxX; x++)
            for (int z = lp.minZ; z <= lp.maxZ; z++) {
                int ring = Math.max(Math.abs(x - cx), Math.abs(z - cz));
                sets.add(new BlockPos(x, y, z), st(ring % 3 == 0 ? b : a), OpList.SET);
            }
        int w = lp.maxX - lp.minX + 1, d = lp.maxZ - lp.minZ + 1;
        if (w >= 9 && d >= 9) {
            if (r.nextBoolean()) Decor.fountain(sets, cx, y, cz, style);
            else monument(sets, cx, y, cz, style);
        }
        for (int[] c : new int[][]{{lp.minX + 2, lp.minZ + 2}, {lp.maxX - 2, lp.minZ + 2}, {lp.minX + 2, lp.maxZ - 2}, {lp.maxX - 2, lp.maxZ - 2}}) {
            if (s.greenery != Greenery.NONE) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    boolean edge = dx != 0 || dz != 0;
                    sets.add(new BlockPos(c[0] + dx, y + 1, c[1] + dz), edge ? BuildingGenerator.slab(Blocks.STONE_BRICK_SLAB,
                            net.minecraft.world.level.block.state.properties.SlabType.BOTTOM) : st(Blocks.GRASS_BLOCK), OpList.SET);
                }
                Decor.tree(sets, c[0], y + 1, c[1], style, r);
            } else Decor.lamp(sets, c[0], y, c[1], style);
        }
    }

    private static void monument(OpList sets, int x, int y, int z, StyleMode style) {
        Block base = style == StyleMode.ANTIQUE ? Blocks.QUARTZ_BLOCK : style == StyleMode.FANTASY ? Blocks.PURPUR_BLOCK : Blocks.POLISHED_ANDESITE;
        Block col = style == StyleMode.ANTIQUE ? Blocks.QUARTZ_PILLAR : style == StyleMode.FANTASY ? Blocks.PURPUR_PILLAR : Blocks.STONE_BRICKS;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) sets.add(new BlockPos(x + dx, y + 1, z + dz), st(base), OpList.SET);
        for (int k = 2; k <= 6; k++) sets.add(new BlockPos(x, y + k, z), st(col), OpList.SET);
        sets.add(new BlockPos(x, y + 7, z), st(style == StyleMode.FANTASY ? Blocks.BEACON : Blocks.GOLD_BLOCK), OpList.SET);
        sets.add(new BlockPos(x, y + 8, z), st(style == StyleMode.FANTASY ? Blocks.END_ROD : Blocks.LIGHTNING_ROD), OpList.SET);
    }

    private static void parking(LotPlan lp, StyleMode style, Random r, OpList sets) {
        int y = lp.baseY;
        boolean alongX = (lp.maxX - lp.minX) >= (lp.maxZ - lp.minZ);
        for (int x = lp.minX; x <= lp.maxX; x++)
            for (int z = lp.minZ; z <= lp.maxZ; z++) {
                int a = alongX ? x - lp.minX : z - lp.minZ;
                int c = alongX ? z - lp.minZ : x - lp.minX;
                int depth = alongX ? lp.maxZ - lp.minZ + 1 : lp.maxX - lp.minX + 1;
                boolean stall = c < 5 || c >= depth - 5;
                boolean line = stall && a % 3 == 0;
                sets.add(new BlockPos(x, y, z), st(line ? Blocks.WHITE_CONCRETE : Blocks.GRAY_CONCRETE), OpList.SET);
                if (stall && a % 3 != 0 && (c == 1 || c == depth - 2) && r.nextInt(3) != 0) car(sets, x, y, z, r);
            }
    }

    /** Машинка из блоков: корпус, стекло и колёса-кнопки. */
    private static void car(OpList sets, int x, int y, int z, Random r) {
        Block[] paints = {Blocks.RED_CONCRETE, Blocks.BLUE_CONCRETE, Blocks.WHITE_CONCRETE, Blocks.BLACK_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.GRAY_CONCRETE};
        Block paint = paints[r.nextInt(paints.length)];
        sets.add(new BlockPos(x, y + 1, z), st(paint), OpList.SET);
        sets.add(new BlockPos(x, y + 2, z), st(Blocks.LIGHT_GRAY_STAINED_GLASS), OpList.SET);
    }

    /** Палисадник перед домом: газон, дорожка к двери, клумбы, низкий забор, почтовый ящик. */
    private static void yard(Plan p, GenSettings s, Random r, OpList clears, OpList founds, OpList sets) {
        int x0 = -p.yardSide, x1 = p.w - 1 + p.yardSide, z0 = -p.yardFront;
        int cx = p.w / 2;
        BlockState fence = st(p.style == StyleMode.MODERN ? Blocks.BIRCH_FENCE : p.style == StyleMode.FANTASY ? Blocks.WARPED_FENCE : Blocks.DARK_OAK_FENCE);
        BlockState path = st(p.style == StyleMode.ANTIQUE ? Blocks.SMOOTH_SANDSTONE : Blocks.STONE_BRICKS);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= -1; z++) {
                BlockPos g = p.toWorld(x, 0, z);
                boolean onPath = x == cx || (p.w >= 12 && x == cx - 1);
                sets.add(g, onPath ? path : st(Blocks.GRASS_BLOCK), OpList.SET);
                if (s.foundation) founds.add(g.below(), st(Blocks.DIRT), OpList.FOUNDATION);
                if (s.clearSite) for (int k = 1; k <= 8; k++) clears.add(g.above(k), st(Blocks.AIR), OpList.CLEAR);
                if (onPath) continue;
                if (z == z0 && p.style != StyleMode.MODERN) sets.add(p.toWorld(x, 1, z), fence, OpList.SET);
                else if (z == -1 && s.greenery != Greenery.NONE && Math.abs(x - cx) > 2) sets.add(p.toWorld(x, 1, z), Decor.flower(r), OpList.SET);
                else if (z == z0 && p.style == StyleMode.MODERN && s.greenery != Greenery.NONE)
                    sets.add(p.toWorld(x, 1, z), Decor.leaves(Blocks.AZALEA_LEAVES), OpList.SET);
            }
        if (s.greenery != Greenery.NONE && p.w + 2 * p.yardSide >= 9) {
            BlockPos t = p.toWorld(x0 + 1, 0, z0 + 2);
            Decor.tree(sets, t.getX(), t.getY(), t.getZ(), p.style, r);
        }
        BlockPos mb = p.toWorld(cx + 1, 1, z0);
        sets.add(mb, fence, OpList.SET);
        sets.add(mb.above(), st(Blocks.BARREL), OpList.SET);
    }
}
