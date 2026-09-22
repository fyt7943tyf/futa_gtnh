package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.Config;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 按键请求打开共享存储 GUI。
 *
 * <p>
 * 打开界面这件事必须由服务端发起（走 {@code player.openGui}），
 * 因为服务端的 {@code Container} 才是权威的那一份。客户端能做的只是「请求」，
 * 是否允许由服务端的配置项决定。
 */
public class PacketOpenGui implements IMessage {

    public PacketOpenGui() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        // 无载荷
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // 无载荷
    }

    public static class Handler implements IMessageHandler<PacketOpenGui, IMessage> {

        @Override
        public IMessage onMessage(PacketOpenGui message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null) return null;
            if (!Config.allowRemoteAccess) return null;
            CommonProxy.openSharedStorage(player, null);
            return null;
        }
    }
}
