package com.futa_gtnh.network;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：寻物任务的状态。
 *
 * <p>
 * 三种终态 + 一种中间态。中间态只带进度，是为了让界面能画进度条 ——
 * 大半径扫描要跑一两秒，没有反馈的话玩家会以为按了没反应。
 */
public class PacketLocatorResult implements IMessage {

    public static final byte STATE_RUNNING = 0;
    public static final byte STATE_FOUND = 1;
    public static final byte STATE_NOT_FOUND = 2;
    public static final byte STATE_CANCELLED = 3;

    private byte state;
    private float progress;
    private int x;
    private int y;
    private int z;
    private double distance;

    public PacketLocatorResult() {}

    private PacketLocatorResult(byte state, float progress, int x, int y, int z, double distance) {
        this.state = state;
        this.progress = progress;
        this.x = x;
        this.y = y;
        this.z = z;
        this.distance = distance;
    }

    public static PacketLocatorResult running(float progress) {
        return new PacketLocatorResult(STATE_RUNNING, progress, 0, 0, 0, -1.0D);
    }

    public static PacketLocatorResult found(int x, int y, int z, double distance) {
        return new PacketLocatorResult(STATE_FOUND, 1.0F, x, y, z, distance);
    }

    public static PacketLocatorResult notFound(float progress) {
        return new PacketLocatorResult(STATE_NOT_FOUND, progress, 0, 0, 0, -1.0D);
    }

    public static PacketLocatorResult cancelled() {
        return new PacketLocatorResult(STATE_CANCELLED, 0.0F, 0, 0, 0, -1.0D);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        state = buf.readByte();
        progress = buf.readFloat();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        distance = buf.readDouble();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(state);
        buf.writeFloat(progress);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeDouble(distance);
    }

    public static class Handler implements IMessageHandler<PacketLocatorResult, IMessage> {

        @Override
        public IMessage onMessage(PacketLocatorResult message, MessageContext ctx) {
            try {
                com.futa_gtnh.client.LocatorState
                    .applyResult(message.state, message.progress, message.x, message.y, message.z, message.distance);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("寻物魔杖：处理结果包失败", t);
            }
            return null;
        }
    }
}
