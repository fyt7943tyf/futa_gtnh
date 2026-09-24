package com.futa_gtnh.network;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.station.StationViews;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 → 服务端：合成站旁边那块「共享存储区」这一页要显示哪些物品。
 *
 * <p>
 * <b>为什么由客户端说了算</b>：排序、搜索、分页的代码全在客户端（终端界面用的是同一套），
 * 服务端要算出<b>一模一样</b>的顺序就得把中文名、拼音、矿辞那一整套抄一遍，
 * 而且服务端没有语言文件，抄出来也不是同一个顺序。所以让客户端算，
 * 把这一页的物品键推给服务端，服务端照着放 —— 两边看到的一定是同一页同一格。
 *
 * <p>
 * <b>伪造这个包没有收益</b>：包里只有物品键，没有数量。取东西走的是
 * {@code SharedStorage.extractItem}，存储里没有就是取不出来；存东西走
 * {@code insertItem}。所以最坏结果只是「界面显示得乱七八糟」，不产生任何物品增减。
 *
 * <p>
 * 27 个键里可能带着很大的 NBT（背包类物品），所以整包先 gzip 再塞进自定义包，
 * 万一还是超长就砍掉尾巴 —— 宁可少显示几格，也不能发一个超过包长上限的包。
 */
public class PacketStationView implements IMessage {

    /** 1.7.10 自定义包的安全上限（留了余量）。 */
    private static final int MAX_PAYLOAD = 28000;

    private int dimension;
    private int x;
    private int y;
    private int z;
    /** 客户端手里没有共享存储快照时置位，服务端收到就补发一份全量。 */
    private boolean requestSync;

    private List<ItemKey> keys = Collections.emptyList();

    public PacketStationView() {}

    public PacketStationView(int dimension, int x, int y, int z, List<ItemKey> keys, boolean requestSync) {
        this.dimension = dimension;
        this.x = x;
        this.y = y;
        this.z = z;
        this.keys = keys == null ? Collections.<ItemKey>emptyList() : keys;
        this.requestSync = requestSync;
    }

    public int getDimension() {
        return dimension;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public boolean isRequestSync() {
        return requestSync;
    }

    public List<ItemKey> getKeys() {
        return keys;
    }

    // ==================================================================

    @Override
    public void fromBytes(ByteBuf buf) {
        dimension = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        requestSync = buf.readBoolean();

        keys = Collections.emptyList();
        int length = buf.readInt();
        if (length <= 0) return;

        byte[] payload = new byte[length];
        buf.readBytes(payload);
        try {
            NBTTagCompound root = CompressedStreamTools.readCompressed(new ByteArrayInputStream(payload));
            NBTTagList list = root.getTagList("keys", 10);
            List<ItemKey> parsed = new ArrayList<>(list.tagCount());
            for (int i = 0; i < list.tagCount(); i++) {
                // 认不出来的键也要占一个位置：第 i 个键对应第 i 个格子，错位就会取错东西
                parsed.add(ItemKey.readFromNbt(list.getCompoundTagAt(i)));
            }
            keys = parsed;
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("共享存储：解析合成站视图包失败", t);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dimension);
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(requestSync);

        byte[] payload = encode(keys);
        if (payload == null) {
            buf.writeInt(0);
            return;
        }
        buf.writeInt(payload.length);
        buf.writeBytes(payload);
    }

    /**
     * 打包这一页的物品键。
     *
     * @return gzip 后的字节；连一个键都放不下时返回 null（调用方发一个空视图）
     */
    private static byte[] encode(List<ItemKey> keys) {
        int count = keys.size();
        while (count > 0) {
            byte[] payload = encode(keys, count);
            if (payload != null && payload.length <= MAX_PAYLOAD) return payload;
            // 超长了：砍一半再试。极罕见（要 27 个都带巨大 NBT）
            count /= 2;
        }
        return null;
    }

    private static byte[] encode(List<ItemKey> keys, int count) {
        try {
            NBTTagList list = new NBTTagList();
            for (int i = 0; i < count; i++) {
                ItemKey key = keys.get(i);
                // 空位也要写一个空标签占位：位置即槽位号，不能省
                list.appendTag(key == null ? new NBTTagCompound() : key.writeToNbt());
            }
            NBTTagCompound root = new NBTTagCompound();
            root.setTag("keys", list);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(root, out);
            return out.toByteArray();
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("共享存储：打包合成站视图失败", t);
            return null;
        }
    }

    public static class Handler implements IMessageHandler<PacketStationView, IMessage> {

        @Override
        public IMessage onMessage(PacketStationView message, MessageContext ctx) {
            try {
                StationViews.applyClientView(ctx.getServerHandler().playerEntity, message);
            } catch (Throwable t) {
                // 匠魂缺席 / 版本对不上：静默忽略，绝不影响别的包
                FutaGtnhMod.LOG.debug("共享存储：处理合成站视图包失败", t);
            }
            return null;
        }
    }
}
