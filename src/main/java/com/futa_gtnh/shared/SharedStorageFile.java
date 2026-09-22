package com.futa_gtnh.shared;

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

import net.minecraft.nbt.NBTTagCompound;

import org.apache.logging.log4j.Logger;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 共享存储的落盘逻辑。
 *
 * <p>
 * 刻意<b>不用</b> {@code WorldSavedData}/{@code MapStorage}：那两个东西的落盘时机
 * 和路径（level.dat 还是 data/ 目录）在 1.7.10 里各版本行为不一致，而且没法控制
 * 写入的原子性。这份数据是全服唯一的，写坏了整个服务器的东西就没了，所以自己管：
 *
 * <ul>
 * <li>写到 {@code <存档目录>/data/futa_gtnh_shared.dat}，和存档一起走，
 * 不会被别的模组碰到；</li>
 * <li>先写 {@code .tmp}，再原子替换正式文件 —— 中途断电/崩服不会留下半个文件；</li>
 * <li>替换前把上一份留成 {@code .bak}。读到坏文件时自动回退到备份，
 * 而不是把玩家的东西清空。</li>
 * </ul>
 */
public final class SharedStorageFile {

    private SharedStorageFile() {}

    public static final String FILE_NAME = "futa_gtnh_shared.dat";
    private static final String TMP_SUFFIX = ".tmp";
    private static final String BAK_SUFFIX = ".bak";
    private static final String DATA_DIR = "data";

    private static final Logger LOG = FutaGtnhMod.LOG;

    /** @return 数据文件路径（不保证存在） */
    public static File fileFor(File worldDirectory) {
        return new File(new File(worldDirectory, DATA_DIR), FILE_NAME);
    }

    /**
     * 读取共享存储；文件不存在时返回一个空存储。
     *
     * <p>
     * 主文件读失败会自动尝试 {@code .bak}。两个都读不出来时<b>返回空存储并打印错误</b>，
     * 而不是抛异常 —— 崩服比丢数据更糟，而且此时把坏文件留在原地还有人工抢救的余地。
     */
    public static SharedStorage load(File worldDirectory) {
        SharedStorage storage = new SharedStorage();
        File primary = fileFor(worldDirectory);
        File backup = new File(primary.getPath() + BAK_SUFFIX);

        if (primary.isFile()) {
            if (tryLoadInto(storage, primary)) {
                logLoaded(primary, storage);
                return storage;
            }
            LOG.error("共享存储：主文件 {} 读取失败，尝试备份 {}", primary.getName(), backup.getName());
        } else {
            // 主文件不在，但备份还在 —— 这恰恰是 save() 换文件换到一半崩掉的样子。
            // 不看一眼备份就空手启动的话，明明完整的数据就躺在旁边却当作没有，
            // 玩家打开界面会看到空空如也。
            LOG.warn("共享存储：没有找到 {}，检查备份 {}", primary.getName(), backup.getName());
        }

        if (backup.isFile() && tryLoadInto(storage, backup)) {
            LOG.error(
                "共享存储：已从备份 {} 恢复 {} 种物品 / {} 种流体。请人工检查主文件为何缺失或损坏。",
                backup.getName(),
                storage.itemTypeCount(),
                storage.fluidTypeCount());
            return storage;
        }

        if (primary.isFile()) {
            LOG.error("共享存储：主文件和备份都无法读取，将以空存储启动。原文件保留在 {} 供人工抢救。", primary.getPath());
        } else {
            LOG.info("共享存储：主文件和备份都不存在，从空存储开始");
        }
        storage.clear();
        storage.markClean();
        return storage;
    }

    private static void logLoaded(File file, SharedStorage storage) {
        LOG.info(
            "共享存储：已从 {} 载入 {} 种物品 / {} 种流体（另有 {} / {} 条因缺少对应模组被暂存）",
            file.getName(),
            storage.itemTypeCount(),
            storage.fluidTypeCount(),
            storage.getUnreadableItemCount(),
            storage.getUnreadableFluidCount());
        storage.markClean();
    }

    private static boolean tryLoadInto(SharedStorage storage, File file) {
        try (InputStream raw = new FileInputStream(file); InputStream in = new BufferedInputStream(raw)) {
            NBTTagCompound tag = net.minecraft.nbt.CompressedStreamTools.readCompressed(in);

            int version = SharedStorage.readFormatVersion(tag);
            if (version > SharedStorage.FORMAT_VERSION) {
                // 用新版存档去喂旧代码，字段含义可能已经变了，硬读会把东西读错位。
                // 宁可不读 —— 文件原样留着，把模组升回去就能恢复。
                LOG.error(
                    "共享存储：{} 的格式版本是 {}，本模组只支持到 {}。拒绝载入以免读错数据，文件已原样保留。",
                    file.getName(),
                    version,
                    SharedStorage.FORMAT_VERSION);
                return false;
            }

            storage.readFromNbt(tag);
            return true;
        } catch (Exception e) {
            LOG.error("共享存储：读取 {} 失败", file.getPath(), e);
            return false;
        }
    }

    /**
     * 原子写入。调用方负责判断 {@link SharedStorage#isDirty()}。
     *
     * @return 是否成功
     */
    public static boolean save(File worldDirectory, SharedStorage storage) {
        File target = fileFor(worldDirectory);
        File dir = target.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            LOG.error("共享存储：无法创建目录 {}", dir.getPath());
            return false;
        }

        File tmp = new File(target.getPath() + TMP_SUFFIX);
        try {
            try (OutputStream raw = new FileOutputStream(tmp); OutputStream out = new BufferedOutputStream(raw)) {
                net.minecraft.nbt.CompressedStreamTools.writeCompressed(storage.writeToNbt(), out);
            } catch (IOException e) {
                LOG.error("共享存储：写入临时文件 {} 失败", tmp.getPath(), e);
                deleteQuietly(tmp);
                return false;
            }

            // 保留上一份为备份；失败不致命，只是少了一层保险
            // 用「复制」而不是「移动」来留备份。
            //
            // 移动的话会有一个窗口期：正式文件已经挪走、新文件还没就位。
            // 万一这中间替换失败（文件被杀软/备份软件占住、磁盘满、权限问题），
            // 正式文件就没了，只剩一个 save() 返回 false —— 下次启动会以为
            // 「从来就没有过数据」。复制的话正式文件全程都在，最坏也只是备份旧一点。
            if (target.isFile()) {
                File bak = new File(target.getPath() + BAK_SUFFIX);
                try {
                    Files.copy(target.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    LOG.warn("共享存储：备份旧文件失败（继续写入新文件）", e);
                }
            }

            try {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // 极少数文件系统不支持原子替换，退化成普通移动
                LOG.warn("共享存储：原子替换失败，改用普通移动", e);
                if (!tmp.renameTo(target)) {
                    LOG.error("共享存储：重命名 {} -> {} 失败", tmp.getPath(), target.getPath());
                    deleteQuietly(tmp);
                    return false;
                }
            }
        } catch (Exception e) {
            LOG.error("共享存储：保存失败", e);
            deleteQuietly(tmp);
            return false;
        }

        return true;
    }

    private static void deleteQuietly(File file) {
        if (file.isFile() && !file.delete()) {
            LOG.warn("共享存储：无法删除临时文件 {}", file.getPath());
        }
    }
}
