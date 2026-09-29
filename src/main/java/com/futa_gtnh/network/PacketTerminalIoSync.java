package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TileEntitySharedTerminal;
import com.futa_gtnh.client.ClientTerminalIo;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：某个共享终端方块「六个面怎么搬东西」的配置。
 *
 * <p>
 * 单向包（改动是走 {@link PacketStorageAction} 的 {@code SET_TERMINAL_IO} 上行的，
 * 那条路本来就有「必须真的开着这个界面」的校验和限流）。
 * 这个包只在两个时机发：玩家打开终端界面时推一份，以及配置被改动之后回一份。
 *
 * <p>
 * 带坐标和维度：客户端要用它确认「这份配置说的就是我正开着的那个终端」，
 * 免得在两地之间来回走的时候把 A 的配置显示到 B 的界面上。
 */
public class PacketTerminalIoSync implements IMessage {

    private int dimension;
    private int x;
    private int y;
    private int z;
    private NBTTagCompound config;

    public PacketTerminalIoSync() {}

    public static PacketTerminalIoSync of(TileEntitySharedTerminal terminal) {
        if (terminal == null || terminal.getWorldObj() == null) return null;

        PacketTerminalIoSync packet = new PacketTerminalIoSync();
        packet.dimension = terminal.getWorldObj().provider.dimensionId;
        packet.x = terminal.xCoord;
        packet.y = terminal.yCoord;
        packet.z = terminal.zCoord;
        packet.config = terminal.getIo()
            .writeToNbt();
        return packet;
    }

    public static void send(EntityPlayerMP player, TileEntitySharedTerminal terminal) {
        PacketTerminalIoSync packet = of(terminal);
        if (packet != null) NetworkHandler.INSTANCE.sendTo(packet, player);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dimension = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        config = buf.readBoolean() ? ByteBufUtils.readTag(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimension);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(config != null);
        if (config != null) {
            ByteBufUtils.writeTag(buf, config);
        }
    }

    public static class Handler implements IMessageHandler<PacketTerminalIoSync, IMessage> {

        @Override
        public IMessage onMessage(PacketTerminalIoSync message, MessageContext ctx) {
            try {
                ClientTerminalIo.setConfig(message.dimension, message.x, message.y, message.z, message.config);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("共享存储：处理终端面配置同步失败", t);
            }
            return null;
        }
    }
}
