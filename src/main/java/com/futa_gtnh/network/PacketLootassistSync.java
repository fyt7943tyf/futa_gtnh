package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.ClientLootassistCache;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 服务端 -&gt; 客户端：小游戏助手共享列表的<b>全量快照</b>。
 *
 * <p>
 * 与 {@link PacketStorageSync} 同一套「gzip 压缩 + 按 28000 字节切片」的做法：
 * 条目数在几百以内时压缩后只有几 KB，一片就够；但切片逻辑照写，
 * 万一有人把列表攒到上千条也不会撞上 1.7.10 自定义包的 32KB 上限。
 */
public class PacketLootassistSync implements IMessage {

    private int transferId;
    private int chunkIndex;
    private int chunkCount;
    private byte[] data;

    public PacketLootassistSync() {}

    public PacketLootassistSync(int transferId, int chunkIndex, int chunkCount, byte[] data) {
        this.transferId = transferId;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.data = data;
    }

    /**
     * 序列化 + 发送整份快照。按 {@link NetworkHandler#CHUNK_SIZE} 切片逐片发出，
     * 客户端按 {@code transferId} 归拢后一次性解压。
     */
    public static void sendTo(EntityPlayerMP player, NBTTagCompound snapshot) {
        byte[] payload;
        try {
            payload = net.minecraft.nbt.CompressedStreamTools.compress(snapshot);
        } catch (Exception e) {
            FutaGtnhMod.LOG.error("小游戏助手：序列化快照失败，将发送空快照", e);
            payload = new byte[0];
        }

        int total = Math.max(1, (payload.length + NetworkHandler.CHUNK_SIZE - 1) / NetworkHandler.CHUNK_SIZE);
        for (int i = 0; i < total; i++) {
            int from = i * NetworkHandler.CHUNK_SIZE;
            int to = Math.min(payload.length, from + NetworkHandler.CHUNK_SIZE);
            byte[] slice = new byte[to - from];
            System.arraycopy(payload, from, slice, 0, slice.length);
            NetworkHandler.INSTANCE.sendTo(new PacketLootassistSync(0, i, total, slice), player);
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

    public static class Handler implements IMessageHandler<PacketLootassistSync, IMessage> {

        @Override
        public IMessage onMessage(PacketLootassistSync message, MessageContext ctx) {
            ClientLootassistCache
                .receiveChunk(message.transferId, message.chunkIndex, message.chunkCount, message.data);
            return null;
        }
    }
}
