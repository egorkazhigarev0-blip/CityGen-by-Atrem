package com.citygen.server;

import com.citygen.CityGen;
import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

@Mod.EventBusSubscriber(modid = CityGen.MODID)
public final class ServerEvents {
    private ServerEvents() {}

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) JobManager.tick(server);
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent e) {
        JobManager.clear();
    }

    @SubscribeEvent
    public static void onCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("citygen")
                .then(Commands.literal("undo").executes(c -> { JobManager.undo(c.getSource().getPlayerOrException()); return Command.SINGLE_SUCCESS; }))
                .then(Commands.literal("cancel").executes(c -> { JobManager.cancel(c.getSource().getPlayerOrException()); return Command.SINGLE_SUCCESS; })));
    }
}
