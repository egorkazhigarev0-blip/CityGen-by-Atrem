package com.citygen.gen;

import com.citygen.gen.GenSettings.StyleMode;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * Набор материалов здания. Основа реалистичности — ограниченная палитра (3–6 блоков)
 * с контрастной отделкой: стена, вторичная стена, отделка (карнизы/подоконники), рама, стекло.
 */
public class Palette {
    public final String name;
    public final StyleMode style;
    /** Битовая маска подходящих типов (BuildingType.ordinal), 0 = все. */
    public int types;

    public Block[] walls, walls2;
    public Block wall, wall2, base, trim, pillar, frame, glass, pane, floor, flatRoof, roofBlock, light, railing, accent, foundation;
    public Block trimStairs, trimSlab, roofStairs, roofSlab, door, trapdoor, fence, neon, dome, partition;

    /** Похожие блоки для «живой» фактуры стен (кирпич со сколами, мох и т.п.). */
    public static final Map<Block, Block[]> TEXTURE = new HashMap<>();

    static {
        TEXTURE.put(Blocks.STONE_BRICKS, new Block[]{Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS});
        TEXTURE.put(Blocks.DEEPSLATE_BRICKS, new Block[]{Blocks.CRACKED_DEEPSLATE_BRICKS, Blocks.DEEPSLATE_TILES});
        TEXTURE.put(Blocks.POLISHED_BLACKSTONE_BRICKS, new Block[]{Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS});
        TEXTURE.put(Blocks.NETHER_BRICKS, new Block[]{Blocks.CRACKED_NETHER_BRICKS});
        TEXTURE.put(Blocks.MUD_BRICKS, new Block[]{Blocks.PACKED_MUD});
        TEXTURE.put(Blocks.SMOOTH_SANDSTONE, new Block[]{Blocks.CUT_SANDSTONE, Blocks.SANDSTONE});
        TEXTURE.put(Blocks.SANDSTONE, new Block[]{Blocks.SMOOTH_SANDSTONE});
        TEXTURE.put(Blocks.END_STONE_BRICKS, new Block[]{Blocks.END_STONE});
        TEXTURE.put(Blocks.PRISMARINE_BRICKS, new Block[]{Blocks.PRISMARINE});
        TEXTURE.put(Blocks.QUARTZ_BRICKS, new Block[]{Blocks.QUARTZ_BLOCK});
        TEXTURE.put(Blocks.CALCITE, new Block[]{Blocks.DIORITE, Blocks.SMOOTH_QUARTZ});
        TEXTURE.put(Blocks.POLISHED_ANDESITE, new Block[]{Blocks.ANDESITE});
        TEXTURE.put(Blocks.BRICKS, new Block[]{Blocks.GRANITE});
        TEXTURE.put(Blocks.COBBLED_DEEPSLATE, new Block[]{Blocks.DEEPSLATE});
        TEXTURE.put(Blocks.PURPUR_BLOCK, new Block[]{Blocks.PURPUR_PILLAR});
    }

    public Palette(String name, StyleMode style) {
        this.name = name;
        this.style = style;
        floor = Blocks.SMOOTH_STONE; flatRoof = Blocks.LIGHT_GRAY_CONCRETE; light = Blocks.SEA_LANTERN;
        railing = Blocks.GLASS_PANE; foundation = Blocks.STONE_BRICKS; door = Blocks.SPRUCE_DOOR;
        trapdoor = Blocks.SPRUCE_TRAPDOOR; fence = Blocks.DARK_OAK_FENCE;
        roofBlock = Blocks.DEEPSLATE_TILES; roofStairs = Blocks.DEEPSLATE_TILE_STAIRS; roofSlab = Blocks.DEEPSLATE_TILE_SLAB;
        glass = Blocks.GLASS; pane = Blocks.GLASS_PANE; neon = Blocks.SEA_LANTERN;
        partition = Blocks.WHITE_CONCRETE;
    }

    public boolean classic() { return style.traditional(); }

    Palette walls(Block... w) { walls = w; wall = w[0]; return this; }
    Palette walls2(Block... w) { walls2 = w; wall2 = w[0]; return this; }
    Palette trim(Block block, Block stairs, Block slab) { trim = block; trimStairs = stairs; trimSlab = slab; return this; }
    Palette roof(Block block, Block stairs, Block slab) { roofBlock = block; roofStairs = stairs; roofSlab = slab; return this; }
    Palette glass(Block g, Block p) { glass = g; pane = p; return this; }
    Palette types(BuildingType... t) { for (BuildingType bt : t) types |= 1 << bt.ordinal(); return this; }
    Palette set(String what, Block b) {
        switch (what) {
            case "base" -> base = b; case "pillar" -> pillar = b; case "frame" -> frame = b; case "floor" -> floor = b;
            case "flatRoof" -> flatRoof = b; case "light" -> light = b; case "railing" -> railing = b; case "accent" -> accent = b;
            case "foundation" -> foundation = b; case "door" -> door = b; case "trapdoor" -> trapdoor = b; case "fence" -> fence = b;
            case "neon" -> neon = b; case "dome" -> dome = b; case "partition" -> partition = b;
            default -> throw new IllegalArgumentException(what);
        }
        return this;
    }

    public boolean suits(BuildingType t) { return types == 0 || (types & (1 << t.ordinal())) != 0; }

    /** Копия с выбранными вариантами цвета стен. */
    public Palette resolve(int wallVariant, int wall2Variant) {
        Palette p = new Palette(name, style);
        p.types = types; p.walls = walls; p.walls2 = walls2;
        p.wall = walls[Math.floorMod(wallVariant, walls.length)];
        p.wall2 = walls2 == null ? wall2 : walls2[Math.floorMod(wall2Variant, walls2.length)];
        p.base = base; p.trim = trim; p.pillar = pillar; p.frame = frame; p.glass = glass; p.pane = pane; p.floor = floor;
        p.flatRoof = flatRoof; p.roofBlock = roofBlock; p.light = light; p.railing = railing; p.accent = accent;
        p.foundation = foundation; p.trimStairs = trimStairs; p.trimSlab = trimSlab; p.roofStairs = roofStairs;
        p.roofSlab = roofSlab; p.door = door; p.trapdoor = trapdoor; p.fence = fence; p.neon = neon;
        p.dome = dome == null ? roofBlock : dome; p.partition = partition;
        return p;
    }

    public static BlockState st(Block b) { return b.defaultBlockState(); }
}
