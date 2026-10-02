package com.futa_gtnh.shared;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.api.SharedStorageReadApi;
import com.futa_gtnh.api.SharedStorageSnapshot;

import cpw.mods.fml.common.Loader;

/** Standalone read API checks; uses isolated in-memory stores and an empty temporary world only. */
public final class SharedStorageReadApiRegression {

    private SharedStorageReadApiRegression() {}

    public static void main(String[] args) throws Exception {
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Bootstrap.func_151354_b();
        check(SharedStorageReadApi.getApiVersion() == 1, "API version");
        check(
            !SharedStorageReadApi.getStatus()
                .isReady(),
            "startup is NOT_READY");
        check(
            SharedStorageReadApi.snapshot()
                .getItems()
                .isEmpty(),
            "startup hides temporary pool");

        ItemStack item = new ItemStack(new Item(), 1, 7);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("variant", "original");
        item.setTagCompound(tag);
        FluidStack fluid = new FluidStack(FluidRegistry.WATER, 1, (NBTTagCompound) tag.copy());
        ItemKey itemKey = ItemKey.of(item);
        FluidKey fluidKey = FluidKey.of(fluid);
        SharedStorage store = new SharedStorage();
        long itemAmount = Long.MAX_VALUE - 7;
        long fluidAmount = 9007199254740993L;
        store.insertItem(itemKey, itemAmount);
        store.insertFluid(fluidKey, fluidAmount);
        store.markClean();
        int revision = store.getRevision();
        SharedStorageSnapshot snapshot = store.snapshotForReadApi("isolated-store");
        check(
            snapshot.getStatus()
                .isReady(),
            "ready snapshot");
        check(
            snapshot.getStatus()
                .getRevision() == revision,
            "snapshot revision");
        check(!store.isDirty() && store.getRevision() == revision, "read has no side effects");
        check(
            snapshot.getItems()
                .get(0)
                .getAmount() == itemAmount,
            "long item amount");
        check(
            snapshot.getFluids()
                .get(0)
                .getAmount() == fluidAmount,
            "long fluid amount");
        ItemStack itemCopy = snapshot.getItems()
            .get(0)
            .getPrototype();
        FluidStack fluidCopy = snapshot.getFluids()
            .get(0)
            .getPrototype();
        check(itemCopy.stackSize == 1 && itemCopy.getItemDamage() == 7, "item prototype");
        check(fluidCopy.amount == 1, "fluid prototype");
        itemCopy.stackSize = 64;
        itemCopy.getTagCompound()
            .setString("variant", "changed");
        fluidCopy.amount = 1000;
        fluidCopy.tag.setString("variant", "changed");
        check(
            "original".equals(
                snapshot.getItems()
                    .get(0)
                    .getPrototype()
                    .getTagCompound()
                    .getString("variant")),
            "item snapshot NBT isolation");
        check(
            "original".equals(
                snapshot.getFluids()
                    .get(0)
                    .getPrototype().tag.getString("variant")),
            "fluid snapshot NBT isolation");
        check(
            store.getItemAmount(itemKey) == itemAmount && store.getFluidAmount(fluidKey) == fluidAmount,
            "returned prototypes cannot mutate storage");
        SharedStorageSnapshot.ItemEntry entry = new SharedStorageSnapshot.ItemEntry(item, itemAmount);
        item.getTagCompound()
            .setString("variant", "caller-change");
        check(
            "original".equals(
                entry.getPrototype()
                    .getTagCompound()
                    .getString("variant")),
            "constructor copies caller NBT");
        expectUnsupported(
            () -> snapshot.getItems()
                .clear());
        expectUnsupported(
            () -> snapshot.getFluids()
                .clear());
        List<Map.Entry<ItemKey, Long>> oldItems = store.snapshotItems();
        List<Map.Entry<FluidKey, Long>> oldFluids = store.snapshotFluids();
        store.extractItem(itemKey, 9);
        store.extractFluid(fluidKey, 11);
        check(
            oldItems.get(0)
                .getValue() == itemAmount
                && oldFluids.get(0)
                    .getValue() == fluidAmount,
            "legacy entry snapshots remain detached");
        expectUnsupported(
            () -> oldItems.get(0)
                .setValue(1L));
        check(
            snapshot.getItems()
                .get(0)
                .getAmount() == itemAmount,
            "old snapshot amount is frozen");

        SharedStorage concurrent = new SharedStorage();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try {
                for (int i = 0; i < 500; i++) {
                    synchronized (concurrent) {
                        concurrent.insertItem(itemKey, 1);
                        concurrent.insertFluid(fluidKey, 1);
                    }
                }
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "isolated-warehouse-writer");
        writer.start();
        for (int i = 0; i < 500; i++) {
            SharedStorageSnapshot paired = concurrent.snapshotForReadApi("concurrent-store");
            check(
                paired.getItems()
                    .isEmpty()
                    == paired.getFluids()
                        .isEmpty(),
                "atomic empty state");
            if (!paired.getItems()
                .isEmpty()) {
                check(
                    paired.getItems()
                        .get(0)
                        .getAmount()
                        == paired.getFluids()
                            .get(0)
                            .getAmount(),
                    "items and fluids captured under one lock");
            }
        }
        writer.join(10000);
        check(!writer.isAlive() && failure.get() == null, "writer completed");

        String beforeStart = SharedStorageReadApi.getStatus()
            .getGeneration();
        SharedStorageManager.onServerStarted(null);
        SharedStorageSnapshot readyEmpty = SharedStorageReadApi.snapshot();
        check(
            readyEmpty.getStatus()
                .isReady()
                && readyEmpty.getItems()
                    .isEmpty(),
            "ready empty differs from NOT_READY");
        String started = readyEmpty.getStatus()
            .getGeneration();
        check(!started.equals(beforeStart), "start changes generation");
        Thread wrongThread = new Thread(() -> {
            try {
                SharedStorageReadApi.snapshot();
                failure.set(new AssertionError("worker access was allowed"));
            } catch (IllegalStateException expected) {
                // Correct: the caller must schedule onto the server thread.
            }
        }, "wrong-read-api-thread");
        wrongThread.start();
        wrongThread.join(10000);
        check(!wrongThread.isAlive() && failure.get() == null, "reject worker access");

        File tempWorld = Files.createTempDirectory("futa-read-api-empty-world-")
            .toFile();
        Field worldDirectory = SharedStorageManager.class.getDeclaredField("worldDirectory");
        worldDirectory.setAccessible(true);
        worldDirectory.set(null, tempWorld);
        try {
            check(SharedStorageManager.reloadFromDisk() == 0, "reload empty temporary world");
            check(
                !started.equals(
                    SharedStorageReadApi.getStatus()
                        .getGeneration()),
                "reload changes generation");
        } finally {
            worldDirectory.set(null, null);
            check(tempWorld.delete(), "remove empty temporary world");
        }
        String reloaded = SharedStorageReadApi.getStatus()
            .getGeneration();
        SharedStorageManager.onServerStopping();
        check(
            !SharedStorageReadApi.getStatus()
                .isReady(),
            "stop is NOT_READY");
        check(
            !reloaded.equals(
                SharedStorageReadApi.getStatus()
                    .getGeneration()),
            "stop changes generation");
        SharedStorageManager.onServerStarted(null);
        check(
            !reloaded.equals(
                SharedStorageReadApi.getStatus()
                    .getGeneration()),
            "restart changes generation");
        SharedStorageManager.onServerStopping();
        System.out.println("SharedStorageReadApi regression checks passed");
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
            throw new AssertionError("Mutation was allowed");
        } catch (UnsupportedOperationException expected) {
            // Correct: this view is immutable.
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
