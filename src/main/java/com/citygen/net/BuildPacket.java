package com.citygen.net;

import com.citygen.gen.GenSettings;
import com.citygen.server.JobManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class BuildPacket {
    final GenSettings settings;
    final BlockPos anchor;
    final Direction look;

    public BuildPacket(GenSettings settings, BlockPos anchor, Direction look) {
        this.settings = settings; this.anchor = anchor; this.look = look;
    }

    public BuildPacket(FriendlyByteBuf buf) {
        this.settings = GenSettings.read(buf);
        this.anchor = buf.readBlockPos();
        this.look = buf.readEnum(Direction.class);
    }

    public void encode(FriendlyByteBuf buf) {
        settings.write(buf);
        buf.writeBlockPos(anchor);
        buf.writeEnum(look);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ServerPlayer sp = ctx.get().getSender();
        if (sp == null) return;
        Direction d = look.getAxis().isHorizontal() ? look : sp.getDirection();
        JobManager.startBuild(sp, settings, anchor, d);
    }
}
