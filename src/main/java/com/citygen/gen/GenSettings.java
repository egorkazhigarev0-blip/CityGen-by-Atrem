package com.citygen.gen;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.Mth;


/** Все настройки генератора. Передаются на сервер пакетом и сохраняются в config/citygen-editor.json. */
public class GenSettings {

    public interface Labeled {
        String label();
        String help();
    }

    public enum StyleMode implements Labeled {
        AUTO("Авто", "Стиль подбирается по типу здания."),
        MODERN("Современный", "Стекло, бетон, каркасные сетки, балконы-ленты, плоские крыши с террасами."),
        CLASSIC("Культурный", "Европейская классика: кирпич и камень, наличники, эркеры, карнизы, мансарды."),
        ANTIQUE("Античный", "Колоннады, портики, арки, фронтоны и купола из мрамора и травертина."),
        FANTASY("Фантастический", "Неоновые пояса, цилиндрические и закрученные башни, стеклянные купола, кольца-платформы.");
        final String l, h; StyleMode(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
        public boolean traditional() { return this == CLASSIC || this == ANTIQUE; }
    }

    public enum Variation implements Labeled {
        SAME("Одинаковые", "Все здания — точные копии."),
        SIMILAR("Похожие", "Один стиль и палитра, но разная высота, ширина и цвет стен."),
        DIFFERENT("Разные", "Каждое здание со своей палитрой, фасадом и размерами (±25%)."),
        CONTRAST("Очень разные", "Разные стили и размеры (±40%) — эклектичная городская улица.");
        final String l, h; Variation(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum HeightProfile implements Labeled {
        RANDOM("Случайные", "Этажность каждого здания случайна в заданном диапазоне."),
        EQUAL("Одинаковые", "Все здания одной высоты."),
        RISING("По возрастанию", "Слева направо здания становятся выше."),
        PEAK("Пик в центре", "Самые высокие здания в середине — силуэт делового центра.");
        final String l, h; HeightProfile(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum FacadeType implements Labeled {
        AUTO("Авто", "Фасад подбирается по типу и стилю здания."),
        CURTAIN("Витраж", "Сплошное стекло с импостами и рёбрами, как у офисных башен."),
        GRID("Каркасная сетка", "Выступающая рама вокруг каждого окна — глубокий фасад."),
        RIBBON("Ленточные окна", "Горизонтальные полосы окон и выступающие пояса."),
        PUNCHED("Окна-проёмы", "Стена с отдельными окнами, цветные панели или рамки-короба."),
        BALCONY("Балконы-ленты", "Сплошные балконы со стеклянным ограждением и перегородками."),
        DIAGRID("Диагональная сетка", "Ромбическая несущая сетка поверх стекла — как у современных небоскрёбов."),
        CLASSIC("Наличники", "Высокие окна с подоконниками и сандриками, ставни, эркеры, балкончики."),
        PILASTER("Пилястры", "Колонны-пилястры между окнами, межэтажные карнизы."),
        ARCADE("Арки", "Арочные окна с замковым камнем и пилястрами."),
        COLONNADE("Колоннада", "Античный портик из колонн перед фасадом, антаблемент и фронтон."),
        NEON("Неон", "Тёмное стекло, светящиеся пояса и вертикали, кольца-платформы.");
        final String l, h; FacadeType(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum Shape implements Labeled {
        AUTO("Авто", "Форма подбирается по типу и стилю."),
        RECT("Прямоугольник", "Простой объём; у небоскрёба — подиум и башня."),
        L("Г-образная", "Два крыла под углом."),
        U("П-образная", "Три крыла с открытым двором сзади."),
        COURTYARD("Двор-колодец", "Замкнутый внутренний двор — европейский квартал."),
        CROSS("Крест", "Крестообразный план, как у музеев и соборов."),
        CYLINDER("Цилиндр", "Круглая или овальная башня."),
        TWIST("Закрученная", "Каждый этаж повёрнут — спиральная башня."),
        TAPER("Сужающаяся", "Башня плавно сужается к вершине."),
        STEPPED("Уступы", "Ступенчатый силуэт в стиле ар-деко.");
        final String l, h; Shape(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum RoofType implements Labeled {
        AUTO("Авто", "Крыша подбирается по типу и стилю."),
        FLAT("Плоская", "Парапет, выход на крышу, техника, панели или зелёная кровля."),
        MANSARD("Мансарда", "Этаж в крутой крыше со слуховыми окнами и трубами (Париж)."),
        GABLE("Двускатная", "Скаты со свесами; у таунхаусов — фронтон на улицу."),
        HIP("Вальмовая", "Четыре ската — частные дома и особняки."),
        PEDIMENT("Фронтон", "Пологая крыша с треугольным фронтоном — античный храм."),
        DOME("Купол", "Купол на барабане или стеклянная полусфера."),
        CONE("Шатёр", "Крутая шатровая крыша — башни и башенки.");
        final String l, h; RoofType(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum GroundFloor implements Labeled {
        AUTO("Авто", "По типу здания."),
        SHOPS("Магазины", "Витрины, вывески, маркизы, кафе и лавки внутри."),
        LOBBY("Лобби", "Высокое остеклённое лобби, ресепшен, диваны."),
        RESIDENTIAL("Как остальные", "Первый этаж такой же, как верхние (с цоколем).");
        final String l, h; GroundFloor(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum Balconies implements Labeled {
        AUTO("Авто", "По типу здания."),
        NONE("Нет", "Без балконов."),
        SOME("Местами", "Балконы через один, шахматкой или колонками."),
        MANY("Везде", "Балкон у каждого окна фасада.");
        final String l, h; Balconies(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum WindowSize implements Labeled {
        AUTO("Авто", "Ширина окон по фасаду."),
        NARROW("Узкие", "Окна в 1 блок, широкие простенки."),
        NORMAL("Обычные", "Окна в 2 блока."),
        WIDE("Широкие", "Окна в 3 блока, панорамное остекление.");
        final String l, h; WindowSize(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum Interior implements Labeled {
        NONE("Пустая коробка", "Только стены и крыша — строится быстрее всего."),
        FLOORS("Перекрытия", "Этажи, свет и лестница без перегородок."),
        ROOMS("Планировка", "Комнаты, перегородки, двери, лестничная клетка и лифт."),
        FURNISHED("С мебелью", "Квартиры, офисы, магазины, кафе, лобби — полностью обставлены.");
        final String l, h; Interior(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum StairsMode implements Labeled {
        AUTO("Авто", "Лестничная клетка там, где хватает места, иначе — марш вдоль стены или стремянка."),
        STAIRWELL("Лестничная клетка", "Настоящие ступени с площадками между этажами."),
        LADDER("Стремянка", "Компактная лестница-стремянка в шахте.");
        final String l, h; StairsMode(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum Toggle3 implements Labeled {
        AUTO("Авто", "По стилю и типу."),
        OFF("Нет", "Выключено."),
        ON("Да", "Включено для всех подходящих зданий.");
        final String l, h; Toggle3(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum Greenery implements Labeled {
        NONE("Нет", "Без деревьев и клумб."),
        LIGHT("Немного", "Деревья вдоль тротуаров, клумбы у входов."),
        RICH("Много", "Аллеи, живые изгороди, скамейки, кашпо на балконах.");
        final String l, h; Greenery(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum Arrangement implements Labeled {
        ROW("Ряд", "Здания стоят в линию перед вами, фасадами к вам."),
        STREET("Улица", "Улица уходит вперёд, здания по обе стороны смотрят на неё."),
        CITY("Город", "Свободные кварталы из редактора города: ставьте их на карте местности и двигайте прямо в мире.");
        final String l, h; Arrangement(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    /** Тип квартала в редакторе города. */
    public enum QuarterKind implements Labeled {
        MIXED("Смешанный", 0xFF6FA8FF, "Многоэтажки, дома средней плотности, офисы и магазины вперемешку."),
        DOWNTOWN("Деловой центр", 0xFF4F8DFF, "Небоскрёбы и офисные башни, площади между ними."),
        RESIDENTIAL("Жилой район", 0xFF8C6CF0, "Многоэтажки и дома средней плотности, зелёные дворы."),
        HISTORIC("Исторический центр", 0xFFE0A33C, "Сплошная застройка по периметру, мансарды, кафе на первых этажах."),
        TOWNHOUSES("Таунхаусы", 0xFFE0703C, "Ряды узких домов вплотную, ступенчатые фронтоны."),
        SUBURB("Пригород", 0xFFD9C36A, "Частные дома с палисадниками и садами."),
        COMMERCIAL("Торговый", 0xFFE05A7A, "Торговые центры с парковками."),
        CULTURE("Культурный", 0xFFB9A6E0, "Музеи, библиотеки и театры вокруг площади."),
        PARK("Парк", 0xFF4CAF50, "Весь квартал — сквер: газоны, дорожки, фонтан, скамейки."),
        PLAZA("Площадь", 0xFFB0B7BF, "Мощёная площадь с фонтаном или памятником."),
        PARKING("Парковка", 0xFF55606B, "Асфальтовая парковка с разметкой и машинами."),
        CONSTRUCTION("Стройка", 0xFFFFC23D, "Все здания квартала строятся: краны, леса, строительная сетка."),
        CUSTOM("Свои настройки", 0xFF9AA7B5, "Тип, стиль, этажность и размеры — из вкладки «Здания».");
        final String l, h; public final int color;
        QuarterKind(String l, int color, String h) { this.l = l; this.color = color; this.h = h; }
        public String label() { return l; } public String help() { return h; }
        public boolean open() { return this == PARK || this == PLAZA || this == PARKING; }
    }

    public enum QFacing implements Labeled {
        AUTO("Авто", "По типу квартала."),
        NORTH_SOUTH("Север + юг", "Два ряда домов спиной друг к другу: фасады на северную и южную улицы."),
        WEST_EAST("Запад + восток", "Два ряда домов: фасады на западную и восточную улицы."),
        PERIMETER("По периметру", "Дома по всем четырём сторонам, внутри — двор (европейский квартал)."),
        NORTH("Север", "Один ряд, фасады на северную улицу."),
        SOUTH("Юг", "Один ряд, фасады на южную улицу."),
        WEST("Запад", "Один ряд, фасады на западную улицу."),
        EAST("Восток", "Один ряд, фасады на восточную улицу.");
        final String l, h; QFacing(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum Courtyard implements Labeled {
        AUTO("Авто", "По типу квартала."),
        NONE("Пусто", "Внутри квартала ничего не строится."),
        GARDEN("Сад", "Зелёный двор с деревьями, дорожками и скамейками."),
        PARKING("Парковка", "Двор отдан под парковку.");
        final String l, h; Courtyard(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    /** Квартал города: прямоугольник в мировых координатах и его настройки. */
    public static class Quarter {
        public String name = "Квартал";
        public int x0, z0, x1, z1;
        public QuarterKind kind = QuarterKind.MIXED;
        public StyleMode style = StyleMode.AUTO;
        public int floorsMin = 0, floorsMax = 0;   // 0 — по типу квартала
        public int lotWidth = 0, lotDepth = 0;     // 0 — авто
        public int spacing = -1;                   // -1 — авто
        public QFacing facing = QFacing.AUTO;
        public StreetMode street = StreetMode.ROAD;
        public Courtyard courtyard = Courtyard.AUTO;
        public HeightProfile heights = HeightProfile.RANDOM;
        public GroundFloor ground = GroundFloor.AUTO;
        public int construction = -1;              // -1 — как в общих настройках
        public int raise = 0;                      // сдвиг основания относительно рельефа
        public int seed = 1;

        public int w() { return x1 - x0 + 1; }
        public int d() { return z1 - z0 + 1; }

        public Quarter copyQ() {
            Quarter q = new Quarter();
            q.name = name; q.x0 = x0; q.z0 = z0; q.x1 = x1; q.z1 = z1; q.kind = kind; q.style = style;
            q.floorsMin = floorsMin; q.floorsMax = floorsMax; q.lotWidth = lotWidth; q.lotDepth = lotDepth; q.spacing = spacing;
            q.facing = facing; q.street = street; q.courtyard = courtyard; q.seed = seed;
            q.heights = heights; q.ground = ground; q.construction = construction; q.raise = raise;
            return q;
        }

        void sanitize() {
            if (name == null || name.isBlank()) name = "Квартал";
            if (name.length() > 24) name = name.substring(0, 24);
            int ax = Math.min(x0, x1), bx = Math.max(x0, x1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
            x0 = ax; x1 = Math.max(ax + MIN_Q - 1, Math.min(bx, ax + MAX_Q - 1));
            z0 = az; z1 = Math.max(az + MIN_Q - 1, Math.min(bz, az + MAX_Q - 1));
            kind = nz(kind, QuarterKind.MIXED); style = nz(style, StyleMode.AUTO);
            floorsMin = Mth.clamp(floorsMin, 0, 80); floorsMax = Mth.clamp(floorsMax, 0, 80);
            lotWidth = lotWidth == 0 ? 0 : Mth.clamp(lotWidth, 6, 64); lotDepth = lotDepth == 0 ? 0 : Mth.clamp(lotDepth, 7, 64);
            spacing = Mth.clamp(spacing, -1, 16);
            facing = nz(facing, QFacing.AUTO); street = nz(street, StreetMode.ROAD); courtyard = nz(courtyard, Courtyard.AUTO);
            heights = nz(heights, HeightProfile.RANDOM); ground = nz(ground, GroundFloor.AUTO);
            construction = Mth.clamp(construction, -1, 100); raise = Mth.clamp(raise, -16, 16);
        }

        public boolean contains(int x, int z) { return x >= x0 && x <= x1 && z >= z0 && z <= z1; }

        public void moveBy(int dx, int dz) { x0 += dx; x1 += dx; z0 += dz; z1 += dz; }

        void write(FriendlyByteBuf b) {
            b.writeUtf(name, 64); b.writeInt(x0); b.writeInt(z0); b.writeInt(x1); b.writeInt(z1);
            b.writeEnum(kind); b.writeEnum(style); b.writeVarInt(floorsMin); b.writeVarInt(floorsMax);
            b.writeVarInt(lotWidth); b.writeVarInt(lotDepth); b.writeVarInt(spacing + 1); b.writeEnum(facing);
            b.writeEnum(street); b.writeEnum(courtyard); b.writeVarInt(seed);
            b.writeEnum(heights); b.writeEnum(ground); b.writeVarInt(construction + 1); b.writeVarInt(raise + 16);
        }

        static Quarter read(FriendlyByteBuf b) {
            Quarter q = new Quarter();
            q.name = b.readUtf(64); q.x0 = b.readInt(); q.z0 = b.readInt(); q.x1 = b.readInt(); q.z1 = b.readInt();
            q.kind = b.readEnum(QuarterKind.class); q.style = b.readEnum(StyleMode.class);
            q.floorsMin = b.readVarInt(); q.floorsMax = b.readVarInt(); q.lotWidth = b.readVarInt(); q.lotDepth = b.readVarInt();
            q.spacing = b.readVarInt() - 1; q.facing = b.readEnum(QFacing.class); q.street = b.readEnum(StreetMode.class);
            q.courtyard = b.readEnum(Courtyard.class); q.seed = b.readVarInt();
            q.heights = b.readEnum(HeightProfile.class); q.ground = b.readEnum(GroundFloor.class);
            q.construction = b.readVarInt() - 1; q.raise = b.readVarInt() - 16;
            return q;
        }
    }

    public enum GroundMode implements Labeled {
        PLAYER("По вашему уровню", "Все здания стоят на уровне земли под вами (в прыжке и полёте тоже)."),
        TERRAIN("По рельефу", "Каждое здание ставится на средний уровень земли своего участка.");
        final String l, h; GroundMode(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    public enum StreetMode implements Labeled {
        NONE("Нет", "Без тротуаров и дорог."),
        SIDEWALK("Тротуар", "Тротуар с фонарями и скамейками."),
        ROAD("Дорога", "Двухполосная дорога с разметкой, бордюрами и тротуарами.");
        final String l, h; StreetMode(String l, String h) { this.l = l; this.h = h; }
        public String label() { return l; } public String help() { return h; }
    }

    /** Содержимое ячейки планировщика кварталов. */
    public enum CellType implements Labeled {
        AUTO("Авто", "А", 0xFF6B7A8F, "Тип из вкладки «Здания» (или случайный в режиме Микс)."),
        SKYSCRAPER("Небоскрёб", "НБ", 0xFF4F8DFF, "Небоскрёб."),
        OFFICE("Офисы", "ОФ", 0xFF39B3C7, "Офисный центр."),
        HIGHRISE("Многоэтажка", "МЭ", 0xFF8C6CF0, "Жилая многоэтажка."),
        MIDRISE("Ср. плотность", "СП", 0xFFE0A33C, "Городской дом 4–8 этажей."),
        TOWNHOUSE("Таунхаусы", "ТХ", 0xFFE0703C, "Ряд узких таунхаусов во всю ширину участка."),
        HOUSE("Дом", "ДМ", 0xFFD9C36A, "Частный дом с палисадником."),
        COMMERCIAL("ТЦ", "ТЦ", 0xFFE05A7A, "Торговый центр."),
        CIVIC("Общ. здание", "ОБ", 0xFFB9A6E0, "Музей, библиотека или театр."),
        PARK("Парк", "ПК", 0xFF4CAF50, "Сквер: газон, деревья, дорожки, скамейки, фонтан."),
        PLAZA("Площадь", "ПЛ", 0xFFB0B7BF, "Мощёная площадь с фонтаном или памятником."),
        PARKING("Парковка", "P", 0xFF55606B, "Асфальтовая парковка с разметкой."),
        EMPTY("Пусто", "—", 0xFF2A323C, "Участок не трогается.");
        public final String l, code, h; public final int color;
        CellType(String l, String code, int color, String h) { this.l = l; this.code = code; this.color = color; this.h = h; }
        public String label() { return l; } public String help() { return h; }

        public BuildingType building() {
            return switch (this) {
                case SKYSCRAPER -> BuildingType.SKYSCRAPER; case OFFICE -> BuildingType.OFFICE;
                case HIGHRISE -> BuildingType.HIGHRISE; case MIDRISE -> BuildingType.MIDRISE;
                case TOWNHOUSE -> BuildingType.TOWNHOUSE; case HOUSE -> BuildingType.HOUSE;
                case COMMERCIAL -> BuildingType.COMMERCIAL; case CIVIC -> BuildingType.CIVIC;
                default -> null;
            };
        }
    }

    public static final int MAX_QUARTERS = 24, MIN_Q = 14, MAX_Q = 200;

    // ---------------- здания ----------------
    public int count = 3;
    public BuildingType type = BuildingType.MIDRISE;
    public StyleMode style = StyleMode.AUTO;
    public Variation variation = Variation.DIFFERENT;
    public HeightProfile heightProfile = HeightProfile.RANDOM;
    public int floorsMin = 4, floorsMax = 7;
    public int width = 22, depth = 16;

    // ---------------- архитектура ----------------
    public int palette = -1;
    public FacadeType facade = FacadeType.AUTO;
    public Shape shape = Shape.AUTO;
    public RoofType roof = RoofType.AUTO;
    public GroundFloor groundFloor = GroundFloor.AUTO;
    public Balconies balconies = Balconies.AUTO;
    public WindowSize windows = WindowSize.AUTO;
    public int floorHeight = 0; // 0 = авто, иначе 3..7
    public int texture = 15;    // % разнообразия фактуры стен
    public Toggle3 fireEscapes = Toggle3.AUTO;
    public boolean ornaments = true;

    // ---------------- интерьер ----------------
    public Interior interior = Interior.FURNISHED;
    public StairsMode stairs = StairsMode.AUTO;
    public int litPercent = 45;
    public boolean rooftop = true;
    public boolean elevators = true;

    // ---------------- участок ----------------
    public Arrangement arrangement = Arrangement.ROW;
    public int distance = 6;
    public int offset = 0;
    public int rotation = 0;
    public int spacing = 4;
    public GroundMode ground = GroundMode.PLAYER;
    public StreetMode street = StreetMode.SIDEWALK;
    public StreetMode crossStreets = StreetMode.SIDEWALK;
    public Greenery greenery = Greenery.LIGHT;
    public boolean yards = true;
    public boolean clearSite = true;
    public boolean foundation = true;
    public long seed = 20261013L;
    public boolean newSeedEachBuild = true;

    public int construction = 3; // % строящихся зданий

    // ---------------- город ----------------
    public java.util.List<Quarter> quarters = new java.util.ArrayList<>();
    public int buildOnly = -1; // индекс квартала для постройки только его (-1 — все)

    public void applyTypeDefaults() {
        if (type == BuildingType.MIXED) return;
        floorsMin = type.defMin;
        floorsMax = type.defMax;
        width = type.defW;
        depth = type.defD;
        if (type == BuildingType.TOWNHOUSE) spacing = 0;
        else if (spacing < 2) spacing = 4;
    }

    /** Сколько зданий примерно получится в городе (для подсказок). */
    public int plannedBuildings() {
        int k = 0;
        for (Quarter q : quarters) if (!q.kind.open()) k += Math.max(1, (q.w() / 18 + 1) * (q.d() > 40 ? 2 : 1));
        return k;
    }

    public GenSettings copy() {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        write(buf);
        GenSettings c = read(buf);
        buf.release();
        return c;
    }

    static <E extends Enum<E>> E nz(E v, E def) { return v == null ? def : v; }

    public GenSettings sanitize() {
        count = Mth.clamp(count, 1, 16);
        type = nz(type, BuildingType.MIDRISE); style = nz(style, StyleMode.AUTO);
        variation = nz(variation, Variation.DIFFERENT); heightProfile = nz(heightProfile, HeightProfile.RANDOM);
        floorsMin = Mth.clamp(floorsMin, 1, 80); floorsMax = Mth.clamp(floorsMax, 1, 80);
        width = Mth.clamp(width, 5, 72); depth = Mth.clamp(depth, 5, 72);
        palette = Mth.clamp(palette, -1, Palettes.ALL.size() - 1);
        facade = nz(facade, FacadeType.AUTO); shape = nz(shape, Shape.AUTO); roof = nz(roof, RoofType.AUTO);
        groundFloor = nz(groundFloor, GroundFloor.AUTO); balconies = nz(balconies, Balconies.AUTO);
        windows = nz(windows, WindowSize.AUTO);
        if (floorHeight != 0) floorHeight = Mth.clamp(floorHeight, 3, 7);
        texture = Mth.clamp(texture, 0, 60);
        fireEscapes = nz(fireEscapes, Toggle3.AUTO);
        interior = nz(interior, Interior.FURNISHED); stairs = nz(stairs, StairsMode.AUTO);
        litPercent = Mth.clamp(litPercent, 0, 100);
        arrangement = nz(arrangement, Arrangement.ROW);
        distance = Mth.clamp(distance, 0, 64); offset = Mth.clamp(offset, -64, 64);
        rotation = Math.floorMod(rotation, 4); spacing = Mth.clamp(spacing, 0, 24);
        ground = nz(ground, GroundMode.PLAYER); street = nz(street, StreetMode.SIDEWALK);
        crossStreets = nz(crossStreets, StreetMode.SIDEWALK); greenery = nz(greenery, Greenery.LIGHT);
        construction = Mth.clamp(construction, 0, 100);
        if (quarters == null) quarters = new java.util.ArrayList<>();
        quarters.removeIf(java.util.Objects::isNull);
        while (quarters.size() > MAX_QUARTERS) quarters.remove(quarters.size() - 1);
        for (Quarter q : quarters) q.sanitize();
        buildOnly = Mth.clamp(buildOnly, -1, quarters.size() - 1);
        return this;
    }

    public void write(FriendlyByteBuf b) {
        b.writeVarInt(count); b.writeEnum(type); b.writeEnum(style); b.writeEnum(variation); b.writeEnum(heightProfile);
        b.writeVarInt(floorsMin); b.writeVarInt(floorsMax); b.writeVarInt(width); b.writeVarInt(depth);
        b.writeVarInt(palette + 1); b.writeEnum(facade); b.writeEnum(shape); b.writeEnum(roof); b.writeEnum(groundFloor);
        b.writeEnum(balconies); b.writeEnum(windows); b.writeVarInt(floorHeight); b.writeVarInt(texture);
        b.writeEnum(fireEscapes); b.writeBoolean(ornaments);
        b.writeEnum(interior); b.writeEnum(stairs); b.writeVarInt(litPercent); b.writeBoolean(rooftop); b.writeBoolean(elevators);
        b.writeEnum(arrangement); b.writeVarInt(distance); b.writeVarInt(offset + 64); b.writeVarInt(rotation);
        b.writeVarInt(spacing); b.writeEnum(ground); b.writeEnum(street); b.writeEnum(crossStreets); b.writeEnum(greenery);
        b.writeBoolean(yards); b.writeBoolean(clearSite); b.writeBoolean(foundation); b.writeLong(seed); b.writeBoolean(newSeedEachBuild);
        b.writeVarInt(construction); b.writeVarInt(buildOnly + 1);
        b.writeVarInt(quarters.size()); for (Quarter q : quarters) q.write(b);
    }

    public static GenSettings read(FriendlyByteBuf b) {
        GenSettings s = new GenSettings();
        s.count = b.readVarInt(); s.type = b.readEnum(BuildingType.class); s.style = b.readEnum(StyleMode.class);
        s.variation = b.readEnum(Variation.class); s.heightProfile = b.readEnum(HeightProfile.class);
        s.floorsMin = b.readVarInt(); s.floorsMax = b.readVarInt(); s.width = b.readVarInt(); s.depth = b.readVarInt();
        s.palette = b.readVarInt() - 1; s.facade = b.readEnum(FacadeType.class); s.shape = b.readEnum(Shape.class);
        s.roof = b.readEnum(RoofType.class); s.groundFloor = b.readEnum(GroundFloor.class);
        s.balconies = b.readEnum(Balconies.class); s.windows = b.readEnum(WindowSize.class);
        s.floorHeight = b.readVarInt(); s.texture = b.readVarInt();
        s.fireEscapes = b.readEnum(Toggle3.class); s.ornaments = b.readBoolean();
        s.interior = b.readEnum(Interior.class); s.stairs = b.readEnum(StairsMode.class); s.litPercent = b.readVarInt();
        s.rooftop = b.readBoolean(); s.elevators = b.readBoolean();
        s.arrangement = b.readEnum(Arrangement.class); s.distance = b.readVarInt(); s.offset = b.readVarInt() - 64;
        s.rotation = b.readVarInt(); s.spacing = b.readVarInt(); s.ground = b.readEnum(GroundMode.class);
        s.street = b.readEnum(StreetMode.class); s.crossStreets = b.readEnum(StreetMode.class); s.greenery = b.readEnum(Greenery.class);
        s.yards = b.readBoolean(); s.clearSite = b.readBoolean(); s.foundation = b.readBoolean(); s.seed = b.readLong();
        s.newSeedEachBuild = b.readBoolean();
        s.construction = b.readVarInt(); s.buildOnly = b.readVarInt() - 1;
        int nq = Math.min(MAX_QUARTERS, b.readVarInt());
        s.quarters = new java.util.ArrayList<>();
        for (int i = 0; i < nq; i++) s.quarters.add(Quarter.read(b));
        return s.sanitize();
    }
}
