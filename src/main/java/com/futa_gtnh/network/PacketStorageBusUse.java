package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.storagebus.ItemStorageBus;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 存储总线放置/加面的客户端请求。
 *
 * <p>
 * 用独立 C2S 包代替原版 C08：客户端在 {@code onItemUseFirst} 里返回 true 后原生点击包
 * 不再发送，如果服务端只靠 {@code onItemUseFirst} 会收不到；同时返回 true 也挡住了
 * 目标容器的 GUI。服务端收到包后自己重新解析放置位置，客户端的判断不作数。
 */
public class PacketStorageBusUse implements IMessage {

    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private int x;
    private int y;
    private int z;
    private int side;

    public PacketStorageBusUse() {}

    public PacketStorageBusUse(int x, int y, int z, int side) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        side = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeByte(side);
    }

    public static class Handler implements IMessageHandler<PacketStorageBusUse, IMessage> {

        @Override
        public IMessage onMessage(PacketStorageBusUse message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() != null ? ctx.getServerHandler().playerEntity : null;
            if (player == null) return null;

            try {
                if (message.side < 0 || message.side >= 6) return null;
                if (player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > MAX_DISTANCE_SQ) {
                    return null;
                }
                ItemStack held = player.getHeldItem();
                if (held == null || !(held.getItem() instanceof ItemStorageBus)) return null;
                ItemStorageBus
                    .applyServerUse(player, player.worldObj, held, message.x, message.y, message.z, message.side);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("存储总线：处理玩家 {} 的放置请求失败", player.getCommandSenderName(), t);
            }
            return null;
        }
    }
}
