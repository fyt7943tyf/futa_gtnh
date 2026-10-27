package com.futa_gtnh.shared;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import net.minecraft.nbt.NBTTagCompound;

import org.apache.logging.log4j.Logger;

import com.futa_gtnh.FutaGtnhMod;

/**
 * Legacy file codec used only during native AE migration and by offline legacy regression fixtures.
 * Once native ownership is committed, both legacy loading and writing are rejected. Normal runtime
 * persistence is Minecraft WorldSavedData containing the original native storage-cell ItemStack NBT.
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
     * 主文件损坏时尝试 {@code .bak}。已有文件都无法读取或格式过新时抛异常，
     * 避免把读取失败当成新仓库，并在后续保存中覆盖原文件和备份。
     */
    public static synchronized SharedStorage load(File worldDirectory) {
        if (NativeMeMigration.isCommitted(worldDirectory))
            throw new IllegalStateException("Legacy shared inventory is archived; use native ME storage");
        SharedMigrationState.checkReady(worldDirectory, true);
        return loadSource(worldDirectory).storage;
    }

    /**
     * Claims the existing pool for the AE backend without moving any stock. Call before publishing
     * the storage to players or machines. Migration failures leave the existing data untouched and
     * must abort startup; they must never fall back to a new writable pool.
     */
    public static synchronized SharedStorage loadForAe(File worldDirectory) {
        if (NativeMeMigration.isCommitted(worldDirectory))
            throw new IllegalStateException("Legacy shared inventory is archived; use native ME storage");
        if (SharedMigrationState.isCommitted(worldDirectory)) {
            SharedMigrationState.checkReady(worldDirectory, true);
            return loadSource(worldDirectory).storage;
        }
        LoadedStorage loaded = loadSource(worldDirectory);
        File source = loaded.source;
        if (source == null) {
            // Even an empty new world needs durable data before its migration marker is committed.
            // This also refuses a stranded migration backup rather than resurrecting old stock.
            if (!save(worldDirectory, loaded.storage)) {
                throw new IllegalStateException("共享存储：无法为 AE 迁移建立初始数据文件");
            }
            source = fileFor(worldDirectory);
        }
        SharedMigrationState.commit(worldDirectory, source);
        return loaded.storage;
    }

    /** Stable across restarts and reloads; available only after a successful AE migration. */
    public static synchronized String getAeStorageId(File worldDirectory) {
        return SharedMigrationState.storageId(worldDirectory);
    }

    /** Read-only decoder used by the one-time native-cell migration; never creates a legacy file. */
    static LoadedStorage readForNativeMigration(File worldDirectory) {
        if (SharedMigrationState.isCommitted(worldDirectory)) SharedMigrationState.checkReady(worldDirectory, true);
        File primary = fileFor(worldDirectory);
        if (!primary.exists() && !new File(primary.getPath() + BAK_SUFFIX).exists())
            return new LoadedStorage(new SharedStorage(), null);
        return loadSource(worldDirectory);
    }

    private static LoadedStorage loadSource(File worldDirectory) {
        SharedStorage storage = new SharedStorage();
        File primary = fileFor(worldDirectory);
        File backup = new File(primary.getPath() + BAK_SUFFIX);

        if (primary.isFile()) {
            if (tryLoadInto(storage, primary)) {
                logLoaded(primary, storage);
                return new LoadedStorage(storage, primary);
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
            return new LoadedStorage(storage, backup);
        }

        if (primary.exists() || backup.exists()) {
            throw new IllegalStateException("共享存储：已有数据文件无法读取，停止载入以保护原文件和备份：" + primary.getPath());
        }
        LOG.info("共享存储：主文件和备份都不存在，从空存储开始");
        // A failed primary parse may have partially populated the temporary instance.
        storage = new SharedStorage();
        storage.markClean();
        return new LoadedStorage(storage, null);
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
                throw new UnsupportedFormatException(file);
            }

            storage.readFromNbt(tag);
            return true;
        } catch (UnsupportedFormatException e) {
            throw e;
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
    public static synchronized boolean save(File worldDirectory, SharedStorage storage) {
        if (NativeMeMigration.isCommitted(worldDirectory) || storage.nativeCells() != null) {
            LOG.error("原生 ME 迁移后禁止写入旧共享池文件");
            return false;
        }
        try {
            SharedMigrationState.checkReady(worldDirectory, false);
        } catch (IllegalStateException error) {
            LOG.error("共享存储：迁移状态不完整，拒绝覆盖数据文件", error);
            return false;
        }
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
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                LOG.warn("共享存储：文件系统不支持原子替换，改用普通移动", e);
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
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

    static final class LoadedStorage {

        final SharedStorage storage;
        final File source;

        private LoadedStorage(SharedStorage storage, File source) {
            this.storage = storage;
            this.source = source;
        }
    }

    private static final class UnsupportedFormatException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private UnsupportedFormatException(File file) {
            super("共享存储：文件格式过新，拒绝回退到旧备份或覆盖数据：" + file.getPath());
        }
    }
}
