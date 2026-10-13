package com.citygen.client;

import com.citygen.CityGen;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки интерфейса редактора (config/citygen-ui.json). */
public final class ClientPrefs {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClientPrefs INSTANCE;

    public int accent = 0;
    public int opacity = 94;
    public int panelDX = 0, panelDY = 0;
    public int tab = 0;
    public boolean mapIso = false;
    public boolean previewOnOpen = true;
    public boolean hints = true;

    public static ClientPrefs get() {
        if (INSTANCE == null) INSTANCE = load();
        return INSTANCE;
    }

    private static Path file() { return FMLPaths.CONFIGDIR.get().resolve("citygen-ui.json"); }

    private static ClientPrefs load() {
        try {
            if (Files.exists(file())) {
                ClientPrefs p = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), ClientPrefs.class);
                if (p != null) return p;
            }
        } catch (Exception e) {
            CityGen.LOG.warn("CityGen: cannot read UI prefs", e);
        }
        return new ClientPrefs();
    }

    public static void save() {
        try {
            Files.writeString(file(), GSON.toJson(get()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            CityGen.LOG.warn("CityGen: cannot save UI prefs", e);
        }
    }
}
