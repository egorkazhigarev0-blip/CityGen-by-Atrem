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
 * Раскладка участков. Полностью детерминирована (сид + позиция + направление),
 * поэтому клиент рисует превью ровно там, где сервер потом построит.
 * Система координат участка: u — вправо от игрока, v — вперёд по взгляду.
 */
public class Layout {
    public static final int SIDEWALK = 0, ROAD = 1;

    public static class Strip {
        public final int u0, u1, v0, v1, kind, lampCross;
        public final boolean alongU;
        Strip(int u0, int u1, int v0, int v1, int kind, boolean alongU, int lampCross) {
            this.u0 = Math.min(u0, u1); this.u1 = Math.max(u0, u1); this.v0 = Math.min(v0, v1); this.v1 = Math.max(v0, v1);
            this.kind = kind; this.alongU = alongU; this.lampCross = lampCross;
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

    public static Layout compute(GenSettings src, BlockPos anchor, Direction look, Level level) {
        GenSettings s = src.copy().sanitize();
        Direction lk = look.getAxis().isHorizontal() ? look : Direction.NORTH;
        for (int k = 0; k < s.rotation; k++) lk = lk.getClockWise();
        int groundY = level == null ? anchor.getY() - 1 : groundBelow(level, anchor);
        Layout L = new Layout(s, anchor, lk, groundY);

        switch (s.arrangement) {
            case ROW -> L.arrangeRow();
            case STREET -> L.arrangeStreet();
            case PLANNER -> L.arrangePlanner();
        }

        int maxY = level == null ? 318 : level.getMaxBuildHeight() - 2;
        for (Plan p : L.plans) {
            p.baseY = s.ground == GroundMode.TERRAIN && level != null ? groundLevel(level, p.minX, p.minZ, p.maxX, p.maxZ) : groundY;
            int orig = p.floors;
            while (p.floors > 1 && p.baseY + p.estimatedHeight() > maxY) p.floors--;
            if (p.floors != orig) L.warnings++;
        }
        for (LotPlan lp : L.lots)
            lp.baseY = s.ground == GroundMode.TERRAIN && level != null ? groundLevel(level, lp.minX, lp.minZ, lp.maxX, lp.maxZ) : groundY;
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
    // Схемы расстановки
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
        if (s.street == StreetMode.SIDEWALK) strips.add(new Strip(-3, 3, v0, v1, SIDEWALK, false, -3));
        else if (s.street == StreetMode.ROAD) {
            strips.add(new Strip(-3, 3, v0, v1, ROAD, false, Integer.MIN_VALUE));
            strips.add(new Strip(-6, -4, v0, v1, SIDEWALK, false, -4));
            strips.add(new Strip(4, 6, v0, v1, SIDEWALK, false, 4));
        }
    }

    /** Таблица кварталов: строка 0 — дальняя (верх таблицы), последняя — ближняя к игроку. */
    private void arrangePlanner() {
        int R = s.rows, C = s.cols, lotW = s.width, lotD = s.depth;
        int rowFront = frontage(s.street);
        int colGap = switch (s.crossStreets) { case NONE -> s.spacing; case SIDEWALK -> 5; case ROAD -> 15; };
        int total = C * lotW + (C - 1) * colGap;
        int u0 = -total / 2;
        int n = s.plannedBuildings(), i = 0;
        Random mixed = new Random(s.seed ^ 0xC0FFEEL);
        int front = s.distance + rowFront;
        int nearFront = front, farBack = front;
        for (int r = R - 1; r >= 0; r--) {
            for (int c = 0; c < C; c++) {
                CellType ct = s.cell(r, c);
                int lu0 = u0 + c * (lotW + colGap);
                if (ct == CellType.EMPTY) continue;
                if (ct == CellType.PARK || ct == CellType.PLAZA || ct == CellType.PARKING) {
                    BlockPos a = site(lu0, front, 0), b = site(lu0 + lotW - 1, front + lotD - 1, 0);
                    lots.add(new LotPlan(ct, Math.min(a.getX(), b.getX()), Math.min(a.getZ(), b.getZ()),
                            Math.max(a.getX(), b.getX()), Math.max(a.getZ(), b.getZ()), look.getOpposite(), s.seed + r * 131L + c));
                    continue;
                }
                BuildingType t = ct == CellType.AUTO
                        ? (s.type == BuildingType.MIXED ? mixedPool(mixed) : s.type)
                        : ct.building();
                boolean userSize = ct == CellType.AUTO;
                double tp = C <= 1 ? 0.5 : c / (double) (C - 1);
                int ord = s.heightProfile == HeightProfile.PEAK
                        ? (int) Math.round((1 - Math.max(Math.abs(2 * tp - 1), R <= 1 ? 0 : Math.abs(2 * r / (double) (R - 1) - 1))) * (n - 1))
                        : (int) Math.round(tp * (n - 1));
                if (t == BuildingType.TOWNHOUSE) {
                    int k = Math.max(1, lotW / 7), uw = lotW / k;
                    for (int j = 0; j < k; j++) {
                        Plan p = makePlan(t, ord, n, userSize, uw, lotD);
                        p.party = k > 1;
                        int w = j == k - 1 ? lotW - uw * (k - 1) : uw;
                        int a0 = lu0 + j * uw;
                        int d = Math.min(p.d, lotD);
                        setLot(p, a0, a0 + w - 1, front, front + d - 1, look.getOpposite());
                        plans.add(p);
                        i++;
                    }
                    continue;
                }
                Plan p = makePlan(t, ord, n, userSize, lotW - 2 * (t == BuildingType.HOUSE && s.yards ? 2 : 0), lotD);
                int w = Math.min(p.w, lotW - 2 * p.yardSide);
                int d = Math.min(p.d, lotD - p.yardFront);
                if (d < t.minD) { p.yardFront = 0; d = Math.min(p.d, lotD); }
                int a0 = lu0 + (lotW - w) / 2;
                setLot(p, a0, a0 + w - 1, front + p.yardFront, front + p.yardFront + d - 1, look.getOpposite());
                plans.add(p);
                i++;
            }
            addFrontStrips(u0 - 2, u0 + total + 1, front, s.street);
            farBack = front + lotD;
            front += lotD + Math.max(rowFront, s.spacing) + (rowFront == 0 ? 0 : 0);
        }
        // поперечные улицы между столбцами
        if (s.crossStreets != StreetMode.NONE && C > 1) {
            for (int c = 0; c < C - 1; c++) {
                int g0 = u0 + (c + 1) * lotW + c * colGap, g1 = g0 + colGap - 1;
                int v0 = nearFront - rowFront, v1 = farBack;
                if (s.crossStreets == StreetMode.SIDEWALK) {
                    strips.add(new Strip(g0, g1, v0, v1, SIDEWALK, false, g0));
                } else {
                    strips.add(new Strip(g0, g0 + 2, v0, v1, SIDEWALK, false, g0));
                    strips.add(new Strip(g0 + 3, g1 - 3, v0, v1, ROAD, false, Integer.MIN_VALUE));
                    strips.add(new Strip(g1 - 2, g1, v0, v1, SIDEWALK, false, g1));
                }
            }
        }
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

    private void addFrontStrips(int uMin, int uMax, int front, StreetMode mode) {
        if (mode == StreetMode.SIDEWALK) {
            strips.add(new Strip(uMin, uMax, front - 3, front - 1, SIDEWALK, true, front - 3));
        } else if (mode == StreetMode.ROAD) {
            strips.add(new Strip(uMin, uMax, front - 3, front - 1, SIDEWALK, true, front - 3));
            strips.add(new Strip(uMin, uMax, front - 10, front - 4, ROAD, true, Integer.MIN_VALUE));
            strips.add(new Strip(uMin, uMax, front - 13, front - 11, SIDEWALK, true, front - 11));
        }
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
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        int min = level.getMinBuildHeight();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        while (y > min) {
            if (isGround(level.getBlockState(mp.set(x, y, z)))) return y;
            y--;
        }
        return y;
    }

    private static int groundLevel(Level level, int minX, int minZ, int maxX, int maxZ) {
        List<Integer> ys = new ArrayList<>();
        for (int i = 0; i <= 4; i++)
            for (int j = 0; j <= 4; j++)
                ys.add(surfaceY(level, minX + (maxX - minX) * i / 4, minZ + (maxZ - minZ) * j / 4));
        Collections.sort(ys);
        return ys.get(ys.size() / 2);
    }
}
