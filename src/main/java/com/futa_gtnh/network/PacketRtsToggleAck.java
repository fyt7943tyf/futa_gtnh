package com.futa_gtnh.network;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：俯瞰模式开启请求的答复。
 *
 * <p>
 * 客户端按乐观策略先进俯瞰模式再等答复（省一个来回的手感延迟），
 * 所以「拒绝」的答复必须把客户端退回去 —— {@code ClientProxy#onRtsToggleRejected}
 * 负责这件事。接受时不带任何状态：客户端相机本来就是本地的，服务端没有
 * 什么要同步给它的。
 *
 * <p>
 * Handler 通过代理方法间接调客户端逻辑，而不是像
 * {@code PacketLocatorResult} 那样直接引用 {@code client} 包的类：
 * 这个包类两端都会加载，直接引用就等于在专用服务端上赌 JVM 的惰性符号解析，
 * 本项目的约定是「公共类不引用 net.minecraft.client.*」（见
 * {@code CommonProxy#clearLocatorTracking} 的注释）。
 */
public class PacketRtsToggleAck implements IMessage {

    private boolean accepted;
    private String reasonKey;

    public PacketRtsToggleAck() {}

    private PacketRtsToggleAck(boolean accepted, String reasonKey) {
        this.accepted = accepted;
        this.reasonKey = reasonKey;
    }

    /** 服务端接受了开启请求（客户端什么都不用做）。 */
    public static PacketRtsToggleAck accepted() {
        return new PacketRtsToggleAck(true, null);
    }

    /** 服务端拒绝了开启请求，客户端应退出俯瞰模式并提示 reasonKey 指向的原因。 */
    public static PacketRtsToggleAck rejected(String reasonKey) {
        return new PacketRtsToggleAck(false, reasonKey);
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        accepted = buf.readBoolean();
        reasonKey = buf.readBoolean() ? ByteBufUtils.readUTF8String(buf) : null;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(accepted);
        buf.writeBoolean(reasonKey != null);
        if (reasonKey != null) {
            ByteBufUtils.writeUTF8String(buf, reasonKey);
        }
    }

    public static class Handler implements IMessageHandler<PacketRtsToggleAck, IMessage> {

        @Override
        public IMessage onMessage(PacketRtsToggleAck message, MessageContext ctx) {
            if (message.accepted) return null;
            try {
                FutaGtnhMod.proxy.onRtsToggleRejected(message.reasonKey);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("俯瞰建筑：处理开启拒绝包失败", t);
            }
            return null;
        }
    }
}
