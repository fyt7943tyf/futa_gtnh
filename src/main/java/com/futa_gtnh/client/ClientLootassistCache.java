package com.futa_gtnh.client;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.apache.logging.log4j.Logger;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.lootassist.LootassistEntry;

/**
 * 助手共享列表的<b>客户端镜像</b>（服务端权威，这里只是缓存）。
 *
 * <p>
 * 数据来源三种，全部由网络包 handler 喂进来：
 * <ul>
 * <li>{@link #receiveChunk}：全量快照（gzip + 分片，见 {@code PacketLootassistSync}），
 * 凑齐后整体替换；</li>
 * <li>{@link #upsert} / {@link #remove}：单条增量；</li>
 * <li>{@link #receiveProgress}：搜索进度（不是数据，给界面进度条用）。</li>
 * </ul>
 *
 * <p>
 * {@link #revision} 在每次数据变化时自增，界面靠「上次绘制时的 revision 是否变了」
 * 决定要不要重新排序/过滤 —— 比每 tick 全量重排省心，也比「等包来了回调界面」
 * 简单（界面可能还没开）。
 */
public final class ClientLootassistCache {

    private ClientLootassistCache() {}

    private static final Logger LOG = FutaGtnhMod.LOG;

    private static final Map<String, LootassistEntry> entries = new LinkedHashMap<>();
    /** 分片快照的归拢缓冲：transferId → 各片。快照传输是串行的，凑齐即清。 */
    private static final Map<Integer, byte[][]> pendingChunks = new LinkedHashMap<>();

    /** 搜索进度（绝对值，见 {@code PacketLootassistProgress}）。 */
    public static int progressDone;
    public static int progressTotal;
    public static int progressFound;
    public static boolean searching;

    /** 数据版本号：任何条目变化都会 +1。 */
    public static int revision;

    // ==================================================================
    // 接收
    // ==================================================================

    public static void receiveChunk(int transferId, int chunkIndex, int chunkCount, byte[] data) {
        if (chunkCount <= 1) {
            applySnapshot(data);
            return;
        }

        byte[][] chunks = pendingChunks.get(transferId);
        if (chunks == null || chunks.length != chunkCount) {
            chunks = new byte[chunkCount][];
            pendingChunks.put(transferId, chunks);
        }
        chunks[chunkIndex] = data;

        for (byte[] chunk : chunks) {
            if (chunk == null) return; // 还没凑齐
        }

        pendingChunks.remove(transferId);
        int length = 0;
        for (byte[] chunk : chunks) {
            length += chunk.length;
        }
        byte[] payload = new byte[length];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, payload, offset, chunk.length);
            offset += chunk.length;
        }
        applySnapshot(payload);
    }

    private static void applySnapshot(byte[] payload) {
        Map<String, LootassistEntry> fresh = new LinkedHashMap<>();
        try {
            NBTTagCompound tag = net.minecraft.nbt.CompressedStreamTools
                .readCompressed(new ByteArrayInputStream(payload));
            NBTTagList list = tag.getTagList("entries", 10);
            for (int i = 0; i < list.tagCount(); i++) {
                LootassistEntry entry = LootassistEntry.readFrom(list.getCompoundTagAt(i));
                fresh.put(entry.key(), entry);
            }
        } catch (Exception e) {
            LOG.error("小游戏助手：解析快照失败（保留现有缓存）", e);
            return;
        }

        entries.clear();
        entries.putAll(fresh);
        revision++;
    }

    public static void upsert(LootassistEntry entry) {
        entries.put(entry.key(), entry);
        revision++;
    }

    public static void remove(String key) {
        if (entries.remove(key) != null) {
            revision++;
        }
    }

    public static void receiveProgress(int done, int total, int found, boolean running) {
        progressDone = done;
        progressTotal = total;
        progressFound = found;
        searching = running;
    }

    // ==================================================================
    // 读取
    // ==================================================================

    /** @return 全部条目的快照列表（界面自己排序/过滤，不缓存视图） */
    public static List<LootassistEntry> snapshot() {
        List<LootassistEntry> copy = new ArrayList<>(entries.size());
        copy.addAll(entries.values());
        return copy;
    }

    public static Collection<LootassistEntry> values() {
        return entries.values();
    }

    public static boolean isEmpty() {
        return entries.isEmpty();
    }
}
