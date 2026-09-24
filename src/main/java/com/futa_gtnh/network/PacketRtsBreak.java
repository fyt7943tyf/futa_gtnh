package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.rts.server.RtsActionGuard;
import com.futa_gtnh.rts.server.RtsBuildService;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：远程破坏一个方块（即时破坏，规则见
 * {@link RtsBuildService} 的类注释）。
 *
 * <p>
 * 单块破坏包和批量破坏走同一条校验链（会话 + 限频 + 范围），只是批量
 * 由引擎按 tick 预算喂进来、不占 {@code rtsOpsPerTickPerPlayer} 的额度。
 */
public class PacketRtsBreak implements IMessage {

    private int x, y, z;

    public PacketRtsBreak() {}

    public PacketRtsBreak(int x, int y, int z) {
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

    public static class Handler implements IMessageHandler<PacketRtsBreak, IMessage> {

        @Override
        public IMessage onMessage(PacketRtsBreak message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            if (!RtsActionGuard.tryConsume(player)) return null;
            if (!RtsActionGuard.isWithinRange(player, message.x + 0.5D, message.y + 0.5D, message.z + 0.5D)) {
                RtsActionGuard.notifyRejected(player, "futa_gtnh.rts.msg.out_of_range");
                return null;
            }

            RtsBuildService.handleBreakBlock(player, message.x, message.y, message.z);
            return null;
        }
    }
}
