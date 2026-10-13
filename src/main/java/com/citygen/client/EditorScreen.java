package com.citygen.client;

import com.citygen.gen.*;
import com.citygen.gen.GenSettings.*;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Редактор CityGen: окно с вкладками, живое превью в мире, таблица кварталов и вид сверху. */
public class EditorScreen extends Screen {
    public static final String VERSION = "0.2.2";
    public static final String STEAM = "https://steamcommunity.com/tradeoffer/new/?partner=1869951288&token=joiRehOd";
    private static final String[] TABS = {"Здания", "Архитектура", "Интерьер", "Участок", "Кварталы", "Вид сверху", "Настройки"};
    private static final ItemStack[] ICONS = {new ItemStack(Items.BRICKS), new ItemStack(Items.QUARTZ_PILLAR), new ItemStack(Items.RED_BED),
            new ItemStack(Items.OAK_SAPLING), new ItemStack(Items.MAP), new ItemStack(Items.SPYGLASS), new ItemStack(Items.COMPARATOR)};
    private static final int TAB_MAP = 5;

    private static int scroll;
    private static CellType brush = CellType.MIDRISE;
    private static int mapZoom = 1;
    private static double mapCU = -1, mapCV = -1;

    private int px, py, pw, ph, side, cx0, cy0, cw, ch;
    private final List<Ui.W> content = new ArrayList<>();
    private int contentBottom;
    private boolean dragging, mapDragging;
    private double dragOX, dragOY;
    private int mapX, mapY, mapW, mapH;
    private EditBox seedBox;

    public EditorScreen() {
        super(Component.literal("CityGen"));
        ClientState.onEditorOpen();
    }

    private static GenSettings s() { return ClientState.settings; }
    private static ClientPrefs prefs() { return ClientPrefs.get(); }

    @Override public boolean isPauseScreen() { return false; }

    // =====================================================================
    // Построение
    // =====================================================================
    @Override
    protected void init() {
        content.clear();
        seedBox = null;
        pw = width < 380 ? width - 4 : Mth.clamp(width - 24, 380, 620);
        ph = height < 232 ? height - 4 : Mth.clamp(height - 16, 232, 360);
        px = Mth.clamp((width - pw) / 2 + prefs().panelDX, 0, Math.max(0, width - pw));
        py = Mth.clamp((height - ph) / 2 + prefs().panelDY, 0, Math.max(0, height - ph));
        side = pw >= 460 ? 100 : 26;
        cx0 = px + side + 8;
        cy0 = py + 28;
        cw = pw - side - 16;
        ch = ph - 28 - 30 - 16;

        int tab = Mth.clamp(prefs().tab, 0, TABS.length - 1);
        // вкладки
        for (int i = 0; i < TABS.length; i++) {
            final int ti = i;
            Ui.Tab t = new Ui.Tab(px, py + 26 + i * 22, side, 20, TABS[i], ICONS[i], tab == i, () -> { prefs().tab = ti; scroll = 0; rebuild(); });
            t.help = TABS[i];
            addRenderableWidget(t);
        }
        // заголовок
        addRenderableWidget(new Ui.Button(px + pw - 22, py + 4, 18, 16, "✕", Ui.Button.Kind.GHOST, this::onClose).help("Закрыть (Esc или G)"));
        addRenderableWidget(new Ui.Button(px + pw - 44, py + 4, 20, 16, "♥", Ui.Button.Kind.GHOST, this::openSupport)
                .help("Поддержать автора — обмен в Steam (откроется браузер после подтверждения)."));
        // подвал
        int fy = py + ph - 26;
        int bw = Math.min(96, (pw - side - 24) / 4 - 4);
        int bx = px + pw - 8 - bw;
        addRenderableWidget(new Ui.Button(bx, fy, bw, 20, "Построить", Ui.Button.Kind.PRIMARY, () -> { ClientState.build(); onClose(); })
                .help("Построить по рамкам превью. Прогресс — над хотбаром, отмена — кнопка «Отменить» или /citygen undo."));
        bx -= bw + 4;
        addRenderableWidget(new Ui.Button(bx, fy, bw, 20, ClientState.previewActive ? "Превью: вкл" : "Превью: выкл", Ui.Button.Kind.NORMAL,
                () -> { ClientState.previewActive = !ClientState.previewActive; if (ClientState.previewActive) ClientState.recompute(); rebuild(); })
                .help("Рамки будущих зданий в мире. Остаются после закрытия окна — можно обойти участок. Белая рамка — сторона входа."));
        bx -= bw + 4;
        addRenderableWidget(new Ui.Button(bx, fy, bw, 20, "Сюда", Ui.Button.Kind.NORMAL, () -> { ClientState.anchorHere(); rebuild(); })
                .help("Перенести участок к вам: точка привязки и направление взгляда берутся заново (уровень — земля под ногами)."));
        bx -= bw + 4;
        addRenderableWidget(new Ui.Button(bx, fy, bw, 20, "Отменить", Ui.Button.Kind.DANGER, ClientState::undo)
                .help("Вернуть местность как была до последней постройки (хранятся 5 последних). /citygen cancel — остановить стройку."));

        // содержимое
        int y = cy0 - scroll;
        switch (tab) {
            case 0 -> y = tabBuildings(y);
            case 1 -> y = tabArchitecture(y);
            case 2 -> y = tabInterior(y);
            case 3 -> y = tabSite(y);
            case 4 -> y = tabPlanner(y);
            case 5 -> y = tabMap(y);
            default -> y = tabSettings(y);
        }
        contentBottom = y + scroll;
        int maxScroll = Math.max(0, contentBottom - cy0 - ch);
        if (scroll > maxScroll) { scroll = maxScroll; rebuild(); return; }
        for (Ui.W w : content) {
            w.visible = w.getY() >= cy0 - 1 && w.getY() + w.getHeight() <= cy0 + ch + 1;
            addWidget(w);
        }
        if (seedBox != null) { seedBox.visible = seedBox.getY() >= cy0 && seedBox.getY() + 18 <= cy0 + ch; addWidget(seedBox); }
        MapPreview.wanted = tab == TAB_MAP;
        if (MapPreview.wanted && !MapPreview.ready()) MapPreview.markDirty();
    }

    private void rebuild() {
        clearWidgets();
        init();
    }

    private void changed() {
        ClientState.recompute();
    }

    // ---------- помощники раскладки ----------
    private int colW() { return (cw - 6) / 2; }
    private int lx() { return cx0; }
    private int rx() { return cx0 + colW() + 6; }

    private <T extends Ui.W> T add(T w) { content.add(w); return w; }

    private int heading(int y, String t) { add(new Ui.Label(cx0, y + 2, cw, t, Ui.accent())); return y + 16; }

    private int row(int y, Ui.W l, Ui.W r) { add(l); if (r != null) add(r); return y + 23; }

    private int text(int y, String t, int color) {
        Ui.Text tx = add(new Ui.Text(cx0 + 2, y + 2, cw - 4, t, color));
        return y + tx.getHeight() + 6;
    }

    private <E extends Enum<E> & Labeled> Ui.Cycle<E> cyc(int x, int y, String name, E[] vals, E init, java.util.function.Consumer<E> on) {
        return Ui.Cycle.of(x, y, colW(), name, vals, init, v -> { on.accept(v); changed(); });
    }

    private Ui.Slider sld(int x, int y, String name, int min, int max, int v, java.util.function.IntFunction<String> fmt,
                          java.util.function.IntConsumer on, String help) {
        return new Ui.Slider(x, y, colW(), 20, name, min, max, v, fmt, i -> { on.accept(i); changed(); }).help(help);
    }

    private Ui.Toggle tog(int x, int y, String name, boolean v, java.util.function.Consumer<Boolean> on, String help) {
        return new Ui.Toggle(x, y, colW(), 20, name, v, b -> { on.accept(b); changed(); }).help(help);
    }

    // =====================================================================
    // Вкладки
    // =====================================================================
    private int tabBuildings(int y) {
        GenSettings s = s();
        boolean mixed = s.type == BuildingType.MIXED;
        boolean planner = s.arrangement == Arrangement.PLANNER;
        y = heading(y, "Основное");
        Ui.Slider cnt = sld(lx(), y, "Количество", 1, 16, s.count, String::valueOf, v -> s.count = v,
                "Сколько зданий в режимах «Ряд» и «Улица». В режиме «Кварталы» число задаёт таблица.");
        cnt.active = !planner;
        y = row(y, cnt, cyc(rx(), y, "Тип", BuildingType.values(), s.type, v -> { s.type = v; s.applyTypeDefaults(); rebuild(); }));
        y = row(y, cyc(lx(), y, "Стиль", StyleMode.values(), s.style, v -> s.style = v),
                cyc(rx(), y, "Различия", Variation.values(), s.variation, v -> s.variation = v));
        y = row(y, cyc(lx(), y, "Высоты", HeightProfile.values(), s.heightProfile, v -> s.heightProfile = v),
                new Ui.Button(rx(), y, colW(), 20, "Сбросить под тип", Ui.Button.Kind.NORMAL,
                        () -> { s.applyTypeDefaults(); changed(); rebuild(); }).help("Вернуть типичную этажность и размеры для выбранного типа."));
        y = heading(y, "Этажность и размеры");
        String sizeHelp = mixed ? "В режиме «Микс» этажность и размеры берутся из типа каждого здания."
                : "1 блок ≈ 1 метр. Этаж = 4–5 блоков, первый этаж выше. Высота мира ограничивает этажность.";
        Ui.Slider fmin = sld(lx(), y, "Этажей от", 1, 80, s.floorsMin, String::valueOf, v -> s.floorsMin = v, sizeHelp);
        Ui.Slider fmax = sld(rx(), y, "Этажей до", 1, 80, s.floorsMax, String::valueOf, v -> s.floorsMax = v, sizeHelp);
        y = row(y, fmin, fmax);
        Ui.Slider sw = sld(lx(), y, planner ? "Ширина участка" : "Ширина", 5, 72, s.width, v -> v + " м", v -> s.width = v, sizeHelp);
        Ui.Slider sd = sld(rx(), y, planner ? "Глубина участка" : "Глубина", 5, 72, s.depth, v -> v + " м", v -> s.depth = v, sizeHelp);
        y = row(y, sw, sd);
        if (mixed) { fmin.active = false; fmax.active = false; if (!planner) { sw.active = false; sd.active = false; } }
        y = text(y, "§7" + s.type.help, Ui.SUB);
        y = text(y, "§8Стиль: " + s.style.help(), Ui.SUB);
        return y;
    }

    private int tabArchitecture(int y) {
        GenSettings s = s();
        y = heading(y, "Облик");
        List<Integer> pals = new ArrayList<>();
        for (int i = -1; i < Palettes.ALL.size(); i++) pals.add(i);
        Ui.Cycle<Integer> pal = new Ui.Cycle<>(lx(), y, colW(), 20, "Палитра", pals, s.palette,
                i -> i < 0 ? "Случайная" : Palettes.ALL.get(i).name,
                i -> i < 0 ? "Палитра подбирается под стиль и тип каждого здания."
                        : "Стиль палитры: " + Palettes.ALL.get(i).style.label() + ". Материалы стен, отделки, стекла и крыши.",
                v -> { s.palette = v; changed(); });
        y = row(y, pal, cyc(rx(), y, "Фасад", FacadeType.values(), s.facade, v -> s.facade = v));
        y = row(y, cyc(lx(), y, "Форма", Shape.values(), s.shape, v -> s.shape = v),
                cyc(rx(), y, "Крыша", RoofType.values(), s.roof, v -> s.roof = v));
        y = row(y, cyc(lx(), y, "1-й этаж", GroundFloor.values(), s.groundFloor, v -> s.groundFloor = v),
                cyc(rx(), y, "Балконы", Balconies.values(), s.balconies, v -> s.balconies = v));
        y = heading(y, "Детали");
        y = row(y, cyc(lx(), y, "Окна", WindowSize.values(), s.windows, v -> s.windows = v),
                sld(rx(), y, "Высота этажа", 2, 7, s.floorHeight == 0 ? 2 : s.floorHeight, v -> v == 2 ? "авто" : v + " бл.",
                        v -> s.floorHeight = v == 2 ? 0 : v, "Высота этажа в блоках вместе с перекрытием. «Авто» — по типу здания."));
        y = row(y, sld(lx(), y, "Фактура стен", 0, 60, s.texture, v -> v + "%", v -> s.texture = v,
                        "Доля «живых» блоков в стенах: сколы, мох, оттенки камня. 0% — идеально ровные стены."),
                cyc(rx(), y, "Пожарн. лестницы", Toggle3.values(), s.fireEscapes, v -> s.fireEscapes = v));
        y = row(y, tog(lx(), y, "Декор и орнамент", s.ornaments, v -> s.ornaments = v,
                "Эркеры, балюстрады, вазоны, акротерии, кашпо с цветами, фонари в портиках."), null);
        return text(y, "§7«Авто» подбирает всё по типу и стилю. Ручной выбор действует на все здания сразу.", Ui.SUB);
    }

    private int tabInterior(int y) {
        GenSettings s = s();
        y = heading(y, "Наполнение");
        y = row(y, cyc(lx(), y, "Интерьер", Interior.values(), s.interior, v -> s.interior = v),
                cyc(rx(), y, "Лестницы", StairsMode.values(), s.stairs, v -> s.stairs = v));
        y = row(y, sld(lx(), y, "Свет в окнах", 0, 100, s.litPercent, v -> v + "%", v -> s.litPercent = v,
                        "Доля освещённых комнат: ночью окна горят неравномерно, как в настоящем городе."),
                tog(rx(), y, "Лифты", s.elevators, v -> s.elevators = v, "Лифтовые шахты с дверями и кнопками в зданиях от 5 этажей."));
        y = row(y, tog(lx(), y, "Детали на крыше", s.rooftop, v -> s.rooftop = v,
                "Выход на крышу, вентиляция, панели, баки, антенны, шпили, сады."), null);
        y = heading(y, "Что внутри");
        return text(y, "§7Квартиры: гостиная с диваном и ТВ, спальни с кроватями и шкафами, кухня, санузел.\n"
                + "Офисы: открытые пространства с рядами столов, переговорные со стеклянными стенами.\n"
                + "Первые этажи: лобби с ресепшеном, кафе, пекарни, книжные, цветочные, супермаркеты.\n"
                + "Общественные здания: музеи с экспонатами или библиотеки с рядами стеллажей.\n"
                + "Высокие дома получают лестничную клетку с маршами и площадками, дома — лестницу вдоль стены.", Ui.SUB);
    }

    private int tabSite(int y) {
        GenSettings s = s();
        y = heading(y, "Расположение");
        List<Integer> rots = List.of(0, 1, 2, 3);
        y = row(y, cyc(lx(), y, "Схема", Arrangement.values(), s.arrangement, v -> { s.arrangement = v; rebuild(); }),
                new Ui.Cycle<>(rx(), y, colW(), 20, "Поворот", rots, s.rotation, v -> (v * 90) + "°",
                        v -> "Повернуть весь участок относительно направления взгляда.", v -> { s.rotation = v; changed(); }));
        y = row(y, sld(lx(), y, "Отступ вперёд", 0, 64, s.distance, v -> v + " м", v -> s.distance = v,
                        "Расстояние от вас до первой линии (с учётом тротуара/дороги)."),
                sld(rx(), y, "Сдвиг вбок", -64, 64, s.offset, v -> (v > 0 ? "+" : "") + v + " м", v -> s.offset = v,
                        "Сдвиг участка влево (−) или вправо (+)."));
        y = row(y, sld(lx(), y, "Между зданиями", 0, 24, s.spacing, v -> v + " м", v -> s.spacing = v,
                        "0–1 — сплошная застройка: боковые стены глухие."),
                cyc(rx(), y, "Основание", GroundMode.values(), s.ground, v -> s.ground = v));
        y = heading(y, "Улицы и зелень");
        y = row(y, cyc(lx(), y, "Улица", StreetMode.values(), s.street, v -> s.street = v),
                cyc(rx(), y, "Поперечные", StreetMode.values(), s.crossStreets, v -> s.crossStreets = v));
        y = row(y, cyc(lx(), y, "Озеленение", Greenery.values(), s.greenery, v -> s.greenery = v),
                tog(rx(), y, "Палисадники", s.yards, v -> s.yards = v, "Газон, дорожка, клумбы и ограда перед частными домами."));
        y = heading(y, "Подготовка");
        y = row(y, tog(lx(), y, "Расчистить участок", s.clearSite, v -> s.clearSite = v, "Убрать деревья, траву и холмы на участке."),
                tog(rx(), y, "Фундамент до земли", s.foundation, v -> s.foundation = v, "Заполнить пустоту под зданием на склонах и над водой."));
        Ui.Label seedL = add(new Ui.Label(lx(), y + 4, 40, "Сид", Ui.SUB));
        seedL.help = "";
        seedBox = new EditBox(font, lx() + 32, y + 1, colW() - 34, 18, Component.literal("Сид"));
        seedBox.setMaxLength(19);
        seedBox.setValue(Long.toString(s.seed));
        seedBox.setFilter(t -> t.isEmpty() || t.equals("-") || t.matches("-?\\d{1,18}"));
        seedBox.setResponder(t -> { try { s.seed = Long.parseLong(t); changed(); } catch (NumberFormatException ignored) {} });
        y = row(y, new Ui.Button(rx(), y, colW(), 20, "Случайный сид", Ui.Button.Kind.NORMAL, () -> {
            s.seed = new Random().nextLong() & 0xFFFFFFFFL; changed(); rebuild();
        }).help("Новый сид — новые варианты зданий на том же месте."), null);
        y = row(y, tog(lx(), y, "Новый сид после стройки", s.newSeedEachBuild, v -> s.newSeedEachBuild = v,
                "После постройки сид меняется, чтобы следующий квартал был другим."), null);
        return y;
    }

    private int tabPlanner(int y) {
        GenSettings s = s();
        if (s.arrangement != Arrangement.PLANNER) {
            y = text(y, "§eРежим кварталов выключен.§7 Сейчас схема «" + s.arrangement.label() + "». "
                    + "Включите его, чтобы строить по таблице: каждая клетка — участок со зданием, парком, площадью или парковкой.", Ui.TEXT);
            return row(y, new Ui.Button(lx(), y, colW(), 20, "Включить кварталы", Ui.Button.Kind.PRIMARY,
                    () -> { s.arrangement = Arrangement.PLANNER; changed(); rebuild(); }), null);
        }
        // кисти
        CellType[] types = CellType.values();
        int perRow = cw >= 420 ? 7 : 5, chipW = (cw - (perRow - 1) * 3) / perRow;
        for (int i = 0; i < types.length; i++) {
            CellType t = types[i];
            add(new Ui.Chip(cx0 + (i % perRow) * (chipW + 3), y + (i / perRow) * 18, chipW, 16, t, () -> brush == t, () -> { brush = t; }));
        }
        y += ((types.length + perRow - 1) / perRow) * 18 + 4;
        int bw = 70;
        int mid = cx0 + cw / 2;
        add(new Ui.Button(mid - bw - 2, y, bw, 14, "+ сверху", Ui.Button.Kind.NORMAL, () -> grid(1, true, 0, false)).help("Добавить дальний ряд участков."));
        add(new Ui.Button(mid + 2, y, bw, 14, "− сверху", Ui.Button.Kind.NORMAL, () -> grid(-1, true, 0, false)).help("Убрать дальний ряд."));
        y += 17;
        int gh = Math.max(s.rows * 16 + 14, Math.min(s.rows * 28 + 14, ch - 120));
        add(new Ui.Button(cx0, y + gh / 2 - 15, 18, 14, "+", Ui.Button.Kind.NORMAL, () -> grid(0, false, 1, true)).help("Добавить столбец слева."));
        add(new Ui.Button(cx0, y + gh / 2 + 1, 18, 14, "−", Ui.Button.Kind.NORMAL, () -> grid(0, false, -1, true)).help("Убрать левый столбец."));
        add(new Ui.Button(cx0 + cw - 18, y + gh / 2 - 15, 18, 14, "+", Ui.Button.Kind.NORMAL, () -> grid(0, false, 1, false)).help("Добавить столбец справа."));
        add(new Ui.Button(cx0 + cw - 18, y + gh / 2 + 1, 18, 14, "−", Ui.Button.Kind.NORMAL, () -> grid(0, false, -1, false)).help("Убрать правый столбец."));
        add(new Ui.Planner(cx0 + 22, y, cw - 44, gh, s, () -> brush, this::changed));
        y += gh + 3;
        add(new Ui.Button(mid - bw - 2, y, bw, 14, "+ снизу", Ui.Button.Kind.NORMAL, () -> grid(1, false, 0, false)).help("Добавить ближний ряд."));
        add(new Ui.Button(mid + 2, y, bw, 14, "− снизу", Ui.Button.Kind.NORMAL, () -> grid(-1, false, 0, false)).help("Убрать ближний ряд."));
        y += 20;
        y = row(y, sld(lx(), y, "Ширина участка", 8, 72, s.width, v -> v + " м", v -> s.width = v, "Ширина одной клетки таблицы."),
                sld(rx(), y, "Глубина участка", 8, 72, s.depth, v -> v + " м", v -> s.depth = v, "Глубина одной клетки таблицы."));
        y = row(y, cyc(lx(), y, "Улицы между рядами", StreetMode.values(), s.street, v -> s.street = v),
                cyc(rx(), y, "Между столбцами", StreetMode.values(), s.crossStreets, v -> s.crossStreets = v));
        int parks = 0, empty = 0;
        for (int r = 0; r < s.rows; r++) for (int c = 0; c < s.cols; c++) {
            CellType t = s.cell(r, c);
            if (t == CellType.PARK || t == CellType.PLAZA || t == CellType.PARKING) parks++;
            if (t == CellType.EMPTY) empty++;
        }
        return text(y, "§7Сетка " + s.rows + "×" + s.cols + " • зданий ≈ " + s.plannedBuildings() + " • парков/площадей: " + parks
                + (empty > 0 ? " • пустых: " + empty : "") + ". Клетки «Авто» используют тип с вкладки «Здания».", Ui.SUB);
    }

    private void grid(int dr, boolean top, int dc, boolean left) {
        s().resizeGrid(dr, top, dc, left);
        changed();
        rebuild();
    }

    private int tabMap(int y) {
        int bw = 62;
        add(new Ui.Button(cx0, y, bw, 16, prefs().mapIso ? "Сверху" : "§lСверху", prefs().mapIso ? Ui.Button.Kind.NORMAL : Ui.Button.Kind.PRIMARY,
                () -> { prefs().mapIso = false; MapPreview.markDirty(); rebuild(); }).help("Вид строго сверху с тенями по высоте."));
        add(new Ui.Button(cx0 + bw + 3, y, bw, 16, "3D", prefs().mapIso ? Ui.Button.Kind.PRIMARY : Ui.Button.Kind.NORMAL,
                () -> { prefs().mapIso = true; MapPreview.markDirty(); rebuild(); }).help("Изометрия: видны фасады, обращённые к вам."));
        add(new Ui.Button(cx0 + 2 * (bw + 3), y, bw, 16, "Обновить", Ui.Button.Kind.NORMAL, MapPreview::markDirty)
                .help("Перерисовать карту по текущим настройкам."));
        add(new Ui.Button(cx0 + cw - 38, y, 18, 16, "−", Ui.Button.Kind.NORMAL, () -> mapZoom = Math.max(1, mapZoom - 1)).help("Отдалить (колесо мыши)."));
        add(new Ui.Button(cx0 + cw - 18, y, 18, 16, "+", Ui.Button.Kind.NORMAL, () -> mapZoom = Math.min(8, mapZoom + 1)).help("Приблизить (колесо мыши)."));
        mapX = cx0; mapY = y + 20; mapW = cw; mapH = ch - 22;
        return mapY + mapH;
    }

    private int tabSettings(int y) {
        ClientPrefs p = prefs();
        y = heading(y, "Интерфейс");
        List<Integer> acc = List.of(0, 1, 2, 3, 4, 5);
        y = row(y, new Ui.Cycle<>(lx(), y, colW(), 20, "Цвет акцента", acc, p.accent, i -> Ui.ACCENT_NAMES[i],
                        i -> "Цвет кнопок и выделений редактора.", v -> { p.accent = v; ClientPrefs.save(); }),
                new Ui.Slider(rx(), y, colW(), 20, "Непрозрачность", 50, 100, p.opacity, v -> v + "%",
                        v -> { p.opacity = v; ClientPrefs.save(); }).help("Насколько панель закрывает мир позади."));
        y = row(y, new Ui.Toggle(lx(), y, colW(), 20, "Превью при открытии", p.previewOnOpen, v -> { p.previewOnOpen = v; ClientPrefs.save(); })
                        .help("Сразу показывать рамки зданий, когда открываете редактор."),
                new Ui.Toggle(rx(), y, colW(), 20, "Строка подсказок", p.hints, v -> { p.hints = v; ClientPrefs.save(); })
                        .help("Показывать пояснение к элементу под курсором внизу окна."));
        y = row(y, new Ui.Button(lx(), y, colW(), 20, "Окно по центру", Ui.Button.Kind.NORMAL,
                        () -> { p.panelDX = 0; p.panelDY = 0; ClientPrefs.save(); rebuild(); }).help("Окно можно перетаскивать за заголовок."),
                new Ui.Button(rx(), y, colW(), 20, "Сбросить генератор", Ui.Button.Kind.DANGER,
                        () -> { ClientState.resetSettings(); rebuild(); }).help("Вернуть все настройки генератора по умолчанию (сид сохраняется)."));
        y = heading(y, "О моде");
        y = text(y, "§fCityGen " + VERSION + "§7 — генератор реалистичных зданий и кварталов для Minecraft 1.20.1 (Forge).\n"
                + "Клавиша §fG§7 — открыть редактор (меняется в Настройки → Управление). /citygen undo — отмена, /citygen cancel — остановка.", Ui.SUB);
        y = text(y, "§dТак вы можете поддержать автора ♥", Ui.TEXT);
        return row(y, new Ui.Button(lx(), y, colW(), 20, "♥ Поддержать автора", Ui.Button.Kind.PRIMARY, this::openSupport)
                .help("Откроется предложение обмена в Steam (после подтверждения ссылки)."), null);
    }

    private void openSupport() {
        minecraft.setScreen(new ConfirmLinkScreen(ok -> {
            if (ok) Util.getPlatform().openUri(STEAM);
            minecraft.setScreen(new EditorScreen());
        }, STEAM, true));
    }

    // =====================================================================
    // Ввод
    // =====================================================================
    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (ClientSetup.OPEN_EDITOR.matches(key, scan) && !(getFocused() instanceof EditBox)) { onClose(); return true; }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (GuiEventListener l : children()) {
            if (l instanceof Ui.W w && content.contains(w) && w.visible && w.isMouseOver(mx, my)
                    && (my < cy0 || my > cy0 + ch)) return false;
        }
        if (super.mouseClicked(mx, my, button)) return true;
        if (button == 0 && mx >= px && mx < px + pw && my >= py && my < py + 24) {
            dragging = true; dragOX = mx; dragOY = my;
            return true;
        }
        if (button == 0 && prefs().tab == TAB_MAP && inMap(mx, my)) { mapDragging = true; return true; }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging) {
            int ddx = (int) (mx - dragOX), ddy = (int) (my - dragOY);
            if (ddx != 0 || ddy != 0) {
                prefs().panelDX += ddx; prefs().panelDY += ddy;
                dragOX += ddx; dragOY += ddy;
                rebuild();
            }
            return true;
        }
        if (mapDragging && MapPreview.ready()) {
            double k = mapScale();
            mapCU -= dx / k; mapCV -= dy / k;
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging) { dragging = false; ClientPrefs.save(); }
        mapDragging = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (prefs().tab == TAB_MAP && inMap(mx, my)) {
            mapZoom = Mth.clamp(mapZoom + (delta > 0 ? 1 : -1), 1, 8);
            return true;
        }
        if (mx >= cx0 && mx < cx0 + cw && my >= cy0 && my < cy0 + ch) {
            int max = Math.max(0, contentBottom - cy0 - ch);
            int ns = Mth.clamp(scroll - (int) Math.signum(delta) * 22, 0, max);
            if (ns != scroll) { scroll = ns; rebuild(); }
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private boolean inMap(double mx, double my) { return mx >= mapX && mx < mapX + mapW && my >= mapY && my < mapY + mapH; }

    @Override
    public void onClose() {
        ClientState.save();
        ClientPrefs.save();
        MapPreview.wanted = false;
        super.onClose();
    }

    @Override
    public void tick() {
        if (seedBox != null) seedBox.tick();
    }

    // =====================================================================
    // Отрисовка
    // =====================================================================
    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        int acc = Ui.accent();
        // тень и панель
        g.fill(px + 3, py + 3, px + pw + 3, py + ph + 3, 0x55000000);
        Ui.rect(g, px, py, pw, ph, Ui.panel());
        Ui.outline(g, px, py, pw, ph, Ui.alpha(acc, 0.55f));
        // шапка
        g.fill(px + 1, py + 1, px + pw - 1, py + 24, Ui.HEADER);
        g.fill(px + 1, py + 24, px + pw - 1, py + 25, Ui.alpha(acc, 0.6f));
        Ui.rect(g, px + 6, py + 5, 14, 14, acc);
        g.drawString(font, "C", px + 10, py + 8, 0xFF0B1118, false);
        g.drawString(font, "CityGen", px + 25, py + 8, Ui.TEXT, false);
        if (pw > 420) Ui.small(g, font, "генератор зданий и кварталов", px + 25 + font.width("CityGen") + 6, py + 10, Ui.SUB);
        // боковая панель
        g.fill(px + 1, py + 25, px + side, py + ph - 1, Ui.SIDEBAR);
        g.fill(px + side, py + 25, px + side + 1, py + ph - 1, Ui.BORDER);
        if (side > 40) sidebarStats(g);
        // подвал
        g.fill(px + side + 1, py + ph - 31, px + pw - 1, py + ph - 30, Ui.BORDER);
        // подсказка
        int hy = py + ph - 44;
        if (prefs().hints) {
            String h = hoveredHelp(mx, my);
            if (!h.isEmpty()) {
                List<FormattedCharSequence> lines = font.split(Component.literal(h), (int) ((cw - 4) / 0.75f));
                int ly = hy + (lines.size() > 1 ? -3 : 2);
                for (int i = 0; i < Math.min(2, lines.size()); i++) {
                    g.pose().pushPose();
                    g.pose().translate(cx0 + 2, ly + i * 8, 0);
                    g.pose().scale(0.75f, 0.75f, 1f);
                    g.drawString(font, lines.get(i), 0, 0, Ui.SUB, false);
                    g.pose().popPose();
                }
            }
        }
        int tab = prefs().tab;

        super.render(g, mx, my, pt);

        // содержимое
        if (tab == TAB_MAP) renderMap(g);
        g.enableScissor(cx0 - 2, cy0 - 2, cx0 + cw + 2, cy0 + ch + 2);
        for (Ui.W w : content) if (w.visible) w.render(g, mx, my, pt);
        if (seedBox != null && seedBox.visible) {
            Ui.rect(g, seedBox.getX() - 2, seedBox.getY() - 1, seedBox.getWidth() + 4, 20, Ui.WIDGET);
            seedBox.render(g, mx, my, pt);
        }
        g.disableScissor();
        // полоса прокрутки
        int max = contentBottom - cy0 - ch;
        if (max > 0) {
            int bh = Math.max(16, ch * ch / (ch + max));
            int by = cy0 + (int) ((ch - bh) * (scroll / (double) max));
            g.fill(cx0 + cw + 3, cy0, cx0 + cw + 5, cy0 + ch, 0x33FFFFFF);
            g.fill(cx0 + cw + 3, by, cx0 + cw + 5, by + bh, Ui.alpha(acc, 0.9f));
        }
    }

    private String hoveredHelp(int mx, int my) {
        for (GuiEventListener l : children())
            if (l instanceof Ui.W w && w.visible && w.isMouseOver(mx, my)) {
                String h = w.help();
                if (!h.isEmpty()) return h;
            }
        if (seedBox != null && seedBox.visible && seedBox.isMouseOver(mx, my))
            return "Сид определяет все случайные решения. Тот же сид на том же месте даёт те же здания.";
        if (prefs().tab == TAB_MAP && inMap(mx, my)) return "Колесо — масштаб, перетаскивание — сдвиг. Красная точка — вы, верх карты — направление взгляда.";
        return "";
    }

    private void sidebarStats(GuiGraphics g) {
        Layout L = ClientState.layout;
        int x = px + 7, y = py + 26 + TABS.length * 22 + 8;
        if (y + 50 > py + ph) return;
        g.fill(px + 6, y - 4, px + side - 6, y - 3, Ui.BORDER);
        if (L == null || L.plans.isEmpty() && L.lots.isEmpty()) {
            Ui.small(g, font, "Нет превью", x, y, Ui.DIM);
            return;
        }
        int fmin = Integer.MAX_VALUE, fmax = 0, hmax = 0;
        for (Plan p : L.plans) { fmin = Math.min(fmin, p.floors); fmax = Math.max(fmax, p.floors); hmax = Math.max(hmax, p.estimatedHeight()); }
        Ui.small(g, font, "Зданий: " + L.plans.size(), x, y, Ui.TEXT);
        if (!L.plans.isEmpty()) {
            Ui.small(g, font, "Этажей: " + (fmin == fmax ? fmin : fmin + "–" + fmax), x, y + 9, Ui.SUB);
            Ui.small(g, font, "Высота до " + hmax + " м", x, y + 18, Ui.SUB);
        }
        if (!L.lots.isEmpty()) Ui.small(g, font, "Парков/площадей: " + L.lots.size(), x, y + 27, Ui.SUB);
        if (L.warnings > 0) Ui.small(g, font, "⚠ понижено: " + L.warnings, x, y + 36, 0xFFFFC857);
        Ui.small(g, font, "v" + VERSION + " • клавиша G", x, py + ph - 10, Ui.DIM);
    }

    private double mapScale() {
        double k = Math.min(mapW / (double) MapPreview.texW, mapH / (double) MapPreview.texH);
        return k * mapZoom;
    }

    private void renderMap(GuiGraphics g) {
        Ui.rect(g, mapX, mapY, mapW, mapH, 0xFF0E141B);
        Ui.outline(g, mapX, mapY, mapW, mapH, Ui.BORDER);
        if (!MapPreview.ready()) {
            String t = MapPreview.busy ? "Рисую карту…" : (ClientState.layout == null ? "Нет превью — нажмите «Сюда»" : "Подготовка…");
            g.drawCenteredString(font, t, mapX + mapW / 2, mapY + mapH / 2 - 4, Ui.SUB);
            return;
        }
        int tw = MapPreview.texW, th = MapPreview.texH;
        double k = mapScale();
        if (mapCU < 0 || mapCU > tw || mapCV < 0 || mapCV > th) { mapCU = tw / 2.0; mapCV = th / 2.0; }
        int srcW = (int) Math.min(tw, Math.floor(mapW / k)), srcH = (int) Math.min(th, Math.floor(mapH / k));
        mapCU = Mth.clamp(mapCU, srcW / 2.0, tw - srcW / 2.0);
        mapCV = Mth.clamp(mapCV, srcH / 2.0, th - srcH / 2.0);
        int u = (int) Math.round(mapCU - srcW / 2.0), v = (int) Math.round(mapCV - srcH / 2.0);
        int dw = (int) Math.round(srcW * k), dh = (int) Math.round(srcH * k);
        int dx = mapX + (mapW - dw) / 2, dy = mapY + (mapH - dh) / 2;
        g.blit(MapPreview.TEX, dx, dy, dw, dh, u, v, srcW, srcH, tw, th);
        if (MapPreview.busy) Ui.small(g, font, "обновление…", mapX + 4, mapY + 4, 0xFFFFC857);
        Ui.small(g, font, "×" + mapZoom, mapX + mapW - 16, mapY + 4, Ui.SUB);
        Ui.small(g, font, "↑ направление взгляда", mapX + 4, mapY + mapH - 9, Ui.SUB);
    }
}
