package com.citygen.gen;

import com.citygen.gen.GenSettings.*;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

import java.util.*;

import static com.citygen.gen.BuildingGenerator.*;

/**
 * Интерьеры: планировка (BSP-деление на комнаты, двери, проверка связности), свет по комнатам
 * и мебель из ванильных блоков: квартиры, офисы, лобби, магазины, кафе, супермаркеты, музеи, библиотеки.
 */
class InteriorGenerator {
    private static final int OUT = 0, FLOOR = 1, WALLC = 2, DOOR = 3;

    enum Usage { RESIDENTIAL, OFFICE, SHOP, LOBBY, MALL, HALL }

    private final BuildingGenerator g;
    private final Blueprint bp;
    private final Random rnd;
    private final int W, D;
    private final boolean furnish;
    private final Kit kit;

    // текущий этаж
    private int fi, y0, h;
    private int[][] state;
    private boolean[][] occ;
    private boolean[][] clear;

    private static final class Room {
        final List<int[]> cells = new ArrayList<>();
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        void add(int x, int z) { cells.add(new int[]{x, z}); x0 = Math.min(x0, x); z0 = Math.min(z0, z); x1 = Math.max(x1, x); z1 = Math.max(z1, z); }
        int area() { return cells.size(); }
        int w() { return x1 - x0 + 1; }
        int d() { return z1 - z0 + 1; }
    }

    /** Набор мебели под стиль. */
    private static final class Kit {
        Block planks, slab, stairs, fence, trapdoor, door, carpet1, carpet2, bed1, bed2, sofa, chair, officeChair, table, counter;
        boolean endRodLamp;
    }

    InteriorGenerator(BuildingGenerator g) {
        this.g = g;
        this.bp = g.bp;
        this.rnd = new Random(g.p.seed ^ 0x1D7E810L);
        this.W = g.W; this.D = g.D;
        this.furnish = g.interior == Interior.FURNISHED;
        this.kit = kit(g.style);
    }

    private static Kit kit(StyleMode st) {
        Kit k = new Kit();
        switch (st) {
            case CLASSIC -> {
                k.planks = Blocks.DARK_OAK_PLANKS; k.slab = Blocks.DARK_OAK_SLAB; k.stairs = Blocks.DARK_OAK_STAIRS;
                k.fence = Blocks.DARK_OAK_FENCE; k.trapdoor = Blocks.DARK_OAK_TRAPDOOR; k.door = Blocks.DARK_OAK_DOOR;
                k.carpet1 = Blocks.RED_CARPET; k.carpet2 = Blocks.BROWN_CARPET; k.bed1 = Blocks.RED_BED; k.bed2 = Blocks.BLUE_BED;
                k.sofa = Blocks.DARK_OAK_STAIRS; k.chair = Blocks.SPRUCE_STAIRS; k.officeChair = Blocks.SPRUCE_STAIRS;
                k.table = Blocks.DARK_OAK_SLAB; k.counter = Blocks.POLISHED_ANDESITE;
            }
            case ANTIQUE -> {
                k.planks = Blocks.SPRUCE_PLANKS; k.slab = Blocks.SPRUCE_SLAB; k.stairs = Blocks.SPRUCE_STAIRS;
                k.fence = Blocks.SPRUCE_FENCE; k.trapdoor = Blocks.SPRUCE_TRAPDOOR; k.door = Blocks.SPRUCE_DOOR;
                k.carpet1 = Blocks.ORANGE_CARPET; k.carpet2 = Blocks.YELLOW_CARPET; k.bed1 = Blocks.ORANGE_BED; k.bed2 = Blocks.YELLOW_BED;
                k.sofa = Blocks.SMOOTH_SANDSTONE_STAIRS; k.chair = Blocks.SPRUCE_STAIRS; k.officeChair = Blocks.SPRUCE_STAIRS;
                k.table = Blocks.QUARTZ_SLAB; k.counter = Blocks.QUARTZ_BLOCK;
            }
            case FANTASY -> {
                k.planks = Blocks.WARPED_PLANKS; k.slab = Blocks.WARPED_SLAB; k.stairs = Blocks.WARPED_STAIRS;
                k.fence = Blocks.WARPED_FENCE; k.trapdoor = Blocks.WARPED_TRAPDOOR; k.door = Blocks.WARPED_DOOR;
                k.carpet1 = Blocks.MAGENTA_CARPET; k.carpet2 = Blocks.CYAN_CARPET; k.bed1 = Blocks.PURPLE_BED; k.bed2 = Blocks.CYAN_BED;
                k.sofa = Blocks.PURPUR_STAIRS; k.chair = Blocks.WARPED_STAIRS; k.officeChair = Blocks.POLISHED_BLACKSTONE_STAIRS;
                k.table = Blocks.PURPUR_SLAB; k.counter = Blocks.PURPUR_BLOCK; k.endRodLamp = true;
            }
            default -> {
                k.planks = Blocks.BIRCH_PLANKS; k.slab = Blocks.BIRCH_SLAB; k.stairs = Blocks.BIRCH_STAIRS;
                k.fence = Blocks.BIRCH_FENCE; k.trapdoor = Blocks.BIRCH_TRAPDOOR; k.door = Blocks.BIRCH_DOOR;
                k.carpet1 = Blocks.LIGHT_GRAY_CARPET; k.carpet2 = Blocks.CYAN_CARPET; k.bed1 = Blocks.WHITE_BED; k.bed2 = Blocks.LIGHT_GRAY_BED;
                k.sofa = Blocks.POLISHED_DEEPSLATE_STAIRS; k.chair = Blocks.BIRCH_STAIRS; k.officeChair = Blocks.POLISHED_BLACKSTONE_STAIRS;
                k.table = Blocks.SMOOTH_QUARTZ_SLAB; k.counter = Blocks.SMOOTH_QUARTZ; k.endRodLamp = true;
            }
        }
        return k;
    }

    void run() {
        for (int i = 0; i < g.N; i++) {
            fi = i; y0 = g.fy[i]; h = g.fh[i];
            if (h < 3) continue;
            Usage u = usage(i);
            state = new int[W][D];
            occ = new boolean[W][D];
            clear = new boolean[W][D];
            boolean any = false;
            for (int x = 0; x < W; x++)
                for (int z = 0; z < D; z++)
                    if (g.in(g.mask[i], x, z) && !g.isWall(g.mask[i], x, z) && !g.core[x][z] && !g.reserved[i][x][z]) {
                        state[x][z] = FLOOR; any = true;
                    }
            if (!any) continue;
            for (int[] k : g.keepClear) if (k[0] == i && k[1] >= 0 && k[2] >= 0 && k[1] < W && k[2] < D) clear[k[1]][k[2]] = true;

            partition(u);
            connect();
            writePartitions(u);
            List<Room> rooms = rooms();
            lights(rooms);
            if (furnish) furnishFloor(u, rooms);
        }
    }

    private Usage usage(int i) {
        BuildingType t = g.p.type;
        if (i == 0) {
            if (g.p.ground == GroundFloor.SHOPS) return t == BuildingType.COMMERCIAL ? Usage.MALL : Usage.SHOP;
            if (g.p.ground == GroundFloor.LOBBY) return t == BuildingType.CIVIC ? Usage.HALL : Usage.LOBBY;
        }
        return switch (t) {
            case SKYSCRAPER, OFFICE -> Usage.OFFICE;
            case COMMERCIAL -> Usage.MALL;
            case CIVIC -> Usage.HALL;
            default -> Usage.RESIDENTIAL;
        };
    }

    // =====================================================================
    // Планировка
    // =====================================================================
    private void partition(Usage u) {
        int x0 = W, z0 = D, x1 = -1, z1 = -1;
        for (int x = 0; x < W; x++) for (int z = 0; z < D; z++) if (state[x][z] == FLOOR) {
            x0 = Math.min(x0, x); z0 = Math.min(z0, z); x1 = Math.max(x1, x); z1 = Math.max(z1, z);
        }
        switch (u) {
            case RESIDENTIAL -> bsp(x0, z0, x1, z1, 3, g.p.type.small() ? 6 : 7, 0);
            case OFFICE -> bsp(x0, z0, x1, z1, 4, 13, 0);
            case HALL -> { if ((x1 - x0) > 24 || (z1 - z0) > 24) bsp(x0, z0, x1, z1, 7, 18, 0); }
            case SHOP -> {
                int x = x0 + 6 + rnd.nextInt(3);
                while (x < x1 - 5) {
                    wallLine(true, x, z0, z1);
                    x += 7 + rnd.nextInt(4);
                }
            }
            case MALL -> { if ((x1 - x0) > 30) wallLine(true, x0 + (x1 - x0) / 3, z0, z1); }
            default -> {}
        }
    }

    private void bsp(int x0, int z0, int x1, int z1, int minR, int maxR, int depth) {
        int w = x1 - x0 + 1, d = z1 - z0 + 1;
        boolean canX = w >= 2 * minR + 1, canZ = d >= 2 * minR + 1;
        boolean want = w > maxR || d > maxR || (depth < 1 && w * d > minR * minR * 3 && rnd.nextFloat() < 0.6f);
        if (!want || (!canX && !canZ) || depth > 8) return;
        boolean splitX = canX && (!canZ || w > d || (w == d && rnd.nextBoolean()));
        if (splitX) {
            int sx = x0 + minR + rnd.nextInt(w - 2 * minR);
            wallLine(true, sx, z0, z1);
            bsp(x0, z0, sx - 1, z1, minR, maxR, depth + 1);
            bsp(sx + 1, z0, x1, z1, minR, maxR, depth + 1);
        } else {
            int sz = z0 + minR + rnd.nextInt(d - 2 * minR);
            wallLine(false, sz, x0, x1);
            bsp(x0, z0, x1, sz - 1, minR, maxR, depth + 1);
            bsp(x0, sz + 1, x1, z1, minR, maxR, depth + 1);
        }
    }

    /** Стена-перегородка: alongZ=true — линия x=c от a до b. Дверь — в случайном допустимом месте. */
    private void wallLine(boolean alongZ, int c, int a, int b) {
        List<int[]> cells = new ArrayList<>();
        for (int t = a; t <= b; t++) {
            int x = alongZ ? c : t, z = alongZ ? t : c;
            if (x < 0 || z < 0 || x >= W || z >= D || state[x][z] != FLOOR) continue;
            state[x][z] = WALLC;
            cells.add(new int[]{x, z});
        }
        Collections.shuffle(cells, rnd);
        for (int[] cc : cells) {
            int x = cc[0], z = cc[1];
            int ax = alongZ ? x - 1 : x, az = alongZ ? z : z - 1, bx = alongZ ? x + 1 : x, bz = alongZ ? z : z + 1;
            if (st(ax, az) == FLOOR && st(bx, bz) == FLOOR) { state[x][z] = DOOR; break; }
        }
    }

    private int st(int x, int z) { return (x < 0 || z < 0 || x >= W || z >= D) ? OUT : state[x][z]; }

    /** Гарантирует, что из любой комнаты можно дойти до входа или лестницы. */
    private void connect() {
        List<int[]> seeds = new ArrayList<>();
        for (int x = 0; x < W; x++) for (int z = 0; z < D; z++) {
            if (state[x][z] != FLOOR) continue;
            boolean seed = clear[x][z];
            for (Direction dd : HORIZ) {
                int ax = x + dd.getStepX(), az = z + dd.getStepZ();
                if (ax >= 0 && az >= 0 && ax < W && az < D && g.reserved[fi][ax][az]) seed = true;
            }
            if (seed) seeds.add(new int[]{x, z});
        }
        if (seeds.isEmpty()) {
            for (int x = 0; x < W && seeds.isEmpty(); x++) for (int z = 0; z < D && seeds.isEmpty(); z++)
                if (state[x][z] == FLOOR) seeds.add(new int[]{x, z});
        }
        for (int guard = 0; guard < 400; guard++) {
            boolean[][] reach = flood(seeds);
            int[] best = null;
            boolean unreached = false;
            for (int x = 0; x < W && best == null; x++)
                for (int z = 0; z < D && best == null; z++) {
                    if (state[x][z] == FLOOR && !reach[x][z]) unreached = true;
                    if (state[x][z] != WALLC) continue;
                    for (int ax = 0; ax < 2 && best == null; ax++) {
                        int dx = ax == 0 ? 1 : 0, dz = ax == 0 ? 0 : 1;
                        int a = st(x - dx, z - dz), b = st(x + dx, z + dz);
                        if (a == OUT || b == OUT || a == WALLC || b == WALLC) continue;
                        boolean ra = reach[x - dx][z - dz], rb = reach[x + dx][z + dz];
                        if (ra != rb) best = new int[]{x, z};
                    }
                }
            if (!unreached) {
                boolean any = false;
                for (int x = 0; x < W && !any; x++) for (int z = 0; z < D && !any; z++) if (state[x][z] == FLOOR && !reach[x][z]) any = true;
                if (!any) return;
            }
            if (best == null) return;
            state[best[0]][best[1]] = DOOR;
        }
    }

    private boolean[][] flood(List<int[]> seeds) {
        boolean[][] r = new boolean[W][D];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        for (int[] s : seeds) if (!r[s[0]][s[1]]) { r[s[0]][s[1]] = true; q.add(s); }
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (Direction dd : HORIZ) {
                int x = c[0] + dd.getStepX(), z = c[1] + dd.getStepZ();
                int s = st(x, z);
                if ((s == FLOOR || s == DOOR) && !r[x][z]) { r[x][z] = true; q.add(new int[]{x, z}); }
            }
        }
        return r;
    }

    private void writePartitions(Usage u) {
        BlockState wallM = BuildingGenerator.st(g.pal.partition);
        boolean glassy = u == Usage.OFFICE || u == Usage.MALL;
        BlockState glassM = BuildingGenerator.st(g.fantasy ? g.pal.pane : Blocks.GLASS_PANE);
        Block door = u == Usage.RESIDENTIAL ? kit.door : null;
        for (int x = 0; x < W; x++)
            for (int z = 0; z < D; z++) {
                int s = state[x][z];
                if (s == WALLC) {
                    boolean gl = glassy && g.hash(x / 6, z / 6, fi * 3 + 11) % 2 == 0;
                    for (int r = 1; r < h; r++) bp.set(x, y0 + r, z, gl && r < h - 1 ? glassM : wallM);
                } else if (s == DOOR) {
                    boolean ew = st(x - 1, z) == FLOOR || st(x + 1, z) == FLOOR || st(x - 1, z) == DOOR || st(x + 1, z) == DOOR;
                    Direction f = ew ? Direction.EAST : Direction.SOUTH;
                    if (door != null) g.placeDoor(door, x, y0 + 1, z, f, DoorHingeSide.LEFT);
                    else { bp.set(x, y0 + 1, z, AIR); bp.set(x, y0 + 2, z, AIR); }
                    for (int r = 3; r < h; r++) bp.set(x, y0 + r, z, wallM);
                    for (Direction dd : HORIZ) {
                        int ax = x + dd.getStepX(), az = z + dd.getStepZ();
                        if (st(ax, az) == FLOOR) clear[ax][az] = true;
                    }
                }
            }
    }

    private List<Room> rooms() {
        List<Room> out = new ArrayList<>();
        boolean[][] seen = new boolean[W][D];
        for (int x = 0; x < W; x++)
            for (int z = 0; z < D; z++) {
                if (state[x][z] != FLOOR || seen[x][z]) continue;
                Room r = new Room();
                ArrayDeque<int[]> q = new ArrayDeque<>();
                q.add(new int[]{x, z}); seen[x][z] = true;
                while (!q.isEmpty()) {
                    int[] c = q.poll();
                    r.add(c[0], c[1]);
                    for (Direction dd : HORIZ) {
                        int ax = c[0] + dd.getStepX(), az = c[1] + dd.getStepZ();
                        if (st(ax, az) == FLOOR && !seen[ax][az]) { seen[ax][az] = true; q.add(new int[]{ax, az}); }
                    }
                }
                out.add(r);
            }
        out.sort((a, b) -> b.area() - a.area());
        return out;
    }

    private void lights(List<Room> rooms) {
        int idx = 0;
        for (Room r : rooms) {
            idx++;
            if (g.hash(fi, idx, 4242) % 100 >= g.s.litPercent) continue;
            List<int[]> spots = new ArrayList<>();
            if (r.area() > 36) {
                for (int[] c : r.cells) if ((c[0] - r.x0) % 5 == 2 && (c[1] - r.z0) % 5 == 2) spots.add(c);
            }
            if (spots.isEmpty()) {
                int cx = (r.x0 + r.x1) / 2, cz = (r.z0 + r.z1) / 2;
                int[] best = r.cells.get(0);
                int bd = Integer.MAX_VALUE;
                for (int[] c : r.cells) { int dd = Math.abs(c[0] - cx) + Math.abs(c[1] - cz); if (dd < bd) { bd = dd; best = c; } }
                spots.add(best);
            }
            for (int[] c : spots) {
                BlockState ceil = bp.get(c[0], y0 + h, c[1]);
                if (ceil == null || ceil.isAir() || !ceil.canOcclude()) continue;
                if (g.trad) bp.set(c[0], y0 + h - 1, c[1], with(BuildingGenerator.st(Blocks.LANTERN), BlockStateProperties.HANGING, true));
                else bp.set(c[0], y0 + h, c[1], g.light);
            }
        }
    }

    // =====================================================================
    // Мебель
    // =====================================================================
    private boolean free(int x, int z) {
        return x >= 0 && z >= 0 && x < W && z < D && state[x][z] == FLOOR && !occ[x][z] && !clear[x][z];
    }

    private boolean solid(int x, int z) {
        if (x < 0 || z < 0 || x >= W || z >= D) return false;
        if (state[x][z] == WALLC) return true;
        if (state[x][z] == DOOR || state[x][z] == FLOOR) return false;
        if (g.reserved[fi][x][z]) return false;
        return g.core[x][z] || g.isWall(g.mask[fi], x, z);
    }

    private boolean opaque(int x, int y, int z) {
        BlockState s = bp.get(x, y, z);
        return s != null && s.canOcclude();
    }

    private void set(int x, int dy, int z, BlockState s) { bp.set(x, y0 + dy, z, s); }

    private interface Placer { void place(int x, int z, Direction into, Direction along); }

    /** Ставит предмет вплотную к стене: fa клеток вдоль стены, fb — вглубь комнаты. */
    private boolean wallItem(Room r, int fa, int fb, boolean tall, Placer pl) {
        List<int[]> cand = new ArrayList<>(r.cells);
        Collections.shuffle(cand, rnd);
        for (int[] c : cand) {
            for (Direction into : HORIZ) {
                Direction along = into.getClockWise();
                boolean ok = true;
                for (int a = 0; a < fa && ok; a++)
                    for (int b = 0; b < fb && ok; b++) {
                        int x = c[0] + along.getStepX() * a + into.getStepX() * b, z = c[1] + along.getStepZ() * a + into.getStepZ() * b;
                        if (!free(x, z)) ok = false;
                        if (b == 0 && ok) {
                            int wx = x - into.getStepX(), wz = z - into.getStepZ();
                            if (!solid(wx, wz)) ok = false;
                            else if (tall && (!opaque(wx, y0 + 1, wz) || !opaque(wx, y0 + 2, wz))) ok = false;
                        }
                    }
                if (!ok) continue;
                // не загораживаем проход: клетка перед предметом должна остаться свободной
                int fx = c[0] + into.getStepX() * fb, fz = c[1] + into.getStepZ() * fb;
                if (st(fx, fz) != FLOOR && fb < 2) continue;
                pl.place(c[0], c[1], into, along);
                for (int a = 0; a < fa; a++)
                    for (int b = 0; b < fb; b++)
                        occ[c[0] + along.getStepX() * a + into.getStepX() * b][c[1] + along.getStepZ() * a + into.getStepZ() * b] = true;
                return true;
            }
        }
        return false;
    }

    /** Предмет в середине комнаты с проходом вокруг. */
    private boolean centerItem(Room r, int fw, int fd, Placer pl) {
        List<int[]> cand = new ArrayList<>(r.cells);
        Collections.shuffle(cand, rnd);
        for (int[] c : cand) {
            boolean ok = true;
            for (int a = -1; a <= fw && ok; a++)
                for (int b = -1; b <= fd && ok; b++) {
                    int x = c[0] + a, z = c[1] + b;
                    boolean inner = a >= 0 && a < fw && b >= 0 && b < fd;
                    if (inner ? !free(x, z) : (st(x, z) != FLOOR || occ[x][z])) ok = false;
                }
            if (!ok) continue;
            pl.place(c[0], c[1], Direction.SOUTH, Direction.EAST);
            for (int a = 0; a < fw; a++) for (int b = 0; b < fd; b++) occ[c[0] + a][c[1] + b] = true;
            return true;
        }
        return false;
    }

    private void furnishFloor(Usage u, List<Room> rooms) {
        switch (u) {
            case RESIDENTIAL -> residential(rooms);
            case OFFICE -> { for (Room r : rooms) office(r); }
            case LOBBY -> { for (Room r : rooms) lobby(r); }
            case SHOP -> { int k = 0; for (Room r : rooms) shop(r, g.hash(fi, k++, 909) % 6); }
            case MALL -> { for (Room r : rooms) if (r.area() > 60) supermarket(r); else shop(r, g.hash(fi, r.x0, 919) % 6); }
            case HALL -> { for (Room r : rooms) hall(r); }
        }
    }

    // ---------- жильё ----------
    private void residential(List<Room> rooms) {
        boolean kitchen = false, living = false;
        if (rooms.size() == 1) { studio(rooms.get(0)); return; }
        for (Room r : rooms) {
            int a = r.area();
            if (Math.min(r.w(), r.d()) < 2) continue;
            if (!living && a >= 12) { livingRoom(r); living = true; }
            else if (a <= 7) bathroom(r);
            else if (!kitchen && a >= 8) { kitchenRoom(r); kitchen = true; }
            else bedroom(r);
        }
    }

    private void studio(Room r) {
        bed(r); kitchenCounter(r, 3); sofaSet(r); plant(r); lamp(r);
    }

    private void livingRoom(Room r) {
        sofaSet(r);
        tv(r);
        bookshelf(r);
        if (rnd.nextBoolean()) wallItem(r, 1, 1, false, (x, z, in, al) -> set(x, 1, z, BuildingGenerator.st(Blocks.JUKEBOX)));
        plant(r); plant(r); lamp(r);
        rug(r);
    }

    private void bedroom(Room r) {
        bed(r);
        wallItem(r, 1, 1, true, (x, z, in, al) -> {
            set(x, 1, z, with(BuildingGenerator.st(Blocks.BARREL), BlockStateProperties.FACING, in));
            set(x, 2, z, with(BuildingGenerator.st(Blocks.BARREL), BlockStateProperties.FACING, in));
        });
        if (rnd.nextBoolean()) desk(r);
        plant(r);
        rug(r);
    }

    private void kitchenRoom(Room r) {
        kitchenCounter(r, Math.min(4, Math.max(r.w(), r.d()) - 1));
        wallItem(r, 1, 1, true, (x, z, in, al) -> { set(x, 1, z, BuildingGenerator.st(Blocks.IRON_BLOCK)); set(x, 2, z, BuildingGenerator.st(Blocks.IRON_BLOCK)); });
        diningTable(r);
        plant(r);
    }

    private void bathroom(Room r) {
        wallItem(r, 1, 1, false, (x, z, in, al) -> set(x, 1, z, BuildingGenerator.st(Blocks.CAULDRON)));
        wallItem(r, 1, 1, false, (x, z, in, al) -> set(x, 1, z, stairs(Blocks.SMOOTH_QUARTZ_STAIRS, in.getOpposite(), Half.BOTTOM)));
        wallItem(r, 2, 1, false, (x, z, in, al) -> {
            set(x, 1, z, slab(Blocks.SMOOTH_QUARTZ_SLAB, SlabType.BOTTOM));
            set(x + al.getStepX(), 1, z + al.getStepZ(), slab(Blocks.SMOOTH_QUARTZ_SLAB, SlabType.BOTTOM));
        });
    }

    private void bed(Room r) {
        Block b = rnd.nextBoolean() ? kit.bed1 : kit.bed2;
        boolean placed = wallItem(r, 1, 2, false, (x, z, in, al) -> {
            BlockState head = with(with(BuildingGenerator.st(b), BlockStateProperties.HORIZONTAL_FACING, in.getOpposite()),
                    BlockStateProperties.BED_PART, BedPart.HEAD);
            BlockState foot = with(head, BlockStateProperties.BED_PART, BedPart.FOOT);
            set(x, 1, z, head);
            set(x + in.getStepX(), 1, z + in.getStepZ(), foot);
            int sx = x + al.getStepX(), sz = z + al.getStepZ();
            if (free(sx, sz)) {
                set(sx, 1, sz, with(BuildingGenerator.st(Blocks.BARREL), BlockStateProperties.FACING, Direction.UP));
                set(sx, 2, sz, lampTop());
                occ[sx][sz] = true;
            }
        });
        if (!placed) centerItem(r, 1, 2, (x, z, in, al) -> {
            BlockState head = with(with(BuildingGenerator.st(b), BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH),
                    BlockStateProperties.BED_PART, BedPart.HEAD);
            set(x, 1, z, head);
            set(x, 1, z + 1, with(head, BlockStateProperties.BED_PART, BedPart.FOOT));
        });
    }

    private BlockState lampTop() {
        return kit.endRodLamp ? BuildingGenerator.st(Blocks.END_ROD) : BuildingGenerator.st(Blocks.LANTERN);
    }

    private void sofaSet(Room r) {
        wallItem(r, 4, 1, false, (x, z, in, al) -> {
            set(x, 1, z, trapdoor(kit.trapdoor, al.getOpposite(), true, Half.BOTTOM));
            for (int a = 1; a <= 2; a++)
                set(x + al.getStepX() * a, 1, z + al.getStepZ() * a, stairs(kit.sofa, in.getOpposite(), Half.BOTTOM));
            set(x + al.getStepX() * 3, 1, z + al.getStepZ() * 3, trapdoor(kit.trapdoor, al, true, Half.BOTTOM));
            for (int a = 1; a <= 2; a++) {
                int tx = x + al.getStepX() * a + in.getStepX() * 2, tz = z + al.getStepZ() * a + in.getStepZ() * 2;
                if (free(tx, tz) && st(tx + in.getStepX(), tz + in.getStepZ()) == FLOOR) {
                    set(tx, 1, tz, trapdoor(kit.trapdoor, in, false, Half.TOP));
                    occ[tx][tz] = true;
                }
            }
        });
    }

    private void tv(Room r) {
        wallItem(r, 2, 1, false, (x, z, in, al) -> {
            for (int a = 0; a < 2; a++) {
                int tx = x + al.getStepX() * a, tz = z + al.getStepZ() * a;
                set(tx, 1, tz, slab(kit.slab, SlabType.TOP));
                set(tx, 2, tz, BuildingGenerator.st(Blocks.BLACK_STAINED_GLASS_PANE));
            }
        });
    }

    private void bookshelf(Room r) {
        wallItem(r, 2, 1, true, (x, z, in, al) -> {
            for (int a = 0; a < 2; a++) {
                set(x + al.getStepX() * a, 1, z + al.getStepZ() * a, BuildingGenerator.st(Blocks.BOOKSHELF));
                set(x + al.getStepX() * a, 2, z + al.getStepZ() * a, BuildingGenerator.st(Blocks.BOOKSHELF));
            }
        });
    }

    private void plant(Room r) {
        Block[] pl = g.fantasy ? new Block[]{Blocks.POTTED_WARPED_FUNGUS, Blocks.POTTED_CRIMSON_FUNGUS, Blocks.AMETHYST_CLUSTER}
                : new Block[]{Blocks.POTTED_FERN, Blocks.POTTED_AZALEA, Blocks.POTTED_BAMBOO, Blocks.POTTED_FLOWERING_AZALEA};
        Block b = pl[rnd.nextInt(pl.length)];
        wallItem(r, 1, 1, false, (x, z, in, al) -> set(x, 1, z, BuildingGenerator.st(b)));
    }

    private void lamp(Room r) {
        wallItem(r, 1, 1, false, (x, z, in, al) -> {
            if (kit.endRodLamp) { set(x, 1, z, BuildingGenerator.st(Blocks.END_ROD)); set(x, 2, z, BuildingGenerator.st(Blocks.END_ROD)); }
            else { set(x, 1, z, BuildingGenerator.st(kit.fence)); set(x, 2, z, BuildingGenerator.st(Blocks.LANTERN)); }
        });
    }

    private void rug(Room r) {
        int rw = Math.min(3, r.w() - 2), rd = Math.min(3, r.d() - 2);
        if (rw < 2 || rd < 2) return;
        Block c = rnd.nextBoolean() ? kit.carpet1 : kit.carpet2;
        centerItem(r, rw, rd, (x, z, in, al) -> {
            for (int a = 0; a < rw; a++) for (int b = 0; b < rd; b++) set(x + a, 1, z + b, BuildingGenerator.st(c));
        });
        // ковёр не мешает ходить
        for (int[] cc : r.cells) if (occ[cc[0]][cc[1]] && bp.get(cc[0], y0 + 1, cc[1]) != null && bp.get(cc[0], y0 + 1, cc[1]).is(c)) occ[cc[0]][cc[1]] = false;
    }

    private void desk(Room r) {
        wallItem(r, 1, 2, false, (x, z, in, al) -> {
            set(x, 1, z, slab(kit.table, SlabType.TOP));
            set(x, 2, z, BuildingGenerator.st(rnd.nextBoolean() ? Blocks.BLACK_STAINED_GLASS_PANE : Blocks.POTTED_FERN));
            set(x + in.getStepX(), 1, z + in.getStepZ(), stairs(kit.chair, in, Half.BOTTOM));
        });
    }

    private void kitchenCounter(Room r, int len) {
        if (len < 2) return;
        wallItem(r, len, 1, false, (x, z, in, al) -> {
            for (int a = 0; a < len; a++) {
                int tx = x + al.getStepX() * a, tz = z + al.getStepZ() * a;
                BlockState s = switch (a) {
                    case 0 -> with(BuildingGenerator.st(Blocks.SMOKER), BlockStateProperties.HORIZONTAL_FACING, in);
                    case 1 -> BuildingGenerator.st(Blocks.CAULDRON);
                    default -> BuildingGenerator.st(kit.counter);
                };
                set(tx, 1, tz, s);
                if (a >= 2 && rnd.nextInt(3) == 0) set(tx, 2, tz, BuildingGenerator.st(Blocks.POTTED_FERN));
            }
        });
    }

    private void diningTable(Room r) {
        centerItem(r, 1, 1, (x, z, in, al) -> {
            set(x, 1, z, BuildingGenerator.st(kit.fence));
            set(x, 2, z, BuildingGenerator.st(trapPlate()));
            for (Direction d : HORIZ) {
                int cx = x + d.getStepX(), cz = z + d.getStepZ();
                if (st(cx, cz) == FLOOR && !occ[cx][cz] && !clear[cx][cz] && rnd.nextInt(4) != 0) {
                    set(cx, 1, cz, stairs(kit.chair, d, Half.BOTTOM));
                    occ[cx][cz] = true;
                }
            }
        });
    }

    private Block trapPlate() {
        return switch (g.style) {
            case CLASSIC -> Blocks.DARK_OAK_PRESSURE_PLATE; case ANTIQUE -> Blocks.SPRUCE_PRESSURE_PLATE;
            case FANTASY -> Blocks.WARPED_PRESSURE_PLATE; default -> Blocks.BIRCH_PRESSURE_PLATE;
        };
    }

    // ---------- офис ----------
    private void office(Room r) {
        int a = r.area();
        if (a >= 40) openOffice(r);
        else if (a >= 12) meeting(r);
        else {
            wallItem(r, 1, 1, false, (x, z, in, al) -> set(x, 1, z, BuildingGenerator.st(Blocks.CARTOGRAPHY_TABLE)));
            wallItem(r, 2, 1, true, (x, z, in, al) -> {
                for (int k = 0; k < 2; k++) {
                    set(x + al.getStepX() * k, 1, z + al.getStepZ() * k, with(BuildingGenerator.st(Blocks.BARREL), BlockStateProperties.FACING, in));
                    set(x + al.getStepX() * k, 2, z + al.getStepZ() * k, with(BuildingGenerator.st(Blocks.BARREL), BlockStateProperties.FACING, in));
                }
            });
        }
        plant(r); plant(r);
    }

    /** Открытый офис: ряды столов спина к спине с проходами. */
    private void openOffice(Room r) {
        BlockState desk = slab(kit.table, SlabType.TOP);
        BlockState mon = BuildingGenerator.st(Blocks.BLACK_STAINED_GLASS_PANE);
        for (int[] c : r.cells) {
            int lx = c[0] - r.x0, lz = c[1] - r.z0;
            if (lx < 2 || lz < 1 || c[0] > r.x1 - 2 || c[1] > r.z1 - 1) continue;
            if (lx % 6 == 5) continue; // поперечный проход
            int m = lz % 5;
            int x = c[0], z = c[1];
            if (!free(x, z)) continue;
            if (m == 2 || m == 3) {
                set(x, 1, z, desk);
                if ((lx + lz) % 3 != 0) set(x, 2, z, mon); else set(x, 2, z, BuildingGenerator.st(Blocks.POTTED_FERN));
                occ[x][z] = true;
            } else if (m == 1 && free(x, z + 1) && st(x, z + 1) == FLOOR) {
                set(x, 1, z, stairs(kit.officeChair, Direction.NORTH, Half.BOTTOM)); occ[x][z] = true;
            } else if (m == 4) {
                set(x, 1, z, stairs(kit.officeChair, Direction.SOUTH, Half.BOTTOM)); occ[x][z] = true;
            }
        }
        wallItem(r, 2, 1, false, (x, z, in, al) -> {
            set(x, 1, z, BuildingGenerator.st(Blocks.LIGHT_BLUE_STAINED_GLASS));
            set(x + al.getStepX(), 1, z + al.getStepZ(), BuildingGenerator.st(Blocks.CARTOGRAPHY_TABLE));
        });
    }

    private void meeting(Room r) {
        boolean alongX = r.w() >= r.d();
        int len = Math.max(1, (alongX ? r.w() : r.d()) - 4);
        centerItem(r, alongX ? len : 1, alongX ? 1 : len, (x, z, in, al) -> {
            for (int k = 0; k < len; k++) {
                int tx = alongX ? x + k : x, tz = alongX ? z : z + k;
                set(tx, 1, tz, slab(kit.table, SlabType.TOP));
                if (alongX) {
                    if (free(tx, tz - 1)) { set(tx, 1, tz - 1, stairs(kit.officeChair, Direction.NORTH, Half.BOTTOM)); occ[tx][tz - 1] = true; }
                    if (free(tx, tz + 1)) { set(tx, 1, tz + 1, stairs(kit.officeChair, Direction.SOUTH, Half.BOTTOM)); occ[tx][tz + 1] = true; }
                } else {
                    if (free(tx - 1, tz)) { set(tx - 1, 1, tz, stairs(kit.officeChair, Direction.WEST, Half.BOTTOM)); occ[tx - 1][tz] = true; }
                    if (free(tx + 1, tz)) { set(tx + 1, 1, tz, stairs(kit.officeChair, Direction.EAST, Half.BOTTOM)); occ[tx + 1][tz] = true; }
                }
            }
        });
        tv(r);
    }

    // ---------- лобби ----------
    private void lobby(Room r) {
        // ресепшен напротив входа
        int ex = g.entranceX0 >= 0 ? (g.entranceX0 + g.entranceX1) / 2 : (r.x0 + r.x1) / 2;
        int ez = g.entranceZ + 4;
        BlockState desk = BuildingGenerator.st(g.trad ? g.pal.trim : kit.counter);
        int placed = 0;
        for (int dx = -2; dx <= 2; dx++) {
            int x = ex + dx;
            if (free(x, ez) && free(x, ez + 1)) {
                set(x, 1, ez, desk);
                if (dx == 0) set(x, 2, ez, BuildingGenerator.st(Blocks.POTTED_FLOWERING_AZALEA));
                occ[x][ez] = true;
                placed++;
            }
        }
        if (placed > 0 && free(ex, ez + 1)) { set(ex, 1, ez + 1, stairs(kit.officeChair, Direction.SOUTH, Half.BOTTOM)); occ[ex][ez + 1] = true; }
        for (int k = 0; k < Math.max(2, r.area() / 40); k++) sofaSet(r);
        for (int k = 0; k < Math.max(4, r.area() / 15); k++) plant(r);
        for (int k = 0; k < Math.max(1, r.area() / 60); k++) diningTable(r);
        if (r.area() > 40) for (int k = 0; k < 2; k++) centerItem(r, 1, 1, (x, z, in, al) -> {
            set(x, 1, z, BuildingGenerator.st(Blocks.MOSS_BLOCK));
            set(x, 2, z, BuildingGenerator.st(Blocks.FLOWERING_AZALEA));
        });
        lamp(r); lamp(r);
        // ковровая дорожка от входа
        if (g.entranceX0 >= 0) for (int z = g.entranceZ + 1; z < ez; z++)
            for (int x = g.entranceX0; x <= g.entranceX1; x++)
                if (st(x, z) == FLOOR && !occ[x][z]) set(x, 1, z, BuildingGenerator.st(kit.carpet1));
    }

    // ---------- магазины ----------
    private void shop(Room r, int kind) {
        if (r.area() < 6) { plant(r); return; }
        switch (kind) {
            case 0 -> { // кафе
                for (int k = 0; k < Math.max(1, r.area() / 12); k++) diningTable(r);
                counter(r, Blocks.CAKE);
            }
            case 1 -> { // пекарня
                counter(r, Blocks.CAKE);
                wallItem(r, 1, 1, false, (x, z, in, al) -> set(x, 1, z, with(BuildingGenerator.st(Blocks.SMOKER), BlockStateProperties.HORIZONTAL_FACING, in)));
                shelves(r, Blocks.BARREL, Blocks.HAY_BLOCK, 3);
            }
            case 2 -> { // книжный
                shelves(r, Blocks.BOOKSHELF, Blocks.BOOKSHELF, 5);
                wallItem(r, 1, 1, false, (x, z, in, al) -> set(x, 1, z, with(BuildingGenerator.st(Blocks.LECTERN), BlockStateProperties.HORIZONTAL_FACING, in)));
                counter(r, Blocks.POTTED_FERN);
            }
            case 3 -> { // цветы
                for (int k = 0; k < 6; k++) plant(r);
                shelves(r, Blocks.MOSS_BLOCK, Blocks.FLOWERING_AZALEA_LEAVES, 3);
                counter(r, Blocks.POTTED_RED_TULIP);
            }
            case 4 -> { // одежда
                Block[] wool = {Blocks.WHITE_WOOL, Blocks.RED_WOOL, Blocks.BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.BLACK_WOOL, Blocks.LIME_WOOL};
                for (int k = 0; k < 5; k++) {
                    Block a = wool[rnd.nextInt(wool.length)], b = wool[rnd.nextInt(wool.length)];
                    wallItem(r, 1, 1, true, (x, z, in, al) -> { set(x, 1, z, BuildingGenerator.st(a)); set(x, 2, z, BuildingGenerator.st(b)); });
                }
                counter(r, Blocks.POTTED_AZALEA);
            }
            default -> { // лавка (фэнтези — алхимия)
                if (g.fantasy) {
                    wallItem(r, 1, 1, false, (x, z, in, al) -> set(x, 1, z, BuildingGenerator.st(Blocks.BREWING_STAND)));
                    wallItem(r, 1, 1, false, (x, z, in, al) -> set(x, 1, z, BuildingGenerator.st(Blocks.ENCHANTING_TABLE)));
                    shelves(r, Blocks.AMETHYST_BLOCK, Blocks.BOOKSHELF, 3);
                } else shelves(r, Blocks.BARREL, Blocks.COMPOSTER, 4);
                counter(r, Blocks.LANTERN);
            }
        }
    }

    private void counter(Room r, Block onTop) {
        wallItem(r, 3, 1, false, (x, z, in, al) -> {
            for (int a = 0; a < 3; a++) {
                int tx = x + al.getStepX() * a, tz = z + al.getStepZ() * a;
                set(tx, 1, tz, BuildingGenerator.st(kit.counter));
                if (a == 1) set(tx, 2, tz, BuildingGenerator.st(onTop));
            }
        });
    }

    private void shelves(Room r, Block bottom, Block top, int n) {
        for (int k = 0; k < n; k++)
            wallItem(r, 1, 1, true, (x, z, in, al) -> {
                set(x, 1, z, bottom == Blocks.BARREL ? with(BuildingGenerator.st(bottom), BlockStateProperties.FACING, in) : BuildingGenerator.st(bottom));
                BlockState t = top == Blocks.FLOWERING_AZALEA_LEAVES
                        ? with(BuildingGenerator.st(top), BlockStateProperties.PERSISTENT, true) : BuildingGenerator.st(top);
                set(x, 2, z, t);
            });
    }

    /** Супермаркет: стеллажи рядами, кассы у входа. */
    private void supermarket(Room r) {
        Block[] goods = {Blocks.BARREL, Blocks.BOOKSHELF, Blocks.COMPOSTER, Blocks.HAY_BLOCK, Blocks.MELON, Blocks.PUMPKIN};
        for (int[] c : r.cells) {
            int lx = c[0] - r.x0, lz = c[1] - r.z0;
            if (lz < 5 || c[1] > r.z1 - 2 || lx < 2 || c[0] > r.x1 - 2) continue;
            if (lx % 4 != 2 || lz % 10 == 9) continue;
            if (!free(c[0], c[1])) continue;
            Block b = goods[g.hash(c[0], c[1], 55) % goods.length];
            set(c[0], 1, c[1], b == Blocks.BARREL ? with(BuildingGenerator.st(b), BlockStateProperties.FACING, Direction.UP) : BuildingGenerator.st(b));
            set(c[0], 2, c[1], BuildingGenerator.st(goods[g.hash(c[0], c[1], 56) % 2]));
            occ[c[0]][c[1]] = true;
        }
        // кассы
        for (int[] c : r.cells) {
            int lx = c[0] - r.x0, lz = c[1] - r.z0;
            if (lz == 3 && lx % 5 == 2 && free(c[0], c[1])) {
                set(c[0], 1, c[1], BuildingGenerator.st(kit.counter));
                set(c[0], 2, c[1], BuildingGenerator.st(Blocks.LANTERN));
                occ[c[0]][c[1]] = true;
            }
        }
    }

    // ---------- общественные здания ----------
    private void hall(Room r) {
        boolean library = g.hash(0, 0, 777) % 2 == 0;
        if (library) {
            for (int[] c : r.cells) {
                int lx = c[0] - r.x0, lz = c[1] - r.z0;
                if (lz < 3 || c[1] > r.z1 - 2 || lx < 2 || c[0] > r.x1 - 2) continue;
                if (lz % 4 != 3 || lx % 8 == 7) continue;
                if (!free(c[0], c[1])) continue;
                for (int dy = 1; dy <= Math.min(3, h - 2); dy++) set(c[0], dy, c[1], BuildingGenerator.st(Blocks.BOOKSHELF));
                occ[c[0]][c[1]] = true;
            }
            for (int k = 0; k < 3; k++) centerItem(r, 1, 1, (x, z, in, al) -> set(x, 1, z, BuildingGenerator.st(Blocks.LECTERN)));
        } else {
            Block[] exhibits = {Blocks.DECORATED_POT, Blocks.AMETHYST_CLUSTER, Blocks.GOLD_BLOCK, Blocks.BELL, Blocks.POTTED_FLOWERING_AZALEA,
                    Blocks.EMERALD_BLOCK, Blocks.DRAGON_HEAD, Blocks.SKELETON_SKULL};
            for (int[] c : r.cells) {
                int lx = c[0] - r.x0, lz = c[1] - r.z0;
                if (lz < 4 || c[1] > r.z1 - 2 || lx < 2 || c[0] > r.x1 - 2) continue;
                if (lx % 4 != 2 || lz % 4 != 0 || !free(c[0], c[1])) continue;
                set(c[0], 1, c[1], BuildingGenerator.st(g.pal.trim));
                Block e = exhibits[g.hash(c[0], c[1], 66) % exhibits.length];
                BlockState es = BuildingGenerator.st(e);
                if (e == Blocks.BELL) es = with(with(es, BlockStateProperties.BELL_ATTACHMENT, BellAttachType.FLOOR), BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
                set(c[0], 2, c[1], es);
                occ[c[0]][c[1]] = true;
            }
            for (int k = 0; k < 4; k++) plant(r);
            for (int k = 0; k < 2; k++) wallItem(r, 3, 1, false, (x, z, in, al) -> {
                for (int a = 0; a < 3; a++) set(x + al.getStepX() * a, 1, z + al.getStepZ() * a, stairs(kit.sofa, in.getOpposite(), Half.BOTTOM));
            });
        }
    }
}
