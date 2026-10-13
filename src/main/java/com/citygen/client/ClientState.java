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

    public static void build() {
        if (anchor == null) anchorHere();
        Network.CHANNEL.sendToServer(new BuildPacket(settings.copy(), anchor, look));
        previewActive = false;
        if (settings.newSeedEachBuild) settings.seed = new Random().nextLong() & 0xFFFFFFFFL;
        save();
        recompute();
    }

    public static void undo() { Network.CHANNEL.sendToServer(new ActionPacket(ActionPacket.Action.UNDO)); }
    public static void cancel() { Network.CHANNEL.sendToServer(new ActionPacket(ActionPacket.Action.CANCEL)); }
}
