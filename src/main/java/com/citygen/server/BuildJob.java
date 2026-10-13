package com.citygen.server;

import com.citygen.gen.Layout;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Clearable;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.UUID;

/**
 * Пошаговая постройка: каждый тик сервера ставит блоки, пока не кончится бюджет времени.
 * Флаги: обновить клиента, без обновления соседей и без выпадения предметов — быстро и без «осыпания».
 * В конце — проход, который соединяет стёкла, решётки, заборы и углы лестниц.
 */
public class BuildJob {
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static final byte RESTORE = 3;

    final ServerLevel level;
    final UUID owner;
    final boolean isUndo;
    final String title;
    private final LongArrayList pos;
    private final List<BlockState> states;
    private final it.unimi.dsi.fastutil.bytes.ByteArrayList kinds;
    private final UndoRecord restoreFrom;
    public final UndoRecord undo;
    private final LongArrayList fix = new LongArrayList();
    private final it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue flood = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
    private final int[] floodBox;
    private static final net.minecraft.world.level.block.state.BlockState AIR = Blocks.AIR.defaultBlockState();
    private int idx, fixIdx;
    public int placed;
    final long startedAt = System.currentTimeMillis();

    public BuildJob(ServerLevel level, UUID owner, OpList ops, String title) {
        this.level = level; this.owner = owner; this.isUndo = false; this.title = title;
        this.pos = ops.pos; this.states = ops.states; this.kinds = ops.kinds;
        this.floodBox = ops.floodBox;
        this.restoreFrom = null;
        this.undo = new UndoRecord(level.dimension(), title);
    }

    /** Задание отмены: восстанавливает блоки в обратном порядке. */
    public BuildJob(ServerLevel level, UUID owner, UndoRecord rec) {
        this.level = level; this.owner = owner; this.isUndo = true; this.title = rec.description;
        this.pos = new LongArrayList(rec.size());
        this.states = new java.util.ArrayList<>(rec.size());
        this.kinds = new it.unimi.dsi.fastutil.bytes.ByteArrayList(rec.size());
        for (int i = rec.size() - 1; i >= 0; i--) { pos.add(rec.pos.getLong(i)); states.add(rec.states.get(i)); kinds.add(RESTORE); }
        this.restoreFrom = rec;
        this.undo = null;
        this.floodBox = null;
    }

    public int total() { return pos.size() + fix.size(); }
    public int done() { return idx + fixIdx; }
    public boolean finished() { return idx >= pos.size() && fixIdx >= fix.size(); }

    /** @return true, если всё готово. */
    public boolean step(long deadline) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        while (idx < pos.size() || !flood.isEmpty()) {
            if ((idx & 63) == 0 && System.nanoTime() > deadline) return false;
            if (!flood.isEmpty()) { if (!drainFlood(mp, deadline)) return false; continue; }
            long l = pos.getLong(idx);
            BlockState st = states.get(idx);
            byte k = kinds.getByte(idx);
            idx++;
            mp.set(BlockPos.getX(l), BlockPos.getY(l), BlockPos.getZ(l));
            if (level.isOutsideBuildHeight(mp)) continue;
            switch (k) {
                case OpList.SET -> place(mp, st);
                case OpList.CLEAR -> { if (!level.getBlockState(mp).isAir()) place(mp, st); }
                case OpList.FOUNDATION -> {
                    for (int d = 0; d < 48 && mp.getY() > level.getMinBuildHeight(); d++) {
                        BlockState cur = level.getBlockState(mp);
                        if (Layout.isGround(cur) && cur.getFluidState().isEmpty()) break;
                        place(mp, st);
                        mp.move(0, -1, 0);
                    }
                }
                case OpList.COLUMN_FULL, OpList.COLUMN_VEG -> column(mp, k == OpList.COLUMN_FULL);
                case RESTORE -> restore(mp, st, l);
                default -> {}
            }
        }
        while (fixIdx < fix.size()) {
            if ((fixIdx & 63) == 0 && System.nanoTime() > deadline) return false;
            long l = fix.getLong(fixIdx++);
            mp.set(BlockPos.getX(l), BlockPos.getY(l), BlockPos.getZ(l));
            BlockState cur = level.getBlockState(mp);
            BlockState upd = Block.updateFromNeighbourShapes(cur, level, mp);
            if (upd != cur && !upd.isAir()) level.setBlock(mp, upd, FLAGS);
        }
        return true;
    }

    /** Расчистка столбца до самого верха; деревья ставятся в очередь на удаление целиком. */
    private void column(BlockPos.MutableBlockPos mp, boolean full) {
        int x = mp.getX(), z = mp.getZ();
        int top = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z);
        for (int y = mp.getY() + (full ? 1 : 0); y < top; y++) {
            mp.set(x, y, z);
            BlockState cur = level.getBlockState(mp);
            if (cur.isAir()) continue;
            boolean tree = isTree(cur);
            if (full || tree || isPlant(cur)) {
                place(mp, AIR);
                if (tree && floodBox != null) flood.enqueue(mp.asLong());
            }
        }
    }

    private boolean drainFlood(BlockPos.MutableBlockPos mp, long deadline) {
        int n = 0;
        while (!flood.isEmpty()) {
            if ((++n & 63) == 0 && System.nanoTime() > deadline) return false;
            long l = flood.dequeueLong();
            int x0 = BlockPos.getX(l), y0 = BlockPos.getY(l), z0 = BlockPos.getZ(l);
            for (int dx = -1; dx <= 1; dx++)
                for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                        int x = x0 + dx, y = y0 + dy, z = z0 + dz;
                        if (x < floodBox[0] || z < floodBox[1] || x > floodBox[2] || z > floodBox[3] || y < floodBox[4]) continue;
                        mp.set(x, y, z);
                        if (level.isOutsideBuildHeight(mp)) continue;
                        BlockState cur = level.getBlockState(mp);
                        if (!isTree(cur)) continue;
                        place(mp, AIR);
                        flood.enqueue(mp.asLong());
                    }
        }
        return true;
    }

    /** Части деревьев: стволы, природная листва (не поставленная игроком), лианы, грибы-великаны, ульи. */
    static boolean isTree(BlockState s) {
        if (s.is(net.minecraft.tags.BlockTags.LOGS)) return true;
        if (s.is(net.minecraft.tags.BlockTags.LEAVES))
            return !s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT)
                    || !s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.PERSISTENT);
        Block b = s.getBlock();
        return b == Blocks.VINE || b == Blocks.COCOA || b == Blocks.BEE_NEST || b == Blocks.BROWN_MUSHROOM_BLOCK
                || b == Blocks.RED_MUSHROOM_BLOCK || b == Blocks.MUSHROOM_STEM || b == Blocks.MANGROVE_ROOTS
                || b == Blocks.HANGING_ROOTS || b == Blocks.GLOW_LICHEN || b == Blocks.MOSS_CARPET
                || b == Blocks.WEEPING_VINES || b == Blocks.WEEPING_VINES_PLANT || b == Blocks.TWISTING_VINES
                || b == Blocks.TWISTING_VINES_PLANT;
    }

    /** Мелкая растительность: трава, цветы, кусты, бамбук, тростник, снег. */
    static boolean isPlant(BlockState s) {
        if (!s.getFluidState().isEmpty()) return false;
        Block b = s.getBlock();
        return s.canBeReplaced() || s.is(net.minecraft.tags.BlockTags.FLOWERS) || s.is(net.minecraft.tags.BlockTags.SAPLINGS)
                || b == Blocks.BAMBOO || b == Blocks.SUGAR_CANE || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.AZALEA || b == Blocks.FLOWERING_AZALEA || b == Blocks.BIG_DRIPLEAF || b == Blocks.BIG_DRIPLEAF_STEM
                || b == Blocks.PUMPKIN || b == Blocks.MELON || b == Blocks.SNOW;
    }

    private void place(BlockPos.MutableBlockPos mp, BlockState st) {
        BlockState cur = level.getBlockState(mp);
        if (cur == st) return;
        CompoundTag tag = null;
        if (cur.hasBlockEntity()) {
            BlockEntity be = level.getBlockEntity(mp);
            if (be != null) {
                tag = be.saveWithFullMetadata();
                Clearable.tryClear(be);
            }
        }
        if (undo != null) undo.add(mp.asLong(), cur, tag);
        if (level.setBlock(mp, st, FLAGS)) {
            placed++;
            Block b = st.getBlock();
            if (b instanceof CrossCollisionBlock || b instanceof WallBlock || b instanceof StairBlock || b instanceof FenceGateBlock)
                fix.add(mp.asLong());
        }
    }

    private void restore(BlockPos.MutableBlockPos mp, BlockState st, long l) {
        BlockState cur = level.getBlockState(mp);
        if (cur != st) {
            if (cur.hasBlockEntity()) {
                BlockEntity be = level.getBlockEntity(mp);
                if (be != null) Clearable.tryClear(be);
            }
            level.setBlock(mp, st, FLAGS);
            placed++;
        }
        CompoundTag tag = restoreFrom.blockEntities.remove(l);
        if (tag != null) {
            BlockEntity be = level.getBlockEntity(mp);
            if (be != null) { be.load(tag); be.setChanged(); }
        }
    }
}
