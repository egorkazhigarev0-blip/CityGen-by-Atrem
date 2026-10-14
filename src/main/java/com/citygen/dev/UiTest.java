package com.citygen.dev;

import com.citygen.CityGen;
import com.citygen.client.*;
import com.citygen.gen.BuildingType;
import com.citygen.gen.GenSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Инструмент разработчика: при переменной окружения CITYGEN_UITEST делает скриншоты всех вкладок редактора,
 * затем создаёт мир и снимает редактор города (карта местности, выбранный квартал) и правку в мире. Потом выходит.
 */
@Mod.EventBusSubscriber(modid = CityGen.MODID, value = Dist.CLIENT)
public final class UiTest {
    private static final boolean ENABLED = System.getenv("CITYGEN_UITEST") != null;
    private static final int TABS = 7;
    private static final List<Object> steps = new ArrayList<>();
    private static int idx = -1, wait;

    private UiTest() {}

    private record Wait(BooleanSupplier until, int timeout) {}

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (!ENABLED || e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (idx == -1) {
            if (mc.screen instanceof TitleScreen) { script(); idx = 0; wait = 60; }
            return;
        }
        if (wait > 0) { wait--; return; }
        while (idx < steps.size()) {
            Object st = steps.get(idx);
            if (st instanceof Integer n) { idx++; wait = n; return; }
            if (st instanceof Wait w) {
                if (w.until.getAsBoolean()) { idx++; continue; }
                return;
            }
            idx++;
            ((Runnable) st).run();
            return;
        }
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        steps.add((Runnable) () -> Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), c -> {}));
        steps.add(5);
    }

    private static void script() {
        Minecraft mc = Minecraft.getInstance();
        steps.add((Runnable) UiTest::setup);
        for (int tab = 0; tab < TABS; tab++) {
            final int t = tab;
            steps.add((Runnable) () -> { ClientPrefs.get().tab = t; mc.setScreen(new EditorScreen()); });
            steps.add(t == 5 ? 100 : 20);
            shot("citygen_tab" + tab);
        }
        // мир для редактора города
        steps.add((Runnable) () -> {
            mc.setScreen(null);
            mc.options.pauseOnLostFocus = false;
            mc.options.renderDistance().set(12);
            mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            String name = "CityGenUi" + System.currentTimeMillis() % 100000;
            LevelSettings ls = new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(),
                    WorldDataConfiguration.DEFAULT);
            mc.createWorldOpenFlows().createFreshLevel(name, ls, new WorldOptions(20261014L, true, false), WorldPresets::createNormalWorldDimensions);
        });
        steps.add(new Wait(() -> mc.player != null && mc.level != null && mc.screen == null, 0));
        steps.add(200);
        steps.add((Runnable) () -> {
            mc.player.connection.sendCommand("time set 1000");
            mc.player.connection.sendCommand("gamerule doDaylightCycle false");
            GenSettings s = ClientState.settings;
            BlockPos p = mc.player.blockPosition();
            DemoCity.make(s, p.getX(), p.getZ() - 60);
            ClientState.anchor = p;
            ClientState.look = Direction.NORTH;
            ClientState.dimension = mc.level.dimension();
            ClientState.selected = -1;
            ClientState.previewActive = true;
            ClientState.recompute();
            mc.setScreen(new CityEditorScreen(new EditorScreen()));
        });
        steps.add(new Wait(() -> !SatMap.busy(), 0));
        steps.add(20);
        shot("citygen_city");
        steps.add((Runnable) () -> { ClientState.selected = 0; mc.setScreen(new CityEditorScreen(new EditorScreen())); });
        steps.add(20);
        shot("citygen_city_selected");
        // крупное окно (типичный монитор с масштабом интерфейса 2)
        steps.add((Runnable) () -> mc.getWindow().setWindowed(1280, 720));
        steps.add(30);
        steps.add((Runnable) () -> { ClientState.selected = -1; mc.setScreen(new CityEditorScreen(new EditorScreen())); });
        steps.add(20);
        shot("citygen_city_big");
        steps.add((Runnable) () -> { ClientState.selected = 4; mc.setScreen(new CityEditorScreen(new EditorScreen())); });
        steps.add(20);
        shot("citygen_city_big_selected");
        steps.add((Runnable) () -> {
            mc.setScreen(null);
            mc.player.connection.sendCommand("gamemode spectator");
            BlockPos p = mc.player.blockPosition();
            mc.player.connection.sendCommand("tp @s " + p.getX() + " " + (p.getY() + 45) + " " + (p.getZ() + 40) + " 180 40");
        });
        steps.add(80);
        steps.add((Runnable) () -> { ClientState.selected = 3; ClientState.worldEdit = true; mc.gui.getChat().clearMessages(false); });
        steps.add(40);
        shot("citygen_world_edit");
        steps.add((Runnable) () -> { ClientState.worldEdit = false; ClientPrefs.get().tab = 4; mc.setScreen(new EditorScreen()); });
        steps.add(20);
        shot("citygen_tab4_world");
        steps.add((Runnable) () -> { ClientPrefs.get().tab = 6; mc.setScreen(new EditorScreen()); });
        steps.add(20);
        shot("citygen_tab6_world");
        steps.add((Runnable) () -> { ClientPrefs.get().tab = 0; mc.setScreen(new EditorScreen()); });
        steps.add(20);
        shot("citygen_tab0_world");
        // постройка города и проверка улиц глазами
        final int[] c = new int[3];
        steps.add((Runnable) () -> {
            mc.setScreen(null);
            ClientState.worldEdit = false;
            com.citygen.server.JobManager.budgetOverrideNs = 25_000_000L;
            GenSettings s = ClientState.settings;
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (GenSettings.Quarter q : s.quarters) { x0 = Math.min(x0, q.x0); z0 = Math.min(z0, q.z0); x1 = Math.max(x1, q.x1); z1 = Math.max(z1, q.z1); }
            c[0] = (x0 + x1) / 2; c[1] = (z0 + z1) / 2;
            c[2] = ClientState.layout != null && ClientState.layout.quarterGround.length > 0 ? ClientState.layout.quarterGround[0] : 70;
            ClientState.buildCity();
        });
        steps.add(40);
        steps.add(new Wait(() -> !com.citygen.server.JobManager.busy(), 0));
        steps.add((Runnable) () -> {
            mc.options.hideGui = true;
            mc.player.connection.sendCommand("tp @s " + c[0] + " " + (c[2] + 95) + " " + (c[1] + 120) + " 180 42");
        });
        steps.add(120);
        shot("citygen_built_aerial");
        steps.add((Runnable) () -> {
            GenSettings.Quarter a = ClientState.settings.quarters.get(0);
            int gx = a.x1 + 7, gz = a.z1 + 7; // перекрёсток между четырьмя кварталами
            mc.player.connection.sendCommand("tp @s " + (gx + 0.5) + " " + (c[2] + 2.6) + " " + (gz + 16.5) + " 180 8");
        });
        steps.add(100);
        shot("citygen_built_crossing");
        steps.add((Runnable) () -> {
            GenSettings.Quarter a = ClientState.settings.quarters.get(0);
            mc.player.connection.sendCommand("tp @s " + (a.x0 + 10.5) + " " + (c[2] + 2.6) + " " + (a.z1 + 7.5) + " -90 5");
        });
        steps.add(100);
        shot("citygen_built_street");
        steps.add((Runnable) () -> {
            GenSettings.Quarter q = ClientState.settings.quarters.get(4);
            mc.player.connection.sendCommand("tp @s " + (q.x0 + q.w() / 2.0) + " " + (c[2] + 40) + " " + (q.z1 + 45.5) + " 180 30");
        });
        steps.add(100);
        shot("citygen_built_construction");
        steps.add((Runnable) () -> { CityGen.LOG.info("UITEST DONE"); mc.stop(); });
    }

    private static void setup() {
        GenSettings s = ClientState.settings;
        s.type = BuildingType.MIXED;
        DemoCity.make(s, 0, -40);
        s.width = 26; s.depth = 22;
        s.street = GenSettings.StreetMode.ROAD;
        ClientPrefs.get().previewOnOpen = true;
        ClientState.anchor = new BlockPos(0, 64, 0);
        ClientState.look = Direction.NORTH;
        ClientState.previewActive = true;
        ClientState.recompute();
    }
}
