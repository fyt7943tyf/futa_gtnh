package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.client.nei.NeiCraftStep;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端完成一次 NEI 自动合成步骤后的回执。
 *
 * <p>
 * NEI 的自动合成线程会根据 {@code craft()} 的返回值继续计算下一步，不能把
 * 「请求已经发出」当成「步骤已经完成」。这个包把实际合成次数和步骤状态带回客户端，
 * 让调用线程在服务器完成后再返回。
 */
public class PacketCraftResult implements IMessage {

    /** 请求完整执行。 */
    public static final byte COMPLETED = 0;
    /** 服务器只完成了请求中的一部分。 */
    public static final byte PARTIAL = 1;
    /** 请求没有完成任何合成。 */
    public static final byte FAILED = 2;

    private long requestId;
    private int crafted;
    private byte status;

    public PacketCraftResult() {}

    public PacketCraftResult(long requestId, int crafted, byte status) {
        this.requestId = requestId;
        this.crafted = crafted;
        this.status = status;
    }

    public long getRequestId() {
        return requestId;
    }

    public int getCrafted() {
        return crafted;
    }

    public byte getStatus() {
        return status;
    }

    /** 只给 NEI 自动合成请求发送回执；普通合成按钮不需要等待。 */
    public static void send(EntityPlayerMP player, long requestId, int crafted, byte status) {
        if (player == null || requestId == 0L) return;
        NetworkHandler.INSTANCE.sendTo(new PacketCraftResult(requestId, crafted, status), player);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        requestId = buf.readLong();
        crafted = buf.readInt();
        status = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(requestId);
        buf.writeInt(crafted);
        buf.writeByte(status);
    }

    public static class Handler implements IMessageHandler<PacketCraftResult, IMessage> {

        @Override
        public IMessage onMessage(PacketCraftResult message, MessageContext ctx) {
            NeiCraftStep.complete(message.getRequestId(), message.getCrafted(), message.getStatus());
            return null;
        }
    }
}
