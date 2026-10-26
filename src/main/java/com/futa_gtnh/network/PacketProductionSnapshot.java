package com.futa_gtnh.network;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** One bounded page of groups, devices and resources, scoped to an individual GUI session. */
public final class PacketProductionSnapshot implements IMessage {

    public int window, sequence;
    public long session;
    public NBTTagCompound data;

    public PacketProductionSnapshot() {}

    public PacketProductionSnapshot(int window, long session, int sequence, NBTTagCompound data) {
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

    public static class Handler implements IMessageHandler<PacketProductionSnapshot, IMessage> {

        @Override
        public IMessage onMessage(PacketProductionSnapshot message, MessageContext context) {
            if (message.data != null) FutaGtnhMod.proxy.onProductionSnapshot(context.netHandler, message);
            return null;
        }
    }
}
