package com.futa_gtnh.storagebus;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.SaveHandlerMP;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.ItemFluidContainer;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.ClientStorageBus;
import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.exchange.FluidContainerHelper;
import com.futa_gtnh.exchange.InventoryExchange;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

import cpw.mods.fml.common.DummyModContainer;
import cpw.mods.fml.common.LoadController;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModMetadata;
import cpw.mods.fml.relauncher.FMLInjectionData;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;

/**
 * Registry-only regression checks for the shared storage bus.
 *
 * <p>
 * 不启动客户端、不连网络：只用一个假 World + 一个假容器，验证
 * 「终端可见的聚合库存」「按优先级写入/抽取」「筛选」「只读/只写」「流体」
 * 以及注销后不再出现。和 {@code TerminalIoRegression} 同一套 bootstrap。
 */
public final class StorageBusRegression {

    private static final ForgeDirection EAST = ForgeDirection.EAST;
    private static final ForgeDirection WEST = ForgeDirection.WEST;
    private static final ForgeDirection SOUTH = ForgeDirection.SOUTH;
    private static int checks;

    private StorageBusRegression() {}

    public static void main(String[] args) throws Exception {
        Loader.injectData(
            new Object[] { "7", "99", "40", "1614", "1.7.10", "9.05", new File("."), Collections.emptyList() });
        Field side = FMLRelaunchLog.class.getDeclaredField("side");
        side.setAccessible(true);
        side.set(null, Side.SERVER);
        Bootstrap.func_151354_b();
        Field namedMods = Loader.class.getDeclaredField("namedMods");
        namedMods.setAccessible(true);
        namedMods.set(Loader.instance(), Collections.emptyMap());
        LoadController controller = new LoadController(Loader.instance());
        Field activeContainer = LoadController.class.getDeclaredField("activeContainer");
        activeContainer.setAccessible(true);
        activeContainer.set(controller, new DummyModContainer(new ModMetadata()));
        Field modController = Loader.class.getDeclaredField("modController");
        modController.setAccessible(true);
        modController.set(Loader.instance(), controller);
        Field minecraftHome = FMLInjectionData.class.getDeclaredField("minecraftHome");
        minecraftHome.setAccessible(true);
        minecraftHome.set(null, new File("build/storage-bus-check"));
        File aeConfigDirectory = new File("build/storage-bus-check/ae-config");
        aeConfigDirectory.mkdirs();
        appeng.core.AEConfig.instance = new appeng.core.AEConfig(new File(aeConfigDirectory, "AE2.cfg"));
        TileEntity.addMapping(TileEntityStorageBus.class, "storage-bus-regression");

        checkAggregationAndRouting();
        checkPlacement();
        checkMultiFace();
        checkItemUseFirst();
        checkItemUseFirstPlacement();
        checkClientFaceRotation();
        checkConfigRoundTrip();
        checkReadOnlyAndWriteOnly();
        checkFilters();
        checkFluids();
        checkUnregister();
        checkDuplicateTargets();
        checkStaleFluidFill();
        checkCursorContainerReturn();
        checkExternalEscrow();
        checkChannelCaches();
        try {
            Class.forName("com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup", false,
                StorageBusRegression.class.getClassLoader());
            Class.forName("com.futa_gtnh.storagebus.DrawerStorageRegression").getMethod("run").invoke(null);
        } catch (ClassNotFoundException absent) {
            System.out.println("Storage Drawers absent: ordinary storage bus paths passed without optional API");
        }

        System.out.println("Storage bus regression passed: " + checks + " checks");
    }

    // ==================================================================
    // 聚合 / 写入 / 抽取
    // ==================================================================

    private static void checkAggregationAndRouting() {
        Fixture f = Fixture.create();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));

        f.target.inventory.setInventorySlotContents(0, new ItemStack(Items.iron_ingot, 64));
        refresh(f);
        check(f.storage.getItemAmount(iron) == 64, "target contents visible to shared storage");
        check(f.storage.getItemTotal() == 64, "readable total includes bus");
        check(f.storage.itemTypeCount() == 1, "type count includes bus");
        check(
            f.storage.snapshotItemsCached()
                .size() == 1,
            "cached snapshot includes bus");

        check(f.storage.extractItem(iron, 30) == 30, "withdraw draws from bus");
        check(f.target.total(iron) == 34 && f.storage.getItemAmount(iron) == 34, "withdraw conserves bus stock");

        // 优先级 0：总线先于共享存储池接收写入
        check(f.storage.insertItem(iron, 50) == 50, "insert accepted");
        check(f.target.total(iron) == 84, "priority-0 bus receives before the pool");
        check(f.storage.getItemAmount(iron) == 84, "aggregate count after insert");
        check(f.storage.itemView().isEmpty(), "no duplicate pool copy when bus accepted");

        // 正优先级更先；池子为空时仍然进总线
        f.bus.getConfig(EAST).setPriority(10);
        StorageBusRegistry.refresh(f.bus.getLink(EAST));
        check(f.storage.insertItem(iron, 10) == 10 && f.target.total(iron) == 94, "positive priority writes to bus");

        // 负优先级：池子有货时先抽池子
        SharedStorage storage = f.storage;
        storage.clear();
        StorageBusRegistry.clear();
        Fixture g = Fixture.create();
        ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
        g.bus.getConfig(EAST).setPriority(-10);
        g.target.inventory.setInventorySlotContents(0, new ItemStack(Items.gold_ingot, 40));
        refresh(g);
        g.storage.insertItemManual(gold, 100);
        check(g.storage.getItemAmount(gold) == 140, "negative-priority bus still counts toward stock");
        check(g.storage.extractItem(gold, 100) == 100, "withdraw amount satisfied");
        check(g.target.total(gold) == 0, "negative-priority bus is drained before the pool");
        check(
            g.storage.itemView()
                .getOrDefault(gold, 0L) == 40L,
            "pool keeps the remainder after the negative bus is empty");

        // 主动抽入 / 回滚用 direct 入口：即使目标挂着可写总线，也只进池子
        Fixture h = Fixture.create();
        h.target.inventory.setInventorySlotContents(0, new ItemStack(Items.iron_ingot, 20));
        refresh(h);
        check(h.storage.insertItemDirect(iron, 5) == 5, "direct insert accepted");
        check(h.target.total(iron) == 20, "direct insert never writes back to the source bus");
        check(h.storage.getItemAmountPool(iron) == 5, "direct insert lands in the pool");
        check(h.storage.extractItemDirect(iron, 5) == 5, "direct extraction uses pool only");
        check(h.target.total(iron) == 20 && h.storage.getItemAmount(iron) == 20, "direct extraction conserves stock");
    }

    private static void checkPlacement() {
        TestWorld world = new TestWorld();
        TestContainer target = new TestContainer();
        world.target = target;
        target.setWorldObj(world);

        check(
            TileEntityStorageBus.pickTargetSide(world, 0, 0, 0, EAST) == EAST,
            "placement prefers the clicked face");
        check(
            TileEntityStorageBus.pickTargetSide(world, 0, 0, 0, WEST) == EAST,
            "placement falls back to the first adjacent container");
        check(
            TileEntityStorageBus.pickTargetSide(world, 20, 0, 0, WEST) == WEST,
            "placement keeps the clicked face when no container exists");
        BlockStorageBus block = new BlockStorageBus();
        check(
            block.onBlockPlaced(world, 0, 0, 0, WEST.ordinal(), 0.5F, 0.5F, 0.5F, 0) == EAST.ordinal(),
            "block metadata records the target face");

        world.blockMetadata = EAST.ordinal();
        java.util.List<AxisAlignedBB> boxes = new java.util.ArrayList<>();
        block.addCollisionBoxesToList(
            world,
            0,
            0,
            0,
            AxisAlignedBB.getBoundingBox(-1.0D, -1.0D, -1.0D, 2.0D, 2.0D, 2.0D),
            boxes,
            null);
        check(boxes.size() == 1, "a single configured face has one collision plate");
        AxisAlignedBB plate = boxes.get(0);
        check(
            Math.abs(plate.minX - 12.0D / 16.0D) < 1.0E-6 && Math.abs(plate.maxX - 1.0D) < 1.0E-6,
            "east-facing plate is flush with the target face");
        check(
            Math.abs(plate.minY - 2.0D / 16.0D) < 1.0E-6 && Math.abs(plate.maxY - 14.0D / 16.0D) < 1.0E-6,
            "plate leaves a margin around the target face");
        check(
            StorageBusGeometry.plateBox(EAST)
                .maxX == 1.0D,
            "plate geometry follows the target face");

        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            AxisAlignedBB plateBox = StorageBusGeometry.plateBox(face);
            check(
                plateBox.minX >= 0.0D && plateBox.minY >= 0.0D
                    && plateBox.minZ >= 0.0D
                    && plateBox.maxX <= 1.0D
                    && plateBox.maxY <= 1.0D
                    && plateBox.maxZ <= 1.0D,
                "plate stays inside the cell: " + face);
            for (AxisAlignedBB layer : new AxisAlignedBB[] { StorageBusGeometry.baseBox(face),
                StorageBusGeometry.middleBox(face), StorageBusGeometry.connectorBox(face) }) {
                check(
                    layer.minX >= plateBox.minX - 1.0E-6 && layer.maxX <= plateBox.maxX + 1.0E-6
                        && layer.minY >= plateBox.minY - 1.0E-6
                        && layer.maxY <= plateBox.maxY + 1.0E-6
                        && layer.minZ >= plateBox.minZ - 1.0E-6
                        && layer.maxZ <= plateBox.maxZ + 1.0E-6,
                    "AE-style layer stays inside the collision plate: " + face);
            }
        }
    }

    private static void checkMultiFace() {
        TestWorld world = new TestWorld();
        TileEntityStorageBus bus = new TileEntityStorageBus();
        bus.setWorldObj(world);
        world.bus = bus;

        check(bus.addFace(EAST), "first face can be added");
        check(bus.addFace(WEST), "second face can be added to the same cell");
        check(!bus.addFace(EAST), "duplicate face is rejected");
        check(
            bus.getFaceMask() == (1 << EAST.ordinal()) + (1 << WEST.ordinal()),
            "face mask contains both configured faces");
        check(
            bus.getConfig(EAST) != null && bus.getConfig(WEST) != null && bus.getConfig(EAST) != bus.getConfig(WEST),
            "each face has an independent configuration");

        bus.getConfig(EAST)
            .setPriority(11);
        bus.getConfig(WEST)
            .setPriority(-12);
        check(
            bus.getConfig(EAST)
                .getPriority() == 11
                && bus.getConfig(WEST)
                    .getPriority() == -12,
            "priorities do not leak between faces");

        BlockStorageBus block = new BlockStorageBus();
        java.util.List<AxisAlignedBB> boxes = new java.util.ArrayList<>();
        block.addCollisionBoxesToList(
            world,
            0,
            0,
            0,
            AxisAlignedBB.getBoundingBox(-1.0D, -1.0D, -1.0D, 2.0D, 2.0D, 2.0D),
            boxes,
            null);
        check(boxes.size() == 2, "two configured faces produce two collision plates");
        boolean hasWestPlate = boxes.get(0).minX < 0.5D || boxes.get(1).minX < 0.5D;
        boolean hasEastPlate = boxes.get(0).minX > 0.5D || boxes.get(1).minX > 0.5D;
        check(hasWestPlate && hasEastPlate, "collision plates keep their opposite faces");

        MovingObjectPosition eastHit = block.collisionRayTrace(
            world,
            0,
            0,
            0,
            Vec3.createVectorHelper(0.5D, 0.5D, 0.5D),
            Vec3.createVectorHelper(2.0D, 0.5D, 0.5D));
        check(
            eastHit != null && eastHit.hitInfo instanceof Integer
                && ((Integer) eastHit.hitInfo).intValue() == EAST.ordinal(),
            "ray from the middle hits the east plate");
        MovingObjectPosition westHit = block.collisionRayTrace(
            world,
            0,
            0,
            0,
            Vec3.createVectorHelper(0.5D, 0.5D, 0.5D),
            Vec3.createVectorHelper(-1.0D, 0.5D, 0.5D));
        check(
            westHit != null && westHit.hitInfo instanceof Integer
                && ((Integer) westHit.hitInfo).intValue() == WEST.ordinal(),
            "ray from the middle hits the west plate");

        NBTTagCompound saved = new NBTTagCompound();
        bus.writeToNBT(saved);
        TileEntityStorageBus copy = new TileEntityStorageBus();
        copy.setWorldObj(world);
        copy.readFromNBT(saved);
        check(
            copy.getFaceMask() == (1 << EAST.ordinal()) + (1 << WEST.ordinal()),
            "multi-face mask survives NBT round trip");
        check(
            copy.getConfig(EAST)
                .getPriority() == 11
                && copy.getConfig(WEST)
                    .getPriority() == -12,
            "per-face settings survive NBT round trip");

        TileEntityStorageBus clientCopy = new TileEntityStorageBus();
        clientCopy.setWorldObj(world);
        clientCopy.onDataPacket(null, (S35PacketUpdateTileEntity) bus.getDescriptionPacket());
        check(
            clientCopy.getFaceMask() == (1 << EAST.ordinal()) + (1 << WEST.ordinal())
                && clientCopy.hasFace(EAST)
                && clientCopy.hasFace(WEST),
            "face mask survives tile-entity description packet");

        NBTTagCompound legacy = new NBTTagCompound();
        StorageBusConfig single = new StorageBusConfig();
        single.setTargetSide(EAST);
        single.setPriority(3);
        legacy.setTag("bus", single.writeToNbt());
        TileEntityStorageBus migrated = new TileEntityStorageBus();
        migrated.setWorldObj(world);
        migrated.readFromNBT(legacy);
        check(
            migrated.getFaceMask() == (1 << EAST.ordinal()) && migrated.getConfig(EAST)
                .getPriority() == 3,
            "legacy single-face NBT migrates to the matching plate");

        check(bus.removeFace(EAST), "one face can be removed");
        check(
            !bus.hasFace(EAST) && bus.hasFace(WEST)
                && bus.getFaceMask() == (1 << WEST.ordinal()),
            "removing one face leaves the other intact");
        bus.invalidate();
    }

    private static void checkItemUseFirst() {
        TestWorld world = new TestWorld();
        TestContainer target = new TestContainer();
        TileEntityStorageBus bus = new TileEntityStorageBus();
        target.setWorldObj(world);
        bus.setWorldObj(world);
        world.target = target;
        world.bus = bus;
        BlockStorageBus busBlock = new BlockStorageBus();
        world.busBlock = busBlock;
        FutaGtnhMod.blockStorageBus = busBlock;

        TestPlayer player = new TestPlayer(world);
        player.setSneaking(true);
        ItemStack stack = new ItemStack(busBlock, 2);
        ItemStorageBus item = new ItemStorageBus(busBlock);

        // 右键目标西面：总线格在 (0,0,0)，目标在 (1,0,0)，应加 EAST 面。
        // 潜行也应生效：玩家常潜行贴总线，避免打开目标容器。
        boolean handled = item.onItemUseFirst(stack, player, world, 1, 0, 0, WEST.ordinal(), 0.5F, 0.5F, 0.5F);
        check(handled, "onItemUseFirst handles an existing bus block");
        check(bus.hasFace(EAST), "onItemUseFirst adds the clicked target face");
        check(stack.stackSize == 1, "onItemUseFirst consumes one bus item");

        handled = item.onItemUseFirst(stack, player, world, 1, 0, 0, WEST.ordinal(), 0.5F, 0.5F, 0.5F);
        check(!handled, "an already-connected face does not place another bus");
        check(bus.hasFace(EAST) && stack.stackSize == 1, "existing face does not consume another item");

        // 从第二个目标方块“背对总线”的那一面右键：仍应找到北边的已有总线格并加 SOUTH 面。
        handled = item.onItemUseFirst(stack, player, world, 0, 0, 1, SOUTH.ordinal(), 0.5F, 0.5F, 0.5F);
        check(handled, "onItemUseFirst finds an adjacent bus from the clicked block's other side");
        check(bus.hasFace(SOUTH) && stack.stackSize == 0, "adding from the far side targets the same bus cell");

        bus.invalidate();
        FutaGtnhMod.blockStorageBus = null;
    }

    private static void checkItemUseFirstPlacement() {
        TestWorld world = new TestWorld();
        TestContainer target = new TestContainer();
        TileEntityStorageBus bus = new TileEntityStorageBus();
        target.setWorldObj(world);
        bus.setWorldObj(world);
        world.target = target;
        world.bus = bus;
        BlockStorageBus busBlock = new BlockStorageBus();
        world.busBlock = busBlock;
        world.busBlockAtOrigin = false;
        FutaGtnhMod.blockStorageBus = busBlock;

        TestPlayer player = new TestPlayer(world);
        ItemStack stack = new ItemStack(busBlock, 1);
        ItemStorageBus item = new ItemStorageBus(busBlock);
        boolean handled = item.onItemUseFirst(stack, player, world, 1, 0, 0, WEST.ordinal(), 0.5F, 0.5F, 0.5F);
        check(handled, "onItemUseFirst can place the first bus");
        check(bus.hasFace(EAST) && world.blockMetadata == EAST.ordinal(), "first bus records the clicked target face");
        check(stack.stackSize == 0, "first placement consumes the bus item");

        bus.invalidate();
        FutaGtnhMod.blockStorageBus = null;
    }

    private static void checkClientFaceRotation() {
        ClientStorageBus.setConfig(
            0,
            0,
            0,
            0,
            0,
            (1 << 0) | (1 << 3),
            new StorageBusConfig().writeToNbt(),
            "",
            false,
            false);
        check(ClientStorageBus.hasFace(0) && ClientStorageBus.hasFace(3), "client cache keeps configured faces");
        check(!ClientStorageBus.hasFace(1), "client cache rejects empty faces");
        check(ClientStorageBus.nextFace(0, 1) == 3, "face rotation moves to the next configured face");
        check(ClientStorageBus.nextFace(3, 1) == 0, "face rotation wraps around");
        check(ClientStorageBus.nextFace(0, -1) == 3, "face rotation wraps backwards");
        ClientStorageBus.clear();
    }

    private static void checkConfigRoundTrip() {
        StorageBusConfig config = new StorageBusConfig();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water = FluidKey.of(new FluidStack(FluidRegistry.WATER, 1));
        config.setTargetSide(EAST);
        config.setPriority(-7);
        config.setAccess(StorageBusConfig.AccessMode.READ_ONLY);
        config.setFilterMode(StorageBusConfig.FilterMode.BLACKLIST);
        config.setMatchMode(StorageBusConfig.MatchMode.FUZZY);
        config.setItemFilter(2, iron);
        config.setFluidFilter(4, water);

        StorageBusConfig restored = new StorageBusConfig();
        restored.readFromNbt(config.writeToNbt());
        check(restored.getTargetSide() == EAST, "config NBT target");
        check(restored.getPriority() == -7, "config NBT priority");
        check(restored.getAccess() == StorageBusConfig.AccessMode.READ_ONLY, "config NBT access");
        check(restored.getFilterMode() == StorageBusConfig.FilterMode.BLACKLIST, "config NBT filter mode");
        check(restored.getMatchMode() == StorageBusConfig.MatchMode.FUZZY, "config NBT match mode");
        check(iron.equals(restored.getItemFilter(2)) && restored.getItemFilter(0) == null, "config NBT keeps item slot");
        check(water.equals(restored.getFluidFilter(4)) && restored.getFluidFilter(0) == null, "config NBT keeps fluid slot");
        check(!restored.allowsItem(iron), "config NBT blacklist still matches");
        check(!restored.allowsFluid(water), "config NBT fluid blacklist still matches");

        restored.setPriority(Integer.MAX_VALUE);
        check(restored.getPriority() == StorageBusConfig.MAX_PRIORITY, "priority clamped high");
        restored.setPriority(Integer.MIN_VALUE);
        check(restored.getPriority() == -StorageBusConfig.MAX_PRIORITY, "priority clamped low");
    }

    private static void checkReadOnlyAndWriteOnly() {
        Fixture f = Fixture.create();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        f.target.inventory.setInventorySlotContents(0, new ItemStack(Items.iron_ingot, 30));
        f.bus.getConfig(EAST).setAccess(StorageBusConfig.AccessMode.READ_ONLY);
        refresh(f);

        check(f.storage.getItemAmount(iron) == 30, "read-only bus is visible");
        check(f.storage.insertItem(iron, 10) == 10, "read-only bus rejects insert without losing it");
        check(f.target.total(iron) == 30, "read-only target unchanged");
        check(f.storage.getItemAmount(iron) == 40, "rejected insert lands in the pool");
        check(f.storage.extractItem(iron, 5) == 5, "read-only bus can be extracted");
        check(f.target.total(iron) == 30, "pool extracted before equal-priority bus");

        Fixture g = Fixture.create();
        g.bus.getConfig(EAST).setAccess(StorageBusConfig.AccessMode.WRITE_ONLY);
        g.target.inventory.setInventorySlotContents(0, new ItemStack(Items.iron_ingot, 7));
        refresh(g);
        check(g.storage.getItemAmount(iron) == 0, "write-only bus is hidden");
        check(g.storage.insertItem(iron, 4) == 4, "write-only bus accepts insert");
        check(g.target.total(iron) == 11, "write-only target received items");
        check(g.storage.extractItem(iron, 1) == 0, "write-only bus cannot be extracted");
        check(g.storage.itemView().isEmpty(), "write-only has no readable pool copy");

        g.bus.getConfig(EAST).setAccess(StorageBusConfig.AccessMode.READ_WRITE);
        refresh(g);
        check(g.storage.getItemAmount(iron) == 11, "access change exposes existing stock");
    }

    private static void checkFilters() {
        Fixture f = Fixture.create();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
        f.bus.getConfig(EAST)
            .setItemFilter(0, iron);
        refresh(f);

        check(f.storage.insertItem(gold, 5) == 5, "whitelisted bus rejects other items");
        check(f.target.total(gold) == 0, "gold did not enter target");
        check(f.storage.getItemAmount(gold) == 5, "gold fell through to the pool");
        check(f.storage.insertItem(iron, 6) == 6 && f.target.total(iron) == 6, "whitelisted item enters target");

        f.bus.getConfig(EAST)
            .setFilterMode(StorageBusConfig.FilterMode.BLACKLIST);
        refresh(f);
        check(f.storage.insertItem(iron, 2) == 2 && f.target.total(iron) == 6, "blacklist now rejects iron");
        check(f.storage.insertItem(gold, 3) == 3 && f.target.total(gold) == 3, "blacklist accepts non-listed gold");

        // 精确 / 模糊：带 NBT 的变体
        ItemStack taggedIron = new ItemStack(Items.iron_ingot);
        taggedIron.setTagCompound(new NBTTagCompound());
        taggedIron.getTagCompound()
            .setString("variant", "bus-check");
        ItemKey taggedKey = ItemKey.of(taggedIron);
        f.bus.getConfig(EAST)
            .clearFilters();
        f.bus.getConfig(EAST)
            .setFilterMode(StorageBusConfig.FilterMode.WHITELIST);
        f.bus.getConfig(EAST)
            .setItemFilter(0, taggedKey);
        f.bus.getConfig(EAST)
            .setMatchMode(StorageBusConfig.MatchMode.EXACT);
        check(!f.bus.getConfig(EAST)
            .allowsItem(iron), "exact mode distinguishes NBT");
        f.bus.getConfig(EAST)
            .setMatchMode(StorageBusConfig.MatchMode.FUZZY);
        check(f.bus.getConfig(EAST)
            .allowsItem(iron), "fuzzy mode ignores NBT");

        // 矿辞：给两个不同物品注册同一个矿辞名
        Item oreItem = new Item().setUnlocalizedName("storage-bus-ore-check");
        Item.itemRegistry.addObject(30150, "storage-bus-ore-check", oreItem);
        OreDictionary.registerOre("ingotStorageBusCheck", new ItemStack(Items.iron_ingot));
        OreDictionary.registerOre("ingotStorageBusCheck", new ItemStack(oreItem));
        ItemKey oreKey = ItemKey.of(new ItemStack(oreItem));
        f.bus.getConfig(EAST)
            .clearFilters();
        f.bus.getConfig(EAST)
            .setItemFilter(0, oreKey);
        f.bus.getConfig(EAST)
            .setMatchMode(StorageBusConfig.MatchMode.ORE_DICT);
        check(f.bus.getConfig(EAST)
            .allowsItem(iron), "ore dictionary mode matches another item with the same ore name");
        check(!f.bus.getConfig(EAST)
            .allowsItem(gold), "ore dictionary mode still rejects unrelated items");
    }

    private static void checkFluids() {
        Fixture f = Fixture.create();
        FluidKey water = FluidKey.of(new FluidStack(FluidRegistry.WATER, 1));
        f.target.tank.fill(new FluidStack(FluidRegistry.WATER, 4000), true);
        refresh(f);

        check(f.storage.getFluidAmount(water) == 4000, "fluid target visible");
        check(f.storage.extractFluid(water, 1000) == 1000, "fluid withdraw from bus");
        check(f.target.tank.getFluidAmount() == 3000, "fluid withdraw conserves target");
        check(f.storage.insertFluid(water, 500) == 500, "fluid insert accepted");
        check(f.target.tank.getFluidAmount() == 3500, "fluid insert goes to bus first");

        f.bus.getConfig(EAST)
            .setFluidFilter(0, water);
        f.bus.getConfig(EAST)
            .setFilterMode(StorageBusConfig.FilterMode.BLACKLIST);
        refresh(f);
        check(f.target.tank.getFluidAmount() == 3500, "fluid blacklist does not change existing content");
        check(f.storage.getFluidAmount(water) == 0, "blacklisted fluid hidden from shared storage");

        Fixture g = Fixture.create();
        g.target.tank.fill(new FluidStack(FluidRegistry.WATER, 4000), true);
        refresh(g);
        TestContainer otherTarget = new TestContainer();
        check(
            g.storage.extractFluidExcluding(water, 1000, otherTarget) == 1000,
            "active output can withdraw fluid from a bus target");
        check(g.target.tank.getFluidAmount() == 3000, "active bus withdrawal conserves the source tank");
        check(
            g.storage.extractFluidExcluding(water, 1000, g.target) == 0,
            "active output excludes the destination's own bus");
        check(g.target.tank.getFluidAmount() == 3000, "excluded destination keeps its fluid");
    }

    private static void checkUnregister() {
        Fixture f = Fixture.create();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        f.target.inventory.setInventorySlotContents(0, new ItemStack(Items.iron_ingot, 12));
        refresh(f);
        check(f.storage.getItemAmount(iron) == 12, "registered bus visible");
        f.bus.invalidate();
        check(f.storage.getItemAmount(iron) == 0, "unregistered bus disappears");
        check(f.target.total(iron) == 12, "unregister never deletes target contents");
    }

    private static void checkDuplicateTargets() {
        Fixture f = Fixture.create();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        ItemKey gold = ItemKey.of(new ItemStack(Items.gold_ingot));
        FluidKey water = FluidKey.of(new FluidStack(FluidRegistry.WATER, 1));
        f.target.inventory.setInventorySlotContents(0, iron.prototype(64));
        f.target.inventory.setInventorySlotContents(1, gold.prototype(8));
        f.target.tank.fill(water.prototype(3000), true);
        refresh(f);
        TileEntityStorageBus other = new TileEntityStorageBus();
        other.xCoord = 2;
        other.setWorldObj(f.world);
        other.addFace(WEST);
        check(f.storage.getItemAmount(iron) == 64 && f.storage.getFluidAmount(water) == 3000,
            "two faces on one target never double-count physical stock");
        check(f.storage.getItemTotal() == 72 && f.storage.itemTypeCount() == 2,
            "totals and snapshots count a shared target once");
        check(f.storage.extractItem(iron, 31) == 31 && f.storage.getItemAmount(iron) == 33,
            "withdraw refreshes every alias before returning");
        check(f.storage.insertItem(iron, 12) == 12 && f.storage.getItemAmount(iron) == 45,
            "insert refreshes every alias before returning");
        check(f.storage.extractFluid(water, 1000) == 1000 && f.storage.getFluidAmount(water) == 2000,
            "fluid aliases stay consistent after extraction");
        f.storage.setItemLimit(iron, 64);
        f.storage.setFluidLimit(water, 2500);
        check(f.storage.insertItem(iron, 20) == 19 && f.target.total(iron) == 64,
            "automation limit uses unique target stock");
        check(f.storage.insertFluid(water, 700) == 500 && f.target.tank.getFluidAmount() == 2500,
            "fluid automation limit uses unique target stock");
        f.bus.getConfig(EAST).setItemFilter(0, iron);
        other.getConfig(WEST).setItemFilter(0, gold);
        StorageBusRegistry.refreshAll();
        check(f.storage.getItemAmount(iron) == 64 && f.storage.getItemAmount(gold) == 8,
            "disjoint alias filters retain both keys");
        f.bus.getConfig(EAST).setAccess(StorageBusConfig.AccessMode.WRITE_ONLY);
        refresh(f);
        check(f.storage.getItemAmount(iron) == 0 && f.storage.itemSpaceLeft(iron) == 0,
            "write-only alias remains hidden but still counts toward limits");
        other.getConfig(WEST).clearItemFilters();
        StorageBusRegistry.refresh(other.getLink(WEST));
        check(f.storage.getItemAmount(iron) == 64 && f.storage.getFluidAmount(water) == 2500,
            "readable alias exposes hidden target without duplicating it");
        other.invalidate();
        check(f.storage.getItemAmount(iron) == 0 && f.storage.getFluidAmount(water) == 0,
            "unregister removes only the last readable alias contribution");
        f.bus.getConfig(EAST).setAccess(StorageBusConfig.AccessMode.READ_WRITE);
        refresh(f);
        check(f.storage.getItemAmount(iron) == 64, "surviving alias remains available");
        TestContainer replacement = new TestContainer();
        replacement.setWorldObj(f.world);
        replacement.inventory.setInventorySlotContents(0, iron.prototype(7));
        f.world.target = replacement;
        refresh(f);
        check(f.storage.getItemAmount(iron) == 7, "target replacement removes the old physical inventory");
        TestWorld separate = new TestWorld();
        separate.target = new TestContainer();
        separate.target.setWorldObj(separate);
        separate.target.inventory.setInventorySlotContents(0, iron.prototype(13));
        separate.bus = new TileEntityStorageBus();
        separate.bus.setWorldObj(separate);
        separate.bus.addFace(EAST);
        check(f.storage.getItemAmount(iron) == 20, "different physical targets still sum their stock");
        separate.bus.invalidate();
        check(f.storage.getItemAmount(iron) == 7, "removing one target preserves the other target's contribution");
        f.storage.clearItemLimit(iron);
        f.storage.clearFluidLimit(water);
    }

    private static void checkStaleFluidFill() {
        Fixture f = Fixture.create();
        FluidKey water = FluidKey.of(new FluidStack(FluidRegistry.WATER, 1));
        FluidContainerRegistry.registerFluidContainer(water.prototype(1000), new ItemStack(Items.water_bucket),
            new ItemStack(Items.bucket));
        f.target.tank.fill(water.prototype(1000), true);
        refresh(f);
        f.target.tank.drain(900, true);
        TestPlayer player = new TestPlayer(f.world);
        player.inventory.mainInventory[0] = new ItemStack(Items.bucket);
        DeltaRecorder recorder = new DeltaRecorder(f.storage, null);
        check(InventoryExchange.fillContainers(player, water, 1000, f.storage, recorder) == 0,
            "stale fluid preview cannot create a full bucket from a partial extraction");
        check(player.inventory.mainInventory[0].getItem() == Items.bucket
            && f.storage.getFluidAmount(water) == 100,
            "failed partial fill keeps the empty bucket and refunds every mB");
        check(FluidContainerHelper.getFluid(player.inventory.mainInventory[0]) == null,
            "no filled container is created by stale stock");
        f.storage.insertFluidManualDirect(water, 900);
        check(InventoryExchange.fillContainers(player, water, 1000, f.storage, recorder) == 1000,
            "exact stock still fills normally after rollback");
        check(player.inventory.mainInventory[0].getItem() == Items.water_bucket && f.storage.getFluidAmount(water) == 0,
            "normal filling conserves total fluid");
        Fixture partial = Fixture.create();
        partial.target.tank.fill(water.prototype(1000), true);
        refresh(partial);
        partial.target.tank.drain(900, true);
        TestPlayer partialPlayer = new TestPlayer(partial.world);
        partialPlayer.inventory.mainInventory[0] = new ItemStack(new ItemFluidContainer(0, 1000));
        check(InventoryExchange.fillContainers(partialPlayer, water, 1000, partial.storage,
            new DeltaRecorder(partial.storage, null)) == 100, "flexible container fills only the actual extracted amount");
        FluidStack contents = FluidContainerHelper.getFluid(partialPlayer.inventory.mainInventory[0]);
        check(contents != null && contents.amount == 100 && partial.storage.getFluidAmount(water) == 0,
            "partial container contents and storage deduction agree");
    }

    private static void checkCursorContainerReturn() {
        Fixture f = Fixture.create();
        StorageBusRegistry.clear();
        FluidKey water = FluidKey.of(new FluidStack(FluidRegistry.WATER, 1));
        f.storage.insertFluidManual(water, 1000);
        TestPlayer player = new TestPlayer(f.world);
        for (int i = 0; i < 36; i++) player.inventory.mainInventory[i] = new ItemStack(Items.stone_axe);
        player.inventory.mainInventory[0] = new ItemStack(Items.bucket, 15);
        player.inventory.setItemStack(new ItemStack(Items.bucket, 4));
        DeltaRecorder recorder = new DeltaRecorder(f.storage, null);
        check(InventoryExchange.fillCursorContainer(player, water, 1000, f.storage, recorder) == 0,
            "partial empty-container return aborts before editing real inventory");
        check(player.inventory.mainInventory[0].stackSize == 15 && player.inventory.getItemStack().stackSize == 4
            && f.storage.getFluidAmount(water) == 1000,
            "failed cursor fill preserves all 19 empty buckets and every mB");
        player.inventory.mainInventory[1] = null;
        check(InventoryExchange.fillCursorContainer(player, water, 1000, f.storage, recorder) == 1000,
            "cursor fill succeeds when all remaining empties fit");
        check(player.inventory.getItemStack().getItem() == Items.water_bucket
            && player.inventory.getItemStack().stackSize == 1
            && player.inventory.mainInventory[0].stackSize == 16
            && player.inventory.mainInventory[1].stackSize == 2
            && f.storage.getFluidAmount(water) == 0,
            "successful partial cursor fill conserves containers and fluid");
    }

    private static void checkExternalEscrow() {
        Fixture f = Fixture.create();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water = FluidKey.of(new FluidStack(FluidRegistry.WATER, 1));
        f.target.inventory.setInventorySlotContents(0, iron.prototype(64));
        f.target.tank.fill(water.prototype(1000), true);
        refresh(f);
        f.storage.markClean();
        SharedStorage.CraftingReservation items = f.storage.reserveItemForCrafting(iron, 10);
        check(items != null && f.storage.isDirty(), "bus-only item reservation marks durable escrow dirty");
        f.storage.markClean();
        SharedStorage.CraftingReservation fluids = f.storage.reserveFluidForCrafting(water, 100);
        check(fluids != null && f.storage.isDirty(), "bus-only fluid reservation marks durable escrow dirty");
        SharedStorage restored = new SharedStorage();
        restored.readFromNbt(f.storage.writeToNbt());
        check(restored.snapshotCraftingItems().get(0).getValue() == 10L
            && restored.snapshotCraftingFluids().get(0).getValue() == 100L,
            "uncertain external reservations survive serialization");
        items.finish(10);
        fluids.finish(100);
        check(f.storage.getItemAmount(iron) == 64 && f.storage.getFluidAmount(water) == 1000,
            "successful reservation refunds still conserve external stock");
    }

    private static void checkChannelCaches() {
        Fixture f = Fixture.create();
        ItemKey iron = ItemKey.of(new ItemStack(Items.iron_ingot));
        FluidKey water = FluidKey.of(FluidRegistry.WATER, null);
        f.target.inventory.setInventorySlotContents(0, iron.prototype(10));
        f.target.tank.fill(water.prototype(1000), true);
        refresh(f);
        Object items = f.storage.snapshotItemsCached();
        Object fluids = f.storage.snapshotFluidsCached();
        f.target.tank.fill(water.prototype(100), true);
        refresh(f);
        check(f.storage.snapshotItemsCached() == items && f.storage.snapshotFluidsCached() != fluids,
            "fluid bus refresh does not invalidate item snapshots");
        fluids = f.storage.snapshotFluidsCached();
        f.target.inventory.setInventorySlotContents(0, iron.prototype(11));
        refresh(f);
        check(f.storage.snapshotFluidsCached() == fluids && f.storage.snapshotItemsCached() != items,
            "item bus refresh does not invalidate fluid snapshots");
        f.bus.getConfig(EAST).setAccess(StorageBusConfig.AccessMode.WRITE_ONLY);
        refresh(f);
        check(f.storage.snapshotItemsCached().isEmpty() && f.storage.snapshotFluidsCached().isEmpty(),
            "access-mode changes invalidate the affected readable channels");
        items = f.storage.snapshotItemsCached();
        fluids = f.storage.snapshotFluidsCached();
        f.target.tank.fill(water.prototype(100), true);
        refresh(f);
        check(f.storage.snapshotItemsCached() == items && f.storage.snapshotFluidsCached() == fluids,
            "hidden stock changes preserve both readable caches");
        f.bus.getConfig(EAST).setAccess(StorageBusConfig.AccessMode.READ_WRITE);
        refresh(f);
        check(f.storage.snapshotItemsCached().get(0).getValue() == 11L
            && f.storage.snapshotFluidsCached().get(0).getValue() == 1200L,
            "restoring read access exposes fresh stock in both channels");
    }

    // ==================================================================
    // Fixture / helpers
    // ==================================================================

    private static void refresh(Fixture fixture) {
        StorageBusRegistry.refresh(fixture.bus.getLink(EAST));
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }

    private static final class Fixture {

        final SharedStorage storage;
        final TestWorld world;
        final TestContainer target;
        final TileEntityStorageBus bus;

        private Fixture(SharedStorage storage, TestWorld world, TestContainer target, TileEntityStorageBus bus) {
            this.storage = storage;
            this.world = world;
            this.target = target;
            this.bus = bus;
        }

        static Fixture create() {
            SharedStorageManager.getStorage()
                .clear();
            StorageBusRegistry.clear();
            TestWorld world = new TestWorld();
            TestContainer target = new TestContainer();
            TileEntityStorageBus bus = new TileEntityStorageBus();
            target.setWorldObj(world);
            world.target = target;
            world.bus = bus;
            bus.setWorldObj(world);
            bus.addFace(EAST);
            StorageBusRegistry.refresh(bus.getLink(EAST));
            return new Fixture(SharedStorageManager.getStorage(), world, target, bus);
        }
    }

    /** 同时提供物品和流体的假容器。 */
    private static final class TestContainer extends TileEntity implements IInventory, IFluidHandler {

        final InventoryBasic inventory = new InventoryBasic("storage-bus-target", true, 3);
        final FluidTank tank = new FluidTank(16000);

        int total(ItemKey key) {
            int total = 0;
            for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (stack != null && key.equals(ItemKey.of(stack))) total += stack.stackSize;
            }
            return total;
        }

        @Override
        public int getSizeInventory() {
            return inventory.getSizeInventory();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return inventory.getStackInSlot(slot);
        }

        @Override
        public ItemStack decrStackSize(int slot, int amount) {
            return inventory.decrStackSize(slot, amount);
        }

        @Override
        public ItemStack getStackInSlotOnClosing(int slot) {
            return inventory.getStackInSlotOnClosing(slot);
        }

        @Override
        public void setInventorySlotContents(int slot, ItemStack stack) {
            inventory.setInventorySlotContents(slot, stack);
        }

        @Override
        public String getInventoryName() {
            return inventory.getInventoryName();
        }

        @Override
        public boolean hasCustomInventoryName() {
            return false;
        }

        @Override
        public int getInventoryStackLimit() {
            return 64;
        }

        @Override
        public void markDirty() {}

        @Override
        public boolean isUseableByPlayer(net.minecraft.entity.player.EntityPlayer player) {
            return true;
        }

        @Override
        public void openInventory() {}

        @Override
        public void closeInventory() {}

        @Override
        public boolean isItemValidForSlot(int slot, ItemStack stack) {
            return true;
        }

        @Override
        public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
            return tank.fill(resource, doFill);
        }

        @Override
        public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
            FluidStack current = tank.getFluid();
            if (current == null || resource == null || !current.isFluidEqual(resource)) return null;
            return tank.drain(resource.amount, doDrain);
        }

        @Override
        public FluidStack drain(ForgeDirection from, int amount, boolean doDrain) {
            return tank.drain(amount, doDrain);
        }

        @Override
        public boolean canFill(ForgeDirection from, Fluid fluid) {
            return true;
        }

        @Override
        public boolean canDrain(ForgeDirection from, Fluid fluid) {
            return true;
        }

        @Override
        public FluidTankInfo[] getTankInfo(ForgeDirection from) {
            return new FluidTankInfo[] { tank.getInfo() };
        }
    }

    private static final class TestWorld extends World {

        TileEntityStorageBus bus;
        TestContainer target;
        BlockStorageBus busBlock;
        boolean busBlockAtOrigin = true;
        int blockMetadata;

        TestWorld() {
            super(
                new SaveHandlerMP(),
                "storage-bus-check",
                new WorldSettings(0, WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT),
                new WorldProviderSurface(),
                new net.minecraft.profiler.Profiler());
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public net.minecraft.entity.Entity getEntityByID(int id) {
            return null;
        }

        @Override
        public TileEntity getTileEntity(int x, int y, int z) {
            if (y != 0 || z != 0) return null;
            if (x == 0) return bus;
            if (x == 1) return target;
            return null;
        }

        @Override
        public int getBlockMetadata(int x, int y, int z) {
            return blockMetadata;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return true;
        }

        @Override
        public net.minecraft.block.Block getBlock(int x, int y, int z) {
            if (x == 0 && y == 0 && z == 0 && busBlockAtOrigin && busBlock != null) return busBlock;
            return net.minecraft.init.Blocks.air;
        }

        @Override
        public boolean setBlock(int x, int y, int z, net.minecraft.block.Block block, int metadata, int flags) {
            if (x == 0 && y == 0 && z == 0) {
                busBlockAtOrigin = true;
                blockMetadata = metadata;
                return true;
            }
            return false;
        }

        @Override
        public void markBlockForUpdate(int x, int y, int z) {}

        @Override
        public void markTileEntityChunkModified(int x, int y, int z, TileEntity tile) {}
    }

    /** 不打开真实 GUI 的测试玩家。 */
    private static final class TestPlayer extends EntityPlayer {

        TestPlayer(World world) {
            super(world, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "storage-bus-test"));
        }

        @Override
        public void openGui(Object mod, int modGuiId, World world, int x, int y, int z) {}

        @Override
        public net.minecraft.util.ChunkCoordinates getPlayerCoordinates() {
            return new net.minecraft.util.ChunkCoordinates(0, 0, 0);
        }

        @Override
        public boolean canCommandSenderUseCommand(int permissionLevel, String command) {
            return true;
        }

        @Override
        public void addChatMessage(net.minecraft.util.IChatComponent message) {}
    }
}
