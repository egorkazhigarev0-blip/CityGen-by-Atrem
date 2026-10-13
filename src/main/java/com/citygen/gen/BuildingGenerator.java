package com.citygen.gen;

import com.citygen.gen.GenSettings.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

import java.util.*;

/**
 * Генератор одного здания в локальных координатах (фасад смотрит на север, -z).
 *
 * Приёмы реалистичности:
 *  - массинг: подиум + башня, уступы, сужение, закрутка, цилиндр, Г/П-образные корпуса, дворы, крест;
 *  - глубина фасада: рама/пилястры/пояса/подоконники/карнизы выступают на 1 блок, балконы и эркеры — на 1–2;
 *  - ритм: окна сгруппированы в симметричные пролёты, углы — пилоны, руст или стеклянные;
 *  - высокий первый этаж (витрины, лобби, колоннада), венчающий карниз, парапет или балюстрада;
 *  - лестничные клетки с площадками, лифты, пожарные лестницы, мебель (InteriorGenerator);
 *  - кровля: надстройки, техника, панели, баки, купола, шпили; ночью горят окна; «живая» фактура стен.
 */
public class BuildingGenerator {
    static final int SOLID = -1, PIER = 0, WIN = 1;
    static final Direction[] HORIZ = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    static final BlockState AIR = Blocks.AIR.defaultBlockState();

    final Plan p;
    final GenSettings s;
    final Palette pal;
    final Random rnd;
    final int W, D, N;
    final int[] fy, fh;
    final boolean[][][] mask;
    final Blueprint bp;
    final Interior interior;
    final int mansardFloor;
    final FacadeType facade;
    final StyleMode style;
    final boolean trad, antique, fantasy;

    // параметры фасада
    int ww = 2, pw = 2, variant, balconyPattern;
    boolean shutters, quoins, frenchBalconies, balconyBands, fins, crown, tallWindows, erkers, flowerBoxes;

    // вертикальные коммуникации
    final boolean[][] core;                // клетки шахт (все этажи)
    final boolean[][][] reserved;          // клетки, занятые лестницей на конкретном этаже
    final List<int[]> keepClear = new ArrayList<>(); // {floor, x, z} — нельзя ставить мебель
    int shaftX0 = -1, shaftZ0, shaftL;     // лестничная клетка (внешний контур 6 x shaftL+2)
    int elevX0 = -1, elevZ0;               // лифт (внешний контур 4x4)
    int coreX = -1, coreZ = -1;            // стремянка в шахте 3x3
    int ladderX = -1, ladderZ, ladderFacing; // стремянка у стены
    int flightX = -1, flightZ;             // марш вдоль стены (дома)
    int entranceX0 = -1, entranceX1, entranceZ;
    boolean portico;

    final BlockState wall, wall2, base, trim, pillar, frame, glass, pane, floor, flatRoof, roofBlock, light, railing, accent, neon;

    public static Blueprint generate(Plan p, GenSettings s) {
        return new BuildingGenerator(p, s).run();
    }

    BuildingGenerator(Plan p, GenSettings s) {
        this.p = p; this.s = s;
        this.rnd = new Random(p.seed);
        this.pal = Palettes.get(p.paletteId).resolve(p.wallVariant, p.wall2Variant);
        this.W = p.w; this.D = p.d; this.N = Math.max(1, p.floors);
        this.fh = new int[N];
        this.fy = new int[N + 1];
        for (int i = 0; i < N; i++) fh[i] = i == 0 ? p.groundH : p.floorH;
        for (int i = 0; i < N; i++) fy[i + 1] = fy[i] + fh[i];
        this.interior = s.interior;
        this.mansardFloor = (p.roof == RoofType.MANSARD && N >= 2) ? N - 1 : -1;
        this.facade = p.facade == FacadeType.AUTO ? FacadeType.PUNCHED : p.facade;
        this.style = p.style;
        this.trad = style.traditional();
        this.antique = style == StyleMode.ANTIQUE;
        this.fantasy = style == StyleMode.FANTASY;
        this.bp = new Blueprint(W, D, p.estimatedHeight() + 4);
        this.mask = new boolean[N][][];
        this.core = new boolean[W][D];
        this.reserved = new boolean[N + 1][W][D];

        wall = st(pal.wall); wall2 = st(pal.wall2); base = st(pal.base); trim = st(pal.trim); pillar = st(pal.pillar);
        frame = st(pal.frame); glass = st(pal.glass); pane = st(pal.pane); floor = st(pal.floor); flatRoof = st(pal.flatRoof);
        roofBlock = st(pal.roofBlock); light = st(pal.light); railing = st(pal.railing); accent = st(pal.accent); neon = st(pal.neon);
    }

    private Blueprint run() {
        setupFacade();
        buildMasks();
        if (interior != Interior.NONE) planVertical();
        for (int i = 0; i <= N; i++) floorPass(i);
        switch (p.roof) {
            case GABLE -> gableRoof();
            case HIP -> erosionRoof(1, false);
            case CONE -> erosionRoof(2, true);
            case PEDIMENT -> pedimentRoof();
            case DOME -> dome();
            default -> {}
        }
        if (interior != Interior.NONE) buildVertical();
        entrance();
        if (facade == FacadeType.COLONNADE) portico();
        if (p.fireEscape) fireEscape();
        if (interior == Interior.ROOMS || interior == Interior.FURNISHED) new InteriorGenerator(this).run();
        if (s.rooftop) {
            if (p.roof == RoofType.FLAT || p.roof == RoofType.DOME) rooftop();
            else if (mansardFloor >= 0) chimneys();
        }
        weathering();
        return bp;
    }

    // =====================================================================
    // Настройка
    // =====================================================================
    private void setupFacade() {
        variant = rnd.nextInt(3);
        balconyPattern = p.balconies == 2 ? 0 : 1 + rnd.nextInt(4);
        switch (facade) {
            case CURTAIN -> { ww = 3; pw = 1; fins = rnd.nextFloat() < 0.4f; variant = rnd.nextInt(2); }
            case GRID -> { ww = rnd.nextBoolean() ? 2 : 3; pw = 1; }
            case RIBBON -> { ww = 4; pw = 1; }
            case PUNCHED -> {
                int[][] opts = {{2, 2}, {2, 1}, {1, 1}, {3, 2}};
                int[] o = opts[rnd.nextInt(opts.length)];
                if (p.type == BuildingType.HOUSE) { o = new int[]{3, 1}; tallWindows = true; variant = 1; }
                ww = o[0]; pw = o[1];
                if (p.balconies > 0 && variant == 2) variant = 1;
            }
            case BALCONY -> { ww = 2; pw = 1; }
            case CLASSIC -> {
                boolean small = p.type.small();
                ww = small || rnd.nextBoolean() ? 1 : 2;
                pw = 2;
                shutters = ww == 1 && rnd.nextFloat() < (small ? 0.7f : 0.4f);
            }
            case PILASTER -> { ww = 2; pw = rnd.nextBoolean() ? 1 : 2; }
            case ARCADE, COLONNADE -> { ww = 3; pw = rnd.nextBoolean() ? 1 : 2; }
            case NEON -> { ww = 4; pw = 1; variant = rnd.nextInt(2); }
            default -> {}
        }
        if (p.windowWidth > 0 && facade != FacadeType.ARCADE && facade != FacadeType.COLONNADE && facade != FacadeType.RIBBON) {
            ww = p.windowWidth;
            if (facade == FacadeType.CLASSIC) shutters = ww == 1 && shutters;
        }
        quoins = rnd.nextBoolean();
        frenchBalconies = trad && rnd.nextFloat() < 0.5f;
        balconyBands = trad && N >= 4 && rnd.nextFloat() < 0.5f && p.type != BuildingType.HOUSE;
        crown = p.type == BuildingType.SKYSCRAPER;
        erkers = s.ornaments && style == StyleMode.CLASSIC && N >= 3 && !p.type.small() && rnd.nextFloat() < 0.45f
                && (facade == FacadeType.CLASSIC || facade == FacadeType.PILASTER);
        flowerBoxes = s.ornaments && s.greenery != Greenery.NONE && trad && rnd.nextFloat() < 0.6f;
    }

    boolean[][] rect(int x0, int z0, int x1, int z1) {
        boolean[][] m = new boolean[W][D];
        for (int x = Math.max(0, x0); x <= Math.min(W - 1, x1); x++)
            for (int z = Math.max(0, z0); z <= Math.min(D - 1, z1); z++) m[x][z] = true;
        return m;
    }

    private static void cut(boolean[][] m, int x0, int z0, int x1, int z1) {
        for (int x = Math.max(0, x0); x <= Math.min(m.length - 1, x1); x++)
            for (int z = Math.max(0, z0); z <= Math.min(m[0].length - 1, z1); z++) m[x][z] = false;
    }

    private static void chamfer(boolean[][] m, int x0, int z0, int x1, int z1, int c) {
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) {
                int dx = Math.min(x - x0, x1 - x), dz = Math.min(z - z0, z1 - z);
                if (dx + dz < c) m[x][z] = false;
            }
    }

    private boolean[][] ellipse(int ins) {
        boolean[][] m = new boolean[W][D];
        double rx = W / 2.0 - ins, rz = D / 2.0 - ins;
        if (rx < 2 || rz < 2) return rect(ins, ins, W - 1 - ins, D - 1 - ins);
        for (int x = 0; x < W; x++)
            for (int z = 0; z < D; z++) {
                double dx = (x + 0.5 - W / 2.0) / rx, dz = (z + 0.5 - D / 2.0) / rz;
                m[x][z] = dx * dx + dz * dz <= 1.0;
            }
        return m;
    }

    private void buildMasks() {
        boolean[][] full = rect(0, 0, W - 1, D - 1);
        for (int i = 0; i < N; i++) mask[i] = full;
        Shape sh = p.shape == null || p.shape == Shape.AUTO ? Shape.RECT : p.shape;
        int m = Math.min(W, D);
        if (p.type == BuildingType.HOUSE && sh == Shape.RECT) { houseWing(full); return; }
        switch (sh) {
            case RECT, STEPPED -> {
                boolean sky = p.type == BuildingType.SKYSCRAPER;
                int podium = (sky && W >= 17 && D >= 17 && N >= 12) ? 2 + rnd.nextInt(3) : 0;
                int inset = podium > 0 ? (m >= 25 ? 3 : 2) : 0;
                boolean cham = sky && !trad && rnd.nextFloat() < 0.4f;
                int setbacks = sh == Shape.STEPPED ? (trad ? 2 + rnd.nextInt(2) : 1 + rnd.nextInt(2)) : 0;
                if (sh == Shape.STEPPED && !sky) setbacks = Math.min(setbacks, N >= 6 ? 2 : N >= 3 ? 1 : 0);
                double[][] th = {{}, {0.7}, {0.55, 0.8}, {0.45, 0.68, 0.86}};
                Map<Integer, boolean[][]> cache = new HashMap<>();
                for (int i = podium; i < N; i++) {
                    double t = (i - podium) / (double) Math.max(1, N - podium);
                    int ins = inset;
                    for (double v : th[setbacks]) if (t >= v) ins += 2;
                    while (ins > 0 && (W - 2 * ins < 9 || D - 2 * ins < 9)) ins--;
                    final int fi = ins;
                    mask[i] = cache.computeIfAbsent(ins, k -> {
                        boolean[][] mm = rect(fi, fi, W - 1 - fi, D - 1 - fi);
                        if (cham && Math.min(W, D) - 2 * fi >= 13) chamfer(mm, fi, fi, W - 1 - fi, D - 1 - fi, 3);
                        return mm;
                    });
                }
                if (sh == Shape.RECT && p.type == BuildingType.OFFICE && N >= 6 && rnd.nextFloat() < 0.35f && W >= 15 && D >= 13)
                    mask[N - 1] = rect(2, 2, W - 3, D - 3);
            }
            case TAPER -> {
                for (int i = 0; i < N; i++) {
                    int ins = (int) Math.round((i / (double) Math.max(1, N - 1)) * m * 0.22);
                    while (ins > 0 && (W - 2 * ins < 9 || D - 2 * ins < 9)) ins--;
                    mask[i] = fantasy ? ellipse(ins) : rect(ins, ins, W - 1 - ins, D - 1 - ins);
                }
            }
            case L -> {
                if (W < 16 || D < 14) break;
                boolean[][] mm = rect(0, 0, W - 1, D - 1);
                if (rnd.nextBoolean()) cut(mm, W / 2 + 1, D / 2 + 1, W - 1, D - 1);
                else cut(mm, 0, D / 2 + 1, W / 2 - 2, D - 1);
                Arrays.fill(mask, mm);
            }
            case U -> {
                if (W < 19 || D < 14) break;
                boolean[][] mm = rect(0, 0, W - 1, D - 1);
                int wing = Math.max(7, W / 4);
                cut(mm, wing, Math.max(7, D / 2), W - 1 - wing, D - 1);
                Arrays.fill(mask, mm);
            }
            case COURTYARD -> {
                if (W < 20 || D < 18) break;
                boolean[][] mm = rect(0, 0, W - 1, D - 1);
                cut(mm, 7, 7, W - 8, D - 8);
                Arrays.fill(mask, mm);
            }
            case CROSS -> {
                if (W < 17 || D < 15) break;
                int aw = Math.max(7, W / 3), ad = Math.max(7, D / 3);
                boolean[][] mm = new boolean[W][D];
                int ax0 = (W - aw) / 2, az0 = (D - ad) / 2;
                for (int x = 0; x < W; x++) for (int z = 0; z < D; z++)
                    mm[x][z] = (x >= ax0 && x < ax0 + aw) || (z >= az0 && z < az0 + ad);
                Arrays.fill(mask, mm);
            }
            case CYLINDER -> {
                if (m < 9) break;
                boolean[][] mm = ellipse(0);
                Arrays.fill(mask, mm);
                if (p.type == BuildingType.SKYSCRAPER && N > 10) {
                    boolean[][] top = ellipse(2);
                    for (int i = (int) (N * 0.8); i < N; i++) mask[i] = top;
                }
            }
            case TWIST -> {
                if (m < 13) break;
                double side = m * 0.72, half = side / 2.0;
                double total = Math.toRadians(60 + rnd.nextInt(60));
                for (int i = 0; i < N; i++) {
                    double a = total * i / Math.max(1, N - 1), ca = Math.cos(a), sa = Math.sin(a);
                    boolean[][] mm = new boolean[W][D];
                    for (int x = 0; x < W; x++) for (int z = 0; z < D; z++) {
                        double dx = x + 0.5 - W / 2.0, dz = z + 0.5 - D / 2.0;
                        double rx = dx * ca + dz * sa, rz = -dx * sa + dz * ca;
                        mm[x][z] = Math.abs(rx) <= half && Math.abs(rz) <= half;
                    }
                    mask[i] = mm;
                }
            }
            default -> {}
        }
    }

    private void houseWing(boolean[][] full) {
        if (W >= 10 && D >= 8 && rnd.nextFloat() < 0.5f) {
            int mw = Math.max(6, (int) (W * 0.62));
            boolean[][] main = rect(0, 0, mw - 1, D - 1);
            boolean[][] all = rect(0, 0, mw - 1, D - 1);
            for (int x = mw - 1; x < W; x++) for (int z = D / 3; z < D; z++) all[x][z] = true;
            mask[0] = all;
            for (int i = 1; i < N; i++) mask[i] = main;
            if (N == 1 && p.roof != RoofType.FLAT) mask[0] = full;
        }
    }

    // =====================================================================
    // Вертикальные коммуникации: лестничная клетка, лифт, марш, стремянка
    // =====================================================================
    boolean inAll(int x, int z) {
        for (int i = 0; i < N; i++) if (!in(mask[i], x, z)) return false;
        return true;
    }

    private boolean freeArea(int x0, int z0, int x1, int z1, int margin) {
        for (int x = x0 - margin; x <= x1 + margin; x++)
            for (int z = z0 - margin; z <= z1 + margin; z++) {
                if (x < 0 || z < 0 || x >= W || z >= D || !inAll(x, z)) return false;
                for (int i = 0; i < N; i++) if (isWall(mask[i], x, z)) return false;
            }
        return true;
    }

    private void planVertical() {
        boolean wantWell = s.stairs != StairsMode.LADDER && !p.type.small() && N >= 2;
        int maxH = 0;
        for (int h : fh) maxH = Math.max(maxH, h);
        int a = (maxH + 1) / 2;
        shaftL = a + 2;
        int sw = 6, sl = shaftL + 2;
        if (wantWell) {
            boolean elev = s.elevators && N >= 5;
            for (int pass = elev ? 0 : 1; pass < 2 && shaftX0 < 0; pass++) {
                int tw = pass == 0 ? sw + 3 : sw, tl = Math.max(sl, 4);
                int best = Integer.MAX_VALUE;
                for (int x = 1; x + tw - 1 < W - 1; x++)
                    for (int z = 1; z + tl - 1 < D - 1; z++) {
                        int dist = Math.abs(x + tw / 2 - W / 2) + Math.abs(z + tl / 2 - D / 2);
                        if (dist >= best) continue;
                        if (!freeArea(x, z, x + tw - 1, z + tl - 1, 1)) continue;
                        best = dist; shaftX0 = x; shaftZ0 = z;
                    }
                if (shaftX0 >= 0 && pass == 0) { elevX0 = shaftX0 + sw - 1; elevZ0 = shaftZ0; }
            }
            if (shaftX0 >= 0) {
                for (int x = shaftX0; x < shaftX0 + sw; x++) for (int z = shaftZ0; z < shaftZ0 + sl; z++) core[x][z] = true;
                if (elevX0 >= 0) for (int x = elevX0; x < elevX0 + 4; x++) for (int z = elevZ0; z < elevZ0 + 4; z++) core[x][z] = true;
                for (int i = 0; i <= N; i++)
                    for (int x = shaftX0 + 2; x <= shaftX0 + 3; x++) {
                        keepClear.add(new int[]{i, x, shaftZ0 - 1});
                        keepClear.add(new int[]{i, x, shaftZ0 - 2});
                    }
                if (elevX0 >= 0) for (int i = 0; i <= N; i++) for (int x = elevX0 + 1; x <= elevX0 + 3; x++) {
                    keepClear.add(new int[]{i, x, elevZ0 - 1});
                    keepClear.add(new int[]{i, x, elevZ0 - 2});
                }
                return;
            }
        }
        // марш вдоль задней стены (дома, таунхаусы)
        if (s.stairs != StairsMode.LADDER && N >= 2) {
            int len = maxH + 2;
            for (int z = D - 2; z >= 2 && flightX < 0; z--)
                for (int x = 1; x + len - 1 < W - 1 && flightX < 0; x++) {
                    boolean ok = true;
                    for (int k = 0; k < len && ok; k++) {
                        int cx = x + k;
                        if (!inAll(cx, z) || !inAll(cx, z - 1) || !inAll(cx, z + 1)) ok = false;
                        else for (int i = 0; i < N; i++) if (isWall(mask[i], cx, z) || isWall(mask[i], cx, z - 1)) ok = false;
                        if (cx == W / 2 || cx == W / 2 - 1) ok = ok && z > 3;
                    }
                    if (ok) { flightX = x + 1; flightZ = z; }
                }
            if (flightX >= 0) {
                for (int i = 0; i <= N; i++)
                    for (int k = -1; k <= maxH; k++) {
                        int cx = flightX + k;
                        if (cx >= 0 && cx < W) { reserved[i][cx][flightZ] = true; }
                        if (cx >= 0 && cx < W && i > 0) reserved[i][cx][flightZ - 1] = true;
                    }
                return;
            }
        }
        // стремянка в шахте 3x3
        int best = -1, bestCenter = Integer.MAX_VALUE;
        boolean[][] top = mask[N - 1];
        if (!p.type.small() && N >= 3) {
            for (int x = 0; x < W; x++)
                for (int z = 0; z < D; z++) {
                    if (!inAll(x, z)) continue;
                    int dist = Math.min(Math.min(rayDist(top, x, z, 1, 0), rayDist(top, x, z, -1, 0)),
                            Math.min(rayDist(top, x, z, 0, 1), rayDist(top, x, z, 0, -1)));
                    int center = Math.abs(x - W / 2) + Math.abs(z - D / 2);
                    if (dist > best || (dist == best && center < bestCenter)) { best = dist; bestCenter = center; coreX = x; coreZ = z; }
                }
            if (best >= 4) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) core[coreX + dx][coreZ + dz] = true;
                for (int i = 0; i <= N; i++) keepClear.add(new int[]{i, coreX, coreZ + 2});
                return;
            }
            coreX = -1;
        }
        // стремянка у задней стены
        for (int k = 0; k < W; k++) {
            int x = W / 2 + ((k % 2 == 0) ? k / 2 + 1 : -(k / 2 + 1));
            if (x <= 0 || x >= W - 1) continue;
            for (int z = D - 2; z >= 1; z--) {
                if (inAll(x, z) && !isWall(top, x, z) && in(top, x, z + 1) && isWall(top, x, z + 1)) {
                    ladderX = x; ladderZ = z;
                    for (int i = 0; i <= N; i++) { reserved[i][x][z] = true; if (z - 1 >= 0) reserved[i][x][z - 1] = true; }
                    return;
                }
            }
        }
    }

    private int rayDist(boolean[][] m, int x, int z, int dx, int dz) {
        int k = 0;
        while (in(m, x + dx * (k + 1), z + dz * (k + 1))) k++;
        return k;
    }

    private void buildVertical() {
        int top = fy[N];
        boolean flat = p.roof == RoofType.FLAT;
        if (shaftX0 >= 0) { stairwell(top, flat); if (elevX0 >= 0) elevator(top, flat); return; }
        if (flightX >= 0) { flights(); return; }
        if (coreX >= 0) { ladderCore(top, flat); return; }
        if (ladderX >= 0) {
            int ladderTop = (p.roof == RoofType.FLAT || p.roof == RoofType.DOME || mansardFloor >= 0) ? top - 1 : top;
            for (int y = 1; y <= ladderTop; y++) {
                bp.set(ladderX, y, ladderZ, ladder(Direction.NORTH));
                bp.set(ladderX, y, ladderZ + 1, y < fy[Math.min(1, N)] ? wallState(0) : wall);
            }
            if (flat) bp.set(ladderX, top, ladderZ, trapdoor(pal.trapdoor, Direction.NORTH, false, Half.TOP));
        }
    }

    /** Лестничная клетка: два марша на этаж с площадками; на плоской крыше — выход в надстройку. */
    private void stairwell(int top, boolean flat) {
        int x0 = shaftX0, z0 = shaftZ0, L = shaftL, sl = L + 2;
        BlockState wallM = st(pal.partition);
        BlockState landing = trad ? trim : floor;
        Block stair = pal.trimStairs;
        int wallTop = flat ? top + 3 : (mansardFloor >= 0 ? top - 1 : top - 1);
        for (int y = 1; y <= wallTop; y++)
            for (int x = x0; x < x0 + 6; x++)
                for (int z = z0; z < z0 + sl; z++)
                    if (x == x0 || x == x0 + 5 || z == z0 || z == z0 + sl - 1) bp.set(x, y, z, wallM);
        for (int i = 0; i < N; i++) {
            int y0 = fy[i], h = fh[i];
            boolean flight = i < N - 1 || (flat && s.rooftop);
            for (int lx = 0; lx < 4; lx++)
                for (int lz = 0; lz < L; lz++)
                    for (int y = y0 + 1; y <= y0 + h; y++) bp.set(x0 + 1 + lx, y, z0 + 1 + lz, AIR);
            if (i == 0) for (int lx = 0; lx < 4; lx++) for (int lz = 0; lz < L; lz++) bp.set(x0 + 1 + lx, 0, z0 + 1 + lz, landing);
            if (!flight) {
                if (i == N - 1) for (int lx = 0; lx < 4; lx++) for (int lz = 0; lz < L; lz++)
                    bp.set(x0 + 1 + lx, y0 + h, z0 + 1 + lz, (p.roof == RoofType.FLAT || p.roof == RoofType.DOME) ? flatRoof : floor);
            } else {
                int a = (h + 1) / 2, b = h - a;
                for (int k = 1; k <= a; k++)
                    for (int lx = 0; lx < 2; lx++) bp.set(x0 + 1 + lx, y0 + k, z0 + 1 + k, stairs(stair, Direction.SOUTH, Half.BOTTOM));
                for (int lz = a + 1; lz < L; lz++) for (int lx = 0; lx < 2; lx++) bp.set(x0 + 1 + lx, y0 + a, z0 + 1 + lz, landing);
                for (int lx = 0; lx < 4; lx++) bp.set(x0 + 1 + lx, y0 + a, z0 + L, landing);
                for (int j = 1; j <= b; j++)
                    for (int lx = 2; lx < 4; lx++) bp.set(x0 + 1 + lx, y0 + a + j, z0 + L - j, stairs(stair, Direction.NORTH, Half.BOTTOM));
                for (int lz = 1; lz <= L - 2 - b; lz++) for (int lx = 2; lx < 4; lx++) bp.set(x0 + 1 + lx, y0 + h, z0 + 1 + lz, landing);
                for (int lx = 0; lx < 4; lx++) bp.set(x0 + 1 + lx, y0 + h, z0 + 1, landing);
            }
            // дверь на этаж
            doorPair(x0 + 2, x0 + 3, y0 + 1, z0, Direction.SOUTH);
            bp.set(x0 + 5, y0 + Math.min(3, h - 1), z0 + sl / 2, light);
        }
        if (flat && s.rooftop) {
            for (int lx = 0; lx < 4; lx++) for (int lz = 0; lz < L; lz++)
                for (int y = top + 1; y <= top + 3; y++) bp.set(x0 + 1 + lx, y, z0 + 1 + lz, AIR);
            for (int x = x0; x < x0 + 6; x++) for (int z = z0; z < z0 + sl; z++) bp.set(x, top + 4, z, flatRoof);
            doorPair(x0 + 2, x0 + 3, top + 1, z0, Direction.SOUTH);
        }
    }

    private void doorPair(int xa, int xb, int y, int z, Direction facing) {
        Block d = trad || antique ? pal.door : Blocks.BIRCH_DOOR;
        placeDoor(d, xa, y, z, facing, DoorHingeSide.RIGHT);
        placeDoor(d, xb, y, z, facing, DoorHingeSide.LEFT);
    }

    private void elevator(int top, boolean flat) {
        int x0 = elevX0, z0 = elevZ0;
        BlockState wallM = st(pal.partition);
        int wallTop = flat && s.rooftop ? top + 3 : top - 1;
        for (int y = 1; y <= wallTop; y++)
            for (int x = x0; x < x0 + 4; x++)
                for (int z = z0; z < z0 + 4; z++) {
                    boolean edge = x == x0 || x == x0 + 3 || z == z0 || z == z0 + 3;
                    bp.set(x, y, z, edge ? wallM : AIR);
                }
        for (int x = x0 + 1; x <= x0 + 2; x++) for (int z = z0 + 1; z <= z0 + 2; z++) bp.set(x, 0, z, st(Blocks.SMOOTH_STONE));
        if (flat && s.rooftop) for (int x = x0; x < x0 + 4; x++) for (int z = z0; z < z0 + 4; z++) bp.set(x, top + 4, z, flatRoof);
        for (int i = 0; i < N; i++) {
            int y0 = fy[i];
            placeDoor(Blocks.IRON_DOOR, x0 + 1, y0 + 1, z0, Direction.SOUTH, DoorHingeSide.RIGHT);
            placeDoor(Blocks.IRON_DOOR, x0 + 2, y0 + 1, z0, Direction.SOUTH, DoorHingeSide.LEFT);
            bp.set(x0 + 3, y0 + 2, z0 - 1, with(with(st(Blocks.STONE_BUTTON), BlockStateProperties.ATTACH_FACE, AttachFace.WALL),
                    BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        }
    }

    /** Марш вдоль стены для домов: ступени стопкой, проём в перекрытии и ограждение. */
    private void flights() {
        int last = (p.roof == RoofType.GABLE || p.roof == RoofType.HIP || p.roof == RoofType.CONE || p.roof == RoofType.PEDIMENT)
                ? N - 1 : N - 2;
        Block stair = trad ? Blocks.DARK_OAK_STAIRS : fantasy ? pal.trimStairs : Blocks.SPRUCE_STAIRS;
        BlockState rail = st(trad ? Blocks.DARK_OAK_FENCE : fantasy ? pal.fence : Blocks.IRON_BARS);
        for (int i = 0; i <= last; i++) {
            int y0 = fy[i], h = fh[i];
            for (int k = 1; k <= h; k++) {
                bp.set(flightX + k - 1, y0 + k, flightZ, stairs(stair, Direction.EAST, Half.BOTTOM));
                for (int y = y0 + k + 1; y < y0 + h; y++) bp.set(flightX + k - 1, y, flightZ, AIR);
            }
            for (int k = 0; k < h - 1; k++) {
                bp.set(flightX + k, y0 + h, flightZ, AIR);
                if (k < h - 2) bp.set(flightX + k, y0 + h + 1, flightZ - 1, rail);
            }
        }
    }

    private void ladderCore(int top, boolean flat) {
        boolean[] opening = new boolean[top + 8];
        for (int i = 0; i < N; i++) { opening[fy[i] + 1] = true; opening[fy[i] + 2] = true; }
        if (flat) { opening[top + 1] = true; opening[top + 2] = true; }
        int wallTop = flat ? top + 3 : top - 1;
        for (int y = 1; y <= wallTop; y++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    boolean open = dx == 0 && dz == 1 && opening[y];
                    bp.set(coreX + dx, y, coreZ + dz, open ? AIR : wall2);
                }
        int ladderTop = flat ? top + 1 : top - 1;
        for (int y = 1; y <= ladderTop; y++) bp.set(coreX, y, coreZ, ladder(Direction.SOUTH));
        if (flat) {
            bp.set(coreX, top + 2, coreZ, AIR);
            bp.set(coreX, top + 3, coreZ, AIR);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) bp.set(coreX + dx, top + 4, coreZ + dz, flatRoof);
        }
    }

    // =====================================================================
    // Этажи
    // =====================================================================
    private void floorPass(int i) {
        boolean[][] cur = i < N ? mask[i] : null;
        boolean[][] prev = i > 0 ? mask[i - 1] : null;
        int y0 = fy[i];
        if (prev != null)
            for (int x = 0; x < W; x++)
                for (int z = 0; z < D; z++)
                    if (prev[x][z] && !in(cur, x, z)) roofCell(i, prev, x, z);
        if (cur == null) return;
        int h = fh[i];
        for (int x = 0; x < W; x++)
            for (int z = 0; z < D; z++) {
                if (!cur[x][z] || isWall(cur, x, z)) continue;
                bp.set(x, y0, z, slabState(i, x, z));
                for (int r = 1; r < h; r++) bp.set(x, y0 + r, z, AIR);
            }
        for (int x = 0; x < W; x++)
            for (int z = 0; z < D; z++)
                if (cur[x][z] && isWall(cur, x, z)) wallCell(i, cur, x, z);
    }

    boolean lightGrid(int x, int z) { return (x + 2) % 4 == 0 && (z + 2) % 4 == 0; }

    boolean lit(int floorIdx, int x, int z) {
        return interior != Interior.NONE && hash(floorIdx, x >> 3, z >> 3) % 100 < s.litPercent;
    }

    private BlockState slabState(int i, int x, int z) {
        if (i == 0) return floor;
        if (interior == Interior.NONE) return AIR;
        if (interior == Interior.FLOORS && lightGrid(x, z) && lit(i - 1, x, z)) return light;
        return floor;
    }

    private boolean attic() {
        return p.roof == RoofType.GABLE || p.roof == RoofType.HIP || p.roof == RoofType.CONE || p.roof == RoofType.PEDIMENT;
    }

    private void roofCell(int i, boolean[][] prev, int x, int z) {
        int y = fy[i];
        boolean edge = isWall(prev, x, z);
        if (i == N && attic()) { // пол чердака
            bp.set(x, y, z, edge ? wallState(N - 1) : floor);
            return;
        }
        if (i == N && mansardFloor >= 0) { // верх мансарды
            if (edge) {
                Direction n = outs(prev, x, z).get(0);
                bp.set(x, y, z, stairs(pal.roofStairs, n.getOpposite(), Half.BOTTOM));
            } else bp.set(x, y, z, roofBlock);
            return;
        }
        if (!edge) {
            bp.set(x, y, z, (interior == Interior.FLOORS && lightGrid(x, z) && lit(i - 1, x, z) && i < N) ? light : flatRoof);
            return;
        }
        boolean main = i == N;
        List<Direction> ns = outs(prev, x, z);
        if (trad) {
            bp.set(x, y, z, trim);
            if (main && s.ornaments && (antique || (style == StyleMode.CLASSIC && variant == 1 && mansardFloor < 0))) {
                // балюстрада: столбики и тумбы
                boolean post = Math.floorMod(x + z, 4) == 0 || ns.size() >= 2;
                bp.set(x, y + 1, z, post ? trim : st(pal.railing == Blocks.IRON_BARS ? Blocks.STONE_BRICK_WALL : pal.railing));
                if (post && ns.size() >= 2) bp.set(x, y + 2, z, st(Blocks.DECORATED_POT));
            } else bp.set(x, y + 1, z, main ? trim : railing);
            for (Direction n : ns) setO(x, z, n, 1, y, stairs(pal.trimStairs, n.getOpposite(), Half.TOP));
            if (ns.size() == 2 && ns.get(0).getAxis() != ns.get(1).getAxis())
                bp.set(x + ns.get(0).getStepX() + ns.get(1).getStepX(), y, z + ns.get(0).getStepZ() + ns.get(1).getStepZ(),
                        stairs(pal.trimStairs, ns.get(0).getOpposite(), Half.TOP));
            if (main && p.party && sideOnly(ns)) bp.set(x, y + 1, z, wall);
        } else if (fantasy) {
            bp.set(x, y, z, frame);
            bp.set(x, y + 1, z, main ? railing : railing);
            for (Direction n : ns) setO(x, z, n, 1, y, slab(pal.trimSlab, SlabType.TOP));
            if (main && Math.floorMod(x + z, 6) == 0) bp.set(x, y + 1, z, neon);
        } else {
            BlockState edgeMat = facade == FacadeType.CURTAIN || facade == FacadeType.DIAGRID ? frame : wall;
            bp.set(x, y, z, edgeMat);
            if (main) {
                bp.set(x, y + 1, z, edgeMat);
                if (p.type == BuildingType.SKYSCRAPER) bp.set(x, y + 2, z, frame);
                else if (facade == FacadeType.GRID || facade == FacadeType.RIBBON)
                    for (Direction n : ns) setO(x, z, n, 1, y, facade == FacadeType.GRID ? pillar : wall);
            } else bp.set(x, y + 1, z, railing);
        }
    }

    private boolean sideOnly(List<Direction> ns) {
        for (Direction n : ns) if (n.getAxis() != Direction.Axis.X) return false;
        return true;
    }

    BlockState wallState(int i) { return (i == 0 && trad) ? base : wall; }

    // =====================================================================
    // Стены и фасады
    // =====================================================================
    private void wallCell(int i, boolean[][] m, int x, int z) {
        List<Direction> ns = outs(m, x, z);
        if (ns.size() >= 2) { cornerColumn(i, x, z, ns); return; }
        Direction n = ns.get(0);
        Direction t = n.getClockWise();
        int a = 0, b = 0;
        while (isEdge(m, x - t.getStepX() * (a + 1), z - t.getStepZ() * (a + 1), n)) a++;
        while (isEdge(m, x + t.getStepX() * (b + 1), z + t.getStepZ() * (b + 1), n)) b++;
        column(i, x, z, n, a, a + b + 1);
    }

    private void column(int i, int x, int z, Direction n, int pos, int len) {
        int y0 = fy[i], h = fh[i];
        boolean side = n.getAxis() == Direction.Axis.X;
        boolean frontBack = !side;
        if (side && p.party) {
            for (int r = 0; r < h; r++) bp.set(x, y0 + r, z, wallState(i));
            return;
        }
        if (i == mansardFloor) { mansardColumn(i, x, z, n, pos, len); return; }
        if (i == 0) {
            if (p.ground == GroundFloor.SHOPS && n != Direction.SOUTH) { shopColumn(x, z, n, pos, len); return; }
            if (p.ground == GroundFloor.LOBBY && n == Direction.NORTH) { lobbyColumn(x, z, n, pos, len); return; }
        }
        switch (facade) {
            case CURTAIN -> curtain(i, x, z, n, pos, len);
            case GRID -> grid(i, x, z, n, pos, len);
            case RIBBON -> ribbon(i, x, z, n, pos, len);
            case BALCONY -> {
                if (frontBack && i > 0) balconyBand(i, x, z, n, pos, len);
                else punched(i, x, z, n, pos, len, frontBack);
            }
            case DIAGRID -> diagrid(i, x, z, side ? z : x);
            case NEON -> neonColumn(i, x, z, n, pos, len, side ? z : x);
            case CLASSIC, PILASTER -> classic(i, x, z, n, pos, len, frontBack);
            case ARCADE, COLONNADE -> arcade(i, x, z, n, pos, len, frontBack);
            default -> punched(i, x, z, n, pos, len, frontBack);
        }
    }

    private void curtain(int i, int x, int z, Direction n, int pos, int len) {
        int y0 = fy[i], h = fh[i];
        int[] c = bay(pos, len, ww, pw);
        boolean crownFloor = crown && i == N - 1;
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            if (c[0] != WIN) {
                bp.set(x, y, z, frame);
                if (fins && c[0] == PIER && i > 0) setO(x, z, n, 1, y, frame);
            } else if (r == 0) {
                bp.set(x, y, z, variant == 1 ? glass : frame);
            } else {
                bp.set(x, y, z, glass);
                if (crownFloor) setO(x, z, n, 1, y, st(Blocks.IRON_BARS));
            }
            if (i == 0 && r == 0) bp.set(x, y, z, base);
        }
    }

    private void grid(int i, int x, int z, Direction n, int pos, int len) {
        int y0 = fy[i], h = fh[i];
        int[] c = bay(pos, len, ww, pw);
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            if (c[0] != WIN) { bp.set(x, y, z, wall); setO(x, z, n, 1, y, pillar); }
            else if (r == 0) { bp.set(x, y, z, i == 0 ? base : wall2); setO(x, z, n, 1, y, pillar); }
            else bp.set(x, y, z, glass);
        }
    }

    private void ribbon(int i, int x, int z, Direction n, int pos, int len) {
        int y0 = fy[i], h = fh[i];
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            if (r <= 1) {
                bp.set(x, y, z, i == 0 && r == 0 ? base : wall);
                setO(x, z, n, 1, y, r == 0 ? wall : slab(pal.trimSlab, SlabType.BOTTOM));
            } else bp.set(x, y, z, pos % ww == 0 ? frame : glass);
        }
    }

    private void diagrid(int i, int x, int z, int u) {
        int y0 = fy[i], h = fh[i];
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            boolean f = Math.floorMod(u + y, 6) == 0 || Math.floorMod(u - y, 6) == 0;
            BlockState st = f ? frame : glass;
            if (i == 0 && r == 0) st = base;
            bp.set(x, y, z, st);
        }
    }

    private void neonColumn(int i, int x, int z, Direction n, int pos, int len, int u) {
        int y0 = fy[i], h = fh[i];
        boolean ring = i > 0 && i % 8 == 0 && i < N - 1;
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            BlockState st;
            if (r == 0) st = (i % 3 == 0) ? neon : frame;
            else if (variant == 1 && Math.floorMod(u, 7) == 0) st = neon;
            else st = glass;
            if (i == 0 && r == 0) st = base;
            bp.set(x, y, z, st);
        }
        if (ring && n != null) {
            BlockState sl = slab(pal.trimSlab, SlabType.TOP);
            setO(x, z, n, 1, y0, sl);
            setO(x, z, n, 2, y0, sl);
            setO(x, z, n, 2, y0 + 1, railing);
            setO(x, z, n, 1, y0 - 1, neon);
        }
    }

    private void punched(int i, int x, int z, Direction n, int pos, int len, boolean frontBack) {
        int y0 = fy[i], h = fh[i];
        int[] c = bay(pos, len, ww, pw);
        int wr1 = tallWindows ? h - 1 : h - 2;
        boolean nearWin = c[0] != WIN && (kind(pos - 1, len) == WIN || kind(pos + 1, len) == WIN);
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            boolean win = c[0] == WIN && r >= 1 && r <= wr1;
            if (win) bp.set(x, y, z, glass);
            else {
                BlockState w = wall;
                if (variant == 1 && c[0] == PIER && r >= 1 && r <= wr1 && (tallWindows || hash(i / 2, c[1], 7) % 3 == 0)) w = wall2;
                if (i == 0 && r == 0) w = base;
                bp.set(x, y, z, w);
            }
            if (variant == 2 && i > 0) {
                if (c[0] == WIN && (r == 0 || r == h - 1)) setO(x, z, n, 1, y, frame);
                if (nearWin) setO(x, z, n, 1, y, frame);
            }
        }
        if (frontBack && i > 0 && p.balconies > 0) {
            int span = balconySpan(pos, len, i);
            if (span > 0) {
                BlockState sl = slab(pal.trimSlab, SlabType.TOP);
                setO(x, z, n, 1, y0, sl);
                setO(x, z, n, 2, y0, sl);
                setO(x, z, n, 2, y0 + 1, railing);
                if (span == 2) setO(x, z, n, 1, y0 + 1, railing);
                else if (s.greenery == Greenery.RICH && hash(i, pos, 21) % 5 == 0) setO(x, z, n, 1, y0 + 1, plantPot(i + pos));
            }
        }
    }

    /** 0 — нет балкона, 1 — внутри балкона, 2 — край балкона. */
    private int balconySpan(int pos, int len, int i) {
        int[] c = bay(pos, len, ww, pw);
        if (c[0] == WIN) return balconyOn(c[1], c[2], i) ? 1 : 0;
        int[] l = bay(pos - 1, len, ww, pw), r = bay(pos + 1, len, ww, pw);
        if (l[0] == WIN && balconyOn(l[1], l[2], i)) return 2;
        if (r[0] == WIN && balconyOn(r[1], r[2], i)) return 2;
        return 0;
    }

    private boolean balconyOn(int bi, int nb, int floorIdx) {
        return switch (balconyPattern) {
            case 0 -> true;
            case 1 -> bi % 2 == 0;
            case 2 -> (bi + floorIdx) % 2 == 0;
            case 3 -> bi % 3 == 1;
            default -> Math.abs(bi - (nb - 1) / 2.0) < 1.0;
        };
    }

    private void balconyBand(int i, int x, int z, Direction n, int pos, int len) {
        int y0 = fy[i], h = fh[i];
        int[] c = bay(pos, len, ww, pw);
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            bp.set(x, y, z, (c[0] == WIN && r >= 1 && r <= h - 2) ? glass : wall);
        }
        if (pos >= 1 && pos <= len - 2) {
            BlockState sl = slab(pal.trimSlab, SlabType.TOP);
            setO(x, z, n, 1, y0, sl);
            setO(x, z, n, 2, y0, sl);
            setO(x, z, n, 2, y0 + 1, railing);
            if (pos == 1 || pos == len - 2) setO(x, z, n, 1, y0 + 1, railing);
            if (c[0] == PIER && c[1] % 3 == 2) {
                for (int r = 1; r < h; r++) { setO(x, z, n, 1, y0 + r, wall2); setO(x, z, n, 2, y0 + r, wall2); }
            } else if (s.greenery == Greenery.RICH && c[0] == WIN && hash(i, pos, 17) % 6 == 0) {
                setO(x, z, n, 1, y0 + 1, plantPot(pos + i));
            }
        }
    }

    private boolean erkerAt(int i, int pos, int len, Direction n) {
        if (!erkers || n != Direction.NORTH || i < 1 || i == mansardFloor) return false;
        int[] c = bay(pos, len, ww, pw);
        int bi = c[1], nb = c[2];
        if (c[0] != WIN) {
            int[] l = bay(pos - 1, len, ww, pw), r = bay(pos + 1, len, ww, pw);
            if (l[0] == WIN) { bi = l[1]; nb = l[2]; }
            else if (r[0] == WIN) { bi = r[1]; nb = r[2]; }
            else return false;
        }
        if (nb < 3) return false;
        return nb % 2 == 1 ? bi == nb / 2 : (bi == 0 || bi == nb - 1);
    }

    private void classic(int i, int x, int z, Direction n, int pos, int len, boolean frontBack) {
        int y0 = fy[i], h = fh[i];
        int[] c = bay(pos, len, ww, pw);
        int wr1 = h - 2;
        BlockState wm = wallState(i);
        Direction in = n.getOpposite();
        if (erkerAt(i, pos, len, n)) { erkerColumn(i, x, z, n, c[0] == WIN); return; }
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            boolean win = c[0] == WIN && r >= 1 && r <= wr1;
            bp.set(x, y, z, win ? pane : wm);
            if (facade == FacadeType.PILASTER && c[0] == PIER && i > 0)
                setO(x, z, n, 1, y, r == h - 1 ? trim : pillar);
        }
        if (c[0] == WIN) {
            setO(x, z, n, 1, y0, slab(pal.trimSlab, SlabType.TOP));
            setO(x, z, n, 1, y0 + h - 1, stairs(pal.trimStairs, in, Half.TOP));
            if (frenchBalconies && i >= 1 && frontBack && hash(i, c[1], 3) % 3 == 0) setO(x, z, n, 1, y0 + 1, railing);
            else if (flowerBoxes && i >= 1 && hash(i, pos, 5) % 4 == 0) setO(x, z, n, 1, y0 + 1, plantPot(i * 7 + pos));
        }
        if (shutters && c[0] != WIN && (kind(pos - 1, len) == WIN || kind(pos + 1, len) == WIN))
            for (int r = 1; r <= wr1; r++) setO(x, z, n, 1, y0 + r, trapdoor(pal.trapdoor, n, true, Half.BOTTOM));
        if (i == 1) setO(x, z, n, 1, y0, trim);
        if (balconyBands && frontBack && pos >= 1 && pos <= len - 2) {
            int top = mansardFloor >= 0 ? N - 2 : N - 1;
            if (i == 1 || (i == top && top >= 3)) {
                if (i != 1) setO(x, z, n, 1, y0, slab(pal.trimSlab, SlabType.TOP));
                setO(x, z, n, 1, y0 + 1, railing);
                if (shutters) for (int r = 2; r <= wr1; r++) setO(x, z, n, 1, y0 + r, AIR);
            }
        }
    }

    /** Эркер: остеклённый выступ на 1 блок, интерьер расширяется в него. */
    private void erkerColumn(int i, int x, int z, Direction n, boolean win) {
        int y0 = fy[i], h = fh[i];
        Direction in = n.getOpposite();
        bp.set(x, y0, z, wall);
        for (int r = 1; r < h; r++) bp.set(x, y0 + r, z, win ? AIR : wall);
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            BlockState o;
            if (r == 0) o = win ? floor : trim;
            else if (r == h - 1) o = trim;
            else o = win ? pane : wall;
            setO(x, z, n, 1, y, o);
        }
        if (i == 1) setO(x, z, n, 1, y0 - 1, stairs(pal.trimStairs, in, Half.TOP));
        int topFloor = mansardFloor >= 0 ? N - 2 : N - 1;
        if (i == topFloor) setO(x, z, n, 1, y0 + h, slab(pal.trimSlab, SlabType.BOTTOM));
    }

    /** Арочные окна: боковые клетки верхнего ряда — перевёрнутые ступени, замковый камень снаружи. */
    private void arcade(int i, int x, int z, Direction n, int pos, int len, boolean frontBack) {
        int y0 = fy[i], h = fh[i];
        int[] c = bay(pos, len, ww, pw);
        BlockState wm = wallState(i);
        Direction t = n.getClockWise();
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            BlockState st = wm;
            if (c[0] == WIN && r >= 1) {
                if (r == h - 1) {
                    if (c[3] == 0) st = stairs(pal.trimStairs, t.getOpposite(), Half.TOP);
                    else if (c[3] == ww - 1) st = stairs(pal.trimStairs, t, Half.TOP);
                    else st = trim;
                } else st = pane;
            }
            bp.set(x, y, z, st);
            if (c[0] == PIER && i > 0) setO(x, z, n, 1, y, r == h - 1 ? trim : pillar);
        }
        if (c[0] == WIN) {
            setO(x, z, n, 1, y0, slab(pal.trimSlab, SlabType.TOP));
            if (c[3] == ww / 2) setO(x, z, n, 1, y0 + h - 1, trim);
            if (flowerBoxes && i >= 1 && hash(i, pos, 5) % 5 == 0) setO(x, z, n, 1, y0 + 1, plantPot(i + pos * 3));
        }
        if (i == 1) setO(x, z, n, 1, y0, trim);
    }

    private void mansardColumn(int i, int x, int z, Direction n, int pos, int len) {
        int y0 = fy[i], h = fh[i];
        int[] c = bay(pos, len, 1, 3);
        Direction in = n.getOpposite();
        bp.set(x, y0, z, trim);
        setO(x, z, n, 1, y0, stairs(pal.trimStairs, in, Half.TOP));
        for (int r = 1; r < h; r++) bp.set(x, y0 + r, z, (c[0] == WIN && r <= 2) ? pane : roofBlock);
        if (c[0] == WIN) {
            if (h > 3) setO(x, z, n, 1, y0 + 3, stairs(pal.roofStairs, in, Half.BOTTOM));
        } else if (kind(pos - 1, len, 1, 3) == WIN || kind(pos + 1, len, 1, 3) == WIN) {
            setO(x, z, n, 1, y0 + 1, roofBlock);
            setO(x, z, n, 1, y0 + 2, roofBlock);
            if (h > 3) setO(x, z, n, 1, y0 + 3, stairs(pal.roofStairs, in, Half.BOTTOM));
        } else {
            setO(x, z, n, 1, y0 + 1, stairs(pal.roofStairs, in, Half.BOTTOM));
        }
    }

    private void shopColumn(int x, int z, Direction n, int pos, int len) {
        int h = fh[0];
        int[] c = bay(pos, len, 3, 1);
        for (int r = 0; r < h; r++) {
            BlockState st;
            if (r == 0) st = base;
            else if (r <= h - 3) st = c[0] == WIN ? (trad ? pane : glass) : (trad ? base : frame);
            else if (r == h - 2) st = fantasy && hash(pos, 0, 41) % 3 == 0 ? neon : accent;
            else st = trad ? trim : frame;
            bp.set(x, r, z, st);
        }
        if (c[0] == WIN && c[1] % 2 == 0 && c[3] == 1 && h - 3 >= 2) placeDoor(pal.door, x, 1, z, n.getOpposite(), DoorHingeSide.LEFT);
        if (pos >= 1 && pos <= len - 2) {
            if (trad) {
                if (c[0] == WIN) setO(x, z, n, 1, h - 2, trapdoor(pal.trapdoor, n, false, Half.TOP));
            } else setO(x, z, n, 1, h - 2, slab(pal.trimSlab, SlabType.TOP));
        }
    }

    private void lobbyColumn(int x, int z, Direction n, int pos, int len) {
        int h = fh[0];
        int[] c = bay(pos, len, 3, 1);
        for (int r = 0; r < h; r++) {
            BlockState st;
            if (r == 0) st = base;
            else if (r == h - 1) st = trad ? trim : (fantasy ? neon : frame);
            else st = c[0] == WIN ? (trad ? pane : glass) : (trad ? base : frame);
            bp.set(x, r, z, st);
            if (trad && c[0] == PIER && facade != FacadeType.COLONNADE) setO(x, z, n, 1, r, r == h - 1 ? trim : pillar);
        }
    }

    private void cornerColumn(int i, int x, int z, List<Direction> ns) {
        int y0 = fy[i], h = fh[i];
        Direction n1 = ns.get(0), n2 = ns.get(1);
        boolean perp = n1.getAxis() != n2.getAxis();
        boolean partyCorner = p.party && (n1.getAxis() == Direction.Axis.X || n2.getAxis() == Direction.Axis.X);
        if (i == mansardFloor) {
            bp.set(x, y0, z, trim);
            for (int r = 1; r < h; r++) bp.set(x, y0 + r, z, roofBlock);
            if (partyCorner) return;
            for (Direction n : ns) {
                setO(x, z, n, 1, y0, stairs(pal.trimStairs, n.getOpposite(), Half.TOP));
                setO(x, z, n, 1, y0 + 1, stairs(pal.roofStairs, n.getOpposite(), Half.BOTTOM));
            }
            if (perp) {
                bp.set(x + n1.getStepX() + n2.getStepX(), y0, z + n1.getStepZ() + n2.getStepZ(), stairs(pal.trimStairs, n1.getOpposite(), Half.TOP));
                bp.set(x + n1.getStepX() + n2.getStepX(), y0 + 1, z + n1.getStepZ() + n2.getStepZ(), stairs(pal.roofStairs, n1.getOpposite(), Half.BOTTOM));
            }
            return;
        }
        if (partyCorner) {
            for (int r = 0; r < h; r++) bp.set(x, y0 + r, z, wallState(i));
            return;
        }
        if (facade == FacadeType.NEON) { neonColumn(i, x, z, perp ? null : n1, 0, 3, x + z); if (perp) neonCornerRing(i, x, z, ns); return; }
        if (facade == FacadeType.DIAGRID) { diagrid(i, x, z, x + z); return; }
        int dx = n1.getStepX() + n2.getStepX(), dz = n1.getStepZ() + n2.getStepZ();
        for (int r = 0; r < h; r++) {
            int y = y0 + r;
            BlockState b;
            switch (facade) {
                case CURTAIN -> b = (r == 0 || i == 0) ? frame : glass;
                case GRID -> {
                    b = wall;
                    for (Direction n : ns) setO(x, z, n, 1, y, pillar);
                    if (perp) bp.set(x + dx, y, z + dz, pillar);
                }
                case RIBBON -> {
                    b = r <= 1 ? wall : glass;
                    if (r <= 1) {
                        BlockState o = r == 0 ? wall : slab(pal.trimSlab, SlabType.BOTTOM);
                        for (Direction n : ns) setO(x, z, n, 1, y, o);
                        if (perp) bp.set(x + dx, y, z + dz, o);
                    }
                }
                case CLASSIC, PILASTER, ARCADE, COLONNADE -> {
                    if (i == 0) b = base;
                    else b = quoins ? (y % 2 == 0 ? trim : wall) : pillar;
                }
                default -> b = (i == 0 && r == 0) ? base : wall;
            }
            if (i == 0 && r == 0 && facade != FacadeType.CURTAIN) b = base;
            bp.set(x, y, z, b);
        }
        if (trad && i == 1) {
            for (Direction n : ns) setO(x, z, n, 1, y0, trim);
            if (perp) bp.set(x + dx, y0, z + dz, trim);
        }
    }

    private void neonCornerRing(int i, int x, int z, List<Direction> ns) {
        if (!(i > 0 && i % 8 == 0 && i < N - 1)) return;
        BlockState sl = slab(pal.trimSlab, SlabType.TOP);
        int y0 = fy[i];
        for (Direction n : ns) { setO(x, z, n, 1, y0, sl); setO(x, z, n, 2, y0, sl); setO(x, z, n, 2, y0 + 1, railing); }
        int dx = ns.get(0).getStepX() + ns.get(1).getStepX(), dz = ns.get(0).getStepZ() + ns.get(1).getStepZ();
        bp.set(x + dx, y0, z + dz, sl);
        bp.set(x + dx, y0 + 1, z + dz, railing);
    }

    // =====================================================================
    // Вход, портик, пожарная лестница
    // =====================================================================
    private void entrance() {
        int cx = W / 2;
        int z0 = -1;
        for (int z = 0; z < D; z++) if (mask[0][cx][z]) { z0 = z; break; }
        if (z0 < 0) return;
        boolean house = p.type.small();
        boolean dbl = W >= 12 && !house;
        int xa = dbl ? cx - 1 : cx, xb = cx;
        entranceX0 = xa; entranceX1 = xb; entranceZ = z0;
        int gh = fh[0];
        for (int x = xa; x <= xb; x++) {
            DoorHingeSide hinge = (dbl && x == xa) ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
            placeDoor(pal.door, x, 1, z0, Direction.SOUTH, hinge);
            for (int y = 3; y < gh - 1; y++) bp.set(x, y, z0, trad ? pane : glass);
            for (int y = 1; y <= 2; y++) {
                bp.set(x, y, z0 - 1, AIR);
                bp.set(x, y, z0 - 2, AIR);
                if (z0 + 1 < D && !core[x][z0 + 1]) bp.set(x, y, z0 + 1, AIR);
            }
            keepClear.add(new int[]{0, x, z0 + 1});
            keepClear.add(new int[]{0, x, z0 + 2});
        }
        if (facade == FacadeType.COLONNADE) return;
        int cy = Math.max(3, Math.min(gh - 1, 4));
        BlockState canopy = house && trad ? slab(pal.roofSlab, SlabType.BOTTOM) : slab(pal.trimSlab, SlabType.TOP);
        for (int x = xa - 1; x <= xb + 1; x++) {
            bp.set(x, cy, z0 - 1, canopy);
            bp.set(x, cy, z0 - 2, canopy);
        }
        if (house && trad) {
            for (int y = 1; y < cy; y++) {
                bp.set(xa - 1, y, z0 - 2, st(pal.fence));
                bp.set(xb + 1, y, z0 - 2, st(pal.fence));
            }
        }
        if (fantasy) { bp.set(xa - 1, cy + 1, z0 - 2, neon); bp.set(xb + 1, cy + 1, z0 - 2, neon); }
    }

    /** Античный портик: колонны с базой и капителью, антаблемент; у общественных зданий — на всю высоту. */
    private void portico() {
        int xa = W, xb = -1, z0 = -1;
        for (int x = 1; x < W - 1; x++) {
            int fz = -1;
            for (int z = 0; z < D; z++) if (mask[0][x][z]) { fz = z; break; }
            if (fz < 0) continue;
            if (z0 < 0) z0 = fz;
            if (fz == z0) { xa = Math.min(xa, x); xb = Math.max(xb, x); }
        }
        if (xb - xa < 4 || z0 < 0) return;
        portico = true;
        boolean giant = p.type == BuildingType.CIVIC || N <= 3;
        int topY = giant ? fy[N] - 1 : fy[1] - 1;
        int len = xb - xa + 1;
        int nc = Math.max(2, (len + 1) / 3 + 1);
        Set<Integer> cols = new HashSet<>();
        for (int k = 0; k < nc; k++) cols.add(xa + (int) Math.round(k * (len - 1) / (double) (nc - 1)));
        for (int x = xa - 1; x <= xb + 1; x++) {
            for (int o = 1; o <= 2; o++) {
                bp.set(x, 0, z0 - o, base);
                for (int y = 1; y <= topY; y++) bp.set(x, y, z0 - o, AIR);
                bp.set(x, topY + 1, z0 - o, trim);
            }
            bp.set(x, topY + 1, z0 - 3, stairs(pal.trimStairs, Direction.SOUTH, Half.TOP));
            bp.set(x, 0, z0 - 3, slab(pal.trimSlab, SlabType.BOTTOM));
            if (cols.contains(x)) {
                bp.set(x, 1, z0 - 2, trim);
                for (int y = 2; y < topY; y++) bp.set(x, y, z0 - 2, pillar);
                bp.set(x, topY, z0 - 2, trim);
            }
        }
        // подсветка снизу у фантастики не нужна; у античности — фонари между колонн
        if (s.ornaments) for (int x : cols) if (x > xa && x < xb) bp.set(x, topY, z0 - 1, with(st(Blocks.LANTERN), BlockStateProperties.HANGING, true));
    }

    /** Пожарная лестница: площадки из люков, ограждение, стремянка и выдвижная лестница. */
    private void fireEscape() {
        int top = mansardFloor >= 0 ? N - 2 : N - 1;
        if (top < 2) return;
        Direction n = Direction.SOUTH;
        boolean[][] m0 = mask[1];
        int zb = -1;
        for (int z = D - 1; z >= 0; z--) if (m0[W / 2][z]) { zb = z; break; }
        if (zb < 0) return;
        for (int i = 1; i <= top; i++) if (!in(mask[i], W / 2, zb) || in(mask[i], W / 2, zb + 1)) return;
        int cx = W / 2;
        if (W < 9) return;
        int x0 = cx - 2, x1 = cx + 2, lx = cx + 1;
        BlockState plat = trapdoor(Blocks.IRON_TRAPDOOR, Direction.NORTH, false, Half.TOP);
        BlockState bars = st(Blocks.IRON_BARS);
        for (int i = 1; i <= top; i++) {
            int y0 = fy[i];
            for (int x = x0; x <= x1; x++) {
                for (int o = 1; o <= 2; o++) bp.set(x, y0, zb + o, x == lx && o == 1 ? ladder(n) : plat);
                bp.set(x, y0 + 1, zb + 2, bars);
            }
            bp.set(x0, y0 + 1, zb + 1, bars);
            bp.set(x1, y0 + 1, zb + 1, bars);
            for (int y = y0 + 1; y < (i == top ? fy[N] + 2 : fy[i + 1]); y++) {
                bp.set(lx, y, zb + 1, ladder(n));
                bp.set(lx, y, zb, i == 0 ? base : wall);
            }
        }
        for (int y = fy[1] - 2; y < fy[1]; y++) {
            bp.set(lx, y, zb + 1, ladder(n));
            bp.set(lx, y, zb, wallState(0));
        }
        bp.set(lx, fy[N], zb, wall);
        bp.set(lx, fy[N] + 1, zb, trad ? trim : wall);
    }

    // =====================================================================
    // Крыши
    // =====================================================================
    private int[] bbox(boolean[][] m) {
        int x0 = W, z0 = D, x1 = -1, z1 = -1;
        for (int x = 0; x < W; x++) for (int z = 0; z < D; z++) if (m[x][z]) {
            x0 = Math.min(x0, x); z0 = Math.min(z0, z); x1 = Math.max(x1, x); z1 = Math.max(z1, z);
        }
        return new int[]{x0, z0, x1, z1};
    }

    private void gableRoof() {
        int[] b = bbox(mask[N - 1]);
        int x0 = b[0], z0 = b[1], x1 = b[2], z1 = b[3];
        int base = fy[N];
        boolean ridgeAlongZ = p.type == BuildingType.TOWNHOUSE || (x1 - x0) < (z1 - z0);
        boolean dutch = p.type == BuildingType.TOWNHOUSE && trad;
        BlockState gableWall = wallState(N - 1);
        int topY = base;
        if (!ridgeAlongZ) {
            int ov = p.party ? 0 : 1;
            for (int k = 0; ; k++) {
                int zl = z0 - 1 + k, zr = z1 + 1 - k, y = base + k;
                if (zl > zr) break;
                topY = y;
                for (int x = x0 - ov; x <= x1 + ov; x++) {
                    if (zl == zr) { bp.set(x, y, zl, roofBlock); continue; }
                    bp.set(x, y, zl, stairs(pal.roofStairs, Direction.SOUTH, Half.BOTTOM));
                    bp.set(x, y, zr, stairs(pal.roofStairs, Direction.NORTH, Half.BOTTOM));
                    if (k >= 1) for (int z = zl + 1; z < zr; z++) {
                        if ((x == x0 || x == x1) && z >= z0 && z <= z1) {
                            boolean win = (k == 1 || k == 2) && z == (z0 + z1) / 2 && (z1 - z0) >= 6 && !p.party;
                            bp.set(x, y, z, win ? pane : gableWall);
                        } else if (x > x0 && x < x1) bp.set(x, y, z, AIR);
                    }
                }
                if (zl == zr) break;
            }
        } else {
            int ov = p.party ? 0 : 1;
            for (int k = 0; ; k++) {
                int xl = x0 - ov + k, xr = x1 + ov - k, y = base + k;
                if (xl > xr) break;
                topY = y;
                for (int z = z0 - 1; z <= z1 + 1; z++) {
                    if (xl == xr) { bp.set(xl, y, z, roofBlock); continue; }
                    bp.set(xl, y, z, stairs(pal.roofStairs, Direction.EAST, Half.BOTTOM));
                    bp.set(xr, y, z, stairs(pal.roofStairs, Direction.WEST, Half.BOTTOM));
                    for (int x = xl + 1; x < xr; x++) {
                        if (k == 0) continue;
                        if ((z == z0 || z == z1) && x >= x0 && x <= x1) {
                            int cxw = (x0 + x1) / 2;
                            boolean win = (k == 1 || k == 2) && (x1 - x0) >= 4
                                    && ((x1 - x0) >= 6 ? Math.abs(x - cxw) == 1 : x == cxw);
                            bp.set(x, y, z, win ? pane : gableWall);
                        } else if (z > z0 && z < z1) bp.set(x, y, z, AIR);
                    }
                }
                if (dutch) {
                    for (int x = Math.max(x0, xl); x <= Math.min(x1, xr); x++) {
                        BlockState cur = bp.get(x, y, z0);
                        if (cur == null || !cur.is(pal.pane)) bp.set(x, y, z0, gableWall);
                        bp.set(x, y, z0 - 1, AIR);
                    }
                    if (xl >= x0) bp.set(xl, y + 1, z0, slab(pal.trimSlab, SlabType.BOTTOM));
                    if (xr <= x1) bp.set(xr, y + 1, z0, slab(pal.trimSlab, SlabType.BOTTOM));
                }
                if (xl == xr) break;
            }
            if (dutch) {
                int cx = (x0 + x1) / 2;
                bp.set(cx, topY + 1, z0, trim);
                bp.set(cx, topY + 2, z0, slab(pal.trimSlab, SlabType.BOTTOM));
            }
        }
        chimney(ridgeAlongZ ? (x0 + x1) / 2 + (x1 - x0 >= 4 ? 1 : 0) : x0 + 2, ridgeAlongZ ? z1 - 2 : (z0 + z1) / 2 + 1, base, topY);
        if (N == 1 && p.type == BuildingType.HOUSE && !mask[0][W - 1][0])
            for (int y = 1; y < fy[1]; y++) bp.set(W - 1, y, 0, st(pal.fence));
    }

    private void chimney(int cx, int cz, int base, int topY) {
        if (!s.rooftop || !(p.type.small()) || rnd.nextFloat() >= 0.8f || fantasy) return;
        for (int y = base; y <= topY + 2; y++) bp.set(cx, y, cz, st(Blocks.BRICKS));
        bp.set(cx, topY + 3, cz, slab(Blocks.STONE_BRICK_SLAB, SlabType.BOTTOM));
    }

    /** Вальмовая (steep=1) или шатровая (steep=2) крыша: послойное «съедание» контура верхнего этажа. */
    private void erosionRoof(int steep, boolean finial) {
        boolean[][] top = mask[N - 1];
        int base = fy[N];
        boolean[][] layer = new boolean[W + 2][D + 2]; // со свесом на 1 (смещение +1)
        for (int x = -1; x <= W; x++) for (int z = -1; z <= D; z++) {
            boolean v = false;
            for (int dx = -1; dx <= 1 && !v; dx++) for (int dz = -1; dz <= 1 && !v; dz++) v = in(top, x + dx, z + dz);
            if (p.party && (x < 0 || x >= W)) v = false;
            layer[x + 1][z + 1] = v;
        }
        int y = base, topY = base;
        for (int k = 0; k < 64; k++) {
            boolean[][] next = new boolean[W + 2][D + 2];
            boolean any = false;
            for (int x = 0; x < W + 2; x++) for (int z = 0; z < D + 2; z++) {
                if (!layer[x][z]) continue;
                boolean inner = true;
                for (int dx = -1; dx <= 1 && inner; dx++) for (int dz = -1; dz <= 1 && inner; dz++) {
                    int ax = x + dx, az = z + dz;
                    if (ax < 0 || az < 0 || ax >= W + 2 || az >= D + 2 || !layer[ax][az]) inner = false;
                }
                next[x][z] = inner;
                any |= !inner;
            }
            if (!any) break;
            for (int x = 0; x < W + 2; x++) for (int z = 0; z < D + 2; z++) {
                if (!layer[x][z]) continue;
                int lx = x - 1, lz = z - 1;
                if (next[x][z]) {
                    if (k > 0) for (int s2 = 0; s2 < steep; s2++) bp.set(lx, y + s2, lz, AIR);
                    continue;
                }
                Direction f = null;
                for (Direction d : HORIZ) {
                    int ax = x + d.getStepX(), az = z + d.getStepZ(), bx = x - d.getStepX(), bz = z - d.getStepZ();
                    boolean inA = ax >= 0 && az >= 0 && ax < W + 2 && az < D + 2 && layer[ax][az];
                    boolean inB = bx >= 0 && bz >= 0 && bx < W + 2 && bz < D + 2 && layer[bx][bz];
                    if (inA && !inB) { f = d; break; }
                }
                if (steep == 2) bp.set(lx, y, lz, roofBlock);
                int sy = y + steep - 1;
                bp.set(lx, sy, lz, f == null ? roofBlock : stairs(pal.roofStairs, f, Half.BOTTOM));
            }
            topY = y + steep - 1;
            layer = next;
            y += steep;
            boolean left = false;
            for (boolean[] row : layer) for (boolean v : row) left |= v;
            if (!left) break;
        }
        if (finial) {
            int[] b = bbox(top);
            int cx = (b[0] + b[2]) / 2, cz = (b[1] + b[3]) / 2;
            bp.set(cx, topY + 1, cz, fantasy ? neon : st(pal.trim));
            bp.set(cx, topY + 2, cz, st(fantasy ? Blocks.END_ROD : Blocks.LIGHTNING_ROD));
        } else {
            int[] b = bbox(top);
            chimney(b[0] + 2, (b[1] + b[3]) / 2, base, topY);
        }
    }

    /** Пологая крыша с фронтоном (античный храм): ступенчатые полублоки, тимпан, карниз. */
    private void pedimentRoof() {
        int[] b = bbox(mask[N - 1]);
        int x0 = b[0], z0 = b[1], x1 = b[2], z1 = b[3];
        int base = fy[N];
        boolean giantPortico = facade == FacadeType.COLONNADE && (p.type == BuildingType.CIVIC || N <= 3);
        int zf = giantPortico ? z0 - 3 : z0 - 1;
        int width = x1 - x0 + 3;
        int halfW = width / 2;
        for (int j = 0; j <= halfW; j++) {
            int t = j + 1;
            int y = base + (t - 1) / 2;
            BlockState st = (t % 2 == 1) ? slab(pal.roofSlab, SlabType.BOTTOM) : slab(pal.roofSlab, SlabType.TOP);
            int xl = x0 - 1 + j, xr = x1 + 1 - j;
            for (int z = zf; z <= z1 + 1; z++) {
                bp.set(xl, y, z, st);
                bp.set(xr, y, z, st);
                for (int yy = base; yy < y; yy++) {
                    if (z == zf || z == z1 + 1) { bp.set(xl, yy, z, trim); bp.set(xr, yy, z, trim); }
                }
            }
        }
        // акротерии
        if (s.ornaments) {
            int cx = (x0 + x1) / 2;
            int ty = base + (halfW + 1) / 2;
            bp.set(cx, ty + 1, zf, st(trad ? Blocks.DECORATED_POT : Blocks.LIGHTNING_ROD));
            bp.set(x0 - 1, base + 1, zf, st(Blocks.DECORATED_POT));
            bp.set(x1 + 1, base + 1, zf, st(Blocks.DECORATED_POT));
        }
    }

    /** Купол: барабан с окнами и полусфера; у фантастики — стеклянная полусфера. */
    private void dome() {
        boolean[][] top = mask[N - 1];
        int[] b = bbox(top);
        int bw = b[2] - b[0] + 1, bd = b[3] - b[1] + 1;
        double cx = (b[0] + b[2]) / 2.0, cz = (b[1] + b[3]) / 2.0;
        int R = Math.max(3, Math.min(14, Math.min(bw, bd) / 2 - 2));
        int y0 = fy[N] + 1;
        int drum = fantasy ? 0 : (R >= 5 ? 3 : 2);
        BlockState domeM = st(pal.dome);
        for (int dy = 0; dy < drum; dy++)
            for (int x = (int) (cx - R - 1); x <= cx + R + 1; x++)
                for (int z = (int) (cz - R - 1); z <= cz + R + 1; z++) {
                    double d = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz));
                    if (d <= R + 0.5 && d > R - 0.5) {
                        boolean win = dy >= 1 && dy < drum && Math.floorMod((int) Math.round(Math.atan2(z - cz, x - cx) * 8), 3) == 0;
                        bp.set(x, y0 + dy, z, win ? pane : (dy == drum - 1 ? trim : wall));
                    } else if (d <= R - 0.5) bp.set(x, y0 + dy, z, AIR);
                }
        int ys = y0 + drum;
        for (int dy = 0; dy <= R; dy++)
            for (int x = (int) (cx - R - 1); x <= cx + R + 1; x++)
                for (int z = (int) (cz - R - 1); z <= cz + R + 1; z++) {
                    double d = Math.sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz) + dy * dy);
                    if (d <= R + 0.5 && d > R - 0.7) bp.set(x, ys + dy, z, domeM);
                    else if (d <= R - 0.7) bp.set(x, ys + dy, z, AIR);
                }
        int lx = (int) Math.round(cx), lz = (int) Math.round(cz);
        bp.set(lx, ys + R + 1, lz, fantasy ? neon : trim);
        bp.set(lx, ys + R + 2, lz, st(fantasy ? Blocks.END_ROD : Blocks.LIGHTNING_ROD));
    }

    private void chimneys() {
        int y = fy[N];
        boolean[][] top = mask[N - 1];
        int count = 2 + rnd.nextInt(3);
        for (int k = 0, tries = 0; k < count && tries < 60; tries++) {
            int x = 1 + rnd.nextInt(Math.max(1, W - 2)), z = 1 + rnd.nextInt(Math.max(1, D - 2));
            if (!in(top, x, z) || isWall(top, x, z) || core[x][z]) continue;
            boolean nearEdge = false;
            for (Direction d : HORIZ) if (isWall(top, x + d.getStepX(), z + d.getStepZ())) nearEdge = true;
            if (!nearEdge) continue;
            BlockState br = trad ? st(Blocks.BRICKS) : wall2;
            bp.set(x, y + 1, z, br); bp.set(x, y + 2, z, br);
            bp.set(x, y + 3, z, slab(pal.trimSlab, SlabType.BOTTOM));
            k++;
        }
    }

    // =====================================================================
    // Кровля (плоская)
    // =====================================================================
    private void rooftop() {
        int y = fy[N];
        boolean[][] top = mask[N - 1];
        boolean[][] used = new boolean[W][D];
        int free = 0;
        for (int x = 0; x < W; x++)
            for (int z = 0; z < D; z++) {
                used[x][z] = !top[x][z] || isWall(top, x, z) || nearEdge(top, x, z) || core[x][z];
                if (bp.get(x, y + 1, z) != null && bp.get(x, y + 1, z) != AIR) used[x][z] = true;
                if (!used[x][z]) free++;
            }
        if (p.roof == RoofType.DOME) return;
        // антенны / шпиль
        if (p.type == BuildingType.SKYSCRAPER) {
            int sx = shaftX0 >= 0 ? shaftX0 + 3 : coreX >= 0 ? coreX : W / 2;
            int sz = shaftX0 >= 0 ? shaftZ0 + (shaftL + 2) / 2 : coreZ >= 0 ? coreZ : D / 2;
            spire(sx, (shaftX0 >= 0 || coreX >= 0) ? y + 5 : y + 1, sz);
        }
        if (fantasy) {
            for (int k = 0; k < 4; k++) {
                int x = rnd.nextInt(W), z = rnd.nextInt(D);
                if (!areaFree(used, x, z, x, z)) continue;
                int hh = 2 + rnd.nextInt(4);
                for (int yy = 1; yy <= hh; yy++) bp.set(x, y + yy, z, st(Blocks.END_ROD));
                used[x][z] = true;
            }
        }
        int units = Math.max(1, Math.min(10, free / 45));
        BlockState body = st(Blocks.SMOOTH_STONE), vent = trapdoor(Blocks.IRON_TRAPDOOR, Direction.NORTH, false, Half.BOTTOM);
        for (int k = 0, tries = 0; k < units && tries < units * 12; tries++) {
            int sx = 2 + rnd.nextInt(2), sz = 2;
            int x = rnd.nextInt(W), z = rnd.nextInt(D);
            if (!areaFree(used, x - 1, z - 1, x + sx, z + sz)) continue;
            boolean tall = rnd.nextFloat() < 0.3f;
            for (int a = x; a < x + sx; a++) for (int b = z; b < z + sz; b++) {
                bp.set(a, y + 1, b, body);
                if (tall) bp.set(a, y + 2, b, body);
                bp.set(a, y + (tall ? 3 : 2), b, vent);
            }
            mark(used, x - 1, z - 1, x + sx, z + sz);
            k++;
        }
        int roll = rnd.nextInt(100);
        if (!trad && !fantasy && p.type != BuildingType.SKYSCRAPER) {
            if (roll < 35) {
                for (int x = 0; x < W; x++) for (int z = 0; z < D; z++)
                    if (!used[x][z] && z % 3 != 0) bp.set(x, y + 1, z, st(Blocks.DAYLIGHT_DETECTOR));
            } else if (roll < 65 || s.greenery == Greenery.RICH) {
                for (int x = 0; x < W; x++) for (int z = 0; z < D; z++) {
                    if (used[x][z]) continue;
                    bp.set(x, y, z, st(Blocks.MOSS_BLOCK));
                    int h = hash(x, z, 99) % 100;
                    if (h < 10) bp.set(x, y + 1, z, st(h < 5 ? Blocks.AZALEA : Blocks.FLOWERING_AZALEA));
                    else if (h < 16) bp.set(x, y + 1, z, Blocks.OAK_LEAVES.defaultBlockState().setValue(BlockStateProperties.PERSISTENT, true));
                }
            }
        } else if (style == StyleMode.CLASSIC && (p.type == BuildingType.MIDRISE || p.type == BuildingType.HIGHRISE || p.type == BuildingType.OFFICE) && roll < 60) {
            for (int tries = 0; tries < 30; tries++) {
                int x = rnd.nextInt(W), z = rnd.nextInt(D);
                if (!areaFree(used, x - 1, z - 1, x + 3, z + 3)) continue;
                waterTank(x, y + 1, z);
                mark(used, x - 1, z - 1, x + 3, z + 3);
                break;
            }
        } else if (antique && s.greenery != Greenery.NONE) { // сад на крыше
            for (int x = 0; x < W; x++) for (int z = 0; z < D; z++)
                if (!used[x][z] && hash(x, z, 77) % 7 == 0) bp.set(x, y + 1, z, plantPot(x * 31 + z));
        }
        if (p.type == BuildingType.COMMERCIAL || p.type == BuildingType.CIVIC) {
            for (int x = 0; x < W; x++) for (int z = 0; z < D; z++)
                if (!used[x][z] && x % 6 >= 2 && x % 6 <= 3 && z % 6 >= 2 && z % 6 <= 3 && bp.get(x, y + 1, z) == null) bp.set(x, y, z, glass);
        }
    }

    private void spire(int x, int y, int z) {
        int h = 6 + rnd.nextInt(16);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) bp.set(x + dx, y, z + dz, frame);
        for (int k = 1; k <= h; k++) bp.set(x, y + k, z, st(fantasy ? Blocks.END_ROD : Blocks.IRON_BARS));
        bp.set(x, y + h + 1, z, fantasy ? neon : st(Blocks.LIGHTNING_ROD));
    }

    private void waterTank(int x, int y, int z) {
        BlockState fence = st(Blocks.SPRUCE_FENCE), plank = st(Blocks.SPRUCE_PLANKS);
        for (int dx = 0; dx <= 2; dx += 2) for (int dz = 0; dz <= 2; dz += 2) {
            bp.set(x + dx, y, z + dz, fence);
            bp.set(x + dx, y + 1, z + dz, fence);
        }
        for (int yy = y + 2; yy <= y + 4; yy++)
            for (int dx = 0; dx <= 2; dx++) for (int dz = 0; dz <= 2; dz++)
                if (!(dx != 1 && dz != 1)) bp.set(x + dx, yy, z + dz, plank);
        bp.set(x + 1, y + 5, z + 1, slab(Blocks.SPRUCE_SLAB, SlabType.BOTTOM));
        bp.set(x + 1, y + 5, z, stairs(Blocks.SPRUCE_STAIRS, Direction.SOUTH, Half.BOTTOM));
        bp.set(x + 1, y + 5, z + 2, stairs(Blocks.SPRUCE_STAIRS, Direction.NORTH, Half.BOTTOM));
        bp.set(x, y + 5, z + 1, stairs(Blocks.SPRUCE_STAIRS, Direction.EAST, Half.BOTTOM));
        bp.set(x + 2, y + 5, z + 1, stairs(Blocks.SPRUCE_STAIRS, Direction.WEST, Half.BOTTOM));
    }

    private boolean nearEdge(boolean[][] m, int x, int z) {
        for (Direction d : HORIZ) if (isWall(m, x + d.getStepX(), z + d.getStepZ())) return true;
        return false;
    }

    private boolean areaFree(boolean[][] used, int x0, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
            if (x < 0 || z < 0 || x >= W || z >= D || used[x][z]) return false;
        }
        return true;
    }

    private void mark(boolean[][] used, int x0, int z0, int x1, int z1) {
        for (int x = Math.max(0, x0); x <= Math.min(W - 1, x1); x++)
            for (int z = Math.max(0, z0); z <= Math.min(D - 1, z1); z++) used[x][z] = true;
    }

    /** «Живая» фактура: часть блоков стен заменяется на похожие (сколы, мох, оттенки). */
    private void weathering() {
        if (s.texture <= 0) return;
        Block[] w1 = Palette.TEXTURE.get(pal.wall), w2 = Palette.TEXTURE.get(pal.base), w3 = Palette.TEXTURE.get(pal.wall2);
        if (w1 == null && w2 == null && w3 == null) return;
        for (int y = 0; y < bp.h; y++)
            for (int z = bp.minZ(); z <= bp.maxZ(); z++)
                for (int x = bp.minX(); x <= bp.maxX(); x++) {
                    BlockState st = bp.get(x, y, z);
                    if (st == null) continue;
                    Block[] v = st.is(pal.wall) ? w1 : st.is(pal.base) ? w2 : st.is(pal.wall2) ? w3 : null;
                    if (v == null) continue;
                    int h = hash(x * 7 + y, z, 313) % 100;
                    if (h < s.texture) bp.set(x, y, z, v[h % v.length].defaultBlockState());
                }
    }

    // =====================================================================
    // Утилиты
    // =====================================================================
    /** Разбивка фасадной линии на симметричные пролёты: {тип, номер пролёта, число пролётов, позиция внутри}. */
    static int[] bay(int pos, int len, int ww, int pw) {
        if (pos <= 0 || pos >= len - 1) return new int[]{SOLID, -1, 0, 0};
        int inner = len - 2, q = pos - 1, unit = ww + pw;
        int nb = (inner + pw) / unit;
        if (nb < 1) return new int[]{SOLID, -1, 0, 0};
        int used = nb * ww + (nb - 1) * pw;
        int pad = (inner - used) / 2;
        if (q < pad || q >= pad + used) return new int[]{SOLID, -1, nb, 0};
        int rr = (q - pad) % unit, bi = (q - pad) / unit;
        return rr < ww ? new int[]{WIN, bi, nb, rr} : new int[]{PIER, bi, nb, rr - ww};
    }

    private int kind(int pos, int len) { return bay(pos, len, ww, pw)[0]; }
    private int kind(int pos, int len, int w, int pp) { return bay(pos, len, w, pp)[0]; }

    boolean in(boolean[][] m, int x, int z) {
        return m != null && x >= 0 && z >= 0 && x < W && z < D && m[x][z];
    }

    boolean isWall(boolean[][] m, int x, int z) {
        if (!in(m, x, z)) return false;
        for (Direction d : HORIZ) if (!in(m, x + d.getStepX(), z + d.getStepZ())) return true;
        return false;
    }

    private boolean isEdge(boolean[][] m, int x, int z, Direction n) {
        return in(m, x, z) && !in(m, x + n.getStepX(), z + n.getStepZ());
    }

    List<Direction> outs(boolean[][] m, int x, int z) {
        List<Direction> l = new ArrayList<>(2);
        for (Direction d : HORIZ) if (!in(m, x + d.getStepX(), z + d.getStepZ())) l.add(d);
        return l;
    }

    private void setO(int x, int z, Direction n, int k, int y, BlockState st) {
        bp.set(x + n.getStepX() * k, y, z + n.getStepZ() * k, st);
    }

    int hash(int a, int b, int c) {
        long h = p.seed ^ (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL) ^ (c * 0x165667B19E3779F9L);
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return (int) (h & 0x7fffffff);
    }

    static final Block[] POTS = {Blocks.POTTED_RED_TULIP, Blocks.POTTED_POPPY, Blocks.POTTED_FERN, Blocks.POTTED_AZURE_BLUET,
            Blocks.POTTED_ALLIUM, Blocks.POTTED_ORANGE_TULIP, Blocks.POTTED_AZALEA, Blocks.POTTED_CORNFLOWER};

    BlockState plantPot(int k) { return POTS[Math.floorMod(k, POTS.length)].defaultBlockState(); }

    static BlockState st(Block b) { return b.defaultBlockState(); }

    static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> prop, T v) {
        return s.hasProperty(prop) ? s.setValue(prop, v) : s;
    }

    static BlockState stairs(Block b, Direction facing, Half half) {
        BlockState s = b.defaultBlockState();
        s = with(s, BlockStateProperties.HORIZONTAL_FACING, facing);
        return with(s, BlockStateProperties.HALF, half);
    }

    static BlockState slab(Block b, SlabType t) {
        return with(b.defaultBlockState(), BlockStateProperties.SLAB_TYPE, t);
    }

    static BlockState trapdoor(Block b, Direction facing, boolean open, Half half) {
        BlockState s = with(b.defaultBlockState(), BlockStateProperties.HORIZONTAL_FACING, facing);
        s = with(s, BlockStateProperties.OPEN, open);
        return with(s, BlockStateProperties.HALF, half);
    }

    static BlockState ladder(Direction facing) {
        return with(Blocks.LADDER.defaultBlockState(), BlockStateProperties.HORIZONTAL_FACING, facing);
    }

    void placeDoor(Block door, int x, int y, int z, Direction facing, DoorHingeSide hinge) {
        BlockState d = with(door.defaultBlockState(), BlockStateProperties.HORIZONTAL_FACING, facing);
        d = with(d, BlockStateProperties.DOOR_HINGE, hinge);
        bp.set(x, y, z, with(d, BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        bp.set(x, y + 1, z, with(d, BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
    }
}
