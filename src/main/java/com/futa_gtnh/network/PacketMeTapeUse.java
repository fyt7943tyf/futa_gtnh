package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.item.ItemMePackingTape;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * ME 封装胶带的封装请求（C2S）。
 *
 * <p>
 * 和 {@code PacketCardboardBoxUse} 同一套管线：客户端 {@code onItemUseFirst}
 * 返回 true 吞掉原生 C08（同时挡住 AE 设备的 GUI），服务端重校验后执行。
 * hit 坐标跟着发 —— 线缆总线按视线命中点选部件（{@code selectPart}），
 * 点到线缆本体时不能乱拆。
 */
public class PacketMeTapeUse implements IMessage {

    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private int x;
    private int y;
    private int z;
    private int side;
    private float hitX;
    private float hitY;
    private float hitZ;

    public PacketMeTapeUse() {}

    public PacketMeTapeUse(int x, int y, int z, int side, float hitX, float hitY, float hitZ) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
        this.hitX = hitX;
        this.hitY = hitY;
        this.hitZ = hitZ;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        side = buf.readByte();
        hitX = buf.readFloat();
        hitY = buf.readFloat();
        hitZ = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeByte(side);
        buf.writeFloat(hitX);
        buf.writeFloat(hitY);
        buf.writeFloat(hitZ);
    }

    public static class Handler implements IMessageHandler<PacketMeTapeUse, IMessage> {

        @Override
        public IMessage onMessage(PacketMeTapeUse message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() != null ? ctx.getServerHandler().playerEntity : null;
            if (player == null) return null;

            try {
                if (message.side < 0 || message.side >= 6) return null;
                if (player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > MAX_DISTANCE_SQ) {
                    return null;
                }
                ItemStack held = player.getHeldItem();
                if (held == null || !(held.getItem() instanceof ItemMePackingTape)) return null;
                com.futa_gtnh.aetape.MeTapeOps.pack(
                    player,
                    player.worldObj,
                    message.x,
                    message.y,
                    message.z,
                    message.side,
                    message.hitX,
                    message.hitY,
                    message.hitZ,
                    held);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("ME 胶带：处理玩家 {} 的封装请求失败", player.getCommandSenderName(), t);
            }
            return null;
        }
    }
}
