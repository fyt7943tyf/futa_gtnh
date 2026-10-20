package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.ClientStorageBus;
import com.futa_gtnh.storagebus.StorageBusConfig;
import com.futa_gtnh.storagebus.StorageBusLink;
import com.futa_gtnh.storagebus.TileEntityStorageBus;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：一条存储总线的完整配置 + 目标信息。
 *
 * <p>
 * 和 IO 节点的 {@link PacketIoNodeSync} 同一套做法：界面自己不保存权威状态，
 * 每次打开 / 改动后都等服务端推一份。目标名只是为了界面标题好看，
 * 真正的读写判定仍在服务端。
 */
public class PacketStorageBusSync implements IMessage {

    private int dimension;
    private int x;
    private int y;
    private int z;
    private int side;
    private int faceMask;
    private NBTTagCompound config;
    private String targetName = "";
    private boolean itemTarget;
    private boolean fluidTarget;

    public PacketStorageBusSync() {}

    public static PacketStorageBusSync of(TileEntityStorageBus bus, int requestedSide) {
        if (bus == null || bus.getWorldObj() == null) return null;
        int mask = bus.getFaceMask();
        if (mask == 0) return null;
        int side = requestedSide;
        if (side < 0 || side >= TileEntityStorageBus.FACES || (mask & (1 << side)) == 0) {
            side = Integer.numberOfTrailingZeros(mask);
        }
        StorageBusConfig config = bus.getConfig(side);
        StorageBusLink link = bus.getLink(side);
        if (config == null || link == null) return null;

        PacketStorageBusSync packet = new PacketStorageBusSync();
        packet.dimension = bus.getWorldObj().provider.dimensionId;
        packet.x = bus.xCoord;
        packet.y = bus.yCoord;
        packet.z = bus.zCoord;
        packet.side = side;
        packet.faceMask = mask;
        packet.config = config.writeToNbt();
        packet.targetName = link.getTargetName();
        packet.itemTarget = link.hasItemTarget();
        packet.fluidTarget = link.hasFluidTarget();
        return packet;
    }

    public static void send(EntityPlayerMP player, TileEntityStorageBus bus, int side) {
        PacketStorageBusSync packet = of(bus, side);
        if (packet != null) NetworkHandler.INSTANCE.sendTo(packet, player);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dimension = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        side = buf.readByte();
        faceMask = buf.readInt();
        config = buf.readBoolean() ? ByteBufUtils.readTag(buf) : null;
        targetName = ByteBufUtils.readUTF8String(buf);
        itemTarget = buf.readBoolean();
        fluidTarget = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimension);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeByte(side);
        buf.writeInt(faceMask);
        buf.writeBoolean(config != null);
        if (config != null) ByteBufUtils.writeTag(buf, config);
        ByteBufUtils.writeUTF8String(buf, targetName == null ? "" : targetName);
        buf.writeBoolean(itemTarget);
        buf.writeBoolean(fluidTarget);
    }

    public static class Handler implements IMessageHandler<PacketStorageBusSync, IMessage> {

        @Override
        public IMessage onMessage(PacketStorageBusSync message, MessageContext ctx) {
            try {
                ClientStorageBus.setConfig(
                    message.dimension,
                    message.x,
                    message.y,
                    message.z,
                    message.side,
                    message.faceMask,
                    message.config,
                    message.targetName,
                    message.itemTarget,
                    message.fluidTarget);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("存储总线：处理配置同步失败", t);
            }
            return null;
        }
    }
}
