package com.futa_gtnh.lootassist;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.apache.logging.log4j.Logger;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 小游戏助手共享列表的落盘逻辑 —— 和 {@code com.futa_gtnh.shared.SharedStorageFile}
 * 完全同一套保障，只是数据不同：
 *
 * <ul>
 * <li>写到 {@code <主世界存档>/data/futa_gtnh_lootassist.dat}，跟存档走；</li>
 * <li>先写 {@code .tmp} 再原子替换，替换前把上一份留成 {@code .bak}；</li>
 * <li>读到坏文件自动回退备份，实在不行就空表启动（数据只是「标记 + 点位」，
 * 丢了可以重新搜索，但能保就保）。</li>
 * </ul>
 */
public final class LootassistFile {

    private LootassistFile() {}

    public static final String FILE_NAME = "futa_gtnh_lootassist.dat";
    private static final String TMP_SUFFIX = ".tmp";
    private static final String BAK_SUFFIX = ".bak";
    private static final String DATA_DIR = "data";
    private static final int FORMAT_VERSION = 1;

    private static final Logger LOG = FutaGtnhMod.LOG;

    /** @return 数据文件路径（不保证存在） */
    public static File fileFor(File worldDirectory) {
        return new File(new File(worldDirectory, DATA_DIR), FILE_NAME);
    }

    /** @return 读出来的全部条目；读不到任何有效数据时返回 null（调用方按空表处理） */
    public static Map<String, LootassistEntry> loadEntries(File worldDirectory) {
        File primary = fileFor(worldDirectory);
        File backup = new File(primary.getPath() + BAK_SUFFIX);

        if (primary.isFile()) {
            Loaded loaded = tryLoad(primary);
            if (loaded != null) {
                LOG.info(
                    "小游戏助手：已从 {} 载入 {} 条地牢记录（{} 条已验证不存在的区块缓存）",
                    primary.getName(),
                    loaded.entries.size(),
                    loaded.absentChunks.size());
                LootassistManager.restoreAbsentChunks(loaded.absentChunks);
                return loaded.entries;
            }
            LOG.error("小游戏助手：主文件 {} 读取失败，尝试备份 {}", primary.getName(), backup.getName());
        }

        if (backup.isFile()) {
            Loaded loaded = tryLoad(backup);
            if (loaded != null) {
                LOG.error("小游戏助手：已从备份 {} 恢复 {} 条地牢记录。请人工检查主文件为何缺失或损坏。", backup.getName(), loaded.entries.size());
                // 备份里的「不存在缓存」也要一并恢复，否则已确认过没有地牢的区块会被反复生成
                LootassistManager.restoreAbsentChunks(loaded.absentChunks);
                return loaded.entries;
            }
        }

        if (primary.isFile()) {
            LOG.error("小游戏助手：主文件和备份都无法读取，将以空列表启动。原文件保留在 {} 供人工抢救。", primary.getPath());
        } else {
            LOG.info("小游戏助手：还没有数据文件，从空列表开始");
        }
        return null;
    }

    /** 把「已验证不存在」的区块缓存也读出来（只有备份恢复路径需要它）。 */
    private static Loaded tryLoad(File file) {
        try (InputStream raw = new FileInputStream(file); InputStream in = new BufferedInputStream(raw)) {
            NBTTagCompound tag = net.minecraft.nbt.CompressedStreamTools.readCompressed(in);

            int version = tag.getInteger("format_version");
            if (version > FORMAT_VERSION) {
                LOG.error("小游戏助手：{} 的格式版本是 {}，本模组只支持到 {}。拒绝载入以免读错数据。", file.getName(), version, FORMAT_VERSION);
                return null;
            }

            Loaded loaded = new Loaded();
            NBTTagList entryList = tag.getTagList("entries", 10);
            for (int i = 0; i < entryList.tagCount(); i++) {
                LootassistEntry entry = LootassistEntry.readFrom(entryList.getCompoundTagAt(i));
                loaded.entries.put(entry.key(), entry);
            }
            NBTTagList absentList = tag.getTagList("absent_chunks", 8);
            for (int i = 0; i < absentList.tagCount(); i++) {
                loaded.absentChunks.add(absentList.getStringTagAt(i));
            }
            return loaded;
        } catch (Exception e) {
            LOG.error("小游戏助手：读取 {} 失败", file.getPath(), e);
            return null;
        }
    }

    /**
     * 原子写入全部数据（条目 + 不存在缓存）。
     *
     * @return 是否成功
     */
    public static boolean save(File worldDirectory, Map<String, LootassistEntry> entries, Set<String> absentChunks) {
        File target = fileFor(worldDirectory);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            LOG.error("小游戏助手：无法创建目录 {}", dir.getPath());
            return false;
        }

        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("format_version", FORMAT_VERSION);

        NBTTagList entryList = new NBTTagList();
        for (LootassistEntry entry : entries.values()) {
            NBTTagCompound entryTag = new NBTTagCompound();
            entry.writeTo(entryTag);
            entryList.appendTag(entryTag);
        }
        tag.setTag("entries", entryList);

        NBTTagList absentList = new NBTTagList();
        for (String chunkKey : absentChunks) {
            absentList.appendTag(new net.minecraft.nbt.NBTTagString(chunkKey));
        }
        tag.setTag("absent_chunks", absentList);

        File tmp = new File(target.getPath() + TMP_SUFFIX);
        try {
            try (OutputStream raw = new FileOutputStream(tmp); OutputStream out = new BufferedOutputStream(raw)) {
                net.minecraft.nbt.CompressedStreamTools.writeCompressed(tag, out);
            } catch (IOException e) {
                LOG.error("小游戏助手：写入临时文件 {} 失败", tmp.getPath(), e);
                deleteQuietly(tmp);
                return false;
            }

            if (target.isFile()) {
                try {
                    Files.copy(
                        target.toPath(),
                        new File(target.getPath() + BAK_SUFFIX).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.warn("小游戏助手：备份旧文件失败（继续写入新文件）", e);
                }
            }

            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("小游戏助手：原子替换失败，改用普通移动", e);
                if (!tmp.renameTo(target)) {
                    LOG.error("小游戏助手：重命名 {} -> {} 失败", tmp.getPath(), target.getPath());
                    deleteQuietly(tmp);
                    return false;
                }
            }
        } catch (Exception e) {
            LOG.error("小游戏助手：保存失败", e);
            deleteQuietly(tmp);
            return false;
        }
        return true;
    }

    private static void deleteQuietly(File file) {
        if (file.isFile() && !file.delete()) {
            LOG.warn("小游戏助手：无法删除临时文件 {}", file.getPath());
        }
    }

    /** load 的内部装货容器。 */
    private static final class Loaded {

        final Map<String, LootassistEntry> entries = new LinkedHashMap<>();
        final Set<String> absentChunks = new HashSet<>();
    }
}
