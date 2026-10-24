package com.futa_gtnh.network;

import java.util.UUID;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** S2C: the connection's actual server player UUID, independent of online mode. */
public final class PacketCosmeticArmorIdentity implements IMessage {

    private UUID playerId;

    public PacketCosmeticArmorIdentity() {}

    public PacketCosmeticArmorIdentity(UUID playerId) {
        this.playerId = playerId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        playerId = new UUID(buf.readLong(), buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(playerId.getMostSignificantBits());
        buf.writeLong(playerId.getLeastSignificantBits());
    }

    public static final class Handler implements IMessageHandler<PacketCosmeticArmorIdentity, IMessage> {

        @Override
        public IMessage onMessage(PacketCosmeticArmorIdentity message, MessageContext ctx) {
            FutaGtnhMod.proxy.onCosmeticArmorIdentity(ctx.netHandler, message.playerId);
            return null;
        }
    }
}
