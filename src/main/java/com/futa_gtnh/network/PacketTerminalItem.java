package com.futa_gtnh.network;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.ItemKey;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** 告诉客户端当前打开的方块终端正在向物品管道输出哪种物品。 */
public class PacketTerminalItem implements IMessage {

    private NBTTagCompound itemTag;

    public PacketTerminalItem() {}

    public PacketTerminalItem(ItemKey key) {
        itemTag = key == null ? null : key.writeToNbt();
    }

    public ItemKey getItemKey() {
        return itemTag == null ? null : ItemKey.readFromNbt(itemTag);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        itemTag = buf.readBoolean() ? ByteBufUtils.readTag(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(itemTag != null);
        if (itemTag != null) {
            ByteBufUtils.writeTag(buf, itemTag);
        }
    }

    public static class Handler implements IMessageHandler<PacketTerminalItem, IMessage> {

        @Override
        public IMessage onMessage(PacketTerminalItem message, MessageContext ctx) {
            try {
                com.futa_gtnh.client.ClientTerminalState.setOutputItem(message.getItemKey());
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("共享存储：处理终端物品同步包失败", t);
            }
            return null;
        }
    }
}
