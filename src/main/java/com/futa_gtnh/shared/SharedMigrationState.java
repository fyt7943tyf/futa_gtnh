package com.futa_gtnh.shared;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Durable ownership of the legacy pool by AE. The stock file is not rewritten during migration:
 * its internal stock, limits, unresolved crafting escrow and unknown rows keep their exact bytes.
 * The immutable pre-migration backup is for operator recovery only, never an automatic fallback.
 * All callers hold SharedStorageFile's class lock, including ordinary saves and reloads.
 */
final class SharedMigrationState {

    static final int VERSION = 1;
    static final String BACKUP_SUFFIX = ".pre-ae-v1";
    static final String MARKER_SUFFIX = ".ae-migration";

    private SharedMigrationState() {}

    static File backupFor(File worldDirectory) {
        return new File(
            SharedStorageFile.fileFor(worldDirectory)
                .getPath() + BACKUP_SUFFIX);
    }

    static File markerFor(File worldDirectory) {
        return new File(
            SharedStorageFile.fileFor(worldDirectory)
                .getPath() + MARKER_SUFFIX);
    }

    static boolean isCommitted(File worldDirectory) {
        return Files.exists(markerFor(worldDirectory).toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    static void checkReady(File worldDirectory, boolean verifyBackup) {
        if (isCommitted(worldDirectory)) {
            readCommitted(worldDirectory, verifyBackup);
        } else if (Files.exists(backupFor(worldDirectory).toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw failure("迁移备份已建立但迁移尚未提交；仅允许启动时验证并续接迁移", null);
        }
    }

    static String storageId(File worldDirectory) {
        return readCommitted(worldDirectory, false).getString("storageId");
    }

    static void commit(File worldDirectory, File source) {
        if (isCommitted(worldDirectory)) {
            readCommitted(worldDirectory, true);
            return;
        }
        File backup = backupFor(worldDirectory);
        File marker = markerFor(worldDirectory);
        File temporary = new File(marker.getPath() + ".tmp");
        try {
            requireRegularFile(source);
            String sourceHash = digest(source);
            if (Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                requireRegularFile(backup);
                if (!sourceHash.equals(digest(backup))) {
                    throw failure("中断迁移的备份与当前源文件不同，拒绝覆盖或重新认领", null);
                }
            } else {
                // No REPLACE_EXISTING: the dedicated backup is never rotated or overwritten.
                Files.copy(source.toPath(), backup.toPath());
                if (!sourceHash.equals(digest(backup))) {
                    throw failure("迁移备份校验失败", null);
                }
            }
            // Resuming an interrupted copy must also flush it before committing ownership.
            try (FileOutputStream output = new FileOutputStream(backup, true)) {
                output.getFD()
                    .sync();
            }
            if (!sourceHash.equals(digest(source))) {
                throw failure("建立备份期间源文件发生变化", null);
            }

            NBTTagCompound state = new NBTTagCompound();
            state.setInteger("version", VERSION);
            state.setString("backend", "ae-shared-pool");
            state.setString(
                "storageId",
                UUID.randomUUID()
                    .toString());
            state.setString("backupSha256", sourceHash);
            state.setString("source", source.equals(SharedStorageFile.fileFor(worldDirectory)) ? "primary" : "backup");
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(state, encoded);
            try (FileOutputStream raw = new FileOutputStream(temporary)) {
                raw.write(encoded.toByteArray());
                raw.flush();
                raw.getFD()
                    .sync();
            }
            // Unlike ordinary legacy saves, an ownership commit requires an atomic rename.
            // Unsupported filesystems fail closed with the original and dedicated backup intact.
            Files.move(temporary.toPath(), marker.toPath(), StandardCopyOption.ATOMIC_MOVE);
            readCommitted(worldDirectory, true);
        } catch (IOException error) {
            throw failure("不能原子提交 AE 存储迁移；原数据和迁移备份保持原样", error);
        }
    }

    private static NBTTagCompound readCommitted(File worldDirectory, boolean verifyBackup) {
        File marker = markerFor(worldDirectory);
        File backup = backupFor(worldDirectory);
        try {
            requireRegularFile(marker);
            requireRegularFile(backup);
            NBTTagCompound state;
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(marker))) {
                state = CompressedStreamTools.readCompressed(input);
            }
            if (!state.hasKey("version", 3) || state.getInteger("version") != VERSION
                || !"ae-shared-pool".equals(state.getString("backend"))) {
                throw failure("迁移标记版本或后端不受支持", null);
            }
            String id = state.getString("storageId");
            if (!UUID.fromString(id)
                .toString()
                .equals(id)) {
                throw failure("迁移仓库标识不合法", null);
            }
            String source = state.getString("source");
            if (!"primary".equals(source) && !"backup".equals(source)) {
                throw failure("迁移来源不合法", null);
            }
            String hash = state.getString("backupSha256");
            if (!hash.matches("[0-9a-f]{64}") || verifyBackup && !hash.equals(digest(backup))) {
                throw failure("迁移前备份校验失败", null);
            }
            File primary = SharedStorageFile.fileFor(worldDirectory);
            File rollingBackup = new File(primary.getPath() + ".bak");
            if (!primary.isFile() && !rollingBackup.isFile()) {
                throw failure("迁移后的主档和常规备份均不存在；禁止用迁移前快照自动恢复库存", null);
            }
            return state;
        } catch (IOException | IllegalArgumentException error) {
            throw failure("迁移标记或备份不可读取，拒绝启用和保存仓库", error);
        }
    }

    private static void requireRegularFile(File file) throws IOException {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a regular file: " + file.getPath());
        }
    }

    private static String digest(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value >>> 4) & 15, 16));
            hex.append(Character.forDigit(value & 15, 16));
        }
        return hex.toString();
    }

    private static IllegalStateException failure(String message, Exception cause) {
        return new IllegalStateException("共享存储 AE 迁移：" + message, cause);
    }
}
