package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TileEntityIoNode;
import com.futa_gtnh.client.ClientIoNode;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：某个 IO 节点方块「六个面怎么搬东西」的配置快照。
 *
 * <p>
 * 结构和 {@link PacketTerminalIoSync} 一致（坐标 + 维度 + 整份配置 + 连接掩码 + 邻居名），
 * 只是宿主换成 IO 节点、客户端缓存换成 {@link ClientIoNode}。发的时机也一样：
 * 玩家打开节点界面时推一份，配置被改动之后回一份权威值。
 */
public class PacketIoNodeSync implements IMessage {

    private int dimension;
    private int x;
    private int y;
    private int z;
    private NBTTagCompound config;
    /** 六个面里哪几面真的接着能搬东西的方块：位 i = ForgeDirection.getOrientation(i)。 */
    private int itemMask;
    private int fluidMask;
    /** 每面邻居方块的注册名（空串 = 没东西 / 没连接），界面标题里显示邻块是什么。 */
    private final String[] neighbourNames = new String[6];

    public PacketIoNodeSync() {}

    public static PacketIoNodeSync of(TileEntityIoNode node) {
        if (node == null || node.getWorldObj() == null) return null;

        // 界面要画「这一面接的是什么」，先保证掩码是新鲜的
        node.refreshConnections();

        PacketIoNodeSync packet = new PacketIoNodeSync();
        packet.dimension = node.getWorldObj().provider.dimensionId;
        packet.x = node.xCoord;
        packet.y = node.yCoord;
        packet.z = node.zCoord;
        packet.config = node.getIo()
            .writeToNbt();
        packet.itemMask = node.getItemMask();
        packet.fluidMask = node.getFluidMask();

        net.minecraft.world.World world = node.getWorldObj();
        for (net.minecraftforge.common.util.ForgeDirection face : net.minecraftforge.common.util.ForgeDirection.VALID_DIRECTIONS) {
            packet.neighbourNames[face.ordinal()] = neighbourName(world, node, face);
        }
        return packet;
    }

    private static String neighbourName(net.minecraft.world.World world, TileEntityIoNode node,
        net.minecraftforge.common.util.ForgeDirection face) {
        int bx = node.xCoord + face.offsetX;
        int by = node.yCoord + face.offsetY;
        int bz = node.zCoord + face.offsetZ;
        if (world.getBlock(bx, by, bz) == null || world.getBlock(bx, by, bz) == net.minecraft.init.Blocks.air) {
            return "";
        }
        // 发注册名字符串而不是数字 ID：GTNH 的方块 ID 空间被 EndlessIds 改过，名字才是两端一致的
        String name = net.minecraft.block.Block.blockRegistry.getNameForObject(world.getBlock(bx, by, bz));
        return name == null ? "" : name;
    }

    public static void send(EntityPlayerMP player, TileEntityIoNode node) {
        PacketIoNodeSync packet = of(node);
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
        }
    }

    public static class Handler implements IMessageHandler<PacketIoNodeSync, IMessage> {

        @Override
        public IMessage onMessage(PacketIoNodeSync message, MessageContext ctx) {
            try {
                ClientIoNode.setConfig(
                    message.dimension,
                    message.x,
                    message.y,
                    message.z,
                    message.config,
                    message.itemMask,
                    message.fluidMask,
                    message.neighbourNames);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("IO 节点：处理配置同步失败", t);
            }
            return null;
        }
    }
}
