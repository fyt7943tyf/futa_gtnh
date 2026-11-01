package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.item.ItemMePackage;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * ME 封装物品的放回请求（C2S）。同 {@code PacketMeTapeUse} 的管线。
 */
public class PacketMePackageUse implements IMessage {

    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private int x;
    private int y;
    private int z;
    private int side;

    public PacketMePackageUse() {}

    public PacketMePackageUse(int x, int y, int z, int side) {
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

    public static class Handler implements IMessageHandler<PacketMePackageUse, IMessage> {

        @Override
        public IMessage onMessage(PacketMePackageUse message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() != null ? ctx.getServerHandler().playerEntity : null;
            if (player == null) return null;

            try {
                if (message.side < 0 || message.side >= 6) return null;
                if (player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > MAX_DISTANCE_SQ) {
                    return null;
                }
                ItemStack held = player.getHeldItem();
                if (held == null || !(held.getItem() instanceof ItemMePackage)) return null;
                com.futa_gtnh.aetape.MeTapeOps
                    .place(player, player.worldObj, message.x, message.y, message.z, message.side, held);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("ME 胶带：处理玩家 {} 的放回请求失败", player.getCommandSenderName(), t);
            }
            return null;
        }
    }
}
