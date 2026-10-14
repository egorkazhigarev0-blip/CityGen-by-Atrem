package com.citygen.client;

import com.citygen.CityGen;
import com.citygen.gen.GenSettings;
import com.citygen.gen.Layout;
import com.citygen.net.ActionPacket;
import com.citygen.net.BuildPacket;
import com.citygen.net.Network;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/** Состояние редактора на клиенте: настройки, точка привязки и рассчитанная раскладка для превью. */
public final class ClientState {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public static GenSettings settings = load();
    public static boolean previewActive;
    public static BlockPos anchor;
    public static Direction look = Direction.NORTH;
    public static ResourceKey<Level> dimension;
    public static Layout layout;
    /** Выбранный квартал (редактор города и правка в мире). */
    public static int selected = -1;
    /** Режим правки кварталов прямо в мире (без открытого окна). */
    public static boolean worldEdit;
    private static long dirtyAt = -1;

    private ClientState() {}

    private static Path file() { return FMLPaths.CONFIGDIR.get().resolve("citygen-editor.json"); }

    private static GenSettings load() {
        try {
            Path f = file();
            if (Files.exists(f)) {
                GenSettings s = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), GenSettings.class);
                if (s != null) return s.sanitize();
            }
        } catch (Exception e) {
            CityGen.LOG.warn("CityGen: cannot read editor settings", e);
        }
        GenSettings s = new GenSettings();
        s.seed = new Random().nextLong() & 0xFFFFFFFFL;
        return s;
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(settings), StandardCharsets.UTF_8);
        } catch (Exception e) {
            CityGen.LOG.warn("CityGen: cannot save editor settings", e);
        }
    }

    public static void resetSettings() {
        long seed = settings.seed;
        settings = new GenSettings();
        settings.seed = seed;
        recompute();
    }

    public static void anchorHere() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        anchor = mc.player.blockPosition();
        look = mc.player.getDirection();
        dimension = mc.player.level().dimension();
        previewActive = true;
        recompute();
    }

    /** При открытии: новая привязка, если превью выключено, игрок ушёл далеко или сменил измерение. */
    public static void onEditorOpen() {
        Minecraft mc = Minecraft.getInstance();
        boolean far = anchor != null && mc.player != null
                && (mc.player.blockPosition().distManhattan(new BlockPos(anchor.getX(), mc.player.getBlockY(), anchor.getZ())) > 24
                || mc.player.level().dimension() != dimension);
        if (!previewActive || anchor == null || far) {
            if (ClientPrefs.get().previewOnOpen || previewActive) anchorHere();
            else { anchorHere(); previewActive = false; }
        } else recompute();
    }

    public static void recompute() {
        Minecraft mc = Minecraft.getInstance();
        if (anchor == null) { layout = null; return; }
        try {
            layout = Layout.compute(settings, anchor, look, mc.level);
        } catch (Exception e) {
            CityGen.LOG.error("CityGen: layout error", e);
            layout = null;
        }
        MapPreview.markDirty();
    }

    /** Отложенный пересчёт превью: при частых изменениях (перетаскивание, удержание клавиш) — не чаще, чем раз в 150 мс. */
    public static void requestRecompute() { dirtyAt = System.currentTimeMillis(); }

    public static void tick() {
        if (dirtyAt >= 0 && System.currentTimeMillis() - dirtyAt > 150) { dirtyAt = -1; recompute(); }
    }

    public static GenSettings.Quarter selectedQuarter() {
        return selected >= 0 && selected < settings.quarters.size() ? settings.quarters.get(selected) : null;
    }

    /** Сдвигает все кварталы так, чтобы город начинался перед игроком. Возвращает новый центр города. */
    public static int[] moveCityToPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || settings.quarters.isEmpty()) return null;
        int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (GenSettings.Quarter q : settings.quarters) { x0 = Math.min(x0, q.x0); z0 = Math.min(z0, q.z0); x1 = Math.max(x1, q.x1); z1 = Math.max(z1, q.z1); }
        Direction f = mc.player.getDirection();
        int w = x1 - x0 + 1, d = z1 - z0 + 1;
        int cx = mc.player.getBlockX() + f.getStepX() * (w / 2 + 14), cz = mc.player.getBlockZ() + f.getStepZ() * (d / 2 + 14);
        int dx = cx - (x0 + w / 2), dz = cz - (z0 + d / 2);
        for (GenSettings.Quarter q : settings.quarters) q.moveBy(dx, dz);
        anchor = mc.player.blockPosition();
        look = f;
        dimension = mc.player.level().dimension();
        recompute();
        return new int[]{cx, cz};
    }

    /** Построить один квартал (остальные остаются в плане). */
    public static void buildQuarter(int qi) {
        settings.arrangement = GenSettings.Arrangement.CITY;
        settings.buildOnly = qi;
        build();
        settings.buildOnly = -1;
        save();
    }

    public static void buildCity() {
        settings.arrangement = GenSettings.Arrangement.CITY;
        settings.buildOnly = -1;
        build();
    }

    public static void build() {
        if (anchor == null) anchorHere();
        Network.CHANNEL.sendToServer(new BuildPacket(settings.copy(), anchor, look));
        previewActive = false;
        // в городе сид не меняем: иначе у ещё не построенных кварталов поменялись бы здания
        if (settings.newSeedEachBuild && settings.arrangement != GenSettings.Arrangement.CITY)
            settings.seed = new Random().nextLong() & 0xFFFFFFFFL;
        save();
        recompute();
    }

    public static void undo() { Network.CHANNEL.sendToServer(new ActionPacket(ActionPacket.Action.UNDO)); }
    public static void cancel() { Network.CHANNEL.sendToServer(new ActionPacket(ActionPacket.Action.CANCEL)); }
}
