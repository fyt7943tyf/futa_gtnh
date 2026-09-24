package com.futa_gtnh.network;

import com.futa_gtnh.Config;
import com.futa_gtnh.rts.RtsSessionManager;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：声明俯瞰建筑模式的会话开关。
 *
 * <p>
 * 和 {@code PacketOpenGui} 一样是「只发意图」：客户端想进 / 想退，服务端
 * 权威决定。相机姿态完全不经过网络 —— 它是纯客户端表现（见
 * {@code RtsSessionManager} 的类注释，服务端只按玩家位置校验范围）。
 *
 * <p>
 * 开启请求会被拒绝（配置关闭时），拒绝时回一个
 * {@link PacketRtsToggleAck} 让客户端把界面退回去并说明原因；
 * 关闭请求总是成功、也不回包 —— 客户端退出是自己的事，服务端只是把
 * 门控记录删掉。
 */
public class PacketRtsToggle implements IMessage {

    private boolean enable;

    public PacketRtsToggle() {}

    public PacketRtsToggle(boolean enable) {
        this.enable = enable;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        enable = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(enable);
    }

    public static class Handler implements IMessageHandler<PacketRtsToggle, IMessage> {

        @Override
        public IMessage onMessage(PacketRtsToggle message, MessageContext ctx) {
            net.minecraft.entity.player.EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;

            if (!message.enable) {
                RtsSessionManager.setActive(player, false);
                // 会话结束后远程打开的界面不再放宽距离校验（走远自动关闭）
                com.futa_gtnh.rts.server.RtsRemoteGuiRegistry.clearFor(player);
                return null;
            }

            if (!Config.rtsEnable) {
                NetworkHandler.INSTANCE.sendTo(PacketRtsToggleAck.rejected("futa_gtnh.rts.msg.disabled"), player);
                return null;
            }

            RtsSessionManager.setActive(player, true);
            NetworkHandler.INSTANCE.sendTo(PacketRtsToggleAck.accepted(), player);
            return null;
        }
    }
}
