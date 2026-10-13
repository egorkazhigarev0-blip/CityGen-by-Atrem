package com.citygen.server;

import com.citygen.CityGen;
import com.citygen.gen.*;
import com.citygen.gen.Blueprint;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/** Очередь построек и история отмен (по игроку). */
public final class JobManager {
    private static final long BUDGET_NS = 30_000_000L; // 30 мс на тик
    /** Для съёмки трейлера: другой бюджет времени на тик и рост всего города «этаж за этажом». */
    public static long budgetOverrideNs = 0;
    public static boolean riseTogether = false;
    private static final int MAX_UNDO = 5;

    private static BuildJob current;
    private static final Map<UUID, Deque<UndoRecord>> undoStacks = new HashMap<>();
    private static int tickCounter;

    private JobManager() {}

    public static boolean allowed(ServerPlayer sp) {
        return sp.hasPermissions(2) || sp.isCreative() || sp.server.isSingleplayerOwner(sp.getGameProfile());
    }

    private static void msg(ServerPlayer sp, String text, ChatFormatting color) {
        sp.sendSystemMessage(Component.literal("[CityGen] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(text).withStyle(color)));
    }

    public static void startBuild(ServerPlayer sp, GenSettings s, BlockPos anchor, Direction look) {
        if (!allowed(sp)) { msg(sp, "Нужны права оператора или творческий режим.", ChatFormatting.RED); return; }
        if (current != null) { msg(sp, "Уже идёт постройка. Дождитесь окончания или /citygen cancel.", ChatFormatting.YELLOW); return; }
        s.sanitize();
        if (anchor.distSqr(new BlockPos(sp.getBlockX(), anchor.getY(), sp.getBlockZ())) > 192 * 192) {
            msg(sp, "Вы слишком далеко от рамок превью. Подойдите ближе или нажмите «Сюда» в редакторе.", ChatFormatting.YELLOW);
            return;
        }
        ServerLevel level = sp.serverLevel();
        long t0 = System.nanoTime();

        Layout layout = Layout.compute(s, anchor, look, level);
        OpList all = makeOps(layout, s);
        String title = layout.plans.size() + " зд. (" + (s.arrangement == GenSettings.Arrangement.PLANNER ? "кварталы" : s.type.label) + ")";
        current = new BuildJob(level, sp.getUUID(), all, title);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        msg(sp, String.format("План готов за %d мс: %d зданий, %,d операций. Строю…", ms, layout.plans.size(), all.size()),
                ChatFormatting.WHITE);
        if (layout.warnings > 0)
            msg(sp, layout.warnings + " зд. понижено — не помещались по высоте мира.", ChatFormatting.YELLOW);
    }

    /**
     * Полный список операций: сначала расчистка (столбцы до самого верха, деревья целиком),
     * потом фундаменты, улицы, участки и здания снизу вверх.
     */
    public static OpList makeOps(Layout layout, GenSettings s) {
        OpList oldClears = new OpList(), founds = new OpList(), streetSets = new OpList(), sets = new OpList();
        StreetGenerator.addOps(layout, s, oldClears, founds, streetSets);
        LotGenerator.addOps(layout, s, oldClears, founds, streetSets);
        for (Plan p : layout.plans) addBlueprint(p, BuildingGenerator.generate(p, s), s, founds, sets);
        OpList all = new OpList();
        if (s.clearSite) all.addAll(columnClears(layout, s));
        if (riseTogether) sets = sortByY(sets);
        all.addAll(founds); all.addAll(streetSets); all.addAll(sets);
        return all;
    }

    /**
     * Расчистка квартала: внутри — всё выше земли до верха столбца; вокруг полосой 6 блоков — только растительность.
     * Задетые деревья удаляются целиком (ствол и крона), чтобы ничего не висело в воздухе.
     */
    static OpList columnClears(Layout L, GenSettings s) {
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap full = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        full.defaultReturnValue(Integer.MIN_VALUE);
        int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE};
        java.util.function.BiConsumer<BlockPos, Integer> mark = (pos, base) -> {
            long k = BlockPos.asLong(pos.getX(), 0, pos.getZ());
            int cur = full.get(k);
            if (cur == Integer.MIN_VALUE || base < cur) full.put(k, (int) base);
            box[0] = Math.min(box[0], pos.getX()); box[1] = Math.min(box[1], pos.getZ());
            box[2] = Math.max(box[2], pos.getX()); box[3] = Math.max(box[3], pos.getZ());
            box[4] = Math.min(box[4], base);
        };
        int M = Blueprint.M;
        for (Plan p : L.plans)
            for (int x = -M - p.yardSide; x <= p.w + M - 1 + p.yardSide; x++)
                for (int z = -M - p.yardFront; z <= p.d + M - 1; z++) mark.accept(p.toWorld(x, 0, z), p.baseY);
        for (Layout.Strip st : L.strips)
            for (int u = st.u0; u <= st.u1; u++) for (int v = st.v0; v <= st.v1; v++) mark.accept(L.site(u, v, 0), L.streetY);
        for (LotPlan lp : L.lots)
            for (int x = lp.minX; x <= lp.maxX; x++) for (int z = lp.minZ; z <= lp.maxZ; z++) mark.accept(new BlockPos(x, 0, z), lp.baseY);
        OpList ops = new OpList();
        if (full.isEmpty()) return ops;
        BlockState air = Blocks.AIR.defaultBlockState();
        boolean flat = s.ground == GenSettings.GroundMode.PLAYER;
        int ring = 6;
        for (int x = box[0] - ring; x <= box[2] + ring; x++)
            for (int z = box[1] - ring; z <= box[3] + ring; z++) {
                int base = full.get(BlockPos.asLong(x, 0, z));
                boolean inside = x >= box[0] && x <= box[2] && z >= box[1] && z <= box[3];
                if (base != Integer.MIN_VALUE) ops.add(new BlockPos(x, base, z), air, OpList.COLUMN_FULL);
                else if (inside && flat) ops.add(new BlockPos(x, L.streetY, z), air, OpList.COLUMN_FULL);
                else ops.add(new BlockPos(x, box[4] - 8, z), air, OpList.COLUMN_VEG);
            }
        ops.floodBox = new int[]{box[0] - 14, box[1] - 14, box[2] + 14, box[3] + 14, box[4] - 10};
        return ops;
    }

    private static void addBlueprint(Plan p, Blueprint bp, GenSettings s, OpList founds, OpList sets) {
        var rot = p.rotation();
        BlockState found = com.citygen.gen.Palettes.get(p.paletteId).foundation.defaultBlockState();
        for (int y = 0; y < bp.h; y++)
            for (int z = bp.minZ(); z <= bp.maxZ(); z++)
                for (int x = bp.minX(); x <= bp.maxX(); x++) {
                    BlockState st = bp.get(x, y, z);
                    if (st == null) continue;
                    if (st.isAir() && s.clearSite) continue; // воздух уже расчищен столбцами
                    sets.add(p.toWorld(x, y, z), st.rotate(rot), OpList.SET);
                    if (y == 0 && s.foundation && !st.isAir()) founds.add(p.toWorld(x, -1, z), found, OpList.FOUNDATION);
                }
    }

    private static OpList sortByY(OpList in) {
        Integer[] idx = new Integer[in.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, java.util.Comparator.comparingInt(i -> BlockPos.getY(in.pos.getLong(i))));
        OpList out = new OpList();
        for (int i : idx) { out.pos.add(in.pos.getLong(i)); out.states.add(in.states.get(i)); out.kinds.add(in.kinds.getByte(i)); }
        return out;
    }

    public static boolean busy() { return current != null; }

    public static void undo(ServerPlayer sp) {
        if (!allowed(sp)) { msg(sp, "Нет прав.", ChatFormatting.RED); return; }
        if (current != null) { msg(sp, "Сначала дождитесь окончания постройки или /citygen cancel.", ChatFormatting.YELLOW); return; }
        Deque<UndoRecord> st = undoStacks.get(sp.getUUID());
        if (st == null || st.isEmpty()) { msg(sp, "Нечего отменять.", ChatFormatting.YELLOW); return; }
        UndoRecord rec = st.pop();
        ServerLevel level = sp.server.getLevel(rec.dimension);
        if (level == null) { msg(sp, "Измерение недоступно.", ChatFormatting.RED); return; }
        current = new BuildJob(level, sp.getUUID(), rec);
        msg(sp, "Отменяю: " + rec.description + " (" + String.format("%,d", rec.size()) + " блоков)…", ChatFormatting.WHITE);
    }

    public static void cancel(ServerPlayer sp) {
        if (current == null) { msg(sp, "Сейчас ничего не строится.", ChatFormatting.YELLOW); return; }
        finish(sp.server, true);
    }

    public static void tick(MinecraftServer server) {
        if (current == null) return;
        boolean done = current.step(System.nanoTime() + (budgetOverrideNs > 0 ? budgetOverrideNs : BUDGET_NS));
        if (done) { finish(server, false); return; }
        if (++tickCounter % 10 == 0) {
            ServerPlayer sp = server.getPlayerList().getPlayer(current.owner);
            if (sp != null) {
                int pct = (int) (100L * current.done() / Math.max(1, current.total()));
                sp.displayClientMessage(Component.literal((current.isUndo ? "Отмена: " : "Строю: ") + pct + "%  ("
                        + String.format("%,d", current.placed) + " блоков)").withStyle(ChatFormatting.AQUA), true);
            }
        }
    }

    private static void finish(MinecraftServer server, boolean cancelled) {
        BuildJob job = current;
        current = null;
        ServerPlayer sp = server.getPlayerList().getPlayer(job.owner);
        double sec = (System.currentTimeMillis() - job.startedAt) / 1000.0;
        if (!job.isUndo && job.undo != null && job.undo.size() > 0) {
            Deque<UndoRecord> st = undoStacks.computeIfAbsent(job.owner, k -> new ArrayDeque<>());
            st.push(job.undo);
            while (st.size() > MAX_UNDO) st.removeLast();
        }
        if (sp == null) return;
        sp.displayClientMessage(Component.empty(), true);
        if (job.isUndo) msg(sp, String.format("Отменено за %.1f с.", sec), ChatFormatting.GREEN);
        else if (cancelled) msg(sp, "Постройка остановлена. Уже поставленное можно убрать: /citygen undo", ChatFormatting.YELLOW);
        else msg(sp, String.format("Готово: %s, %,d блоков за %.1f с. Отмена — кнопка «Отменить» или /citygen undo",
                job.title, job.placed, sec), ChatFormatting.GREEN);
        CityGen.LOG.info("CityGen job finished: {} blocks in {}s", job.placed, sec);
    }

    public static void clear() {
        current = null;
        undoStacks.clear();
    }
}
