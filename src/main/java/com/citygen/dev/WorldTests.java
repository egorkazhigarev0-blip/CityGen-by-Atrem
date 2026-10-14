package com.citygen.dev;

import com.citygen.CityGen;
import com.citygen.gen.BuildingType;
import com.citygen.gen.GenSettings;
import com.citygen.gen.Layout;
import com.citygen.server.BuildJob;
import com.citygen.server.JobManager;
import com.citygen.server.OpList;
import com.citygen.server.UndoRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.*;

/** Проверка постройки в настоящем мире (gradlew runGameTestServer). В обычной игре не запускается. */
@GameTestHolder(CityGen.MODID)
@PrefixGameTestTemplate(false)
public final class WorldTests {
    private WorldTests() {}

    private static boolean isNaturalTree(BlockState s) {
        if (s.is(net.minecraft.tags.BlockTags.LOGS)) return true;
        return s.is(net.minecraft.tags.BlockTags.LEAVES) && s.hasProperty(BlockStateProperties.PERSISTENT) && !s.getValue(BlockStateProperties.PERSISTENT);
    }

    private static void plantForest(ServerLevel level, BlockPos c, int r) {
        var reg = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.CONFIGURED_FEATURE);
        var keys = java.util.List.of(net.minecraft.data.worldgen.features.TreeFeatures.OAK, net.minecraft.data.worldgen.features.TreeFeatures.FANCY_OAK,
                net.minecraft.data.worldgen.features.TreeFeatures.BIRCH, net.minecraft.data.worldgen.features.TreeFeatures.DARK_OAK,
                net.minecraft.data.worldgen.features.TreeFeatures.SPRUCE);
        net.minecraft.util.RandomSource rnd = net.minecraft.util.RandomSource.create(7);
        int planted = 0;
        for (int x = -r; x <= r; x += 4)
            for (int z = -r; z <= r; z += 4) {
                int gx = c.getX() + x + rnd.nextInt(3), gz = c.getZ() + z + rnd.nextInt(3);
                level.getChunk(gx >> 4, gz >> 4);
                int gy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, gx, gz);
                var key = keys.get(rnd.nextInt(keys.size()));
                var f = reg.getHolder(key);
                if (f.isPresent() && f.get().value().place(level, level.getChunkSource().getGenerator(), rnd, new BlockPos(gx, gy, gz))) planted++;
            }
        CityGen.LOG.info("WORLDTEST planted {} trees", planted);
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void buildAndUndo(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<String> problems = new ArrayList<>();
        Direction[] dirs = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        BuildingType[] types = {BuildingType.MIXED, BuildingType.TOWNHOUSE, BuildingType.MIDRISE, BuildingType.HIGHRISE};
        for (int k = 0; k < dirs.length; k++) {
            GenSettings s = new GenSettings();
            s.type = types[k]; s.applyTypeDefaults();
            s.count = 4; s.street = GenSettings.StreetMode.ROAD; s.seed = 99 + k;
            s.interior = GenSettings.Interior.FURNISHED; s.greenery = GenSettings.Greenery.RICH;
            s.style = k == 0 ? GenSettings.StyleMode.MODERN : k == 1 ? GenSettings.StyleMode.CLASSIC
                    : k == 2 ? GenSettings.StyleMode.ANTIQUE : GenSettings.StyleMode.FANTASY;
            if (k == 2) {
                s.construction = 30;
            }
            if (k == 3) { s.arrangement = GenSettings.Arrangement.STREET; s.fireEscapes = GenSettings.Toggle3.ON; }
            BlockPos base0 = h.absolutePos(new BlockPos(0, 1, 0)).offset(400 * (k + 1), 0, 0);
            level.getChunk(base0.getX() >> 4, base0.getZ() >> 4);
            BlockPos anchor = new BlockPos(base0.getX(), level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, base0.getX(), base0.getZ()), base0.getZ());
            CityGen.LOG.info("WORLDTEST anchor {} block below {}", anchor, level.getBlockState(anchor.below()).getBlock().getDescriptionId());
            if (k == 2) { // город: три квартала с общими дорогами
                DemoCity.make(s, anchor.getX(), anchor.getZ() - 75);
                s.quarters.subList(3, s.quarters.size()).clear();
                s.interior = GenSettings.Interior.ROOMS;
            }
            Layout L = Layout.compute(s, anchor, dirs[k], level);
            Layout air = Layout.compute(s, anchor.above(17), dirs[k], level);
            if (air.streetY != L.streetY) {
                StringBuilder sb = new StringBuilder();
                for (int y = anchor.getY() + 18; y >= anchor.getY() - 4; y--) {
                    BlockState t = level.getBlockState(new BlockPos(anchor.getX(), y, anchor.getZ()));
                    if (!t.isAir()) sb.append(y).append('=').append(t.getBlock().getDescriptionId()).append(' ');
                }
                CityGen.LOG.info("WORLDTEST column at anchor {}: {}", anchor, sb);
            }
            if (air.streetY != L.streetY) problems.add(dirs[k] + ": в прыжке/полёте уровень другой: " + air.streetY + " vs " + L.streetY);
            // лес вокруг и на участке: настоящие ванильные деревья
            if (k == 0 || k == 2) plantForest(level, anchor, 90);
            OpList ops = JobManager.makeOps(L, s);
            BuildJob job = new BuildJob(level, UUID.randomUUID(), ops, "test");
            long t0 = System.nanoTime();
            while (!job.step(System.nanoTime() + 1_000_000_000L)) { }
            long ms = (System.nanoTime() - t0) / 1_000_000;

            // 1) все поставленные блоки должны «держаться» и совпадать с планом
            Map<String, Integer> unstable = new TreeMap<>();
            int panes = 0, connectedPanes = 0, doors = 0, ladders = 0;
            Set<Long> seen = new HashSet<>();
            for (int i = ops.size() - 1; i >= 0; i--) {
                if (ops.kinds.getByte(i) != OpList.SET) continue;
                long l = ops.pos.getLong(i);
                if (!seen.add(l)) continue;
                BlockPos p = BlockPos.of(l);
                BlockState st = level.getBlockState(p);
                if (st.isAir()) continue;
                if (!st.canSurvive(level, p)) {
                    unstable.merge(st.getBlock().getDescriptionId(), 1, Integer::sum);
                    CityGen.LOG.info("WORLDTEST unstable {} at {} below={} above={}", st, p, level.getBlockState(p.below()), level.getBlockState(p.above()));
                }
                if (st.getBlock() instanceof IronBarsBlock) {
                    panes++;
                    if (st.getValue(BlockStateProperties.NORTH) || st.getValue(BlockStateProperties.SOUTH)
                            || st.getValue(BlockStateProperties.EAST) || st.getValue(BlockStateProperties.WEST)) connectedPanes++;
                }
                if (st.getBlock() instanceof DoorBlock) doors++;
                if (st.getBlock() instanceof LadderBlock) ladders++;
            }
            CityGen.LOG.info("WORLDTEST {} {}: placed={} in {} ms, panes={} connected={}, doors={}, ladders={}, unstable={}",
                    dirs[k], s.type, job.placed, ms, panes, connectedPanes, doors, ladders, unstable);
            if (job.placed < 500) problems.add(dirs[k] + ": слишком мало блоков " + job.placed);
            if (!unstable.isEmpty()) problems.add(dirs[k] + ": неустойчивые блоки " + unstable);
            if (panes > 0 && connectedPanes < panes / 2) problems.add(dirs[k] + ": стёкла не соединились " + connectedPanes + "/" + panes);
            if (doors == 0) problems.add(dirs[k] + ": нет дверей");

            // 1б) над кварталом и вокруг него не осталось частей деревьев
            if ((k == 0 || k == 2) && ops.floodBox != null) {
                int[] b = ops.floodBox;
                int left = 0, total = 0;
                it.unimi.dsi.fastutil.longs.LongOpenHashSet placedByUs = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                for (int i = 0; i < ops.size(); i++) if (ops.kinds.getByte(i) == OpList.SET) placedByUs.add(ops.pos.getLong(i));
                BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
                for (int x = b[0]; x <= b[2]; x++)
                    for (int z = b[1]; z <= b[3]; z++)
                        for (int y = L.streetY - 3; y < L.streetY + 60; y++) {
                            BlockState t = level.getBlockState(mp.set(x, y, z));
                            if (!isNaturalTree(t) || placedByUs.contains(BlockPos.asLong(x, y, z))) continue;
                            total++;
                            if (x >= b[0] + 8 && x <= b[2] - 8 && z >= b[1] + 8 && z <= b[3] - 8) left++;
                        }
                CityGen.LOG.info("WORLDTEST {} trees: left inside site+6 = {}, in outer belt = {}", dirs[k], left, total - left);
                if (left > 0) problems.add(dirs[k] + ": остались части деревьев над кварталом: " + left);
            }
            // 2) отмена возвращает исходное состояние
            UndoRecord rec = job.undo;
            Map<Long, BlockState> original = new HashMap<>();
            for (int i = 0; i < rec.size(); i++) original.putIfAbsent(rec.pos.getLong(i), rec.states.get(i));
            BuildJob undo = new BuildJob(level, UUID.randomUUID(), rec);
            while (!undo.step(System.nanoTime() + 1_000_000_000L)) { }
            int mismatch = 0;
            for (var e : original.entrySet())
                if (level.getBlockState(BlockPos.of(e.getKey())) != e.getValue()) mismatch++;
            CityGen.LOG.info("WORLDTEST {} undo: restored={} mismatches={}", dirs[k], original.size(), mismatch);
            if (mismatch > 0) problems.add(dirs[k] + ": после отмены отличается блоков: " + mismatch);
        }
        if (problems.isEmpty()) {
            CityGen.LOG.info("WORLDTEST PASSED");
            h.succeed();
        } else {
            CityGen.LOG.error("WORLDTEST FAILED: {}", problems);
            h.fail(String.join("; ", problems));
        }
    }
}
