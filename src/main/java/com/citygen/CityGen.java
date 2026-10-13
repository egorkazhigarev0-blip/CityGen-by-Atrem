package com.citygen;

import com.citygen.net.Network;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * CityGen — внутриигровой генератор реалистичных зданий.
 * Клавиша G (по умолчанию) открывает редактор; сервер строит здания порциями,
 * чтобы игра не зависала, и хранит историю для отмены.
 */
@Mod(CityGen.MODID)
public class CityGen {
    public static final String MODID = "citygen";
    public static final Logger LOG = LogUtils.getLogger();

    public CityGen() {
        Network.register();
    }
}
