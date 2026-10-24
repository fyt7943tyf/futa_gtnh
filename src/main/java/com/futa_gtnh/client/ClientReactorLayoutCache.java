package com.futa_gtnh.client;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.apache.logging.log4j.Logger;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.reactor.ReactorLayout;

/**
 * 布局档案的<b>客户端镜像</b>（服务端权威，这里只是缓存）。
 *
 * <p>
 * 数据两种，全部由网络包 handler 喂进来：
 * <ul>
 * <li>{@link #receiveChunk}：布局列表全量快照（gzip + 分片，见
 * {@code PacketReactorLayoutSync}），凑齐后整体替换；</li>
 * <li>{@link #receivePreview}：单条布局的完整内容 + 需求清单（选中时按需下发）。</li>
 * </ul>
 *
 * <p>
 * {@link #revision} 在列表变化时自增，界面靠它决定要不要重排 —— 与
 * {@code ClientLootassistCache} 同一套约定。预览是单独一份数据（不参与 revision），
 * 界面在 {@code updateScreen} 里比对自己的 previewId 就知道要不要重画。
 */
public final class ClientReactorLayoutCache {

    private ClientReactorLayoutCache() {}

    private static final Logger LOG = FutaGtnhMod.LOG;

    /** 列表里的一条布局（只有元数据，没有网格内容）。 */
    public static final class Summary {

        public long id;
        public String name = "";
        public boolean fluidCooled;
        public int columns;
        public long createdAt;
        public int used;
    }

    /** 需求清单的一行（服务端算好，这里只管显示）。 */
    public static final class ManifestLine {

        public ItemStack display;
        public String displayName = "";
        public int needed;
        public int havePlayer;
        public int haveStorage;
        public boolean exact;
    }

    private static final Map<Long, Summary> summaries = new LinkedHashMap<>();
    /** 分片快照的归拢缓冲。列表传输是串行的，凑齐即清。 */
    private static final Map<Integer, byte[][]> pendingChunks = new LinkedHashMap<>();

    /** 数据版本号：布局列表变化就会 +1。 */
    public static int revision;

    // ---- 当前选中的那条的完整预览 ----
    public static long previewId = -1L;
    public static String previewName = "";
    public static boolean previewFluid;
    public static int previewColumns;
    public static ItemStack[] previewStacks = new ItemStack[0];
    public static List<ManifestLine> previewManifest = new ArrayList<>();

    // ==================================================================
    // 接收：布局列表
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
        Map<Long, Summary> fresh = new LinkedHashMap<>();
        try {
            NBTTagCompound tag = net.minecraft.nbt.CompressedStreamTools
                .readCompressed(new ByteArrayInputStream(payload));
            NBTTagList list = tag.getTagList("layouts", 10);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound entry = list.getCompoundTagAt(i);
                Summary summary = new Summary();
                summary.id = entry.getLong("id");
                summary.name = entry.getString("name");
                summary.fluidCooled = entry.getBoolean("fluid");
                summary.columns = entry.getInteger("columns");
                summary.createdAt = entry.getLong("created");
                summary.used = entry.getInteger("used");
                fresh.put(summary.id, summary);
            }
        } catch (Exception e) {
            LOG.error("反应堆布局档案：解析列表失败（保留现有缓存）", e);
            return;
        }

        summaries.clear();
        summaries.putAll(fresh);
        revision++;
    }

    // ==================================================================
    // 接收：单条预览
    // ==================================================================

    public static void receivePreview(byte[] payload) {
        if (payload == null || payload.length == 0) {
            clearPreview();
            return;
        }
        try {
            NBTTagCompound tag = net.minecraft.nbt.CompressedStreamTools
                .readCompressed(new ByteArrayInputStream(payload));
            previewId = tag.getLong("id");
            previewName = tag.getString("name");
            previewFluid = tag.getBoolean("fluid");
            previewColumns = tag.getInteger("columns");

            NBTTagList slots = tag.getTagList("slots", 10);
            int columns = Math.max(ReactorLayout.MIN_COLUMNS, Math.min(ReactorLayout.MAX_COLUMNS, previewColumns));
            ItemStack[] stacks = new ItemStack[columns * ReactorLayout.ROWS];
            for (int i = 0; i < slots.tagCount(); i++) {
                NBTTagCompound entry = slots.getCompoundTagAt(i);
                int slot = entry.getInteger("slot");
                if (slot < 0 || slot >= stacks.length) continue;
                Item item = Item.getItemById(entry.getInteger("item"));
                if (item == null) continue;
                ItemStack stack = new ItemStack(item, Math.max(1, entry.getInteger("size")), entry.getInteger("meta"));
                if (entry.hasKey("nbt")) {
                    stack.setTagCompound(entry.getCompoundTag("nbt"));
                }
                stacks[slot] = stack;
            }
            previewStacks = stacks;

            previewManifest = new ArrayList<>();
            NBTTagList manifest = tag.getTagList("manifest", 10);
            for (int i = 0; i < manifest.tagCount(); i++) {
                NBTTagCompound entry = manifest.getCompoundTagAt(i);
                Item item = Item.getItemById(entry.getInteger("item"));
                if (item == null) continue;
                ManifestLine line = new ManifestLine();
                line.display = new ItemStack(item, 1, entry.getInteger("meta"));
                if (entry.hasKey("nbt")) {
                    line.display.setTagCompound(entry.getCompoundTag("nbt"));
                }
                line.displayName = entry.getString("displayName");
                line.needed = entry.getInteger("needed");
                line.havePlayer = entry.getInteger("havePlayer");
                line.haveStorage = entry.getInteger("haveStorage");
                line.exact = entry.getBoolean("exact");
                previewManifest.add(line);
            }
        } catch (Exception e) {
            LOG.error("反应堆布局档案：解析预览失败", e);
            clearPreview();
        }
    }

    public static void clearPreview() {
        previewId = -1L;
        previewName = "";
        previewFluid = false;
        previewColumns = ReactorLayout.MIN_COLUMNS;
        previewStacks = new ItemStack[0];
        previewManifest = new ArrayList<>();
    }

    // ==================================================================
    // 读取
    // ==================================================================

    /** @return 全部布局摘要（按服务端顺序） */
    public static Collection<Summary> snapshot() {
        List<Summary> copy = new ArrayList<>(summaries.size());
        copy.addAll(summaries.values());
        return copy;
    }

    public static Summary get(long id) {
        return summaries.get(id);
    }

    public static boolean isEmpty() {
        return summaries.isEmpty();
    }
}
