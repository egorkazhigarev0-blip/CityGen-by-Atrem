package com.citygen.dev;

import com.citygen.CityGen;
import com.citygen.gen.*;
import com.citygen.gen.GenSettings.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Инструмент разработчика: запускается только при генерации данных (gradlew runData).
 * Рисует здания всех типов и стилей, планы этажей с мебелью, сцены и гоняет стресс-тест.
 */
@Mod.EventBusSubscriber(modid = CityGen.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class SelfTest {
    private SelfTest() {}

    @SubscribeEvent
    public static void onGather(GatherDataEvent e) {
        try { run(); } catch (Throwable t) { CityGen.LOG.error("SELFTEST FAILED", t); }
    }

    private static final BlockPos O = new BlockPos(0, 64, 0);

    private static void run() throws Exception {
        File out = new File("selftest");
        out.mkdirs();
        int errors = 0;

        // 1) типы × стили
        StyleMode[] styles = {StyleMode.MODERN, StyleMode.CLASSIC, StyleMode.ANTIQUE, StyleMode.FANTASY};
        for (StyleMode st : styles) {
            List<BufferedImage> tiles = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (BuildingType t : BuildingType.values()) {
                if (t == BuildingType.MIXED) continue;
                GenSettings s = base(t);
                s.style = st; s.seed = 40 + t.ordinal() * 3L + st.ordinal();
                if (t == BuildingType.SKYSCRAPER) { s.floorsMin = 28; s.floorsMax = 28; }
                try {
                    Layout L = Layout.compute(s, O, Direction.NORTH, null);
                    Plan p = L.plans.get(0);
                    tiles.add(img(PreviewRenderer.render(L, s, PreviewRenderer.Mode.ISO, () -> false)));
                    labels.add(t.label + " | " + p.facade.label() + " | " + p.roof.label() + " | " + p.shape.label() + " | " + Palettes.get(p.paletteId).name);
                } catch (Throwable ex) { errors++; CityGen.LOG.error("SELFTEST {} {} failed", t, st, ex); }
            }
            ImageIO.write(sheet(tiles, labels, 4, 420, 520), "png", new File(out, "style_" + st.name().toLowerCase() + ".png"));
        }

        // 2) планы этажей с мебелью
        List<BufferedImage> plansImg = new ArrayList<>();
        List<String> planLabels = new ArrayList<>();
        Object[][] fp = {
                {BuildingType.MIDRISE, StyleMode.CLASSIC, 2, "Квартиры (классика), 3-й этаж"},
                {BuildingType.MIDRISE, StyleMode.MODERN, 0, "Магазины, 1-й этаж"},
                {BuildingType.OFFICE, StyleMode.MODERN, 3, "Офис, 4-й этаж"},
                {BuildingType.HIGHRISE, StyleMode.MODERN, 0, "Лобби"},
                {BuildingType.CIVIC, StyleMode.ANTIQUE, 0, "Музей / библиотека"},
                {BuildingType.HOUSE, StyleMode.CLASSIC, 0, "Частный дом"},
                {BuildingType.COMMERCIAL, StyleMode.MODERN, 0, "Торговый центр"},
                {BuildingType.TOWNHOUSE, StyleMode.FANTASY, 1, "Таунхаус (фантастика)"},
        };
        for (Object[] f : fp) {
            GenSettings s = base((BuildingType) f[0]);
            s.style = (StyleMode) f[1]; s.seed = 77; s.interior = Interior.FURNISHED; s.litPercent = 100;
            try {
                Layout L = Layout.compute(s, O, Direction.NORTH, null);
                Plan p = L.plans.get(0);
                int fl = Math.min((Integer) f[2], p.floors - 1);
                Blueprint bp = BuildingGenerator.generate(p, s);
                int y0 = fl == 0 ? 0 : p.groundH + (fl - 1) * p.floorH;
                plansImg.add(floorPlan(bp, y0, 9));
                planLabels.add((String) f[3]);
            } catch (Throwable ex) { errors++; CityGen.LOG.error("SELFTEST floorplan {} failed", f[3], ex); }
        }
        ImageIO.write(sheet(plansImg, planLabels, 4, 480, 420), "png", new File(out, "floorplans.png"));

        // 3) сцены
        GenSettings city = new GenSettings();
        DemoCity.make(city, 0, 0);
        city.interior = Interior.NONE; city.seed = 5; city.construction = 10; city.greenery = Greenery.RICH;
        scene(out, "city", city, PreviewRenderer.Mode.ISO);
        scene(out, "city_top", city, PreviewRenderer.Mode.TOP);
        // крупно: перекрёсток двух кварталов (дороги, «зебры», фонари у бордюра)
        GenSettings cross = new GenSettings();
        DemoCity.make(cross, 0, 0);
        cross.quarters.subList(2, cross.quarters.size()).clear();
        cross.interior = Interior.NONE; cross.seed = 6;
        scene(out, "crossing", cross, PreviewRenderer.Mode.ISO);

        // стройки
        List<BufferedImage> cons = new ArrayList<>();
        List<String> consL = new ArrayList<>();
        for (BuildingType t : new BuildingType[]{BuildingType.SKYSCRAPER, BuildingType.OFFICE, BuildingType.HIGHRISE, BuildingType.MIDRISE, BuildingType.HOUSE, BuildingType.TOWNHOUSE}) {
            GenSettings s2 = base(t);
            s2.construction = 100; s2.seed = 300 + t.ordinal();
            if (t == BuildingType.SKYSCRAPER) { s2.floorsMin = 26; s2.floorsMax = 26; }
            Layout L = Layout.compute(s2, O, Direction.NORTH, null);
            cons.add(img(PreviewRenderer.render(L, s2, PreviewRenderer.Mode.ISO, () -> false)));
            consL.add("Стройка: " + t.label);
        }
        ImageIO.write(sheet(cons, consL, 3, 520, 560), "png", new File(out, "construction.png"));

        // проходимость: «виртуальный игрок» заходит в каждое здание
        Random rr = new Random(42);
        int checked = 0, bad = 0;
        double sum = 0;
        StringBuilder report = new StringBuilder();
        for (int k = 0; k < 480; k++) {
            BuildingType t = BuildingType.values()[rr.nextInt(BuildingType.values().length - 1)];
            GenSettings s3 = base(t);
            s3.style = StyleMode.values()[1 + rr.nextInt(4)];
            s3.interior = Interior.FURNISHED; s3.seed = rr.nextLong(); s3.construction = 0;
            if (t == BuildingType.SKYSCRAPER) { s3.floorsMin = 12; s3.floorsMax = 20; }
            try {
                Layout L = Layout.compute(s3, O, Direction.NORTH, null);
                Plan p = L.plans.get(0);
                Blueprint bp = BuildingGenerator.generate(p, s3);
                Reach.Result rs = Reach.check(bp, p);
                checked++;
                sum += rs.worst;
                if (rs.worst < 0.85) {
                    if (bad < 6) {
                        int fy1 = 0;
                        for (int i = 0; i < Math.min(rs.worstFloor, p.floors - 1); i++) fy1 += i == 0 ? p.groundH : p.floorH;
                        java.nio.file.Files.writeString(new File(out, "reach_" + bad + ".txt").toPath(),
                                t + " " + p.style + " " + p.facade + " " + p.shape + " gh=" + p.groundH + " fh=" + p.floorH + "\n"
                                        + Reach.dump(bp, rs, Math.max(0, fy1 - p.groundH - 1), fy1 + 2));
                    }
                    bad++;
                    report.append(String.format("%n  %s %s %s %s floors=%d worst=%.2f@%d seed=%d", t, p.style, p.facade, p.shape, p.floors,
                            rs.worst, rs.worstFloor, s3.seed));
                }
            } catch (Throwable ex) { errors++; CityGen.LOG.error("SELFTEST reach failed", ex); }
        }
        CityGen.LOG.info("SELFTEST REACH: checked={} bad={} avgWorst={}{}", checked, bad, String.format("%.3f", sum / Math.max(1, checked)), report);

        GenSettings fant = base(BuildingType.SKYSCRAPER);
        fant.style = StyleMode.FANTASY; fant.count = 5; fant.heightProfile = HeightProfile.PEAK; fant.floorsMin = 18; fant.floorsMax = 40;
        fant.street = StreetMode.ROAD; fant.seed = 11; fant.spacing = 6;
        scene(out, "fantasy_row", fant, PreviewRenderer.Mode.ISO);

        GenSettings ant = base(BuildingType.CIVIC);
        ant.style = StyleMode.ANTIQUE; ant.count = 3; ant.street = StreetMode.SIDEWALK; ant.seed = 3; ant.spacing = 8;
        scene(out, "antique_row", ant, PreviewRenderer.Mode.ISO);

        GenSettings th = base(BuildingType.TOWNHOUSE);
        th.style = StyleMode.CLASSIC; th.count = 8; th.spacing = 0; th.arrangement = Arrangement.STREET; th.seed = 9;
        scene(out, "townhouse_street", th, PreviewRenderer.Mode.ISO);

        // 4) стресс-тест
        Random r = new Random(1);
        int ok = 0;
        for (int k = 0; k < 400; k++) {
            GenSettings s = new GenSettings();
            s.type = BuildingType.values()[r.nextInt(BuildingType.values().length)];
            s.applyTypeDefaults();
            s.count = 1 + r.nextInt(6);
            s.style = StyleMode.values()[r.nextInt(StyleMode.values().length)];
            s.variation = Variation.values()[r.nextInt(4)];
            s.heightProfile = HeightProfile.values()[r.nextInt(4)];
            s.floorsMin = 1 + r.nextInt(30); s.floorsMax = 1 + r.nextInt(50);
            s.width = 5 + r.nextInt(60); s.depth = 5 + r.nextInt(60);
            s.facade = FacadeType.values()[r.nextInt(FacadeType.values().length)];
            s.shape = GenSettings.Shape.values()[r.nextInt(GenSettings.Shape.values().length)];
            s.roof = RoofType.values()[r.nextInt(RoofType.values().length)];
            s.groundFloor = GroundFloor.values()[r.nextInt(GroundFloor.values().length)];
            s.balconies = Balconies.values()[r.nextInt(4)];
            s.windows = WindowSize.values()[r.nextInt(4)];
            s.floorHeight = r.nextBoolean() ? 0 : 3 + r.nextInt(5);
            s.fireEscapes = Toggle3.values()[r.nextInt(3)];
            s.interior = Interior.values()[r.nextInt(4)];
            s.stairs = StairsMode.values()[r.nextInt(3)];
            s.arrangement = Arrangement.values()[r.nextInt(Arrangement.values().length)];
            s.street = StreetMode.values()[r.nextInt(3)];
            s.crossStreets = StreetMode.values()[r.nextInt(3)];
            s.greenery = Greenery.values()[r.nextInt(3)];
            s.construction = r.nextInt(30);
            s.quarters.clear();
            for (int qn = 1 + r.nextInt(3), qi = 0; qi < qn; qi++) {
                GenSettings.Quarter q = new GenSettings.Quarter();
                q.kind = GenSettings.QuarterKind.values()[r.nextInt(GenSettings.QuarterKind.values().length)];
                q.facing = GenSettings.QFacing.values()[r.nextInt(GenSettings.QFacing.values().length)];
                q.courtyard = GenSettings.Courtyard.values()[r.nextInt(4)];
                q.street = StreetMode.values()[r.nextInt(3)];
                q.x0 = r.nextInt(200) - 100; q.z0 = r.nextInt(200) - 100;
                q.x1 = q.x0 + 14 + r.nextInt(80); q.z1 = q.z0 + 14 + r.nextInt(80);
                q.floorsMin = r.nextBoolean() ? 0 : 1 + r.nextInt(10); q.floorsMax = q.floorsMin + r.nextInt(10);
                q.lotWidth = r.nextBoolean() ? 0 : 6 + r.nextInt(40); q.spacing = r.nextInt(8) - 1; q.seed = r.nextInt(99);
                q.heights = HeightProfile.values()[r.nextInt(4)];
                q.ground = GroundFloor.values()[r.nextInt(GroundFloor.values().length)];
                q.construction = r.nextInt(5) == 0 ? r.nextInt(101) : -1;
                q.raise = r.nextInt(9) - 4;
                q.lotDepth = r.nextBoolean() ? 0 : 7 + r.nextInt(30);
                q.style = StyleMode.values()[r.nextInt(StyleMode.values().length)];
                s.quarters.add(q);
            }
            s.spacing = r.nextInt(10);
            s.rotation = r.nextInt(4); s.offset = r.nextInt(40) - 20;
            s.palette = r.nextInt(Palettes.ALL.size() + 1) - 1;
            s.seed = r.nextLong();
            try {
                Layout L = Layout.compute(s, O, Direction.from2DDataValue(r.nextInt(4)), null);
                com.citygen.server.JobManager.makeOps(L, s);
                ok++;
            } catch (Throwable ex) {
                errors++;
                CityGen.LOG.error("SELFTEST stress #{} failed: type={} style={} facade={} shape={} roof={} interior={} w={} d={}",
                        k, s.type, s.style, s.facade, s.shape, s.roof, s.interior, s.width, s.depth, ex);
            }
        }
        CityGen.LOG.info("SELFTEST DONE: stress ok={} errors={}", ok, errors);
    }

    private static GenSettings base(BuildingType t) {
        GenSettings s = new GenSettings();
        s.type = t; s.applyTypeDefaults(); s.count = 1; s.street = StreetMode.NONE; s.greenery = Greenery.LIGHT;
        s.interior = Interior.NONE; s.construction = 0;
        return s;
    }

    private static void scene(File out, String name, GenSettings s, PreviewRenderer.Mode mode) {
        try {
            Layout L = Layout.compute(s, O, Direction.NORTH, null);
            long t0 = System.nanoTime();
            PreviewRenderer.Image im = PreviewRenderer.render(L, s, mode, () -> false);
            CityGen.LOG.info("SELFTEST scene {}: {} plans, {} lots, render {} ms", name, L.plans.size(), L.lots.size(), (System.nanoTime() - t0) / 1_000_000);
            ImageIO.write(img(im), "png", new File(out, "scene_" + name + ".png"));
        } catch (Throwable ex) {
            CityGen.LOG.error("SELFTEST scene {} failed", name, ex);
        }
    }

    private static BufferedImage img(PreviewRenderer.Image im) {
        BufferedImage b = new BufferedImage(im.w, im.h, BufferedImage.TYPE_INT_ARGB);
        b.setRGB(0, 0, im.w, im.h, im.argb, 0, im.w);
        return b;
    }

    /** План этажа: вид сверху на уровне мебели. */
    private static BufferedImage floorPlan(Blueprint bp, int y0, int k) {
        int w = bp.maxX() - bp.minX() + 1, d = bp.maxZ() - bp.minZ() + 1;
        BufferedImage img = new BufferedImage(w * k, d * k, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x151B23));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        for (int z = bp.minZ(); z <= bp.maxZ(); z++)
            for (int x = bp.minX(); x <= bp.maxX(); x++) {
                BlockState top = null;
                boolean furn = false;
                for (int dy = 2; dy >= 0; dy--) {
                    BlockState st = bp.get(x, y0 + dy, z);
                    if (st != null && !st.isAir()) { top = st; furn = dy >= 1; break; }
                }
                if (top == null) continue;
                int c = PreviewRenderer.color(top);
                g.setColor(new Color(c, true));
                int px = (x - bp.minX()) * k, pz = (z - bp.minZ()) * k;
                g.fillRect(px, pz, k, k);
                if (furn) { g.setColor(new Color(0, 0, 0, 90)); g.drawRect(px, pz, k - 1, k - 1); }
            }
        g.dispose();
        return img;
    }

    private static BufferedImage sheet(List<BufferedImage> tiles, List<String> labels, int cols, int cw, int chh) {
        int rows = Math.max(1, (tiles.size() + cols - 1) / cols);
        BufferedImage img = new BufferedImage(cols * cw, rows * (chh + 22), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x0F151C));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (int i = 0; i < tiles.size(); i++) {
            BufferedImage t = tiles.get(i);
            int col = i % cols, row = i / cols;
            double k = Math.min(1.5, Math.min(cw / (double) t.getWidth(), chh / (double) t.getHeight()));
            int w = (int) (t.getWidth() * k), h = (int) (t.getHeight() * k);
            g.drawImage(t, col * cw + (cw - w) / 2, row * (chh + 22) + 20 + (chh - h), w, h, null);
            g.setColor(new Color(0xE9EEF4));
            g.setFont(new Font("Dialog", Font.PLAIN, 11));
            String lab = labels.get(i);
            while (g.getFontMetrics().stringWidth(lab) > cw - 8 && lab.length() > 4) lab = lab.substring(0, lab.length() - 2);
            g.drawString(lab, col * cw + 4, row * (chh + 22) + 14);
        }
        g.dispose();
        return img;
    }
}
