package com.futa_gtnh.me.logistics;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;

import net.minecraft.init.Items;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;

import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.block.TileEntityIoNode;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.me.storage.MEPassThrough;
import appeng.util.InventoryAdaptor;
import appeng.util.inv.AdaptorIInventory;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;

/** Callback contracts, recovery ownership, version/checksum preservation, and all legacy rate/config values. */
public final class NativeLogisticsRegression {

    private static int checks;
    private static SharedStorage stock;

    public static void run() throws Exception {
        Field storage = SharedStorageManager.class.getDeclaredField("storage");
        storage.setAccessible(true);
        Object previous = storage.get(null);
        try {
            stock = new SharedStorage();
            storage.set(null, stock);
            net.minecraft.tileentity.TileEntity.addMapping(TileEntityIoNode.class, "native-logistics-regression-io");
            checkpoints();
            configs();
            selectors();
            items();
            fluids();
            unexpected();
            storageSafety();
            check(
                !NativeStorageRuntime.mayExpose(new com.futa_gtnh.block.TileEntitySharedTerminal()),
                "native bus cannot mirror the shared network back into itself");
            check(
                !NativeStorageRuntime.mayExpose(new TileEntityIoNode()),
                "native bus does not expose a shared IO endpoint");
            check(
                !NativeStorageRuntime.mayExpose(new com.futa_gtnh.storagebus.TileEntityStorageBus()),
                "native bus does not expose another compatibility bus");
            check(
                NativeStorageRuntime.mayExpose(new net.minecraft.tileentity.TileEntity()),
                "ordinary native target remains eligible");
            check(
                NativeStorageRuntime.mayExpose(null),
                "missing native target can initialize later without forced loading");
            buffers();
            creditAndVersion();
        } finally {
            storage.set(null, previous);
        }
        System.out.println(
            "Native logistics regression: " + checks
                + " checks passed (real native adaptors/stacks, partial/rejected/changed callbacks, sealed checkpoints, all legacy rates/configs)");
    }

    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
        checks++;
    }

    private static void checkpoints() throws Exception {
        NBTTagCompound state = new NBTTagCompound();
        state.setTag("parts", new NBTTagCompound());
        NBTTagCompound original = new NBTTagCompound();
        original.setByteArray("bytes", new byte[] { -1, 0, 10 });
        original.setIntArray("ints", new int[] { -100, 42, Integer.MAX_VALUE });
        NBTTagList strings = new NBTTagList();
        strings.appendTag(new NBTTagString("中文原配置"));
        original.setTag("strings", strings);
        state.setTag("legacyConfig", original);
        NativeLogisticsNbt.seal(state);
        NativeLogisticsNbt.validate(state);
        checks++;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(state, bytes);
        NativeLogisticsNbt
            .validate(CompressedStreamTools.readCompressed(new ByteArrayInputStream(bytes.toByteArray())));
        checks++;
        NBTTagCompound corrupt = (NBTTagCompound) state.copy();
        corrupt.getCompoundTag("legacyConfig")
            .setByteArray("bytes", new byte[] { -1, 1, 10 });
        try {
            NativeLogisticsNbt.validate(corrupt);
            throw new AssertionError("checksum accepted corruption");
        } catch (IllegalStateException expected) {
            checks++;
        }
        corrupt = (NBTTagCompound) state.copy();
        corrupt.setInteger("version", 99);
        TileEntityIoNode io = new TileEntityIoNode();
        NBTTagCompound tile = new NBTTagCompound();
        tile.setTag("nativeIo", corrupt);
        io.readFromNBT(tile);
        check(
            !io.nativeIo()
                .failure()
                .isEmpty(),
            "future checkpoint is paused");
        NBTTagCompound saved = new NBTTagCompound();
        io.writeToNBT(saved);
        check(
            saved.getCompoundTag("nativeIo")
                .equals(corrupt),
            "future raw checkpoint is retained without downgrade/rewrite");
    }

    private static void configs() {
        TerminalIoConfig config = new TerminalIoConfig();
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
            for (TerminalIoConfig.Mode mode : TerminalIoConfig.Mode.values()) {
                config.setMode(side, false, mode);
                config.setMode(side, true, mode);
                TerminalIoConfig copy = new TerminalIoConfig();
                copy.readFromNbt(config.writeToNbt());
                check(
                    copy.getMode(side, false) == mode && copy.getMode(side, true) == mode,
                    "all six legacy faces/modes roundtrip");
            }
        for (int kind = 0; kind < 3; kind++) {
            for (int i = 0; i < 20; i++) config.stepRate(kind, -1);
            int count = kind == 0 ? config.getIntervalStepCount()
                : kind == 1 ? config.getItemStepCount() : config.getFluidStepCount();
            for (int i = 0; i < count; i++) {
                NBTTagCompound saved = config.writeToNbt();
                TerminalIoConfig copy = new TerminalIoConfig();
                copy.readFromNbt(saved);
                check(
                    copy.getIntervalTicks() == config.getIntervalTicks()
                        && copy.getItemsPerOperation() == config.getItemsPerOperation()
                        && copy.getFluidPerOperation() == config.getFluidPerOperation(),
                    "all native worker legacy rate budgets roundtrip");
                config.stepRate(kind, 1);
            }
        }
        for (int i = 0; i < 100; i++) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("manualSlot", i);
            ItemStack item = new ItemStack(Items.paper);
            item.setTagCompound(tag);
            config.getOutputFilter(ForgeDirection.DOWN)
                .toggleItem(ItemKey.of(item));
        }
        TerminalIoConfig copy = new TerminalIoConfig();
        copy.readFromNbt(config.writeToNbt());
        check(
            copy.getOutputFilter(ForgeDirection.DOWN)
                .getItems()
                .size() == 100,
            "legacy manual list exceeds native nine slots without truncation");
    }

    private static void selectors() {
        MemoryMonitor<IAEItemStack> network = new MemoryMonitor<>(AEItemStackType.ITEM_STACK_TYPE);
        network.identity = AEItemStack.create(new ItemStack(Items.gold_ingot));
        network.amount = 20;
        IAEItemStack manual = AEItemStack.create(new ItemStack(Items.iron_ingot));
        NativeExportConfig<IAEItemStack> selection = new NativeExportConfig<>(
            stack -> true,
            () -> java.util.Collections.singletonList(manual));
        selection.prepare(network, 1);
        check(
            selection.getSizeInventory() == 2 && selection.getAEStackInSlot(0)
                .isSameType(manual),
            "native selector preserves manual-before-ore priority even when the manual resource is temporarily absent");
        IAEItemStack ore = selection.getAEStackInSlot(1);
        network.amount = 19;
        selection.postChange(network, java.util.Collections.singletonList(network.identity), null);
        selection.prepare(network, 1);
        check(selection.getAEStackInSlot(1) == ore, "quantity-only changes retain the cached native selector");
        network.amount = 0;
        selection.postChange(network, java.util.Collections.singletonList(network.identity), null);
        selection.prepare(network, 1);
        check(
            selection.getSizeInventory() == 1 && selection.getAEStackInSlot(0)
                .isSameType(manual),
            "depleted dynamic matches disappear while explicit native ghost configuration remains available");
        selection.close();
    }

    private static void items() {
        InventoryBasic inventory = new InventoryBasic("source", false, 1);
        inventory.setInventorySlotContents(0, new ItemStack(Items.iron_ingot, 12));
        NativeTransferGuard guard = new NativeTransferGuard(null);
        MemoryMonitor<IAEItemStack> network = new MemoryMonitor<>(AEItemStackType.ITEM_STACK_TYPE);
        network.capacity = 7;
        IMEMonitor<IAEItemStack> monitor = guard.monitor(network, AEItemStackType.ITEM_STACK_TYPE, true, false);
        InventoryAdaptor source = guard.adaptor(new AdaptorIInventory(inventory));
        ItemStack removed = source.removeItems(12, null, null);
        IAEItemStack rest = monitor.injectItems(AEItemStack.create(removed), Actionable.MODULATE, null);
        check(rest != null && rest.getStackSize() == 5, "partial native insertion reports exact rejected ownership");
        source.addItems(rest.getItemStack());
        guard.finish();
        check(
            network.amount == 7 && inventory.getStackInSlot(0).stackSize == 5,
            "native adaptor partial rollback conserves twelve");
        check(!stock.hasPendingItemTransfer(ItemKey.of(removed)), "successful partial rollback releases escrow");
        inventory.setInventorySlotContents(0, new ItemStack(Items.gold_ingot, 12));
        network = new MemoryMonitor<>(AEItemStackType.ITEM_STACK_TYPE);
        network.capacity = 7;
        monitor = guard.monitor(network, AEItemStackType.ITEM_STACK_TYPE, true, false);
        removed = source.removeItems(12, null, null);
        rest = monitor.injectItems(AEItemStack.create(removed), Actionable.MODULATE, null);
        inventory.setInventorySlotContents(0, new ItemStack(Items.coal, 64));
        check(source.addItems(rest.getItemStack()).stackSize == 5, "source may refuse native rollback");
        guard.finish();
        check(
            network.amount == 7 && stock.getItemAmountPool(ItemKey.of(removed)) == 5,
            "rejected rollback goes to durable native recovery, never disappears");
    }

    private static void fluids() {
        NativeTransferGuard guard = new NativeTransferGuard(null);
        MemoryMonitor<IAEFluidStack> network = new MemoryMonitor<>(AEFluidStackType.FLUID_STACK_TYPE);
        network.capacity = 12;
        IMEMonitor<IAEFluidStack> monitor = guard.monitor(network, AEFluidStackType.FLUID_STACK_TYPE, true, true);
        final FluidTank source = new FluidTank(new FluidStack(FluidRegistry.WATER, 20), 100) {

            @Override
            public FluidStack drain(int amount, boolean execute) {
                return super.drain(execute ? Math.min(7, amount) : amount, execute);
            }
        };
        net.minecraftforge.fluids.IFluidHandler tank = new net.minecraftforge.fluids.IFluidHandler() {

            public FluidStack drain(ForgeDirection side, FluidStack stack, boolean execute) {
                return source.drain(stack.amount, execute);
            }

            public FluidStack drain(ForgeDirection side, int amount, boolean execute) {
                return source.drain(amount, execute);
            }

            public int fill(ForgeDirection side, FluidStack stack, boolean execute) {
                return source.fill(stack, execute);
            }

            public boolean canFill(ForgeDirection side, net.minecraftforge.fluids.Fluid fluid) {
                return true;
            }

            public boolean canDrain(ForgeDirection side, net.minecraftforge.fluids.Fluid fluid) {
                return true;
            }

            public net.minecraftforge.fluids.FluidTankInfo[] getTankInfo(ForgeDirection side) {
                return new net.minecraftforge.fluids.FluidTankInfo[] { source.getInfo() };
            }
        };
        check(
            monitor.injectItems(AEFluidStack.create(new FluidStack(FluidRegistry.WATER, 12)), Actionable.MODULATE, null)
                == null && network.amount == 0,
            "AE2FC pre-drain injection only reserves capacity");
        FluidStack actual = guard.fluid(tank)
            .drain(ForgeDirection.UP, new FluidStack(FluidRegistry.WATER, 12), true);
        guard.finish();
        check(
            actual.amount == 7 && source.getFluidAmount() == 13 && network.amount == 7,
            "short actual drain cannot duplicate the simulated twelve");
        check(!stock.hasPendingFluidTransfer(FluidKey.of(actual)), "known partial fluid result releases escrow");
    }

    private static void unexpected() {
        InventoryBasic inventory = new InventoryBasic("changed", false, 1) {

            @Override
            public ItemStack decrStackSize(int slot, int amount) {
                super.decrStackSize(slot, amount);
                return new ItemStack(Items.diamond, 3);
            }
        };
        inventory.setInventorySlotContents(0, new ItemStack(Items.emerald, 8));
        NativeTransferGuard guard = new NativeTransferGuard(null);
        try {
            guard.adaptor(new AdaptorIInventory(inventory))
                .removeItems(8, null, null);
            throw new AssertionError("changed resource accepted silently");
        } catch (IllegalStateException expected) {
            checks++;
        }
        check(
            !stock.hasPendingItemTransfer(ItemKey.of(new ItemStack(Items.emerald)))
                && stock.hasPendingItemTransfer(ItemKey.of(new ItemStack(Items.diamond))),
            "unexpected known return retains actual resource, never the simulated resource");
    }

    private static void storageSafety() {
        com.futa_gtnh.storagebus.TileEntityStorageBus tile = new com.futa_gtnh.storagebus.TileEntityStorageBus();
        IAEItemStack stack = AEItemStack.create(new ItemStack(Items.blaze_rod, 8));
        ItemKey key = ItemKey.fromNative(stack);
        check(
            NativeBusSafety.transfer(tile, stack, Actionable.MODULATE, true, () -> null) == null
                && !stock.hasPendingItemTransfer(key),
            "ordinary native bus result releases its temporary claim without creating stock");
        try {
            NativeBusSafety.transfer(
                tile,
                stack,
                Actionable.MODULATE,
                false,
                () -> { throw new IllegalStateException("foreign callback changed then threw"); });
            throw new AssertionError("callback failure swallowed");
        } catch (IllegalStateException expected) {
            checks++;
        }
        check(
            stock.hasPendingItemTransfer(key) && !tile.failure()
                .isEmpty(),
            "unknown native storage-bus callback retains escrow and isolates the endpoint");
        NBTTagCompound data = new NBTTagCompound();
        tile.nativeBus()
            .write(data);
        com.futa_gtnh.storagebus.TileEntityStorageBus copy = new com.futa_gtnh.storagebus.TileEntityStorageBus();
        copy.nativeBus()
            .read(data);
        check(
            !copy.failure()
                .isEmpty(),
            "uncertain storage bus does not reconnect or replay after restart");
    }

    private static void buffers() {
        net.minecraft.tileentity.TileEntity
            .addMapping(com.futa_gtnh.block.TileEntitySharedTerminal.class, "native-logistics-regression-terminal");
        com.futa_gtnh.block.TileEntitySharedTerminal terminal = new com.futa_gtnh.block.TileEntitySharedTerminal();
        NBTTagCompound owned = new ItemStack(Items.apple, 200).writeToNBT(new NBTTagCompound());
        owned.setInteger("futaOwnedCount", 200);
        terminal.restoreInputBuffer(owned);
        NBTTagCompound tile = new NBTTagCompound();
        terminal.writeToNBT(tile);
        com.futa_gtnh.block.TileEntitySharedTerminal restored = new com.futa_gtnh.block.TileEntitySharedTerminal();
        restored.readFromNBT(tile);
        check(
            restored.getStackInSlot(0).stackSize == 200,
            "physical terminal remainder persists an integer quantity without byte-count truncation");
        NBTTagCompound missing = new NBTTagCompound();
        missing.setString("id", "missing:owned_item");
        missing.setByte("Count", (byte) 37);
        missing.setShort("Damage", (short) 6);
        terminal.restoreInputBuffer(missing);
        tile = new NBTTagCompound();
        terminal.writeToNBT(tile);
        restored.readFromNBT(tile);
        check(
            restored.inputBufferTag()
                .equals(missing) && !restored.isItemValidForSlot(0, new ItemStack(Items.apple)),
            "unregistered owned remainder retains original NBT and blocks overwrite");
    }

    private static void creditAndVersion() {
        ItemKey key = ItemKey.of(new ItemStack(Items.redstone));
        stock.setItemLimit(key, 10);
        SharedStorage.OwnedTransfer held = stock.holdOwnedItemTransfer(key, 10);
        check(stock.nativeItemCeiling(key, false) == 0, "another operation cannot borrow reserved space");
        check(
            com.futa_gtnh.ae2.PublicStorageAccess
                .withOwnedTransferCredit(key, 10, () -> stock.nativeItemCeiling(key, false)) == 10,
            "current native injection does not double count its own escrow");
        held.setOwnedAmount(0);
        held.finish(0);
        stock.enableNativeLogistics();
        NBTTagCompound saved = stock.writeToNbt();
        check(
            saved.getInteger("version") == 4 && saved.getBoolean("nativeLogistics"),
            "previous executor is guarded out of upgraded world");
        SharedStorage copy = new SharedStorage();
        copy.readFromNbt(saved);
        check(
            copy.writeToNbt()
                .getInteger("version") == 4,
            "downgrade guard survives restart");
        saved.removeTag("nativeLogistics");
        copy.readFromNbt(saved);
        check(
            copy.writeToNbt()
                .getInteger("version") == 4,
            "an established v4 ownership barrier cannot silently downgrade when its redundant flag is absent");
    }

    private static final class MemoryMonitor<T extends IAEStack<T>> extends MEPassThrough<T> implements IMEMonitor<T> {

        final IAEStackType<T> type;
        long amount, capacity = Long.MAX_VALUE;
        T identity;

        MemoryMonitor(IAEStackType<T> type) {
            super(null, type);
            this.type = type;
        }

        @Override
        public T injectItems(T stack, Actionable mode, BaseActionSource source) {
            long accepted = Math.min(stack.getStackSize(), Math.max(0, capacity - amount));
            if (mode == Actionable.MODULATE) {
                amount += accepted;
                identity = stack.copy();
            }
            return accepted == stack.getStackSize() ? null
                : stack.copy()
                    .setStackSize(stack.getStackSize() - accepted);
        }

        @Override
        public T extractItems(T request, Actionable mode, BaseActionSource source) {
            long taken = Math.min(amount, request.getStackSize());
            if (mode == Actionable.MODULATE) amount -= taken;
            return taken == 0 ? null
                : request.copy()
                    .setStackSize(taken);
        }

        @Override
        public IItemList<T> getStorageList() {
            IItemList<T> list = type.createList();
            if (identity != null && amount > 0) list.add(
                identity.copy()
                    .setStackSize(amount));
            return list;
        }

        @Override
        public void addListener(IMEMonitorHandlerReceiver listener, Object token) {}

        @Override
        public void removeListener(IMEMonitorHandlerReceiver listener) {}
    }
}
