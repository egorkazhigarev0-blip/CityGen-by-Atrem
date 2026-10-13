package com.citygen.gen;

import com.citygen.gen.GenSettings.StyleMode;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.citygen.gen.BuildingType.*;

/** Каталог палитр. Порядок важен: индекс сохраняется в настройках. */
public final class Palettes {
    public static final List<Palette> ALL = new ArrayList<>();

    static {
        StyleMode M = StyleMode.MODERN, C = StyleMode.CLASSIC, A = StyleMode.ANTIQUE, F = StyleMode.FANTASY;
        // ======================= СОВРЕМЕННЫЕ =======================
        ALL.add(new Palette("Белый бетон", M)
                .walls(Blocks.WHITE_CONCRETE).walls2(Blocks.LIGHT_GRAY_CONCRETE)
                .trim(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB)
                .glass(Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE)
                .set("base", Blocks.POLISHED_ANDESITE).set("pillar", Blocks.WHITE_CONCRETE).set("frame", Blocks.GRAY_CONCRETE)
                .set("accent", Blocks.CYAN_TERRACOTTA).set("door", Blocks.BIRCH_DOOR).set("trapdoor", Blocks.BIRCH_TRAPDOOR)
                .set("flatRoof", Blocks.LIGHT_GRAY_CONCRETE));

        ALL.add(new Palette("Графит и тёмное стекло", M)
                .walls(Blocks.GRAY_CONCRETE).walls2(Blocks.BLACK_CONCRETE)
                .trim(Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE_STAIRS, Blocks.POLISHED_DEEPSLATE_SLAB)
                .glass(Blocks.GRAY_STAINED_GLASS, Blocks.GRAY_STAINED_GLASS_PANE)
                .set("base", Blocks.POLISHED_DEEPSLATE).set("pillar", Blocks.BLACK_CONCRETE).set("frame", Blocks.BLACK_CONCRETE)
                .set("accent", Blocks.WAXED_CUT_COPPER).set("light", Blocks.OCHRE_FROGLIGHT).set("floor", Blocks.POLISHED_ANDESITE)
                .set("railing", Blocks.GRAY_STAINED_GLASS_PANE).set("door", Blocks.DARK_OAK_DOOR)
                .set("trapdoor", Blocks.DARK_OAK_TRAPDOOR).set("foundation", Blocks.DEEPSLATE_BRICKS)
                .set("partition", Blocks.LIGHT_GRAY_CONCRETE)
                .types(SKYSCRAPER, OFFICE, HIGHRISE, MIDRISE, COMMERCIAL));

        ALL.add(new Palette("Стекло и сталь", M)
                .walls(Blocks.LIGHT_GRAY_CONCRETE).walls2(Blocks.POLISHED_ANDESITE)
                .trim(Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE_STAIRS, Blocks.POLISHED_ANDESITE_SLAB)
                .glass(Blocks.CYAN_STAINED_GLASS, Blocks.CYAN_STAINED_GLASS_PANE)
                .set("base", Blocks.POLISHED_ANDESITE).set("pillar", Blocks.LIGHT_GRAY_CONCRETE).set("frame", Blocks.LIGHT_GRAY_CONCRETE)
                .set("accent", Blocks.LIGHT_BLUE_CONCRETE).set("railing", Blocks.CYAN_STAINED_GLASS_PANE)
                .set("door", Blocks.BIRCH_DOOR).set("trapdoor", Blocks.BIRCH_TRAPDOOR)
                .types(SKYSCRAPER, OFFICE, COMMERCIAL, HIGHRISE, CIVIC));

        ALL.add(new Palette("Песчаник и бронза", M)
                .walls(Blocks.SMOOTH_SANDSTONE).walls2(Blocks.CUT_SANDSTONE)
                .trim(Blocks.SMOOTH_SANDSTONE, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB)
                .glass(Blocks.GRAY_STAINED_GLASS, Blocks.GRAY_STAINED_GLASS_PANE)
                .set("base", Blocks.SMOOTH_SANDSTONE).set("pillar", Blocks.SMOOTH_SANDSTONE).set("frame", Blocks.WAXED_EXPOSED_CUT_COPPER)
                .set("accent", Blocks.WAXED_EXPOSED_CUT_COPPER).set("light", Blocks.SHROOMLIGHT)
                .set("flatRoof", Blocks.SMOOTH_SANDSTONE).set("door", Blocks.SPRUCE_DOOR).set("foundation", Blocks.SANDSTONE)
                .types(SKYSCRAPER, OFFICE, MIDRISE, HIGHRISE, COMMERCIAL, CIVIC));

        ALL.add(new Palette("Скандинавский (дерево)", M)
                .walls(Blocks.CALCITE, Blocks.WHITE_CONCRETE).walls2(Blocks.STRIPPED_SPRUCE_WOOD, Blocks.STRIPPED_DARK_OAK_WOOD)
                .trim(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB)
                .glass(Blocks.GLASS, Blocks.GLASS_PANE)
                .set("base", Blocks.POLISHED_ANDESITE).set("pillar", Blocks.STRIPPED_SPRUCE_WOOD).set("frame", Blocks.BLACK_CONCRETE)
                .set("accent", Blocks.STRIPPED_SPRUCE_WOOD).set("light", Blocks.SHROOMLIGHT).set("floor", Blocks.SPRUCE_PLANKS)
                .set("flatRoof", Blocks.GRAY_CONCRETE).set("door", Blocks.SPRUCE_DOOR)
                .types(HOUSE, TOWNHOUSE, MIDRISE, HIGHRISE));

        ALL.add(new Palette("Цветные панели", M)
                .walls(Blocks.WHITE_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE)
                .walls2(Blocks.ORANGE_TERRACOTTA, Blocks.YELLOW_TERRACOTTA, Blocks.LIGHT_BLUE_TERRACOTTA, Blocks.RED_TERRACOTTA,
                        Blocks.CYAN_TERRACOTTA, Blocks.LIME_TERRACOTTA)
                .trim(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB)
                .glass(Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE)
                .set("base", Blocks.GRAY_CONCRETE).set("pillar", Blocks.WHITE_CONCRETE).set("frame", Blocks.GRAY_CONCRETE)
                .set("accent", Blocks.ORANGE_TERRACOTTA).set("door", Blocks.BIRCH_DOOR).set("trapdoor", Blocks.BIRCH_TRAPDOOR)
                .types(HIGHRISE, MIDRISE, TOWNHOUSE));

        // ======================= КУЛЬТУРНЫЕ (ЕВРОПЕЙСКИЕ) =======================
        ALL.add(new Palette("Красный кирпич", C)
                .walls(Blocks.BRICKS).walls2(Blocks.BRICKS)
                .trim(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB)
                .glass(Blocks.GLASS, Blocks.GLASS_PANE)
                .set("base", Blocks.STONE_BRICKS).set("pillar", Blocks.QUARTZ_PILLAR).set("frame", Blocks.DARK_OAK_PLANKS)
                .set("accent", Blocks.DARK_OAK_PLANKS).set("light", Blocks.SHROOMLIGHT).set("floor", Blocks.SPRUCE_PLANKS)
                .set("railing", Blocks.IRON_BARS).set("door", Blocks.DARK_OAK_DOOR).set("trapdoor", Blocks.SPRUCE_TRAPDOOR)
                .set("flatRoof", Blocks.STONE_BRICKS).set("dome", Blocks.WAXED_OXIDIZED_COPPER).set("partition", Blocks.WHITE_TERRACOTTA));

        ALL.add(new Palette("Парижский известняк", C)
                .walls(Blocks.SMOOTH_SANDSTONE).walls2(Blocks.CUT_SANDSTONE)
                .trim(Blocks.CUT_SANDSTONE, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB)
                .roof(Blocks.POLISHED_DEEPSLATE, Blocks.POLISHED_DEEPSLATE_STAIRS, Blocks.POLISHED_DEEPSLATE_SLAB)
                .glass(Blocks.GLASS, Blocks.GLASS_PANE)
                .set("base", Blocks.CUT_SANDSTONE).set("pillar", Blocks.SMOOTH_SANDSTONE).set("frame", Blocks.DARK_OAK_PLANKS)
                .set("accent", Blocks.GREEN_TERRACOTTA).set("light", Blocks.SHROOMLIGHT).set("floor", Blocks.OAK_PLANKS)
                .set("railing", Blocks.IRON_BARS).set("door", Blocks.DARK_OAK_DOOR).set("trapdoor", Blocks.DARK_OAK_TRAPDOOR)
                .set("flatRoof", Blocks.SMOOTH_SANDSTONE).set("foundation", Blocks.SANDSTONE).set("dome", Blocks.POLISHED_DEEPSLATE)
                .set("partition", Blocks.WHITE_TERRACOTTA)
                .types(MIDRISE, HIGHRISE, OFFICE, SKYSCRAPER, COMMERCIAL, TOWNHOUSE, CIVIC));

        ALL.add(new Palette("Пастельная штукатурка", C)
                .walls(Blocks.YELLOW_TERRACOTTA, Blocks.WHITE_TERRACOTTA, Blocks.PINK_TERRACOTTA, Blocks.LIGHT_GRAY_TERRACOTTA,
                        Blocks.ORANGE_TERRACOTTA, Blocks.CYAN_TERRACOTTA, Blocks.LIGHT_BLUE_TERRACOTTA)
                .walls2(Blocks.WHITE_TERRACOTTA)
                .trim(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB)
                .roof(Blocks.POLISHED_GRANITE, Blocks.POLISHED_GRANITE_STAIRS, Blocks.POLISHED_GRANITE_SLAB)
                .glass(Blocks.GLASS, Blocks.GLASS_PANE)
                .set("base", Blocks.POLISHED_ANDESITE).set("pillar", Blocks.QUARTZ_PILLAR).set("frame", Blocks.SPRUCE_PLANKS)
                .set("accent", Blocks.RED_TERRACOTTA).set("light", Blocks.SHROOMLIGHT).set("floor", Blocks.OAK_PLANKS)
                .set("railing", Blocks.IRON_BARS).set("door", Blocks.SPRUCE_DOOR).set("trapdoor", Blocks.WARPED_TRAPDOOR)
                .set("flatRoof", Blocks.STONE_BRICKS).set("dome", Blocks.WAXED_OXIDIZED_COPPER).set("partition", Blocks.WHITE_TERRACOTTA)
                .types(MIDRISE, TOWNHOUSE, HOUSE, HIGHRISE, COMMERCIAL, CIVIC));

        ALL.add(new Palette("Серый камень", C)
                .walls(Blocks.STONE_BRICKS).walls2(Blocks.POLISHED_ANDESITE)
                .trim(Blocks.POLISHED_ANDESITE, Blocks.POLISHED_ANDESITE_STAIRS, Blocks.POLISHED_ANDESITE_SLAB)
                .glass(Blocks.GLASS, Blocks.GLASS_PANE)
                .set("base", Blocks.DEEPSLATE_BRICKS).set("pillar", Blocks.POLISHED_ANDESITE).set("frame", Blocks.SPRUCE_PLANKS)
                .set("accent", Blocks.SPRUCE_PLANKS).set("light", Blocks.SHROOMLIGHT).set("floor", Blocks.SPRUCE_PLANKS)
                .set("railing", Blocks.IRON_BARS).set("door", Blocks.SPRUCE_DOOR).set("trapdoor", Blocks.SPRUCE_TRAPDOOR)
                .set("flatRoof", Blocks.POLISHED_ANDESITE).set("foundation", Blocks.COBBLED_DEEPSLATE)
                .set("dome", Blocks.WAXED_WEATHERED_COPPER).set("partition", Blocks.CALCITE));

        ALL.add(new Palette("Голландский кирпич", C)
                .walls(Blocks.BRICKS, Blocks.MUD_BRICKS, Blocks.DARK_PRISMARINE, Blocks.DEEPSLATE_BRICKS,
                        Blocks.RED_NETHER_BRICKS, Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.WHITE_TERRACOTTA)
                .walls2(Blocks.BRICKS)
                .trim(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB)
                .glass(Blocks.GLASS, Blocks.GLASS_PANE)
                .set("base", Blocks.STONE_BRICKS).set("pillar", Blocks.QUARTZ_PILLAR).set("frame", Blocks.DARK_OAK_PLANKS)
                .set("accent", Blocks.DARK_OAK_PLANKS).set("light", Blocks.SHROOMLIGHT).set("floor", Blocks.OAK_PLANKS)
                .set("railing", Blocks.IRON_BARS).set("door", Blocks.DARK_OAK_DOOR).set("trapdoor", Blocks.DARK_OAK_TRAPDOOR)
                .set("flatRoof", Blocks.STONE_BRICKS).set("partition", Blocks.WHITE_TERRACOTTA)
                .types(TOWNHOUSE, HOUSE, MIDRISE));

        // ======================= АНТИЧНЫЕ =======================
        ALL.add(new Palette("Белый мрамор", A)
                .walls(Blocks.SMOOTH_QUARTZ, Blocks.CALCITE).walls2(Blocks.QUARTZ_BRICKS)
                .trim(Blocks.QUARTZ_BLOCK, Blocks.QUARTZ_STAIRS, Blocks.QUARTZ_SLAB)
                .roof(Blocks.BRICKS, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB)
                .glass(Blocks.GLASS, Blocks.GLASS_PANE)
                .set("base", Blocks.QUARTZ_BRICKS).set("pillar", Blocks.QUARTZ_PILLAR).set("frame", Blocks.QUARTZ_BRICKS)
                .set("accent", Blocks.GOLD_BLOCK).set("light", Blocks.OCHRE_FROGLIGHT).set("floor", Blocks.POLISHED_DIORITE)
                .set("railing", Blocks.DIORITE_WALL).set("door", Blocks.DARK_OAK_DOOR).set("trapdoor", Blocks.DARK_OAK_TRAPDOOR)
                .set("flatRoof", Blocks.SMOOTH_STONE).set("foundation", Blocks.QUARTZ_BRICKS).set("dome", Blocks.WAXED_OXIDIZED_COPPER)
                .set("partition", Blocks.SMOOTH_QUARTZ).set("fence", Blocks.SPRUCE_FENCE));

        ALL.add(new Palette("Римский травертин", A)
                .walls(Blocks.SMOOTH_SANDSTONE, Blocks.SANDSTONE).walls2(Blocks.CUT_SANDSTONE)
                .trim(Blocks.CUT_SANDSTONE, Blocks.SMOOTH_SANDSTONE_STAIRS, Blocks.SMOOTH_SANDSTONE_SLAB)
                .roof(Blocks.BRICKS, Blocks.BRICK_STAIRS, Blocks.BRICK_SLAB)
                .glass(Blocks.GLASS, Blocks.GLASS_PANE)
                .set("base", Blocks.CUT_SANDSTONE).set("pillar", Blocks.SMOOTH_SANDSTONE).set("frame", Blocks.CUT_SANDSTONE)
                .set("accent", Blocks.WAXED_EXPOSED_CUT_COPPER).set("light", Blocks.OCHRE_FROGLIGHT).set("floor", Blocks.POLISHED_GRANITE)
                .set("railing", Blocks.SANDSTONE_WALL).set("door", Blocks.SPRUCE_DOOR).set("trapdoor", Blocks.SPRUCE_TRAPDOOR)
                .set("flatRoof", Blocks.SMOOTH_SANDSTONE).set("foundation", Blocks.SANDSTONE).set("dome", Blocks.BRICKS)
                .set("partition", Blocks.SMOOTH_SANDSTONE).set("fence", Blocks.SPRUCE_FENCE));

        ALL.add(new Palette("Эгейский (белый и синий)", A)
                .walls(Blocks.WHITE_CONCRETE, Blocks.CALCITE).walls2(Blocks.WHITE_CONCRETE)
                .trim(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB)
                .roof(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB)
                .glass(Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE)
                .set("base", Blocks.SMOOTH_QUARTZ).set("pillar", Blocks.QUARTZ_PILLAR).set("frame", Blocks.BLUE_CONCRETE)
                .set("accent", Blocks.BLUE_CONCRETE).set("light", Blocks.OCHRE_FROGLIGHT).set("floor", Blocks.SMOOTH_STONE)
                .set("railing", Blocks.WHITE_STAINED_GLASS_PANE).set("door", Blocks.WARPED_DOOR).set("trapdoor", Blocks.WARPED_TRAPDOOR)
                .set("flatRoof", Blocks.WHITE_CONCRETE).set("foundation", Blocks.STONE_BRICKS).set("dome", Blocks.BLUE_CONCRETE)
                .set("partition", Blocks.WHITE_CONCRETE).types(HOUSE, TOWNHOUSE, MIDRISE, CIVIC, COMMERCIAL));

        // ======================= ФАНТАСТИЧЕСКИЕ =======================
        ALL.add(new Palette("Неон-сити", F)
                .walls(Blocks.BLACK_CONCRETE, Blocks.POLISHED_BLACKSTONE).walls2(Blocks.GRAY_CONCRETE)
                .trim(Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_STAIRS, Blocks.POLISHED_BLACKSTONE_SLAB)
                .roof(Blocks.POLISHED_BLACKSTONE, Blocks.POLISHED_BLACKSTONE_STAIRS, Blocks.POLISHED_BLACKSTONE_SLAB)
                .glass(Blocks.PURPLE_STAINED_GLASS, Blocks.PURPLE_STAINED_GLASS_PANE)
                .set("base", Blocks.POLISHED_BLACKSTONE_BRICKS).set("pillar", Blocks.BLACK_CONCRETE).set("frame", Blocks.BLACK_CONCRETE)
                .set("accent", Blocks.MAGENTA_CONCRETE).set("light", Blocks.PEARLESCENT_FROGLIGHT).set("floor", Blocks.POLISHED_DEEPSLATE)
                .set("railing", Blocks.MAGENTA_STAINED_GLASS_PANE).set("door", Blocks.CRIMSON_DOOR).set("trapdoor", Blocks.CRIMSON_TRAPDOOR)
                .set("flatRoof", Blocks.BLACK_CONCRETE).set("foundation", Blocks.POLISHED_BLACKSTONE_BRICKS)
                .set("neon", Blocks.PEARLESCENT_FROGLIGHT).set("dome", Blocks.MAGENTA_STAINED_GLASS)
                .set("partition", Blocks.GRAY_CONCRETE).set("fence", Blocks.CRIMSON_FENCE));

        ALL.add(new Palette("Белый космопорт", F)
                .walls(Blocks.WHITE_CONCRETE, Blocks.SMOOTH_QUARTZ).walls2(Blocks.LIGHT_GRAY_CONCRETE)
                .trim(Blocks.SMOOTH_QUARTZ, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB)
                .roof(Blocks.WHITE_CONCRETE, Blocks.SMOOTH_QUARTZ_STAIRS, Blocks.SMOOTH_QUARTZ_SLAB)
                .glass(Blocks.LIGHT_BLUE_STAINED_GLASS, Blocks.LIGHT_BLUE_STAINED_GLASS_PANE)
                .set("base", Blocks.SMOOTH_QUARTZ).set("pillar", Blocks.WHITE_CONCRETE).set("frame", Blocks.LIGHT_GRAY_CONCRETE)
                .set("accent", Blocks.CYAN_CONCRETE).set("light", Blocks.SEA_LANTERN).set("floor", Blocks.SMOOTH_QUARTZ)
                .set("railing", Blocks.LIGHT_BLUE_STAINED_GLASS_PANE).set("door", Blocks.BIRCH_DOOR).set("trapdoor", Blocks.BIRCH_TRAPDOOR)
                .set("flatRoof", Blocks.WHITE_CONCRETE).set("neon", Blocks.SEA_LANTERN).set("dome", Blocks.LIGHT_BLUE_STAINED_GLASS)
                .set("partition", Blocks.WHITE_CONCRETE).set("fence", Blocks.BIRCH_FENCE));

        ALL.add(new Palette("Эндер-цитадель", F)
                .walls(Blocks.PURPUR_BLOCK).walls2(Blocks.END_STONE_BRICKS)
                .trim(Blocks.PURPUR_BLOCK, Blocks.PURPUR_STAIRS, Blocks.PURPUR_SLAB)
                .roof(Blocks.PURPUR_BLOCK, Blocks.PURPUR_STAIRS, Blocks.PURPUR_SLAB)
                .glass(Blocks.MAGENTA_STAINED_GLASS, Blocks.MAGENTA_STAINED_GLASS_PANE)
                .set("base", Blocks.END_STONE_BRICKS).set("pillar", Blocks.PURPUR_PILLAR).set("frame", Blocks.CRYING_OBSIDIAN)
                .set("accent", Blocks.AMETHYST_BLOCK).set("light", Blocks.PEARLESCENT_FROGLIGHT).set("floor", Blocks.END_STONE_BRICKS)
                .set("railing", Blocks.MAGENTA_STAINED_GLASS_PANE).set("door", Blocks.WARPED_DOOR).set("trapdoor", Blocks.WARPED_TRAPDOOR)
                .set("flatRoof", Blocks.END_STONE_BRICKS).set("foundation", Blocks.END_STONE_BRICKS)
                .set("neon", Blocks.PEARLESCENT_FROGLIGHT).set("dome", Blocks.AMETHYST_BLOCK)
                .set("partition", Blocks.END_STONE_BRICKS).set("fence", Blocks.WARPED_FENCE));

        ALL.add(new Palette("Атлантида", F)
                .walls(Blocks.PRISMARINE_BRICKS).walls2(Blocks.DARK_PRISMARINE)
                .trim(Blocks.PRISMARINE_BRICKS, Blocks.PRISMARINE_BRICK_STAIRS, Blocks.PRISMARINE_BRICK_SLAB)
                .roof(Blocks.DARK_PRISMARINE, Blocks.DARK_PRISMARINE_STAIRS, Blocks.DARK_PRISMARINE_SLAB)
                .glass(Blocks.CYAN_STAINED_GLASS, Blocks.CYAN_STAINED_GLASS_PANE)
                .set("base", Blocks.DARK_PRISMARINE).set("pillar", Blocks.PRISMARINE).set("frame", Blocks.DARK_PRISMARINE)
                .set("accent", Blocks.SEA_LANTERN).set("light", Blocks.SEA_LANTERN).set("floor", Blocks.PRISMARINE_BRICKS)
                .set("railing", Blocks.CYAN_STAINED_GLASS_PANE).set("door", Blocks.WARPED_DOOR).set("trapdoor", Blocks.WARPED_TRAPDOOR)
                .set("flatRoof", Blocks.DARK_PRISMARINE).set("foundation", Blocks.DARK_PRISMARINE)
                .set("neon", Blocks.SEA_LANTERN).set("dome", Blocks.LIGHT_BLUE_STAINED_GLASS)
                .set("partition", Blocks.PRISMARINE_BRICKS).set("fence", Blocks.WARPED_FENCE));
    }

    private Palettes() {}

    public static Palette get(int id) { return ALL.get(Math.floorMod(id, ALL.size())); }

    public static int pick(StyleMode style, BuildingType t, Random r) {
        List<Integer> c = new ArrayList<>();
        for (int i = 0; i < ALL.size(); i++) if (ALL.get(i).style == style && ALL.get(i).suits(t)) c.add(i);
        if (c.isEmpty()) for (int i = 0; i < ALL.size(); i++) if (ALL.get(i).style == style) c.add(i);
        return c.get(r.nextInt(c.size()));
    }
}
