package com.futa_gtnh.network;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.ClientTerminalState;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：「拾取自动入库」当前到底是开还是关。
 *
 * <p>
 * 两个时机下发：开界面时（服务端在 {@code GuiHandler} 里发），
 * 以及玩家点了开关之后（作为权威回执）。客户端那份只用来画按钮，
 * 真正的判定全在服务端。
 */
public class PacketAutoStoreSync implements IMessage {

    private boolean enabled;

    public PacketAutoStoreSync() {}

    public PacketAutoStoreSync(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        enabled = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(enabled);
    }

    public static class Handler implements IMessageHandler<PacketAutoStoreSync, IMessage> {

        @Override
        public IMessage onMessage(PacketAutoStoreSync message, MessageContext ctx) {
            try {
                ClientTerminalState.setAutoStore(message.enabled);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("共享存储：处理自动入库同步包失败", t);
            }
            return null;
        }
    }
}
