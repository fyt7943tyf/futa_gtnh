package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.cardboard.CardboardBoxOps;
import com.futa_gtnh.cardboard.ItemBlockCardboardBox;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 纸箱装箱的客户端请求（C2S）。
 *
 * <p>
 * 用独立包代替原版 C08：客户端在 {@code onItemUseFirst} 里返回 true 后原生点击包
 * 不再发送（同时挡住目标机器的 GUI），服务端动作只能自己带包过去 ——
 * 和 {@code PacketSolarDescalerUse} / {@code PacketStorageBusUse} 同一套管线。
 * 服务端收到后重新校验（黑名单 / 硬度 / 权限），客户端的判断不作数。
 */
public class PacketCardboardBoxUse implements IMessage {

    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private int x;
    private int y;
    private int z;

    public PacketCardboardBoxUse() {}

    public PacketCardboardBoxUse(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<PacketCardboardBoxUse, IMessage> {

        @Override
        public IMessage onMessage(PacketCardboardBoxUse message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() != null ? ctx.getServerHandler().playerEntity : null;
            if (player == null) return null;

            try {
                if (player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > MAX_DISTANCE_SQ) {
                    return null;
                }
                ItemStack held = player.getHeldItem();
                if (held == null || !(held.getItem() instanceof ItemBlockCardboardBox)) return null;
                if (held.getItemDamage() != 0) return null;
                CardboardBoxOps.pack(player, player.worldObj, message.x, message.y, message.z, held);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("纸箱：处理玩家 {} 的装箱请求失败", player.getCommandSenderName(), t);
            }
            return null;
        }
    }
}
