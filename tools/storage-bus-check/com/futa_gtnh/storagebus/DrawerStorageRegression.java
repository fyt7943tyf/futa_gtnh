package com.futa_gtnh.storagebus;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.block.TerminalIoConfig;
import com.jaquadro.minecraft.storagedrawers.StorageDrawers;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer;
import com.jaquadro.minecraft.storagedrawers.block.tile.TileEntityDrawersStandard;
import com.jaquadro.minecraft.storagedrawers.config.ConfigManager;
import com.jaquadro.minecraft.storagedrawers.config.OreDictRegistry;
import com.jaquadro.minecraft.storagedrawers.storage.DefaultStorageProvider;
import com.jaquadro.minecraft.storagedrawers.storage.IStorageProvider;

/** Uses the real Storage Drawers tile, DrawerData and virtual InventoryStack implementation. */
public final class DrawerStorageRegression {

    private static final ForgeDirection EAST = ForgeDirection.EAST;
    private static final ForgeDirection WEST = ForgeDirection.WEST;
    private static int checks;

    private DrawerStorageRegression() {}

    public static void run() throws Exception {
        File root = new File("build/storage-bus-check/drawers");
        root.mkdirs();
        StorageDrawers.config = new ConfigManager(new File(root, "StorageDrawers.cfg"));
        StorageDrawers.oreDictRegistry = new OreDictRegistry();
        checkReported80();
        checkTransfers();
        checkMultipleDrawers();
        checkSidedRules();
        checkActiveIo();
        checkRepeatedTransfers();
        checkLargeDrawerBatch();
        checkNativeSimulation();
        System.out.println("Storage Drawers regression passed: " + checks + " checks");
    }

    private static void checkReported80() {
        Fixture f = new Fixture(1);
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        f.drawer.getDrawer(0).setStoredItem(iron.prototype(), 208);
        int virtual = 0;
        for (int slot = 0; slot < f.drawer.getSizeInventory(); slot++) {
            ItemStack stack = f.drawer.getStackInSlot(slot);
            if (stack != null) virtual += stack.stackSize;
        }
        check(virtual == 80, "real drawer reproduces 208 stored items exposed as 80 virtual-window items");
        StorageBusRegistry.refreshAll();
        check(f.storage.getItemAmount(iron) == 208 && f.storage.getItemTotal() == 208,
            "shared terminal and totals use all 208 physical items");
        check(f.storage.snapshotItemsCached().get(0).getValue() == 208L,
            "terminal/AE snapshots expose the full physical count");
        TileEntityStorageBus alias = new TileEntityStorageBus();
        alias.xCoord = 2;
        alias.setWorldObj(f.world);
        alias.addFace(WEST);
        check(f.storage.getItemAmount(iron) == 208, "two buses on the same drawer still count once");
        f.bus.getConfig(EAST).setItemFilter(0, iron);
        f.bus.getConfig(EAST).setFilterMode(StorageBusConfig.FilterMode.BLACKLIST);
        StorageBusRegistry.refresh(f.bus.getLink(EAST));
        alias.invalidate();
        check(f.storage.getItemAmount(iron) == 0 && f.drawer.getDrawer(0).getStoredItemCount() == 208,
            "drawer adapter still obeys filters without changing physical stock");
    }

    private static void checkNativeSimulation() {
        Fixture f = new Fixture(1);
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        f.drawer.getDrawer(0).setStoredItem(iron.prototype(), 208);
        check(ExternalInventoryOps.simulateExtractItems(f.drawer, WEST, iron, 200) == 200,
            "ME simulation reads drawer physical quantity beyond its output window");
        check(ExternalInventoryOps.simulateInsertItems(f.drawer, WEST, iron, 200) == 48,
            "ME simulation reads remaining physical capacity rather than virtual input slots");
        check(f.drawer.getDrawer(0).getStoredItemCount() == 208,
            "drawer insertion and extraction simulations never change physical stock");
        try {
            StorageDrawers.config.cache.enableSidedOutput = false;
            check(ExternalInventoryOps.simulateExtractItems(f.drawer, WEST, iron, 10) == 0,
                "ME extraction simulation respects disabled sided drawer output");
            StorageDrawers.config.cache.enableSidedInput = false;
            check(ExternalInventoryOps.simulateInsertItems(f.drawer, WEST, iron, 10) == 0,
                "ME insertion simulation respects disabled sided drawer input");
        } finally {
            StorageDrawers.config.cache.enableSidedInput = true;
            StorageDrawers.config.cache.enableSidedOutput = true;
        }
    }

    private static void checkTransfers() {
        Fixture f = new Fixture(1);
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        IDrawer drawer = f.drawer.getDrawer(0);
        drawer.setStoredItem(iron.prototype(), 208);
        StorageBusRegistry.refreshAll();
        check(f.storage.extractItem(iron, 64) == 64 && drawer.getStoredItemCount() == 144,
            "a replenished 64-item output window is charged against physical count");
        check(f.storage.getItemAmount(iron) == 144, "withdrawal refreshes the full mirrored count");
        check(f.storage.insertItemManual(iron, 64) == 64 && drawer.getStoredItemCount() == 208,
            "insertion works even when the virtual input window becomes full and refills");
        check(f.storage.extractItem(iron, 208) == 208 && drawer.getStoredItemCount() == 0,
            "one request can withdraw beyond a single virtual output stack");
        check(f.storage.insertItemManual(iron, 500) == 500 && drawer.getStoredItemCount() == 256
            && f.storage.getItemAmountPool(iron) == 244 && f.storage.getItemAmount(iron) == 500,
            "drawer capacity fills first and only the actual excess enters the pool");
        check(f.storage.extractItem(iron, 500) == 500 && f.storage.getItemAmount(iron) == 0
            && drawer.getStoredItemCount() == 0, "mixed drawer/pool withdrawal conserves every item");
        f.storage.setItemLimit(iron, 220);
        drawer.setStoredItem(iron.prototype(), 208);
        StorageBusRegistry.refreshAll();
        check(f.storage.insertItem(iron, 64) == 12 && drawer.getStoredItemCount() == 220,
            "automation limits use full physical stock rather than virtual windows");
        f.storage.clearItemLimit(iron);
    }

    private static void checkMultipleDrawers() {
        Fixture f = new Fixture(4);
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
        ItemStack tagged = iron.prototype();
        tagged.setTagCompound(new NBTTagCompound());
        tagged.getTagCompound().setString("grade", "pure");
        ItemKey pure = ItemKey.of(tagged);
        f.drawer.getDrawer(0).setStoredItem(iron.prototype(), 108);
        f.drawer.getDrawer(1).setStoredItem(iron.prototype(), 100);
        f.drawer.getDrawer(2).setStoredItem(gold.prototype(), 200);
        f.drawer.getDrawer(3).setStoredItem(pure.prototype(), 150);
        StorageBusRegistry.refreshAll();
        check(f.storage.getItemAmount(iron) == 208 && f.storage.getItemAmount(gold) == 200
            && f.storage.getItemAmount(pure) == 150, "2x2 drawer totals merge identical keys and isolate NBT");
        check(f.storage.extractItem(iron, 208) == 208 && f.storage.getItemAmount(pure) == 150,
            "multi-drawer withdrawal does not consume a different NBT variant");
        check(f.storage.extractItem(pure, 100) == 100 && f.drawer.getDrawer(3).getStoredItemCount() == 50,
            "tagged drawer extraction uses the true count");
        f.bus.getConfig(EAST).setAccess(StorageBusConfig.AccessMode.WRITE_ONLY);
        StorageBusRegistry.refreshAll();
        f.storage.setItemLimit(gold, 200);
        check(f.storage.getItemAmount(gold) == 0 && f.storage.itemSpaceLeft(gold) == 0,
            "write-only drawers stay hidden but their full stock enforces limits");
        f.storage.clearItemLimit(gold);
    }

    private static void checkSidedRules() {
        Fixture f = new Fixture(1);
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        f.drawer.getDrawer(0).setStoredItem(iron.prototype(), 208);
        try {
            StorageDrawers.config.cache.enableSidedOutput = false;
            check(ExternalInventoryOps.extractItems(f.drawer, WEST, iron, 64) == 0
                && f.drawer.getDrawer(0).getStoredItemCount() == 208, "disabled drawer output remains disabled");
            StorageDrawers.config.cache.enableSidedInput = false;
            check(ExternalInventoryOps.insertItems(f.drawer, WEST, iron, 20) == 0
                && f.drawer.getDrawer(0).getStoredItemCount() == 208, "disabled drawer input remains disabled");
        } finally {
            StorageDrawers.config.cache.enableSidedInput = true;
            StorageDrawers.config.cache.enableSidedOutput = true;
        }
    }

    private static void checkActiveIo() throws Exception {
        Fixture f = new Fixture(1);
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        f.drawer.getDrawer(0).setStoredItem(iron.prototype(), 208);
        StorageBusRegistry.refreshAll();
        Class<?> engine = Class.forName("com.futa_gtnh.block.TerminalIoEngine");
        Method pull = engine.getDeclaredMethod("pullItems", net.minecraft.inventory.IInventory.class,
            ForgeDirection.class, TerminalIoConfig.class);
        pull.setAccessible(true);
        TerminalIoConfig config = new TerminalIoConfig();
        f.storage.setItemLimit(iron, 0);
        pull.invoke(null, f.drawer, WEST, config);
        check(f.drawer.getDrawer(0).getStoredItemCount() == 192 && f.storage.getItemAmountPool(iron) == 16
            && f.storage.getItemAmount(iron) == 208, "active IO moves counted drawer stock into the pool without loss");
        f.storage.clearItemLimit(iron);
        f.drawer.getDrawer(0).setStoredItem(null, 0);
        StorageBusRegistry.refreshAll();
        config.getOutputFilter(EAST).toggleItem(iron);
        Method push = engine.getDeclaredMethod("pushItems", net.minecraft.inventory.IInventory.class,
            ForgeDirection.class, TerminalIoConfig.class, ForgeDirection.class);
        push.setAccessible(true);
        push.invoke(null, f.drawer, WEST, config, EAST);
        check(f.drawer.getDrawer(0).getStoredItemCount() == 16 && f.storage.getItemAmountPool(iron) == 0
            && f.storage.getItemAmount(iron) == 16, "active output refreshes the drawer mirror immediately");
        f.drawer.getDrawer(0).setStoredItem(iron.prototype(), 208);
        StorageBusRegistry.clear();
        f.storage.clear();
        f.storage.setItemLimit(iron, 0);
        pull.invoke(null, f.drawer, WEST, config);
        check(f.drawer.getDrawer(0).getStoredItemCount() == 208 && f.storage.getItemAmountPool(iron) == 0,
            "uncounted drawer stock still obeys the active input limit");
        f.storage.clearItemLimit(iron);
    }

    private static void checkRepeatedTransfers() {
        Fixture f = new Fixture(1);
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        Random random = new Random(20880);
        long expected = 0;
        for (int operation = 0; operation < 300; operation++) {
            int requested = 1 + random.nextInt(500);
            if (random.nextBoolean()) {
                long accepted = f.storage.insertItemManual(iron, requested);
                check(accepted == requested, "drawer overflow remains available in the pool");
                expected += accepted;
            } else {
                long taken = f.storage.extractItem(iron, requested);
                check(taken == Math.min(expected, requested), "partial-window extraction returns every requested available item");
                expected -= taken;
            }
            check(f.storage.getItemAmount(iron) == expected
                && f.storage.getItemAmountPool(iron) + f.drawer.getDrawer(0).getStoredItemCount() == expected,
                "repeated transfers conserve physical inventory across window boundaries");
        }
    }

    private static final class Fixture {

        final SharedStorage storage = SharedStorageManager.getStorage();
        final DrawerWorld world = new DrawerWorld();
        final RealDrawer drawer = new RealDrawer();
        final TileEntityStorageBus bus = new TileEntityStorageBus();

        Fixture(int count) {
            storage.clear();
            StorageBusRegistry.clear();
            drawer.setDrawerCount(count);
            drawer.setWorldObj(world);
            world.target = drawer;
            world.bus = bus;
            bus.setWorldObj(world);
            bus.addFace(EAST);
        }
    }

    private static void checkLargeDrawerBatch() {
        Fixture f = new Fixture(1);
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        f.drawer.stackCapacity = 20000;
        f.drawer.getDrawer(0).setStoredItem(iron.prototype(), 1000000);
        long taken = ExternalInventoryOps.extractItems(f.drawer, WEST, iron, Long.MAX_VALUE);
        check(taken > 0 && taken < 1000000 && taken + f.drawer.getDrawer(0).getStoredItemCount() == 1000000,
            "huge drawer requests return a bounded batch and leave the remainder safely stored");
        long accepted = ExternalInventoryOps.insertItems(f.drawer, WEST, iron, Long.MAX_VALUE);
        check(accepted > 0 && f.drawer.getDrawer(0).getStoredItemCount() == 1000000 - taken + accepted,
            "huge inserts also bound world work and report only their actual accepted batch");
    }

    /** Only capacity and world/network notifications are fixture overrides; inventory logic is real. */
    private static final class RealDrawer extends TileEntityDrawersStandard {

        private IStorageProvider provider;
        private int stackCapacity = 4;

        @Override
        protected IStorageProvider getStorageProvider() {
            if (provider == null) provider = new DefaultStorageProvider(this, this) {
                @Override
                public int getSlotStackCapacity(int slot) { return stackCapacity; }
                @Override
                public void markAmountDirty(int slot) {}
                @Override
                public void markDirty(int slot) {}
            };
            return provider;
        }

        @Override
        public Block getBlockType() { return Blocks.planks; }
    }

    private static final class DrawerWorld extends World {

        TileEntity target;
        TileEntityStorageBus bus;

        DrawerWorld() {
            super(new SaveHandlerMP(), "drawer-check",
                new WorldSettings(0, WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                new WorldProviderSurface(), new net.minecraft.profiler.Profiler());
        }

        @Override
        protected IChunkProvider createChunkProvider() { return null; }
        @Override
        protected int func_152379_p() { return 0; }
        @Override
        public net.minecraft.entity.Entity getEntityByID(int id) { return null; }
        @Override
        public boolean blockExists(int x, int y, int z) { return true; }
        @Override
        public int getBlockMetadata(int x, int y, int z) { return 0; }
        @Override
        public Block getBlock(int x, int y, int z) { return Blocks.air; }
        @Override
        public void func_147453_f(int x, int y, int z, Block block) {}
        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            if (y != 0 || z != 0) return null;
            return x == 0 ? bus : x == 1 ? target : null;
        }
        @Override
        public void markBlockForUpdate(int x, int y, int z) {}
        @Override
        public void markTileEntityChunkModified(int x, int y, int z, TileEntity tile) {}
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
}
