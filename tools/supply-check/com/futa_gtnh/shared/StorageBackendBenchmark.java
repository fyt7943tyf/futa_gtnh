package com.futa_gtnh.shared;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntToLongFunction;

import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.me.FrozenNativeMeCellArray1341;
import com.futa_gtnh.me.FrozenNativeMeCellArray1344;
import com.futa_gtnh.me.NativeMeCellArray;
import com.futa_gtnh.mixins.MixinAeCellContents;
import com.glodblock.github.common.item.ItemFluidExtremeStorageCell;
import com.google.gson.GsonBuilder;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.items.storage.ItemExtremeStorageCell;
import appeng.me.cache.GridStorageCache;
import appeng.me.cache.ItemFlowGridCache;
import appeng.me.cache.NetworkMonitor;
import appeng.me.cache.SecurityCache;
import appeng.me.storage.CellInventory;
import appeng.me.storage.NetworkInventoryHandler;
import appeng.util.IterationCounter;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;

/** Isolated real storage-engine comparison, deliberately excluded from normal builds and release JARs. */
public final class StorageBackendBenchmark {

    private static final long QUANTITY = 1000000;
    private static final BaseActionSource SOURCE = new BaseActionSource();
    private static final int WARMUP = Integer.getInteger("futa.storageWarmup", 300);
    private static final int SAMPLES = Integer.getInteger("futa.storageSamples", 600);
    private static final String PROFILE = System.getProperty("futa.storageProfile", "nbt");
    private static volatile long blackhole;
    private static Item itemCell, fluidCell;
    private static Item metaItem;
    private static com.sun.management.ThreadMXBean allocations;
    private static int checks;

    public static void run() throws Exception {
        Config.enableSupplyTracking = false;
        // Zero is also a fresh NetworkInventoryHandler's initial pass; real initialized worlds have advanced this.
        IterationCounter.fetchNewId();
        java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean
            && ((com.sun.management.ThreadMXBean) bean).isThreadAllocatedMemorySupported()) {
            allocations = (com.sun.management.ThreadMXBean) bean;
            allocations.setThreadAllocatedMemoryEnabled(true);
        }
        itemCell = new ItemExtremeStorageCell("Universe", 576460752303423487L, 63, 16384, 600000);
        fluidCell = new ItemFluidExtremeStorageCell("fluid_storage.Universe", 4503599627370495L, 16384, 63, 600000);
        Item.itemRegistry.addObject(30998, "futa_storage_bench:item_cell", itemCell);
        Item.itemRegistry.addObject(30999, "futa_storage_bench:fluid_cell", fluidCell);
        if (!PROFILE.equals("nbt") && !PROFILE.equals("plain"))
            throw new IllegalArgumentException("storageProfile must be nbt or plain");
        if (PROFILE.equals("plain")) {
            metaItem = new Item().setHasSubtypes(true);
            Item.itemRegistry.addObject(30997, "futa_storage_bench:meta_item", metaItem);
            FluidRegistry.registerFluid(new Fluid("futa_storage_bench_absent"));
            for (String raw : System.getProperty("futa.storageScales", "1,63,1024,4096")
                .split(","))
                for (int i = 0; i < Integer.parseInt(raw); i++)
                    if (FluidRegistry.getFluid("futa_storage_bench_" + i) == null)
                        FluidRegistry.registerFluid(new Fluid("futa_storage_bench_" + i));
        }
        if (Boolean.getBoolean("futa.storageLoadOnly")) {
            loadingBenchmark();
            return;
        }
        if (Boolean.getBoolean("futa.storageVerifySaved")) {
            verifySavedArtifacts();
            return;
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put(
            "startedAtUtc",
            java.time.Instant.now()
                .toString());
        report.put("baselineTag", "v1.33.10");
        report.put("baselineCommit", "e0f2358e6e8f5804a389a7ffbe67592706a0a010");
        report.put("publicVersion", "1.34.5-candidate");
        report.put("nativeBaseline", "v1.34.1 / d191ba5d1cd1b0df2181778e35351570bd4b9a37");
        report.put(
            "storageHelper",
            appeng.api.AEApi.instance()
                .storage()
                .getClass()
                .getName());
        require(
            appeng.api.AEApi.instance()
                .storage() instanceof appeng.core.api.ApiStorage,
            "benchmark uses the actual native storage helper, not a proxy factory");
        report.put("aeVersion", "rv3-beta-1082-GTNH");
        report.put("ae2fcVersion", "1.5.108-gtnh");
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        report.put(
            "processors",
            Runtime.getRuntime()
                .availableProcessors());
        report.put(
            "maxHeapMiB",
            Runtime.getRuntime()
                .maxMemory() / 1048576);
        report.put("quantityPerResource", QUANTITY);
        report.put("warmupSamples", WARMUP);
        report.put("samples", SAMPLES);
        report.put("profile", PROFILE);
        report.put("monitorCachePrimedAndVerified", true);
        report.put(
            "notes",
            "Same real AE NetworkMonitor/NetworkInventoryHandler and native Universe media. Plain AE has no public storage wrapper or allocator; no cable/world tick, security host, listeners, telemetry or external inventories. Same JVM includes Futa mixins in every arm. Synthetic isolated timings, not a TPS or full-server comparison. Profile nbt uses tagged variants, profile plain uses metadata-distinct items and separately registered fluids without NBT. Requests are prebuilt, copied by actual engine as needed. A roundtrip is one extraction plus one matching insertion.");
        List<Map<String, Object>> scales = new ArrayList<>();
        report.put("scales", scales);
        String requested = System.getProperty("futa.storageScales", "1,63,1024,4096");
        for (String value : requested.split(",")) scales.add(scale(Integer.parseInt(value)));
        report.put("checks", checks);
        File output = new File("build/reports/storage-backend-benchmark.json");
        output.getParentFile()
            .mkdirs();
        String json = new GsonBuilder().setPrettyPrinting()
            .create()
            .toJson(report);
        Files.write(output.toPath(), json.getBytes(StandardCharsets.UTF_8));
        System.out.println("STORAGE_BACKEND_BENCHMARK_SUCCESS " + output.getAbsolutePath() + " checks=" + checks);
    }

    /** Same bytes and fully decoded media in all arms; only public arms build the resource-to-media index. */
    private static void loadingBenchmark() throws Exception {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put(
            "startedAtUtc",
            java.time.Instant.now()
                .toString());
        report.put("baseline", "v1.34.4 / 4c8370b3cd0b18b472185f9085b57ccadf7333ed");
        report.put("candidate", "1.34.5");
        report.put("profile", PROFILE);
        report.put("java", System.getProperty("java.runtime.version"));
        report.put("aeVersion", "rv3-beta-1082-GTNH");
        report.put("ae2fcVersion", "1.5.108-gtnh");
        report.put(
            "storageHelper",
            appeng.api.AEApi.instance()
                .storage()
                .getClass()
                .getName());
        require(
            appeng.api.AEApi.instance()
                .storage() instanceof appeng.core.api.ApiStorage,
            "real native storage helper");
        report.put(
            "notes",
            "Identical native-cell NBT/bytes per arm, fully materialized native contents before return. Frozen v1.34.4 source differs only by class name. Public arms include full counter validation, missing-mod quarantine and resource index; plain AE mounts the same actual CellInventory without public checks/index. gunzip case includes decompression; decoded case starts with already decoded immutable input NBT. No lazy loading shortcut, no disk/world tick/TPS measurement. All quantities and tags verified outside timed regions; arm order rotates. Native baseline forces the native decoder directly without constructing a second item list. Native-catalogue timing additionally enumerates all loaded stock in every arm. Compatibility-key caches are optional and built on first compatibility use; a separate first-full-snapshot case includes that work. First-pool-snapshot additionally measures the old facade's verbatim entrySet-to-list copying against the actual new native snapshot API, without metadata/GUI overhead. Service metadata and source backup validation are measured separately by the full backend benchmark.");
        int warmup = Integer.getInteger("futa.storageLoadWarmup", 20);
        int samples = Integer.getInteger("futa.storageLoadSamples", 60);
        report.put("warmupSamples", warmup);
        report.put("samples", samples);
        List<Map<String, Object>> scales = new ArrayList<>();
        report.put("scales", scales);
        for (String raw : System.getProperty("futa.storageScales", "1,63,1024,4096")
            .split(",")) {
            int size = Integer.parseInt(raw);
            ItemKey[] items = new ItemKey[size];
            FluidKey[] fluids = new FluidKey[size];
            for (int i = 0; i < size; i++) {
                NBTTagCompound tag = new NBTTagCompound();
                tag.setInteger("storageBenchType", i);
                ItemStack item = PROFILE.equals("plain") ? new ItemStack(metaItem, 1, i)
                    : new ItemStack(Items.iron_ingot);
                if (!PROFILE.equals("plain")) item.setTagCompound(tag);
                items[i] = ItemKey.of(item);
                fluids[i] = FluidKey.of(
                    PROFILE.equals("plain") ? new FluidStack(FluidRegistry.getFluid("futa_storage_bench_" + i), 1)
                        : new FluidStack(FluidRegistry.WATER, 1, (NBTTagCompound) tag.copy()));
            }
            NBTTagCompound root = seedCells(items, fluids);
            NBTTagCompound unchanged = (NBTTagCompound) root.copy();
            byte[] compressed = compress(root);
            String[] names = { "public_1344", "public_1345", "plain_native_ae" };
            IntToLongFunction[] decoded = { i -> loadedCount(root, 0), i -> loadedCount(root, 1),
                i -> loadedCount(root, 2) };
            IntToLongFunction[] gzip = { i -> loadedCount(decompress(compressed), 0),
                i -> loadedCount(decompress(compressed), 1), i -> loadedCount(decompress(compressed), 2) };
            Map<String, Object> scale = new LinkedHashMap<>();
            scales.add(scale);
            scale.put("typesPerChannel", size);
            scale.put("totalResources", size * 2);
            scale.put("compressedBytes", compressed.length);
            scale.put("decoded_mount", compare("decoded_mount", names, decoded, 1, warmup, samples));
            scale.put("gunzip_decode_mount", compare("gunzip_decode_mount", names, gzip, 1, warmup, samples));
            IntToLongFunction[] catalogue = { i -> loadedCatalogue(root, 0), i -> loadedCatalogue(root, 1),
                i -> loadedCatalogue(root, 2) };
            scale.put(
                "decoded_mount_native_catalogue",
                compare("decoded_mount_native_catalogue", names, catalogue, 1, warmup, samples));
            IntToLongFunction[] firstSnapshot = { i -> firstCompatibilitySnapshot(root, false),
                i -> firstCompatibilitySnapshot(root, true) };
            scale.put(
                "decoded_mount_first_compat_snapshot",
                compare(
                    "decoded_mount_first_compat_snapshot",
                    new String[] { names[0], names[1] },
                    firstSnapshot,
                    1,
                    warmup,
                    samples));
            IntToLongFunction[] poolSnapshot = { i -> firstPoolSnapshot(root, false),
                i -> firstPoolSnapshot(root, true) };
            scale.put(
                "decoded_mount_first_pool_snapshot",
                compare(
                    "decoded_mount_first_pool_snapshot",
                    new String[] { names[0], names[1] },
                    poolSnapshot,
                    1,
                    warmup,
                    samples));
            FrozenNativeMeCellArray1344 before = loadBefore(root);
            NativeMeCellArray after = array();
            after.readFromNBT(root);
            Map<ItemKey, Long> plainItems = new LinkedHashMap<>();
            Map<FluidKey, Long> plainFluids = new LinkedHashMap<>();
            for (IMEInventoryHandler handler : loadCells(root, false))
                for (Object value : handler.getAvailableItems(AEItemStackType.ITEM_STACK_TYPE.createList(), 0)) {
                    IAEItemStack stack = (IAEItemStack) value;
                    plainItems.merge(ItemKey.of(stack.getItemStack()), stack.getStackSize(), Math::addExact);
                }
            for (IMEInventoryHandler handler : loadCells(root, true))
                for (Object value : handler.getAvailableItems(AEFluidStackType.FLUID_STACK_TYPE.createList(), 0)) {
                    IAEFluidStack stack = (IAEFluidStack) value;
                    plainFluids.merge(FluidKey.of(stack.getFluidStack()), stack.getStackSize(), Math::addExact);
                }
            for (int i = 0; i < size; i++) {
                require(
                    before.itemView()
                        .get(items[i]) == QUANTITY
                        && before.fluidView()
                            .get(fluids[i]) == QUANTITY,
                    "frozen loaded quantities/tags");
                require(
                    after.itemView()
                        .get(items[i]) == QUANTITY
                        && after.fluidView()
                            .get(fluids[i]) == QUANTITY,
                    "optimized loaded quantities/tags");
                require(
                    plainItems.get(items[i]) == QUANTITY && plainFluids.get(fluids[i]) == QUANTITY,
                    "native loaded quantities/tags");
            }
            require(root.equals(unchanged), "all loaders retain original input NBT");
            require(
                after.itemView()
                    .size() == size
                    && after.fluidView()
                        .size() == size
                    && plainItems.size() == size
                    && plainFluids.size() == size,
                "no merged/lost/extra resources");
        }
        report.put("checks", checks);
        File output = new File("build/reports/storage-loading-benchmark.json");
        output.getParentFile()
            .mkdirs();
        Files.write(
            output.toPath(),
            new GsonBuilder().setPrettyPrinting()
                .create()
                .toJson(report)
                .getBytes(StandardCharsets.UTF_8));
        System.out.println("STORAGE_LOADING_BENCHMARK_SUCCESS " + output.getAbsolutePath() + " checks=" + checks);
    }

    private static FrozenNativeMeCellArray1344 loadBefore(NBTTagCompound root) {
        FrozenNativeMeCellArray1344 cells = new FrozenNativeMeCellArray1344(
            fluid -> new ItemStack(fluid ? fluidCell : itemCell));
        cells.readFromNBT(root);
        return cells;
    }

    @SuppressWarnings("rawtypes")
    private static long loadedCount(NBTTagCompound root, int arm) {
        if (arm == 0) {
            FrozenNativeMeCellArray1344 cells = loadBefore(root);
            return cells.itemView()
                .size()
                + cells.fluidView()
                    .size();
        }
        if (arm == 1) {
            NativeMeCellArray cells = array();
            cells.readFromNBT(root);
            return cells.itemView()
                .size()
                + cells.fluidView()
                    .size();
        }
        long count = 0;
        for (boolean fluids : new boolean[] { false, true }) for (IMEInventoryHandler handler : loadCells(root, fluids))
            count += ((ICellInventoryHandler) handler).getCellInv()
                .getStoredItemTypes();
        return count;
    }

    private static long firstCompatibilitySnapshot(NBTTagCompound root, boolean current) {
        if (current) {
            NativeMeCellArray cells = array();
            cells.readFromNBT(root);
            return cells.itemView()
                .entrySet()
                .size()
                + cells.fluidView()
                    .entrySet()
                    .size();
        }
        FrozenNativeMeCellArray1344 cells = loadBefore(root);
        return cells.itemView()
            .entrySet()
            .size()
            + cells.fluidView()
                .entrySet()
                .size();
    }

    private static <K> List<Map.Entry<K, Long>> oldPoolSnapshot(Map<K, Long> view) {
        List<Map.Entry<K, Long>> result = new ArrayList<>();
        for (Map.Entry<K, Long> row : view.entrySet())
            result.add(new java.util.AbstractMap.SimpleImmutableEntry<>(row));
        return Collections.unmodifiableList(result);
    }

    private static long firstPoolSnapshot(NBTTagCompound root, boolean current) {
        if (current) {
            NativeMeCellArray cells = array();
            cells.readFromNBT(root);
            return cells.snapshotItems()
                .size()
                + cells.snapshotFluids()
                    .size();
        }
        FrozenNativeMeCellArray1344 cells = loadBefore(root);
        return oldPoolSnapshot(cells.itemView()).size() + oldPoolSnapshot(cells.fluidView()).size();
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static long loadedCatalogue(NBTTagCompound root, int arm) {
        List<IMEInventoryHandler> items, fluids;
        if (arm == 0) {
            FrozenNativeMeCellArray1344 cells = loadBefore(root);
            cells.setPublished(true);
            items = cells.getCellArray(AEItemStackType.ITEM_STACK_TYPE);
            fluids = cells.getCellArray(AEFluidStackType.FLUID_STACK_TYPE);
        } else if (arm == 1) {
            NativeMeCellArray cells = array();
            cells.readFromNBT(root);
            cells.setPublished(true);
            items = cells.getCellArray(AEItemStackType.ITEM_STACK_TYPE);
            fluids = cells.getCellArray(AEFluidStackType.FLUID_STACK_TYPE);
        } else {
            items = loadCells(root, false);
            fluids = loadCells(root, true);
        }
        IItemList<IAEItemStack> a = AEItemStackType.ITEM_STACK_TYPE.createList();
        IItemList<IAEFluidStack> b = AEFluidStackType.FLUID_STACK_TYPE.createList();
        for (IMEInventoryHandler handler : items) handler.getAvailableItems(a, 0);
        for (IMEInventoryHandler handler : fluids) handler.getAvailableItems(b, 0);
        return a.size() + b.size();
    }

    private static Map<String, Object> scale(int size) throws Exception {
        System.out.println("STORAGE_BENCH_START types/channel=" + size);
        ItemKey[] items = new ItemKey[size];
        FluidKey[] fluids = new FluidKey[size];
        IAEItemStack[] itemRequests = new IAEItemStack[size];
        IAEFluidStack[] fluidRequests = new IAEFluidStack[size];
        LegacySharedStorage13310 legacy = new LegacySharedStorage13310();
        SharedStorage decoder = new SharedStorage();
        for (int i = 0; i < size; i++) {
            NBTTagCompound tag = null;
            if (size > 1 && PROFILE.equals("nbt")) {
                tag = new NBTTagCompound();
                tag.setInteger("storageBenchType", i);
                tag.setString("batch", "same-native-and-shared");
            }
            ItemStack item = new ItemStack(Items.iron_ingot);
            if (PROFILE.equals("plain")) item = new ItemStack(metaItem, 1, i);
            if (tag != null) item.setTagCompound(tag);
            items[i] = ItemKey.of(item);
            fluids[i] = FluidKey.of(
                new FluidStack(
                    PROFILE.equals("plain") ? FluidRegistry.getFluid("futa_storage_bench_" + i) : FluidRegistry.WATER,
                    1,
                    tag));
            itemRequests[i] = AEItemStack.create(item)
                .setStackSize(64);
            fluidRequests[i] = AEFluidStack.create(fluids[i].prototype())
                .setStackSize(1000);
            legacy.poolInsertItem(items[i], QUANTITY, true);
            legacy.poolInsertFluid(fluids[i], QUANTITY, true);
            decoder.poolInsertItem(items[i], QUANTITY, true);
            decoder.poolInsertFluid(fluids[i], QUANTITY, true);
        }
        NBTTagCompound nativeCells = seedCells(items, fluids);
        NBTTagCompound publicTag = new NBTTagCompound();
        publicTag.setInteger("version", NativeMeStorageData.VERSION);
        publicTag.setString("storageId", new UUID(7943, size).toString());
        publicTag.setTag("migration", new NBTTagCompound());
        publicTag.setTag("metadata", decoder.writeServiceMetadata());
        publicTag.setTag(
            "itemCells",
            nativeCells.getTag("itemCells")
                .copy());
        publicTag.setTag(
            "fluidCells",
            nativeCells.getTag("fluidCells")
                .copy());
        // The standalone decoder contains seed stock; the live native metadata must contain no second inventory.
        publicTag.getCompoundTag("metadata")
            .setTag("items", new NBTTagList());
        publicTag.getCompoundTag("metadata")
            .setTag("fluids", new NBTTagList());
        NativeMeStorageData data = loadPublic((NBTTagCompound) publicTag.copy());
        data.cells()
            .setPublished(true);
        FrozenNativeMeCellArray1341 before = new FrozenNativeMeCellArray1341(
            fluid -> new ItemStack(fluid ? fluidCell : itemCell));
        before.readFromNBT((NBTTagCompound) nativeCells.copy());
        SharedStorage beforeSettings = new SharedStorage();
        field(SharedStorage.class, "items").set(beforeSettings, before.itemView());
        field(SharedStorage.class, "fluids").set(beforeSettings, before.fluidView());
        before.attach(beforeSettings, data::markDirty);
        before.setPublished(true);
        LegacyPoolProviders13310 providers = new LegacyPoolProviders13310(legacy);
        Backend[] arms = { new Backend("legacy_shared_pool", providers.items(), providers.fluids()),
            new Backend(
                "public_native_ae_1341",
                before.getCellArray(AEItemStackType.ITEM_STACK_TYPE),
                before.getCellArray(AEFluidStackType.FLUID_STACK_TYPE)),
            new Backend(
                "public_native_ae",
                data.cells()
                    .getCellArray(AEItemStackType.ITEM_STACK_TYPE),
                data.cells()
                    .getCellArray(AEFluidStackType.FLUID_STACK_TYPE)),
            new Backend("plain_native_ae", loadCells(nativeCells, false), loadCells(nativeCells, true)) };
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("typesPerChannel", size);
        result.put("totalResources", size * 2);
        result.put(
            "publicCellCount",
            data.cells()
                .cellCount());
        result.put("plainCellCount", arms[3].item.getHandlerCount() + arms[3].fluid.getHandlerCount());
        List<Map<String, Object>> queries = new ArrayList<>();
        result.put("queries", queries);
        for (Backend arm : arms) checkContent(arm, itemRequests, fluidRequests);
        NBTTagCompound absentTag = new NBTTagCompound();
        absentTag.setInteger("storageBenchType", -1);
        ItemStack absentStack = PROFILE.equals("plain") ? new ItemStack(metaItem, 1, 32760)
            : new ItemStack(Items.iron_ingot);
        if (PROFILE.equals("nbt")) absentStack.setTagCompound(absentTag);
        IAEItemStack absentItem = AEItemStack.create(absentStack)
            .setStackSize(64);
        IAEFluidStack absentFluid = AEFluidStack
            .create(
                new FluidStack(
                    PROFILE.equals("plain") ? FluidRegistry.getFluid("futa_storage_bench_absent") : FluidRegistry.WATER,
                    1,
                    PROFILE.equals("plain") ? null : absentTag))
            .setStackSize(1000);
        queries.add(measure("monitor_cached_amount", arms, 64, b -> i -> {
            int n = i % size;
            return amount(
                b.item.monitor.getStorageList()
                    .findPrecise(itemRequests[n]))
                + amount(
                    b.fluid.monitor.getStorageList()
                        .findPrecise(fluidRequests[n]));
        }));
        queries.add(measure("precise_provider_amount", arms, 16, b -> i -> {
            int n = i % size;
            return amount(b.item.inventory.getAvailableItem(itemRequests[n], IterationCounter.fetchNewId()))
                + amount(b.fluid.inventory.getAvailableItem(fluidRequests[n], IterationCounter.fetchNewId()));
        }));
        queries.add(
            measure(
                "simulate_item_extract",
                arms,
                8,
                b -> i -> amount(b.item.monitor.extractItems(itemRequests[i % size], Actionable.SIMULATE, SOURCE))));
        queries.add(
            measure(
                "simulate_fluid_extract",
                arms,
                8,
                b -> i -> amount(b.fluid.monitor.extractItems(fluidRequests[i % size], Actionable.SIMULATE, SOURCE))));
        queries.add(measure("simulate_item_insert", arms, 8, b -> i -> {
            IAEItemStack rest = b.item.monitor.injectItems(itemRequests[i % size], Actionable.SIMULATE, SOURCE);
            return rest == null ? 64 : 64 - rest.getStackSize();
        }));
        queries.add(measure("simulate_fluid_insert", arms, 8, b -> i -> {
            IAEFluidStack rest = b.fluid.monitor.injectItems(fluidRequests[i % size], Actionable.SIMULATE, SOURCE);
            return rest == null ? 1000 : 1000 - rest.getStackSize();
        }));
        queries.add(measure("missing_item_extract", arms, 1, b -> i -> {
            IAEItemStack out = b.item.monitor.extractItems(absentItem, Actionable.MODULATE, SOURCE);
            if (out != null) throw new AssertionError("Missing resource returned inventory");
            return 0;
        }));
        queries.add(measure("missing_fluid_extract", arms, 1, b -> i -> {
            IAEFluidStack out = b.fluid.monitor.extractItems(absentFluid, Actionable.MODULATE, SOURCE);
            if (out != null) throw new AssertionError("Missing fluid returned inventory");
            return 0;
        }));
        queries.add(measure("item_roundtrip_first", arms, 1, b -> i -> roundtrip(b.item.monitor, itemRequests[0])));
        queries
            .add(measure("item_roundtrip_last", arms, 1, b -> i -> roundtrip(b.item.monitor, itemRequests[size - 1])));
        queries.add(measure("fluid_roundtrip_first", arms, 1, b -> i -> roundtrip(b.fluid.monitor, fluidRequests[0])));
        queries.add(
            measure("fluid_roundtrip_last", arms, 1, b -> i -> roundtrip(b.fluid.monitor, fluidRequests[size - 1])));
        queries.add(measure("mixed_roundtrip_random", arms, 1, b -> i -> {
            int n = (i * 31) % size;
            return roundtrip(b.item.monitor, itemRequests[n]) + roundtrip(b.fluid.monitor, fluidRequests[n]);
        }));
        queries.add(measure("enumerate_both_channels", arms, 1, b -> i -> enumerate(b.item) + enumerate(b.fluid)));
        for (Backend arm : arms) checkContent(arm, itemRequests, fluidRequests);
        result.put("persistence", persistence(size, legacy, data, arms[3], items, fluids));
        result.put("migration", migration(size, decoder, items, fluids));
        result.put("compatibilityPool", compatibility(legacy, data.access(), items, fluids));
        for (Backend arm : arms) checkContent(arm, itemRequests, fluidRequests);
        System.out.println("STORAGE_BENCH_DONE types/channel=" + size);
        return result;
    }

    private static long amount(IAEStack<?> stack) {
        return stack == null ? 0 : stack.getStackSize();
    }

    private static <T extends IAEStack<T>> long roundtrip(NetworkMonitor<T> monitor, T request) {
        T taken = monitor.extractItems(request, Actionable.MODULATE, SOURCE);
        if (taken == null || taken.getStackSize() != request.getStackSize())
            throw new AssertionError("Incomplete benchmark extraction");
        T rest = monitor.injectItems(taken, Actionable.MODULATE, SOURCE);
        if (rest != null && rest.getStackSize() > 0) throw new AssertionError("Incomplete benchmark return");
        return taken.getStackSize();
    }

    private static <T extends IAEStack<T>> long enumerate(Channel<T> channel) {
        long count = 0;
        for (T row : channel.inventory.getAvailableItems(channel.type.createList(), IterationCounter.fetchNewId()))
            count += row.getStackSize();
        return count;
    }

    private static void checkContent(Backend arm, IAEItemStack[] items, IAEFluidStack[] fluids) {
        IItemList<IAEItemStack> a = arm.item.inventory
            .getAvailableItems(AEItemStackType.ITEM_STACK_TYPE.createList(), IterationCounter.fetchNewId());
        IItemList<IAEFluidStack> b = arm.fluid.inventory
            .getAvailableItems(AEFluidStackType.FLUID_STACK_TYPE.createList(), IterationCounter.fetchNewId());
        require(
            a.size() == items.length && b.size() == fluids.length,
            "type cardinality " + arm.name
                + " item="
                + a.size()
                + " fluid="
                + b.size()
                + " expected="
                + items.length
                + " available="
                + enumerate(arm.item)
                + "/"
                + enumerate(arm.fluid));
        for (int i = 0; i < items.length; i++) {
            require(amount(a.findPrecise(items[i])) == QUANTITY, "item/NBT conservation " + arm.name + "/" + i);
            require(amount(b.findPrecise(fluids[i])) == QUANTITY, "fluid/NBT conservation " + arm.name + "/" + i);
            require(
                amount(
                    arm.item.monitor.getStorageList()
                        .findPrecise(items[i]))
                    == QUANTITY,
                "cached item lookup must be a real hit " + arm.name);
            require(
                amount(
                    arm.fluid.monitor.getStorageList()
                        .findPrecise(fluids[i]))
                    == QUANTITY,
                "cached fluid lookup must be a real hit " + arm.name);
        }
    }

    private static NBTTagCompound seedCells(ItemKey[] items, FluidKey[] fluids) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList a = new NBTTagList(), b = new NBTTagList();
        for (int start = 0; start < items.length; start += 63) {
            ItemStack item = new ItemStack(itemCell), fluid = new ItemStack(fluidCell);
            IMEInventoryHandler<IAEItemStack> ai = cell(item, AEItemStackType.ITEM_STACK_TYPE);
            IMEInventoryHandler<IAEFluidStack> bf = cell(fluid, AEFluidStackType.FLUID_STACK_TYPE);
            for (int i = start; i < Math.min(start + 63, items.length); i++) {
                require(
                    ai.injectItems(
                        AEItemStack.create(items[i].prototype())
                            .setStackSize(QUANTITY),
                        Actionable.MODULATE,
                        SOURCE) == null,
                    "native seed accepts item");
                require(
                    bf.injectItems(
                        AEFluidStack.create(fluids[i].prototype())
                            .setStackSize(QUANTITY),
                        Actionable.MODULATE,
                        SOURCE) == null,
                    "native seed accepts fluid");
            }
            a.appendTag(item.writeToNBT(new NBTTagCompound()));
            b.appendTag(fluid.writeToNBT(new NBTTagCompound()));
        }
        root.setTag("itemCells", a);
        root.setTag("fluidCells", b);
        return root;
    }

    @SuppressWarnings("unchecked")
    private static <T extends IAEStack<T>> IMEInventoryHandler<T> cell(ItemStack media, IAEStackType<T> type) {
        return (IMEInventoryHandler<T>) CellInventory.getCell(media, null, type);
    }

    private static NativeMeCellArray array() {
        return new NativeMeCellArray(fluid -> new ItemStack(fluid ? fluidCell : itemCell));
    }

    private static NativeMeStorageData loadPublic(NBTTagCompound root) {
        NativeMeStorageData data = new NativeMeStorageData(NativeMeStorageData.NAME, array());
        data.readFromNBT(root);
        return data;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static List<IMEInventoryHandler> loadCells(NBTTagCompound root, boolean fluids) {
        NBTTagList tags = root.getTagList(fluids ? "fluidCells" : "itemCells", 10);
        List<IMEInventoryHandler> result = new ArrayList<>();
        for (int i = 0; i < tags.tagCount(); i++) {
            ItemStack media = ItemStack.loadItemStackFromNBT(tags.getCompoundTagAt(i));
            IMEInventoryHandler inventory = CellInventory.getCell(
                media,
                new PlainSaveProvider(),
                fluids ? AEFluidStackType.FLUID_STACK_TYPE : AEItemStackType.ITEM_STACK_TYPE);
            Object nativeInventory = ((ICellInventoryHandler<?>) inventory).getCellInv();
            if (!(nativeInventory instanceof MixinAeCellContents))
                throw new AssertionError("native decoder bridge missing");
            ((MixinAeCellContents) nativeInventory).futa$loadedStacks();
            result.add(inventory);
        }
        return result;
    }

    private static final class Backend {

        final String name;
        final Channel<IAEItemStack> item;
        final Channel<IAEFluidStack> fluid;

        Backend(String name, IMEInventoryHandler<IAEItemStack> item, IMEInventoryHandler<IAEFluidStack> fluid)
            throws Exception {
            this(name, Collections.singletonList(item), Collections.singletonList(fluid));
        }

        @SuppressWarnings("rawtypes")
        Backend(String name, List<? extends IMEInventoryHandler> items, List<? extends IMEInventoryHandler> fluids)
            throws Exception {
            this.name = name;
            GridStorageCache[] cache = { null };
            ItemFlowGridCache flow = new ItemFlowGridCache(null);
            IGrid grid = (IGrid) Proxy
                .newProxyInstance(IGrid.class.getClassLoader(), new Class<?>[] { IGrid.class }, (p, m, a) -> {
                    if (m.getName()
                        .equals("getCache") && a[0] == IStorageGrid.class) return cache[0];
                    if (m.getName()
                        .equals("getCache") && a[0] == ItemFlowGridCache.class) return flow;
                    if (m.getName()
                        .equals("hashCode")) return System.identityHashCode(p);
                    if (m.getName()
                        .equals("equals")) return p == a[0];
                    if (m.getReturnType() == boolean.class) return false;
                    if (m.getReturnType() == int.class) return 0;
                    if (m.getReturnType() == long.class) return 0L;
                    return null;
                });
            cache[0] = new GridStorageCache(grid);
            item = new Channel<>(AEItemStackType.ITEM_STACK_TYPE, grid, cache[0], items);
            fluid = new Channel<>(AEFluidStackType.FLUID_STACK_TYPE, grid, cache[0], fluids);
        }
    }

    private static final class PlainSaveProvider implements ISaveProvider {

        boolean dirty;

        @Override
        public void saveChanges(IMEInventory inventory) {
            dirty = true;
        }
    }

    private static final class Channel<T extends IAEStack<T>> {

        final IAEStackType<T> type;
        final NetworkInventoryHandler<T> inventory;
        final NetworkMonitor<T> monitor;

        @SuppressWarnings({ "rawtypes", "unchecked" })
        Channel(IAEStackType<T> type, IGrid grid, GridStorageCache cache, List<? extends IMEInventoryHandler> handlers)
            throws Exception {
            this.type = type;
            inventory = new NetworkInventoryHandler<>(type, new SecurityCache(grid));
            for (IMEInventoryHandler handler : handlers) inventory.addNewStorage(handler);
            monitor = new NetworkMonitor<>(cache, type);
            ((Map) field(GridStorageCache.class, "inventoryHandlers").get(cache)).put(type, inventory);
            ((Map) field(GridStorageCache.class, "monitors").get(cache)).put(type, monitor);
            // Mirror the cell-array registration notification normally sent by GridStorageCache.
            java.lang.reflect.Method update = NetworkMonitor.class.getDeclaredMethod("forceUpdate");
            update.setAccessible(true);
            update.invoke(monitor);
        }

        int getHandlerCount() {
            return inventory.getHandlers()
                .size();
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Map<String, Object> measure(String name, Backend[] arms, int batch,
        java.util.function.Function<Backend, IntToLongFunction> factory) {
        String[] labels = new String[arms.length];
        IntToLongFunction[] ops = new IntToLongFunction[arms.length];
        for (int i = 0; i < arms.length; i++) {
            labels[i] = arms[i].name;
            ops[i] = factory.apply(arms[i]);
        }
        return compare(name, labels, ops, batch, WARMUP, SAMPLES);
    }

    private static Map<String, Object> compare(String name, String[] labels, IntToLongFunction[] ops, int batch,
        int warmup, int samples) {
        for (int i = 0; i < warmup; i++)
            for (int a = 0; a < ops.length; a++) invoke(ops[(a + i) % ops.length], i * batch, batch);
        long[][] times = new long[ops.length][samples];
        long[] bytes = new long[ops.length];
        long gc = gcCount();
        for (int i = 0; i < samples; i++) for (int j = 0; j < ops.length; j++) {
            int a = (i + j) % ops.length;
            long before = allocated(), start = System.nanoTime();
            invoke(ops[a], i * batch, batch);
            times[a][i] = System.nanoTime() - start;
            bytes[a] += allocations == null ? 0 : allocated() - before;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", name);
        result.put("batch", batch);
        result.put("samples", samples);
        result.put("gcCollectionsAllArms", gcCount() - gc);
        for (int a = 0; a < ops.length; a++) {
            Arrays.sort(times[a]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("p50Us", times[a][samples / 2] / (1000d * batch));
            row.put("p95Us", times[a][(int) (samples * .95)] / (1000d * batch));
            row.put("p99Us", times[a][(int) (samples * .99)] / (1000d * batch));
            row.put("maxUs", times[a][samples - 1] / (1000d * batch));
            row.put("allocatedBytesPerOp", allocations == null ? null : bytes[a] / (double) (samples * batch));
            result.put(labels[a], row);
        }
        System.out.println(
            "STORAGE_BENCH_CASE " + name
                + " "
                + new GsonBuilder().create()
                    .toJson(result));
        return result;
    }

    private static void invoke(IntToLongFunction op, int start, int batch) {
        long n = 0;
        for (int i = 0; i < batch; i++) n ^= op.applyAsLong(start + i);
        blackhole ^= n;
    }

    private static long allocated() {
        return allocations == null ? 0
            : allocations.getThreadAllocatedBytes(
                Thread.currentThread()
                    .getId());
    }

    private static long gcCount() {
        long n = 0;
        for (java.lang.management.GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans())
            if (bean.getCollectionCount() > 0) n += bean.getCollectionCount();
        return n;
    }

    private static NBTTagCompound writePlain(Backend backend) {
        NBTTagCompound root = new NBTTagCompound();
        NBTTagList a = new NBTTagList(), b = new NBTTagList();
        for (IMEInventoryHandler<?> handler : backend.item.inventory.getHandlers()) a.appendTag(
            ((ICellInventoryHandler<?>) handler).getCellInv()
                .getItemStack()
                .writeToNBT(new NBTTagCompound()));
        for (IMEInventoryHandler<?> handler : backend.fluid.inventory.getHandlers()) b.appendTag(
            ((ICellInventoryHandler<?>) handler).getCellInv()
                .getItemStack()
                .writeToNBT(new NBTTagCompound()));
        root.setTag("itemCells", a);
        root.setTag("fluidCells", b);
        return root;
    }

    private static Map<String, Object> persistence(int size, LegacySharedStorage13310 legacy, NativeMeStorageData data,
        Backend plainBackend, ItemKey[] items, FluidKey[] fluids) throws Exception {
        NBTTagCompound plain = writePlain(plainBackend);
        NBTTagCompound old = legacy.writeToNbt(), current = new NBTTagCompound();
        data.writeToNBT(current);
        byte[][] compressed = { compress(old), compress(current), compress(plain) };
        Map<String, Object> result = new LinkedHashMap<>();
        String[] labels = { "legacy_shared_pool", "public_native_ae", "plain_native_ae" };
        List<Map<String, Object>> operations = new ArrayList<>();
        result.put("operations", operations);
        for (int i = 0; i < labels.length; i++) result.put(labels[i] + "CompressedBytes", compressed[i].length);
        IntToLongFunction[] save = { i -> compress(legacy.writeToNbt()).length, i -> {
            NBTTagCompound n = new NBTTagCompound();
            data.writeToNBT(n);
            return compress(n).length;
        }, i -> compress(writePlain(plainBackend)).length };
        operations.add(compare("serialize_gzip", labels, save, 1, 12, 50));
        IntToLongFunction[] load = { i -> {
            LegacySharedStorage13310 n = new LegacySharedStorage13310();
            n.readFromNbt(decompress(compressed[0]));
            return n.getItemAmountPool(items[size - 1]) + n.getFluidAmountPool(fluids[size - 1]);
        }, i -> {
            NativeMeStorageData n = loadPublic(decompress(compressed[1]));
            return n.access()
                .getItemAmountPool(items[size - 1])
                + n.access()
                    .getFluidAmountPool(fluids[size - 1]);
        }, i -> {
            NBTTagCompound n = decompress(compressed[2]);
            return loadCells(n, false).size() + loadCells(n, true).size();
        } };
        operations.add(compare("gunzip_decode_mount", labels, load, 1, 12, 50));
        File directory = artifactDirectory(size);
        directory.mkdirs();
        for (int a = 0; a < labels.length; a++) {
            File target = new File(directory, labels[a] + ".dat");
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(compressed[a]);
            }
            NBTTagCompound restored;
            try (FileInputStream in = new FileInputStream(target)) {
                restored = CompressedStreamTools.readCompressed(in);
            }
            require(
                restored.equals(a == 0 ? old : a == 1 ? current : plain),
                "persisted compressed bytes/NBT roundtrip " + labels[a]);
        }
        NativeMeStorageData loaded = loadPublic(decompress(compressed[1]));
        for (int i = 0; i < size; i++) require(
            loaded.access()
                .getItemAmountPool(items[i]) == QUANTITY
                && loaded.access()
                    .getFluidAmountPool(fluids[i]) == QUANTITY,
            "public remount exact quantities and tags");
        return result;
    }

    private static Map<String, Object> migration(int size, SharedStorage decoder, ItemKey[] items, FluidKey[] fluids)
        throws Exception {
        NBTTagCompound stock = decoder.writeToNbt();
        long before = allocated(), start = System.nanoTime();
        SharedStorage input = new SharedStorage();
        input.readFromNbt(stock);
        NativeMeStorageData migrated = new NativeMeStorageData(NativeMeStorageData.NAME, array());
        migrated.initialize(new UUID(7943, size), input, new NBTTagCompound());
        long elapsed = System.nanoTime() - start, bytes = allocated() - before;
        for (int i = 0; i < size; i++) require(
            migrated.access()
                .getItemAmountPool(items[i]) == QUANTITY
                && migrated.access()
                    .getFluidAmountPool(fluids[i]) == QUANTITY,
            "migration engine quantity/NBT");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("engineDecodeAndConvertMs", elapsed / 1e6);
        result.put("allocatedBytes", bytes);
        result.put("samples", 1);
        result.put(
            "notes",
            "Separate one-shot measurements of in-memory conversion and real on-disk NativeMeMigration.load (backup/hash/fsync/remount verification/atomic ownership commit). Startup validates the original backup before native load. Under build/ only; these are not warm percentile measurements.");
        File parent = new File("build/storage-backend-benchmark/migrations");
        parent.mkdirs();
        File world = Files.createTempDirectory(parent.toPath(), "types-" + size + "-")
            .toFile();
        File source = SharedStorageFile.fileFor(world);
        source.getParentFile()
            .mkdirs();
        try (FileOutputStream out = new FileOutputStream(source)) {
            CompressedStreamTools.writeCompressed(stock, out);
        }
        start = System.nanoTime();
        NativeMeStorageData full = NativeMeMigration.load(world, StorageBackendBenchmark::array);
        result.put("completeMigrationMs", (System.nanoTime() - start) / 1e6);
        start = System.nanoTime();
        NativeMeStorageData restart = NativeMeMigration.load(world, StorageBackendBenchmark::array);
        result.put("validatedNativeStartupMs", (System.nanoTime() - start) / 1e6);
        for (int i = 0; i < size; i++) require(
            full.access()
                .getItemAmountPool(items[i]) == QUANTITY
                && full.access()
                    .getFluidAmountPool(fluids[i]) == QUANTITY
                && restart.access()
                    .getItemAmountPool(items[i]) == QUANTITY
                && restart.access()
                    .getFluidAmountPool(fluids[i]) == QUANTITY,
            "on-disk migration and validated restart conserve all stock/NBT");
        return result;
    }

    private static Map<String, Object> compatibility(LegacySharedStorage13310 old, SharedStorage current,
        ItemKey[] items, FluidKey[] fluids) {
        String[] labels = { "legacy_shared_pool", "public_native_ae" };
        int size = items.length;
        IntToLongFunction[] ops = { i -> {
            int n = (i * 31) % size;
            old.poolExtractItem(items[n], 64);
            old.poolInsertItem(items[n], 64, true);
            old.poolExtractFluid(fluids[n], 1000);
            old.poolInsertFluid(fluids[n], 1000, true);
            return 1064;
        }, i -> {
            int n = (i * 31) % size;
            current.poolExtractItem(items[n], 64);
            current.poolInsertItem(items[n], 64, true);
            current.poolExtractFluid(fluids[n], 1000);
            current.poolInsertFluid(fluids[n], 1000, true);
            return 1064;
        } };
        return compare("direct_pool_recovery_roundtrip_both_channels", labels, ops, 1, 30, 60);
    }

    private static byte[] compress(NBTTagCompound tag) {
        try {
            return CompressedStreamTools.compress(tag);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Independently remount both native arms from the measured on-disk artifacts and compare every key and amount. */
    private static void verifySavedArtifacts() throws Exception {
        for (String scale : System.getProperty("futa.storageScales", "1,63,1024,4096")
            .split(",")) {
            File root = artifactDirectory(Integer.parseInt(scale));
            LegacySharedStorage13310 old = new LegacySharedStorage13310();
            old.readFromNbt(readArtifact(new File(root, "legacy_shared_pool.dat")));
            NativeMeStorageData current = loadPublic(readArtifact(new File(root, "public_native_ae.dat")));
            NBTTagCompound plain = readArtifact(new File(root, "plain_native_ae.dat"));
            Map<ItemKey, Long> expectedItems = new LinkedHashMap<>(), actualItems = new LinkedHashMap<>();
            Map<FluidKey, Long> expectedFluids = new LinkedHashMap<>(), actualFluids = new LinkedHashMap<>();
            for (Map.Entry<ItemKey, Long> row : old.snapshotPoolItems())
                expectedItems.put(row.getKey(), row.getValue());
            for (Map.Entry<FluidKey, Long> row : old.snapshotPoolFluids())
                expectedFluids.put(row.getKey(), row.getValue());
            for (IMEInventoryHandler handler : loadCells(plain, false)) {
                IItemList<IAEItemStack> content = handler
                    .getAvailableItems(AEItemStackType.ITEM_STACK_TYPE.createList(), 0);
                for (IAEItemStack row : content)
                    actualItems.merge(ItemKey.of(row.getItemStack()), row.getStackSize(), Math::addExact);
            }
            for (IMEInventoryHandler handler : loadCells(plain, true)) {
                IItemList<IAEFluidStack> content = handler
                    .getAvailableItems(AEFluidStackType.FLUID_STACK_TYPE.createList(), 0);
                for (IAEFluidStack row : content)
                    actualFluids.merge(FluidKey.of(row.getFluidStack()), row.getStackSize(), Math::addExact);
            }
            require(
                expectedItems.equals(
                    current.cells()
                        .itemView()),
                "saved public AE items/NBT match legacy");
            require(
                expectedFluids.equals(
                    current.cells()
                        .fluidView()),
                "saved public AE fluids/NBT match legacy");
            require(expectedItems.equals(actualItems), "saved ordinary AE items/NBT match legacy");
            require(expectedFluids.equals(actualFluids), "saved ordinary AE fluids/NBT match legacy");
            LegacyPoolProviders13310 providers = new LegacyPoolProviders13310(old);
            current.cells()
                .setPublished(true);
            Backend[] restored = {
                new Backend("legacy", providers.items(), providers.fluids()), new Backend(
                    "public",
                    current.cells()
                        .getCellArray(AEItemStackType.ITEM_STACK_TYPE),
                    current.cells()
                        .getCellArray(AEFluidStackType.FLUID_STACK_TYPE)),
                new Backend("plain", loadCells(plain, false), loadCells(plain, true)) };
            for (Backend backend : restored) {
                for (Map.Entry<ItemKey, Long> row : expectedItems.entrySet()) require(
                    amount(
                        backend.item.monitor.getStorageList()
                            .findPrecise(
                                AEItemStack.create(
                                    row.getKey()
                                        .prototype())))
                        == row.getValue(),
                    "cached monitor amount after item remount " + backend.name);
                for (Map.Entry<FluidKey, Long> row : expectedFluids.entrySet()) require(
                    amount(
                        backend.fluid.monitor.getStorageList()
                            .findPrecise(
                                AEFluidStack.create(
                                    row.getKey()
                                        .prototype())))
                        == row.getValue(),
                    "cached monitor amount after fluid remount " + backend.name);
            }
        }
        System.out.println(
            "STORAGE_BACKEND_ARTIFACTS_VERIFIED " + checks
                + " checks passed (complete stock maps and per-key cached lookups after real native remount)");
    }

    private static NBTTagCompound readArtifact(File source) throws Exception {
        try (FileInputStream input = new FileInputStream(source)) {
            return CompressedStreamTools.readCompressed(input);
        }
    }

    private static File artifactDirectory(int size) {
        return new File("build/storage-backend-benchmark/" + (PROFILE.equals("plain") ? "plain/" : "") + size);
    }

    private static NBTTagCompound decompress(byte[] data) {
        try {
            return CompressedStreamTools.readCompressed(new ByteArrayInputStream(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(boolean okay, String reason) {
        if (!okay) throw new AssertionError(reason);
        checks++;
    }
}
