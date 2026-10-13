package com.citygen.net;

import com.citygen.server.JobManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class ActionPacket {
    public enum Action { UNDO, CANCEL }

    final Action action;

    public ActionPacket(Action action) { this.action = action; }
    public ActionPacket(FriendlyByteBuf buf) { this.action = buf.readEnum(Action.class); }
    public void encode(FriendlyByteBuf buf) { buf.writeEnum(action); }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sp = ctx.get().getSender();
        if (sp == null) return;
        switch (action) {
            case UNDO -> JobManager.undo(sp);
            case CANCEL -> JobManager.cancel(sp);
        }
    }
}
