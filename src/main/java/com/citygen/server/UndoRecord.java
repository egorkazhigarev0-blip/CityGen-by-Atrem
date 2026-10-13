package com.citygen.server;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Исходные блоки, которые были заменены при постройке. */
public class UndoRecord {
    public final ResourceKey<Level> dimension;
    public final LongArrayList pos = new LongArrayList();
    public final List<BlockState> states = new ArrayList<>();
    public final Long2ObjectOpenHashMap<CompoundTag> blockEntities = new Long2ObjectOpenHashMap<>();
    public final String description;

    public UndoRecord(ResourceKey<Level> dimension, String description) {
        this.dimension = dimension; this.description = description;
    }

    public void add(long p, BlockState old, CompoundTag be) {
        if (be != null && !blockEntities.containsKey(p)) blockEntities.put(p, be);
        pos.add(p); states.add(old);
    }

    public int size() { return pos.size(); }
}
