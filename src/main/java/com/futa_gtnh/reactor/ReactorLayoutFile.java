package com.futa_gtnh.reactor;

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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.apache.logging.log4j.Logger;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 反应堆布局档案的落盘逻辑 —— 和 {@code SharedStorageFile} / {@code LootassistFile}
 * 完全同一套保障，只是数据不同：
 *
 * <ul>
 * <li>写到 {@code <主世界存档>/data/futa_gtnh_reactor_layouts.dat}，跟存档走；</li>
 * <li>先写 {@code .tmp} 再原子替换，替换前把上一份留成 {@code .bak}；</li>
 * <li>读到坏文件自动回退备份，实在不行就空档案启动。布局是玩家手攒的配置，
 * 丢了得一条条重新记录，所以能保就保。</li>
 * </ul>
 *
 * <p>
 * 条目里的物品 id 是<b>注册表 id</b>，IC2 缺席/换版本时解析失败的单格会被跳过
 * （见 {@link ReactorLayout#readFrom}），档案不会因此整份报废。
 */
public final class ReactorLayoutFile {

    private ReactorLayoutFile() {}

    public static final String FILE_NAME = "futa_gtnh_reactor_layouts.dat";
    private static final String TMP_SUFFIX = ".tmp";
    private static final String BAK_SUFFIX = ".bak";
    private static final String DATA_DIR = "data";
    private static final int FORMAT_VERSION = 1;

    private static final Logger LOG = FutaGtnhMod.LOG;

    /** @return 数据文件路径（不保证存在） */
    public static File fileFor(File worldDirectory) {
        return new File(new File(worldDirectory, DATA_DIR), FILE_NAME);
    }

    /** 读出来的全部数据。 */
    public static final class Loaded {

        public final Map<Long, ReactorLayout> layouts = new LinkedHashMap<>();
        /** 布局 id 的单调计数器（跨重启不重号，重号会顶掉旧条目）。 */
        public long nextId = 1L;
    }

    /** @return 读出来的全部数据；读不到任何有效数据时返回 null（调用方按空档案处理） */
    public static Loaded load(File worldDirectory) {
        File primary = fileFor(worldDirectory);
        File backup = new File(primary.getPath() + BAK_SUFFIX);

        if (primary.isFile()) {
            Loaded loaded = tryLoad(primary);
            if (loaded != null) {
                LOG.info("反应堆布局档案：已从 {} 载入 {} 条布局", primary.getName(), loaded.layouts.size());
                return loaded;
            }
            LOG.error("反应堆布局档案：主文件 {} 读取失败，尝试备份 {}", primary.getName(), backup.getName());
        }

        if (backup.isFile()) {
            Loaded loaded = tryLoad(backup);
            if (loaded != null) {
                LOG.error("反应堆布局档案：已从备份 {} 恢复 {} 条布局。请人工检查主文件为何缺失或损坏。", backup.getName(), loaded.layouts.size());
                return loaded;
            }
        }

        if (primary.isFile()) {
            LOG.error("反应堆布局档案：主文件和备份都无法读取，将以空档案启动。原文件保留在 {} 供人工抢救。", primary.getPath());
        } else {
            LOG.info("反应堆布局档案：还没有数据文件，从空档案开始");
        }
        return null;
    }

    private static Loaded tryLoad(File file) {
        try (InputStream raw = new FileInputStream(file); InputStream in = new BufferedInputStream(raw)) {
            NBTTagCompound tag = net.minecraft.nbt.CompressedStreamTools.readCompressed(in);

            int version = tag.getInteger("format_version");
            if (version > FORMAT_VERSION) {
                LOG.error("反应堆布局档案：{} 的格式版本是 {}，本模组只支持到 {}。拒绝载入以免读错数据。", file.getName(), version, FORMAT_VERSION);
                return null;
            }

            Loaded loaded = new Loaded();
            loaded.nextId = Math.max(1L, tag.getLong("next_id"));
            NBTTagList list = tag.getTagList("layouts", 10);
            for (int i = 0; i < list.tagCount(); i++) {
                ReactorLayout layout = ReactorLayout.readFrom(list.getCompoundTagAt(i));
                if (layout.id <= 0L) continue;
                loaded.layouts.put(layout.id, layout);
                loaded.nextId = Math.max(loaded.nextId, layout.id + 1L);
            }
            return loaded;
        } catch (Exception e) {
            LOG.error("反应堆布局档案：读取 {} 失败", file.getPath(), e);
            return null;
        }
    }

    /**
     * 原子写入全部布局。
     *
     * @return 是否成功
     */
    public static boolean save(File worldDirectory, Collection<ReactorLayout> layouts, long nextId) {
        File target = fileFor(worldDirectory);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            LOG.error("反应堆布局档案：无法创建目录 {}", dir.getPath());
            return false;
        }

        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("format_version", FORMAT_VERSION);
        tag.setLong("next_id", nextId);

        NBTTagList list = new NBTTagList();
        for (ReactorLayout layout : layouts) {
            NBTTagCompound entry = new NBTTagCompound();
            layout.writeTo(entry);
            list.appendTag(entry);
        }
        tag.setTag("layouts", list);

        File tmp = new File(target.getPath() + TMP_SUFFIX);
        try {
            try (OutputStream raw = new FileOutputStream(tmp); OutputStream out = new BufferedOutputStream(raw)) {
                net.minecraft.nbt.CompressedStreamTools.writeCompressed(tag, out);
            } catch (IOException e) {
                LOG.error("反应堆布局档案：写入临时文件 {} 失败", tmp.getPath(), e);
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
                    LOG.warn("反应堆布局档案：备份旧文件失败（继续写入新文件）", e);
                }
            }

            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                LOG.warn("反应堆布局档案：原子替换失败，改用普通移动", e);
                if (!tmp.renameTo(target)) {
                    LOG.error("反应堆布局档案：重命名 {} -> {} 失败", tmp.getPath(), target.getPath());
                    deleteQuietly(tmp);
                    return false;
                }
            }
        } catch (Exception e) {
            LOG.error("反应堆布局档案：保存失败", e);
            deleteQuietly(tmp);
            return false;
        }
        return true;
    }

    private static void deleteQuietly(File file) {
        if (file.isFile() && !file.delete()) {
            LOG.warn("反应堆布局档案：无法删除临时文件 {}", file.getPath());
        }
    }
}
