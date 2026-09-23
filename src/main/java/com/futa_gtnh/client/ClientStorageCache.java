package com.futa_gtnh.client;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.PacketStorageDelta;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * 客户端持有的共享存储本地副本。
 *
 * <p>
 * 为什么客户端要存一整份：搜索、排序、翻页都要在<b>每一次按键、每一帧</b>重算，
 * 如果每次操作都问服务端要数据，一个几千条目的存储就能把服务器问崩。
 * 所以开界面时拉一次全量，之后靠增量包保持同步 —— 和 AE2 终端的做法一致。
 *
 * <p>
 * 全量同步走的是「整体 gzip + 按字节切片」：服务端把整份存储压成一个字节流，
 * 切成若干片分别发送，客户端按 {@code transferId} 把片拼回去再一次性解压。
 * 好处是压缩率最高（几千个条目通常压到几十 KB），而且每片大小固定、不会超包长。
 */
public final class ClientStorageCache {

    private ClientStorageCache() {}

    private static final List<StorageViewEntry> ITEMS = new ArrayList<>();
    private static final List<StorageViewEntry> FLUIDS = new ArrayList<>();
    private static final Map<ItemKey, StorageViewEntry> ITEM_INDEX = new HashMap<>();
    private static final Map<FluidKey, StorageViewEntry> FLUID_INDEX = new HashMap<>();

    /** 每次内容变化自增，GUI 靠它判断「要不要重新过滤排序」。 */
    private static int revision;
    private static boolean ready;

    // ---- 分块接收状态 ----
    private static int incomingTransferId = Integer.MIN_VALUE;
    private static byte[][] incomingChunks;
    private static int incomingReceived;

    public static boolean isReady() {
        return ready;
    }

    public static int getRevision() {
        return revision;
    }

    public static List<StorageViewEntry> items() {
        return ITEMS;
    }

    public static List<StorageViewEntry> fluids() {
        return FLUIDS;
    }

    /**
     * 共享存储里这种物品有多少（客户端视角）。
     *
     * <p>
     * NEI 的配方转移联动（「材料够不够」的绿红提示）用：判定时把背包和共享存储
     * 加在一起算。数量是增量同步来的绝对值，最多短暂滞后一个包。
     */
    public static long getItemAmount(ItemStack stack) {
        if (stack == null || !ready) return 0L;
        ItemKey key = ItemKey.of(stack);
        if (key == null) return 0L;
        StorageViewEntry entry = ITEM_INDEX.get(key);
        return entry == null ? 0L : entry.getAmount();
    }

    /** 共享存储里这种流体有多少毫巴（客户端视角）。 */
    public static long getFluidAmount(net.minecraftforge.fluids.FluidStack fluid) {
        if (fluid == null || !ready) return 0L;
        FluidKey key = FluidKey.of(fluid);
        if (key == null) return 0L;
        StorageViewEntry entry = FLUID_INDEX.get(key);
        return entry == null ? 0L : entry.getAmount();
    }

    // ==================================================================
    // 全量同步
    // ==================================================================

    /**
     * 收到一片数据。
     *
     * <p>
     * {@code transferId} 变了就丢弃上一轮没拼完的残片：服务端连续发两次快照时
     * （比如玩家反复开关界面），旧的那次没必要再拼，也无从判断它是否完整。
     */
    public static void receiveChunk(int transferId, int chunkIndex, int chunkCount, byte[] data) {
        if (chunkCount <= 0 || chunkCount > 4096) return;

        if (transferId != incomingTransferId) {
            incomingTransferId = transferId;
            incomingChunks = new byte[chunkCount][];
            incomingReceived = 0;
        }

        if (incomingChunks == null || incomingChunks.length != chunkCount) {
            incomingChunks = new byte[chunkCount][];
            incomingReceived = 0;
        }

        if (chunkIndex < 0 || chunkIndex >= chunkCount) return;

        if (incomingChunks[chunkIndex] == null) {
            incomingChunks[chunkIndex] = data == null ? new byte[0] : data;
            incomingReceived++;
        }

        if (incomingReceived >= chunkCount) {
            byte[][] finished = incomingChunks;
            incomingChunks = null;
            incomingReceived = 0;
            assemble(finished);
        }
    }

    private static void assemble(byte[][] chunks) {
        int total = 0;
        for (byte[] chunk : chunks) {
            total += chunk == null ? 0 : chunk.length;
        }

        byte[] payload = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            if (chunk == null) continue;
            System.arraycopy(chunk, 0, payload, offset, chunk.length);
            offset += chunk.length;
        }

        try {
            NBTTagCompound root = net.minecraft.nbt.CompressedStreamTools
                .readCompressed(new ByteArrayInputStream(payload));
            applySnapshot(root);
        } catch (Throwable t) {
            FutaGtnhMod.LOG.error("共享存储：解析服务端快照失败", t);
        }
    }

    private static void applySnapshot(NBTTagCompound root) {
        ITEMS.clear();
        FLUIDS.clear();
        ITEM_INDEX.clear();
        FLUID_INDEX.clear();

        if (root != null) {
            NBTTagList itemList = root.getTagList("items", 10);
            for (int i = 0; i < itemList.tagCount(); i++) {
                NBTTagCompound tag = itemList.getCompoundTagAt(i);
                ItemKey key = ItemKey.readFromNbt(tag);
                long amount = tag.getLong("amount");
                if (key == null || amount <= 0L) continue;
                addItem(key, amount);
            }

            NBTTagList fluidList = root.getTagList("fluids", 10);
            for (int i = 0; i < fluidList.tagCount(); i++) {
                NBTTagCompound tag = fluidList.getCompoundTagAt(i);
                FluidKey key = FluidKey.readFromNbt(tag);
                long amount = tag.getLong("amount");
                if (key == null || amount <= 0L) continue;
                addFluid(key, amount);
            }
        }

        ready = true;
        revision++;
    }

    // ==================================================================
    // 增量同步
    // ==================================================================

    /**
     * 应用一批增量改动。
     *
     * <p>
     * 增量里带的是<b>绝对值</b>而不是增减量，所以这个方法是幂等的：
     * 丢一条、重复一条都不会让客户端和服务端长期不一致，
     * 最多是短暂显示错误，下一条就会纠正过来。
     */
    public static void applyDelta(List<PacketStorageDelta.Change> changes) {
        if (changes == null || changes.isEmpty()) return;

        for (PacketStorageDelta.Change change : changes) {
            if (change == null || change.key == null) continue;

            if (change.kind == PacketStorageDelta.KIND_ITEM) {
                ItemKey key = ItemKey.readFromNbt(change.key);
                if (key == null) continue;
                if (change.op == PacketStorageDelta.OP_REMOVE || change.amount <= 0L) {
                    removeItem(key);
                } else {
                    addItem(key, change.amount);
                }
            } else if (change.kind == PacketStorageDelta.KIND_FLUID) {
                FluidKey key = FluidKey.readFromNbt(change.key);
                if (key == null) continue;
                if (change.op == PacketStorageDelta.OP_REMOVE || change.amount <= 0L) {
                    removeFluid(key);
                } else {
                    addFluid(key, change.amount);
                }
            }
        }

        revision++;
    }

    // ==================================================================
    // 内部维护
    // ==================================================================

    private static void addItem(ItemKey key, long amount) {
        StorageViewEntry existing = ITEM_INDEX.get(key);
        if (existing != null) {
            existing.setAmount(amount);
            return;
        }
        try {
            StorageViewEntry entry = StorageViewEntry.ofItem(key, amount);
            ITEM_INDEX.put(key, entry);
            ITEMS.add(entry);
        } catch (Throwable t) {
            // 单个坏物品不能拖垮整个界面：跳过它，其余照常显示
            FutaGtnhMod.LOG.warn("共享存储：无法为 {} 构建显示条目，已跳过", key, t);
        }
    }

    private static void removeItem(ItemKey key) {
        StorageViewEntry existing = ITEM_INDEX.remove(key);
        if (existing != null) {
            ITEMS.remove(existing);
        }
    }

    private static void addFluid(FluidKey key, long amount) {
        StorageViewEntry existing = FLUID_INDEX.get(key);
        if (existing != null) {
            existing.setAmount(amount);
            return;
        }
        try {
            StorageViewEntry entry = StorageViewEntry.ofFluid(key, amount);
            if (entry == null) {
                // GT 没能为这种流体造出显示物品，跳过总比让整个界面崩掉好
                FutaGtnhMod.LOG.warn("共享存储：{} 无法构建显示条目，已跳过", key);
                return;
            }
            FLUID_INDEX.put(key, entry);
            FLUIDS.add(entry);
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("共享存储：无法为 {} 构建显示条目，已跳过", key, t);
        }
    }

    private static void removeFluid(FluidKey key) {
        StorageViewEntry existing = FLUID_INDEX.remove(key);
        if (existing != null) {
            FLUIDS.remove(existing);
        }
    }

    /** 断开连接或切换存档时清空，避免把上一个服务器的数据带过来。 */
    public static void clear() {
        ITEMS.clear();
        FLUIDS.clear();
        ITEM_INDEX.clear();
        FLUID_INDEX.clear();
        incomingChunks = null;
        incomingReceived = 0;
        incomingTransferId = Integer.MIN_VALUE;
        ready = false;
        revision++;
    }
}
