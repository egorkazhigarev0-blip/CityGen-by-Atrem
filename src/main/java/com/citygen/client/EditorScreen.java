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
    public static final String VERSION = "0.3.0";
    public static final String STEAM = "https://steamcommunity.com/tradeoffer/new/?partner=1869951288&token=joiRehOd";
    public static final String TELEGRAM = "https://t.me/atremt";
    private static final String[] TABS = {"Здания", "Архитектура", "Интерьер", "Участок", "Город", "Вид сверху", "Настройки"};
    private static final ItemStack[] ICONS = {new ItemStack(Items.BRICKS), new ItemStack(Items.QUARTZ_PILLAR), new ItemStack(Items.RED_BED),
            new ItemStack(Items.OAK_SAPLING), new ItemStack(Items.FILLED_MAP), new ItemStack(Items.SPYGLASS), new ItemStack(Items.COMPARATOR)};
    private static final int TAB_MAP = 5;

    private static int scroll;
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
        pw = width < 380 ? width - 4 : Mth.clamp(width - 24, 380, 640);
        ph = height < 232 ? height - 4 : Mth.clamp(height - 16, 232, 370);
        px = Mth.clamp((width - pw) / 2 + prefs().panelDX, 0, Math.max(0, width - pw));
        py = Mth.clamp((height - ph) / 2 + prefs().panelDY, 0, Math.max(0, height - ph));
        side = pw >= 460 ? 108 : 28;
        cx0 = px + side + 8;
        cy0 = py + 28;
        cw = pw - side - 16;
        ch = ph - 28 - 30 - 16;

        int tab = Mth.clamp(prefs().tab, 0, TABS.length - 1);
        // вкладки
        for (int i = 0; i < TABS.length; i++) {
            final int ti = i;
            Ui.Tab t = new Ui.Tab(px, py + 30 + i * 23, side, 21, TABS[i], ICONS[i], tab == i, () -> { prefs().tab = ti; scroll = 0; rebuild(); });
            t.help = TABS[i];
            addRenderableWidget(t);
        }
        // заголовок
        addRenderableWidget(new Ui.Button(px + pw - 22, py + 4, 18, 16, "✕", Ui.Button.Kind.GHOST, this::onClose).help("Закрыть (Esc или G)"));
        addRenderableWidget(new Ui.Button(px + pw - 44, py + 4, 20, 16, "♥", Ui.Button.Kind.GHOST, this::openSupport)
                .help("Поддержать автора — обмен в Steam (откроется браузер после подтверждения)."));
        addRenderableWidget(new Ui.Button(px + pw - 66, py + 4, 20, 16, "✈", Ui.Button.Kind.GHOST, this::openTelegram)
                .help("Telegram-канал t.me/atremt — здесь вы можете увидеть ранние версии и следить за модом."));
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
        boolean cityMode = s().arrangement == Arrangement.CITY && !s().quarters.isEmpty();
        addRenderableWidget(new Ui.Button(bx, fy, bw, 20, cityMode ? "Город сюда" : "Сюда", Ui.Button.Kind.NORMAL, () -> {
            if (cityMode) ClientState.moveCityToPlayer(); else ClientState.anchorHere();
            rebuild();
        }).help(cityMode ? "Сдвинуть все кварталы так, чтобы город начинался перед вами (расположение кварталов сохранится)."
                : "Перенести участок к вам: точка привязки и направление взгляда берутся заново (уровень — земля под ногами)."));
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
        boolean planner = false; // размеры в «Городе» задаются у каждого квартала
        y = heading(y, "Основное");
        Ui.Slider cnt = sld(lx(), y, "Количество", 1, 16, s.count, String::valueOf, v -> s.count = v,
                "Сколько зданий в режимах «Ряд» и «Улица». В режиме «Город» здания расставляются по кварталам.");
        cnt.active = s.arrangement != Arrangement.CITY;
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
                tog(rx(), y, "Лифты", s.elevators, v -> s.elevators = v, "Декоративные лифтовые шахты — только в небоскрёбах, офисах и многоэтажках от 10 этажей. В остальных зданиях — лестницы."));
        y = row(y, tog(lx(), y, "Детали на крыше", s.rooftop, v -> s.rooftop = v,
                "Выход на крышу, вентиляция, панели, баки, антенны, шпили, сады."), null);
        y = heading(y, "Что внутри");
        return text(y, "§7Квартиры: гостиная с диваном и ТВ, спальни с кроватями и шкафами, кухня, санузел.\n"
                + "Офисы: открытые пространства с рядами столов, переговорные со стеклянными стенами.\n"
                + "Первые этажи: лобби с ресепшеном, кафе, пекарни, книжные, цветочные, супермаркеты.\n"
                + "Общественные здания: музеи с экспонатами или библиотеки с рядами стеллажей.\n"
                + "Высокие дома получают лестничную клетку с маршами и площадками, дома — лестницу вдоль стены.\n"
                + "Каждое здание проверяется «виртуальным игроком»: все комнаты и этажи должны быть достижимы.", Ui.SUB);
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
        y = text(y, "§fГород из свободных кварталов.§7 Откройте карту местности, нарисуйте кварталы мышью, "
                + "двигайте и растягивайте их, настраивайте каждый — а потом поправьте положение прямо в мире.", Ui.TEXT);
        y = row(y, new Ui.Button(lx(), y, colW(), 20, "Открыть редактор города", Ui.Button.Kind.PRIMARY, () -> minecraft.setScreen(new CityEditorScreen(this)))
                        .icon(new ItemStack(Items.FILLED_MAP)).help("Карта местности сверху: рисуйте, двигайте и настраивайте кварталы."),
                new Ui.Button(rx(), y, colW(), 20, "Править в мире", Ui.Button.Kind.NORMAL, () -> {
                    if (s.quarters.isEmpty()) return;
                    s.arrangement = Arrangement.CITY;
                    if (ClientState.selected < 0) ClientState.selected = 0;
                    ClientState.worldEdit = true; ClientState.previewActive = true; ClientState.recompute(); onClose();
                }).help("Закрыть окно и двигать кварталы стрелками прямо в мире (Enter — вернуться)."));
        boolean cityMode = s.arrangement == Arrangement.CITY;
        if (!cityMode && !s.quarters.isEmpty())
            y = row(y, new Ui.Button(lx(), y, colW(), 20, "Включить режим «Город»", Ui.Button.Kind.NORMAL,
                    () -> { s.arrangement = Arrangement.CITY; changed(); rebuild(); }).help("Сейчас выбрана схема «" + s.arrangement.label() + "»."), null);
        y = heading(y, "Кварталы (" + s.quarters.size() + "/" + GenSettings.MAX_QUARTERS + ")");
        if (s.quarters.isEmpty()) return text(y, "§7Пока пусто — откройте редактор города и нарисуйте первый квартал.", Ui.SUB);
        int tw = colW();
        for (int i = 0; i < s.quarters.size(); i++) {
            GenSettings.Quarter q = s.quarters.get(i);
            final int qi = i;
            int x = i % 2 == 0 ? lx() : rx();
            add(new Ui.Tile(x, y, tw, 24, q.name, q.kind.label() + " • " + q.w() + "×" + q.d() + " м", q.kind.color, () -> false, () -> {
                ClientState.selected = qi; minecraft.setScreen(new CityEditorScreen(this));
            }).help("Открыть квартал в редакторе города."));
            if (i % 2 == 1 || i == s.quarters.size() - 1) y += 27;
        }
        y = heading(y + 2, "Весь город");
        return row(y, sld(lx(), y, "Строящиеся здания", 0, 100, s.construction, v -> v + "%", v -> s.construction = v,
                        "Доля зданий, которые ещё строятся: каркас, леса, краны. По умолчанию 3% — как в жизни."),
                cyc(rx(), y, "Озеленение", Greenery.values(), s.greenery, v -> s.greenery = v));
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
        GenSettings gs = s();
        y = heading(y, "Интерфейс");
        List<Integer> acc = new ArrayList<>();
        for (int i = 0; i < Ui.ACCENTS.length; i++) acc.add(i);
        y = row(y, new Ui.Cycle<>(lx(), y, colW(), 20, "Цвет акцента", acc, p.accent, i -> Ui.ACCENT_NAMES[i],
                        i -> "Цвет кнопок и выделений редактора.", v -> { p.accent = v; ClientPrefs.save(); rebuild(); }),
                new Ui.Slider(rx(), y, colW(), 20, "Непрозрачность", 50, 100, p.opacity, v -> v + "%",
                        v -> { p.opacity = v; ClientPrefs.save(); }).help("Насколько панель закрывает мир позади."));
        y = row(y, new Ui.Toggle(lx(), y, colW(), 20, "Современный шрифт", p.modernFont, v -> { p.modernFont = v; ClientPrefs.save(); rebuild(); })
                        .help("Гладкий шрифт Noto Sans в окнах CityGen. Выключите, чтобы вернуть пиксельный шрифт Minecraft."),
                new Ui.Toggle(rx(), y, colW(), 20, "Строка подсказок", p.hints, v -> { p.hints = v; ClientPrefs.save(); })
                        .help("Показывать пояснение к элементу под курсором внизу окна."));
        y = row(y, new Ui.Toggle(lx(), y, colW(), 20, "Превью при открытии", p.previewOnOpen, v -> { p.previewOnOpen = v; ClientPrefs.save(); })
                        .help("Сразу показывать рамки зданий, когда открываете редактор."),
                new Ui.Button(rx(), y, colW(), 20, "Окно по центру", Ui.Button.Kind.NORMAL,
                        () -> { p.panelDX = 0; p.panelDY = 0; ClientPrefs.save(); rebuild(); }).help("Окно можно перетаскивать за заголовок."));
        y = heading(y, "Генерация");
        y = row(y, sld(lx(), y, "Строящиеся здания", 0, 100, gs.construction, v -> v + "%", v -> gs.construction = v,
                        "Шанс, что здание ещё строится: каркас, леса, строительная сетка, кран. В жизни это редкость — по умолчанию 3%."),
                new Ui.Button(rx(), y, colW(), 20, "Сбросить генератор", Ui.Button.Kind.DANGER,
                        () -> { ClientState.resetSettings(); rebuild(); }).help("Вернуть все настройки генератора по умолчанию (сид сохраняется)."));
        y = heading(y, "О моде");
        y = text(y, "§fCityGen " + VERSION + "§7 — генератор реалистичных зданий, кварталов и городов для Minecraft 1.20.1 (Forge).\n"
                + "Клавиша §fG§7 — открыть редактор (меняется в Настройки → Управление). /citygen undo — отмена, /citygen cancel — остановка.", Ui.SUB);
        y = text(y, "§bt.me/atremt§7 — здесь вы можете увидеть ранние версии и следить за модом.", Ui.TEXT);
        y = text(y, "§dТак вы можете поддержать автора ♥", Ui.TEXT);
        y = row(y, new Ui.Button(lx(), y, colW(), 20, "✈ Telegram-канал", Ui.Button.Kind.NORMAL, this::openTelegram)
                        .help("Откроется t.me/atremt (после подтверждения ссылки): ранние версии и новости мода."),
                new Ui.Button(rx(), y, colW(), 20, "♥ Поддержать автора", Ui.Button.Kind.PRIMARY, this::openSupport)
                        .help("Откроется предложение обмена в Steam (после подтверждения ссылки)."));
        return text(y, "§8Шрифт интерфейса — Noto Sans (SIL Open Font License).", Ui.DIM);
    }

    private void openTelegram() {
        minecraft.setScreen(new ConfirmLinkScreen(ok -> {
            if (ok) Util.getPlatform().openUri(TELEGRAM);
            minecraft.setScreen(new EditorScreen());
        }, TELEGRAM, true));
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
        Ui.softShadow(g, px, py, pw, ph, 6);
        Ui.round(g, px, py, pw, ph, 6, Ui.alpha(acc, 0.5f));
        Ui.round(g, px + 1, py + 1, pw - 2, ph - 2, 5, Ui.panel());
        // шапка
        g.fillGradient(px + 4, py + 1, px + pw - 4, py + 25, Ui.mix(Ui.HEADER, acc, 0.10f), Ui.HEADER);
        Ui.round(g, px + 1, py + 1, pw - 2, 6, 5, Ui.mix(Ui.HEADER, acc, 0.10f));
        g.fill(px + 1, py + 25, px + pw - 1, py + 26, Ui.alpha(acc, 0.55f));
        Ui.round(g, px + 6, py + 5, 16, 16, 4, acc);
        Ui.center(g, "C", px + 14, py + 9, 0xFF0A1016);
        Ui.text(g, "CityGen", px + 28, py + 9, Ui.TEXT);
        if (pw > 440) Ui.small(g, "генератор зданий, кварталов и городов", px + 28 + Ui.width("CityGen") + 6, py + 11, Ui.SUB);
        // боковая панель
        g.fill(px + 1, py + 26, px + side, py + ph - 6, Ui.SIDEBAR);
        Ui.round(g, px + 1, py + ph - 8, side - 1, 7, 5, Ui.SIDEBAR);
        g.fill(px + side, py + 26, px + side + 1, py + ph - 1, Ui.BORDER);
        if (side > 40) sidebarStats(g);
        // подвал
        g.fill(px + side + 1, py + ph - 31, px + pw - 1, py + ph - 30, Ui.BORDER);
        // подсказка
        int hy = py + ph - 44;
        if (prefs().hints) {
            String h = hoveredHelp(mx, my);
            if (!h.isEmpty()) {
                List<FormattedCharSequence> lines = Ui.wrap(h, (int) ((cw - 4) / 0.75f));
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
        int x = px + 8, y = py + 30 + TABS.length * 23 + 8;
        if (y + 50 > py + ph) return;
        g.fill(px + 6, y - 4, px + side - 6, y - 3, Ui.BORDER);
        if (L == null || L.plans.isEmpty() && L.lots.isEmpty()) {
            Ui.small(g, "Нет превью", x, y, Ui.DIM);
            return;
        }
        int fmin = Integer.MAX_VALUE, fmax = 0, hmax = 0;
        for (Plan p : L.plans) { fmin = Math.min(fmin, p.floors); fmax = Math.max(fmax, p.floors); hmax = Math.max(hmax, p.estimatedHeight()); }
        Ui.small(g, "Зданий: " + L.plans.size(), x, y, Ui.TEXT);
        if (!L.plans.isEmpty()) {
            Ui.small(g, "Этажей: " + (fmin == fmax ? fmin : fmin + "–" + fmax), x, y + 9, Ui.SUB);
            Ui.small(g, "Высота до " + hmax + " м", x, y + 18, Ui.SUB);
        }
        if (!L.lots.isEmpty()) Ui.small(g, "Парков/площадей: " + L.lots.size(), x, y + 27, Ui.SUB);
        if (L.warnings > 0) Ui.small(g, "⚠ понижено: " + L.warnings, x, y + 36, 0xFFFFC857);
        Ui.small(g, "v" + VERSION + " • клавиша G", x, py + ph - 10, Ui.DIM);
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
            Ui.center(g, t, mapX + mapW / 2, mapY + mapH / 2 - 4, Ui.SUB);
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
        if (MapPreview.busy) Ui.small(g, "обновление…", mapX + 4, mapY + 4, 0xFFFFC857);
        Ui.small(g, "×" + mapZoom, mapX + mapW - 16, mapY + 4, Ui.SUB);
        Ui.small(g, "↑ направление взгляда", mapX + 4, mapY + mapH - 9, Ui.SUB);
    }
}
