package com.citygen.gen;

import com.citygen.gen.GenSettings.*;
import com.citygen.server.OpList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/** Тротуары, дорога с разметкой, бордюры, фонари и деревья в лунках. */
public final class StreetGenerator {
    private StreetGenerator() {}

    public static void addOps(Layout L, GenSettings s, OpList clears, OpList founds, OpList sets) {
        int y = L.streetY;
        StyleMode style = LotGenerator.decorStyle(L, s);
        BlockState walk = Decor.st(switch (style) {
            case CLASSIC -> Blocks.STONE_BRICKS;
            case ANTIQUE -> Blocks.SMOOTH_SANDSTONE;
            case FANTASY -> Blocks.POLISHED_DEEPSLATE;
            default -> Blocks.SMOOTH_STONE;
        });
        BlockState walk2 = Decor.st(switch (style) {
            case CLASSIC -> Blocks.POLISHED_ANDESITE;
            case ANTIQUE -> Blocks.CUT_SANDSTONE;
            case FANTASY -> Blocks.POLISHED_BLACKSTONE;
            default -> Blocks.SMOOTH_STONE;
        });
        BlockState curb = Decor.st(Blocks.POLISHED_ANDESITE);
        BlockState asphalt = Decor.st(Blocks.GRAY_CONCRETE);
        BlockState line = Decor.st(style == StyleMode.FANTASY ? Blocks.CYAN_CONCRETE : Blocks.WHITE_CONCRETE);
        BlockState found = Decor.st(Blocks.STONE);
        BlockState air = Decor.st(Blocks.AIR);
        Random r = new Random(s.seed ^ 0x57EE7L);
        for (Layout.Strip st : L.strips) {
            for (int u = st.u0; u <= st.u1; u++)
                for (int v = st.v0; v <= st.v1; v++) {
                    int along = st.alongU ? u : v, cross = st.alongU ? v : u;
                    int c0 = st.alongU ? st.v0 : st.u0, c1 = st.alongU ? st.v1 : st.u1;
                    BlockPos p = L.site(u, v, y);
                    BlockState surf;
                    if (st.kind == Layout.ROAD) {
                        int mid = (c0 + c1) / 2;
                        surf = (cross == mid && Math.floorMod(along, 6) < 3) ? line : asphalt;
                    } else {
                        boolean isCurb = st.lampCross != Integer.MIN_VALUE && cross == st.lampCross && (cross == c0 || cross == c1);
                        surf = isCurb ? curb : (Math.floorMod(along + cross, 4) == 0 ? walk2 : walk);
                    }
                    sets.add(p, surf, OpList.SET);
                    if (s.foundation) founds.add(p.below(), found, OpList.FOUNDATION);
                    if (s.clearSite) for (int k = 1; k <= 6; k++) clears.add(p.above(k), air, OpList.CLEAR);
                    if (st.kind == Layout.SIDEWALK && cross == st.lampCross) {
                        int m = Math.floorMod(along, 12);
                        if (m == 6) Decor.lamp(sets, p.getX(), y, p.getZ(), style);
                        else if (m == 0 && s.greenery != Greenery.NONE) {
                            sets.add(p, Decor.st(Blocks.GRASS_BLOCK), OpList.SET);
                            Decor.tree(sets, p.getX(), y, p.getZ(), style, r);
                        } else if (m == 3 && s.greenery == Greenery.RICH) {
                            sets.add(p.above(), Decor.leaves(Blocks.AZALEA_LEAVES), OpList.SET);
                        } else if (m == 9 && s.greenery == Greenery.RICH) {
                            sets.add(p.above(), Decor.st(Blocks.COMPOSTER), OpList.SET);
                        }
                    }
                }
        }
    }
}
