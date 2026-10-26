package com.futa_gtnh.shared;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;

import com.futa_gtnh.storagebus.StorageBusRegistry;

/** Actual legacy files, immutable backups, interrupted commits and repeated production loads. */
public final class SharedStorageMigrationRegression {

    private static int checks;
    private static File root;

    private SharedStorageMigrationRegression() {}

    public static void run() throws Exception {
        root = new File("build/shared-storage-migration-check");
        root.mkdirs();
        checkExactAdoption();
        checkInterruptedCommit();
        checkChangedSource();
        checkFailedCommit();
        checkCorruptState();
        checkMissingLiveData();
        checkBackupRecovery();
        checkFutureLegacy();
        checkNewWorld();
        System.out.println("Shared storage AE migration regression passed: " + checks + " checks");
    }

    private static void checkExactAdoption() throws Exception {
        File world = world("exact-");
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        SharedStorage original = new SharedStorage();
        long itemAmount = 9007199254740993L;
        long fluidAmount = 4294967297L;
        original.insertItemManualDirect(iron, itemAmount);
        original.insertFluidManualDirect(water, fluidAmount);
        original.reserveItemForCrafting(iron, 3);
        original.reserveFluidForCrafting(water, 5);
        original.setItemLimit(iron, 1);
        original.setFluidLimit(water, 2);
        NBTTagCompound tag = original.writeToNbt();
        NBTTagCompound unknownItem = new NBTTagCompound();
        unknownItem.setInteger("item", Integer.MAX_VALUE);
        unknownItem.setInteger("meta", 32768);
        unknownItem.setLong("amount", Long.MAX_VALUE - 11);
        tag.getTagList("items", 10).appendTag(unknownItem);
        NBTTagCompound unknownFluid = new NBTTagCompound();
        unknownFluid.setString("fluid", "futa_migration_missing_fluid");
        unknownFluid.setLong("amount", Long.MAX_VALUE - 13);
        tag.getTagList("fluids", 10).appendTag(unknownFluid);
        tag.getTagList("craftingItems", 10).appendTag(unknownItem.copy());
        tag.getTagList("craftingFluids", 10).appendTag(unknownFluid.copy());
        tag.setString("uninterpreted-extension", "preserve exact migration bytes");
        File primary = SharedStorageFile.fileFor(world);
        write(primary, tag);
        byte[] before = Files.readAllBytes(primary.toPath());

        Map<ItemKey, Long> externalItems = registryMap("READABLE_ITEMS");
        Map<FluidKey, Long> externalFluids = registryMap("READABLE_FLUIDS");
        Map<ItemKey, Long> savedItems = new LinkedHashMap<>(externalItems);
        Map<FluidKey, Long> savedFluids = new LinkedHashMap<>(externalFluids);
        Field enabled = StorageBusRegistry.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        boolean wasEnabled = enabled.getBoolean(null);
        try {
            enabled.setBoolean(null, true);
            externalItems.put(iron, 19L);
            externalFluids.put(water, 23L);
            SharedStorage adopted = SharedStorageFile.loadForAe(world);
            check(Arrays.equals(before, Files.readAllBytes(primary.toPath())), "migration never rewrites legacy stock");
            check(Arrays.equals(before, Files.readAllBytes(SharedMigrationState.backupFor(world).toPath())),
                "dedicated backup preserves every original byte");
            check(adopted.getItemAmountPool(iron) == itemAmount - 3
                && adopted.getFluidAmountPool(water) == fluidAmount - 5, "long internal stock is adopted exactly");
            check(adopted.getItemAmount(iron) == itemAmount - 3 + 19
                && adopted.getFluidAmount(water) == fluidAmount - 5 + 23, "fixture includes external inventory view");
            check(adopted.snapshotCraftingItems().get(0).getValue() == 3
                && adopted.snapshotCraftingFluids().get(0).getValue() == 5, "uncertain CPU escrow stays unavailable");
            check(adopted.getUnreadableItemCount() == 1 && adopted.getUnreadableFluidCount() == 1,
                "missing item and fluid remain quarantined");
            check(adopted.getItemLimit(iron) == 1 && adopted.getFluidLimit(water) == 2,
                "limits do not truncate over-limit stock during adoption");
            String id = SharedStorageFile.getAeStorageId(world);
            byte[] marker = Files.readAllBytes(SharedMigrationState.markerFor(world).toPath());
            SharedStorage repeated = SharedStorageFile.loadForAe(world);
            check(id.equals(SharedStorageFile.getAeStorageId(world))
                && Arrays.equals(marker, Files.readAllBytes(SharedMigrationState.markerFor(world).toPath())),
                "repeated startup preserves storage identity and commit bytes");
            check(repeated.getItemAmountPool(iron) == adopted.getItemAmountPool(iron),
                "repeated migration does not add another copy");
            adopted.insertItemManualDirect(iron, 1);
            check(SharedStorageFile.save(world, adopted), "adopted stock saves through production writer");
            NBTTagCompound persisted = read(primary);
            check(persisted.getTagList("items", 10).getCompoundTagAt(0).getLong("amount") == itemAmount - 2
                && persisted.getTagList("fluids", 10).getCompoundTagAt(0).getLong("amount") == fluidAmount - 5,
                "saving does not materialize external items or fluid into the pool");
            check(persisted.getTagList("craftingItems", 10).equals(tag.getTagList("craftingItems", 10))
                && persisted.getTagList("craftingFluids", 10).equals(tag.getTagList("craftingFluids", 10)),
                "ordinary save preserves known and unknown CPU escrow");
            check(persisted.getTagList("items", 10).getCompoundTagAt(1).equals(unknownItem)
                && persisted.getTagList("fluids", 10).getCompoundTagAt(1).equals(unknownFluid),
                "ordinary save preserves unknown stock NBT");
            check(Arrays.equals(before, Files.readAllBytes(SharedMigrationState.backupFor(world).toPath())),
                "ordinary saves never rotate the migration backup");
            check(SharedStorageFile.loadForAe(world).getItemAmountPool(iron) == itemAmount - 2
                && id.equals(SharedStorageFile.getAeStorageId(world)), "restart loads live data without reimporting backup");
        } finally {
            externalItems.clear();
            externalItems.putAll(savedItems);
            externalFluids.clear();
            externalFluids.putAll(savedFluids);
            enabled.setBoolean(null, wasEnabled);
        }
    }

    private static void checkInterruptedCommit() throws Exception {
        File world = baseline("interrupted-");
        File primary = SharedStorageFile.fileFor(world);
        Files.copy(primary.toPath(), SharedMigrationState.backupFor(world).toPath());
        expectFailure(() -> SharedStorageFile.load(world), "ordinary load rejects pending migration");
        check(!SharedStorageFile.save(world, new SharedStorage()), "ordinary save rejects pending migration");
        SharedStorage adopted = SharedStorageFile.loadForAe(world);
        check(adopted.getItemAmountPool(iron()) == 42 && SharedMigrationState.markerFor(world).isFile(),
            "startup resumes matching backup and atomically commits");
    }

    private static void checkChangedSource() throws Exception {
        File world = baseline("changed-");
        File primary = SharedStorageFile.fileFor(world);
        Files.copy(primary.toPath(), SharedMigrationState.backupFor(world).toPath());
        byte[] backup = Files.readAllBytes(SharedMigrationState.backupFor(world).toPath());
        SharedStorage changed = new SharedStorage();
        changed.insertItemManualDirect(iron(), 99);
        write(primary, changed.writeToNbt());
        byte[] current = Files.readAllBytes(primary.toPath());
        expectFailure(() -> SharedStorageFile.loadForAe(world), "changed source cannot overwrite immutable backup");
        check(Arrays.equals(current, Files.readAllBytes(primary.toPath()))
            && Arrays.equals(backup, Files.readAllBytes(SharedMigrationState.backupFor(world).toPath()))
            && !SharedMigrationState.markerFor(world).exists(), "failed resume leaves source, backup and state unchanged");
    }

    private static void checkFailedCommit() throws Exception {
        File world = baseline("commit-failure-");
        File primary = SharedStorageFile.fileFor(world);
        byte[] before = Files.readAllBytes(primary.toPath());
        File obstructedTemporary = new File(SharedMigrationState.markerFor(world).getPath() + ".tmp");
        check(obstructedTemporary.mkdir(), "create deterministic commit IO failure");
        expectFailure(() -> SharedStorageFile.loadForAe(world), "commit IO failure aborts adoption");
        check(Arrays.equals(before, Files.readAllBytes(primary.toPath()))
            && Arrays.equals(before, Files.readAllBytes(SharedMigrationState.backupFor(world).toPath())),
            "failed marker write preserves source and completed backup");
        check(!SharedStorageFile.save(world, new SharedStorage()), "failed commit cannot become writable empty storage");
        Files.delete(obstructedTemporary.toPath());
        check(SharedStorageFile.loadForAe(world).getItemAmountPool(iron()) == 42,
            "removing IO obstruction resumes without duplicate stock");
    }

    private static void checkCorruptState() throws Exception {
        File world = baseline("invalid-state-");
        SharedStorageFile.loadForAe(world);
        File primary = SharedStorageFile.fileFor(world);
        File marker = SharedMigrationState.markerFor(world);
        File backup = SharedMigrationState.backupFor(world);
        byte[] original = Files.readAllBytes(primary.toPath());
        byte[] markerBytes = Files.readAllBytes(marker.toPath());
        Files.write(marker.toPath(), new byte[] { 1, 2, 3 });
        expectFailure(() -> SharedStorageFile.loadForAe(world), "corrupt marker never falls back to legacy mode");
        check(!SharedStorageFile.save(world, new SharedStorage()), "corrupt marker blocks writes");
        Files.write(marker.toPath(), markerBytes);
        NBTTagCompound future = read(marker);
        future.setInteger("version", SharedMigrationState.VERSION + 1);
        write(marker, future);
        expectFailure(() -> SharedStorageFile.loadForAe(world), "future migration format is rejected");
        check(!SharedStorageFile.save(world, new SharedStorage()), "future migration format blocks writes");
        Files.write(marker.toPath(), markerBytes);
        NBTTagCompound invalid = read(marker);
        invalid.setString("storageId", "not-a-warehouse-id");
        write(marker, invalid);
        expectFailure(() -> SharedStorageFile.loadForAe(world), "invalid identity is rejected");
        Files.write(marker.toPath(), markerBytes);
        Files.write(backup.toPath(), new byte[] { 4, 5, 6 });
        expectFailure(() -> SharedStorageFile.loadForAe(world), "changed dedicated backup fails checksum");
        check(Arrays.equals(original, Files.readAllBytes(primary.toPath())), "all invalid states preserve live data");
    }

    private static void checkMissingLiveData() throws Exception {
        File world = baseline("missing-live-");
        SharedStorageFile.loadForAe(world);
        Files.delete(SharedStorageFile.fileFor(world).toPath());
        expectFailure(() -> SharedStorageFile.loadForAe(world), "missing live files cannot resurrect pre-migration stock");
        check(!SharedStorageFile.save(world, new SharedStorage()), "missing live files cannot be overwritten by empty pool");
        Files.delete(SharedMigrationState.markerFor(world).toPath());
        expectFailure(() -> SharedStorageFile.loadForAe(world), "stranded backup alone cannot be claimed as a new world");
    }

    private static void checkBackupRecovery() throws Exception {
        File world = baseline("rolling-backup-");
        File primary = SharedStorageFile.fileFor(world);
        File rolling = new File(primary.getPath() + ".bak");
        Files.copy(primary.toPath(), rolling.toPath());
        byte[] good = Files.readAllBytes(rolling.toPath());
        byte[] broken = { 1, 2, 3 };
        Files.write(primary.toPath(), broken);
        check(SharedStorageFile.loadForAe(world).getItemAmountPool(iron()) == 42,
            "existing rolling-backup recovery remains available before adoption");
        check("backup".equals(read(SharedMigrationState.markerFor(world)).getString("source"))
            && Arrays.equals(good, Files.readAllBytes(SharedMigrationState.backupFor(world).toPath())),
            "dedicated migration backup uses the actually loaded authoritative file");
        check(Arrays.equals(broken, Files.readAllBytes(primary.toPath())), "migration preserves corrupt primary for inspection");
    }

    private static void checkFutureLegacy() throws Exception {
        File world = baseline("future-legacy-");
        File primary = SharedStorageFile.fileFor(world);
        NBTTagCompound future = read(primary);
        future.setInteger("version", SharedStorage.FORMAT_VERSION + 1);
        write(primary, future);
        byte[] before = Files.readAllBytes(primary.toPath());
        expectFailure(() -> SharedStorageFile.loadForAe(world), "future stock format prevents migration");
        check(Arrays.equals(before, Files.readAllBytes(primary.toPath()))
            && !SharedMigrationState.backupFor(world).exists() && !SharedMigrationState.markerFor(world).exists(),
            "unsupported legacy files acquire no migration state");
    }

    private static void checkNewWorld() throws Exception {
        File world = world("new-world-");
        check(SharedStorageFile.loadForAe(world).itemTypeCount() == 0, "new world starts with empty adopted storage");
        String id = SharedStorageFile.getAeStorageId(world);
        check(SharedStorageFile.fileFor(world).isFile() && SharedMigrationState.backupFor(world).isFile(),
            "empty world has durable source and backup before commit");
        check(SharedStorageFile.loadForAe(world).itemTypeCount() == 0 && id.equals(SharedStorageFile.getAeStorageId(world)),
            "restart before first player write retains empty storage identity");
    }

    private static File baseline(String prefix) throws Exception {
        File world = world(prefix);
        SharedStorage storage = new SharedStorage();
        storage.insertItemManualDirect(iron(), 42);
        write(SharedStorageFile.fileFor(world), storage.writeToNbt());
        return world;
    }

    private static File world(String prefix) throws Exception {
        return Files.createTempDirectory(root.toPath(), prefix).toFile();
    }

    private static ItemKey iron() {
        return ItemKey.of(new ItemStack(Items.iron_ingot));
    }

    @SuppressWarnings("unchecked")
    private static <K> Map<K, Long> registryMap(String name) throws Exception {
        Field field = StorageBusRegistry.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<K, Long>) field.get(null);
    }

    private static NBTTagCompound read(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file)) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    private static void write(File file, NBTTagCompound tag) throws Exception {
        file.getParentFile().mkdirs();
        try (FileOutputStream output = new FileOutputStream(file)) {
            CompressedStreamTools.writeCompressed(tag, output);
        }
    }

    private static void expectFailure(Runnable action, String label) {
        try {
            action.run();
            throw new AssertionError(label);
        } catch (IllegalStateException expected) {
            checks++;
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
