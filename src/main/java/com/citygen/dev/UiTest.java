package com.citygen.dev;

import com.citygen.CityGen;
import com.citygen.client.ClientPrefs;
import com.citygen.client.ClientState;
import com.citygen.client.EditorScreen;
import com.citygen.gen.BuildingType;
import com.citygen.gen.GenSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Инструмент разработчика: при переменной окружения CITYGEN_UITEST делает скриншоты всех вкладок редактора и выходит. */
@Mod.EventBusSubscriber(modid = CityGen.MODID, value = Dist.CLIENT)
public final class UiTest {
    private static final boolean ENABLED = System.getenv("CITYGEN_UITEST") != null;
    private static final int TABS = 7;
    private static int stage = -1, wait;

    private UiTest() {}

    @SubscribeEvent
    public static void onTick(TickEvent.ClientTickEvent e) {
        if (!ENABLED || e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (stage == -1) {
            if (mc.screen instanceof TitleScreen) { stage = 0; wait = 60; setup(); }
            return;
        }
        if (--wait > 0) return;
        if (stage < TABS * 2) {
            int tab = stage / 2;
            if (stage % 2 == 0) {
                ClientPrefs.get().tab = tab;
                mc.setScreen(new EditorScreen());
                wait = tab == 5 ? 100 : 20;
            } else {
                Screenshot.grab(mc.gameDirectory, "citygen_tab" + tab + ".png", mc.getMainRenderTarget(), c -> {});
                wait = 5;
            }
            stage++;
        } else if (stage == TABS * 2) {
            CityGen.LOG.info("UITEST DONE");
            stage++;
            mc.stop();
        }
    }

    private static void setup() {
        GenSettings s = ClientState.settings;
        s.type = BuildingType.MIXED;
        s.arrangement = GenSettings.Arrangement.PLANNER;
        s.rows = 3; s.cols = 4;
        s.cells = new byte[]{0, 0, 9, 0, 1, 0, 0, 5, 10, 4, 11, 6};
        s.width = 26; s.depth = 22;
        s.street = GenSettings.StreetMode.ROAD;
        ClientPrefs.get().previewOnOpen = true;
        ClientState.anchor = new BlockPos(0, 64, 0);
        ClientState.look = Direction.NORTH;
        ClientState.previewActive = true;
        ClientState.recompute();
    }
}
