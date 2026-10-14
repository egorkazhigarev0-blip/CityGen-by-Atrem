package com.citygen.dev;

import com.citygen.CityGen;
import com.citygen.client.CityEditorScreen;
import com.citygen.client.ClientPrefs;
import com.citygen.client.ClientState;
import com.citygen.client.EditorScreen;
import com.citygen.gen.*;
import com.citygen.gen.GenSettings.*;
import com.citygen.server.JobManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Инструмент разработчика для рекламного ролика (переменная окружения CITYGEN_TRAILER):
 * создаёт мир, находит место, строит город и снимает пролёты камерой в кадры run/trailer/<сцена>/NNNNN.jpg.
 */
@Mod.EventBusSubscriber(modid = CityGen.MODID, value = Dist.CLIENT)
public final class Trailer {
    private static final boolean ENABLED = System.getenv("CITYGEN_TRAILER") != null;
    /** CITYGEN_TRAILER=city — ролик про редактор города (Shorts 0.3). */
    private static final boolean CITY_SHORT = "city".equals(System.getenv("CITYGEN_TRAILER"));
    private static java.util.function.IntConsumer hook;
    private Trailer() {}

    // ---------------- шаги сценария ----------------
    private interface Step { default void start() {} boolean tick(); }

    private static final List<Step> steps = new ArrayList<>();
    private static int stepIdx = -1;
    private static boolean started;

    // ---------------- запись ----------------
    private static String scene;
    private static int frame, frames;
    private static Path path;
    private static BooleanSupplier until;
    private static int extraAfter;
    private static boolean recording;
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);
    private static final Semaphore QUEUE = new Semaphore(24);
    private static File outDir;

    // ---------------- мир ----------------
    private static BlockPos site;      // якорь главного города (земля)
    private static Layout cityLayout;
    private static int cMinX, cMinZ, cMaxX, cMaxZ, cTop, groundY;
    private static final List<int[]> styleSpots = new ArrayList<>(); // {anchorX, groundY, anchorZ, minX, minZ, maxX, maxZ, top}

    /** Поза камеры для t ∈ [0,1]. */
    private interface Path { double[] pose(double t); }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (!ENABLED || e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (!started) {
            if (!(mc.screen instanceof TitleScreen)) return;
            started = true;
            if (CITY_SHORT) buildCityScript(); else buildScript();
            stepIdx = 0;
            steps.get(0).start();
        }
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen && mc.level != null) mc.setScreen(null);
        if (recording || stepIdx < 0 || stepIdx >= steps.size()) return;
        if (steps.get(stepIdx).tick()) {
            stepIdx++;
            if (stepIdx < steps.size()) steps.get(stepIdx).start();
        }
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (!ENABLED || !recording) return;
        Minecraft mc = Minecraft.getInstance();
        if (e.phase == TickEvent.Phase.START) {
            if (hook != null) hook.accept(frame);
            if (path != null && mc.player != null) {
                double t = frames > 0 ? Math.min(1.0, frame / (double) Math.max(1, frames - 1)) : frame;
                apply(mc.player, path.pose(t));
            }
            return;
        }
        capture(mc, scene, frame);
        frame++;
        boolean done;
        if (until != null) {
            if (until.getAsBoolean()) { if (extraAfter-- <= 0) done = true; else done = false; }
            else done = false;
        } else done = frame >= frames;
        if (done) {
            recording = false;
            hook = null;
            CityGen.LOG.info("TRAILER scene {} recorded: {} frames", scene, frame);
        }
    }

    private static void apply(LocalPlayer p, double[] q) {
        p.absMoveTo(q[0], q[1], q[2], (float) q[3], (float) q[4]);
        p.setYHeadRot((float) q[3]);
        p.yBodyRot = (float) q[3];
        p.setDeltaMovement(0, 0, 0);
        p.setOldPosAndRot();
        p.yHeadRotO = p.yHeadRot;
        p.yBodyRotO = p.yBodyRot;
    }

    private static void capture(Minecraft mc, String sc, int f) {
        NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getPixelsRGBA();
        img.close();
        File dir = new File(outDir, sc);
        dir.mkdirs();
        File file = new File(dir, String.format("%05d.jpg", f));
        QUEUE.acquireUninterruptibly();
        IO.submit(() -> {
            try {
                BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                int[] rgb = new int[px.length];
                for (int i = 0; i < px.length; i++) {
                    int c = px[i];
                    rgb[i] = ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF);
                }
                b.setRGB(0, 0, w, h, rgb, 0, w);
                ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
                ImageWriteParam prm = wr.getDefaultWriteParam();
                prm.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                prm.setCompressionQuality(0.93f);
                try (ImageOutputStream os = ImageIO.createImageOutputStream(file)) {
                    wr.setOutput(os);
                    wr.write(null, new IIOImage(b, null, null), prm);
                }
                wr.dispose();
            } catch (Exception ex) {
                CityGen.LOG.error("TRAILER write failed", ex);
            } finally {
                QUEUE.release();
            }
        });
    }

    // =====================================================================
    // Помощники шагов
    // =====================================================================
    private static Step run(Runnable r) { return () -> { r.run(); return true; }; }

    private static Step waitTicks(int n) {
        int[] left = {0};
        return new Step() {
            public void start() { left[0] = n; }
            public boolean tick() { return --left[0] <= 0; }
        };
    }

    private static Step waitFor(BooleanSupplier c) { return c::getAsBoolean; }

    private static Step server(java.util.function.Consumer<MinecraftServer> task) {
        boolean[] done = {false};
        return new Step() {
            public void start() {
                MinecraftServer srv = Minecraft.getInstance().getSingleplayerServer();
                srv.execute(() -> {
                    try { task.accept(srv); } catch (Throwable t) { CityGen.LOG.error("TRAILER server task failed", t); }
                    done[0] = true;
                });
            }
            public boolean tick() { return done[0]; }
        };
    }

    private static Step cmd(String c) {
        return server(srv -> srv.getCommands().performPrefixedCommand(srv.createCommandSourceStack().withSuppressedOutput(), c));
    }

    /** Поставить камеру в начало пути и подождать прогрузки. */
    private static Step preroll(java.util.function.Supplier<Path> p, int ticks) {
        int[] left = {0};
        return new Step() {
            public void start() {
                left[0] = ticks;
                Minecraft mc = Minecraft.getInstance();
                double[] q = p.get().pose(0);
                cmd(String.format(java.util.Locale.ROOT, "tp @a %.2f %.2f %.2f %.1f %.1f", q[0], q[1], q[2], q[3], q[4])).start();
                if (mc.player != null) apply(mc.player, q);
            }
            public boolean tick() {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) apply(mc.player, p.get().pose(0));
                return --left[0] <= 0;
            }
        };
    }

    private static Step record(String name, int n, java.util.function.Supplier<Path> p) {
        return new Step() {
            public void start() { scene = name; frame = 0; frames = n; path = p.get(); until = null; recording = true; }
            public boolean tick() { return !recording; }
        };
    }

    private static Step recordHook(String name, int n, java.util.function.Supplier<Path> p, java.util.function.IntConsumer h) {
        return new Step() {
            public void start() { scene = name; frame = 0; frames = n; path = p.get(); until = null; hook = h; recording = true; }
            public boolean tick() { return !recording; }
        };
    }

    private static Step recordUntil(String name, java.util.function.Supplier<Path> p, BooleanSupplier stop, int extra, int guess) {
        return new Step() {
            public void start() { scene = name; frame = 0; frames = guess; path = p.get(); until = stop; extraAfter = extra; recording = true; }
            public boolean tick() { return !recording; }
        };
    }

    // ---------------- пути камеры ----------------
    private static double[] look(double x, double y, double z, double tx, double ty, double tz) {
        double dx = tx - x, dy = ty - y, dz = tz - z;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new double[]{x, y, z, yaw, pitch};
    }

    private static double ease(double t) { return t * t * (3 - 2 * t); }

    private static Path orbit(double cx, double cy, double cz, double r, double h, double a0, double a1, double ty) {
        return t -> {
            double a = Math.toRadians(a0 + (a1 - a0) * ease(t));
            return look(cx + Math.cos(a) * r, cy + h, cz + Math.sin(a) * r, cx, ty, cz);
        };
    }

    /** Орбита без сглаживания, растянутая на произвольное число кадров (для таймлапса). */
    private static Path slowOrbit(double cx, double cy, double cz, double r, double h, double a0, double degPerFrame, double ty) {
        return f -> {
            double a = Math.toRadians(a0 + degPerFrame * f);
            return look(cx + Math.cos(a) * r, cy + h, cz + Math.sin(a) * r, cx, ty, cz);
        };
    }

    private static Path dolly(double[] from, double[] to, double[] target0, double[] target1) {
        return t -> {
            double e = ease(t);
            double x = from[0] + (to[0] - from[0]) * e, y = from[1] + (to[1] - from[1]) * e, z = from[2] + (to[2] - from[2]) * e;
            double tx = target0[0] + (target1[0] - target0[0]) * e, ty = target0[1] + (target1[1] - target0[1]) * e,
                    tz = target0[2] + (target1[2] - target0[2]) * e;
            return look(x, y, z, tx, ty, tz);
        };
    }

    private static double cx() { return (cMinX + cMaxX) / 2.0; }
    private static double cz() { return (cMinZ + cMaxZ) / 2.0; }
    private static double span() { return Math.max(cMaxX - cMinX, cMaxZ - cMinZ); }

    // =====================================================================
    // Сценарий
    // =====================================================================
    private static GenSettings citySettings() {
        GenSettings s = new GenSettings();
        s.type = BuildingType.MIXED;
        if (site != null) DemoCity.make(s, site.getX(), site.getZ() - 70);
        s.street = StreetMode.ROAD;
        s.variation = Variation.DIFFERENT; s.heightProfile = HeightProfile.PEAK;
        s.interior = Interior.FURNISHED; s.greenery = Greenery.RICH; s.litPercent = 60;
        s.distance = 4; s.seed = 777; s.newSeedEachBuild = false;
        return s;
    }

    private static void buildScript() {
        Minecraft mc = Minecraft.getInstance();
        outDir = new File(mc.gameDirectory, "trailer");
        outDir.mkdirs();
        steps.add(run(() -> {
            mc.options.renderDistance().set(16);
            mc.options.simulationDistance().set(12);
            mc.options.bobView().set(false);
            mc.options.fov().set(70);
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.pauseOnLostFocus = false;
            mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc.options.gamma().set(0.7);
            mc.options.hideGui = false;
            if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
            ClientPrefs.get().previewOnOpen = true;
            ClientPrefs.get().accent = 0;
            ClientPrefs.get().panelDX = 0; ClientPrefs.get().panelDY = 0;
            String name = "CityGenTrailer" + System.currentTimeMillis() % 100000;
            LevelSettings ls = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(),
                    WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, ls, new WorldOptions(20261013L, true, false),
                    WorldPresets::createNormalWorldDimensions);
        }));
        steps.add(waitFor(() -> mc.player != null && mc.level != null && mc.screen == null));
        steps.add(waitTicks(60));
        steps.add(cmd("gamemode spectator @a"));
        steps.add(cmd("gamerule doDaylightCycle false"));
        steps.add(cmd("gamerule doWeatherCycle false"));
        steps.add(cmd("gamerule doMobSpawning false"));
        steps.add(cmd("weather clear"));
        steps.add(cmd("time set 2500"));
        steps.add(server(Trailer::findSite));
        steps.add(run(() -> {
            mc.options.hideGui = true;
            JobManager.riseTogether = true;
            JobManager.budgetOverrideNs = 6_000_000L;
            ClientState.settings = citySettings();
            ClientState.anchor = site;
            ClientState.look = Direction.NORTH;
            ClientState.dimension = mc.level.dimension();
            ClientState.previewActive = true;
            ClientState.recompute();
        }));
        // 1. Нетронутый лес с рамками будущего города
        steps.add(preroll(() -> orbit(cx(), groundY, cz(), span() * 0.75, span() * 0.45, 200, 250, groundY + 10), 260));
        steps.add(record("01_forest_preview", 180, () -> orbit(cx(), groundY, cz(), span() * 0.75, span() * 0.45, 200, 250, groundY + 10)));
        // 2. Редактор поверх мира
        steps.add(preroll(() -> t -> look(site.getX() + 0.5, groundY + 1.6 + 12, site.getZ() + 18, cx(), groundY + 8, cz()), 40));
        steps.add(run(() -> { mc.options.hideGui = false; ClientPrefs.get().tab = 0; mc.setScreen(new EditorScreen()); }));
        steps.add(record("02_ui_buildings", 75, () -> t -> look(site.getX() + 0.5, groundY + 13.6, site.getZ() + 18, cx(), groundY + 8, cz())));
        steps.add(run(() -> { ClientPrefs.get().tab = 4; mc.setScreen(new EditorScreen()); }));
        steps.add(record("03_ui_planner", 75, () -> t -> look(site.getX() + 0.5, groundY + 13.6, site.getZ() + 18, cx(), groundY + 8, cz())));
        steps.add(run(() -> { ClientPrefs.get().tab = 5; mc.setScreen(new EditorScreen()); }));
        steps.add(waitTicks(80));
        steps.add(record("04_ui_map", 90, () -> t -> look(site.getX() + 0.5, groundY + 13.6, site.getZ() + 18, cx(), groundY + 8, cz())));
        steps.add(run(() -> { ClientPrefs.get().tab = 1; mc.setScreen(new EditorScreen()); }));
        steps.add(record("05_ui_arch", 60, () -> t -> look(site.getX() + 0.5, groundY + 13.6, site.getZ() + 18, cx(), groundY + 8, cz())));
        // 3. Стройка: таймлапс
        steps.add(run(() -> { mc.setScreen(null); mc.options.hideGui = true; ClientState.build(); }));
        steps.add(recordUntil("06_build_timelapse",
                () -> slowOrbit(cx(), groundY, cz(), span() * 0.8, span() * 0.5, 230, 0.12, groundY + 15),
                () -> !JobManager.busy(), 90, 0));
        steps.add(run(() -> { JobManager.budgetOverrideNs = 0; ClientState.previewActive = false; }));
        steps.add(waitTicks(120));
        // 4. Пролёты над готовым городом
        steps.add(preroll(() -> orbit(cx(), groundY, cz(), span() * 0.62, Math.min(cTop - groundY + 25, 150), 120, 200, groundY + (cTop - groundY) * 0.45), 160));
        steps.add(record("07_orbit_high", 240, () -> orbit(cx(), groundY, cz(), span() * 0.62, Math.min(cTop - groundY + 25, 150), 120, 200, groundY + (cTop - groundY) * 0.45)));
        steps.add(preroll(Trailer::streetPath, 160));
        steps.add(record("08_street_dolly", 270, Trailer::streetPath));
        steps.add(preroll(Trailer::cranePath, 120));
        steps.add(record("09_crane_up", 210, Trailer::cranePath));
        // 5. Интерьеры
        steps.add(server(Trailer::findInteriors));
        for (int k = 0; k < 3; k++) {
            final int kk = k;
            steps.add(preroll(() -> interiorPath(kk), 70));
            steps.add(record("10_interior_" + k, 120, () -> interiorPath(kk)));
        }
        // 6. Ночь
        steps.add(cmd("time set 12900"));
        steps.add(run(() -> mc.options.gamma().set(1.0)));
        steps.add(waitTicks(60));
        steps.add(preroll(() -> orbit(cx(), groundY, cz(), span() * 0.7, cTop - groundY + 10, 300, 360, groundY + (cTop - groundY) * 0.4), 140));
        steps.add(record("11_night", 270, () -> orbit(cx(), groundY, cz(), span() * 0.7, cTop - groundY + 10, 300, 360, groundY + (cTop - groundY) * 0.4)));
        steps.add(cmd("time set 2500"));
        steps.add(run(() -> mc.options.gamma().set(0.7)));
        // 7. Стили
        steps.add(run(() -> JobManager.riseTogether = false));
        for (int k = 0; k < 4; k++) {
            final int kk = k;
            steps.add(server(srv -> buildStyle(srv, kk)));
            steps.add(waitFor(() -> !JobManager.busy()));
        }
        steps.add(waitTicks(60));
        for (int k = 0; k < 4; k++) {
            final int kk = k;
            steps.add(preroll(() -> stylePath(kk), 160));
            steps.add(record("12_style_" + k, 150, () -> stylePath(kk)));
        }
        // 8. Финальный отъезд
        steps.add(preroll(Trailer::finalPath, 200));
        steps.add(record("13_final", 240, Trailer::finalPath));
        steps.add(run(() -> {
            IO.shutdown();
            try { IO.awaitTermination(2, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}
            CityGen.LOG.info("TRAILER DONE");
            mc.stop();
        }));
    }

    private static Path streetPath() {
        // по главной дороге перед ближним рядом, на уровне глаз, вдоль всей ширины
        double z = site.getZ() - 4 - 6.5, y = groundY + 3.2;
        return dolly(new double[]{cMinX - 10, y, z}, new double[]{cMaxX + 10, y + 4, z},
                new double[]{cMinX + 14, groundY + 9, z - 22}, new double[]{cMaxX + 30, groundY + 12, z - 22});
    }

    private static Path cranePath() {
        // взлёт вдоль самого высокого здания
        Plan tall = cityLayout.plans.get(0);
        for (Plan p : cityLayout.plans) if (p.estimatedHeight() > tall.estimatedHeight()) tall = p;
        double tx = (tall.minX + tall.maxX) / 2.0, tz = (tall.minZ + tall.maxZ) / 2.0;
        double top = tall.baseY + tall.estimatedHeight();
        double dist = Math.max(tall.maxX - tall.minX, tall.maxZ - tall.minZ) * 1.6 + 12;
        return dolly(new double[]{tx + dist * 0.4, groundY + 2.5, tz + dist}, new double[]{tx + dist * 0.6, top + 18, tz + dist * 1.2},
                new double[]{tx, groundY + 20, tz}, new double[]{tx, top - 10, tz});
    }

    private static final List<double[]> interiors = new ArrayList<>();

    private static Path interiorPath(int k) {
        if (interiors.isEmpty()) return orbit(cx(), groundY, cz(), 40, 20, 0, 30, groundY);
        double[] v = interiors.get(k % interiors.size());
        return dolly(new double[]{v[0], v[1], v[2]}, new double[]{v[0] + v[5] * 2.5, v[1], v[2] + v[6] * 2.5},
                new double[]{v[3], v[1] - 0.6, v[4]}, new double[]{v[3] + v[6] * 3, v[1] - 0.6, v[4] - v[5] * 3});
    }

    private static Path stylePath(int k) {
        if (k >= styleSpots.size()) return orbit(cx(), groundY, cz(), 60, 30, 0, 30, groundY);
        int[] s = styleSpots.get(k);
        double x = (s[3] + s[5]) / 2.0, z = (s[4] + s[6]) / 2.0, sp = Math.max(s[5] - s[3], s[6] - s[4]);
        return orbit(x, s[1], z, sp * 0.75 + 12, (s[7] - s[1]) * 0.6 + 10, 60, 105, s[1] + (s[7] - s[1]) * 0.35);
    }

    private static Path finalPath() {
        double x = cx() + 60, z = cz() + 40;
        return dolly(new double[]{cx() + 10, Math.min(cTop + 10, 200), cMaxZ + 30}, new double[]{x + 110, Math.min(cTop + 70, 250), z + 160},
                new double[]{cx(), groundY + 20, cz()}, new double[]{x - 20, groundY, z - 60});
    }

    // =====================================================================
    // Серверные задачи
    // =====================================================================
    private static int biomeScore(ServerLevel level, int x, int z) {
        var b = level.getUncachedNoiseBiome(x >> 2, 64 >> 2, z >> 2);
        if (b.is(net.minecraft.tags.BiomeTags.IS_OCEAN) || b.is(net.minecraft.tags.BiomeTags.IS_RIVER)
                || b.is(net.minecraft.tags.BiomeTags.IS_BEACH) || b.is(net.minecraft.tags.BiomeTags.IS_MOUNTAIN)
                || b.is(net.minecraft.tags.BiomeTags.IS_HILL)) return -100;
        if (b.is(net.minecraft.world.level.biome.Biomes.FOREST) || b.is(net.minecraft.world.level.biome.Biomes.BIRCH_FOREST)
                || b.is(net.minecraft.world.level.biome.Biomes.FLOWER_FOREST)) return 3;
        if (b.is(net.minecraft.world.level.biome.Biomes.PLAINS) || b.is(net.minecraft.world.level.biome.Biomes.SUNFLOWER_PLAINS)
                || b.is(net.minecraft.world.level.biome.Biomes.MEADOW)) return 2;
        if (b.is(net.minecraft.world.level.biome.Biomes.SWAMP) || b.is(net.minecraft.world.level.biome.Biomes.MANGROVE_SWAMP)
                || b.is(net.minecraft.world.level.biome.Biomes.DESERT) || b.is(net.minecraft.world.level.biome.Biomes.JUNGLE)
                || b.is(net.minecraft.world.level.biome.Biomes.STONY_SHORE)) return -100;
        return 1;
    }

    /** Оценка участка по биомам; -1 — есть вода, горы или болото. */
    private static int areaScore(ServerLevel level, int cx, int cz, int w, int d, boolean wantForest) {
        int sum = 0, forest = 0;
        for (int i = -4; i <= 4; i++)
            for (int j = -4; j <= 4; j++) {
                int sc = biomeScore(level, cx + i * w / 8, cz + j * d / 8);
                if (sc < 0) return -1;
                sum += sc;
                if (sc == 3) forest++;
            }
        return wantForest ? sum + forest : sum;
    }

    private static final List<int[]> styleAnchors = new ArrayList<>();

    private static void findSite(MinecraftServer srv) {
        ServerLevel level = srv.overworld();
        BlockPos spawn = level.getSharedSpawnPos();
        List<int[]> cands = new ArrayList<>();
        for (int gx = -14; gx <= 14; gx++)
            for (int gz = -14; gz <= 14; gz++) {
                int x = spawn.getX() + gx * 192, z = spawn.getZ() + gz * 192;
                int sc = areaScore(level, x, z, 260, 220, true);
                if (sc > 0) cands.add(new int[]{x, z, sc - (Math.abs(gx) + Math.abs(gz))});
            }
        cands.sort((p, q) -> q[2] - p[2]);
        BlockPos bestPos = spawn;
        double best = Double.MAX_VALUE;
        for (int k = 0; k < Math.min(6, cands.size()); k++) {
            int[] c = cands.get(k);
            double v = heightVariance(level, c[0], c[1]);
            if (v < best) { best = v; bestPos = new BlockPos(c[0], 0, c[1]); }
        }
        level.getChunk(bestPos.getX() >> 4, (bestPos.getZ() + 70) >> 4);
        int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bestPos.getX(), bestPos.getZ() + 70);
        site = new BlockPos(bestPos.getX(), gy, bestPos.getZ() + 70);
        cityLayout = Layout.compute(citySettings(), site, Direction.NORTH, level);
        groundY = cityLayout.streetY;
        cMinX = Integer.MAX_VALUE; cMinZ = Integer.MAX_VALUE; cMaxX = Integer.MIN_VALUE; cMaxZ = Integer.MIN_VALUE; cTop = groundY;
        for (Plan p : cityLayout.plans) {
            cMinX = Math.min(cMinX, p.minX); cMinZ = Math.min(cMinZ, p.minZ); cMaxX = Math.max(cMaxX, p.maxX); cMaxZ = Math.max(cMaxZ, p.maxZ);
            cTop = Math.max(cTop, p.baseY + p.estimatedHeight());
        }
        cMaxZ = Math.max(cMaxZ, site.getZ());
        styleAnchors.clear();
        for (int r = 1; r <= 8 && styleAnchors.size() < 4; r++)
            for (int k = 0; k < r * 8 && styleAnchors.size() < 4; k++) {
                double a = 2 * Math.PI * k / (r * 8);
                int x = (int) (cx() + Math.cos(a) * (150 + r * 70)), z = (int) (cz() + Math.sin(a) * (150 + r * 70));
                if (areaScore(level, x, z, 150, 110, false) <= 0) continue;
                boolean far = true;
                for (int[] o : styleAnchors) if (Math.abs(o[0] - x) + Math.abs(o[1] - z) < 220) far = false;
                if (far) styleAnchors.add(new int[]{x, z});
            }
        CityGen.LOG.info("TRAILER site {} variance {} candidates {} city {}..{} x {}..{} top {} styleSpots {}", site, best, cands.size(),
                cMinX, cMaxX, cMinZ, cMaxZ, cTop, styleAnchors.size());
    }

    private static double heightVariance(ServerLevel level, int cx, int cz) {
        double sum = 0, sum2 = 0;
        int n = 0;
        for (int i = -3; i <= 3; i++)
            for (int j = -3; j <= 3; j++) {
                int x = cx + i * 28, z = cz + j * 24;
                level.getChunk(x >> 4, z >> 4);
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                sum += h; sum2 += h * (double) h; n++;
            }
        double mean = sum / n;
        return sum2 / n - mean * mean + Math.abs(mean - 70) * 3;
    }

    private static void findInteriors(MinecraftServer srv) {
        interiors.clear();
        GenSettings s = citySettings();
        for (Plan p : cityLayout.plans) {
            if (interiors.size() >= 3) break;
            if (p.floors < 3 || p.type.small()) continue;
            // точка за главным фасадом на 2-м этаже, взгляд вглубь
            int floor = Math.min(2, p.floors - 1);
            double y = p.baseY + p.groundH + (floor - 1) * p.floorH + 2.2;
            BlockPos a = p.toWorld(Math.max(2, p.w / 4), 0, 1), b = p.toWorld(Math.max(2, p.w / 4), 0, Math.min(p.d - 2, 7));
            Direction r = p.facing.getClockWise();
            interiors.add(new double[]{a.getX() + 0.5, y, a.getZ() + 0.5, b.getX() + 0.5, b.getZ() + 0.5, r.getStepX(), r.getStepZ()});
        }
        CityGen.LOG.info("TRAILER interiors {}", interiors.size());
    }

    private static void buildStyle(MinecraftServer srv, int k) {
        ServerLevel level = srv.overworld();
        ServerPlayer sp = srv.getPlayerList().getPlayers().get(0);
        GenSettings s = new GenSettings();
        s.street = StreetMode.ROAD; s.greenery = Greenery.RICH; s.interior = Interior.ROOMS; s.seed = 900 + k;
        s.distance = 4; s.variation = Variation.DIFFERENT;
        switch (k) {
            case 0 -> { s.type = BuildingType.CIVIC; s.applyTypeDefaults(); s.style = StyleMode.ANTIQUE; s.count = 3; s.spacing = 8; }
            case 1 -> { s.type = BuildingType.SKYSCRAPER; s.applyTypeDefaults(); s.style = StyleMode.FANTASY; s.count = 4;
                s.floorsMin = 20; s.floorsMax = 38; s.heightProfile = HeightProfile.PEAK; s.spacing = 8; }
            case 2 -> { s.type = BuildingType.TOWNHOUSE; s.applyTypeDefaults(); s.style = StyleMode.CLASSIC; s.count = 10; s.spacing = 0; }
            default -> { s.type = BuildingType.MIDRISE; s.applyTypeDefaults(); s.style = StyleMode.CLASSIC; s.count = 4; s.spacing = 0;
                s.roof = RoofType.MANSARD; }
        }
        int ax = k < styleAnchors.size() ? styleAnchors.get(k)[0] : cMaxX + 70 + k * 120;
        int az = k < styleAnchors.size() ? styleAnchors.get(k)[1] + 40 : site.getZ();
        level.getChunk(ax >> 4, az >> 4);
        int gy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ax, az);
        BlockPos anchor = new BlockPos(ax, gy, az);
        Layout L = Layout.compute(s, anchor, Direction.NORTH, level);
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, top = L.streetY;
        for (Plan p : L.plans) {
            minX = Math.min(minX, p.minX); minZ = Math.min(minZ, p.minZ); maxX = Math.max(maxX, p.maxX); maxZ = Math.max(maxZ, p.maxZ);
            top = Math.max(top, p.baseY + p.estimatedHeight());
        }
        styleSpots.add(new int[]{ax, L.streetY, az, minX, minZ, maxX, maxZ, top});
        CityGen.LOG.info("TRAILER style {} at {} box {}..{} x {}..{} top {}", k, anchor, minX, maxX, minZ, maxZ, top);
        sp.teleportTo(level, anchor.getX() + 0.5, anchor.getY() + 20, anchor.getZ() + 10, 180f, 30f);
        JobManager.startBuild(sp, s, anchor, Direction.NORTH);
    }

    // =====================================================================
    // Shorts про редактор города (0.3.0)
    // =====================================================================
    private static final List<Quarter> demo = new ArrayList<>();
    private static double[] eye;

    private static Path still(double[] q) { return t -> q; }

    private static void buildCityScript() {
        Minecraft mc = Minecraft.getInstance();
        outDir = new File(mc.gameDirectory, "trailer_city");
        outDir.mkdirs();
        steps.add(run(() -> {
            mc.options.renderDistance().set(16);
            mc.options.simulationDistance().set(12);
            mc.options.bobView().set(false);
            mc.options.fov().set(70);
            mc.options.cloudStatus().set(CloudStatus.OFF);
            mc.options.pauseOnLostFocus = false;
            mc.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            mc.options.gamma().set(0.7);
            mc.options.hideGui = false;
            if (!mc.getWindow().isFullscreen()) mc.getWindow().toggleFullScreen();
            ClientPrefs.get().previewOnOpen = true;
            ClientPrefs.get().accent = 0;
            ClientPrefs.get().modernFont = true;
            ClientPrefs.get().satRadius = 160;
            ClientPrefs.get().mapGrid = false;
            ClientPrefs.get().mapPlan = true;
            String name = "CityGenShort" + System.currentTimeMillis() % 100000;
            LevelSettings ls = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(),
                    WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, ls, new WorldOptions(20261013L, true, false),
                    WorldPresets::createNormalWorldDimensions);
        }));
        steps.add(waitFor(() -> mc.player != null && mc.level != null && mc.screen == null));
        steps.add(waitTicks(60));
        steps.add(run(() -> { mc.options.guiScale().set(3); mc.resizeDisplay(); }));
        steps.add(cmd("gamemode spectator @a"));
        steps.add(cmd("gamerule doDaylightCycle false"));
        steps.add(cmd("gamerule doWeatherCycle false"));
        steps.add(cmd("gamerule doMobSpawning false"));
        steps.add(cmd("weather clear"));
        steps.add(cmd("time set 3000"));
        steps.add(server(Trailer::findSite));
        // камера над будущим городом, местность прогружается
        steps.add(run(() -> {
            GenSettings full = citySettings();
            demo.clear();
            demo.addAll(full.quarters);
            GenSettings s = citySettings();
            s.quarters.clear();
            ClientState.settings = s;
            ClientState.anchor = site;
            ClientState.look = Direction.NORTH;
            ClientState.dimension = mc.level.dimension();
            ClientState.previewActive = true;
            ClientState.selected = -1;
            ClientState.recompute();
            eye = look(cx(), groundY + 70, cz() + 40, cx(), groundY, cz() - 10);
        }));
        steps.add(preroll(() -> still(eye), 220));
        // 1. Нетронутый лес сверху
        steps.add(preroll(() -> orbit(cx(), groundY, cz(), span() * 0.7, 70, 230, 262, groundY), 60));
        steps.add(record("c01_forest", 120, () -> orbit(cx(), groundY, cz(), span() * 0.7, 70, 230, 262, groundY)));
        // 2. Открыли редактор: снимок местности
        steps.add(preroll(() -> still(eye), 20));
        steps.add(run(() -> mc.setScreen(new CityEditorScreen(new EditorScreen()))));
        steps.add(waitFor(() -> !com.citygen.client.SatMap.busy()));
        steps.add(waitTicks(20));
        steps.add(record("c02_editor", 80, () -> still(eye)));
        // 3. Рисуем кварталы мышью
        steps.add(recordHook("c03_draw", 6 * 26 + 30, () -> still(eye), Trailer::drawHook));
        // 4. Настройка квартала
        steps.add(run(() -> {
            ClientState.selected = 0;
            CityEditorScreen.devCursor = null;
            mc.setScreen(new CityEditorScreen(new EditorScreen()));
        }));
        steps.add(waitTicks(10));
        steps.add(recordHook("c04_tune", 120, () -> still(eye), Trailer::tuneHook));
        // 5. Правка в мире
        steps.add(run(() -> {
            mc.setScreen(null);
            ClientState.selected = 4;
            ClientState.worldEdit = true;
            ClientState.recompute();
        }));
        steps.add(preroll(Trailer::worldEditPath, 60));
        steps.add(recordHook("c05_world", 150, Trailer::worldEditPath, Trailer::worldHook));
        // 6. Стройка
        steps.add(run(() -> {
            ClientState.worldEdit = false;
            mc.options.hideGui = true;
            JobManager.riseTogether = true;
            JobManager.budgetOverrideNs = 6_000_000L;
            ClientState.settings.quarters.clear();
            for (Quarter q : demo) ClientState.settings.quarters.add(q.copyQ());
            ClientState.recompute();
            cityLayout = ClientState.layout;
        }));
        steps.add(preroll(() -> slowOrbit(cx(), groundY, cz(), span() * 0.8, span() * 0.5, 230, 0.12, groundY + 15), 40));
        steps.add(run(ClientState::buildCity));
        steps.add(recordUntil("c06_build", () -> slowOrbit(cx(), groundY, cz(), span() * 0.8, span() * 0.5, 230, 0.12, groundY + 15),
                () -> !JobManager.busy(), 90, 0));
        steps.add(run(() -> { JobManager.budgetOverrideNs = 0; ClientState.previewActive = false; }));
        steps.add(waitTicks(120));
        // 7. Улица, стройка с кранами, финал
        steps.add(preroll(Trailer::cityStreetPath, 140));
        steps.add(record("c07_street", 210, Trailer::cityStreetPath));
        steps.add(preroll(Trailer::constructionPath, 120));
        steps.add(record("c08_construction", 180, Trailer::constructionPath));
        steps.add(preroll(() -> orbit(cx(), qy(0), cz(), span() * 1.05, 95, 150, 215, qy(0) + 35), 140));
        steps.add(record("c09_orbit", 240, () -> orbit(cx(), qy(0), cz(), span() * 1.05, 95, 150, 215, qy(0) + 35)));
        steps.add(preroll(Trailer::finalPath, 160));
        steps.add(record("c10_final", 210, Trailer::finalPath));
        steps.add(run(() -> {
            IO.shutdown();
            try { IO.awaitTermination(2, TimeUnit.MINUTES); } catch (InterruptedException ignored) {}
            CityGen.LOG.info("TRAILER DONE");
            mc.stop();
        }));
    }

    private static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    /** Кварталы появляются по одному: прямоугольник растягивается за «курсором». */
    private static void drawHook(int f) {
        List<Quarter> qs = ClientState.settings.quarters;
        int per = 26, grow = 18;
        int k = Math.min(demo.size() - 1, f / per), local = f - k * per;
        if (f >= demo.size() * per) {
            CityEditorScreen.devCursor = null;
            return;
        }
        Quarter target = demo.get(k);
        while (qs.size() < k) { Quarter d = demo.get(qs.size()).copyQ(); qs.add(d); ClientState.recompute(); }
        if (qs.size() == k) {
            Quarter q = target.copyQ();
            q.x1 = q.x0; q.z1 = q.z0;
            qs.add(q);
            ClientState.selected = k;
        }
        Quarter q = qs.get(k);
        double t = smooth(local / (double) grow);
        q.x1 = q.x0 + (int) Math.round((target.x1 - target.x0) * t);
        q.z1 = q.z0 + (int) Math.round((target.z1 - target.z0) * t);
        if (local == grow) { q.x1 = target.x1; q.z1 = target.z1; ClientState.recompute(); }
        CityEditorScreen ed = CityEditorScreen.open;
        if (ed != null) {
            double[] c = local < grow ? ed.toScreen(q.x1 + 1, q.z1 + 1) : ed.toScreen(q.x1 + 1, q.z1 + 1);
            if (local >= grow) {
                double[] nx = k + 1 < demo.size() ? ed.toScreen(demo.get(k + 1).x0, demo.get(k + 1).z0) : c;
                double u = smooth((local - grow) / (double) (per - grow));
                c = new double[]{c[0] + (nx[0] - c[0]) * u, c[1] + (nx[1] - c[1]) * u};
            }
            CityEditorScreen.devCursor = c;
        }
    }

    /** Меняем тип квартала — план внутри перерисовывается. */
    private static void tuneHook(int f) {
        QuarterKind[] kinds = {QuarterKind.DOWNTOWN, QuarterKind.RESIDENTIAL, QuarterKind.HISTORIC, QuarterKind.COMMERCIAL, QuarterKind.DOWNTOWN};
        if (f % 24 != 0) return;
        int i = f / 24;
        if (i >= kinds.length) return;
        Quarter q = ClientState.settings.quarters.get(0);
        q.kind = kinds[i];
        ClientState.recompute();
        if (CityEditorScreen.open != null) CityEditorScreen.open.devRefresh();
    }

    /** Уровень земли квартала i по текущей раскладке. */
    private static int qy(int i) {
        Layout L = ClientState.layout;
        return L != null && i < L.quarterGround.length ? L.quarterGround[i] : groundY;
    }

    /** Квартал «едет» стрелками туда и обратно. */
    private static void worldHook(int f) {
        Quarter q = ClientState.settings.quarters.get(4);
        Quarter base = demo.get(4);
        int off;
        if (f < 30) off = 0;
        else if (f < 70) off = (f - 30) / 3;
        else if (f < 90) off = 13;
        else off = Math.max(0, 13 - (f - 90) / 3);
        int dz = base.z0 + off - q.z0; // на юг — там нет соседей
        if (dz != 0) { q.moveBy(0, dz); ClientState.requestRecompute(); }
    }

    private static Path worldEditPath() {
        Quarter q = demo.get(4);
        double x = (q.x0 + q.x1) / 2.0, z = (q.z0 + q.z1) / 2.0, g = qy(4);
        return dolly(new double[]{x - 24, g + 46, z + 78}, new double[]{x + 8, g + 40, z + 72},
                new double[]{x, g + 2, z + 4}, new double[]{x + 4, g + 2, z + 8});
    }

    private static Path cityStreetPath() {
        // по общей дороге между кварталами, на уровне глаз
        Quarter a = demo.get(0);
        double g = qy(0), x = a.x1 + 7.5, y = g + 2.8;
        return dolly(new double[]{x, y, a.z0 - 4}, new double[]{x, y + 1, a.z1 + 30},
                new double[]{x + 2, g + 8, a.z0 + 30}, new double[]{x, g + 6, a.z1 + 70});
    }

    private static Path constructionPath() {
        Quarter q = demo.get(4);
        double x = (q.x0 + q.x1) / 2.0, z = (q.z0 + q.z1) / 2.0, g = qy(4);
        return orbit(x, g, z, 55, 30, 60, 120, g + 22);
    }
}
