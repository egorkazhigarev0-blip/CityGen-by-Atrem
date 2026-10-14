package com.citygen.dev;

import com.citygen.gen.Blueprint;
import com.citygen.gen.Plan;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;

/**
 * Проверка проходимости здания «виртуальным игроком»: ходьба, прыжок на 1 блок, падение,
 * лестницы-стремянки и деревянные двери. Считает долю достижимого пола на каждом этаже.
 */
public final class Reach {
    private Reach() {}

    public static final class Result {
        public double worst = 1.0;
        public int worstFloor = -1;
        public int floors;
        public double[] perFloor;
        boolean[] seen;
    }

    public static String names(Blueprint bp, int x0, int x1, int z0, int z1, int y) {
        StringBuilder sb = new StringBuilder();
        for (int z = z0; z <= z1; z++)
            for (int x = x0; x <= x1; x++) sb.append(x).append(',').append(y).append(',').append(z).append(' ').append(bp.get(x, y, z)).append((char) 10);
        return sb.toString();
    }

    /** Текстовый срез слоёв здания: # — твёрдое, . — воздух, s — ступени, D — дверь, H — стремянка, * — достижимо. */
    public static String dump(Blueprint bp, Result r, int y0, int y1) {
        StringBuilder sb = new StringBuilder();
        for (int y = y0; y <= y1; y++) {
            sb.append("y=").append(y).append('\n');
            for (int z = bp.minZ(); z <= bp.maxZ(); z++) {
                for (int x = bp.minX(); x <= bp.maxX(); x++) {
                    BlockState b = bp.get(x, y, z);
                    char c;
                    BlockState un = bp.get(x, y - 1, z);
                    boolean counted = x >= 0 && z >= 0 && un != null && !un.isAir() && stand(bp, x, y, z) && floorOf(bp, y) >= 0 && inner(bp, floorOf(bp, y), x, z);
                    if (r.seen != null && y < bp.h && r.seen[idx(bp, x, y, z)]) c = '*';
                    else if (counted) c = '!';
                    else if (b == null) c = ' ';
                    else if (b.isAir()) c = '.';
                    else if (b.getBlock() instanceof StairBlock) c = 's';
                    else if (b.getBlock() instanceof DoorBlock) c = 'D';
                    else if (b.getBlock() instanceof LadderBlock) c = 'H';
                    else if (b.getBlock() instanceof TrapDoorBlock) c = 't';
                    else if (b.getBlock() instanceof SlabBlock) c = '_';
                    else if (passable(b)) c = ',';
                    else c = '#';
                    sb.append(c);
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static boolean passable(BlockState b) {
        if (b == null || b.isAir()) return true;
        Block k = b.getBlock();
        if (k instanceof DoorBlock) return k != Blocks.IRON_DOOR;
        if (k instanceof TrapDoorBlock || k instanceof LadderBlock) return true;
        var shape = b.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        // ковры, нажимные плиты и прочие «тонкие» блоки игрок просто перешагивает
        return shape.isEmpty() || shape.max(net.minecraft.core.Direction.Axis.Y) <= 0.2;
    }

    private static boolean ladder(BlockState b) { return b != null && b.getBlock() instanceof LadderBlock; }

    private static boolean support(Blueprint bp, int x, int y, int z) {
        if (y < 0) return true;
        BlockState b = bp.get(x, y, z);
        if (b == null) return y == 0; // земля вокруг здания
        if (b.isAir()) return false;
        if (b.getBlock() instanceof DoorBlock || b.getBlock() instanceof TrapDoorBlock && b.getValue(TrapDoorBlock.OPEN)) return false;
        return !b.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
    }

    private static boolean pass(Blueprint bp, int x, int y, int z) {
        if (!bp.inside(x, y, z)) return y >= 0;
        return passable(bp.get(x, y, z));
    }

    private static boolean stand(Blueprint bp, int x, int y, int z) {
        if (!bp.inside(x, y, z) || y < 1) return false;
        if (!pass(bp, x, y, z) || !pass(bp, x, y + 1, z)) return false;
        return support(bp, x, y - 1, z) || ladder(bp.get(x, y, z)) || ladder(bp.get(x, y - 1, z));
    }

    public static Result check(Blueprint bp, Plan p) {
        int n = p.floors;
        int[] fy = new int[n + 1];
        for (int i = 0; i < n; i++) fy[i + 1] = fy[i] + (i == 0 ? p.groundH : p.floorH);
        int sx = bp.maxX() - bp.minX() + 1, sz = bp.maxZ() - bp.minZ() + 1, H = bp.h;
        boolean[] seen = new boolean[sx * sz * H];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        int startX = p.w / 2, startZ = bp.minZ();
        if (stand(bp, startX, 1, startZ)) { seen[idx(bp, startX, 1, startZ)] = true; q.add(new int[]{startX, 1, startZ}); }
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            int x = c[0], y = c[1], z = c[2];
            boolean onLadder = ladder(bp.get(x, y, z)) || ladder(bp.get(x, y + 1, z));
            for (int[] d : dirs) {
                int nx = x + d[0], nz = z + d[1];
                if (!bp.inside(nx, y, nz)) continue;
                if (stand(bp, nx, y, nz)) visit(bp, seen, q, nx, y, nz);
                else if (pass(bp, nx, y, nz) && pass(bp, nx, y + 1, nz)) {
                    int fy2 = y - 1; // падение
                    while (fy2 >= 1 && !stand(bp, nx, fy2, nz) && pass(bp, nx, fy2, nz)) fy2--;
                    if (fy2 >= 1 && stand(bp, nx, fy2, nz)) visit(bp, seen, q, nx, fy2, nz);
                }
                if (pass(bp, x, y + 2, z) && stand(bp, nx, y + 1, nz)) visit(bp, seen, q, nx, y + 1, nz); // прыжок на блок
            }
            if (onLadder && stand(bp, x, y + 1, z)) visit(bp, seen, q, x, y + 1, z);
            if (onLadder && y > 1 && stand(bp, x, y - 1, z)) visit(bp, seen, q, x, y - 1, z);
            // открытый люк над стремянкой
            if (onLadder && bp.get(x, y + 2, z) != null && bp.get(x, y + 2, z).getBlock() instanceof TrapDoorBlock && stand(bp, x, y + 3, z))
                visit(bp, seen, q, x, y + 3, z);
        }
        Result r = new Result();
        r.seen = seen;
        r.floors = n;
        r.perFloor = new double[n];
        for (int i = 0; i < n; i++) {
            int y = fy[i] + 1, total = 0, ok = 0;
            for (int z = 0; z < p.d; z++)
                for (int x = 0; x < p.w; x++) {
                    BlockState under = bp.get(x, y - 1, z);
                    if (under == null || under.isAir() || !stand(bp, x, y, z) || !inner(bp, i, x, z)) continue;
                    total++;
                    if (seen[idx(bp, x, y, z)]) ok++;
                }
            double frac = total == 0 ? 1.0 : ok / (double) total;
            r.perFloor[i] = frac;
            if (frac < r.worst) { r.worst = frac; r.worstFloor = i; }
        }
        return r;
    }

    private static boolean inner(Blueprint bp, int i, int x, int z) {
        if (bp.inner == null) return true;
        return i < bp.inner.length && x >= 0 && z >= 0 && x < bp.w && z < bp.d && bp.inner[i][x][z];
    }

    private static int floorOf(Blueprint bp, int y) {
        if (bp.floorY == null) return -1;
        for (int i = 0; i + 1 < bp.floorY.length; i++) if (bp.floorY[i] + 1 == y) return i;
        return -1;
    }

    /** Под крышей — значит внутри здания (открытые террасы на уступах не считаем). */
    private static boolean covered(Blueprint bp, int x, int y, int z) {
        for (int dy = 2; dy <= 10; dy++) {
            BlockState b = bp.get(x, y + dy, z);
            if (b != null && !b.isAir() && !passable(b)) return true;
        }
        return false;
    }

    /** Кабина декоративного лифта (за железными дверями) — не считаем. */
    private static boolean cab(Blueprint bp, int x, int y, int z) {
        BlockState under = bp.get(x, y - 1, z);
        if (under == null || under.getBlock() != Blocks.SMOOTH_STONE) return false;
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                BlockState b = bp.get(x + dx, y, z + dz);
                if (b != null && b.getBlock() == Blocks.IRON_DOOR) return true;
            }
        return false;
    }

    private static void visit(Blueprint bp, boolean[] seen, ArrayDeque<int[]> q, int x, int y, int z) {
        int i = idx(bp, x, y, z);
        if (seen[i]) return;
        seen[i] = true;
        q.add(new int[]{x, y, z});
    }

    private static int idx(Blueprint bp, int x, int y, int z) {
        int sx = bp.maxX() - bp.minX() + 1, sz = bp.maxZ() - bp.minZ() + 1;
        return (y * sz + (z - bp.minZ())) * sx + (x - bp.minX());
    }
}
