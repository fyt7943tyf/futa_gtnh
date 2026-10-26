package com.futa_gtnh.shared;

import java.io.File;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.fluids.FluidRegistry;

/** Real SharedStorage routing and persistence against a recording ME boundary. */
public final class StorageNetworkAccessRegression {

    private static int checks;

    private StorageNetworkAccessRegression() {}

    public static void run() throws Exception {
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        FluidKey lava = FluidKey.of(FluidRegistry.LAVA, null);
        SharedStorage storage = new SharedStorage();
        storage.poolInsertItem(iron, 13, true);
        storage.poolInsertFluid(water, 20, true);
        storage.setItemLimit(iron, 30);
        storage.setFluidLimit(water, 50);
        RecordingNetwork first = new RecordingNetwork();
        first.items.put(iron, 700L);
        first.items.put(gold, 800L);
        first.fluids.put(water, 900L);
        first.fluids.put(lava, 1000L);
        storage.setNetworkAccess(first);
        check(storage.usesNetwork(), "binding selects network routing");
        check(storage.getItemAmount(iron) == 700 && storage.getFluidAmount(water) == 900,
            "normal amount queries use network quantities instead of adding the raw pool again");
        check(storage.getItemTotal() == 1500 && storage.getFluidTotal() == 1900
            && storage.itemTypeCount() == 2 && storage.fluidTypeCount() == 2,
            "totals and type counts reflect only the selected network view");
        check(storage.insertItem(iron, 10) == 5 && first.last.equals("item-in:false:false"),
            "automated item insertion delegates and preserves actual accepted count");
        check(storage.insertItemManual(iron, 10) == 5 && first.last.equals("item-in:true:false"),
            "manual item insertion preserves manual flag");
        check(storage.insertItemDirect(iron, 10) == 5 && first.last.equals("item-in:false:true"),
            "direct item insertion still routes through ME with pool-only context");
        check(storage.insertItemManualDirect(iron, 10) == 5 && first.last.equals("item-in:true:true"),
            "manual direct item insertion preserves both flags");
        check(storage.insertFluid(water, 10) == 5 && first.last.equals("fluid-in:false:false"),
            "automated fluid insertion delegates accepted mB");
        check(storage.insertFluidManual(water, 10) == 5 && first.last.equals("fluid-in:true:false"),
            "manual fluid insertion preserves manual flag");
        check(storage.insertFluidDirect(water, 10) == 5 && first.last.equals("fluid-in:false:true"),
            "direct fluid insertion routes with pool-only context");
        check(storage.insertFluidManualDirect(water, 10) == 5 && first.last.equals("fluid-in:true:true"),
            "manual direct fluid insertion preserves both flags");
        TileEntity excluded = new TileEntity();
        check(storage.extractItemExcluding(iron, 10, excluded) == 5 && first.excluded == excluded,
            "item extraction preserves the excluded output container");
        check(storage.extractFluidExcluding(water, 10, excluded) == 5 && first.excluded == excluded,
            "fluid extraction preserves the excluded output container");
        check(storage.extractItem(iron, 10) == 5 && first.excluded == null,
            "ordinary extraction clears earlier exclusion");
        check(storage.extractFluid(water, 10) == 5 && first.excluded == null,
            "ordinary fluid extraction does not inherit unrelated exclusions");
        check(storage.itemSpaceLeft(iron) == 100 && first.last.equals("item-space:false")
            && storage.itemManualSpaceLeft(iron) == 200 && first.last.equals("item-space:true"),
            "item capacity queries route their automation or manual policy");
        check(storage.fluidSpaceLeft(water) == 300 && first.last.equals("fluid-space:false")
            && storage.fluidManualSpaceLeft(water) == 400 && first.last.equals("fluid-space:true"),
            "fluid capacity queries route their automation or manual policy");

        int calls = first.calls;
        check(storage.getItemAmountPool(iron) == 13 && storage.getFluidAmountPool(water) == 20,
            "network actions do not accidentally mutate raw durable stock");
        check(storage.poolItemSpace(iron, false) == 17 && storage.poolFluidSpace(water, false) == 30,
            "raw provider capacity ignores network quantities and respects raw limits");
        check(storage.poolInsertItem(iron, 3, true) == 3 && storage.poolExtractItem(iron, 2) == 2
            && storage.poolInsertFluid(water, 10, true) == 10 && storage.poolExtractFluid(water, 4) == 4,
            "raw provider operations address the durable pool directly");
        check(storage.snapshotPoolItems().size() == 1 && storage.snapshotPoolItems().get(0).getValue() == 14
            && storage.snapshotPoolFluids().size() == 1 && storage.snapshotPoolFluids().get(0).getValue() == 26,
            "raw snapshots never contain other network disks or external containers");
        NBTTagCompound tag = storage.writeToNbt();
        check(first.calls == calls, "raw operations and serialization never invoke the network facade");
        check(tag.getTagList("items", 10).tagCount() == 1
            && tag.getTagList("items", 10).getCompoundTagAt(0).getLong("amount") == 14
            && tag.getTagList("fluids", 10).tagCount() == 1
            && tag.getTagList("fluids", 10).getCompoundTagAt(0).getLong("amount") == 26,
            "NBT persistence serializes only raw stock exactly once");
        File root = new File("build/storage-network-access-check");
        root.mkdirs();
        File world = Files.createTempDirectory(root.toPath(), "raw-only-").toFile();
        check(SharedStorageFile.save(world, storage), "actual file writer accepts a network-bound pool");
        SharedStorage reloaded = SharedStorageFile.loadForAe(world);
        check(reloaded.getItemAmountPool(iron) == 14 && reloaded.getItemAmountPool(gold) == 0
            && reloaded.getFluidAmountPool(water) == 26 && reloaded.getFluidAmountPool(lava) == 0,
            "save, migration and reload cannot materialize other ME storage into raw stock");

        List<Map.Entry<ItemKey, Long>> cached = storage.snapshotItemsCached();
        first.items.put(iron, 701L);
        first.itemRevision++;
        check(storage.snapshotItemsCached() != cached && storage.getItemAmount(iron) == 701,
            "network revisions invalidate old cached views");
        storage.setNetworkAccess(null);
        check(!storage.usesNetwork() && storage.getItemAmountPool(iron) == 14
            && storage.snapshotPoolItems().size() == 1, "detaching leaves durable stock untouched");
        RecordingNetwork second = new RecordingNetwork();
        second.items.put(gold, 7L);
        storage.setNetworkAccess(second);
        check(storage.getItemAmount(iron) == 0 && storage.getItemAmount(gold) == 7
            && storage.snapshotItemsCached().size() == 1
            && storage.snapshotItemsCached().get(0).getKey().equals(gold),
            "rebinding selects only the new network, including its cache generation");
        check(storage.getItemAmountPool(iron) == 14 && storage.getItemAmountPool(gold) == 0,
            "rebinding never merges either network's view into the raw pool");
        storage.setNetworkAccess(first);
        check(storage.getItemAmount(iron) == 701 && storage.getItemAmountPool(iron) == 14,
            "returning to a former network does not duplicate its data");
        System.out.println("Storage network access regression passed: " + checks + " checks");
    }

    private static final class RecordingNetwork implements StorageNetworkAccess {

        final Map<ItemKey, Long> items = new LinkedHashMap<>();
        final Map<FluidKey, Long> fluids = new LinkedHashMap<>();
        int calls;
        int itemRevision;
        String last = "";
        TileEntity excluded;

        private void record(String operation) { calls++; last = operation; }
        @Override public long itemAmount(ItemKey key) { record("item-amount"); return items.getOrDefault(key, 0L); }
        @Override public long fluidAmount(FluidKey key) { record("fluid-amount"); return fluids.getOrDefault(key, 0L); }
        @Override public long insertItem(ItemKey key, long amount, boolean manual, boolean direct) {
            record("item-in:" + manual + ":" + direct); return amount / 2;
        }
        @Override public long insertFluid(FluidKey key, long amount, boolean manual, boolean direct) {
            record("fluid-in:" + manual + ":" + direct); return amount / 2;
        }
        @Override public long extractItem(ItemKey key, long amount, TileEntity excluded) {
            record("item-out"); this.excluded = excluded; return amount / 2;
        }
        @Override public long extractFluid(FluidKey key, long amount, TileEntity excluded) {
            record("fluid-out"); this.excluded = excluded; return amount / 2;
        }
        @Override public long itemSpace(ItemKey key, boolean manual) { record("item-space:" + manual); return manual ? 200 : 100; }
        @Override public long fluidSpace(FluidKey key, boolean manual) { record("fluid-space:" + manual); return manual ? 400 : 300; }
        @Override public List<Map.Entry<ItemKey, Long>> items() { record("items"); return snapshot(items); }
        @Override public List<Map.Entry<FluidKey, Long>> fluids() { record("fluids"); return snapshot(fluids); }
        @Override public int itemRevision() { return itemRevision; }
        @Override public int fluidRevision() { return 0; }

        private static <K> List<Map.Entry<K, Long>> snapshot(Map<K, Long> values) {
            List<Map.Entry<K, Long>> result = new ArrayList<>();
            for (Map.Entry<K, Long> entry : values.entrySet()) result.add(new AbstractMap.SimpleImmutableEntry<>(entry));
            return Collections.unmodifiableList(result);
        }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
