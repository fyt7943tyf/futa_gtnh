package com.futa_gtnh.network;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.ClientReactorLayoutCache;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：一条布局的<b>完整预览</b>（54 格内容 + 需求清单）。
 *
 * <p>
 * 按需下发：玩家在浏览器里选中某条布局（或部署完成后刷新）时才发这一条，
 * 所以包体可控 —— 反应堆只收 IC2 的反应堆元件，单格 NBT 就是几个整型
 * （{@code advDmg} 之类），54 格压缩后是 KB 级。仍带长度上限防御：
 * 真撞上异常大的 NBT 就当空包丢弃，不撑爆 1.7.10 的自定义包上限。
 */
public class PacketReactorLayoutPreview implements IMessage {

    private static final int MAX_PAYLOAD = NetworkHandler.CHUNK_SIZE;

    private byte[] data;

    public PacketReactorLayoutPreview() {}

    public PacketReactorLayoutPreview(NBTTagCompound payload) {
        try {
            this.data = net.minecraft.nbt.CompressedStreamTools.compress(payload);
        } catch (Exception e) {
            FutaGtnhMod.LOG.error("反应堆布局档案：序列化预览失败，将发送空预览", e);
            this.data = new byte[0];
        }
        if (this.data.length > MAX_PAYLOAD) {
            // 正常内容到不了这个量级；真到了说明哪格 NBT 异常巨大，
            // 宁可预览空掉也不能把客户端踢下线
            FutaGtnhMod.LOG.error("反应堆布局档案：预览载荷 {} 字节超过上限，已丢弃", this.data.length);
            this.data = new byte[0];
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int length = buf.readInt();
        if (length < 0 || length > MAX_PAYLOAD) {
            data = new byte[0];
            return;
        }
        data = new byte[length];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(data == null ? 0 : data.length);
        if (data != null) {
            buf.writeBytes(data);
        }
    }

    public static class Handler implements IMessageHandler<PacketReactorLayoutPreview, IMessage> {

        @Override
        public IMessage onMessage(PacketReactorLayoutPreview message, MessageContext ctx) {
            ClientReactorLayoutCache.receivePreview(message.data);
            return null;
        }
    }
}
