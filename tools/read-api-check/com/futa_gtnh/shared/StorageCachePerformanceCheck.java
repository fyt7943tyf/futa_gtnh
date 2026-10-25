package com.futa_gtnh.shared;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;

import cpw.mods.fml.common.Loader;

/** Counts real snapshot rebuilds; the benchmark is diagnostic, never a timing-based pass/fail test. */
public final class StorageCachePerformanceCheck {

    private StorageCachePerformanceCheck() {}

    public static void run() throws Exception {
        CountingStorage storage = new CountingStorage();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        storage.insertItemManual(iron, 64);
        storage.insertFluidManual(water, 1000);
        List<Map.Entry<ItemKey, Long>> items = storage.snapshotItemsCached();
        List<Map.Entry<FluidKey, Long>> fluids = storage.snapshotFluidsCached();
        int fullRevision = storage.getRevision();
        storage.insertFluidManual(water, 100);
        check(storage.snapshotItemsCached() == items && storage.itemSnapshots == 1,
            "fluid writes reuse the item snapshot");
        check(storage.snapshotFluidsCached() != fluids && storage.snapshotFluidsCached().get(0).getValue() == 1100L,
            "fluid writes refresh the fluid snapshot");
        check(storage.getRevision() != fullRevision, "full snapshot/API revision still notices fluid changes");
        fluids = storage.snapshotFluidsCached();
        storage.extractItem(iron, 1);
        check(storage.snapshotFluidsCached() == fluids && storage.fluidSnapshots == 2,
            "item writes reuse the fluid snapshot");
        check(storage.snapshotItemsCached() != items && storage.snapshotItemsCached().get(0).getValue() == 63L,
            "item writes refresh the item snapshot");
        items = storage.snapshotItemsCached();
        storage.setItemLimit(iron, 10);
        storage.setFluidLimit(water, 10);
        check(storage.snapshotItemsCached() == items && storage.snapshotFluidsCached() == fluids,
            "limit-only changes do not rebuild content views");
        NBTTagCompound saved = storage.writeToNbt();
        storage.clear();
        check(storage.snapshotItemsCached().isEmpty() && storage.snapshotFluidsCached().isEmpty(),
            "clear invalidates both cached channels");
        storage.readFromNbt(saved);
        check(storage.snapshotItemsCached().get(0).getValue() == 63L
            && storage.snapshotFluidsCached().get(0).getValue() == 1100L, "load invalidates both cached channels");
        System.out.println("Storage channel cache regression passed");
    }

    public static void checkAeMirrorCaching() throws Exception {
        CountingStorage storage = new CountingStorage();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        storage.insertItemManual(iron, 64);
        storage.insertFluidManual(water, 1000);
        Field active = SharedStorageManager.class.getDeclaredField("storage");
        active.setAccessible(true);
        Object previous = active.get(null);
        Class<?> bridge = Class.forName("com.futa_gtnh.ae2.AeCellBridge");
        Method itemRefresh = bridge.getDeclaredMethod("refreshItemMirror");
        Method fluidRefresh = bridge.getDeclaredMethod("refreshFluidMirror");
        itemRefresh.setAccessible(true);
        fluidRefresh.setAccessible(true);
        Field itemMirror = bridge.getDeclaredField("itemMirror");
        Field fluidMirror = bridge.getDeclaredField("fluidMirror");
        itemMirror.setAccessible(true);
        fluidMirror.setAccessible(true);
        try {
            active.set(null, storage);
            // Reuse contract fixtures for the AE stack objects; all mirror/cache logic remains real.
            Map<ItemKey, Object> initialItems = new LinkedHashMap<>();
            initialItems.put(iron, aeStack("appeng.api.storage.data.IAEItemStack"));
            Map<FluidKey, Object> initialFluids = new LinkedHashMap<>();
            initialFluids.put(water, aeStack("appeng.api.storage.data.IAEFluidStack"));
            itemMirror.set(null, initialItems);
            fluidMirror.set(null, initialFluids);
            storage.snapshotItemsCached();
            storage.snapshotFluidsCached();
            itemRefresh.invoke(null);
            fluidRefresh.invoke(null);
            Object items = itemMirror.get(null);
            Object fluids = fluidMirror.get(null);
            check(storage.itemSnapshots == 1 && storage.fluidSnapshots == 1,
                "AE shares the snapshots already built for IO");
            storage.insertFluidManual(water, 1);
            itemRefresh.invoke(null);
            fluidRefresh.invoke(null);
            check(itemMirror.get(null) == items && fluidMirror.get(null) != fluids,
                "fluid-only updates rebuild only the AE fluid mirror");
            fluids = fluidMirror.get(null);
            storage.insertItemManual(iron, 1);
            itemRefresh.invoke(null);
            fluidRefresh.invoke(null);
            check(itemMirror.get(null) != items && fluidMirror.get(null) == fluids,
                "item-only updates rebuild only the AE item mirror");
            CountingStorage replacement = new CountingStorage();
            replacement.insertItemManual(iron, 1);
            replacement.insertFluidManual(water, 1);
            replacement.insertItemManual(iron, 1);
            replacement.insertFluidManual(water, 1);
            check(replacement.getItemRevision() == storage.getItemRevision()
                && replacement.getFluidRevision() == storage.getFluidRevision(), "replacement test has matching watermarks");
            active.set(null, replacement);
            itemRefresh.invoke(null);
            fluidRefresh.invoke(null);
            check(replacement.itemSnapshots == 1 && replacement.fluidSnapshots == 1,
                "a replacement pool rebuilds mirrors even if channel revisions match");
        } finally {
            active.set(null, previous);
            itemMirror.set(null, null);
            fluidMirror.set(null, null);
            for (String name : new String[] { "itemMirrorStorage", "fluidMirrorStorage" }) {
                Field owner = bridge.getDeclaredField(name);
                owner.setAccessible(true);
                owner.set(null, null);
            }
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static Object aeStack(String interfaceName) throws Exception {
        Class<?> type = Class.forName(interfaceName);
        AtomicLong amount = new AtomicLong();
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, values) -> {
            if (method.getName().equals("setStackSize")) {
                amount.set((Long) values[0]);
                return proxy;
            }
            if (method.getName().equals("getStackSize")) return amount.get();
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == long.class) return 0L;
            return null;
        });
    }

    public static void main(String[] args) {
        Loader.injectData(new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Bootstrap.func_151354_b();
        benchmark(1000, 100);
        benchmark(6000, 1000);
    }

    private static void benchmark(int types, int ticks) {
        CountingStorage storage = new CountingStorage();
        for (int i = 0; i < types; i++) storage.insertItemManual(ItemKey.of(new ItemStack(Items.iron_ingot, 1, i)), 64);
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        storage.insertFluidManual(water, 1);
        storage.snapshotItemsCached();
        storage.itemSnapshots = 0;
        long start = System.nanoTime();
        for (int tick = 0; tick < ticks; tick++) {
            storage.insertFluidManual(water, 1);
            for (int viewer = 0; viewer < 16; viewer++) {
                if (storage.snapshotItemsCached().size() != types) throw new AssertionError("Lost item keys");
            }
        }
        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf(java.util.Locale.ROOT,
            "Cache benchmark: types=%d, fluid_writes=%d, item_queries=%d, item_snapshot_rebuilds=%d, elapsed_ms=%.3f%n",
            types, ticks, ticks * 16, storage.itemSnapshots, elapsed);
    }

    static final class CountingStorage extends SharedStorage {

        int itemSnapshots;
        int fluidSnapshots;

        @Override
        public synchronized List<Map.Entry<ItemKey, Long>> snapshotItems() {
            itemSnapshots++;
            return super.snapshotItems();
        }

        @Override
        public synchronized List<Map.Entry<FluidKey, Long>> snapshotFluids() {
            fluidSnapshots++;
            return super.snapshotFluids();
        }
    }
}
