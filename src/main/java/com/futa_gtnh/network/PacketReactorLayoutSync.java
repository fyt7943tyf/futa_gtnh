package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.ClientReactorLayoutCache;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：布局档案的<b>全量列表</b>（只有元数据，不含 54 格内容）。
 *
 * <p>
 * 与 {@link PacketStorageSync} / {@code PacketLootassistSync} 同一套
 * 「gzip 压缩 + 按 28000 字节切片」的做法：布局列表很小（上限几百条、每条几十字节），
 * 一片就够；切片逻辑照写，保险不撞 1.7.10 自定义包的 32KB 上限。
 *
 * <p>
 * 列表刻意<b>不带网格内容</b>：一条布局最多 54 个带 NBT 的物品栈，全量带上会把
 * 同步包撑大两个数量级。完整内容只在玩家选中某条时按需走
 * {@link PacketReactorLayoutPreview}。
 */
public class PacketReactorLayoutSync implements IMessage {

    private int transferId;
    private int chunkIndex;
    private int chunkCount;
    private byte[] data;

    public PacketReactorLayoutSync() {}

    private PacketReactorLayoutSync(int transferId, int chunkIndex, int chunkCount, byte[] data) {
        this.transferId = transferId;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.data = data;
    }

    /**
     * 序列化 + 发送整份列表。按 {@link NetworkHandler#CHUNK_SIZE} 切片逐片发出，
     * 客户端按 {@code transferId} 归拢后一次性解压。
     */
    public static void sendTo(EntityPlayerMP player, NBTTagCompound snapshot) {
        byte[] payload;
        try {
            payload = net.minecraft.nbt.CompressedStreamTools.compress(snapshot);
        } catch (Exception e) {
            FutaGtnhMod.LOG.error("反应堆布局档案：序列化列表失败，将发送空列表", e);
            payload = new byte[0];
        }

        int total = Math.max(1, (payload.length + NetworkHandler.CHUNK_SIZE - 1) / NetworkHandler.CHUNK_SIZE);
        for (int i = 0; i < total; i++) {
            int from = i * NetworkHandler.CHUNK_SIZE;
            int to = Math.min(payload.length, from + NetworkHandler.CHUNK_SIZE);
            byte[] slice = new byte[to - from];
            System.arraycopy(payload, from, slice, 0, slice.length);
            NetworkHandler.INSTANCE.sendTo(new PacketReactorLayoutSync(0, i, total, slice), player);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        transferId = buf.readInt();
        chunkIndex = buf.readInt();
        chunkCount = buf.readInt();
        int length = buf.readInt();
        if (length < 0 || length > NetworkHandler.CHUNK_SIZE * 4) {
            // 明显不合理的长度，直接当成空数据，避免被畸形包撑爆内存
            data = new byte[0];
            return;
        }
        data = new byte[length];
        buf.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(transferId);
        buf.writeInt(chunkIndex);
        buf.writeInt(chunkCount);
        buf.writeInt(data == null ? 0 : data.length);
        if (data != null) {
            buf.writeBytes(data);
        }
    }

    public static class Handler implements IMessageHandler<PacketReactorLayoutSync, IMessage> {

        @Override
        public IMessage onMessage(PacketReactorLayoutSync message, MessageContext ctx) {
            ClientReactorLayoutCache.receiveChunk(0, message.chunkIndex, message.chunkCount, message.data);
            return null;
        }
    }
}
