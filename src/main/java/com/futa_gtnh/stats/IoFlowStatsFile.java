package com.futa_gtnh.stats;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.apache.logging.log4j.Logger;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * IO 流量统计的落盘逻辑。
 *
 * <p>
 * 和 {@code SharedStorageFile} 同一套做法（那边的类注释里有完整理由）：
 * 不用 {@code WorldSavedData}，自己写到 {@code <存档目录>/data/futa_gtnh_io_flow.dat}，
 * 先写 {@code .tmp} 再原子替换，替换前留 {@code .bak}，坏文件自动回退备份。
 *
 * <p>
 * 1.7.10 的 NBT 没有 long 数组类型，环形历史用 {@code int[]} 按「高 32 位在前、
 * 低 32 位在后」打包存储。
 */
public final class IoFlowStatsFile {

    private IoFlowStatsFile() {}

    public static final String FILE_NAME = "futa_gtnh_io_flow.dat";
    private static final String TMP_SUFFIX = ".tmp";
    private static final String BAK_SUFFIX = ".bak";
    private static final String DATA_DIR = "data";

    private static final Logger LOG = FutaGtnhMod.LOG;

    /** @return 数据文件路径（不保证存在） */
    public static File fileFor(File worldDirectory) {
        return new File(new File(worldDirectory, DATA_DIR), FILE_NAME);
    }

    /** 读取到节点表。文件不存在 / 读不出来时返回空表，绝不抛异常。 */
    public static void load(File worldDirectory, Map<String, IoFlowStats.NodeRecord> nodes) {
        File primary = fileFor(worldDirectory);
        File backup = new File(primary.getPath() + BAK_SUFFIX);

        if (primary.isFile()) {
            if (tryLoadInto(primary, nodes)) return;
            LOG.error("IO 流量统计：主文件 {} 读取失败，尝试备份", primary.getName());
        }
        if (backup.isFile() && tryLoadInto(backup, nodes)) {
            LOG.error("IO 流量统计：已从备份 {} 恢复 {} 个节点。请检查主文件为何损坏。", backup.getName(), nodes.size());
            return;
        }
        if (primary.isFile()) {
            LOG.error("IO 流量统计：主文件和备份都无法读取，从空记录开始。原文件保留在 {} 供人工抢救。", primary.getPath());
        }
    }

    private static boolean tryLoadInto(File file, Map<String, IoFlowStats.NodeRecord> nodes) {
        try (InputStream raw = new FileInputStream(file); InputStream in = new BufferedInputStream(raw)) {
            NBTTagCompound tag = net.minecraft.nbt.CompressedStreamTools.readCompressed(in);

            int version = tag.getInteger("formatVersion");
            if (version > IoFlowStats.FORMAT_VERSION) {
                LOG.error(
                    "IO 流量统计：{} 的格式版本是 {}，本模组只支持到 {}。拒绝载入，文件已原样保留。",
                    file.getName(),
                    version,
                    IoFlowStats.FORMAT_VERSION);
                return false;
            }

            NBTTagList nodeTags = tag.getTagList("nodes", 10);
            for (int i = 0; i < nodeTags.tagCount(); i++) {
                IoFlowStats.NodeRecord node = readNode(nodeTags.getCompoundTagAt(i));
                if (node != null) nodes.put(node.key, node);
            }
            return true;
        } catch (Exception e) {
            LOG.error("IO 流量统计：读取 {} 失败", file.getPath(), e);
            return false;
        }
    }

    private static IoFlowStats.NodeRecord readNode(NBTTagCompound tag) {
        int dim = tag.getInteger("d");
        int x = tag.getInteger("x");
        int y = tag.getInteger("y");
        int z = tag.getInteger("z");
        IoFlowStats.NodeRecord node = new IoFlowStats.NodeRecord(dim, x, y, z, tag.getByte("t") == 1);
        node.name = tag.getString("n");
        node.mode = tag.getByte("m");
        if (node.mode < 0 || node.mode >= TerminalIoConfig.StatsMode.values().length) node.mode = 2;
        node.statsMode = TerminalIoConfig.StatsMode.values()[node.mode];
        node.enabled = tag.getBoolean("e");
        node.online = false; // 重启后一律从离线开始，等心跳把它点亮
        node.lastSeenTick = 0;
        node.ringIndex = tag.getInteger("idx");
        node.ringCount = tag.getInteger("cnt");
        unpackRing(tag, "ii", node.ringItemIn);
        unpackRing(tag, "io", node.ringItemOut);
        unpackRing(tag, "fi", node.ringFluidIn);
        unpackRing(tag, "fo", node.ringFluidOut);
        node.totalItemIn = tag.getLong("tii");
        node.totalItemOut = tag.getLong("tio");
        node.totalFluidIn = tag.getLong("tfi");
        node.totalFluidOut = tag.getLong("tfo");

        NBTTagList resourceTags = tag.getTagList("res", 10);
        for (int i = 0; i < resourceTags.tagCount() && node.resources.size() < IoFlowStats.MAX_TRACKED_RESOURCES; i++) {
            NBTTagCompound resourceTag = resourceTags.getCompoundTagAt(i);
            Object key = readResourceKey(resourceTag);
            if (key == null) continue;
            IoFlowStats.ResRecord record = new IoFlowStats.ResRecord(resourceTag.getBoolean("fl"));
            record.totalIn = resourceTag.getLong("ti");
            record.totalOut = resourceTag.getLong("to");
            unpackRing(resourceTag, "ri", record.ringIn);
            unpackRing(resourceTag, "ro", record.ringOut);
            node.resources.put(key, record);
        }
        return node;
    }

    /** @return ItemKey / FluidKey / 「其他」哨兵；资源所属模组已被卸载时返回 null（丢弃该条历史） */
    private static Object readResourceKey(NBTTagCompound tag) {
        byte aggregate = tag.getByte("agg");
        if (aggregate == 1) return IoFlowStats.OTHERS_ITEM;
        if (aggregate == 2) return IoFlowStats.OTHERS_FLUID;

        if (tag.getBoolean("fl")) {
            NBTTagCompound fluidTag = tag.getCompoundTag("fluid");
            FluidKey key = FluidKey.readFromNbt(fluidTag);
            return key != null && key.getFluid() != null ? key : null;
        }
        NBTTagCompound itemTag = tag.getCompoundTag("item");
        ItemKey key = ItemKey.readFromNbt(itemTag);
        return key != null && key.getItem() != null ? key : null;
    }

    /** 原子写入。调用方负责脏判断。 */
    public static boolean save(File worldDirectory, Map<String, IoFlowStats.NodeRecord> nodes) {
        File target = fileFor(worldDirectory);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            LOG.error("IO 流量统计：无法创建目录 {}", dir.getPath());
            return false;
        }

        File tmp = new File(target.getPath() + TMP_SUFFIX);
        try {
            try (OutputStream raw = new FileOutputStream(tmp); OutputStream out = new BufferedOutputStream(raw)) {
                net.minecraft.nbt.CompressedStreamTools.writeCompressed(writeRoot(nodes), out);
            } catch (IOException e) {
                LOG.error("IO 流量统计：写入临时文件 {} 失败", tmp.getPath(), e);
                deleteQuietly(tmp);
                return false;
            }

            // 备份与替换的策略与 SharedStorageFile 相同：复制留备份（全程不断档），再原子替换
            if (target.isFile()) {
                File bak = new File(target.getPath() + BAK_SUFFIX);
                try {
                    Files.copy(target.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.warn("IO 流量统计：备份旧文件失败（继续写入新文件）", e);
                }
            }

            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("IO 流量统计：原子替换失败，改用普通移动", e);
                if (!tmp.renameTo(target)) {
                    LOG.error("IO 流量统计：重命名 {} -> {} 失败", tmp.getPath(), target.getPath());
                    deleteQuietly(tmp);
                    return false;
                }
            }
        } catch (Exception e) {
            LOG.error("IO 流量统计：保存失败", e);
            deleteQuietly(tmp);
            return false;
        }

        return true;
    }

    private static NBTTagCompound writeRoot(Map<String, IoFlowStats.NodeRecord> nodes) {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("formatVersion", IoFlowStats.FORMAT_VERSION);
        NBTTagList nodeTags = new NBTTagList();
        for (IoFlowStats.NodeRecord node : nodes.values()) {
            nodeTags.appendTag(writeNode(node));
        }
        root.setTag("nodes", nodeTags);
        return root;
    }

    private static NBTTagCompound writeNode(IoFlowStats.NodeRecord node) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("d", node.dim);
        tag.setInteger("x", node.x);
        tag.setInteger("y", node.y);
        tag.setInteger("z", node.z);
        tag.setByte("t", node.type);
        if (node.name != null && !node.name.isEmpty()) tag.setString("n", node.name);
        tag.setByte("m", node.mode);
        tag.setBoolean("e", node.enabled);
        tag.setInteger("idx", node.ringIndex);
        tag.setInteger("cnt", node.ringCount);
        packRing(tag, "ii", node.ringItemIn);
        packRing(tag, "io", node.ringItemOut);
        packRing(tag, "fi", node.ringFluidIn);
        packRing(tag, "fo", node.ringFluidOut);
        tag.setLong("tii", node.totalItemIn);
        tag.setLong("tio", node.totalItemOut);
        tag.setLong("tfi", node.totalFluidIn);
        tag.setLong("tfo", node.totalFluidOut);

        NBTTagList resourceTags = new NBTTagList();
        for (Map.Entry<Object, IoFlowStats.ResRecord> entry : node.resources.entrySet()) {
            NBTTagCompound resourceTag = new NBTTagCompound();
            IoFlowStats.ResRecord record = entry.getValue();
            resourceTag.setBoolean("fl", record.fluid);
            if (entry.getKey() == IoFlowStats.OTHERS_ITEM) {
                resourceTag.setByte("agg", (byte) 1);
            } else if (entry.getKey() == IoFlowStats.OTHERS_FLUID) {
                resourceTag.setByte("agg", (byte) 2);
            } else if (record.fluid && entry.getKey() instanceof FluidKey) {
                resourceTag.setTag("fluid", ((FluidKey) entry.getKey()).writeToNbt());
            } else if (!record.fluid && entry.getKey() instanceof ItemKey) {
                resourceTag.setTag("item", ((ItemKey) entry.getKey()).writeToNbt());
            } else {
                continue; // 键和类型对不上（理论不可能）：宁可不存，不存错
            }
            resourceTag.setLong("ti", record.totalIn);
            resourceTag.setLong("to", record.totalOut);
            packRing(resourceTag, "ri", record.ringIn);
            packRing(resourceTag, "ro", record.ringOut);
            resourceTags.appendTag(resourceTag);
        }
        tag.setTag("res", resourceTags);
        return tag;
    }

    // ==================================================================
    // long[] 与 int[] 的互转（1.7.10 的 NBT 没有 long 数组）
    // ==================================================================

    private static void packRing(NBTTagCompound tag, String name, long[] ring) {
        int[] packed = new int[ring.length * 2];
        for (int i = 0; i < ring.length; i++) {
            packed[i * 2] = (int) (ring[i] >>> 32);
            packed[i * 2 + 1] = (int) ring[i];
        }
        tag.setIntArray(name, packed);
    }

    private static void unpackRing(NBTTagCompound tag, String name, long[] ring) {
        int[] packed = tag.getIntArray(name);
        if (packed.length == 0) return; // 键不存在：保持全零
        int count = Math.min(ring.length, packed.length / 2);
        for (int i = 0; i < count; i++) {
            ring[i] = ((long) packed[i * 2] << 32) | (packed[i * 2 + 1] & 0xFFFFFFFFL);
        }
    }

    private static void deleteQuietly(File file) {
        if (file.isFile() && !file.delete()) {
            LOG.warn("IO 流量统计：无法删除临时文件 {}", file);
        }
    }
}
