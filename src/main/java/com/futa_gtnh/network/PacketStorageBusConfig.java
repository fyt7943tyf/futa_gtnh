package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.inventory.ContainerStorageBus;
import com.futa_gtnh.storagebus.TileEntityStorageBus;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：存储总线配置的整份上行，或向服务端要一份权威值。
 *
 * <p>
 * 校验标准对齐 IO 节点：必须真的开着这个总线的界面、必须在 8 格内、坐标上必须
 * 真的是存储总线。配置里的数字/枚举会被 {@code StorageBusConfig.readFromNbt}
 * 夹回合法范围，客户端伪造不了越界优先级或非法筛选。
 *
 * <p>
 * 这个包<b>不搬任何物品</b>，只改「怎么搬」的配置，所以即使被伪造也变不出东西。
 */
public class PacketStorageBusConfig implements IMessage {

    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private int x;
    private int y;
    private int z;
    private boolean request;
    private NBTTagCompound config;

    public PacketStorageBusConfig() {}

    public static PacketStorageBusConfig update(int x, int y, int z, NBTTagCompound config) {
        PacketStorageBusConfig packet = new PacketStorageBusConfig();
        packet.x = x;
        packet.y = y;
        packet.z = z;
        packet.config = config;
        return packet;
    }

    public static PacketStorageBusConfig request(int x, int y, int z) {
        PacketStorageBusConfig packet = new PacketStorageBusConfig();
        packet.x = x;
        packet.y = y;
        packet.z = z;
        packet.request = true;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        request = buf.readBoolean();
        config = buf.readBoolean() ? ByteBufUtils.readTag(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(request);
        buf.writeBoolean(config != null);
        if (config != null) ByteBufUtils.writeTag(buf, config);
    }

    public static class Handler implements IMessageHandler<PacketStorageBusConfig, IMessage> {

        @Override
        public IMessage onMessage(PacketStorageBusConfig message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() != null ? ctx.getServerHandler().playerEntity : null;
            if (player == null) return null;

            try {
                if (!(player.openContainer instanceof ContainerStorageBus)) return null;
                ContainerStorageBus container = (ContainerStorageBus) player.openContainer;

                TileEntity tile = player.worldObj.getTileEntity(message.x, message.y, message.z);
                if (!(tile instanceof TileEntityStorageBus)) return null;
                TileEntityStorageBus bus = (TileEntityStorageBus) tile;
                if (container.getBus() != bus) return null;

                if (player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > MAX_DISTANCE_SQ) {
                    return null;
                }

                if (!message.request && message.config != null) {
                    bus.getConfig()
                        .readFromNbt(message.config);
                    bus.onConfigChanged();
                } else {
                    bus.ensureRegistered();
                }
                PacketStorageBusSync.send(player, bus);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("存储总线：处理玩家 {} 的配置上行失败", player.getCommandSenderName(), t);
            }
            return null;
        }
    }
}
