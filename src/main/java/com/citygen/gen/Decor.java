package com.citygen.gen;

import com.citygen.gen.GenSettings.StyleMode;
import com.citygen.server.OpList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.Random;

/** Элементы благоустройства: деревья, скамейки, фонари, клумбы, фонтаны. */
public final class Decor {
    private Decor() {}

    static BlockState st(Block b) { return b.defaultBlockState(); }

    static BlockState leaves(Block b) {
        BlockState s = b.defaultBlockState();
        return s.hasProperty(BlockStateProperties.PERSISTENT) ? s.setValue(BlockStateProperties.PERSISTENT, true) : s;
    }

    /** Дерево с корнем на поверхности (x, y, z): ствол начинается с y+1. */
    public static void tree(OpList ops, int x, int y, int z, StyleMode style, Random r) {
        if (style == StyleMode.FANTASY) {
            boolean warped = r.nextBoolean();
            Block stem = warped ? Blocks.WARPED_STEM : Blocks.CRIMSON_STEM, cap = warped ? Blocks.WARPED_WART_BLOCK : Blocks.NETHER_WART_BLOCK;
            int t = 4 + r.nextInt(3);
            for (int k = 1; k <= t; k++) ops.add(new BlockPos(x, y + k, z), st(stem), OpList.SET);
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue;
                ops.add(new BlockPos(x + dx, y + t + 1, z + dz), st(cap), OpList.SET);
                if (Math.abs(dx) + Math.abs(dz) == 3 && r.nextInt(3) == 0) ops.add(new BlockPos(x + dx, y + t, z + dz), st(Blocks.SHROOMLIGHT), OpList.SET);
            }
            ops.add(new BlockPos(x, y + t + 2, z), st(cap), OpList.SET);
            return;
        }
        if (style == StyleMode.ANTIQUE) { // кипарис
            int t = 6 + r.nextInt(3);
            for (int k = 1; k <= 2; k++) ops.add(new BlockPos(x, y + k, z), st(Blocks.SPRUCE_LOG), OpList.SET);
            for (int k = 3; k <= t; k++) {
                ops.add(new BlockPos(x, y + k, z), leaves(Blocks.SPRUCE_LEAVES), OpList.SET);
                if (k < t - 1) for (Direction d : BuildingGenerator.HORIZ)
                    ops.add(new BlockPos(x + d.getStepX(), y + k, z + d.getStepZ()), leaves(Blocks.SPRUCE_LEAVES), OpList.SET);
            }
            return;
        }
        int kind = r.nextInt(3);
        Block log = kind == 0 ? Blocks.OAK_LOG : kind == 1 ? Blocks.BIRCH_LOG : Blocks.DARK_OAK_LOG;
        Block leaf = kind == 0 ? Blocks.OAK_LEAVES : kind == 1 ? Blocks.BIRCH_LEAVES : Blocks.DARK_OAK_LEAVES;
        if (r.nextInt(5) == 0) { log = Blocks.CHERRY_LOG; leaf = Blocks.CHERRY_LEAVES; }
        int t = 3 + r.nextInt(2);
        for (int k = 1; k <= t; k++) ops.add(new BlockPos(x, y + k, z), st(log), OpList.SET);
        for (int dy = -1; dy <= 2; dy++) {
            int rad = dy <= 0 ? 2 : 1;
            for (int dx = -rad; dx <= rad; dx++) for (int dz = -rad; dz <= rad; dz++) {
                if (Math.abs(dx) == rad && Math.abs(dz) == rad && (rad == 1 || r.nextBoolean())) continue;
                if (dx == 0 && dz == 0 && dy <= 0) continue;
                ops.add(new BlockPos(x + dx, y + t + dy, z + dz), leaves(leaf), OpList.SET);
            }
        }
    }

    /** Компактное уличное дерево у бордюра: крона 3×3, почти не нависает над дорогой. */
    public static void streetTree(OpList ops, int x, int y, int z, StyleMode style, Random r) {
        if (style == StyleMode.ANTIQUE) { tree(ops, x, y, z, style, r); return; }
        Block log, leaf;
        if (style == StyleMode.FANTASY) {
            boolean warped = r.nextBoolean();
            log = warped ? Blocks.WARPED_STEM : Blocks.CRIMSON_STEM;
            leaf = warped ? Blocks.WARPED_WART_BLOCK : Blocks.NETHER_WART_BLOCK;
        } else {
            int kind = r.nextInt(4);
            log = kind == 0 ? Blocks.BIRCH_LOG : kind == 1 ? Blocks.CHERRY_LOG : Blocks.OAK_LOG;
            leaf = kind == 0 ? Blocks.BIRCH_LEAVES : kind == 1 ? Blocks.CHERRY_LEAVES : Blocks.OAK_LEAVES;
        }
        int t = 3 + r.nextInt(2);
        for (int k = 1; k <= t; k++) ops.add(new BlockPos(x, y + k, z), st(log), OpList.SET);
        for (int dy = 0; dy <= 2; dy++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    boolean corner = dx != 0 && dz != 0;
                    if (dy == 2 && corner) continue;
                    if (dy == 0 && corner && r.nextBoolean()) continue;
                    if (dx == 0 && dz == 0 && dy == 0) continue;
                    ops.add(new BlockPos(x + dx, y + t + dy, z + dz), leaves(leaf), OpList.SET);
                }
        if (style == StyleMode.FANTASY) ops.add(new BlockPos(x, y + t + 3, z), st(Blocks.SHROOMLIGHT), OpList.SET);
    }

    /** Фонарный столб: колонна + фонарь сверху. */
    public static void lamp(OpList ops, int x, int y, int z, StyleMode style) {
        Block post = switch (style) {
            case CLASSIC -> Blocks.POLISHED_BLACKSTONE_WALL;
            case ANTIQUE -> Blocks.SANDSTONE_WALL;
            case FANTASY -> Blocks.END_ROD;
            default -> Blocks.POLISHED_BLACKSTONE_WALL;
        };
        for (int k = 1; k <= 4; k++) ops.add(new BlockPos(x, y + k, z), st(post), OpList.SET);
        ops.add(new BlockPos(x, y + 5, z), st(style == StyleMode.FANTASY ? Blocks.SEA_LANTERN
                : style == StyleMode.MODERN ? Blocks.SEA_LANTERN : Blocks.LANTERN), OpList.SET);
    }

    /** Скамейка длиной 2 вдоль направления along; спинка к стороне back. */
    public static void bench(OpList ops, int x, int y, int z, Direction along, Direction back, Block stairs, Block trapdoor) {
        for (int a = 0; a < 2; a++)
            ops.add(new BlockPos(x + along.getStepX() * a, y + 1, z + along.getStepZ() * a),
                    BuildingGenerator.stairs(stairs, back, Half.BOTTOM), OpList.SET);
        ops.add(new BlockPos(x - along.getStepX(), y + 1, z - along.getStepZ()), BuildingGenerator.trapdoor(trapdoor, along.getOpposite(), true, Half.BOTTOM), OpList.SET);
        ops.add(new BlockPos(x + along.getStepX() * 2, y + 1, z + along.getStepZ() * 2), BuildingGenerator.trapdoor(trapdoor, along, true, Half.BOTTOM), OpList.SET);
    }

    /** Фонтан 5x5 с чашей воды; (x,z) — центр, y — уровень поверхности. */
    public static void fountain(OpList ops, int x, int y, int z, StyleMode style) {
        Block rim = style == StyleMode.ANTIQUE ? Blocks.QUARTZ_BLOCK : style == StyleMode.FANTASY ? Blocks.PRISMARINE_BRICKS : Blocks.STONE_BRICKS;
        Block cap = style == StyleMode.ANTIQUE ? Blocks.QUARTZ_SLAB : style == StyleMode.FANTASY ? Blocks.PRISMARINE_BRICK_SLAB : Blocks.STONE_BRICK_SLAB;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                ops.add(new BlockPos(x + dx, y - 1, z + dz), st(rim), OpList.SET);
                if (edge) {
                    ops.add(new BlockPos(x + dx, y, z + dz), st(rim), OpList.SET);
                    ops.add(new BlockPos(x + dx, y + 1, z + dz), BuildingGenerator.slab(cap, SlabType.BOTTOM), OpList.SET);
                } else if (dx == 0 && dz == 0) {
                    ops.add(new BlockPos(x, y, z), st(rim), OpList.SET);
                    ops.add(new BlockPos(x, y + 1, z), st(rim), OpList.SET);
                    ops.add(new BlockPos(x, y + 2, z), st(style == StyleMode.FANTASY ? Blocks.SEA_LANTERN : Blocks.DECORATED_POT), OpList.SET);
                } else ops.add(new BlockPos(x + dx, y, z + dz), st(Blocks.WATER), OpList.SET);
            }
    }

    static final Block[] FLOWERS = {Blocks.POPPY, Blocks.DANDELION, Blocks.AZURE_BLUET, Blocks.OXEYE_DAISY, Blocks.CORNFLOWER,
            Blocks.ALLIUM, Blocks.RED_TULIP, Blocks.WHITE_TULIP, Blocks.LILY_OF_THE_VALLEY};

    public static BlockState flower(Random r) { return st(FLOWERS[r.nextInt(FLOWERS.length)]); }
}
