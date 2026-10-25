package com.futa_gtnh.network;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Only one selected resource and two bounded pages, rather than a broadcast of the whole ledger. */
public final class PacketSupplySnapshot implements IMessage {

    public int window, sequence;
    public NBTTagCompound data;

    public PacketSupplySnapshot() {}

    public PacketSupplySnapshot(int window, int sequence, NBTTagCompound data) {
        this.window = window;
        this.sequence = sequence;
        this.data = data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        if (buf.readableBytes() > NetworkHandler.CHUNK_SIZE || buf.readableBytes() < 8) return;
        window = buf.readInt();
        sequence = buf.readInt();
        data = ByteBufUtils.readTag(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(window)
            .writeInt(sequence);
        ByteBufUtils.writeTag(buf, data);
    }

    public static class Handler implements IMessageHandler<PacketSupplySnapshot, IMessage> {

        @Override
        public IMessage onMessage(PacketSupplySnapshot message, MessageContext context) {
            if (message.data != null) FutaGtnhMod.proxy.onSupplySnapshot(context.netHandler, message);
            return null;
        }
    }
}
