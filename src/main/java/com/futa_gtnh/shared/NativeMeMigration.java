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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.me.NativeMeCellArray;

/** One atomic conversion, immutable source backup and a separate ownership marker prevent accidental reimports. */
public final class NativeMeMigration {

    private static final String BACKUP_SUFFIX = ".pre-native-ae-v1";
    private static final String MARKER = "futa_gtnh_native_me_migration.dat";

    private NativeMeMigration() {}

    public static File dataFile(File world) {
        return new File(new File(world, "data"), NativeMeStorageData.NAME + ".dat");
    }

    static File backupFile(File world) {
        return new File(
            SharedStorageFile.fileFor(world)
                .getPath() + BACKUP_SUFFIX);
    }

    static File markerFile(File world) {
        return new File(new File(world, "data"), MARKER);
    }

    static boolean isCommitted(File world) {
        return Files.exists(markerFile(world).toPath(), LinkOption.NOFOLLOW_LINKS);
    }

    public static NativeMeStorageData load(File world) {
        return load(world, NativeMeCellArray::new);
    }

    static synchronized NativeMeStorageData load(File world, Supplier<NativeMeCellArray> factory) {
        try {
            File dataFile = dataFile(world);
            if (isCommitted(world)) {
                NBTTagCompound marker = read(markerFile(world));
                validateOrigin(world, marker);
                if (!dataFile.isFile()) throw new IllegalStateException(
                    "Native ME inventory is missing; archived legacy stock must never be reimported");
                NativeMeStorageData data = readData(dataFile, factory);
                if (!data.storageId()
                    .toString()
                    .equals(marker.getString("storageId"))
                    || !data.migration()
                        .equals(marker.getCompoundTag("origin")))
                    throw new IllegalStateException("Native ME inventory and migration identity differ");
                protectLegacyReader(world, data);
                return data;
            }
            // A crash after writing native cells but before committing ownership resumes this exact snapshot.
            if (dataFile.exists()) {
                NativeMeStorageData data = readData(dataFile, factory);
                NBTTagCompound marker = marker(data);
                validateOrigin(world, marker);
                protectLegacyReader(world, data);
                writeAtomic(markerFile(world), marker);
                return data;
            }
            SharedStorageFile.LoadedStorage legacy = SharedStorageFile.readForNativeMigration(world);
            File source = legacy.source;
            NBTTagCompound origin = new NBTTagCompound();
            origin.setString("backend", "ae-native-cells");
            origin.setBoolean("newWorld", source == null);
            UUID id;
            if (source == null) {
                if (backupFile(world).exists())
                    throw new IllegalStateException("Native migration backup exists but live source is missing");
                id = UUID.randomUUID();
            } else {
                String hash = digest(source);
                File backup = backupFile(world);
                if (backup.exists()) {
                    if (!hash.equals(digest(backup))) throw new IllegalStateException(
                        "Interrupted native migration source changed; immutable backup retained");
                } else {
                    Files.copy(source.toPath(), backup.toPath());
                }
                try (FileOutputStream stream = new FileOutputStream(backup, true)) {
                    stream.getFD()
                        .sync();
                }
                if (!hash.equals(digest(backup)) || !hash.equals(digest(source)))
                    throw new IllegalStateException("Native migration source or backup changed during conversion");
                origin.setString("sourceSha256", hash);
                origin.setString("source", source.getName());
                id = SharedMigrationState.isCommitted(world) ? UUID.fromString(SharedMigrationState.storageId(world))
                    : UUID.randomUUID();
            }
            Map<ItemKey, Long> expectedItems = new LinkedHashMap<>(legacy.storage.itemView());
            Map<FluidKey, Long> expectedFluids = new LinkedHashMap<>(legacy.storage.fluidView());
            NativeMeStorageData data = new NativeMeStorageData(NativeMeStorageData.NAME, factory.get());
            data.initialize(id, legacy.storage, origin);
            NBTTagCompound tag = new NBTTagCompound();
            data.writeToNBT(tag);
            NativeMeStorageData checked = new NativeMeStorageData(NativeMeStorageData.NAME, factory.get());
            checked.readFromNBT((NBTTagCompound) tag.copy());
            if (!expectedItems.equals(
                checked.cells()
                    .itemView())
                || !expectedFluids.equals(
                    checked.cells()
                        .fluidView())
                || !data.access()
                    .writeServiceMetadata()
                    .equals(
                        checked.access()
                            .writeServiceMetadata()))
                throw new IllegalStateException("Native cell remount failed exact stock/metadata verification");
            NBTTagCompound root = new NBTTagCompound();
            root.setTag("data", tag);
            writeAtomic(dataFile, root);
            NativeMeStorageData persisted = readData(dataFile, factory);
            if (!expectedItems.equals(
                persisted.cells()
                    .itemView())
                || !expectedFluids.equals(
                    persisted.cells()
                        .fluidView()))
                throw new IllegalStateException("Persisted native cells differ from legacy quantities");
            protectLegacyReader(world, persisted);
            writeAtomic(markerFile(world), marker(persisted));
            return persisted;
        } catch (IOException error) {
            throw new IllegalStateException(
                "Native ME migration/load failed; legacy source and immutable backup retained",
                error);
        }
    }

    /** The previous public-ME releases must refuse their archived pool after ownership has moved. */
    private static void protectLegacyReader(File world, NativeMeStorageData data) throws IOException {
        File oldMarker = SharedMigrationState.markerFor(world);
        if (oldMarker.exists()) {
            NBTTagCompound existing = read(oldMarker);
            if (existing.getInteger("version") == 2 && "ae-native-cells".equals(existing.getString("backend"))) {
                if (!data.storageId()
                    .toString()
                    .equals(existing.getString("storageId")))
                    throw new IllegalStateException("Native downgrade guard identity differs");
                return;
            }
            File archive = new File(oldMarker.getPath() + ".pre-native-v1");
            if (archive.exists()) {
                if (!digest(oldMarker).equals(digest(archive)))
                    throw new IllegalStateException("Legacy ownership marker archive differs");
            } else Files.copy(oldMarker.toPath(), archive.toPath());
        }
        NBTTagCompound guard = new NBTTagCompound();
        guard.setInteger("version", 2);
        guard.setString("backend", "ae-native-cells");
        guard.setString(
            "storageId",
            data.storageId()
                .toString());
        guard.setString("nativeData", NativeMeStorageData.NAME);
        writeAtomic(oldMarker, guard);
    }

    private static NativeMeStorageData readData(File file, Supplier<NativeMeCellArray> factory) throws IOException {
        NBTTagCompound root = read(file);
        if (!root.hasKey("data", 10)) throw new IllegalStateException("Invalid native ME world data root");
        NativeMeStorageData data = new NativeMeStorageData(NativeMeStorageData.NAME, factory.get());
        data.readFromNBT(root.getCompoundTag("data"));
        return data;
    }

    private static NBTTagCompound marker(NativeMeStorageData data) {
        NBTTagCompound marker = new NBTTagCompound();
        marker.setInteger("version", 1);
        marker.setString(
            "storageId",
            data.storageId()
                .toString());
        marker.setTag("origin", data.migration());
        return marker;
    }

    private static void validateOrigin(File world, NBTTagCompound marker) throws IOException {
        if (!marker.hasKey("version", 3) || marker.getInteger("version") != 1 || !marker.hasKey("origin", 10))
            throw new IllegalStateException("Unsupported native ME migration marker");
        UUID.fromString(marker.getString("storageId"));
        NBTTagCompound origin = marker.getCompoundTag("origin");
        if (!"ae-native-cells".equals(origin.getString("backend")) || !origin.hasKey("newWorld", 1))
            throw new IllegalStateException("Unknown native ME storage origin");
        if (!origin.getBoolean("newWorld")) {
            String hash = origin.getString("sourceSha256");
            if (!hash.matches("[0-9a-f]{64}") || !hash.equals(digest(backupFile(world))))
                throw new IllegalStateException("Immutable pre-native migration backup failed verification");
        }
    }

    private static NBTTagCompound read(File file) throws IOException {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Not a regular file: " + file);
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    private static void writeAtomic(File target, NBTTagCompound tag) throws IOException {
        Files.createDirectories(
            target.toPath()
                .getParent());
        File temporary = new File(target.getPath() + ".tmp");
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(tag, encoded);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(encoded.toByteArray());
            output.flush();
            output.getFD()
                .sync();
        }
        // Refuse unsupported atomic replacement during ownership transfer rather than publishing partial inventory.
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
    }

    private static String digest(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            StringBuilder value = new StringBuilder(64);
            for (byte b : digest.digest()) {
                value.append(Character.forDigit((b >>> 4) & 15, 16));
                value.append(Character.forDigit(b & 15, 16));
            }
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
