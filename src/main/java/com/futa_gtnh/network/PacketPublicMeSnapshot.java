package com.futa_gtnh.network;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public final class PacketPublicMeSnapshot implements IMessage {

    public int window, sequence;
    public long session;
    public NBTTagCompound data;

    public PacketPublicMeSnapshot() {}

    public PacketPublicMeSnapshot(int window, long session, int sequence, NBTTagCompound data) {
        this.window = window;
        this.session = session;
        this.sequence = sequence;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        if (buf.readableBytes() > NetworkHandler.CHUNK_SIZE || buf.readableBytes() < 16) return;
        try {
            window = buf.readInt();
            session = buf.readLong();
            sequence = buf.readInt();
            data = ByteBufUtils.readTag(buf);
            if (buf.isReadable()) data = null;
        } catch (RuntimeException malformed) {
            data = null;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(window)
            .writeLong(session)
            .writeInt(sequence);
        ByteBufUtils.writeTag(buf, data);
    }

    public static class Handler implements IMessageHandler<PacketPublicMeSnapshot, IMessage> {

        @Override
        public IMessage onMessage(PacketPublicMeSnapshot message, MessageContext context) {
            if (message.data != null) FutaGtnhMod.proxy.onPublicMeSnapshot(context.netHandler, message);
            return null;
        }
    }
}
