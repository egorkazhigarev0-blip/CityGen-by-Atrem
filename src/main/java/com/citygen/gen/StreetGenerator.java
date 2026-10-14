package com.citygen.gen;

import com.citygen.gen.GenSettings.*;
import com.citygen.server.OpList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Random;

/**
 * Улицы. Все полосы сначала растрируются в одну карту (дорога важнее тротуара),
 * затем по карте вычисляются перекрёстки, разметка, «зебры», бордюры и места для фонарей и деревьев.
 * Так соседние кварталы делят общую дорогу без наложений и «чужих» столбов на асфальте.
 */
public final class StreetGenerator {
    private static final byte NONE = 0, WALK = 1, ROAD = 2;
    private static final int CORR_X = 1, CORR_Z = 2, CROSS = 3;
    private static final int MAX_ROAD = 9;

    private StreetGenerator() {}

    public static void addOps(Layout L, GenSettings s, OpList clears, OpList founds, OpList sets) {
        if (L.strips.isEmpty()) return;
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Layout.Strip st : L.strips) {
            minX = Math.min(minX, st.minX); minZ = Math.min(minZ, st.minZ);
            maxX = Math.max(maxX, st.maxX); maxZ = Math.max(maxZ, st.maxZ);
        }
        minX--; minZ--; maxX++; maxZ++;
        int W = maxX - minX + 1, D = maxZ - minZ + 1;
        if ((long) W * D > 4_000_000L) return; // защита от гигантских карт
        byte[] k = new byte[W * D];
        int[] yy = new int[W * D];
        for (Layout.Strip st : L.strips) {
            byte v = st.kind == Layout.ROAD ? ROAD : WALK;
            for (int x = st.minX; x <= st.maxX; x++)
                for (int z = st.minZ; z <= st.maxZ; z++) {
                    int i = (z - minZ) * W + (x - minX);
                    if (v > k[i]) { k[i] = v; yy[i] = st.y; }
                    else if (v == k[i]) yy[i] = Math.min(yy[i], st.y);
                }
        }
        // перед входами в здания — без фонарей и деревьев
        boolean[] noDecor = new boolean[W * D];
        for (Plan p : L.plans)
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -5; dz <= -1; dz++) {
                    BlockPos q = p.toWorld(p.w / 2 + dx, 0, dz - p.yardFront);
                    int x = q.getX() - minX, z = q.getZ() - minZ;
                    if (x >= 0 && z >= 0 && x < W && z < D) noDecor[z * W + x] = true;
                }

        // длины пробегов дороги по X и Z и позиция клетки внутри пробега
        int[] rx = new int[W * D], px = new int[W * D], rz = new int[W * D], pz = new int[W * D];
        for (int z = 0; z < D; z++) {
            int x = 0;
            while (x < W) {
                if (k[z * W + x] != ROAD) { x++; continue; }
                int a = x;
                while (x < W && k[z * W + x] == ROAD) x++;
                for (int t = a; t < x; t++) { rx[z * W + t] = x - a; px[z * W + t] = t - a; }
            }
        }
        for (int x = 0; x < W; x++) {
            int z = 0;
            while (z < D) {
                if (k[z * W + x] != ROAD) { z++; continue; }
                int a = z;
                while (z < D && k[z * W + x] == ROAD) z++;
                for (int t = a; t < z; t++) { rz[t * W + x] = z - a; pz[t * W + x] = t - a; }
            }
        }
        int[] cls = new int[W * D];
        for (int i = 0; i < W * D; i++) {
            if (k[i] != ROAD) continue;
            boolean nz = rz[i] <= MAX_ROAD, nx = rx[i] <= MAX_ROAD;
            if (nz && !nx) cls[i] = CORR_X;
            else if (nx && !nz) cls[i] = CORR_Z;
            else if (!nx) cls[i] = CROSS;
            else cls[i] = rx[i] >= rz[i] ? CORR_X : CORR_Z;
        }
        // окрестность перекрёстков (±4) — без декора и с «зебрами»
        boolean[] nearCross = new boolean[W * D];
        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++) {
                if (cls[z * W + x] != CROSS) continue;
                for (int dz = -4; dz <= 4; dz++)
                    for (int dx = -4; dx <= 4; dx++) {
                        int a = x + dx, b = z + dz;
                        if (a >= 0 && b >= 0 && a < W && b < D) nearCross[b * W + a] = true;
                    }
            }

        StyleMode style = LotGenerator.decorStyle(L, s);
        BlockState walk = Decor.st(switch (style) {
            case CLASSIC -> Blocks.STONE_BRICKS; case ANTIQUE -> Blocks.SMOOTH_SANDSTONE;
            case FANTASY -> Blocks.POLISHED_DEEPSLATE; default -> Blocks.SMOOTH_STONE;
        });
        BlockState walk2 = Decor.st(switch (style) {
            case CLASSIC -> Blocks.POLISHED_ANDESITE; case ANTIQUE -> Blocks.CUT_SANDSTONE;
            case FANTASY -> Blocks.POLISHED_BLACKSTONE; default -> Blocks.SMOOTH_STONE;
        });
        BlockState curb = Decor.st(Blocks.POLISHED_ANDESITE);
        BlockState asphalt = Decor.st(Blocks.GRAY_CONCRETE);
        BlockState asphalt2 = Decor.st(Blocks.CYAN_TERRACOTTA);
        BlockState line = Decor.st(style == StyleMode.FANTASY ? Blocks.CYAN_CONCRETE : Blocks.WHITE_CONCRETE);
        BlockState pit = Decor.st(Blocks.PODZOL);
        BlockState found = Decor.st(Blocks.STONE);
        Random r = new Random(s.seed ^ 0x57EE7L);
        int treeStep = s.greenery == Greenery.RICH ? 14 : 28;

        for (int z = 0; z < D; z++)
            for (int x = 0; x < W; x++) {
                int i = z * W + x;
                if (k[i] == NONE) continue;
                int wx = x + minX, wz = z + minZ, y = yy[i];
                BlockPos p = new BlockPos(wx, y, wz);
                BlockState surf;
                if (k[i] == ROAD) {
                    surf = asphalt;
                    if (cls[i] == CORR_X || cls[i] == CORR_Z) {
                        boolean alongX = cls[i] == CORR_X;
                        int width = alongX ? rz[i] : rx[i], cross = alongX ? pz[i] : px[i], along = alongX ? wx : wz;
                        if (crossDist(cls, W, D, x, z, alongX) <= 3) {
                            if (cross % 2 == 0 && cross > 0 && cross < width - 1) surf = line; // «зебра»
                        } else if (width >= 5 && width % 2 == 1 && cross == width / 2 && Math.floorMod(along, 6) < 3) {
                            surf = line;
                        } else if (Math.floorMod(wx * 31 + wz * 17, 29) == 0) surf = asphalt2; // заплатки на асфальте
                    }
                } else {
                    boolean nRoad = road(k, W, D, x, z - 1) || road(k, W, D, x, z + 1);
                    boolean eRoad = road(k, W, D, x - 1, z) || road(k, W, D, x + 1, z);
                    boolean isCurb = nRoad || eRoad;
                    surf = isCurb ? curb : (Math.floorMod(wx + wz, 4) == 0 ? walk2 : walk);
                    if (isCurb && !(nRoad && eRoad) && !nearCross[i] && !noDecor[i]) {
                        int along = nRoad ? wx : wz;
                        boolean run = nRoad ? walkAt(k, W, D, x - 2, z) && walkAt(k, W, D, x + 2, z)
                                : walkAt(k, W, D, x, z - 2) && walkAt(k, W, D, x, z + 2);
                        if (run) {
                            if (Math.floorMod(along, 14) == 7) Decor.lamp(sets, wx, y, wz, style);
                            else if (s.greenery != Greenery.NONE && Math.floorMod(along, treeStep) == 0) {
                                surf = pit;
                                Decor.streetTree(sets, wx, y, wz, style, r);
                            }
                        }
                    } else if (!isCurb && !nearRoad(k, W, D, x, z, 4) && !noDecor[i]
                            && Math.floorMod(wx, 10) == 0 && Math.floorMod(wz, 10) == 0) {
                        Decor.lamp(sets, wx, y, wz, style); // пешеходная зона без дорог
                    }
                }
                sets.add(p, surf, OpList.SET);
                if (s.foundation) founds.add(p.below(), found, OpList.FOUNDATION);
            }
    }

    /** Расстояние вдоль оси коридора до ближайшей клетки перекрёстка (до 4). */
    private static int crossDist(int[] cls, int W, int D, int x, int z, boolean alongX) {
        for (int d = 1; d <= 4; d++) {
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                int a = alongX ? x + d * sgn : x, b = alongX ? z : z + d * sgn;
                if (a >= 0 && b >= 0 && a < W && b < D && cls[b * W + a] == CROSS) return d;
            }
        }
        return 99;
    }

    private static boolean road(byte[] k, int W, int D, int x, int z) {
        return x >= 0 && z >= 0 && x < W && z < D && k[z * W + x] == ROAD;
    }

    private static boolean walkAt(byte[] k, int W, int D, int x, int z) {
        return x >= 0 && z >= 0 && x < W && z < D && k[z * W + x] == WALK;
    }

    private static boolean nearRoad(byte[] k, int W, int D, int x, int z, int r) {
        for (int dz = -r; dz <= r; dz++)
            for (int dx = -r; dx <= r; dx++)
                if (road(k, W, D, x + dx, z + dz)) return true;
        return false;
    }
}
