package com.futa_gtnh.network;

import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 把整份共享存储的全量快照发给客户端，供 GUI 搜索/排序/分页使用。
 *
 * <p>
 * 做法是「先整体 gzip，再按字节切片分发」，而不是「按条目分包」：
 * 条目分包要么让每个包都带一份 NBT 头开销，要么得猜每包塞多少条才不超长
 * （带大 NBT 的物品会让包体积暴涨）。整体压缩后切片，压缩率最好，
 * 而且每片大小是<b>确定的</b>，不可能超长。
 *
 * <p>
 * 共享存储是全服共享的，所以压缩结果对所有玩家都一样 —— 由调用方
 * {@link #createPayload} 压一次、缓存起来复用，不是每个玩家压一遍。
 */
public class PacketStorageSync implements IMessage {

    private int transferId;
    private int chunkIndex;
    private int chunkCount;
    private byte[] data;

    public PacketStorageSync() {}

    public PacketStorageSync(int transferId, int chunkIndex, int chunkCount, byte[] data) {
        this.transferId = transferId;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.data = data;
    }

    /**
     * 把整份存储序列化成压缩字节流。同一份内容可以发给任意多个玩家。
     *
     * @return gzip 后的 NBT 字节
     */
    public static byte[] createPayload(SharedStorage storage) {
        NBTTagCompound root = new NBTTagCompound();

        NBTTagList itemList = new NBTTagList();
        for (Map.Entry<ItemKey, Long> entry : storage.snapshotItems()) {
            NBTTagCompound tag = entry.getKey()
                .writeToNbt();
            tag.setLong("amount", entry.getValue());
            itemList.appendTag(tag);
        }
        root.setTag("items", itemList);

        NBTTagList fluidList = new NBTTagList();
        for (Map.Entry<FluidKey, Long> entry : storage.snapshotFluids()) {
            NBTTagCompound tag = entry.getKey()
                .writeToNbt();
            tag.setLong("amount", entry.getValue());
            fluidList.appendTag(tag);
        }
        root.setTag("fluids", fluidList);

        try {
            return net.minecraft.nbt.CompressedStreamTools.compress(root);
        } catch (Exception e) {
            FutaGtnhMod.LOG.error("共享存储：序列化快照失败，将发送空快照", e);
            return new byte[0];
        }
    }

    /**
     * 发送整份快照。按 {@link NetworkHandler#CHUNK_SIZE} 切片，逐片发出。
     *
     * <p>
     * 切片本身不压缩；每个片是独立的 {@link PacketStorageSync}，
     * 客户端按 {@code transferId} 归拢，凑齐 {@code chunkCount} 片后一次性解压。
     */
    public static void sendTo(EntityPlayerMP player, int transferId, byte[] payload) {
        int total = Math.max(1, (payload.length + NetworkHandler.CHUNK_SIZE - 1) / NetworkHandler.CHUNK_SIZE);
        for (int i = 0; i < total; i++) {
            int from = i * NetworkHandler.CHUNK_SIZE;
            int to = Math.min(payload.length, from + NetworkHandler.CHUNK_SIZE);
            byte[] slice = new byte[to - from];
            System.arraycopy(payload, from, slice, 0, slice.length);
            NetworkHandler.INSTANCE.sendTo(new PacketStorageSync(transferId, i, total, slice), player);
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

    public static class Handler implements IMessageHandler<PacketStorageSync, IMessage> {

        @Override
        public IMessage onMessage(PacketStorageSync message, MessageContext ctx) {
            com.futa_gtnh.client.ClientStorageCache
                .receiveChunk(message.transferId, message.chunkIndex, message.chunkCount, message.data);
            return null;
        }
    }
}
