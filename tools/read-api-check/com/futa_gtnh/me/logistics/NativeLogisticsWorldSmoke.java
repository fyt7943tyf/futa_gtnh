package com.futa_gtnh.me.logistics;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.block.TileEntityIoNode;
import com.futa_gtnh.me.NativeMeStorageWorldSmoke;
import com.futa_gtnh.me.PublicMeNetwork;
import com.futa_gtnh.me.PublicMeWorldSmoke;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.storagebus.TileEntityStorageBus;
import com.futa_gtnh.supply.SupplySource;
import com.futa_gtnh.supply.SupplyTracker;

import appeng.api.config.Actionable;
import appeng.parts.misc.PartStorageBus;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.GameRegistry;

/** Actual AE grid scheduling and real native item/fluid workers, only in the isolated smoke world. */
public final class NativeLogisticsWorldSmoke {

    private static volatile boolean complete;
    private static volatile String failure;
    private WorldServer world;
    private int x, y, z, ticks, stage, wait;
    private ItemKey item;
    private FluidKey fluid;
    private Fixture target;
    private TileEntityIoNode io;
    private TileEntityIoNode sentinel;
    private boolean restartedCheckpoint;
    private TileEntityStorageBus bus;
    private TileEntityStorageBus alias;
    private gregtech.api.metatileentity.BaseMetaTileEntity gtBus;

    public static void register() {
        complete = false;
        failure = null;
        GameRegistry.registerTileEntity(Fixture.class, "futa_native_logistics_smoke_fixture");
        FMLCommonHandler.instance()
            .bus()
            .register(new NativeLogisticsWorldSmoke());
    }

    public static boolean isComplete() {
        return complete;
    }

    public static String failure() {
        return failure;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || complete
            || failure != null
            || !NativeMeStorageWorldSmoke.isComplete()
            || !com.futa_gtnh.me.GtswnQuantumWorldSmoke.isComplete()) return;
        if (!PublicMeNetwork.isOnline()) return;
        try {
            world = MinecraftServer.getServer()
                .worldServerForDimension(0);
            require(
                Boolean.getBoolean("futa.uiSmoke") && world.getSaveHandler()
                    .getWorldDirectory()
                    .getName()
                    .equals(PublicMeWorldSmoke.saveName()),
                "isolated smoke save only");
            if (++ticks > 1200) throw new AssertionError(
                "native logistics timeout stage=" + stage
                    + " io="
                    + (io == null ? "none"
                        : io.nativeIo()
                            .failure() + " "
                            + io.nativeIo()
                                .diagnostic())
                    + " counts="
                    + (target == null ? "none" : target.count + "/" + target.amount));
            SharedStorage stock = SharedStorageManager.getStorage();
            if (stage == 0) {
                x = (PublicMeNetwork.core().xCoord & ~15) + 4;
                y = PublicMeNetwork.core().yCoord - 24;
                z = (PublicMeNetwork.core().zCoord & ~15) + 4;
                TileEntity persisted = world.getTileEntity(x, y, z - 2);
                restartedCheckpoint = persisted instanceof TileEntityIoNode;
                if (!restartedCheckpoint) {
                    require(world.isAirBlock(x, y, z - 2), "empty persistent checkpoint fixture");
                    world.setBlock(x, y, z - 2, FutaGtnhMod.blockIoNode);
                    sentinel = (TileEntityIoNode) world.getTileEntity(x, y, z - 2);
                    sentinel.getIo()
                        .setMode(ForgeDirection.DOWN, false, TerminalIoConfig.Mode.PULL);
                    sentinel.getIo()
                        .getOutputFilter(ForgeDirection.DOWN)
                        .toggleItem(ItemKey.of(new ItemStack(Items.redstone)));
                    for (int kind = 0; kind < 3; kind++) for (int step = 0; step < 20; step++) sentinel.getIo()
                        .stepRate(kind, 1);
                    sentinel.onIoChanged();
                } else sentinel = (TileEntityIoNode) persisted;
                require(
                    sentinel.getIo()
                        .getIntervalTicks() == 100
                        && sentinel.getIo()
                            .getItemsPerOperation() == 32768
                        && sentinel.getIo()
                            .getFluidPerOperation() == 20000000
                        && sentinel.getIo()
                            .getOutputFilter(ForgeDirection.DOWN)
                            .containsItem(ItemKey.of(new ItemStack(Items.redstone)))
                        && sentinel.getIo()
                            .getMode(ForgeDirection.DOWN, false) == TerminalIoConfig.Mode.PULL,
                    "real chunk checkpoint retains old rates/filter/mode across shutdown and reload");
                require(
                    world.isAirBlock(x, y, z) && world.isAirBlock(x + 1, y, z) && world.isAirBlock(x + 2, y, z),
                    "empty fixture region");
                NBTTagCompound identity = new NBTTagCompound();
                identity.setString(
                    "nativeIoSmoke",
                    UUID.randomUUID()
                        .toString());
                ItemStack prototype = new ItemStack(Items.diamond);
                prototype.setTagCompound(identity);
                item = ItemKey.of(prototype);
                fluid = FluidKey.of(new FluidStack(FluidRegistry.WATER, 1, (NBTTagCompound) identity.copy()));
                target = new Fixture();
                target.item = item;
                target.fluid = fluid;
                target.count = 47;
                target.amount = 2500;
                world.setBlock(x + 1, y, z, FutaGtnhMod.blockSharedTerminal);
                world.setTileEntity(x + 1, y, z, target);
                world.setBlock(x, y, z, FutaGtnhMod.blockIoNode);
                io = (TileEntityIoNode) world.getTileEntity(x, y, z);
                NBTTagCompound old = new NBTTagCompound();
                io.writeToNBT(old);
                old.removeTag("nativeIo");
                TerminalIoConfig config = new TerminalIoConfig();
                config.setMode(ForgeDirection.EAST, false, TerminalIoConfig.Mode.PULL);
                config.setMode(ForgeDirection.EAST, true, TerminalIoConfig.Mode.PULL);
                old.setTag("io", config.writeToNbt());
                io.readFromNBT(old);
                stage = 1;
                return;
            }
            require(
                io.nativeIo()
                    .failure()
                    .isEmpty(),
                "native worker not paused: " + io.nativeIo()
                    .failure());
            if (stage == 1) {
                if (target.count != 0 || target.amount != 0) return;
                require(
                    io.nativeIo()
                        .nativePartCount() == 2,
                    "two distinct native import parts");
                require(
                    stock.getItemAmount(item) == 47 && stock.getFluidAmount(fluid) == 2500,
                    "legacy IO -> actual ME network conserves stock (native priority may choose ordinary cells): pool="
                        + stock.getItemAmountPool(item)
                        + "/"
                        + stock.getFluidAmountPool(fluid)
                        + " network="
                        + stock.getItemAmount(item)
                        + "/"
                        + stock.getFluidAmount(fluid));
                SupplySource endpoint = SupplySource.at(SupplySource.IO, io, ForgeDirection.EAST);
                require(
                    SupplyTracker.LEDGER.snapshot(item, 10, SupplyTracker.minute()).rows.size() == 1
                        && SupplyTracker.LEDGER.snapshot(item, 10, SupplyTracker.minute()).rows.get(0).source.id()
                            .equals(endpoint.id()),
                    "original production IO identity retained by actual native part");
                require(
                    SupplyTracker.LEDGER.snapshot(item, 10, SupplyTracker.minute()).incoming == 47,
                    "native imports recorded once");
                require(
                    SupplyTracker.LEDGER.snapshot(fluid, 10, SupplyTracker.minute()).incoming == 2500,
                    "native fluid imports recorded once");
                io.getIo()
                    .setMode(ForgeDirection.EAST, false, TerminalIoConfig.Mode.PUSH);
                io.getIo()
                    .setMode(ForgeDirection.EAST, true, TerminalIoConfig.Mode.PUSH);
                io.onIoChanged();
                wait = 0;
                stage = 2;
                return;
            }
            if (stage == 2) {
                if (++wait < 30) return;
                require(target.count == 0 && target.amount == 0, "empty legacy whitelist denies native export");
                io.getIo()
                    .getOutputFilter(ForgeDirection.EAST)
                    .toggleItem(item);
                io.getIo()
                    .getOutputFilter(ForgeDirection.EAST)
                    .toggleFluid(fluid);
                io.onIoChanged();
                stage = 3;
                return;
            }
            if (stage == 3) {
                if (target.count != 47 || target.amount != 2500) return;
                require(
                    stock.getItemAmount(item) == 0 && stock.getFluidAmount(fluid) == 0,
                    "actual native exports conserve both resources");
                require(
                    SupplyTracker.LEDGER.snapshot(item, 10, SupplyTracker.minute()).outgoing == 47,
                    "native item export once");
                require(
                    SupplyTracker.LEDGER.snapshot(fluid, 10, SupplyTracker.minute()).outgoing == 2500,
                    "native fluid export once");
                NBTTagCompound saved = new NBTTagCompound();
                io.writeToNBT(saved);
                io.invalidate();
                io.readFromNBT(saved);
                io.validate();
                require(
                    io.getIo()
                        .matchesOutput(ForgeDirection.EAST, item)
                        && io.getIo()
                            .matchesOutput(ForgeDirection.EAST, fluid),
                    "native-part save/reload preserves legacy per-face filters");
                io.getIo()
                    .setMode(ForgeDirection.EAST, false, TerminalIoConfig.Mode.OFF);
                io.getIo()
                    .setMode(ForgeDirection.EAST, true, TerminalIoConfig.Mode.OFF);
                io.onIoChanged();
                world.setBlock(x + 2, y, z, FutaGtnhMod.blockStorageBus);
                bus = (TileEntityStorageBus) world.getTileEntity(x + 2, y, z);
                bus.addFace(ForgeDirection.WEST);
                wait = 0;
                stage = 4;
                return;
            }
            if (stage == 4) {
                PartStorageBus items = bus.nativeBus()
                    .part(ForgeDirection.WEST, false),
                    fluids = bus.nativeBus()
                        .part(ForgeDirection.WEST, true);
                if (items == null || fluids == null
                    || !items.getProxy()
                        .isActive()
                    || !fluids.getProxy()
                        .isActive()
                    || ++wait < 30) return;
                require(
                    bus.failure()
                        .isEmpty(),
                    "native storage bus active");
                require(
                    stock.getItemAmount(item) == 47 && stock.getFluidAmount(fluid) == 2500,
                    "both native external storage channels published");
                require(world.isAirBlock(x + 1, y, z + 1), "empty alias fixture region");
                world.setBlock(x + 1, y, z + 1, FutaGtnhMod.blockStorageBus);
                alias = (TileEntityStorageBus) world.getTileEntity(x + 1, y, z + 1);
                alias.addFace(ForgeDirection.NORTH);
                wait = 0;
                stage = 41;
                return;
            }
            if (stage == 41) {
                if (++wait < 40) return;
                require(
                    stock.getItemAmount(item) == 47 && stock.getFluidAmount(fluid) == 2500,
                    "two native legacy aliases publish one physical target once");
                require(
                    PublicMeNetwork.monitor(AEItemStackType.ITEM_STACK_TYPE)
                        .extractItems(
                            AEItemStack.create(item.prototype(7)),
                            Actionable.MODULATE,
                            PublicMeNetwork.actionSource())
                        .getStackSize() == 7,
                    "native item storage adaptor extraction");
                require(
                    PublicMeNetwork.monitor(AEFluidStackType.FLUID_STACK_TYPE)
                        .extractItems(
                            AEFluidStack.create(fluid.prototype(300)),
                            Actionable.MODULATE,
                            PublicMeNetwork.actionSource())
                        .getStackSize() == 300,
                    "native fluid storage adaptor extraction");
                require(target.count == 40 && target.amount == 2200, "external actual ownership matches extraction");
                wait = 0;
                stage = 42;
                return;
            }
            if (stage == 42) {
                if (++wait < 40) return;
                require(
                    stock.getItemAmount(item) == 40 && stock.getFluidAmount(fluid) == 2200,
                    "duplicate native monitor deltas remain exact after actual extraction");
                bus.getConfig(ForgeDirection.WEST)
                    .setAccess(com.futa_gtnh.storagebus.StorageBusConfig.AccessMode.WRITE_ONLY);
                alias.getConfig(ForgeDirection.NORTH)
                    .setAccess(com.futa_gtnh.storagebus.StorageBusConfig.AccessMode.WRITE_ONLY);
                bus.onConfigChanged(ForgeDirection.WEST);
                alias.onConfigChanged(ForgeDirection.NORTH);
                wait = 0;
                stage = 43;
                return;
            }
            if (stage == 43) {
                if (++wait < 30) return;
                require(
                    stock.getItemAmount(item) == 0 && stock.getFluidAmount(fluid) == 0,
                    "native write-only buses keep both physical channels hidden");
                require(
                    PublicMeNetwork.monitor(AEItemStackType.ITEM_STACK_TYPE)
                        .injectItems(
                            AEItemStack.create(item.prototype(3)),
                            Actionable.MODULATE,
                            PublicMeNetwork.actionSource())
                        == null,
                    "native write-only item bus accepts an actual insertion");
                require(
                    PublicMeNetwork.monitor(AEFluidStackType.FLUID_STACK_TYPE)
                        .injectItems(
                            AEFluidStack.create(fluid.prototype(200)),
                            Actionable.MODULATE,
                            PublicMeNetwork.actionSource())
                        == null,
                    "native write-only fluid bus accepts an actual insertion");
                require(
                    target.count == 43 && target.amount == 2400
                        && stock.getItemAmountPool(item) == 0
                        && stock.getFluidAmountPool(fluid) == 0,
                    "actual insertion reaches the native external adaptor, not the pool allocator");
                bus.getConfig(ForgeDirection.WEST)
                    .setAccess(com.futa_gtnh.storagebus.StorageBusConfig.AccessMode.READ_WRITE);
                alias.getConfig(ForgeDirection.NORTH)
                    .setAccess(com.futa_gtnh.storagebus.StorageBusConfig.AccessMode.READ_WRITE);
                bus.onConfigChanged(ForgeDirection.WEST);
                alias.onConfigChanged(ForgeDirection.NORTH);
                wait = 0;
                stage = 44;
                return;
            }
            if (stage == 44) {
                if (++wait < 30) return;
                require(
                    stock.getItemAmount(item) == 43 && stock.getFluidAmount(fluid) == 2400,
                    "restoring native read access exposes unique current contents");
                require(
                    PublicMeNetwork.monitor(AEItemStackType.ITEM_STACK_TYPE)
                        .extractItems(
                            AEItemStack.create(item.prototype(3)),
                            Actionable.MODULATE,
                            PublicMeNetwork.actionSource())
                        .getStackSize() == 3,
                    "native item inserted stock can be withdrawn exactly once");
                require(
                    PublicMeNetwork.monitor(AEFluidStackType.FLUID_STACK_TYPE)
                        .extractItems(
                            AEFluidStack.create(fluid.prototype(200)),
                            Actionable.MODULATE,
                            PublicMeNetwork.actionSource())
                        .getStackSize() == 200,
                    "native fluid inserted stock can be withdrawn exactly once");
                bus.getConfig(ForgeDirection.WEST)
                    .setItemFilter(0, ItemKey.of(new ItemStack(Items.coal)));
                bus.onConfigChanged(ForgeDirection.WEST);
                alias.getConfig(ForgeDirection.NORTH)
                    .setItemFilter(0, ItemKey.of(new ItemStack(Items.coal)));
                alias.onConfigChanged(ForgeDirection.NORTH);
                wait = 0;
                stage = 5;
                return;
            }
            if (stage == 5) {
                if (++wait < 30) return;
                require(
                    stock.getItemAmount(item) == 0 && stock.getFluidAmount(fluid) == 2200,
                    "legacy channel-specific filters remain independent");
                require(
                    PublicMeNetwork.monitor(AEItemStackType.ITEM_STACK_TYPE)
                        .extractItems(
                            AEItemStack.create(item.prototype()),
                            Actionable.MODULATE,
                            PublicMeNetwork.actionSource())
                        == null,
                    "native bus refuses filtered extraction");
                stock.setItemLimit(item, 29);
                stock.setFluidLimit(fluid, 1500);
                io.getIo()
                    .setMode(ForgeDirection.EAST, false, TerminalIoConfig.Mode.PULL);
                io.getIo()
                    .setMode(ForgeDirection.EAST, true, TerminalIoConfig.Mode.PULL);
                io.onIoChanged();
                wait = 0;
                stage = 6;
                return;
            }
            if (stage == 6) {
                if (++wait < 80) return;
                require(
                    stock.getItemAmountPool(item) == 29 && stock.getFluidAmountPool(fluid) == 1500
                        && target.count == 11
                        && target.amount == 700,
                    "native import fills exact old caps without double-counting its escrow or writing back to the source storage bus");
                io.getIo()
                    .setMode(ForgeDirection.EAST, false, TerminalIoConfig.Mode.OFF);
                io.getIo()
                    .setMode(ForgeDirection.EAST, true, TerminalIoConfig.Mode.OFF);
                io.onIoChanged();
                PublicMeNetwork.monitor(AEItemStackType.ITEM_STACK_TYPE)
                    .extractItems(
                        AEItemStack.create(item.prototype(29)),
                        Actionable.MODULATE,
                        PublicMeNetwork.actionSource());
                PublicMeNetwork.monitor(AEFluidStackType.FLUID_STACK_TYPE)
                    .extractItems(
                        AEFluidStack.create(fluid.prototype(2200)),
                        Actionable.MODULATE,
                        PublicMeNetwork.actionSource());
                stock.setItemLimit(item, SharedStorage.MAX_AMOUNT);
                stock.setFluidLimit(fluid, SharedStorage.MAX_AMOUNT);
                world.setBlockToAir(x + 1, y, z + 1);
                world.setBlockToAir(x + 2, y, z);
                world.setBlockToAir(x + 1, y, z);
                int meta = -1;
                for (int id = 1; id < gregtech.api.GregTechAPI.METATILEENTITIES.length; id++) {
                    Object candidate = gregtech.api.GregTechAPI.METATILEENTITIES[id];
                    if (candidate != null
                        && candidate.getClass() == gregtech.api.metatileentity.implementations.MTEHatchInputBus.class) {
                        meta = id;
                        break;
                    }
                }
                require(meta > 0, "registered real GT input bus");
                world.setBlock(x + 1, y, z, gregtech.api.GregTechAPI.sBlockMachines, 0, 3);
                gtBus = (gregtech.api.metatileentity.BaseMetaTileEntity) world.getTileEntity(x + 1, y, z);
                gtBus.setInitialValuesAsNBT(new NBTTagCompound(), (short) meta);
                gtBus.setFrontFacing(ForgeDirection.WEST);
                stock.poolInsertItem(item, 23, true);
                io.getIo()
                    .setMode(ForgeDirection.EAST, false, TerminalIoConfig.Mode.PUSH);
                io.onIoChanged();
                wait = 0;
                stage = 7;
                return;
            }
            if (stage == 7) {
                if (++wait < 80) return;
                long delivered = 0;
                for (int slot = 0; slot < gtBus.getSizeInventory(); slot++) {
                    ItemStack actual = gtBus.getStackInSlot(slot);
                    if (actual != null && item.equals(ItemKey.of(actual))) delivered += actual.stackSize;
                }
                require(
                    delivered == 23 && stock.getItemAmountPool(item) == 0,
                    "native AE export uses real GT ItemIO/side permissions and consumes exactly 23 tagged ingredients");
                for (int slot = 0; slot < gtBus.getSizeInventory(); slot++) gtBus.setInventorySlotContents(slot, null);
                world.setBlockToAir(x, y, z);
                world.setBlockToAir(x + 1, y, z);
                require(
                    !stock.hasPendingItemTransfer(item) && !stock.hasPendingFluidTransfer(fluid),
                    "no successful transfer leaves escrow");
                require(
                    sentinel.nativeIo()
                        .failure()
                        .isEmpty()
                        && sentinel.nativeIo()
                            .nativePartCount() == 1,
                    "persistent checkpoint actually reconnects its native import worker");
                NBTTagCompound checkpoint = new NBTTagCompound();
                sentinel.writeToNBT(checkpoint);
                require(
                    checkpoint.getCompoundTag("nativeIo")
                        .getCompoundTag("parts")
                        .hasKey("0:0", 10),
                    "native AE part state is included in the Minecraft tile save");
                if (restartedCheckpoint) System.out.println(
                    "NATIVE_LOGISTICS_RESTART_CHECKPOINT_SUCCESS actual Minecraft chunk reload + sealed native part NBT + original filters/modes/max rates + native node reconnect");
                recoveryDrop();
                SharedStorageManager.saveNow();
                complete = true;
                System.out.println(
                    "NATIVE_LOGISTICS_SMOKE_SUCCESS actual legacy NBT + native AE scheduler + AE item import/export + AE2FC fluid import/export + native external item/fluid storage buses + exact conservation + old empty whitelist + filters + reload + original IO source identity");
            }
        } catch (Throwable error) {
            failure = "NATIVE_LOGISTICS_SMOKE_FAILED " + error;
            error.printStackTrace();
            System.err.println(failure);
        }
    }

    private void recoveryDrop() {
        int rx = x + 1, rz = z + 1;
        require(world.isAirBlock(rx, y, rz), "empty recovery fixture");
        NBTTagCompound missing = new NBTTagCompound();
        missing.setString("id", "missing:native_io_recovery_fixture");
        missing.setByte("Count", (byte) 37);
        ItemStack carrier = null;
        for (int cycle = 0; cycle < 2; cycle++) {
            world.setBlock(rx, y, rz, FutaGtnhMod.blockSharedTerminal);
            com.futa_gtnh.block.TileEntitySharedTerminal terminal = (com.futa_gtnh.block.TileEntitySharedTerminal) world
                .getTileEntity(rx, y, rz);
            if (carrier == null) terminal.restoreInputBuffer(missing);
            else FutaGtnhMod.blockSharedTerminal.onBlockPlacedBy(world, rx, y, rz, null, carrier);
            require(
                terminal.inputBufferTag()
                    .equals(missing),
                "unregistered owned buffer survives recovery carrier placement");
            require(
                FutaGtnhMod.blockSharedTerminal.getDrops(world, rx, y, rz, 0, 0)
                    .isEmpty(),
                "pre-removal harvest cannot create an extra recovery shell");
            world.setBlockToAir(rx, y, rz);
            require(
                FutaGtnhMod.blockSharedTerminal.getDrops(world, rx, y, rz, 0, 0)
                    .isEmpty(),
                "post-removal harvest cannot create an extra recovery shell");
            int count = 0;
            for (Object value : world.getEntitiesWithinAABB(
                net.minecraft.entity.item.EntityItem.class,
                net.minecraft.util.AxisAlignedBB.getBoundingBox(rx - 1, y - 1, rz - 1, rx + 2, y + 2, rz + 2))) {
                net.minecraft.entity.item.EntityItem entity = (net.minecraft.entity.item.EntityItem) value;
                ItemStack stack = entity.getEntityItem();
                if (entity.isDead
                    || stack.getItem() != net.minecraft.item.Item.getItemFromBlock(FutaGtnhMod.blockSharedTerminal))
                    continue;
                require(
                    stack.hasTagCompound() && stack.getTagCompound()
                        .getCompoundTag("futaInputRecovery")
                        .equals(missing),
                    "one dropped block shell owns the original raw buffer");
                carrier = stack.copy();
                count += stack.stackSize;
                entity.setDead();
            }
            require(
                count == 1,
                "repeated recovery placement/breaking drops exactly one shell and never duplicates the block");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static final class Fixture extends TileEntity implements ISidedInventory, IFluidHandler {

        ItemKey item;
        FluidKey fluid;
        int count, amount;

        @Override
        public int getSizeInventory() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return count == 0 ? null : item.prototype(count);
        }

        @Override
        public ItemStack decrStackSize(int slot, int requested) {
            int taken = Math.min(count, requested);
            count -= taken;
            return taken > 0 ? item.prototype(taken) : null;
        }

        @Override
        public ItemStack getStackInSlotOnClosing(int slot) {
            return null;
        }

        @Override
        public void setInventorySlotContents(int slot, ItemStack stack) {
            require(stack == null || item.equals(ItemKey.of(stack)), "fixture exact item identity");
            count = stack == null ? 0 : stack.stackSize;
        }

        @Override
        public String getInventoryName() {
            return "native-logistics-smoke";
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
        public boolean isUseableByPlayer(EntityPlayer player) {
            return false;
        }

        @Override
        public void openInventory() {}

        @Override
        public void closeInventory() {}

        @Override
        public boolean isItemValidForSlot(int slot, ItemStack stack) {
            return item.equals(ItemKey.of(stack));
        }

        @Override
        public int[] getAccessibleSlotsFromSide(int side) {
            return new int[] { 0 };
        }

        @Override
        public boolean canInsertItem(int slot, ItemStack stack, int side) {
            return isItemValidForSlot(slot, stack);
        }

        @Override
        public boolean canExtractItem(int slot, ItemStack stack, int side) {
            return true;
        }

        @Override
        public int fill(ForgeDirection side, FluidStack stack, boolean execute) {
            if (!fluid.equals(FluidKey.of(stack))) return 0;
            int accepted = Math.min(stack.amount, 10000 - amount);
            if (execute) amount += accepted;
            return accepted;
        }

        @Override
        public FluidStack drain(ForgeDirection side, FluidStack stack, boolean execute) {
            if (stack == null || !fluid.equals(FluidKey.of(stack))) return null;
            return drain(side, stack.amount, execute);
        }

        @Override
        public FluidStack drain(ForgeDirection side, int requested, boolean execute) {
            int taken = Math.min(amount, requested);
            if (execute) amount -= taken;
            return taken > 0 ? fluid.prototype(taken) : null;
        }

        @Override
        public boolean canFill(ForgeDirection side, Fluid value) {
            return true;
        }

        @Override
        public boolean canDrain(ForgeDirection side, Fluid value) {
            return true;
        }

        @Override
        public FluidTankInfo[] getTankInfo(ForgeDirection side) {
            return new FluidTankInfo[] { new FluidTankInfo(amount > 0 ? fluid.prototype(amount) : null, 10000) };
        }
    }
}
