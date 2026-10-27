package com.futa_gtnh.shared;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.me.NativeMeCellArray;

import appeng.api.config.Actionable;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;

/** Native cells plus an independent model; spies reject full-cell scans on the transfer path. */
public final class NativeMeIndexRegression {

    private static int checks;
    private static Item itemMedia, fluidMedia;

    private static final class Spies {

        final Map<Object, Object> installed = new IdentityHashMap<>();
        int enumerations;
        boolean rejectEnumeration;

        @SuppressWarnings("rawtypes")
        void install(NativeMeCellArray array) throws Exception {
            for (String name : new String[] { "items", "fluids" }) {
                Object channel = field(array.getClass(), name).get(array);
                for (Object slot : (List<?>) field(channel.getClass(), "slots").get(channel)) {
                    if (installed.containsKey(slot)) continue;
                    Field nativeField = field(slot.getClass(), "nativeInventory");
                    Object delegate = nativeField.get(slot);
                    Object proxy = Proxy.newProxyInstance(
                        ICellInventoryHandler.class.getClassLoader(),
                        new Class<?>[] { ICellInventoryHandler.class },
                        (p, m, a) -> {
                            if (m.getName()
                                .equals("getAvailableItems")) {
                                enumerations++;
                                if (rejectEnumeration)
                                    throw new AssertionError("A transfer enumerated a complete native cell");
                            }
                            try {
                                return m.invoke(delegate, a);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
                    nativeField.set(slot, proxy);
                    installed.put(slot, proxy);
                }
            }
        }
    }

    public static void run(Item items, Item fluids) throws Exception {
        itemMedia = items;
        fluidMedia = fluids;
        incremental();
        limitsAndEscrow();
        multiCellAndReload();
        bulk();
        nativeBypassAndMutableRequests();
        System.out.println(
            "Native ME incremental index: " + checks
                + " checks passed (native amounts, per-key membership, no transfer scans, batching, simulation, overflow, limits and remount)");
    }

    private static NativeMeCellArray array() {
        return new NativeMeCellArray(fluid -> new ItemStack(fluid ? fluidMedia : itemMedia));
    }

    private static ItemKey item(int i) {
        ItemStack stack = new ItemStack(Items.paper);
        NBTTagCompound n = new NBTTagCompound();
        n.setInteger("indexVariant", i);
        stack.setTagCompound(n);
        return ItemKey.of(stack);
    }

    private static FluidKey fluid(int i) {
        NBTTagCompound n = new NBTTagCompound();
        n.setInteger("indexVariant", i);
        return FluidKey.of(new FluidStack(FluidRegistry.WATER, 1, n));
    }

    private static IAEItemStack request(ItemKey key, long count) {
        return AEItemStack.create(key.prototype())
            .setStackSize(count);
    }

    private static IAEFluidStack request(FluidKey key, long count) {
        return AEFluidStack.create(key.prototype())
            .setStackSize(count);
    }

    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void incremental() throws Exception {
        NativeMeCellArray cells = array();
        SharedStorage access = new SharedStorage();
        int[] dirty = { 0 }, topology = { 0 };
        access.attachNativeCells(cells, () -> dirty[0]++);
        cells.setPublished(true);
        cells.onTopologyChanged(() -> topology[0]++);
        Map<ItemKey, Long> items = new HashMap<>();
        Map<FluidKey, Long> fluids = new HashMap<>();
        for (int i = 0; i < 140; i++) {
            items.put(item(i), 1000L);
            fluids.put(fluid(i), 1000L);
            cells.itemView()
                .put(item(i), 1000L);
            cells.fluidView()
                .put(fluid(i), 1000L);
        }
        check(cells.cellCount() == 6, "native type limits allocate three cells per channel");
        Spies spy = new Spies();
        spy.install(cells);
        spy.rejectEnumeration = true;
        Random random = new Random(1342);
        for (int i = 0; i < 8000; i++) {
            int kind = random.nextInt(180);
            boolean incoming = random.nextBoolean(), simulate = i % 7 == 0;
            long wanted = random.nextInt(1500) + 1;
            ItemKey ik = item(kind);
            FluidKey fk = fluid(kind);
            long before = items.getOrDefault(ik, 0L), fBefore = fluids.getOrDefault(fk, 0L);
            // Exercise the actual published handlers, including unsuccessful requests to every other cell.
            long itemDelta = transfer(cells, ik, wanted, incoming, simulate),
                fluidDelta = transfer(cells, fk, wanted, incoming, simulate);
            check(itemDelta == (incoming ? wanted : Math.min(before, wanted)), "actual item acceptance/withdrawal");
            check(fluidDelta == (incoming ? wanted : Math.min(fBefore, wanted)), "actual fluid acceptance/withdrawal");
            if (!simulate) {
                long left = incoming ? before + itemDelta : before - itemDelta,
                    fLeft = incoming ? fBefore + fluidDelta : fBefore - fluidDelta;
                if (left == 0) items.remove(ik);
                else items.put(ik, left);
                if (fLeft == 0) fluids.remove(fk);
                else fluids.put(fk, fLeft);
            }
            check(
                access.getItemAmountPool(ik) == items.getOrDefault(ik, 0L),
                "item location lookup follows actual native quantity");
            check(
                access.getFluidAmountPool(fk) == fluids.getOrDefault(fk, 0L),
                "fluid location lookup preserves full NBT");
            if (i % 83 == 0) {
                check(
                    cells.itemView()
                        .equals(items),
                    "all item memberships agree with independent model");
                check(
                    cells.fluidView()
                        .equals(fluids),
                    "all fluid memberships agree with independent model");
            }
            spy.install(cells);
        }
        check(
            spy.enumerations == 0,
            "ordinary/simulated/failed/new-key transfers never enumerate complete native cells");
        int count = dirty[0], nodes = topology[0];
        check(
            transfer(cells, item(999999), 1, false, false) == 0 && transfer(cells, fluid(999999), 1, false, false) == 0,
            "missing resources are not manufactured");
        check(dirty[0] == count && topology[0] == nodes, "failed requests do not mark saves or topology dirty");
        spy.rejectEnumeration = false;
        NBTTagCompound saved = new NBTTagCompound();
        cells.writeToNBT(saved);
        NativeMeCellArray loaded = array();
        loaded.readFromNBT(saved);
        check(
            loaded.itemView()
                .equals(items)
                && loaded.fluidView()
                    .equals(fluids),
            "random transfer state survives exact native serialization/remount");
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static long transfer(NativeMeCellArray cells, Object key, long count, boolean in, boolean simulate) {
        boolean fluid = key instanceof FluidKey;
        Object type = fluid ? AEFluidStackType.FLUID_STACK_TYPE : AEItemStackType.ITEM_STACK_TYPE;
        appeng.api.storage.data.IAEStack request = fluid ? request((FluidKey) key, count)
            : request((ItemKey) key, count);
        long remaining = count;
        Actionable mode = simulate ? Actionable.SIMULATE : Actionable.MODULATE;
        for (IMEInventoryHandler handler : cells.getCellArray((appeng.api.storage.data.IAEStackType) type)) {
            appeng.api.storage.data.IAEStack result = in ? handler.injectItems(
                request.copy()
                    .setStackSize(remaining),
                mode,
                null)
                : handler.extractItems(
                    request.copy()
                        .setStackSize(remaining),
                    mode,
                    null);
            remaining = in ? (result == null ? 0 : result.getStackSize())
                : remaining - (result == null ? 0 : result.getStackSize());
            if (remaining == 0) break;
        }
        return count - remaining;
    }

    private static void limitsAndEscrow() {
        NativeMeCellArray cells = array();
        SharedStorage access = new SharedStorage();
        access.attachNativeCells(cells, () -> {});
        cells.setPublished(true);
        ItemKey a = item(0);
        FluidKey b = fluid(0);
        access.poolInsertItem(a, 100, true);
        access.poolInsertFluid(b, 100, true);
        access.setItemLimit(a, 110);
        access.setFluidLimit(b, 110);
        check(
            transfer(cells, a, 50, true, true) == 10 && access.getItemAmountPool(a) == 100,
            "item simulated limits read native quantity");
        check(
            transfer(cells, b, 50, true, false) == 10 && access.getFluidAmountPool(b) == 110,
            "fluid actual partial limit");
        access.reserveItemForCrafting(a, 30);
        access.reserveFluidForCrafting(b, 30);
        check(
            transfer(cells, a, 100, true, false) == 10 && access.getItemAmountPool(a) == 80,
            "escrow capacity remains reserved for items");
        check(
            transfer(cells, b, 100, true, false) == 0 && access.getFluidAmountPool(b) == 80,
            "escrow capacity remains reserved for fluids");
        access.clearItemLimit(a);
        access.clearFluidLimit(b);
        check(
            transfer(cells, a, 5, true, false) == 5 && transfer(cells, b, 5, true, false) == 5,
            "removing caps changes subsequent native acceptance immediately");
        int disks = cells.cellCount();
        try {
            AEFluidStack.create(new FluidStack(new net.minecraftforge.fluids.Fluid("futa_index_unregistered"), 1));
            throw new AssertionError("native engine must refuse unregistered fluids");
        } catch (IllegalArgumentException expected) {
            checks++;
        }
        check(cells.cellCount() == disks, "unregistered fluids are refused before any native media changes");
    }

    private static void multiCellAndReload() {
        NativeMeCellArray cells = array();
        SharedStorage access = new SharedStorage();
        access.attachNativeCells(cells, () -> {});
        cells.setPublished(true);
        ItemKey a = item(400);
        FluidKey b = fluid(400);
        check(
            transfer(cells, a, Long.MAX_VALUE, true, false) == Long.MAX_VALUE,
            "huge item insertion splits native media");
        check(
            transfer(cells, b, Long.MAX_VALUE, true, false) == Long.MAX_VALUE,
            "huge fluid insertion splits native media");
        check(
            transfer(cells, a, 1, true, false) == 0 && transfer(cells, b, 1, true, false) == 0,
            "aggregate long cap remains exact without a quantity cache");
        check(
            transfer(cells, a, Long.MAX_VALUE - 1, false, false) == Long.MAX_VALUE - 1
                && access.getItemAmountPool(a) == 1,
            "multi-cell drain retains only actual item locations");
        check(
            transfer(cells, b, Long.MAX_VALUE, false, false) == Long.MAX_VALUE && !cells.fluidView()
                .containsKey(b),
            "full fluid drain removes all locations");
        check(
            transfer(cells, b, 42, true, false) == 42 && access.getFluidAmountPool(b) == 42,
            "drained native media is reused correctly");
        Object stale = cells.getCellArray(AEItemStackType.ITEM_STACK_TYPE)
            .get(0);
        NBTTagCompound data = new NBTTagCompound();
        cells.writeToNBT(data);
        cells.readFromNBT(data);
        check(
            ((IMEInventoryHandler<IAEItemStack>) stale).extractItems(request(a, 1), Actionable.MODULATE, null) == null,
            "in-place remount invalidates old handles");
        check(
            access.getItemAmountPool(a) == 1 && access.getFluidAmountPool(b) == 42,
            "in-place remount rebuilds native identities exactly");
    }

    private static void bulk() throws Exception {
        SharedStorage legacy = new SharedStorage();
        for (int i = 0; i < 700; i++) {
            legacy.poolInsertItem(item(i), i + 1, true);
            legacy.poolInsertFluid(fluid(i), i + 2, true);
        }
        Map<ItemKey, Long> expectedItems = new HashMap<>(legacy.itemView());
        Map<FluidKey, Long> expectedFluids = new HashMap<>(legacy.fluidView());
        NativeMeCellArray cells = array();
        legacy.attachNativeCells(cells, () -> {});
        check(
            cells.itemView()
                .equals(expectedItems)
                && cells.fluidView()
                    .equals(expectedFluids),
            "batch import preserves every full key and quantity");
        check(cells.cellCount() == 24, "batch import fills native type capacity before expansion");
        cells.importStock(
            java.util.Collections.singletonMap(item(0), 9L),
            java.util.Collections.singletonMap(fluid(0), 8L));
        check(
            legacy.getItemAmountPool(item(0)) == 10 && legacy.getFluidAmountPool(fluid(0)) == 10,
            "metadata rows restored after a mod returns merge with existing native stock");
        Spies spy = new Spies();
        spy.install(cells);
        Map<ItemKey, Long> moreItems = new java.util.LinkedHashMap<>();
        Map<FluidKey, Long> moreFluids = new java.util.LinkedHashMap<>();
        moreItems.put(item(0), 1L);
        moreFluids.put(fluid(0), 1L);
        for (int i = 700; i < 780; i++) {
            moreItems.put(item(i), 1L);
            moreFluids.put(fluid(i), 1L);
        }
        cells.importStock(moreItems, moreFluids);
        check(spy.enumerations == 4, "bulk import enumerates each changed pre-existing cell once, never once per row");
        check(
            cells.itemView()
                .size() == 780
                && cells.fluidView()
                    .size() == 780
                && legacy.getItemAmountPool(item(0)) == 11,
            "bulk import preserves existing and newly allocated memberships");
        cells.setPublished(true);
        try {
            cells.importStock(expectedItems, expectedFluids);
            throw new AssertionError("online import must be refused");
        } catch (IllegalStateException expected) {
            checks++;
        }
        SharedStorage original = new SharedStorage();
        original.poolInsertItem(item(1), 42, true);
        original.poolInsertFluid(fluid(1), 73, true);
        NBTTagCompound source = original.writeToNbt();
        NativeMeCellArray broken = new NativeMeCellArray(isFluid -> isFluid ? null : new ItemStack(itemMedia));
        try {
            original.attachNativeCells(broken, () -> {});
            throw new AssertionError("second-channel failure must stop attach");
        } catch (IllegalStateException expected) {
            checks++;
        }
        check(
            original.nativeCells() == null && source.equals(original.writeToNbt()),
            "failed batch conversion retains the entire legacy decoder and both original quantity maps");
    }

    @SuppressWarnings("unchecked")
    private static void nativeBypassAndMutableRequests() throws Exception {
        NativeMeCellArray cells = array();
        SharedStorage access = new SharedStorage();
        access.attachNativeCells(cells, () -> {});
        cells.setPublished(true);
        ItemKey a = item(1), b = item(2);
        transfer(cells, a, 100, true, false);
        Object channel = field(cells.getClass(), "items").get(cells);
        Object slot = ((List<?>) field(channel.getClass(), "slots").get(channel)).get(0);
        IMEInventoryHandler<IAEItemStack> handler = (IMEInventoryHandler<IAEItemStack>) field(
            slot.getClass(),
            "nativeInventory").get(slot);
        appeng.api.storage.ICellInventory<IAEItemStack> raw = ((ICellInventoryHandler<IAEItemStack>) handler)
            .getCellInv();
        // Cached absence must not survive an integration writing straight into the real native engine.
        check(transfer(cells, b, 1, false, false) == 0, "cache an absent resource before raw native insertion");
        raw.injectItems(request(b, 35), Actionable.MODULATE, null);
        check(
            access.getItemAmountPool(b) == 35 && transfer(cells, b, 5, false, false) == 5,
            "raw native save callback repairs just the externally changed cell");
        raw.extractItems(request(a, 100), Actionable.MODULATE, null);
        check(
            !cells.itemView()
                .containsKey(a) && transfer(cells, a, 1, false, false) == 0,
            "raw native depletion removes membership and invalidates positive lookup");
        raw.injectItems(request(a, 23), Actionable.MODULATE, null);
        IAEItemStack mutable = request(a, 1);
        IMEInventoryHandler<IAEItemStack> published = cells.getCellArray(AEItemStackType.ITEM_STACK_TYPE)
            .get(0);
        check(
            published.extractItems(mutable, Actionable.SIMULATE, null)
                .getStackSize() == 1,
            "prime lookup with caller-owned stack");
        // Native AEItemStack.setTagCompound leaves its old hash in this pinned AE version. A fresh request is valid;
        // changing the old caller-owned object must not alter a cached identity and hide the fresh request's stock.
        IAEItemStack missing = request(item(999), 1);
        check(
            published.extractItems(missing, Actionable.SIMULATE, null) == null,
            "prime an absent caller-owned identity");
        missing.setTagCompound(b.copyNbt());
        check(
            published.extractItems(request(b, 1), Actionable.MODULATE, null)
                .getStackSize() == 1 && access.getItemAmountPool(a) == 23
                && access.getItemAmountPool(b) == 29,
            "mutating an old request cannot poison a cached absent identity");
        transfer(cells, fluid(1), 20, true, false);
        transfer(cells, fluid(2), 30, true, false);
        IAEFluidStack reused = request(fluid(1), 1);
        IMEInventoryHandler<IAEFluidStack> fluidHandler = cells.getCellArray(AEFluidStackType.FLUID_STACK_TYPE)
            .get(0);
        check(
            fluidHandler.extractItems(reused, Actionable.SIMULATE, null)
                .getStackSize() == 1,
            "prime a reusable fluid request");
        reused.setTagCompound(fluid(2).copyNbt());
        check(
            fluidHandler.extractItems(reused, Actionable.MODULATE, null)
                .getStackSize() == 1 && access.getFluidAmountPool(fluid(1)) == 20
                && access.getFluidAmountPool(fluid(2)) == 29,
            "a valid mutable fluid request changes cache identity safely");
        cells.itemView()
            .clear();
        check(
            cells.itemView()
                .isEmpty()
                && cells.itemView()
                    .size() == 0,
            "clear resets both native identity indexes and probes");
    }
}
