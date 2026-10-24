package com.futa_gtnh.network;

import com.futa_gtnh.common.SwiftStepAuraStats;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Server-authoritative aura feedback. Only the local client snapshot is retained. */
public final class PacketSwiftStepAuraStatus implements IMessage {

    public static volatile PacketSwiftStepAuraStatus latest;
    public int crops, crosses, botania, updates, blocked;
    public boolean limited;
    public long receivedAt;

    public PacketSwiftStepAuraStatus() {}

    public PacketSwiftStepAuraStatus(SwiftStepAuraStats stats) {
        crops = stats.crops;
        crosses = stats.crosses;
        botania = stats.botania;
        updates = stats.updates;
        blocked = stats.blocked;
        limited = stats.limited;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        crops = buf.readInt();
        crosses = buf.readInt();
        botania = buf.readInt();
        updates = buf.readInt();
        blocked = buf.readInt();
        limited = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(crops);
        buf.writeInt(crosses);
        buf.writeInt(botania);
        buf.writeInt(updates);
        buf.writeInt(blocked);
        buf.writeBoolean(limited);
    }

    public static final class Handler implements IMessageHandler<PacketSwiftStepAuraStatus, IMessage> {

        @Override
        public IMessage onMessage(PacketSwiftStepAuraStatus message, MessageContext ctx) {
            message.receivedAt = System.currentTimeMillis();
            latest = message;
            return null;
        }
    }
}
