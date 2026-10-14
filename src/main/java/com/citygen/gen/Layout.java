package com.citygen.gen;

import com.citygen.gen.GenSettings.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.*;

/**
 * Раскладка участков. Полностью детерминирована (сид + позиция + направление + кварталы),
 * поэтому клиент рисует превью ровно там, где сервер потом построит.
 * Для «Ряда» и «Улицы» используется система координат игрока: u — вправо, v — вперёд по взгляду.
 * Кварталы «Города» заданы прямо в мировых координатах.
 */
public class Layout {
    public static final int SIDEWALK = 0, ROAD = 1;
    /** Ширина полос вокруг квартала: тротуар 3 + дорога 7. Два квартала с зазором 13 делят одну дорогу. */
    public static final int WALK = 3, ROAD_W = 7, SHARED_GAP = WALK * 2 + ROAD_W;

    /** Полоса дороги или тротуара в мировых координатах. */
    public static class Strip {
        public final int minX, minZ, maxX, maxZ, kind, quarter;
        public int y;
        public Strip(int x0, int z0, int x1, int z1, int kind, int quarter, int y) {
            this.minX = Math.min(x0, x1); this.maxX = Math.max(x0, x1); this.minZ = Math.min(z0, z1); this.maxZ = Math.max(z0, z1);
            this.kind = kind; this.quarter = quarter; this.y = y;
        }
    }

    public final List<Plan> plans = new ArrayList<>();
    public final List<LotPlan> lots = new ArrayList<>();
    public final List<Strip> strips = new ArrayList<>();
    public final BlockPos anchor;
    public final Direction look, right;
    public final int streetY;
    public final int offset;
    public int warnings; // сколько зданий пришлось урезать по высоте
    public int[] quarterGround = new int[0];

    private final GenSettings s;
    private final boolean sharedDecisions;
    private final StyleMode sharedStyle;
    private final int equalFloorsRoll;
    private int nextIndex;

    private Layout(GenSettings s, BlockPos anchor, Direction look, int groundY) {
        this.s = s;
        this.anchor = anchor;
        this.look = look;
        this.right = look.getClockWise();
        this.streetY = groundY;
        this.offset = s.offset;
        this.sharedDecisions = s.variation == Variation.SAME || s.variation == Variation.SIMILAR;
        BuildingType first = s.type == BuildingType.MIXED ? BuildingType.MIDRISE : s.type;
        this.sharedStyle = decideStyle(s.style, first, new Random(s.seed ^ 0x51ED2701L));
        this.equalFloorsRoll = new Random(s.seed ^ 0x77L).nextInt(1000);
    }

    public BlockPos site(int u, int v, int y) {
        u += offset;
        return new BlockPos(anchor.getX() + right.getStepX() * u + look.getStepX() * v, y,
                anchor.getZ() + right.getStepZ() * u + look.getStepZ() * v);
    }

    /** Полоса в системе координат игрока → мировой прямоугольник. */
    private void siteStrip(int u0, int u1, int v0, int v1, int kind) {
        BlockPos a = site(u0, v0, 0), b = site(u1, v1, 0);
        strips.add(new Strip(a.getX(), a.getZ(), b.getX(), b.getZ(), kind, -1, streetY));
    }

    public static Layout compute(GenSettings src, BlockPos anchor, Direction look, Level level) {
        GenSettings s = src.copy().sanitize();
        Direction lk = look.getAxis().isHorizontal() ? look : Direction.NORTH;
        for (int k = 0; k < s.rotation; k++) lk = lk.getClockWise();
        int groundY = level == null ? anchor.getY() - 1 : groundBelow(level, anchor);
        Layout L = new Layout(s, anchor, lk, groundY);

        switch (s.arrangement) {
            case ROW -> L.arrangeRow();
            case STREET -> L.arrangeStreet();
            case CITY -> L.arrangeCity();
        }

        // уровень земли: по игроку, по рельефу участка, а в городе — по рельефу всего квартала
        boolean terrain = s.ground == GroundMode.TERRAIN && level != null;
        L.quarterGround = new int[s.quarters.size()];
        for (int qi = 0; qi < s.quarters.size(); qi++) {
            Quarter q = s.quarters.get(qi);
            // кварталы города всегда стоят на своём рельефе: они могут быть далеко от игрока
            L.quarterGround[qi] = level != null ? groundLevel(level, q.x0, q.z0, q.x1, q.z1, groundY) : groundY;
        }
        harmonize(s.quarters, L.quarterGround);
        for (int qi = 0; qi < s.quarters.size(); qi++) L.quarterGround[qi] += s.quarters.get(qi).raise;
        int maxY = level == null ? 318 : level.getMaxBuildHeight() - 2;
        for (Plan p : L.plans) {
            if (p.quarter >= 0) p.baseY = L.quarterGround[p.quarter];
            else p.baseY = terrain ? groundLevel(level, p.minX, p.minZ, p.maxX, p.maxZ, groundY) : groundY;
            int orig = p.floors;
            while (p.floors > 1 && p.baseY + p.estimatedHeight() > maxY) p.floors--;
            if (p.floors != orig) L.warnings++;
        }
        for (LotPlan lp : L.lots)
            lp.baseY = lp.quarter >= 0 ? L.quarterGround[lp.quarter]
                    : terrain ? groundLevel(level, lp.minX, lp.minZ, lp.maxX, lp.maxZ, groundY) : groundY;
        for (Strip st : L.strips) if (st.quarter >= 0) st.y = L.quarterGround[st.quarter];
        return L;
    }

    // =====================================================================
    // Выбор параметров здания
    // =====================================================================
    static StyleMode decideStyle(StyleMode mode, BuildingType t, Random r) {
        if (mode != StyleMode.AUTO) return mode;
        float x = r.nextFloat();
        return switch (t) {
            case SKYSCRAPER, OFFICE, COMMERCIAL -> x < 0.8f ? StyleMode.MODERN : StyleMode.CLASSIC;
            case HIGHRISE -> x < 0.7f ? StyleMode.MODERN : StyleMode.CLASSIC;
            case CIVIC -> x < 0.5f ? StyleMode.ANTIQUE : x < 0.8f ? StyleMode.CLASSIC : StyleMode.MODERN;
            default -> x < 0.48f ? StyleMode.MODERN : x < 0.95f ? StyleMode.CLASSIC : StyleMode.ANTIQUE;
        };
    }

    @SafeVarargs
    private static <T> T pick(Random r, T... v) { return v[r.nextInt(v.length)]; }

    /** Создаёт план здания (размеры ещё «желаемые», реальные задаёт place()). */
    private Plan makePlan(BuildingType t, int i, int n, boolean userSize, int maxW, int maxD) {
        int idx = nextIndex++;
        Random dec = new Random(sharedDecisions ? s.seed * 31 + t.ordinal() : s.seed + 7919L * (idx + 1));
        Random var = new Random(s.variation == Variation.SAME ? s.seed : s.seed + 104729L * (idx + 1));
        Plan p = new Plan();
        p.index = idx;
        p.type = t;
        p.seed = s.variation == Variation.SAME ? s.seed : s.seed * 6364136223846793005L + idx * 1442695040888963407L;
        p.style = s.variation == Variation.CONTRAST ? decideStyle(s.style, t, dec)
                : (s.style == StyleMode.AUTO && s.type != BuildingType.MIXED && t != s.type ? decideStyle(s.style, t, dec) : sharedStyle);
        if (s.style == StyleMode.AUTO && t == BuildingType.CIVIC && s.variation != Variation.SAME) p.style = decideStyle(s.style, t, dec);

        p.paletteId = s.palette >= 0 ? s.palette : Palettes.pick(p.style, t, dec);
        p.wallVariant = (s.variation == Variation.SAME ? dec : var).nextInt(16);
        p.wall2Variant = (s.variation == Variation.SAME ? dec : var).nextInt(16);
        resolveLook(p, s, dec);

        boolean useUser = userSize && s.type != BuildingType.MIXED;
        int fmin = useUser ? Math.min(s.floorsMin, s.floorsMax) : t.defMin;
        int fmax = useUser ? Math.max(s.floorsMin, s.floorsMax) : t.defMax;
        double tp = n <= 1 ? 0.5 : i / (double) (n - 1);
        HeightProfile hp = s.heightProfile;
        if (s.variation == Variation.SAME && hp == HeightProfile.RANDOM) hp = HeightProfile.EQUAL;
        p.floors = switch (hp) {
            case RANDOM -> fmin + var.nextInt(fmax - fmin + 1);
            case EQUAL -> fmin + equalFloorsRoll % (fmax - fmin + 1);
            case RISING -> (int) Math.round(fmin + (fmax - fmin) * tp);
            case PEAK -> (int) Math.round(fmin + (fmax - fmin) * (1 - Math.abs(2 * tp - 1)));
        };
        if (p.roof == RoofType.MANSARD && p.floors < 2) p.roof = RoofType.FLAT;

        double vf = switch (s.variation) { case SAME -> 0; case SIMILAR -> 0.1; case DIFFERENT -> 0.25; case CONTRAST -> 0.4; };
        int bw = useUser ? s.width : t.defW;
        int bd = useUser ? s.depth : t.defD;
        p.w = Mth.clamp((int) Math.round(bw * (1 + (var.nextDouble() * 2 - 1) * vf)), t.minW, Math.max(t.minW, maxW));
        p.d = Mth.clamp((int) Math.round(bd * (1 + (var.nextDouble() * 2 - 1) * vf)), t.minD, Math.max(t.minD, maxD));
        if (t == BuildingType.HOUSE && s.yards) { p.yardFront = 5; p.yardSide = 2; }
        if (s.roof == RoofType.AUTO) {
            int m = Math.min(p.w, p.d);
            if (p.roof == RoofType.PEDIMENT && m > 26) p.roof = RoofType.FLAT;
            if ((p.roof == RoofType.HIP || p.roof == RoofType.GABLE) && m > 24) p.roof = RoofType.FLAT;
            if (p.roof == RoofType.CONE && m > 20 && p.shape != Shape.TAPER && p.shape != Shape.TWIST) p.roof = RoofType.DOME;
        }
        p.underConstruction = new Random(p.seed ^ 0xC0457L).nextInt(100) < s.construction;
        return p;
    }

    static void resolveLook(Plan p, GenSettings s, Random r) {
        BuildingType t = p.type;
        StyleMode st = p.style;
        FacadeType f;
        RoofType rf;
        Shape sh;
        switch (st) {
            case CLASSIC -> {
                f = switch (t) {
                    case SKYSCRAPER -> pick(r, FacadeType.PILASTER, FacadeType.PILASTER, FacadeType.ARCADE);
                    case TOWNHOUSE, HOUSE -> FacadeType.CLASSIC;
                    case CIVIC -> pick(r, FacadeType.PILASTER, FacadeType.ARCADE, FacadeType.COLONNADE);
                    default -> pick(r, FacadeType.CLASSIC, FacadeType.CLASSIC, FacadeType.PILASTER, FacadeType.ARCADE);
                };
                rf = switch (t) {
                    case MIDRISE -> r.nextFloat() < 0.6f ? RoofType.MANSARD : RoofType.FLAT;
                    case TOWNHOUSE -> RoofType.GABLE;
                    case HOUSE -> r.nextBoolean() ? RoofType.GABLE : RoofType.HIP;
                    case HIGHRISE, OFFICE -> r.nextFloat() < 0.3f ? RoofType.MANSARD : RoofType.FLAT;
                    case CIVIC -> r.nextBoolean() ? RoofType.DOME : RoofType.MANSARD;
                    default -> RoofType.FLAT;
                };
                sh = switch (t) {
                    case SKYSCRAPER -> Shape.STEPPED;
                    case MIDRISE -> pick(r, Shape.RECT, Shape.RECT, Shape.COURTYARD, Shape.U, Shape.L);
                    case OFFICE, HIGHRISE -> pick(r, Shape.RECT, Shape.RECT, Shape.L, Shape.U);
                    case CIVIC -> pick(r, Shape.RECT, Shape.CROSS);
                    default -> Shape.RECT;
                };
            }
            case ANTIQUE -> {
                f = switch (t) {
                    case CIVIC -> FacadeType.COLONNADE;
                    case HOUSE, TOWNHOUSE -> pick(r, FacadeType.ARCADE, FacadeType.CLASSIC);
                    default -> pick(r, FacadeType.ARCADE, FacadeType.PILASTER, FacadeType.COLONNADE);
                };
                rf = switch (t) {
                    case CIVIC -> r.nextFloat() < 0.6f ? RoofType.PEDIMENT : RoofType.DOME;
                    case HOUSE, TOWNHOUSE -> r.nextBoolean() ? RoofType.HIP : RoofType.PEDIMENT;
                    case MIDRISE, COMMERCIAL -> r.nextFloat() < 0.3f ? RoofType.PEDIMENT : RoofType.FLAT;
                    default -> RoofType.FLAT;
                };
                sh = switch (t) {
                    case SKYSCRAPER -> Shape.STEPPED;
                    case CIVIC -> pick(r, Shape.RECT, Shape.RECT, Shape.CROSS);
                    case MIDRISE -> pick(r, Shape.RECT, Shape.COURTYARD);
                    default -> Shape.RECT;
                };
            }
            case FANTASY -> {
                f = switch (t) {
                    case HOUSE, TOWNHOUSE -> pick(r, FacadeType.NEON, FacadeType.CURTAIN);
                    default -> pick(r, FacadeType.NEON, FacadeType.NEON, FacadeType.DIAGRID, FacadeType.CURTAIN);
                };
                rf = switch (t) {
                    case SKYSCRAPER, OFFICE, HIGHRISE -> pick(r, RoofType.FLAT, RoofType.FLAT, RoofType.DOME, RoofType.CONE);
                    case CIVIC -> RoofType.DOME;
                    case HOUSE, TOWNHOUSE -> pick(r, RoofType.DOME, RoofType.CONE, RoofType.FLAT);
                    default -> pick(r, RoofType.FLAT, RoofType.DOME);
                };
                sh = switch (t) {
                    case SKYSCRAPER -> pick(r, Shape.TWIST, Shape.CYLINDER, Shape.TAPER);
                    case OFFICE, HIGHRISE -> pick(r, Shape.CYLINDER, Shape.TWIST, Shape.RECT);
                    case MIDRISE, CIVIC -> pick(r, Shape.CYLINDER, Shape.RECT, Shape.CROSS);
                    case HOUSE -> pick(r, Shape.CYLINDER, Shape.RECT);
                    default -> Shape.RECT;
                };
            }
            default -> {
                f = switch (t) {
                    case SKYSCRAPER -> pick(r, FacadeType.CURTAIN, FacadeType.GRID, FacadeType.CURTAIN, FacadeType.RIBBON, FacadeType.DIAGRID);
                    case OFFICE -> pick(r, FacadeType.RIBBON, FacadeType.GRID, FacadeType.CURTAIN, FacadeType.DIAGRID);
                    case HIGHRISE -> pick(r, FacadeType.BALCONY, FacadeType.PUNCHED, FacadeType.GRID);
                    case MIDRISE -> pick(r, FacadeType.PUNCHED, FacadeType.BALCONY, FacadeType.RIBBON);
                    case TOWNHOUSE, HOUSE -> FacadeType.PUNCHED;
                    case CIVIC -> pick(r, FacadeType.CURTAIN, FacadeType.DIAGRID, FacadeType.GRID);
                    case COMMERCIAL -> pick(r, FacadeType.CURTAIN, FacadeType.RIBBON);
                    default -> FacadeType.PUNCHED;
                };
                rf = switch (t) {
                    case TOWNHOUSE -> r.nextFloat() < 0.3f ? RoofType.GABLE : RoofType.FLAT;
                    case HOUSE -> r.nextFloat() < 0.4f ? RoofType.GABLE : RoofType.FLAT;
                    default -> RoofType.FLAT;
                };
                sh = switch (t) {
                    case SKYSCRAPER -> pick(r, Shape.RECT, Shape.RECT, Shape.STEPPED, Shape.TAPER, Shape.CYLINDER, Shape.TWIST);
                    case OFFICE -> pick(r, Shape.RECT, Shape.RECT, Shape.L, Shape.CYLINDER);
                    case HIGHRISE -> pick(r, Shape.RECT, Shape.RECT, Shape.L, Shape.U);
                    case MIDRISE -> pick(r, Shape.RECT, Shape.RECT, Shape.COURTYARD, Shape.U, Shape.L);
                    case COMMERCIAL -> pick(r, Shape.RECT, Shape.RECT, Shape.L);
                    case CIVIC -> pick(r, Shape.RECT, Shape.CROSS, Shape.CYLINDER);
                    default -> Shape.RECT;
                };
            }
        }
        GroundFloor g = switch (t) {
            case SKYSCRAPER, OFFICE, CIVIC -> GroundFloor.LOBBY;
            case HIGHRISE -> r.nextBoolean() ? GroundFloor.LOBBY : GroundFloor.SHOPS;
            case MIDRISE -> r.nextFloat() < 0.7f ? GroundFloor.SHOPS : GroundFloor.RESIDENTIAL;
            case COMMERCIAL -> GroundFloor.SHOPS;
            default -> GroundFloor.RESIDENTIAL;
        };
        int bal = switch (t) {
            case HIGHRISE, MIDRISE -> r.nextFloat() < 0.6f ? 1 + r.nextInt(2) : 0;
            case TOWNHOUSE -> st.traditional() ? 0 : r.nextInt(2);
            default -> 0;
        };
        p.facade = s.facade != FacadeType.AUTO ? s.facade : f;
        p.roof = s.roof != RoofType.AUTO ? s.roof : rf;
        p.shape = s.shape != Shape.AUTO ? s.shape : sh;
        p.ground = s.groundFloor != GroundFloor.AUTO ? s.groundFloor : g;
        p.balconies = switch (s.balconies) { case NONE -> 0; case SOME -> 1; case MANY -> 2; default -> bal; };
        if (p.facade == FacadeType.BALCONY && p.balconies == 0 && s.balconies == Balconies.AUTO) p.balconies = 1;
        p.windowWidth = switch (s.windows) { case NARROW -> 1; case NORMAL -> 2; case WIDE -> 3; default -> 0; };
        boolean escapeType = t == BuildingType.MIDRISE || t == BuildingType.HIGHRISE || t == BuildingType.OFFICE;
        p.fireEscape = switch (s.fireEscapes) {
            case ON -> escapeType || t == BuildingType.COMMERCIAL;
            case OFF -> false;
            default -> escapeType && st == StyleMode.CLASSIC && r.nextFloat() < 0.5f;
        };

        boolean tallFloors = st.traditional() && (t == BuildingType.MIDRISE || t == BuildingType.OFFICE || t == BuildingType.HIGHRISE);
        p.floorH = t == BuildingType.CIVIC ? 6 : (tallFloors || t == BuildingType.COMMERCIAL) ? 5 : 4;
        p.groundH = switch (t) {
            case SKYSCRAPER, OFFICE, COMMERCIAL -> 6;
            case CIVIC -> 7;
            case HIGHRISE, MIDRISE -> 5;
            default -> 4;
        };
        if (p.ground == GroundFloor.SHOPS || p.ground == GroundFloor.LOBBY) p.groundH = Math.max(p.groundH, 5);
        if (s.floorHeight > 0) {
            p.floorH = s.floorHeight;
            p.groundH = Math.max(s.floorHeight, (p.ground == GroundFloor.RESIDENTIAL) ? s.floorHeight : 5);
        }
    }

    // =====================================================================
    // Схемы расстановки: ряд и улица
    // =====================================================================
    private int frontage(StreetMode m) { return switch (m) { case NONE -> 0; case SIDEWALK -> 3; case ROAD -> 13; }; }

    private void arrangeRow() {
        int n = s.count;
        BuildingType[] types = typesFor(n);
        List<Plan> row = new ArrayList<>();
        for (int i = 0; i < n; i++) row.add(makePlan(types[i], i, n, true, 72, 72));
        boolean party = s.spacing < 2 && n > 1;
        int total = 0;
        for (Plan p : row) total += p.w + 2 * p.yardSide;
        total += s.spacing * (n - 1);
        int u = -total / 2, front = s.distance + frontage(s.street);
        for (Plan p : row) {
            p.party = party;
            int lotW = p.w + 2 * p.yardSide;
            setLot(p, u + p.yardSide, u + p.yardSide + p.w - 1, front + p.yardFront, front + p.yardFront + p.d - 1, look.getOpposite());
            plans.add(p);
            u += lotW + s.spacing;
        }
        addFrontStrips(-total / 2 - 2, -total / 2 + total + 1, front, s.street);
    }

    private void arrangeStreet() {
        int n = s.count;
        BuildingType[] types = typesFor(n);
        int half = switch (s.street) { case NONE -> 2; case SIDEWALK -> 3; case ROAD -> 6; };
        int vl = s.distance, vr = s.distance, vEnd = s.distance;
        boolean party = s.spacing < 2 && n > 1;
        for (int i = 0; i < n; i++) {
            Plan p = makePlan(types[i], i, n, true, 72, 72);
            p.party = party;
            int lotW = p.w + 2 * p.yardSide;
            if (i % 2 == 0) {
                setLot(p, -half - p.yardFront - p.d, -half - p.yardFront - 1, vl + p.yardSide, vl + p.yardSide + p.w - 1, right);
                vl += lotW + s.spacing;
            } else {
                setLot(p, half + p.yardFront + 1, half + p.yardFront + p.d, vr + p.yardSide, vr + p.yardSide + p.w - 1, right.getOpposite());
                vr += lotW + s.spacing;
            }
            plans.add(p);
            vEnd = Math.max(vEnd, Math.max(vl, vr));
        }
        int v0 = s.distance - 2, v1 = vEnd + 2;
        if (s.street == StreetMode.SIDEWALK) siteStrip(-3, 3, v0, v1, SIDEWALK);
        else if (s.street == StreetMode.ROAD) {
            siteStrip(-3, 3, v0, v1, ROAD);
            siteStrip(-6, -4, v0, v1, SIDEWALK);
            siteStrip(4, 6, v0, v1, SIDEWALK);
        }
    }

    private void addFrontStrips(int uMin, int uMax, int front, StreetMode mode) {
        if (mode == StreetMode.SIDEWALK) {
            siteStrip(uMin, uMax, front - 3, front - 1, SIDEWALK);
        } else if (mode == StreetMode.ROAD) {
            siteStrip(uMin, uMax, front - 3, front - 1, SIDEWALK);
            siteStrip(uMin, uMax, front - 10, front - 4, ROAD);
            siteStrip(uMin, uMax, front - 13, front - 11, SIDEWALK);
        }
    }

    // =====================================================================
    // Город: свободные кварталы
    // =====================================================================
    /** Параметры типа квартала. */
    record KindSpec(BuildingType[] pool, int lotW, int lotD, int spacing, QFacing facing, CellType court, int parkChance) {}

    static KindSpec spec(QuarterKind k, GenSettings s) {
        BuildingType S = BuildingType.SKYSCRAPER, O = BuildingType.OFFICE, H = BuildingType.HIGHRISE, M = BuildingType.MIDRISE,
                T = BuildingType.TOWNHOUSE, D = BuildingType.HOUSE, C = BuildingType.COMMERCIAL, V = BuildingType.CIVIC;
        return switch (k) {
            case DOWNTOWN -> new KindSpec(new BuildingType[]{S, S, O, O, O, H}, 28, 28, 5, QFacing.AUTO, CellType.PLAZA, 0);
            case RESIDENTIAL -> new KindSpec(new BuildingType[]{H, H, M, M, M}, 26, 16, 6, QFacing.AUTO, CellType.PARK, 7);
            case HISTORIC -> new KindSpec(new BuildingType[]{M, M, M, M, M, V}, 18, 15, 0, QFacing.PERIMETER, CellType.PARK, 0);
            case TOWNHOUSES -> new KindSpec(new BuildingType[]{T}, 7, 12, 0, QFacing.AUTO, CellType.PARK, 0);
            case SUBURB -> new KindSpec(new BuildingType[]{D}, 15, 15, 2, QFacing.AUTO, CellType.PARK, 5);
            case COMMERCIAL -> new KindSpec(new BuildingType[]{C, C, M}, 40, 26, 6, QFacing.AUTO, CellType.PARKING, 0);
            case CULTURE -> new KindSpec(new BuildingType[]{V, V, M}, 30, 22, 8, QFacing.AUTO, CellType.PLAZA, 0);
            case CONSTRUCTION -> new KindSpec(new BuildingType[]{H, O, M}, 26, 18, 8, QFacing.AUTO, CellType.EMPTY, 0);
            case CUSTOM -> new KindSpec(s.type == BuildingType.MIXED ? new BuildingType[]{M, M, H, O, C, V} : new BuildingType[]{s.type},
                    s.width, s.depth, s.spacing, QFacing.AUTO, CellType.PARK, 0);
            default -> new KindSpec(new BuildingType[]{M, M, M, H, O, C}, 22, 18, 4, QFacing.AUTO, CellType.PARK, 6);
        };
    }

    private void arrangeCity() {
        for (int qi = 0; qi < s.quarters.size(); qi++) {
            if (s.buildOnly >= 0 && s.buildOnly != qi) continue;
            arrangeQuarter(s.quarters.get(qi), qi);
        }
    }

    private void arrangeQuarter(Quarter q, int qi) {
        Random r = new Random(s.seed * 31 + q.seed * 1000003L + qi * 7919L);
        // кольцо улиц вокруг квартала
        if (q.street != StreetMode.NONE) {
            int a = WALK;
            strips.add(new Strip(q.x0 - a, q.z0 - a, q.x1 + a, q.z0 - 1, SIDEWALK, qi, streetY));
            strips.add(new Strip(q.x0 - a, q.z1 + 1, q.x1 + a, q.z1 + a, SIDEWALK, qi, streetY));
            strips.add(new Strip(q.x0 - a, q.z0, q.x0 - 1, q.z1, SIDEWALK, qi, streetY));
            strips.add(new Strip(q.x1 + 1, q.z0, q.x1 + a, q.z1, SIDEWALK, qi, streetY));
            if (q.street == StreetMode.ROAD) {
                int b = WALK + ROAD_W;
                strips.add(new Strip(q.x0 - b, q.z0 - b, q.x1 + b, q.z0 - a - 1, ROAD, qi, streetY));
                strips.add(new Strip(q.x0 - b, q.z1 + a + 1, q.x1 + b, q.z1 + b, ROAD, qi, streetY));
                strips.add(new Strip(q.x0 - b, q.z0 - a, q.x0 - a - 1, q.z1 + a, ROAD, qi, streetY));
                strips.add(new Strip(q.x1 + a + 1, q.z0 - a, q.x1 + b, q.z1 + a, ROAD, qi, streetY));
            }
        }
        if (q.kind.open()) {
            CellType ct = q.kind == QuarterKind.PARK ? CellType.PARK : q.kind == QuarterKind.PLAZA ? CellType.PLAZA : CellType.PARKING;
            addLot(ct, q.x0, q.z0, q.x1, q.z1, Direction.NORTH, r.nextLong(), qi);
            return;
        }
        KindSpec sp = spec(q.kind, s);
        StyleMode style = quarterStyle(q, r);
        int rowD = q.lotDepth > 0 ? q.lotDepth : sp.lotD;
        int spacing = q.spacing >= 0 ? q.spacing : sp.spacing;
        QFacing f = q.facing != QFacing.AUTO ? q.facing : sp.facing;
        if (f == QFacing.AUTO) f = q.w() >= q.d() ? QFacing.NORTH_SOUTH : QFacing.WEST_EAST;
        int across = (f == QFacing.WEST_EAST || f == QFacing.WEST || f == QFacing.EAST) ? q.w() : q.d();
        if ((f == QFacing.NORTH_SOUTH || f == QFacing.WEST_EAST || f == QFacing.PERIMETER) && across < 2 * 9)
            f = f == QFacing.WEST_EAST ? QFacing.WEST : QFacing.NORTH;
        boolean two = f == QFacing.NORTH_SOUTH || f == QFacing.WEST_EAST || f == QFacing.PERIMETER;
        rowD = Math.max(7, Math.min(rowD, two ? across / 2 : across));
        CellType court = switch (q.courtyard) {
            case NONE -> CellType.EMPTY; case GARDEN -> CellType.PARK; case PARKING -> CellType.PARKING; default -> sp.court;
        };
        int gx0 = q.x0, gz0 = q.z0, gx1 = q.x1, gz1 = q.z1; // остаток под двор
        switch (f) {
            case NORTH_SOUTH, PERIMETER -> {
                int dn = fillRow(q, qi, sp, style, r, Direction.NORTH, q.x0, q.x1, q.z0, rowD, spacing);
                int ds = fillRow(q, qi, sp, style, r, Direction.SOUTH, q.x0, q.x1, q.z1, rowD, spacing);
                gz0 = q.z0 + dn; gz1 = q.z1 - ds;
                if (f == QFacing.PERIMETER && q.d() - 2 * rowD >= 9) {
                    int sideGap = spacing > 0 ? spacing : 0;
                    int dw = fillRow(q, qi, sp, style, r, Direction.WEST, q.z0 + rowD + sideGap, q.z1 - rowD - sideGap, q.x0, rowD, spacing);
                    int de = fillRow(q, qi, sp, style, r, Direction.EAST, q.z0 + rowD + sideGap, q.z1 - rowD - sideGap, q.x1, rowD, spacing);
                    gx0 = q.x0 + dw; gx1 = q.x1 - de;
                    gz0 = q.z0 + rowD; gz1 = q.z1 - rowD;
                }
            }
            case WEST_EAST -> {
                int dw = fillRow(q, qi, sp, style, r, Direction.WEST, q.z0, q.z1, q.x0, rowD, spacing);
                int de = fillRow(q, qi, sp, style, r, Direction.EAST, q.z0, q.z1, q.x1, rowD, spacing);
                gx0 = q.x0 + dw; gx1 = q.x1 - de;
            }
            case NORTH -> gz0 = q.z0 + fillRow(q, qi, sp, style, r, Direction.NORTH, q.x0, q.x1, q.z0, rowD, spacing);
            case SOUTH -> gz1 = q.z1 - fillRow(q, qi, sp, style, r, Direction.SOUTH, q.x0, q.x1, q.z1, rowD, spacing);
            case WEST -> gx0 = q.x0 + fillRow(q, qi, sp, style, r, Direction.WEST, q.z0, q.z1, q.x0, rowD, spacing);
            default -> gx1 = q.x1 - fillRow(q, qi, sp, style, r, Direction.EAST, q.z0, q.z1, q.x1, rowD, spacing);
        }
        // двор: отступ 3 блока от задних фасадов (там балконы и лестницы)
        int inset = 3;
        if (court != CellType.EMPTY && gx1 - gx0 - 2 * inset >= 8 && gz1 - gz0 - 2 * inset >= 8)
            addLot(court, gx0 + inset, gz0 + inset, gx1 - inset, gz1 - inset, Direction.NORTH, r.nextLong(), qi);
    }

    private StyleMode quarterStyle(Quarter q, Random r) {
        if (q.style != StyleMode.AUTO) return q.style;
        if (s.style != StyleMode.AUTO) return s.style;
        float x = r.nextFloat();
        return switch (q.kind) {
            case HISTORIC -> StyleMode.CLASSIC;
            case CULTURE -> x < 0.6f ? StyleMode.ANTIQUE : StyleMode.CLASSIC;
            case TOWNHOUSES -> x < 0.75f ? StyleMode.CLASSIC : StyleMode.MODERN;
            case DOWNTOWN, COMMERCIAL, CONSTRUCTION -> x < 0.85f ? StyleMode.MODERN : StyleMode.CLASSIC;
            default -> x < 0.55f ? StyleMode.MODERN : StyleMode.CLASSIC;
        };
    }

    /**
     * Ряд участков вдоль улицы. face — куда смотрят фасады; a0..a1 — диапазон вдоль улицы;
     * front — координата линии фасадов (z для N/S, x для W/E); участки уходят вглубь на rowD.
     */
    /** @return фактическая глубина ряда (до самого глубокого заднего фасада) — остальное отдаётся двору. */
    private int fillRow(Quarter q, int qi, KindSpec sp, StyleMode style, Random r, Direction face,
                        int a0, int a1, int front, int rowD, int spacing) {
        int cursor = a0, used = 0;
        while (cursor <= a1) {
            int remaining = a1 - cursor + 1;
            if (remaining < 6) break;
            if (sp.parkChance > 0 && remaining >= 14 && r.nextInt(100) < sp.parkChance) {
                int w = Math.min(remaining, 12 + r.nextInt(8));
                addRowLot(CellType.PARK, face, cursor, cursor + w - 1, front, Math.min(rowD, 14), r.nextLong(), qi);
                used = Math.max(used, Math.min(rowD, 14));
                cursor += w + Math.max(spacing, 1);
                continue;
            }
            BuildingType t = sp.pool[r.nextInt(sp.pool.length)];
            int desired;
            if (t == BuildingType.TOWNHOUSE) desired = (q.lotWidth > 0 ? q.lotWidth : 6 + r.nextInt(3));
            else {
                int base = q.lotWidth > 0 ? q.lotWidth : (q.kind == QuarterKind.CUSTOM || t == sp.pool[0] && sp.lotW > 0 ? sp.lotW : t.defW);
                desired = (int) Math.round(base * (0.85 + r.nextDouble() * 0.3));
            }
            int minW = Math.min(t.minW, remaining);
            int w = Mth.clamp(desired, minW, remaining);
            if (remaining - w - spacing < Math.max(6, t.minW)) w = remaining; // хвост — в этот же участок
            if (w < t.minW) { t = BuildingType.TOWNHOUSE; if (w < t.minW) break; }

            int fmin, fmax;
            if (q.floorsMin > 0 || q.floorsMax > 0) {
                fmin = Math.max(1, Math.min(q.floorsMin > 0 ? q.floorsMin : 1, q.floorsMax > 0 ? q.floorsMax : 80));
                fmax = Math.max(fmin, q.floorsMax > 0 ? q.floorsMax : fmin + 3);
            } else if (q.kind == QuarterKind.CUSTOM && s.type != BuildingType.MIXED) {
                fmin = Math.min(s.floorsMin, s.floorsMax); fmax = Math.max(s.floorsMin, s.floorsMax);
            } else { fmin = t.defMin; fmax = t.defMax; }
            double frac = switch (q.heights) {
                case EQUAL -> new Random(q.seed * 7919L + qi).nextDouble();
                case PEAK -> {
                    double cu = cursor + w / 2.0;
                    double ac = face == Direction.NORTH || face == Direction.WEST ? front + rowD / 2.0 : front - rowD / 2.0;
                    double cx = face.getAxis() == Direction.Axis.Z ? cu : ac, cz = face.getAxis() == Direction.Axis.Z ? ac : cu;
                    double dx = (cx - (q.x0 + q.x1) / 2.0) / (q.w() / 2.0), dz = (cz - (q.z0 + q.z1) / 2.0) / (q.d() / 2.0);
                    yield Mth.clamp(1.0 - Math.sqrt(dx * dx + dz * dz) / 1.25, 0, 1) * 0.85 + r.nextDouble() * 0.15;
                }
                case RISING -> {
                    double cx = face.getAxis() == Direction.Axis.Z ? cursor + w / 2.0 : front;
                    yield Mth.clamp((cx - q.x0) / (double) Math.max(1, q.w()), 0, 1) * 0.85 + r.nextDouble() * 0.15;
                }
                default -> -1;
            };
            Plan p = quarterPlan(t, style, fmin, fmax, r, qi);
            if (frac >= 0) p.floors = Mth.clamp(fmin + (int) Math.round(frac * (fmax - fmin)), fmin, fmax);
            if (q.ground != GroundFloor.AUTO && !t.small()) {
                p.ground = q.ground;
                if (p.ground == GroundFloor.SHOPS || p.ground == GroundFloor.LOBBY) p.groundH = Math.max(p.groundH, 5);
            }
            if (q.construction >= 0) p.underConstruction = new Random(p.seed ^ 0xC0457L).nextInt(100) < q.construction;
            if (q.kind == QuarterKind.CONSTRUCTION) p.underConstruction = true;
            int yf = p.yardFront, ys = p.yardSide;
            if (w - 2 * ys < t.minW) ys = 0;
            if (rowD - yf < t.minD) yf = 0;
            p.yardFront = yf; p.yardSide = ys;
            int bw = w - 2 * ys;
            int bd = Math.max(t.minD, Math.min(rowD - yf, (int) Math.round(t.defD * (0.9 + r.nextDouble() * 0.25))));
            if (q.lotDepth > 0 || q.kind == QuarterKind.CUSTOM) bd = Math.max(t.minD, rowD - yf);
            bd = Math.min(bd, rowD - yf);
            p.party = spacing < 2;
            int s0 = cursor + ys, s1 = cursor + ys + bw - 1;
            switch (face) {
                case NORTH -> p.place(s0, front + yf, s1, front + yf + bd - 1, face);
                case SOUTH -> p.place(s0, front - yf - bd + 1, s1, front - yf, face);
                case WEST -> p.place(front + yf, s0, front + yf + bd - 1, s1, face);
                default -> p.place(front - yf - bd + 1, s0, front - yf, s1, face);
            }
            adjustRoof(p);
            plans.add(p);
            used = Math.max(used, yf + bd);
            cursor += w + spacing;
        }
        return used;
    }

    private void addRowLot(CellType ct, Direction face, int a0, int a1, int front, int depth, long seed, int qi) {
        switch (face) {
            case NORTH -> addLot(ct, a0, front, a1, front + depth - 1, face, seed, qi);
            case SOUTH -> addLot(ct, a0, front - depth + 1, a1, front, face, seed, qi);
            case WEST -> addLot(ct, front, a0, front + depth - 1, a1, face, seed, qi);
            default -> addLot(ct, front - depth + 1, a0, front, a1, face, seed, qi);
        }
    }

    private void addLot(CellType ct, int x0, int z0, int x1, int z1, Direction face, long seed, int qi) {
        LotPlan lp = new LotPlan(ct, Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1), face, seed);
        lp.quarter = qi;
        lots.add(lp);
    }

    /** План здания для квартала: стиль квартала, палитра и детали — свои у каждого дома. */
    private Plan quarterPlan(BuildingType t, StyleMode style, int fmin, int fmax, Random r, int qi) {
        Plan p = new Plan();
        p.index = nextIndex++;
        p.type = t;
        p.quarter = qi;
        p.seed = r.nextLong();
        p.style = style;
        Random dec = new Random(p.seed);
        p.paletteId = s.palette >= 0 ? s.palette : Palettes.pick(style, t, dec);
        p.wallVariant = dec.nextInt(16);
        p.wall2Variant = dec.nextInt(16);
        resolveLook(p, s, dec);
        p.floors = fmin + dec.nextInt(Math.max(1, fmax - fmin + 1));
        if (p.roof == RoofType.MANSARD && p.floors < 2) p.roof = RoofType.FLAT;
        p.w = t.defW; p.d = t.defD;
        if (t == BuildingType.HOUSE && s.yards) { p.yardFront = 5; p.yardSide = 2; }
        p.underConstruction = dec.nextInt(100) < s.construction;
        return p;
    }

    private void adjustRoof(Plan p) {
        if (s.roof != RoofType.AUTO) return;
        int m = Math.min(p.w, p.d);
        if (p.roof == RoofType.PEDIMENT && m > 26) p.roof = RoofType.FLAT;
        if ((p.roof == RoofType.HIP || p.roof == RoofType.GABLE) && m > 24) p.roof = RoofType.FLAT;
        if (p.roof == RoofType.CONE && m > 20 && p.shape != Shape.TAPER && p.shape != Shape.TWIST) p.roof = RoofType.DOME;
    }

    private static BuildingType mixedPool(Random r) {
        BuildingType[] pool = {BuildingType.MIDRISE, BuildingType.MIDRISE, BuildingType.MIDRISE, BuildingType.HIGHRISE,
                BuildingType.HIGHRISE, BuildingType.OFFICE, BuildingType.OFFICE, BuildingType.SKYSCRAPER,
                BuildingType.COMMERCIAL, BuildingType.CIVIC};
        return pool[r.nextInt(pool.length)];
    }

    private BuildingType[] typesFor(int n) {
        BuildingType[] types = new BuildingType[n];
        if (s.type != BuildingType.MIXED) { Arrays.fill(types, s.type); return types; }
        Random r = new Random(s.seed);
        for (int i = 0; i < n; i++) types[i] = mixedPool(r);
        if (s.heightProfile == HeightProfile.RISING) Arrays.sort(types, Comparator.comparingInt(BuildingType::rank));
        else if (s.heightProfile == HeightProfile.PEAK) {
            BuildingType[] sorted = types.clone();
            Arrays.sort(sorted, Comparator.comparingInt(BuildingType::rank).reversed());
            int mid = (n - 1) / 2;
            for (int k = 0; k < n; k++) {
                int off = (k + 1) / 2 * (k % 2 == 1 ? 1 : -1);
                types[Mth.clamp(mid + off, 0, n - 1)] = sorted[k];
            }
        }
        return types;
    }

    private void setLot(Plan p, int u0, int u1, int v0, int v1, Direction facing) {
        BlockPos a = site(u0, v0, 0), b = site(u1, v1, 0);
        p.place(Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()), facing);
    }

    // =====================================================================
    // Рельеф
    // =====================================================================
    public static boolean isGround(BlockState st) {
        if (st.isAir()) return false;
        if (st.is(BlockTags.LEAVES) || st.is(BlockTags.LOGS)) return false;
        if (!st.getFluidState().isEmpty()) return true;
        return !st.canBeReplaced();
    }

    /** Уровень земли под точкой: ищем вниз от ног, пропуская листву, траву, снег и воздух (прыжок, полёт). */
    public static int groundBelow(Level level, BlockPos feet) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int min = level.getMinBuildHeight();
        for (int y = feet.getY(); y > min; y--) {
            if (isGround(level.getBlockState(mp.set(feet.getX(), y, feet.getZ())))) return y;
        }
        return feet.getY() - 1;
    }

    public static int surfaceY(Level level, int x, int z) {
        // на сервере чанк догружается: у незагруженного чанка Level.getHeight вернул бы дно мира
        int y = level.isClientSide ? level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
                : level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        while (y > min) {
            if (isGround(level.getBlockState(mp.set(x, y, z)))) return y;
            y--;
        }
        return y;
    }

    /**
     * Соседние кварталы (делящие улицу) с небольшим перепадом высот ставятся на общий уровень — иначе на стыке
     * общей дороги появляются ступеньки. Кварталы на заметно другой высоте (холм, обрыв) остаются на своём уровне.
     */
    static void harmonize(List<Quarter> qs, int[] ground) {
        int n = qs.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                Quarter a = qs.get(i), b = qs.get(j);
                int gx = Math.max(b.x0 - a.x1 - 1, a.x0 - b.x1 - 1), gz = Math.max(b.z0 - a.z1 - 1, a.z0 - b.z1 - 1);
                if (Math.max(gx, gz) <= SHARED_GAP + 8 && Math.abs(ground[i] - ground[j]) <= 8) parent[find(parent, i)] = find(parent, j);
            }
        Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) groups.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        for (List<Integer> g : groups.values()) {
            if (g.size() < 2) continue;
            List<Integer> ys = new ArrayList<>();
            for (int i : g) ys.add(ground[i]);
            Collections.sort(ys);
            int m = ys.get(ys.size() / 2);
            for (int i : g) if (Math.abs(ground[i] - m) <= 6) ground[i] = m;
        }
    }

    private static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }

    /** Медиана высоты земли по сетке 5×5. На клиенте незагруженные чанки пропускаются (fallback — уровень игрока). */
    public static int groundLevel(Level level, int minX, int minZ, int maxX, int maxZ, int fallback) {
        List<Integer> ys = new ArrayList<>();
        for (int i = 0; i <= 4; i++)
            for (int j = 0; j <= 4; j++) {
                int x = minX + (maxX - minX) * i / 4, z = minZ + (maxZ - minZ) * j / 4;
                if (level.isClientSide && !level.hasChunk(x >> 4, z >> 4)) continue;
                ys.add(surfaceY(level, x, z));
            }
        if (ys.isEmpty()) return fallback;
        Collections.sort(ys);
        return ys.get(ys.size() / 2);
    }
}
