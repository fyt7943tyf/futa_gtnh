package com.futa_gtnh.network;

import com.futa_gtnh.client.ClientLootassistCache;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：搜索进度（发给发起搜索的玩家）。
 *
 * <p>
 * 字段刻意做成绝对值（done/total/found）而不是增量：包本身每 10 tick 才一个，
 * 丢了也不该累积误差，绝对值天然幂等。
 */
public class PacketLootassistProgress implements IMessage {

    private int done;
    private int total;
    private int found;
    private boolean running;

    public PacketLootassistProgress() {}

    public PacketLootassistProgress(int done, int total, int found, boolean running) {
        this.done = done;
        this.total = total;
        this.found = found;
        this.running = running;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        done = buf.readInt();
        total = buf.readInt();
        found = buf.readInt();
        running = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(done);
        buf.writeInt(total);
        buf.writeInt(found);
        buf.writeBoolean(running);
    }

    public static class Handler implements IMessageHandler<PacketLootassistProgress, IMessage> {

        @Override
        public IMessage onMessage(PacketLootassistProgress message, MessageContext ctx) {
            ClientLootassistCache.receiveProgress(message.done, message.total, message.found, message.running);
            return null;
        }
    }
}
