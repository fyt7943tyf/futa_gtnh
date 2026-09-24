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
 *
 * <p>
 * {@link #STATE_ARRIVED} 是「已经找到、而且已经传送到过一次」——
 * 特意做成独立状态而不是「FOUND + 一个布尔」：传送之后<b>结果要留着</b>
 * （追踪和光束是玩家到了之后唯一的指路手段），但传送按钮得作废。
 * 两者分开写，界面判断起来就是一句 {@code state == STATE_FOUND}。
 */
public class PacketLocatorResult implements IMessage {

    public static final byte STATE_RUNNING = 0;
    public static final byte STATE_FOUND = 1;
    public static final byte STATE_NOT_FOUND = 2;
    public static final byte STATE_CANCELLED = 3;
    /** 已找到，并且已经用这次结果传送过一次（坐标和距离客户端手上那份不变）。 */
    public static final byte STATE_ARRIVED = 4;

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

    /**
     * 传送成功。
     *
     * <p>
     * <b>坐标和距离照抄结果那一份</b>（服务端只留着坐标，距离没留），
     * 客户端收到这个包只做一件事：把状态从「已找到」改成「已传送」，
     * 从而把传送按钮作废，而结果、坐标、光束全部原样留着。
     */
    public static PacketLocatorResult arrived() {
        return new PacketLocatorResult(STATE_ARRIVED, 1.0F, 0, 0, 0, -1.0D);
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
