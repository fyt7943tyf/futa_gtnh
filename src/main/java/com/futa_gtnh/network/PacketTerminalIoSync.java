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
    /** 六个面里哪几面真的挨着能搬东西的方块：位 i = ForgeDirection.getOrientation(i)。 */
    private int itemMask;
    private int fluidMask;
    /**
     * 每面邻居方块是什么（注册名 + metadata），用来在界面里把那圈方块画成<b>它本来的样子</b> ——
     * 边上放的是木箱子就该画成木箱子，而不是画成又一个共享终端。
     *
     * <p>
     * 发的是注册名字符串而不是数字 ID：GTNH 这边方块 ID 空间是被 EndlessIDs 改过的，
     * 名字才是两端一致的标识。空字符串表示这一面没有东西。
     */
    private final String[] neighbourNames = new String[6];
    private final byte[] neighbourMetas = new byte[6];

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
        packet.itemMask = terminal.getTargetMask(false);
        packet.fluidMask = terminal.getTargetMask(true);

        // 邻居长什么样：注册名 + metadata（拿不到就留空，客户端会退化成纯色半透明方块）
        net.minecraft.world.World world = terminal.getWorldObj();
        for (net.minecraftforge.common.util.ForgeDirection face : net.minecraftforge.common.util.ForgeDirection.VALID_DIRECTIONS) {
            int bx = terminal.xCoord + face.offsetX;
            int by = terminal.yCoord + face.offsetY;
            int bz = terminal.zCoord + face.offsetZ;
            net.minecraft.block.Block block = world.getBlock(bx, by, bz);
            if (block == null || block == net.minecraft.init.Blocks.air) continue;

            String name = net.minecraft.block.Block.blockRegistry.getNameForObject(block);
            packet.neighbourNames[face.ordinal()] = name == null ? "" : name;
            packet.neighbourMetas[face.ordinal()] = (byte) world.getBlockMetadata(bx, by, bz);
        }

        // 一次性诊断：这一份同步包里到底算了什么。
        //
        // 「配置界面里 3D 预览不显示邻居」这类反馈最难办的地方是：到底是服务端没算出来、
        // 还是客户端没画出来，从界面上看都是「空的」。所以把六个面各自看到了什么方块、
        // 算出来的两个掩码各是什么，一次打在日志里 —— 每次打开界面只打一行，不刷屏。
        try {
            StringBuilder detail = new StringBuilder();
            for (net.minecraftforge.common.util.ForgeDirection face : net.minecraftforge.common.util.ForgeDirection.VALID_DIRECTIONS) {
                String name = packet.neighbourNames[face.ordinal()];
                detail.append(' ')
                    .append(face.name())
                    .append('=')
                    .append(name == null || name.isEmpty() ? "(空)" : name);
            }
            FutaGtnhMod.LOG.info(
                "共享终端 IO 诊断：({} {} {}) 维度 {} 物品掩码={} 流体掩码={} 邻居：{}",
                terminal.xCoord,
                terminal.yCoord,
                terminal.zCoord,
                packet.dimension,
                Integer.toBinaryString(packet.itemMask),
                Integer.toBinaryString(packet.fluidMask),
                detail.toString());
        } catch (Throwable ignored) {
            // 只是日志
        }
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
        itemMask = buf.readInt();
        fluidMask = buf.readInt();
        for (int i = 0; i < 6; i++) {
            neighbourNames[i] = ByteBufUtils.readUTF8String(buf);
            neighbourMetas[i] = buf.readByte();
        }
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
        buf.writeInt(itemMask);
        buf.writeInt(fluidMask);
        for (int i = 0; i < 6; i++) {
            ByteBufUtils.writeUTF8String(buf, neighbourNames[i] == null ? "" : neighbourNames[i]);
            buf.writeByte(neighbourMetas[i]);
        }
    }

    public static class Handler implements IMessageHandler<PacketTerminalIoSync, IMessage> {

        @Override
        public IMessage onMessage(PacketTerminalIoSync message, MessageContext ctx) {
            try {
                ClientTerminalIo.setConfig(
                    message.dimension,
                    message.x,
                    message.y,
                    message.z,
                    message.config,
                    message.itemMask,
                    message.fluidMask,
                    message.neighbourNames,
                    message.neighbourMetas);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("共享存储：处理终端面配置同步失败", t);
            }
            return null;
        }
    }
}
