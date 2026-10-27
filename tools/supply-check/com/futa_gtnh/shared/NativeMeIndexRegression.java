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
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.me.FrozenNativeMeCellArray1344;
import com.futa_gtnh.me.NativeMeCellArray;
import com.futa_gtnh.me.NativeMeItemIdentity;
import com.futa_gtnh.mixins.MixinAeCellContents;

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
        nativeKeySnapshots();
        loadingIntegrity();
        detachedNativeIdentity();
        compatibilityKeyCache();
        pairedIntegrityFuzz();
        allocationOptimizations();
        compactMembershipTransitions();
        System.out.println(
            "Native ME incremental index: " + checks
                + " checks passed (native amounts, per-key membership, no transfer scans, batching, simulation, overflow, limits and remount)");
    }

    private static void nativeKeySnapshots() {
        for (int i = 0; i < 64; i++) {
            ItemStack raw = item(i).prototype();
            NBTTagCompound tag = raw.getTagCompound();
            tag.setByteArray("bytes", new byte[] { (byte) i, 7, -9 });
            tag.setIntArray("ints", new int[] { i, Integer.MIN_VALUE, Integer.MAX_VALUE });
            NBTTagCompound nested = new NBTTagCompound();
            nested.setString("name", "载入检查" + i);
            NBTTagList list = new NBTTagList();
            list.appendTag(nested);
            tag.setTag("nested", list);
            ItemKey item = ItemKey.of(raw);
            FluidKey fluid = FluidKey.of(FluidRegistry.WATER, tag);
            IAEItemStack itemStack = request(item, 1);
            IAEFluidStack fluidStack = request(fluid, 1);
            ItemKey itemSnapshot = ItemKey.fromNative(itemStack);
            FluidKey fluidSnapshot = FluidKey.fromNative(fluidStack);
            check(
                itemSnapshot.equals(ItemKey.of(itemStack.getItemStack())) && itemSnapshot.hashCode() == item.hashCode(),
                "native item key preserves canonical identity/hash");
            check(
                fluidSnapshot.equals(FluidKey.of(fluidStack.getFluidStack()))
                    && fluidSnapshot.hashCode() == fluid.hashCode(),
                "native fluid key preserves canonical identity/hash");
            itemStack.setTagCompound(item(1000 + i).copyNbt());
            fluidStack.setTagCompound(fluid(1000 + i).copyNbt());
            itemSnapshot.copyNbt()
                .setInteger("indexVariant", -1);
            fluidSnapshot.copyNbt()
                .setInteger("indexVariant", -1);
            check(
                itemSnapshot.equals(item) && fluidSnapshot.equals(fluid),
                "native key copies are independent of mutable requests and returned tags");
        }
        check(ItemKey.fromNative(null) == null && FluidKey.fromNative(null) == null, "null native keys are rejected");
        check(
            ItemKey.fromNative(AEItemStack.create(new ItemStack(Items.iron_ingot)))
                .equals(ItemKey.of(new ItemStack(Items.iron_ingot))),
            "untagged native item identity");
        check(
            FluidKey.fromNative(AEFluidStack.create(new FluidStack(FluidRegistry.WATER, 1)))
                .equals(FluidKey.of(new FluidStack(FluidRegistry.WATER, 1))),
            "untagged native fluid identity");
    }

    /** Paired frozen/new loaders must retain stock, tags and whole-media quarantine, or reject the same corruption. */
    private static void loadingIntegrity() {
        NativeMeCellArray source = array();
        source.itemView()
            .put(item(0), 71L);
        source.itemView()
            .put(item(1), 91L);
        source.fluidView()
            .put(fluid(0), 3000000000L);
        source.fluidView()
            .put(fluid(1), 5000000000L);
        NBTTagCompound seed = new NBTTagCompound();
        source.writeToNBT(seed);
        for (int variant = 0; variant < 12; variant++) {
            NBTTagCompound input = (NBTTagCompound) seed.copy();
            NBTTagCompound item = input.getTagList("itemCells", 10)
                .getCompoundTagAt(0)
                .getCompoundTag("tag");
            NBTTagCompound fluid = input.getTagList("fluidCells", 10)
                .getCompoundTagAt(0)
                .getCompoundTag("tag");
            switch (variant) {
                case 1:
                    item.getCompoundTag("#0")
                        .setShort("id", (short) 32767);
                    break;
                case 2:
                    fluid.getCompoundTag("#0")
                        .setString("FluidName", "futa_missing_load_check");
                    break;
                case 3:
                    item.setLong("ic", 1);
                    break;
                case 4:
                    fluid.setLong("fc", 1);
                    break;
                case 5:
                    item.setLong("@0", -1);
                    item.getCompoundTag("#0")
                        .setLong("Cnt", -1);
                    break;
                case 6:
                    fluid.removeTag("#0");
                    break;
                case 7:
                    item.setShort("it", (short) 64);
                    break;
                case 8:
                    item.setLong("@0", Long.MAX_VALUE);
                    item.setLong("@1", Long.MAX_VALUE);
                    break;
                case 9:
                    item.setLong("ic", -1);
                    break;
                case 10:
                    NBTTagCompound row = fluid.getCompoundTag("#0");
                    row.setTag(
                        "tag",
                        row.getCompoundTag("Tag")
                            .copy());
                    row.removeTag("Tag");
                    break;
                case 11:
                    NBTTagCompound extra = new NBTTagCompound();
                    extra.setString("FluidName", "futa_missing_extra_check");
                    fluid.setTag("#999", extra);
                    break;
                default:
                    break;
            }
            NBTTagCompound original = (NBTTagCompound) input.copy();
            FrozenNativeMeCellArray1344 before = new FrozenNativeMeCellArray1344(
                f -> new ItemStack(f ? fluidMedia : itemMedia));
            NativeMeCellArray after = array();
            boolean oldFailed = false, newFailed = false;
            try {
                before.readFromNBT(input);
            } catch (RuntimeException e) {
                oldFailed = true;
            }
            try {
                after.readFromNBT(input);
            } catch (RuntimeException e) {
                newFailed = true;
            }
            check(input.equals(original), "loader never changes source NBT variant " + variant);
            check(oldFailed == newFailed, "integrity rejection matches frozen loader variant " + variant);
            if (variant >= 3 && variant <= 9)
                check(newFailed, "corrupt counters/rows/overflow refuse publication variant " + variant);
            if (newFailed) continue;
            check(
                before.itemView()
                    .equals(after.itemView())
                    && before.fluidView()
                        .equals(after.fluidView()),
                "all loaded quantities and complete identities match variant " + variant);
            check(
                before.frozenCellCount() == after.frozenCellCount(),
                "missing-mod whole-medium quarantine matches variant " + variant);
            NBTTagCompound oldSaved = new NBTTagCompound(), newSaved = new NBTTagCompound();
            before.writeToNBT(oldSaved);
            after.writeToNBT(newSaved);
            check(oldSaved.equals(newSaved), "complete saved NBT retained variant " + variant);
            if (variant == 1 || variant == 2 || variant == 11) {
                String channel = variant == 1 ? "itemCells" : "fluidCells";
                check(
                    after.frozenCellCount() == 1 && input.getTag(channel)
                        .equals(newSaved.getTag(channel)),
                    "unknown entries preserve every original field in whole medium");
            }
            NativeMeCellArray reloaded = array();
            reloaded.readFromNBT(newSaved);
            check(
                reloaded.itemView()
                    .equals(after.itemView())
                    && reloaded.fluidView()
                        .equals(after.fluidView()),
                "second remount does not duplicate or lose stock");
            if (variant == 0) check(
                after.itemView()
                    .equals(source.itemView())
                    && after.fluidView()
                        .equals(source.fluidView()),
                "all initial tagged and long quantities survive");
        }
    }

    private static void detachedNativeIdentity() throws Exception {
        for (int i = 0; i < 64; i++) {
            ItemKey key = item(i);
            IAEItemStack original = request(key, 10000000000L + i);
            check(original instanceof NativeMeItemIdentity, "real AE item identity bridge loaded");
            IAEItemStack copy = ((NativeMeItemIdentity) original).futa$copyIdentity();
            check(
                copy != original && copy.equals(original) && copy.hashCode() == original.hashCode(),
                "detached copy preserves full identity/hash");
            check(
                copy.getStackSize() == original.getStackSize(),
                "identity helper follows native quantity copy semantics");
            check(
                field(AEItemStack.class, "def").get(copy) != field(AEItemStack.class, "def").get(original),
                "native mutable definitions are independent");
            copy.setTagCompound(item(1000 + i).copyNbt());
            check(
                ItemKey.fromNative(original)
                    .equals(key),
                "changing private definition does not mutate caller definition");
            original.setTagCompound(item(2000 + i).copyNbt());
            check(
                ItemKey.fromNative(copy)
                    .equals(item(1000 + i)),
                "changing caller definition does not mutate private definition");
            IAEItemStack rehashed = ((NativeMeItemIdentity) original).futa$copyIdentity();
            IAEItemStack expected = request(item(2000 + i), 1);
            check(
                rehashed.equals(expected) && rehashed.hashCode() == expected.hashCode(),
                "copied definition rehashes even after native setter left caller hash stale");
        }
    }

    private static int cacheSize(NativeMeCellArray array, String name) throws Exception {
        Object channel = field(array.getClass(), name).get(array);
        return ((Map<?, ?>) field(channel.getClass(), "locations").get(channel)).size();
    }

    private static void compatibilityKeyCache() throws Exception {
        NativeMeCellArray seed = array();
        for (int i = 0; i < 140; i++) {
            seed.itemView()
                .put(item(i), 100L + i);
            seed.fluidView()
                .put(fluid(i), 1000L + i);
        }
        NBTTagCompound saved = new NBTTagCompound();
        seed.writeToNBT(saved);
        NativeMeCellArray cells = array();
        cells.readFromNBT(saved);
        cells.setPublished(true);
        check(
            cells.itemView()
                .size() == 140
                && cells.fluidView()
                    .size() == 140,
            "all native locations are ready before compatibility keys");
        check(
            cacheSize(cells, "items") == 0 && cacheSize(cells, "fluids") == 0,
            "loading does not manufacture unused compatibility keys");
        for (String name : new String[] { "items", "fluids" }) {
            Object channel = field(cells.getClass(), name).get(cells);
            for (Object resource : ((Map<?, ?>) field(channel.getClass(), "identities").get(channel)).values()) {
                check(
                    field(resource.getClass(), "key").get(resource) == null,
                    "cold resources have no duplicate compatibility identity");
                check(
                    ((appeng.api.storage.data.IAEStack<?>) field(resource.getClass(), "identity").get(resource))
                        .getStackSize() == 1,
                    "private identities never cache native quantities");
            }
        }
        // Full AE enumeration and actual native transfers must work before the first compatibility query.
        appeng.api.storage.data.IItemList<IAEItemStack> nativeItems = AEItemStackType.ITEM_STACK_TYPE.createList();
        for (IMEInventoryHandler<?> handler : cells.getCellArray(AEItemStackType.ITEM_STACK_TYPE)) {
            @SuppressWarnings("unchecked")
            IMEInventoryHandler<IAEItemStack> typed = (IMEInventoryHandler<IAEItemStack>) handler;
            typed.getAvailableItems(nativeItems, 0);
            if (handler instanceof ICellInventoryHandler<?>) check(
                ((ICellInventoryHandler<?>) handler).getCellInv() instanceof MixinAeCellContents,
                "native decoder visit bridge loaded");
        }
        check(
            nativeItems.size() == 140,
            "fully decoded native inventory enumerates every resource before compatibility use");
        check(
            transfer(cells, item(0), 100, false, false) == 100 && transfer(cells, fluid(0), 1000, false, false) == 1000,
            "cold native locations support exact depletion");
        check(
            cells.itemView()
                .size() == 139
                && cells.fluidView()
                    .size() == 139,
            "cold depletion removes native membership without a compatibility key");
        check(
            transfer(cells, item(0), 100, true, false) == 100 && transfer(cells, fluid(0), 1000, true, false) == 1000,
            "cold depleted resource can return");
        check(
            cacheSize(cells, "items") == 0 && cacheSize(cells, "fluids") == 0,
            "native requests and full native enumeration do not materialize compatibility keys");
        check(
            cells.itemView()
                .get(null) == null
                && cells.itemView()
                    .get("wrong") == null
                && !cells.fluidView()
                    .containsKey("wrong")
                && cells.itemView()
                    .remove("wrong") == null,
            "map view rejects null and foreign key types without enumeration");
        check(
            cells.itemView()
                .get(item(1)) == 101
                && cells.fluidView()
                    .get(fluid(1)) == 1001,
            "first compatibility lookup reads original exact quantities");
        check(
            cacheSize(cells, "items") == 1 && cacheSize(cells, "fluids") == 1,
            "one lookup caches only the requested immutable key");
        check(
            cells.itemView()
                .get(item(1)) == 101 && cacheSize(cells, "items") == 1,
            "repeated compatibility lookup reuses its key cache");
        check(
            transfer(cells, item(1), 101, false, false) == 101 && cacheSize(cells, "items") == 0,
            "depletion removes an already materialized compatibility key");
        check(
            transfer(cells, item(1), 101, true, false) == 101 && cells.itemView()
                .get(item(1)) == 101,
            "returning resource cannot use a stale compatibility location");
        check(
            cells.itemView()
                .equals(seed.itemView())
                && cells.fluidView()
                    .equals(seed.fluidView()),
            "first full compatibility snapshot preserves all quantities/tags");
        check(
            cacheSize(cells, "items") == 140 && cacheSize(cells, "fluids") == 140,
            "first full compatibility snapshot caches all immutable keys");
        NBTTagCompound after = new NBTTagCompound();
        cells.writeToNBT(after);
        NativeMeCellArray remount = array();
        remount.readFromNBT(after);
        check(
            cacheSize(remount, "items") == 0 && remount.itemView()
                .equals(cells.itemView())
                && remount.fluidView()
                    .equals(cells.fluidView()),
            "compatibility cache is not persisted and remount retains complete stock");
        cells.itemView()
            .clear();
        cells.fluidView()
            .clear();
        check(
            cells.cellCount() == 0 && cacheSize(cells, "items") == 0 && cacheSize(cells, "fluids") == 0,
            "clear invalidates both native and compatibility indices");
    }

    private static String validation(Object channel, ItemStack cell) throws Exception {
        return validation(channel, cell, null);
    }

    private static String validation(Object channel, ItemStack cell, Object context) throws Exception {
        java.lang.reflect.Method method = context == null ? channel.getClass()
            .getDeclaredMethod("validateContents", ItemStack.class)
            : channel.getClass()
                .getDeclaredMethod("validateContents", ItemStack.class, context.getClass());
        method.setAccessible(true);
        try {
            return (boolean) (context == null ? method.invoke(channel, cell) : method.invoke(channel, cell, context))
                ? "frozen"
                : "valid";
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof IllegalStateException) && !(e.getCause() instanceof ArithmeticException)
                && !(e.getCause() instanceof net.minecraft.util.ReportedException)) throw e;
            return "rejected";
        }
    }

    /** Same vanilla numeric coercions and whole-medium decisions, across unordered rows and malformed extras. */
    private static void pairedIntegrityFuzz() throws Exception {
        Random random = new Random(79431344L);
        NativeMeCellArray current = array();
        FrozenNativeMeCellArray1344 frozen = new FrozenNativeMeCellArray1344(
            f -> new ItemStack(f ? fluidMedia : itemMedia));
        for (String channel : new String[] { "items", "fluids" }) {
            boolean fluid = channel.equals("fluids");
            Object before = field(frozen.getClass(), channel).get(frozen);
            Object after = field(current.getClass(), channel).get(current);
            java.lang.reflect.Constructor<?> contextFactory = Class
                .forName("com.futa_gtnh.me.NativeMeValidationContext")
                .getDeclaredConstructor();
            contextFactory.setAccessible(true);
            Object reusedContext = contextFactory.newInstance();
            for (int sample = 0; sample < 1200; sample++) {
                int types = sample % 64;
                NBTTagCompound tag = new NBTTagCompound();
                long total = 0;
                for (int i = 0; i < types; i++) {
                    NBTTagCompound row = new NBTTagCompound();
                    if (fluid) row.setString("FluidName", FluidRegistry.getFluidName(FluidRegistry.WATER));
                    else row.setShort("id", (short) Item.getIdFromItem(Items.paper));
                    long amount = 20 + random.nextInt(100);
                    switch (i % 6) {
                        case 0:
                            tag.setByte("@" + i, (byte) amount);
                            break;
                        case 1:
                            tag.setShort("@" + i, (short) amount);
                            break;
                        case 2:
                            tag.setInteger("@" + i, (int) amount);
                            break;
                        case 3:
                            tag.setLong("@" + i, amount);
                            break;
                        case 4:
                            tag.setFloat("@" + i, amount + .75f);
                            break;
                        default:
                            tag.setDouble("@" + i, amount + .75);
                            break;
                    }
                    row.setLong("Cnt", amount);
                    tag.setTag("#" + i, row);
                    total += tag.getLong("@" + i);
                }
                String count = fluid ? "fc" : "ic", type = fluid ? "ft" : "it";
                tag.setShort(type, (short) types);
                tag.setLong(count, total);
                int index = types == 0 ? 0 : random.nextInt(types);
                switch (sample % 12) {
                    case 1:
                        tag.removeTag("#" + index);
                        break;
                    case 2:
                        tag.setLong("@" + index, -1);
                        tag.getCompoundTag("#" + index)
                            .setLong("Cnt", -1);
                        break;
                    case 3:
                        tag.setLong(count, total + 1);
                        break;
                    case 4:
                        tag.setShort(type, (short) (sample % 2 == 0 ? -1 : 64));
                        break;
                    case 5:
                        tag.setLong("@0", Long.MAX_VALUE);
                        tag.setLong("@1", Long.MAX_VALUE);
                        break;
                    case 6:
                        tag.getCompoundTag("#" + index)
                            .setString(fluid ? "FluidName" : "id", "missing");
                        break;
                    case 7:
                        tag.setLong(count, -1);
                        break;
                    case 8:
                        tag.removeTag("@" + index);
                        break;
                    case 9:
                        tag.setTag(
                            "#00",
                            tag.getCompoundTag("#0")
                                .copy());
                        tag.removeTag("#0");
                        break;
                    case 10:
                        tag.setInteger("#" + index, 1);
                        break;
                    default:
                        break;
                }
                String[] extras = { "#", "#00", "#01", "#-1", "#63", "#999", "#2147483647", "#a", "#0a" };
                String extra = extras[sample % extras.length];
                if (sample % 3 == 0) tag.setTag(
                    extra,
                    tag.getCompoundTag("#0")
                        .copy());
                if (sample % 17 == 0) tag.setString(extra, "wrong type");
                NBTTagCompound unchanged = (NBTTagCompound) tag.copy();
                ItemStack cell = new ItemStack(fluid ? fluidMedia : itemMedia);
                if (sample % 2 == 0) {
                    NBTTagCompound fallback = new NBTTagCompound() {};
                    for (Object raw : tag.func_150296_c()) {
                        String name = (String) raw;
                        fallback.setTag(name, tag.getTag(name));
                    }
                    cell.setTagCompound(fallback);
                } else cell.setTagCompound(tag);
                check(
                    validation(before, cell).equals(validation(after, cell, reusedContext)),
                    "paired complete integrity decision sample " + channel + "/" + sample);
                check(tag.equals(unchanged), "integrity checker never edits original NBT");
            }
        }
    }

    private static void allocationOptimizations() throws Exception {
        Class<?> type = Class.forName("com.futa_gtnh.me.NativeMeValidationContext");
        java.lang.reflect.Constructor<?> factory = type
            .getDeclaredConstructor(java.util.function.IntPredicate.class, java.util.function.Predicate.class);
        factory.setAccessible(true);
        int[] itemCalls = { 0 }, fluidCalls = { 0 };
        java.util.Set<Integer> registeredItems = new java.util.HashSet<>();
        java.util.Set<String> registeredFluids = new java.util.HashSet<>();
        for (int i = 0; i < 32; i++) {
            registeredItems.add(i);
            registeredFluids.add("test" + i);
        }
        java.util.function.IntPredicate itemLookup = i -> {
            itemCalls[0]++;
            return registeredItems.contains(i);
        };
        java.util.function.Predicate<String> fluidLookup = name -> {
            fluidCalls[0]++;
            return registeredFluids.contains(name);
        };
        Object context = factory.newInstance(itemLookup, fluidLookup);
        java.lang.reflect.Method itemExists = type.getDeclaredMethod("itemExists", int.class),
            fluidExists = type.getDeclaredMethod("fluidExists", String.class),
            reset = type.getDeclaredMethod("resetRows");
        itemExists.setAccessible(true);
        fluidExists.setAccessible(true);
        reset.setAccessible(true);
        for (int repeat = 0; repeat < 20; repeat++) for (int i = 0; i < 33; i++) {
            check(
                (boolean) itemExists.invoke(context, i) == (i < 32),
                "load-local item registry cache preserves presence/absence");
            check(
                (boolean) fluidExists.invoke(context, "test" + i) == (i < 32),
                "load-local fluid registry cache preserves presence/absence");
        }
        check(
            itemCalls[0] == 33 && fluidCalls[0] == 33,
            "each repeated registry identity is resolved only once per load");
        registeredItems.add(32);
        registeredFluids.add("test32");
        Object nextLoad = factory.newInstance(itemLookup, fluidLookup);
        check(
            (boolean) itemExists.invoke(nextLoad, 32) && (boolean) fluidExists.invoke(nextLoad, "test32"),
            "fresh load sees restored registry entries without stale negative cache");
        for (int i = 33; i < 1000; i++) {
            check(
                !(boolean) itemExists.invoke(context, i) && !(boolean) fluidExists.invoke(context, "test" + i),
                "distinct-heavy registry lookups remain correct past memoization budget");
        }
        check(
            field(type, "itemCache").get(context) == null && field(type, "fluidCache").get(context) == null,
            "load-local maps stop growing after bounded distinct entries");
        NBTTagCompound[] rows = (NBTTagCompound[]) field(type, "rows").get(context);
        long[] amounts = (long[]) field(type, "amounts").get(context);
        for (int i = 0; i < 63; i++) {
            rows[i] = new NBTTagCompound();
            amounts[i] = 77;
        }
        reset.invoke(context);
        for (int i = 0; i < 63; i++)
            check(rows[i] == null && amounts[i] == 0, "reused scratch clears every prior row/count");

        NativeMeCellArray cells = array();
        SharedStorage access = new SharedStorage();
        access.attachNativeCells(cells, () -> {});
        cells.setPublished(true);
        for (int i = 0; i < 140; i++) {
            cells.itemView()
                .put(item(i), 100L + i);
            cells.fluidView()
                .put(fluid(i), 1000L + i);
        }
        for (String name : new String[] { "items", "fluids" }) {
            Object channel = field(cells.getClass(), name).get(cells);
            for (Object resource : ((Map<?, ?>) field(channel.getClass(), "identities").get(channel)).values()) check(
                field(resource.getClass(), "firstSlot").get(resource) != null
                    && field(resource.getClass(), "extraSlots").get(resource) == null,
                "single-medium resources allocate no relationship list");
        }
        List<Map.Entry<ItemKey, Long>> items = access.snapshotPoolItems();
        List<Map.Entry<FluidKey, Long>> fluids = access.snapshotPoolFluids();
        check(
            items.equals(
                new java.util.ArrayList<>(
                    cells.itemView()
                        .entrySet()))
                && fluids.equals(
                    new java.util.ArrayList<>(
                        cells.fluidView()
                            .entrySet())),
            "direct final snapshots preserve complete keys/quantities/order");
        try {
            items.clear();
            throw new AssertionError("mutable snapshot list");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
        try {
            items.get(0)
                .setValue(1L);
            throw new AssertionError("mutable snapshot row");
        } catch (UnsupportedOperationException expected) {
            checks++;
        }
        ItemKey selected = items.get(0)
            .getKey();
        long original = items.get(0)
            .getValue();
        check(
            transfer(cells, selected, 1, false, false) == 1 && items.get(0)
                .getValue() == original && access.getItemAmountPool(selected) == original - 1,
            "snapshot quantities are independent while live quantities remain native");
        Object savedItems = field(SharedStorage.class, "items").get(access),
            savedFluids = field(SharedStorage.class, "fluids").get(access);
        java.util.AbstractMap<Object, Long> rejectEntrySet = new java.util.AbstractMap<Object, Long>() {

            @Override
            public java.util.Set<Entry<Object, Long>> entrySet() {
                throw new AssertionError("snapshot built an intermediate entry set");
            }
        };
        field(SharedStorage.class, "items").set(access, rejectEntrySet);
        field(SharedStorage.class, "fluids").set(access, rejectEntrySet);
        try {
            check(
                access.snapshotPoolItems()
                    .size() == 140
                    && access.snapshotPoolFluids()
                        .size() == 140,
                "native facade snapshots bypass intermediate map entry sets");
        } finally {
            field(SharedStorage.class, "items").set(access, savedItems);
            field(SharedStorage.class, "fluids").set(access, savedFluids);
        }
    }

    private static void compactMembershipTransitions() throws Exception {
        NativeMeCellArray seed = array();
        seed.itemView()
            .put(item(600), 50L);
        seed.fluidView()
            .put(fluid(600), 500L);
        NBTTagCompound data = new NBTTagCompound();
        seed.writeToNBT(data);
        for (String name : new String[] { "itemCells", "fluidCells" }) {
            NBTTagCompound cell = data.getTagList(name, 10)
                .getCompoundTagAt(0);
            net.minecraft.nbt.NBTTagList copies = new net.minecraft.nbt.NBTTagList();
            for (int i = 0; i < 3; i++) copies.appendTag(cell.copy());
            data.setTag(name, copies);
        }
        NativeMeCellArray cells = array();
        cells.readFromNBT(data);
        cells.setPublished(true);
        for (String name : new String[] { "items", "fluids" }) {
            Object channel = field(cells.getClass(), name).get(cells);
            Object resource = ((Map<?, ?>) field(channel.getClass(), "identities").get(channel)).values()
                .iterator()
                .next();
            check(
                ((List<?>) field(resource.getClass(), "extraSlots").get(resource)).size() == 2,
                "three-media resource records inline first plus two extra locations");
            Object key = name.equals("items") ? item(600) : fluid(600);
            long perCell = name.equals("items") ? 50 : 500;
            check(transfer(cells, key, perCell, false, false) == perCell, "first physical medium drains exactly");
            check(
                ((List<?>) field(resource.getClass(), "extraSlots").get(resource)).size() == 1
                    && field(resource.getClass(), "firstSlot").get(resource) != null,
                "depleting first medium promotes the next without losing later locations");
            check(
                transfer(cells, key, perCell, false, false) == perCell
                    && field(resource.getClass(), "extraSlots").get(resource) == null,
                "two media collapse to one inline location");
            check(
                transfer(cells, key, perCell, false, false) == perCell
                    && field(resource.getClass(), "firstSlot").get(resource) == null,
                "last medium removes the complete relationship");
            check(
                transfer(cells, key, 7, true, false) == 7,
                "empty native media can be reused after compact relation removal");
        }
        check(
            cells.itemView()
                .get(item(600)) == 7
                && cells.fluidView()
                    .get(fluid(600)) == 7,
            "all compact transitions conserve exact item/fluid quantities");
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
