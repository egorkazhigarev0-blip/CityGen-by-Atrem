package com.citygen.net;

import com.citygen.CityGen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class Network {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CityGen.MODID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    private Network() {}

    public static void register() {
        int id = 0;
        CHANNEL.messageBuilder(BuildPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(BuildPacket::encode).decoder(BuildPacket::new).consumerMainThread(BuildPacket::handle).add();
        CHANNEL.messageBuilder(ActionPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ActionPacket::encode).decoder(ActionPacket::new).consumerMainThread(ActionPacket::handle).add();
    }
}
