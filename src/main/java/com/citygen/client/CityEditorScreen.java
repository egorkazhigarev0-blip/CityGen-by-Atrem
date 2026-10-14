package com.citygen.client;

import com.citygen.gen.GenSettings;
import com.citygen.gen.GenSettings.*;
import com.citygen.gen.Layout;
import com.citygen.gen.LotPlan;
import com.citygen.gen.Plan;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Редактор города: снимок местности сверху, на котором игрок сам рисует кварталы, двигает их
 * и меняет размер мышью (с привязкой к соседям и общей дороге), а справа настраивает выбранный квартал.
 * Отсюда же — правка кварталов прямо в мире и постройка одного квартала или всего города.
 */
public class CityEditorScreen extends Screen {
    private enum Tool { SELECT, DRAW }
    private enum Drag { NONE, PAN, MOVE, RESIZE, DRAW }

    private static final int[] RADII = {96, 128, 160, 192, 256};
    private static Tool tool = Tool.SELECT;
    private static double viewX = Double.NaN, viewZ;
    private static float zoom;
    private static int sideScroll;
    private static QuarterKind lastKind = QuarterKind.MIXED;
    private static final Deque<List<Quarter>> UNDO = new ArrayDeque<>();

    private final Screen parent;
    private int mapX, mapY, mapW, mapH, sideX, sideY, sideW, sideH, barY;
    private final List<Ui.W> side = new ArrayList<>();
    private int sideBottom;
    private EditBox nameBox;

    private Drag drag = Drag.NONE;
    private boolean wideTitle;
    private int handle;            // 1 — запад, 2 — восток, 4 — север, 8 — юг
    private double pressX, pressY;
    private Quarter orig;
    private boolean undoPushed;
    private int drawX0, drawZ0, drawX1, drawZ1;
    private final List<int[]> guides = new ArrayList<>(); // {0 — линия x / 1 — линия z, координата}
    private String toast = "";
    private long toastAt;

    public CityEditorScreen(Screen parent) {
        super(Component.literal("Редактор города"));
        this.parent = parent;
        GenSettings s = ClientState.settings;
        s.arrangement = Arrangement.CITY;
        if (ClientState.anchor == null) ClientState.anchorHere();
        ClientState.previewActive = true;
        ClientState.worldEdit = false;
        if (ClientState.selected >= s.quarters.size()) ClientState.selected = -1;
        ClientState.recompute();
    }

    private static GenSettings s() { return ClientState.settings; }
    private static List<Quarter> qs() { return ClientState.settings.quarters; }
    private static Quarter sel() { return ClientState.selectedQuarter(); }

    @Override public boolean isPauseScreen() { return false; }

    // =====================================================================
    // Построение
    // =====================================================================
    @Override
    protected void init() {
        open = this;
        clearWidgets();
        side.clear();
        nameBox = null;
        int pad = 6;
        sideW = Mth.clamp(width / 4, 150, 196);
        mapX = pad; mapY = 30; mapW = width - sideW - pad * 3; mapH = height - 30 - 32;
        sideX = mapX + mapW + pad; sideY = mapY + 22; sideH = mapY + mapH - sideY;
        barY = height - 28;

        // снимок местности и вид
        Player pl = minecraft.player;
        int r = ClientPrefs.get().satRadius;
        if (pl != null && (!SatMap.ready() || !SatMap.sameWorld() || SatMap.radius != r
                || Math.abs(SatMap.cx - pl.getBlockX()) > r / 2 || Math.abs(SatMap.cz - pl.getBlockZ()) > r / 2))
            SatMap.start(pl.getBlockX(), pl.getBlockZ(), r);
        if (Double.isNaN(viewX) || zoom <= 0) resetView();

        // верхняя панель: на узком экране — короткие подписи
        wideTitle = width >= 600;
        int x = wideTitle ? 150 : 32, y = 6;
        int tier = x + 376 <= width - 28 ? 0 : x + 312 <= width - 28 ? 1 : 2; // 0 — полные подписи, 2 — самые короткие
        boolean compact = tier == 2;
        int wSel = tier == 0 ? 58 : tier == 1 ? 52 : 46, wAdd = tier == 0 ? 78 : tier == 1 ? 70 : 22, wCyc = tier == 0 ? 104 : 64,
                wIco = tier == 0 ? 62 : tier == 1 ? 54 : 20;
        addRenderableWidget(new Ui.Button(x, y, wSel, 18, "Выбор", tool == Tool.SELECT ? Ui.Button.Kind.PRIMARY : Ui.Button.Kind.NORMAL,
                () -> { tool = Tool.SELECT; rebuild(); }).help("Выбирать, двигать и растягивать кварталы мышью. Клавиша V."));
        x += wSel + 3;
        addRenderableWidget(new Ui.Button(x, y, wAdd, 18, compact ? "+" : "+ Квартал", tool == Tool.DRAW ? Ui.Button.Kind.PRIMARY : Ui.Button.Kind.NORMAL,
                () -> { tool = Tool.DRAW; rebuild(); }).help("Нарисовать новый квартал: зажмите ЛКМ на карте и растяните прямоугольник. Клавиша Q."));
        x += wAdd + 8;
        List<Integer> radii = new ArrayList<>();
        for (int v : RADII) radii.add(v);
        addRenderableWidget(new Ui.Cycle<>(x, y, wCyc, 18, tier == 0 ? "Обзор" : "", radii, ClientPrefs.get().satRadius, v -> v + " м",
                v -> "Радиус снимка местности вокруг вас. Видно только то, что уже загружено (дальность прорисовки).",
                v -> { ClientPrefs.get().satRadius = v; ClientPrefs.save(); retake(); }));
        x += wCyc + 3;
        addRenderableWidget(new Ui.Button(x, y, wIco, 18, compact ? "↻" : "Снимок", Ui.Button.Kind.NORMAL, this::retake)
                .help("Переснять местность вокруг вас (после постройки, полёта или загрузки новых чанков)."));
        x += wIco + 3;
        addRenderableWidget(new Ui.Button(x, y, wIco, 18, compact ? "◎" : "К вам", Ui.Button.Kind.NORMAL, this::resetView)
                .help("Вернуть карту к вашему положению. Клавиша Home."));
        addRenderableWidget(new Ui.Button(width - 24, 6, 18, 18, "✕", Ui.Button.Kind.GHOST, this::onClose).help("Вернуться в редактор (Esc)."));

        // нижняя панель
        int bw = Math.min(124, (mapW - 6) / 3);
        boolean shortB = bw < 104;
        int bx = mapX + mapW - bw;
        Quarter q = sel();
        addRenderableWidget(new Ui.Button(sideX, barY, sideW, 20, "Готово", Ui.Button.Kind.PRIMARY, this::onClose)
                .help("Сохранить город и вернуться в главное окно редактора."));
        Ui.Button bCity = new Ui.Button(bx, barY, bw, 20, shortB ? "Построить всё" : "Построить город", Ui.Button.Kind.NORMAL, this::buildCity)
                .help("Построить все кварталы сразу. Прогресс — над хотбаром, отмена — «Отменить» или /citygen undo.");
        bCity.active = !qs().isEmpty();
        addRenderableWidget(bCity);
        bx -= bw + 3;
        Ui.Button bOne = new Ui.Button(bx, barY, bw, 20, shortB ? "Построить кв." : "Построить квартал", Ui.Button.Kind.NORMAL, this::buildSelected)
                .help("Построить только выбранный квартал — остальные останутся в плане.");
        bOne.active = q != null;
        addRenderableWidget(bOne);
        bx -= bw + 3;
        Ui.Button bWorld = new Ui.Button(bx, barY, bw, 20, shortB ? "В мире" : "Править в мире", Ui.Button.Kind.NORMAL, this::worldEdit)
                .help("Закрыть окно и двигать кварталы прямо в мире: стрелки — сдвиг, R — фасады, V — сюда, Enter — назад. Клавиша E.");
        bWorld.active = !qs().isEmpty();
        addRenderableWidget(bWorld);

        buildSide();
    }

    private void rebuild() { init(); }

    // ---------------- для записи роликов (dev/Trailer) ----------------
    /** Открытый сейчас редактор города (или null). */
    public static CityEditorScreen open;
    /** Нарисованный «курсор» в координатах интерфейса (только для роликов). */
    public static double[] devCursor;

    public double[] toScreen(double wx, double wz) { return new double[]{sx(wx), sy(wz)}; }

    public void devRefresh() { rebuild(); }

    private void retake() {
        Player pl = minecraft.player;
        if (pl != null) SatMap.start(pl.getBlockX(), pl.getBlockZ(), ClientPrefs.get().satRadius);
        rebuild();
    }

    private void resetView() {
        Player pl = minecraft.player;
        viewX = pl != null ? pl.getX() : 0;
        viewZ = pl != null ? pl.getZ() : 0;
        int r = ClientPrefs.get().satRadius;
        zoom = Mth.clamp(Math.min(mapW, mapH) / (2f * r), 0.35f, 8f);
    }

    // ---------------- боковая панель ----------------
    private <T extends Ui.W> T add(T w) { side.add(w); return w; }

    private int heading(int y, String t) { add(new Ui.Label(sideX, y + 2, sideW - 6, t, Ui.accent())); return y + 16; }

    private int text(int y, String t, int color) {
        Ui.Text tx = add(new Ui.Text(sideX + 2, y + 1, sideW - 10, t, color));
        return y + tx.getHeight() + 5;
    }

    private void changed() {
        ClientState.requestRecompute();
    }

    private <E extends Enum<E> & Labeled> int cyc(int y, String name, E[] vals, E init, java.util.function.Consumer<E> on) {
        add(Ui.Cycle.of(sideX, y, sideW - 6, name, vals, init, v -> { pushUndo(); on.accept(v); changed(); }));
        return y + 23;
    }

    private int sld(int y, String name, int min, int max, int v, java.util.function.IntFunction<String> fmt,
                    java.util.function.IntConsumer on, String help) {
        add(new Ui.Slider(sideX, y, sideW - 6, 20, name, min, max, v, fmt, i -> { on.accept(i); changed(); }).help(help));
        return y + 23;
    }

    private void buildSide() {
        int y = sideY - sideScroll;
        Quarter q = sel();
        if (q != null) y = sideQuarter(q, y);
        else y = sideCity(y);
        sideBottom = y + sideScroll;
        int max = Math.max(0, sideBottom - sideY - sideH);
        if (sideScroll > max) { sideScroll = max; rebuild(); return; }
        for (Ui.W w : side) {
            w.visible = w.getY() >= sideY - 1 && w.getY() + w.getHeight() <= sideY + sideH + 1;
            addWidget(w);
        }
        if (nameBox != null) {
            nameBox.visible = nameBox.getY() >= sideY && nameBox.getY() + 18 <= sideY + sideH;
            addWidget(nameBox);
        }
    }

    private int sideQuarter(Quarter q, int y) {
        int qi = ClientState.selected;
        nameBox = new EditBox(font, sideX + 4, y + 6, sideW - 14, 10, Component.literal("Название"));
        nameBox.setMaxLength(24);
        nameBox.setValue(q.name);
        nameBox.setBordered(false);
        nameBox.setTextColor(Ui.TEXT);
        nameBox.setResponder(t -> { if (!t.isBlank()) q.name = t; });
        y += 22;
        SatMap.Stats st = SatMap.stats(q.x0, q.z0, q.x1, q.z1);
        String info = q.w() + " × " + q.d() + " м   X " + q.x0 + "…" + q.x1 + "   Z " + q.z0 + "…" + q.z1;
        y = text(y, "§7" + info, Ui.SUB) - 2;
        if (st.unknownPct() < 60) {
            String relief = "Перепад высот: " + st.relief() + " м" + (st.waterPct() > 0 ? "   вода: " + st.waterPct() + "%" : "");
            y = text(y, (st.relief() > 12 || st.waterPct() > 25 ? "§e⚠ " : "§a✔ ") + relief, Ui.SUB) - 2;
        } else y = text(y, "§8Местность не снята (далеко от вас)", Ui.SUB) - 2;
        y += 3;
        y = heading(y, "Застройка");
        y = cyc(y, "Тип", QuarterKind.values(), q.kind, v -> { q.kind = v; lastKind = v; rebuild(); });
        y = cyc(y, "Стиль", StyleMode.values(), q.style, v -> q.style = v);
        y = sld(y, "Этажей от", 0, 80, q.floorsMin, v -> v == 0 ? "авто" : String.valueOf(v), v -> q.floorsMin = v,
                "Минимальная этажность. «Авто» — по типу квартала.");
        y = sld(y, "Этажей до", 0, 80, q.floorsMax, v -> v == 0 ? "авто" : String.valueOf(v), v -> q.floorsMax = v,
                "Максимальная этажность. «Авто» — по типу квартала.");
        y = cyc(y, "Высоты", HeightProfile.values(), q.heights, v -> q.heights = v);
        y = cyc(y, "1-й этаж", GroundFloor.values(), q.ground, v -> q.ground = v);
        y = sld(y, "Стройки", -1, 100, q.construction, v -> v < 0 ? "как везде" : v + "%", v -> q.construction = v,
                "Доля строящихся зданий в этом квартале. «Как везде» — из общих настроек города.");
        y = heading(y, "Планировка");
        y = cyc(y, "Фасады", QFacing.values(), q.facing, v -> q.facing = v);
        y = cyc(y, "Двор", Courtyard.values(), q.courtyard, v -> q.courtyard = v);
        y = sld(y, "Ширина участка", 0, 64, q.lotWidth, v -> v < 6 ? "авто" : v + " м", v -> q.lotWidth = v < 6 ? 0 : v,
                "Ширина участка одного здания вдоль улицы.");
        y = sld(y, "Глубина участка", 0, 64, q.lotDepth, v -> v < 7 ? "авто" : v + " м", v -> q.lotDepth = v < 7 ? 0 : v,
                "Глубина ряда домов от улицы внутрь квартала.");
        y = sld(y, "Между домами", -1, 16, q.spacing, v -> v < 0 ? "авто" : v + " м", v -> q.spacing = v,
                "Расстояние между зданиями. 0–1 — сплошная застройка с глухими боковыми стенами.");
        y = cyc(y, "Улица вокруг", StreetMode.values(), q.street, v -> q.street = v);
        y = sld(y, "Основание", -16, 16, q.raise, v -> v == 0 ? "по рельефу" : (v > 0 ? "+" : "") + v + " м", v -> q.raise = v,
                "Поднять или опустить весь квартал относительно среднего уровня земли.");
        y = heading(y, "Действия");
        int hw = (sideW - 9) / 2;
        add(new Ui.Button(sideX, y, hw, 20, "Новый вид", Ui.Button.Kind.NORMAL, () -> { pushUndo(); q.seed++; changed(); })
                .help("Другие здания в этом же квартале (новый сид квартала)."));
        add(new Ui.Button(sideX + hw + 3, y, hw, 20, "Копия", Ui.Button.Kind.NORMAL, this::duplicate)
                .help("Копия квартала рядом, через общую дорогу (Ctrl+D)."));
        y += 23;
        add(new Ui.Button(sideX, y, hw, 20, "К вам", Ui.Button.Kind.NORMAL, () -> moveToPlayer(q))
                .help("Перенести квартал перед вами."));
        add(new Ui.Button(sideX + hw + 3, y, hw, 20, "Удалить", Ui.Button.Kind.DANGER, () -> deleteQuarter(qi)).help("Удалить квартал (Delete)."));
        y += 23;
        add(new Ui.Button(sideX, y, sideW - 6, 20, "‹ Все кварталы", Ui.Button.Kind.GHOST, () -> select(-1))
                .help("Снять выделение и вернуться к списку кварталов."));
        return y + 26;
    }

    private int sideCity(int y) {
        GenSettings s = s();
        if (qs().isEmpty()) {
            y = text(y, "§fНа карте пока нет кварталов.", Ui.TEXT);
            y = text(y, "§7Нажмите «+ Квартал» и растяните прямоугольник на свободном месте. "
                    + "Кварталы с зазором 13 м делят общую дорогу — привязка подскажет.", Ui.SUB);
            add(new Ui.Button(sideX, y, sideW - 6, 20, "+ Нарисовать квартал", Ui.Button.Kind.PRIMARY, () -> { tool = Tool.DRAW; rebuild(); }));
            y += 26;
        } else {
            y = heading(y, "Кварталы (" + qs().size() + "/" + GenSettings.MAX_QUARTERS + ")");
            for (int i = 0; i < qs().size(); i++) {
                Quarter q = qs().get(i);
                final int qi = i;
                boolean bad = conflict(i) != null;
                add(new Ui.Tile(sideX, y, sideW - 6, 24, (bad ? "⚠ " : "") + q.name, q.kind.label() + " • " + q.w() + "×" + q.d() + " м",
                        q.kind.color, () -> false, () -> { select(qi); centerOn(qs().get(qi)); }).help("Выбрать и показать квартал на карте."));
                y += 27;
            }
            add(new Ui.Button(sideX, y, sideW - 6, 20, "+ Нарисовать квартал", Ui.Button.Kind.NORMAL, () -> { tool = Tool.DRAW; rebuild(); }));
            y += 23;
            add(new Ui.Button(sideX, y, sideW - 6, 20, "Перенести город к вам", Ui.Button.Kind.NORMAL, this::moveCityToPlayer)
                    .help("Сдвинуть все кварталы так, чтобы город начинался перед вами (взаимное расположение сохранится)."));
            y += 26;
        }
        y = heading(y, "Весь город");
        y = sld(y, "Строящиеся здания", 0, 100, s.construction, v -> v + "%", v -> s.construction = v,
                "Доля зданий, которые ещё строятся: каркас, леса, краны. В жизни это немного — по умолчанию 3%.");
        y = cyc(y, "Озеленение", Greenery.values(), s.greenery, v -> s.greenery = v);
        y = cyc(y, "Стиль", StyleMode.values(), s.style, v -> s.style = v);
        y = cyc(y, "Интерьер", Interior.values(), s.interior, v -> s.interior = v);
        y = heading(y, "Карта");
        add(new Ui.Toggle(sideX, y, sideW - 6, 20, "Сетка чанков", ClientPrefs.get().mapGrid, v -> { ClientPrefs.get().mapGrid = v; ClientPrefs.save(); })
                .help("Сетка 16×16 блоков поверх снимка местности."));
        y += 23;
        add(new Ui.Toggle(sideX, y, sideW - 6, 20, "План застройки", ClientPrefs.get().mapPlan, v -> { ClientPrefs.get().mapPlan = v; ClientPrefs.save(); })
                .help("Показывать пятна будущих зданий, парков и площадей внутри кварталов."));
        y += 23;
        y = heading(y, "Подсказки");
        return text(y, "§7ЛКМ — выбрать и двигать, маркеры — размер, ПКМ или СКМ — сдвиг карты, колесо — масштаб. "
                + "Alt — без привязки. Стрелки — сдвиг на 1 м (Shift — на 8), R — фасады, Delete — удалить, Ctrl+Z — отмена.", Ui.SUB);
    }

    // =====================================================================
    // Действия
    // =====================================================================
    private void select(int i) {
        if (ClientState.selected != i) sideScroll = 0;
        ClientState.selected = i;
        rebuild();
    }

    private void centerOn(Quarter q) {
        viewX = (q.x0 + q.x1 + 1) / 2.0;
        viewZ = (q.z0 + q.z1 + 1) / 2.0;
    }

    private static void pushUndo() {
        List<Quarter> snap = new ArrayList<>();
        for (Quarter q : qs()) snap.add(q.copyQ());
        UNDO.push(snap);
        while (UNDO.size() > 40) UNDO.removeLast();
    }

    private void undo() {
        if (UNDO.isEmpty()) { toast("Отменять нечего"); return; }
        List<Quarter> snap = UNDO.pop();
        qs().clear();
        qs().addAll(snap);
        if (ClientState.selected >= qs().size()) ClientState.selected = qs().size() - 1;
        changed();
        rebuild();
    }

    private void toast(String t) { toast = t; toastAt = System.currentTimeMillis(); }

    private void deleteQuarter(int qi) {
        if (qi < 0 || qi >= qs().size()) return;
        pushUndo();
        String n = qs().get(qi).name;
        qs().remove(qi);
        ClientState.selected = -1;
        toast("Квартал «" + n + "» удалён (Ctrl+Z — вернуть)");
        changed();
        rebuild();
    }

    private void duplicate() {
        Quarter q = sel();
        if (q == null) return;
        if (qs().size() >= GenSettings.MAX_QUARTERS) { toast("Не больше " + GenSettings.MAX_QUARTERS + " кварталов"); return; }
        pushUndo();
        Quarter c = q.copyQ();
        c.name = nextName(q.name);
        c.seed = q.seed + 17;
        int gap = minGap(q, c);
        c.moveBy(q.w() + gap, 0);
        qs().add(c);
        ClientState.selected = qs().size() - 1;
        changed();
        rebuild();
    }

    private static String nextName(String base) {
        String b = base.replaceAll("\\s+\\d+$", "");
        for (int k = 2; k < 100; k++) {
            String n = b + " " + k;
            boolean used = false;
            for (Quarter q : qs()) if (q.name.equals(n)) used = true;
            if (!used) return n.length() > 24 ? n.substring(0, 24) : n;
        }
        return b;
    }

    private void moveToPlayer(Quarter q) {
        Player pl = minecraft.player;
        if (pl == null) return;
        pushUndo();
        Direction f = pl.getDirection();
        int ring = ring(q) + 2;
        int cx = pl.getBlockX() + f.getStepX() * (q.w() / 2 + ring), cz = pl.getBlockZ() + f.getStepZ() * (q.d() / 2 + ring);
        q.moveBy(cx - (q.x0 + q.w() / 2), cz - (q.z0 + q.d() / 2));
        centerOn(q);
        changed();
        rebuild();
    }

    private void moveCityToPlayer() {
        if (qs().isEmpty()) return;
        pushUndo();
        int[] c = ClientState.moveCityToPlayer();
        if (c != null) { viewX = c[0]; viewZ = c[1]; }
        changed();
        rebuild();
    }

    private void buildSelected() {
        if (sel() == null) return;
        String err = overlapError();
        if (err != null) { toast(err); return; }
        ClientState.save();
        ClientState.buildQuarter(ClientState.selected);
        minecraft.setScreen(null);
    }

    private void buildCity() {
        if (qs().isEmpty()) return;
        String err = overlapError();
        if (err != null) { toast(err); return; }
        ClientState.save();
        ClientState.buildCity();
        minecraft.setScreen(null);
    }

    private void worldEdit() {
        if (qs().isEmpty()) return;
        if (ClientState.selected < 0) ClientState.selected = 0;
        ClientState.worldEdit = true;
        ClientState.previewActive = true;
        ClientState.save();
        minecraft.setScreen(null);
    }

    // =====================================================================
    // Проверки и привязка
    // =====================================================================
    static int ring(Quarter q) { return q.street == StreetMode.ROAD ? Layout.WALK + Layout.ROAD_W : q.street == StreetMode.SIDEWALK ? Layout.WALK : 0; }

    /** Минимальный зазор между кварталами: две дороги делят одну (13 м), иначе — ширина большего кольца улиц. */
    static int minGap(Quarter a, Quarter b) {
        if (a.street == StreetMode.ROAD && b.street == StreetMode.ROAD) return Layout.SHARED_GAP;
        return Math.max(ring(a), ring(b));
    }

    static int gapX(Quarter a, Quarter b) { return Math.max(b.x0 - a.x1 - 1, a.x0 - b.x1 - 1); }
    static int gapZ(Quarter a, Quarter b) { return Math.max(b.z0 - a.z1 - 1, a.z0 - b.z1 - 1); }

    /** Конфликт квартала i с другими: null — всё хорошо, иначе текст. */
    static String conflict(int i) {
        Quarter a = qs().get(i);
        for (int j = 0; j < qs().size(); j++) {
            if (j == i) continue;
            Quarter b = qs().get(j);
            int gx = gapX(a, b), gz = gapZ(a, b);
            if (gx < 0 && gz < 0) return "пересекается с «" + b.name + "»";
            int g = Math.max(gx, gz);
            if (g < minGap(a, b) && (gx < 0 || gz < 0)) return "слишком близко к «" + b.name + "» (нужно ≥ " + minGap(a, b) + " м)";
        }
        return null;
    }

    private static String overlapError() {
        for (int i = 0; i < qs().size(); i++)
            for (int j = i + 1; j < qs().size(); j++) {
                Quarter a = qs().get(i), b = qs().get(j);
                if (gapX(a, b) < 0 && gapZ(a, b) < 0) return "Кварталы «" + a.name + "» и «" + b.name + "» пересекаются — раздвиньте их";
            }
        return null;
    }

    private int snapThreshold() { return Math.max(2, Math.round(7 / zoom)); }

    /** Привязка сдвига: возвращает поправку по оси для всего квартала. */
    private int snapMove(Quarter q, boolean xAxis) {
        int a0 = xAxis ? q.x0 : q.z0, a1 = xAxis ? q.x1 : q.z1, len = a1 - a0 + 1;
        int best = Integer.MAX_VALUE, bestLine = 0;
        int thr = snapThreshold();
        for (int j = 0; j < qs().size(); j++) {
            if (j == ClientState.selected) continue;
            Quarter o = qs().get(j);
            int o0 = xAxis ? o.x0 : o.z0, o1 = xAxis ? o.x1 : o.z1;
            boolean beside = xAxis ? gapZ(q, o) < 0 : gapX(q, o) < 0;
            int gap = minGap(q, o);
            int[][] cands = beside
                    ? new int[][]{{o0, o0}, {o1 - len + 1, o1}, {o1 + 1 + gap, o1 + 1 + gap}, {o0 - gap - len, o0 - gap - 1}}
                    : new int[][]{{o0, o0}, {o1 - len + 1, o1}};
            for (int[] c : cands) {
                int d = c[0] - a0;
                if (Math.abs(d) <= thr && Math.abs(d) < Math.abs(best)) { best = d; bestLine = c[1]; }
            }
        }
        if (best == Integer.MAX_VALUE) return 0;
        guides.add(new int[]{xAxis ? 0 : 1, bestLine});
        return best;
    }

    /** Привязка края при растягивании. */
    private int snapEdge(Quarter q, int value, boolean xAxis, boolean low) {
        int thr = snapThreshold(), best = Integer.MAX_VALUE;
        for (int j = 0; j < qs().size(); j++) {
            if (j == ClientState.selected) continue;
            Quarter o = qs().get(j);
            int o0 = xAxis ? o.x0 : o.z0, o1 = xAxis ? o.x1 : o.z1, gap = minGap(q, o);
            int[] cands = low ? new int[]{o0, o1 + 1 + gap} : new int[]{o1, o0 - 1 - gap};
            for (int c : cands) if (Math.abs(c - value) <= thr && Math.abs(c - value) < Math.abs(best - value)) best = c;
        }
        if (best == Integer.MAX_VALUE) return value;
        guides.add(new int[]{xAxis ? 0 : 1, best});
        return best;
    }

    // =====================================================================
    // Координаты
    // =====================================================================
    private double sx(double wx) { return mapX + mapW / 2.0 + (wx - viewX) * zoom; }
    private double sy(double wz) { return mapY + mapH / 2.0 + (wz - viewZ) * zoom; }
    private double wx(double sx) { return viewX + (sx - mapX - mapW / 2.0) / zoom; }
    private double wz(double sy) { return viewZ + (sy - mapY - mapH / 2.0) / zoom; }
    private boolean inMap(double mx, double my) { return mx >= mapX && mx < mapX + mapW && my >= mapY && my < mapY + mapH; }

    private void fillW(GuiGraphics g, int x0, int z0, int x1, int z1, int c) {
        int a = (int) Math.floor(sx(x0)), b = (int) Math.floor(sy(z0)), cc = (int) Math.ceil(sx(x1 + 1)), d = (int) Math.ceil(sy(z1 + 1));
        if (cc <= a) cc = a + 1;
        if (d <= b) d = b + 1;
        g.fill(a, b, cc, d, c);
    }

    private void frameW(GuiGraphics g, int x0, int z0, int x1, int z1, int c, int t) {
        int a = (int) Math.floor(sx(x0)), b = (int) Math.floor(sy(z0)), cc = (int) Math.ceil(sx(x1 + 1)), d = (int) Math.ceil(sy(z1 + 1));
        g.fill(a, b, cc, b + t, c);
        g.fill(a, d - t, cc, d, c);
        g.fill(a, b, a + t, d, c);
        g.fill(cc - t, b, cc, d, c);
    }

    private int handleAt(Quarter q, double mx, double my) {
        if (q == null) return 0;
        double a = sx(q.x0), b = sy(q.z0), c = sx(q.x1 + 1), d = sy(q.z1 + 1);
        double r = 5;
        int h = 0;
        boolean inX = mx > a - r && mx < c + r, inZ = my > b - r && my < d + r;
        if (!inX || !inZ) return 0;
        if (Math.abs(mx - a) <= r) h |= 1;
        else if (Math.abs(mx - c) <= r) h |= 2;
        if (Math.abs(my - b) <= r) h |= 4;
        else if (Math.abs(my - d) <= r) h |= 8;
        return h;
    }

    private int quarterAt(double wx, double wz) {
        int x = (int) Math.floor(wx), z = (int) Math.floor(wz);
        if (ClientState.selected >= 0 && sel() != null && sel().contains(x, z)) return ClientState.selected;
        for (int i = qs().size() - 1; i >= 0; i--) if (qs().get(i).contains(x, z)) return i;
        return -1;
    }

    // =====================================================================
    // Ввод
    // =====================================================================
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!inMap(mx, my)) {
            for (GuiEventListener l : children())
                if (l instanceof Ui.W w && side.contains(w) && w.visible && w.isMouseOver(mx, my) && (my < sideY || my > sideY + sideH)) return false;
            if (nameBox != null && !nameBox.isMouseOver(mx, my)) nameBox.setFocused(false);
            return super.mouseClicked(mx, my, button);
        }
        if (nameBox != null) { nameBox.setFocused(false); setFocused(null); }
        pressX = mx; pressY = my;
        guides.clear();
        if (button == 1 || button == 2) { drag = Drag.PAN; return true; }
        if (button != 0) return false;
        int wx = (int) Math.floor(wx(mx)), wz = (int) Math.floor(wz(my));
        if (tool == Tool.DRAW) {
            drag = Drag.DRAW;
            drawX0 = drawX1 = wx; drawZ0 = drawZ1 = wz;
            return true;
        }
        int h = handleAt(sel(), mx, my);
        if (h != 0) {
            drag = Drag.RESIZE; handle = h; orig = sel().copyQ(); undoPushed = false;
            return true;
        }
        int qi = quarterAt(wx(mx), wz(my));
        if (qi >= 0) {
            if (qi != ClientState.selected) select(qi);
            drag = Drag.MOVE; orig = sel().copyQ(); undoPushed = false;
            return true;
        }
        if (ClientState.selected >= 0) select(-1);
        drag = Drag.PAN;
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        switch (drag) {
            case PAN -> { viewX -= dx / zoom; viewZ -= dy / zoom; return true; }
            case DRAW -> { drawX1 = (int) Math.floor(wx(mx)); drawZ1 = (int) Math.floor(wz(my)); return true; }
            case MOVE -> {
                Quarter q = sel();
                if (q == null) return true;
                int ddx = (int) Math.round((mx - pressX) / zoom), ddz = (int) Math.round((my - pressY) / zoom);
                if ((ddx != 0 || ddz != 0) && !undoPushed) { pushUndoFrom(orig); undoPushed = true; }
                q.x0 = orig.x0 + ddx; q.x1 = orig.x1 + ddx; q.z0 = orig.z0 + ddz; q.z1 = orig.z1 + ddz;
                guides.clear();
                if (!hasAltDown()) { q.moveBy(snapMove(q, true), 0); q.moveBy(0, snapMove(q, false)); }
                changed();
                return true;
            }
            case RESIZE -> {
                Quarter q = sel();
                if (q == null) return true;
                if (!undoPushed) { pushUndoFrom(orig); undoPushed = true; }
                int wx = (int) Math.round(wx(mx)), wz = (int) Math.round(wz(my));
                guides.clear();
                boolean snap = !hasAltDown();
                if ((handle & 1) != 0) q.x0 = Math.min(snap ? snapEdge(q, wx, true, true) : wx, q.x1 - GenSettings.MIN_Q + 1);
                if ((handle & 2) != 0) q.x1 = Math.max(snap ? snapEdge(q, wx - 1, true, false) : wx - 1, q.x0 + GenSettings.MIN_Q - 1);
                if ((handle & 4) != 0) q.z0 = Math.min(snap ? snapEdge(q, wz, false, true) : wz, q.z1 - GenSettings.MIN_Q + 1);
                if ((handle & 8) != 0) q.z1 = Math.max(snap ? snapEdge(q, wz - 1, false, false) : wz - 1, q.z0 + GenSettings.MIN_Q - 1);
                if (q.w() > GenSettings.MAX_Q) { if ((handle & 1) != 0) q.x0 = q.x1 - GenSettings.MAX_Q + 1; else q.x1 = q.x0 + GenSettings.MAX_Q - 1; }
                if (q.d() > GenSettings.MAX_Q) { if ((handle & 4) != 0) q.z0 = q.z1 - GenSettings.MAX_Q + 1; else q.z1 = q.z0 + GenSettings.MAX_Q - 1; }
                changed();
                return true;
            }
            default -> { return super.mouseDragged(mx, my, button, dx, dy); }
        }
    }

    /** В стек отмены кладётся состояние до начала перетаскивания. */
    private void pushUndoFrom(Quarter before) {
        List<Quarter> snap = new ArrayList<>();
        for (int i = 0; i < qs().size(); i++) snap.add(i == ClientState.selected ? before.copyQ() : qs().get(i).copyQ());
        UNDO.push(snap);
        while (UNDO.size() > 40) UNDO.removeLast();
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        Drag d = drag;
        drag = Drag.NONE;
        if (d == Drag.DRAW) finishDraw();
        if (d == Drag.MOVE || d == Drag.RESIZE) { guides.clear(); ClientState.recompute(); rebuild(); }
        return super.mouseReleased(mx, my, button);
    }

    private void finishDraw() {
        guides.clear();
        if (qs().size() >= GenSettings.MAX_QUARTERS) { toast("Не больше " + GenSettings.MAX_QUARTERS + " кварталов"); return; }
        Quarter q = new Quarter();
        q.kind = lastKind;
        q.name = nextName(lastKind.label());
        int ax = Math.min(drawX0, drawX1), bx = Math.max(drawX0, drawX1), az = Math.min(drawZ0, drawZ1), bz = Math.max(drawZ0, drawZ1);
        if (bx - ax < 3 && bz - az < 3) { ax = drawX0 - 24; bx = drawX0 + 23; az = drawZ0 - 24; bz = drawZ0 + 23; }
        q.x0 = ax; q.x1 = Math.max(bx, ax + GenSettings.MIN_Q - 1); q.z0 = az; q.z1 = Math.max(bz, az + GenSettings.MIN_Q - 1);
        if (q.w() > GenSettings.MAX_Q) q.x1 = q.x0 + GenSettings.MAX_Q - 1;
        if (q.d() > GenSettings.MAX_Q) q.z1 = q.z0 + GenSettings.MAX_Q - 1;
        int seed = 1;
        for (Quarter o : qs()) seed = Math.max(seed, o.seed + 1);
        q.seed = seed;
        pushUndo();
        qs().add(q);
        ClientState.selected = qs().size() - 1;
        if (!hasAltDown()) { q.moveBy(snapMove(q, true), 0); q.moveBy(0, snapMove(q, false)); guides.clear(); }
        tool = Tool.SELECT;
        s().arrangement = Arrangement.CITY;
        String c = conflict(ClientState.selected);
        toast(c == null ? "Квартал «" + q.name + "» добавлен — настройте его справа" : "⚠ Квартал " + c);
        ClientState.recompute();
        sideScroll = 0;
        rebuild();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (inMap(mx, my)) {
            double wx = wx(mx), wz = wz(my);
            zoom = Mth.clamp(zoom * (delta > 0 ? 1.25f : 0.8f), 0.25f, 12f);
            viewX = wx - (mx - mapX - mapW / 2.0) / zoom;
            viewZ = wz - (my - mapY - mapH / 2.0) / zoom;
            return true;
        }
        if (mx >= sideX && mx < sideX + sideW && my >= sideY && my < sideY + sideH) {
            for (GuiEventListener l : children())
                if (l instanceof Ui.W w && w.visible && w.isMouseOver(mx, my) && w.mouseScrolled(mx, my, delta)) return true;
            int max = Math.max(0, sideBottom - sideY - sideH);
            int ns = Mth.clamp(sideScroll - (int) Math.signum(delta) * 23, 0, max);
            if (ns != sideScroll) { sideScroll = ns; rebuild(); }
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (nameBox != null && nameBox.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_ESCAPE) { nameBox.setFocused(false); setFocused(null); rebuild(); return true; }
            return nameBox.keyPressed(key, scan, mods);
        }
        boolean ctrl = hasControlDown(), shift = hasShiftDown();
        Quarter q = sel();
        int step = shift ? 8 : 1;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> { onClose(); return true; }
            case GLFW.GLFW_KEY_Z -> { if (ctrl) { undo(); return true; } }
            case GLFW.GLFW_KEY_D -> { if (ctrl) { duplicate(); return true; } }
            case GLFW.GLFW_KEY_DELETE -> { if (q != null) { deleteQuarter(ClientState.selected); return true; } }
            case GLFW.GLFW_KEY_V -> { tool = Tool.SELECT; rebuild(); return true; }
            case GLFW.GLFW_KEY_Q -> { tool = Tool.DRAW; rebuild(); return true; }
            case GLFW.GLFW_KEY_E -> { worldEdit(); return true; }
            case GLFW.GLFW_KEY_HOME -> { resetView(); return true; }
            case GLFW.GLFW_KEY_TAB -> {
                if (!qs().isEmpty()) { int n = Math.floorMod(ClientState.selected + (shift ? -1 : 1), qs().size()); select(n); centerOn(qs().get(n)); }
                return true;
            }
            case GLFW.GLFW_KEY_R -> {
                if (q != null) { pushUndo(); q.facing = QFacing.values()[(q.facing.ordinal() + 1) % QFacing.values().length]; toast("Фасады: " + q.facing.label()); changed(); rebuild(); }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_DOWN -> {
                if (q == null) {
                    double k = 24 / zoom * (shift ? 3 : 1);
                    if (key == GLFW.GLFW_KEY_LEFT) viewX -= k; else if (key == GLFW.GLFW_KEY_RIGHT) viewX += k;
                    else if (key == GLFW.GLFW_KEY_UP) viewZ -= k; else viewZ += k;
                    return true;
                }
                pushUndo();
                if (key == GLFW.GLFW_KEY_LEFT) q.moveBy(-step, 0); else if (key == GLFW.GLFW_KEY_RIGHT) q.moveBy(step, 0);
                else if (key == GLFW.GLFW_KEY_UP) q.moveBy(0, -step); else q.moveBy(0, step);
                changed();
                return true;
            }
            default -> {}
        }
        if (ClientSetup.OPEN_EDITOR.matches(key, scan)) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (nameBox != null && nameBox.isFocused()) return nameBox.charTyped(c, mods);
        return super.charTyped(c, mods);
    }

    @Override
    public void tick() {
        SatMap.tick();
        if (nameBox != null) nameBox.tick();
    }

    @Override
    public void onClose() {
        ClientState.save();
        ClientPrefs.save();
        minecraft.setScreen(parent);
    }

    @Override
    public void removed() {
        if (open == this) open = null;
        ClientState.save();
    }

    // =====================================================================
    // Отрисовка
    // =====================================================================
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        int acc = Ui.accent();
        g.fill(0, 0, width, height, 0xC00A0F14);
        // шапка
        Ui.round(g, 6, 5, 20, 20, 4, acc);
        Ui.center(g, "C", 16, 11, 0xFF0A1016);
        if (wideTitle) {
            Ui.text(g, "Редактор города", 32, 7, Ui.TEXT);
            Ui.small(g, qs().size() + " кв. • " + (ClientState.layout == null ? 0 : ClientState.layout.plans.size()) + " зданий", 32, 17, Ui.SUB);
        }
        // карта
        Ui.card(g, mapX - 1, mapY - 1, mapW + 2, mapH + 2, 4, 0xFF0B1015, Ui.BORDER);
        g.enableScissor(mapX, mapY, mapX + mapW, mapY + mapH);
        renderMap(g, mx, my);
        renderStatus(g, mx, my);
        g.disableScissor();
        // боковая панель
        Ui.card(g, sideX - 1, mapY - 1, sideW + 2, mapH + 2, 4, Ui.panel() | 0xFF000000, Ui.BORDER);
        Quarter q = sel();
        if (q != null) {
            Ui.round(g, sideX + 4, mapY + 6, 4, 12, 2, q.kind.color);
            Ui.text(g, "Квартал", sideX + 12, mapY + 8, Ui.SUB);
            Ui.small(g, "№" + (ClientState.selected + 1), sideX + sideW - 22, mapY + 9, Ui.DIM);
        } else Ui.text(g, "Город", sideX + 6, mapY + 8, Ui.SUB);
        g.fill(sideX + 4, sideY - 4, sideX + sideW - 4, sideY - 3, Ui.BORDER);

        super.render(g, mx, my, pt);

        g.enableScissor(sideX, sideY - 2, sideX + sideW, sideY + sideH + 2);
        if (nameBox != null && nameBox.visible) {
            Ui.card(g, nameBox.getX() - 3, nameBox.getY() - 5, nameBox.getWidth() + 6, 18, 3,
                    nameBox.isFocused() ? Ui.WIDGET_HOVER : Ui.WIDGET, nameBox.isFocused() ? acc : Ui.BORDER);
            nameBox.render(g, mx, my, pt);
            if (!nameBox.isFocused()) Ui.small(g, "✎", nameBox.getX() + nameBox.getWidth() - 6, nameBox.getY() + 1, Ui.DIM);
        }
        for (Ui.W w : side) if (w.visible) w.render(g, mx, my, pt);
        g.disableScissor();
        int max = sideBottom - sideY - sideH;
        if (max > 0) {
            int bh = Math.max(16, sideH * sideH / (sideH + max));
            int by = sideY + (int) ((sideH - bh) * (sideScroll / (double) max));
            g.fill(sideX + sideW - 3, by, sideX + sideW - 1, by + bh, Ui.alpha(acc, 0.8f));
        }
        renderTooltip(g, mx, my);
        if (devCursor != null) {
            int cx = (int) devCursor[0], cy = (int) devCursor[1];
            g.pose().pushPose();
            g.pose().translate(0, 0, 500);
            for (int i = 0; i < 11; i++) g.fill(cx - 1, cy + i - 1, cx + Math.max(1, i * 6 / 10) + 2, cy + i + 1, 0xFF000000);
            for (int i = 0; i < 10; i++) g.fill(cx, cy + i, cx + Math.max(1, i * 6 / 10) + 1, cy + i + 1, 0xFFFFFFFF);
            g.pose().popPose();
        }
    }

    private void renderStatus(GuiGraphics g, int mx, int my) {
        String left;
        if (System.currentTimeMillis() - toastAt < 4000 && !toast.isEmpty()) left = "§e" + toast;
        else if (inMap(mx, my)) {
            int wx = (int) Math.floor(wx(mx)), wz = (int) Math.floor(wz(my));
            int h = SatMap.height(wx, wz);
            left = "X " + wx + "   Z " + wz + (h == Integer.MIN_VALUE ? "" : "   высота " + h)
                    + (drag == Drag.DRAW ? "   §f" + (Math.abs(drawX1 - drawX0) + 1) + " × " + (Math.abs(drawZ1 - drawZ0) + 1) + " м" : "");
        } else {
            String err = overlapError();
            left = err != null ? "§c⚠ " + err : tool == Tool.DRAW ? "Растяните прямоугольник квартала на карте (клик — квартал 48×48)"
                    : "ЛКМ — выбрать и двигать • маркеры — размер • ПКМ — сдвиг карты • колесо — масштаб";
        }
        String t = Ui.fit(left, mapW - 16);
        int tw = Ui.width(t);
        Ui.round(g, mapX + 4, mapY + mapH - 17, tw + 10, 13, 3, 0xC0101418);
        Ui.text(g, t, mapX + 9, mapY + mapH - 14, Ui.SUB);
    }

    private void renderTooltip(GuiGraphics g, int mx, int my) {
        String h = "";
        for (GuiEventListener l : children())
            if (l instanceof Ui.W w && w.visible && w.isMouseOver(mx, my) && !w.help().isEmpty()) { h = w.help(); break; }
        if (h.isEmpty() || !ClientPrefs.get().hints) return;
        List<FormattedCharSequence> lines = Ui.wrap(h, 220);
        int w = 0;
        for (FormattedCharSequence l : lines) w = Math.max(w, font.width(l));
        int x = Math.min(mx + 10, width - w - 12), y = my + 14;
        int hh = lines.size() * 10 + 6;
        if (y + hh > height - 2) y = my - hh - 4;
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        Ui.card(g, x - 4, y - 3, w + 8, hh, 3, 0xF0121A22, Ui.alpha(Ui.accent(), 0.6f));
        for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x, y + i * 10, Ui.TEXT, false);
        g.pose().popPose();
    }

    private void renderMap(GuiGraphics g, int mx, int my) {
        // снимок
        if (SatMap.ready() && SatMap.sameWorld()) {
            int x0 = (int) Math.floor(sx(SatMap.cx - SatMap.radius)), y0 = (int) Math.floor(sy(SatMap.cz - SatMap.radius));
            int sz = Math.round(SatMap.size * zoom);
            g.blit(SatMap.TEX, x0, y0, sz, sz, 0f, 0f, SatMap.size, SatMap.size, SatMap.size, SatMap.size);
        }
        // сетка чанков
        if (ClientPrefs.get().mapGrid && zoom >= 1.2f) {
            int gx0 = Math.floorDiv((int) wx(mapX), 16) * 16, gz0 = Math.floorDiv((int) wz(mapY), 16) * 16;
            for (int x = gx0; sx(x) < mapX + mapW; x += 16) g.fill((int) sx(x), mapY, (int) sx(x) + 1, mapY + mapH, 0x14FFFFFF);
            for (int z = gz0; sy(z) < mapY + mapH; z += 16) g.fill(mapX, (int) sy(z), mapX + mapW, (int) sy(z) + 1, 0x14FFFFFF);
        }
        // улицы, затем кварталы
        for (Quarter q : qs()) {
            int r = ring(q);
            if (r <= 0) continue;
            if (q.street == StreetMode.ROAD) {
                fillW(g, q.x0 - r, q.z0 - r, q.x1 + r, q.z1 + r, 0x90363C44);
            }
            int w = Layout.WALK;
            fillW(g, q.x0 - w, q.z0 - w, q.x1 + w, q.z1 + w, 0xA0858B92);
        }
        for (Quarter q : qs()) fillW(g, q.x0, q.z0, q.x1, q.z1, 0x802B3A26);
        Layout L = ClientState.layout;
        boolean plan = ClientPrefs.get().mapPlan && L != null;
        for (int i = 0; i < qs().size(); i++) {
            Quarter q = qs().get(i);
            boolean selected = i == ClientState.selected;
            fillW(g, q.x0, q.z0, q.x1, q.z1, Ui.alpha(q.kind.color, plan ? 0.22f : 0.38f));
            if (plan && !(selected && drag != Drag.NONE)) {
                for (LotPlan lp : L.lots)
                    if (lp.quarter == i) fillW(g, lp.minX, lp.minZ, lp.maxX, lp.maxZ, Ui.alpha(lp.kind.color, 0.75f));
                for (Plan p : L.plans) {
                    if (p.quarter != i) continue;
                    int c = p.underConstruction ? 0xFFE8B83A : Ui.mix(q.kind.color, 0xFFFFFFFF, 0.45f);
                    fillW(g, p.minX, p.minZ, p.maxX, p.maxZ, Ui.alpha(c, 0.9f));
                    if (zoom >= 1.5f) frameW(g, p.minX, p.minZ, p.maxX, p.maxZ, 0x60000000, 1);
                }
            }
        }
        // рамки и подписи
        for (int i = 0; i < qs().size(); i++) {
            Quarter q = qs().get(i);
            boolean selected = i == ClientState.selected;
            boolean bad = conflict(i) != null;
            int c = bad ? Ui.BAD : selected ? 0xFFFFFFFF : q.kind.color;
            frameW(g, q.x0, q.z0, q.x1, q.z1, c, selected ? 2 : 1);
            int qw = (int) ((q.w()) * zoom), qh = (int) (q.d() * zoom);
            int cx = (int) sx((q.x0 + q.x1 + 1) / 2.0), cy = (int) sy((q.z0 + q.z1 + 1) / 2.0);
            if (qw > 46 && qh > 24) {
                String n = Ui.fit(q.name, qw - 8);
                int tw = Ui.width(n);
                Ui.round(g, cx - tw / 2 - 4, cy - 10, tw + 8, 21, 3, 0xB0101418);
                Ui.text(g, n, cx - tw / 2, cy - 8, selected ? 0xFFFFFFFF : Ui.TEXT);
                String sz = q.w() + "×" + q.d();
                Ui.small(g, sz, cx - Ui.smallWidth(sz) / 2, cy + 2, bad ? Ui.BAD : Ui.SUB);
            } else if (qw > 20 && qh > 10) {
                String n = Ui.fit(q.name, (int) ((qw - 4) / 0.75f));
                int tw = Ui.smallWidth(n);
                Ui.round(g, cx - tw / 2 - 2, cy - 5, tw + 4, 10, 2, 0xB0101418);
                Ui.small(g, n, cx - tw / 2, cy - 3, selected ? 0xFFFFFFFF : Ui.TEXT);
            }
            if (selected) {
                int a = (int) Math.floor(sx(q.x0)), b = (int) Math.floor(sy(q.z0)), cc = (int) Math.ceil(sx(q.x1 + 1)), d = (int) Math.ceil(sy(q.z1 + 1));
                int[][] hs = {{a, b}, {cc, b}, {a, d}, {cc, d}, {(a + cc) / 2, b}, {(a + cc) / 2, d}, {a, (b + d) / 2}, {cc, (b + d) / 2}};
                for (int[] h : hs) { g.fill(h[0] - 3, h[1] - 3, h[0] + 3, h[1] + 3, Ui.accent()); g.fill(h[0] - 2, h[1] - 2, h[0] + 2, h[1] + 2, 0xFFFFFFFF); }
                // стрелки фасадов
                facingMarks(g, q, a, b, cc, d);
            }
        }
        // направляющие привязки
        for (int[] gd : guides) {
            if (gd[0] == 0) { int x = (int) sx(gd[1] + 0.5); for (int y = mapY; y < mapY + mapH; y += 6) g.fill(x, y, x + 1, y + 3, Ui.accent()); }
            else { int y = (int) sy(gd[1] + 0.5); for (int x = mapX; x < mapX + mapW; x += 6) g.fill(x, y, x + 3, y + 1, Ui.accent()); }
        }
        // рисуемый квартал
        if (drag == Drag.DRAW) {
            int ax = Math.min(drawX0, drawX1), bx = Math.max(drawX0, drawX1), az = Math.min(drawZ0, drawZ1), bz = Math.max(drawZ0, drawZ1);
            fillW(g, ax - Layout.WALK - Layout.ROAD_W, az - Layout.WALK - Layout.ROAD_W, bx + Layout.WALK + Layout.ROAD_W, bz + Layout.WALK + Layout.ROAD_W, 0x40363C44);
            fillW(g, ax, az, bx, bz, Ui.alpha(lastKind.color, 0.4f));
            frameW(g, ax, az, bx, bz, 0xFFFFFFFF, 1);
        }
        // игрок
        Player pl = minecraft.player;
        if (pl != null) {
            int px = (int) sx(pl.getX()), py = (int) sy(pl.getZ());
            double yaw = Math.toRadians(pl.getYRot());
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            for (int k = 0; k < 9; k++) g.fill((int) (px + fx * k) - 1, (int) (py + fz * k) - 1, (int) (px + fx * k) + 1, (int) (py + fz * k) + 1, 0xFFFFFFFF);
            Ui.round(g, px - 4, py - 4, 8, 8, 4, 0xFFFF4D4D);
            Ui.round(g, px - 2, py - 2, 4, 4, 2, 0xFFFFFFFF);
        }
        // компас и масштаб
        Ui.round(g, mapX + 6, mapY + 6, 16, 16, 8, 0xC0101418);
        Ui.center(g, "С", mapX + 14, mapY + 10, 0xFFFF6B6B);
        int meters = zoom >= 4 ? 10 : zoom >= 1.5f ? 25 : zoom >= 0.7f ? 50 : 100;
        int len = Math.round(meters * zoom);
        int bx = mapX + mapW - len - 34, by = mapY + 26;
        g.fill(bx, by, bx + len, by + 2, 0xFFFFFFFF);
        g.fill(bx, by - 3, bx + 1, by + 2, 0xFFFFFFFF);
        g.fill(bx + len - 1, by - 3, bx + len, by + 2, 0xFFFFFFFF);
        Ui.small(g, meters + " м", bx + len + 4, by - 3, 0xFFFFFFFF);
        // состояние снимка
        if (SatMap.busy()) {
            String t = "Снимаю местность… " + Math.round(SatMap.progress() * 100) + "%";
            Ui.round(g, mapX + 26, mapY + 6, Ui.width(t) + 8, 14, 3, 0xC0101418);
            Ui.text(g, t, mapX + 30, mapY + 9, Ui.WARN);
        }
        if (qs().isEmpty() && drag != Drag.DRAW) {
            String t = tool == Tool.DRAW ? "Растяните прямоугольник — это будет первый квартал" : "Нажмите «+ Квартал» и нарисуйте первый квартал";
            int tw = Ui.width(t);
            t = Ui.fit(t, mapW - 30);
            tw = Ui.width(t);
            Ui.round(g, mapX + (mapW - tw) / 2 - 8, mapY + mapH / 2 - 9, tw + 16, 18, 4, 0xD0101418);
            Ui.text(g, t, mapX + (mapW - tw) / 2, mapY + mapH / 2 - 4, Ui.TEXT);
        }
    }

    /** Белые засечки на сторонах, куда смотрят фасады (входы со стороны улиц). */
    private void facingMarks(GuiGraphics g, Quarter q, int a, int b, int c, int d) {
        boolean n, s, w, e;
        QFacing f = q.facing;
        if (f == QFacing.AUTO) {
            boolean per = q.kind == QuarterKind.HISTORIC;
            boolean wide = q.w() >= q.d();
            n = per || wide; s = per || wide; w = per || !wide; e = per || !wide;
        } else {
            n = f == QFacing.NORTH || f == QFacing.NORTH_SOUTH || f == QFacing.PERIMETER;
            s = f == QFacing.SOUTH || f == QFacing.NORTH_SOUTH || f == QFacing.PERIMETER;
            w = f == QFacing.WEST || f == QFacing.WEST_EAST || f == QFacing.PERIMETER;
            e = f == QFacing.EAST || f == QFacing.WEST_EAST || f == QFacing.PERIMETER;
        }
        if (q.kind.open()) return;
        int col = 0xE0FFFFFF;
        if (n) for (int x = a + 6; x < c - 6; x += 8) g.fill(x, b + 3, x + 4, b + 5, col);
        if (s) for (int x = a + 6; x < c - 6; x += 8) g.fill(x, d - 5, x + 4, d - 3, col);
        if (w) for (int y = b + 6; y < d - 6; y += 8) g.fill(a + 3, y, a + 5, y + 4, col);
        if (e) for (int y = b + 6; y < d - 6; y += 8) g.fill(c - 5, y, c - 3, y + 4, col);
    }
}
