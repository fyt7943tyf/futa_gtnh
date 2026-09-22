package com.futa_gtnh.network;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 增量同步：一次操作通常只动一两个条目，没必要重发整份快照。
 *
 * <p>
 * 一次操作可能同时改多个条目（例如「全部存入」会一次动 36 个格子里的东西），
 * 所以这里是<b>批量</b>的：一个包携带一串变更，整包只走一次网络往返。
 *
 * <p>
 * 只有「设值」和「删除」两种操作，没有「加/减」—— 增量语义会让客户端
 * 在丢包或乱序时和服务器算出不同的结果，而绝对值是幂等的：
 * 哪怕中间漏了一条，下一条同名条目也会把它纠正回来。
 */
public class PacketStorageDelta implements IMessage {

    public static final byte KIND_ITEM = 0;
    public static final byte KIND_FLUID = 1;

    public static final byte OP_SET = 0;
    public static final byte OP_REMOVE = 1;

    /**
     * 估算的序列化体积上限（字节）。
     *
     * <p>
     * 光限制条数是不够的：「全部存入」一次能带上 36 格，而背包/工具箱/电池这类
     * 物品的 NBT 动辄几十 KB。36 条这种条目拼起来就是一个几 MB 的包 ——
     * 1.7.10 的 netty 帧长上限撑不住，轻则卡一下，重则把客户端踢下线。
     *
     * <p>
     * 超过这个预算就不再往里加了（{@link #hasOverflowed()} 会变成 true），
     * 由调用方改成重发一份全量快照 —— 那样是分片的，体积可控。
     */
    public static final int MAX_ESTIMATED_BYTES = 24000;

    /** 单包最多携带的变更条数。 */
    public static final int MAX_CHANGES = 256;

    private final List<Change> changes = new ArrayList<>();

    private int estimatedBytes;
    private boolean overflowed;

    public PacketStorageDelta() {}

    public static final class Change {

        public final byte kind;
        public final byte op;
        public final NBTTagCompound key;
        public final long amount;

        Change(byte kind, byte op, NBTTagCompound key, long amount) {
            this.kind = kind;
            this.op = op;
            this.key = key;
            this.amount = amount;
        }
    }

    /**
     * 记录一条物品变更。
     *
     * @param amount 变更后的绝对值；{@code amount <= 0} 视作「条目已删空」
     */
    public void addItem(ItemKey key, long amount) {
        if (key == null) return;
        add(KIND_ITEM, amount, key.writeToNbt());
    }

    public void addFluid(FluidKey key, long amount) {
        if (key == null) return;
        add(KIND_FLUID, amount, key.writeToNbt());
    }

    private void add(byte kind, long amount, NBTTagCompound keyTag) {
        if (changes.size() >= MAX_CHANGES) {
            overflowed = true;
            return;
        }

        int cost = estimateBytes(keyTag);
        if (estimatedBytes + cost > MAX_ESTIMATED_BYTES) {
            overflowed = true;
            return;
        }

        estimatedBytes += cost;
        changes.add(new Change(kind, amount > 0L ? OP_SET : OP_REMOVE, keyTag, Math.max(amount, 0L)));
    }

    /**
     * 估算一个键序列化之后占多少字节。
     *
     * <p>
     * 直接量真实字节数而不是拍脑袋乘系数：NBT 的大小完全由内容决定，
     * 一个空白工具和一个塞满附魔与自定义数据的工具能差三个数量级。
     * 而且这个函数只在「一次操作真正改了东西」时才会被调用几十次，
     * 开销可以忽略。
     */
    private static int estimateBytes(NBTTagCompound tag) {
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream(64);
            java.io.DataOutputStream out = new java.io.DataOutputStream(buffer);
            net.minecraft.nbt.CompressedStreamTools.write(tag, out);
            out.flush();
            return buffer.size() + 16;
        } catch (Exception e) {
            // 量不出来就按一个偏大的常数算，宁可少发几条也不能发出超长包
            return 4096;
        }
    }

    /**
     * @return 是否因为有变更装不下而被丢弃。调用方应当在为 true 时
     *         改用全量快照重发（那一条路径是分片的，体积可控）
     */
    public boolean hasOverflowed() {
        return overflowed;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }

    public int size() {
        return changes.size();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        changes.clear();
        int count = buf.readInt();
        if (count < 0 || count > MAX_CHANGES) {
            // 畸形包：直接丢弃内容，不抛异常
            return;
        }
        for (int i = 0; i < count; i++) {
            byte kind = buf.readByte();
            byte op = buf.readByte();
            NBTTagCompound key = ByteBufUtils.readTag(buf);
            long amount = buf.readLong();
            changes.add(new Change(kind, op, key, amount));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(changes.size());
        for (Change change : changes) {
            buf.writeByte(change.kind);
            buf.writeByte(change.op);
            ByteBufUtils.writeTag(buf, change.key);
            buf.writeLong(change.amount);
        }
    }

    public List<Change> getChanges() {
        return changes;
    }

    public static class Handler implements IMessageHandler<PacketStorageDelta, IMessage> {

        @Override
        public IMessage onMessage(PacketStorageDelta message, MessageContext ctx) {
            com.futa_gtnh.client.ClientStorageCache.applyDelta(message.getChanges());
            return null;
        }
    }
}
