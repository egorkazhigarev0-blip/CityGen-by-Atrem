package com.citygen.server;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Плоский список операций с блоками. */
public class OpList {
    public static final byte SET = 0, CLEAR = 1, FOUNDATION = 2, COLUMN_FULL = 4, COLUMN_VEG = 5;
    /** Границы, в которых можно удалять деревья целиком: minX, minZ, maxX, maxZ, minY. null — не удалять. */
    public int[] floodBox;
    public final LongArrayList pos = new LongArrayList();
    public final List<BlockState> states = new ArrayList<>();
    public final ByteArrayList kinds = new ByteArrayList();

    public void add(BlockPos p, BlockState s, byte kind) {
        pos.add(p.asLong()); states.add(s); kinds.add(kind);
    }

    public void addAll(OpList o) {
        pos.addAll(o.pos); states.addAll(o.states); kinds.addAll(o.kinds);
        if (o.floodBox != null) floodBox = o.floodBox;
    }

    public int size() { return pos.size(); }
}
