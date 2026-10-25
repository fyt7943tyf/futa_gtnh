package com.futa_gtnh.shared;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;

/** Exercises real save files and deterministic worker-thread writes in isolated build directories. */
public final class SharedStoragePersistenceRegression {

    private static int checks;

    private SharedStoragePersistenceRegression() {}

    public static void run() throws Exception {
        File root = new File("build/shared-storage-persistence-check");
        root.mkdirs();
        checkLoadFailures(Files.createTempDirectory(root.toPath(), "load-").toFile());
        Object oldStorage = getManagerField("storage");
        Object oldDirectory = getManagerField("worldDirectory");
        Object oldLoaded = getManagerField("loaded");
        Object oldBlocked = getManagerField("savingBlocked");
        try {
            setManagerField("loaded", true);
            setManagerField("savingBlocked", false);
            checkReloadFailure(Files.createTempDirectory(root.toPath(), "reload-").toFile());
            checkConcurrentSave(Files.createTempDirectory(root.toPath(), "concurrent-").toFile());
        } finally {
            setManagerField("storage", oldStorage);
            setManagerField("worldDirectory", oldDirectory);
            setManagerField("loaded", oldLoaded);
            setManagerField("savingBlocked", oldBlocked);
        }
        System.out.println("Shared storage persistence regression passed: " + checks + " checks");
    }

    private static void checkLoadFailures(File world) throws Exception {
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        SharedStorage original = new SharedStorage();
        original.insertItemManual(iron, 42);
        check(SharedStorageFile.save(world, original), "write isolated baseline");
        File primary = SharedStorageFile.fileFor(world);
        File backup = new File(primary.getPath() + ".bak");
        byte[] good = Files.readAllBytes(primary.toPath());
        Files.write(backup.toPath(), good);
        NBTTagCompound future = original.writeToNbt();
        future.setInteger("version", SharedStorage.FORMAT_VERSION + 1);
        write(primary, future);
        byte[] futureBytes = Files.readAllBytes(primary.toPath());
        expectLoadFailure(world);
        check(Arrays.equals(futureBytes, Files.readAllBytes(primary.toPath()))
            && Arrays.equals(good, Files.readAllBytes(backup.toPath())),
            "newer primary never falls back to or overwrites an older backup");
        byte[] broken = { 1, 2, 3 };
        Files.write(primary.toPath(), broken);
        check(SharedStorageFile.load(world).getItemAmount(iron) == 42, "corrupt primary still recovers valid backup");
        Files.write(backup.toPath(), broken);
        expectLoadFailure(world);
        check(Arrays.equals(broken, Files.readAllBytes(primary.toPath()))
            && Arrays.equals(broken, Files.readAllBytes(backup.toPath())), "failed load preserves both broken files");
        Files.delete(primary.toPath());
        expectLoadFailure(world);
        check(!primary.exists() && Arrays.equals(broken, Files.readAllBytes(backup.toPath())),
            "an unreadable backup alone is not treated as a new warehouse");
        Files.write(backup.toPath(), good);
        check(SharedStorageFile.load(world).getItemAmount(iron) == 42, "missing primary recovers valid backup");
        Files.delete(backup.toPath());
        check(SharedStorageFile.load(world).itemTypeCount() == 0, "new worlds with no files start empty");
    }

    private static void checkReloadFailure(File world) throws Exception {
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        SharedStorage current = new SharedStorage();
        current.insertItemManual(iron, 12);
        setManagerField("storage", current);
        setManagerField("worldDirectory", world);
        File primary = SharedStorageFile.fileFor(world);
        primary.getParentFile().mkdirs();
        NBTTagCompound future = current.writeToNbt();
        future.setInteger("version", SharedStorage.FORMAT_VERSION + 1);
        write(primary, future);
        byte[] original = Files.readAllBytes(primary.toPath());
        check(SharedStorageManager.reloadFromDisk() == -1 && SharedStorageManager.getStorage() == current,
            "failed reload retains the exact in-memory pool");
        check(current.getItemAmount(iron) == 12 && SharedStorageManager.isSavingBlocked(),
            "failed reload retains stock and blocks subsequent saves");
        SharedStorageManager.saveNow();
        current.insertItemManual(iron, 1);
        SharedStorageManager.saveNow();
        check(Arrays.equals(original, Files.readAllBytes(primary.toPath()))
            && !new File(primary.getPath() + ".bak").exists(), "repeated saves cannot destroy an unreadable file");
        SharedStorage repaired = new SharedStorage();
        repaired.insertItemManual(iron, 7);
        write(primary, repaired.writeToNbt());
        check(SharedStorageManager.reloadFromDisk() == 1 && !SharedStorageManager.isSavingBlocked(),
            "successful reload re-enables persistence after repair");
        SharedStorageManager.getStorage().insertItemManual(iron, 1);
        SharedStorageManager.saveNow();
        check(SharedStorageFile.load(world).getItemAmount(iron) == 8, "repaired warehouse saves normally");
    }

    private static void checkConcurrentSave(File world) throws Exception {
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        PausingStorage current = new PausingStorage();
        current.insertItemManual(iron, 1);
        setManagerField("storage", current);
        setManagerField("worldDirectory", world);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                check(current.captured.await(10, TimeUnit.SECONDS), "snapshot captured before worker mutation");
                current.insertItemManual(iron, 1);
                current.insertFluidManual(water, 100);
                current.setItemLimit(iron, 37);
                current.markDirty();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                current.changed.countDown();
            }
        }, "shared-storage-save-check");
        writer.start();
        SharedStorageManager.saveNow();
        writer.join(10000);
        check(!writer.isAlive() && failure.get() == null, "worker completes without holding the save lock");
        check(current.isDirty() && current.getItemAmount(iron) == 2, "concurrent mutations remain dirty after save");
        SharedStorage first = SharedStorageFile.load(world);
        check(first.getItemAmount(iron) == 1 && first.getFluidAmount(water) == 0 && !first.hasItemLimit(iron),
            "first file contains exactly the captured snapshot");
        SharedStorageManager.saveNow();
        SharedStorage second = SharedStorageFile.load(world);
        check(second.getItemAmount(iron) == 2 && second.getFluidAmount(water) == 100 && second.getItemLimit(iron) == 37,
            "next save persists all concurrent items, fluids and settings");
        check(!current.isDirty(), "unchanged successful save clears the dirty flag");
    }

    private static final class PausingStorage extends SharedStorage {

        final CountDownLatch captured = new CountDownLatch(1);
        final CountDownLatch changed = new CountDownLatch(1);

        @Override
        public NBTTagCompound writeToNbt() {
            NBTTagCompound snapshot = super.writeToNbt();
            captured.countDown();
            try {
                if (!changed.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("worker timed out");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return snapshot;
        }
    }

    private static void expectLoadFailure(File world) {
        try {
            SharedStorageFile.load(world);
            throw new AssertionError("Unreadable warehouse was accepted as an empty pool");
        } catch (IllegalStateException expected) {
            checks++;
        }
    }

    private static void write(File file, NBTTagCompound tag) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            CompressedStreamTools.writeCompressed(tag, output);
        }
    }

    private static Object getManagerField(String name) throws Exception {
        Field field = SharedStorageManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setManagerField(String name, Object value) throws Exception {
        Field field = SharedStorageManager.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
