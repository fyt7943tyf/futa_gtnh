package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.rts.server.RtsActionGuard;
import com.futa_gtnh.rts.server.RtsBuildService;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：远程旋转一个方块的朝向（快捷键 R，旋转光标悬停的方块）。
 *
 * <p>
 * 直接调原版 {@code Block.rotateBlock} —— 原木轴向、活塞朝向这些原版支持
 * 的都能转；GT 机器如果没实现这个方法就原样转不动，提示一声。
 */
public class PacketRtsRotate implements IMessage {

    private int x, y, z;

    public PacketRtsRotate() {}

    public PacketRtsRotate(int x, int y, int z) {
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

    public static class Handler implements IMessageHandler<PacketRtsRotate, IMessage> {

        @Override
        public IMessage onMessage(PacketRtsRotate message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            if (!RtsActionGuard.tryConsume(player)) return null;
            if (!RtsActionGuard.isWithinRange(player, message.x + 0.5D, message.y + 0.5D, message.z + 0.5D)) {
                RtsActionGuard.notifyRejected(player, "futa_gtnh.rts.msg.out_of_range");
                return null;
            }

            RtsBuildService.handleRotateBlock(player, message.x, message.y, message.z);
            return null;
        }
    }
}
