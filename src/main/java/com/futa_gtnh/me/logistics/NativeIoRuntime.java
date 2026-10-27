package com.futa_gtnh.me.logistics;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.IFluidHandler;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.ae2.PublicStorageAccess;
import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.me.PublicMeNetwork;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.glodblock.github.common.parts.PartFluidExportBus;
import com.glodblock.github.common.parts.PartFluidImportBus;
import com.glodblock.github.loader.ItemAndBlockHolder;

import appeng.api.AEApi;
import appeng.api.config.Upgrades;
import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.StorageName;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.parts.AEBasePart;
import appeng.parts.automation.PartExportBus;
import appeng.parts.automation.PartImportBus;
import appeng.tile.inventory.IAEStackInventory;
import appeng.util.InventoryAdaptor;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStackType;

/** Retains the old six-face policy; the only transport workers are native AE/AE2FC parts. */
public final class NativeIoRuntime {

    private static final int[] IDLE_STEPS = { 4, 8, 16, 32, 64, 128 };
    private final TileEntity tile;
    private final TerminalIoConfig config;
    private final NativePartSet parts;
    private final Map<String, AEBasePart> workers = new LinkedHashMap<>();
    private final Map<String, NativeTransferGuard> guards = new LinkedHashMap<>();
    private AEBasePart executing;
    private java.util.List<String> schedule = java.util.Collections.emptyList();
    private String failure = "";
    private int elapsed;
    private int idleRounds;
    private boolean manualOnly;
    private int modeRevision = Integer.MIN_VALUE;
    private long clockTicks;
    private long workRounds;
    private NBTTagCompound original;
    private NBTTagCompound frozen;
    private boolean prepared;

    public NativeIoRuntime(TileEntity tile, TerminalIoConfig config) {
        this.tile = tile;
        this.config = config;
        parts = new NativePartSet(tile);
    }

    public void update() {
        if (!failure.isEmpty() || tile.getWorldObj() == null
            || tile.getWorldObj().isRemote
            || !PublicMeNetwork.isAttachable()) return;
        try {
            if (!prepared) {
                com.futa_gtnh.shared.SharedStorageManager.requireNativeLogisticsGuard();
                prepared = true;
            }
            if (original == null) {
                original = config.writeToNbt();
                tile.markDirty();
            }
            if (config.getOutputRevision() != modeRevision) {
                synchronizeParts();
                modeRevision = config.getOutputRevision();
            }
            parts.refresh();
        } catch (RuntimeException | LinkageError error) {
            pause(error);
        }
    }

    private void synchronizeParts() {
        if (!config.hasAnyMode()) parts.remove("clock");
        if (config.hasAnyMode() && !parts.contains("clock")) parts.add("clock", ForgeDirection.UNKNOWN, new Clock());
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            for (int kind = 0; kind < 4; kind++) {
                boolean fluid = kind >= 2, input = kind % 2 == 0;
                TerminalIoConfig.Mode mode = config.getMode(side, fluid);
                boolean enabled = input ? mode.pulls() : mode.pushes();
                String id = side.ordinal() + ":" + kind;
                if (!enabled && workers.containsKey(id)) {
                    closeSelector(workers.remove(id));
                    parts.remove(id);
                    guards.remove(id);
                } else if (enabled && !workers.containsKey(id)) {
                    NativeTransferGuard guard = new NativeTransferGuard(this);
                    guards.put(id, guard);
                    AEBasePart part = fluid ? input ? new FluidImport(side, guard) : new FluidExport(side, guard)
                        : input ? new ItemImport(side, guard) : new ItemExport(side, guard);
                    workers.put(id, parts.add(id, side, part));
                }
            }
        }
        schedule = new java.util.ArrayList<>(workers.keySet());
        java.util.Collections.sort(schedule);
    }

    private void run() {
        clockTicks++;
        if (!failure.isEmpty() || tile.isInvalid() || !PublicMeNetwork.isOnline()) return;
        int interval = config.getIntervalTicks();
        boolean full = ++elapsed >= idleWaitTicks(interval, idleRounds);
        if (!full && elapsed % interval != 0) return;
        if (full) elapsed = 0;
        manualOnly = !full;
        boolean moved = false;
        workRounds++;
        for (String id : schedule) {
            if (!failure.isEmpty()) break;
            AEBasePart part = workers.get(id);
            if (!full && (!(part instanceof ItemExport) || config.getOutputFilter(part.getSide())
                .getItems()
                .isEmpty())) continue;
            try {
                TileEntity target = target(part.getSide());
                if (target == null || target instanceof com.futa_gtnh.block.TileEntitySharedTerminal
                    || target instanceof com.futa_gtnh.block.TileEntityIoNode) continue;
                executing = part;
                TickRateModulation result = PublicStorageAccess.withExcludedTarget(
                    target,
                    () -> ((IGridTickable) part).tickingRequest(part.getActionableNode(), config.getIntervalTicks()));
                guards.get(id)
                    .finish();
                moved |= result == TickRateModulation.FASTER || result == TickRateModulation.URGENT;
            } catch (RuntimeException | LinkageError error) {
                pause(error);
            } finally {
                executing = null;
            }
        }
        manualOnly = false;
        if (moved) {
            elapsed = 0;
            idleRounds = 0;
        } else if (full) idleRounds = com.futa_gtnh.Config.ioIdleBackoff ? Math.min(6, idleRounds + 1) : 0;
    }

    static int idleWaitTicks(int interval, int rounds) {
        return rounds <= 0 || !com.futa_gtnh.Config.ioIdleBackoff ? Math.max(1, interval)
            : Math.max(Math.max(1, interval), IDLE_STEPS[Math.min(rounds, IDLE_STEPS.length) - 1]);
    }

    void recovered(appeng.api.storage.data.IAEStack<?> stack, long amount) {
        Object key = stack instanceof IAEItemStack item ? ItemKey.fromNative(item)
            : FluidKey.fromNative((IAEFluidStack) stack);
        com.futa_gtnh.supply.SupplyTracker.record(
            key,
            com.futa_gtnh.supply.SupplySource.at(com.futa_gtnh.supply.SupplySource.IO, tile, executing.getSide()),
            amount,
            true);
    }

    private TileEntity target(ForgeDirection side) {
        int x = tile.xCoord + side.offsetX, y = tile.yCoord + side.offsetY, z = tile.zCoord + side.offsetZ;
        return tile.getWorldObj()
            .getChunkProvider()
            .chunkExists(x >> 4, z >> 4)
                ? tile.getWorldObj()
                    .getTileEntity(x, y, z)
                : null;
    }

    void pause(Throwable error) {
        failure = error.toString();
        tile.markDirty();
        FutaGtnhMod.LOG
            .error("原生 AE 兼容 IO 已暂停；未确认的搬运保留托管，禁止自动重试：{} {} {}", tile.xCoord, tile.yCoord, tile.zCoord, error);
    }

    public String failure() {
        return failure;
    }

    public int nativePartCount() {
        return workers.size();
    }

    public String diagnostic() {
        StringBuilder text = new StringBuilder("clock=" + clockTicks + ", rounds=" + workRounds);
        for (Map.Entry<String, AEBasePart> entry : workers.entrySet()) text.append("; ")
            .append(entry.getKey())
            .append(" active=")
            .append(
                entry.getValue()
                    .getProxy()
                    .isActive())
            .append(" grid=")
            .append(
                entry.getValue()
                    .getActionableNode() != null
                    && entry.getValue()
                        .getActionableNode()
                        .getGrid() == PublicMeNetwork.grid());
        return text.toString();
    }

    public void changed() {
        elapsed = 0;
        idleRounds = 0;
        modeRevision = Integer.MIN_VALUE;
    }

    public void close() {
        for (AEBasePart part : workers.values()) closeSelector(part);
        parts.close();
        workers.clear();
        guards.clear();
        executing = null;
        modeRevision = Integer.MIN_VALUE;
    }

    private void closeSelector(AEBasePart part) {
        if (part instanceof ItemExport item) item.selection.close();
        if (part instanceof FluidExport fluid) fluid.selection.close();
    }

    public void write(NBTTagCompound tileTag) {
        if (frozen != null) {
            tileTag.setTag("nativeIo", frozen.copy());
            return;
        }
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("parts", parts.write());
        if (original != null) tag.setTag("legacyConfig", original.copy());
        if (!failure.isEmpty()) tag.setString("paused", failure);
        tileTag.setTag("nativeIo", NativeLogisticsNbt.seal(tag));
    }

    public void read(NBTTagCompound tileTag) {
        prepared = false;
        close();
        NBTTagCompound tag = tileTag.getCompoundTag("nativeIo");
        try {
            NativeLogisticsNbt.validate(tag);
        } catch (IllegalStateException error) {
            frozen = (NBTTagCompound) tag.copy();
            failure = error.toString();
            return;
        }
        frozen = null;
        parts.read(tag.getCompoundTag("parts"));
        workers.clear();
        guards.clear();
        modeRevision = Integer.MIN_VALUE;
        original = tag.hasKey("legacyConfig", 10) ? (NBTTagCompound) tag.getCompoundTag("legacyConfig")
            .copy() : null;
        failure = tag.getString("paused");
    }

    private ItemStack item(boolean input) {
        return (input ? AEApi.instance()
            .definitions()
            .parts()
            .importBus()
            : AEApi.instance()
                .definitions()
                .parts()
                .exportBus()).maybeStack(1)
                    .get();
    }

    private final class Clock extends AEBasePart implements IGridTickable {

        Clock() {
            super(item(true));
            getProxy().setIdlePowerUsage(0);
        }

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return new TickingRequest(1, 1, false, false);
        }

        @Override
        public TickRateModulation tickingRequest(IGridNode node, int ticks) {
            run();
            return TickRateModulation.SAME;
        }

        @Override
        public void securityBreak() {
            pause(new IllegalStateException("Native IO security break"));
        }
    }

    private final class ItemImport extends PartImportBus {

        private final NativeTransferGuard guard;

        ItemImport(ForgeDirection side, NativeTransferGuard guard) {
            super(item(true));
            this.guard = guard;
        }

        @Override
        public int calculateAmountToSend() {
            return config.getItemsPerOperation();
        }

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return null;
        }

        @Override
        protected int availableSlots() {
            return 0;
        }

        @Override
        protected TickRateModulation doBusWork() {
            return executing == this ? super.doBusWork() : TickRateModulation.SLEEP;
        }

        @Override
        protected Object getTarget() {
            return guard == null ? null : guard.adaptor((InventoryAdaptor) super.getTarget());
        }

        @Override
        protected IMEMonitor<IAEItemStack> getMonitor() {
            return guard.monitor(super.getMonitor(), AEItemStackType.ITEM_STACK_TYPE, true, false);
        }

        @Override
        public int getInstalledUpgrades(Upgrades upgrade) {
            return 0;
        }

        @Override
        public void securityBreak() {
            pause(new IllegalStateException("Native IO security break"));
        }
    }

    private final class ItemExport extends PartExportBus {

        private final NativeTransferGuard guard;
        private final NativeExportConfig<IAEItemStack> selection;

        ItemExport(ForgeDirection side, NativeTransferGuard guard) {
            super(item(false));
            this.guard = guard;
            selection = new NativeExportConfig<>(stack -> config.matchesOutput(side, ItemKey.fromNative(stack)), () -> {
                java.util.List<IAEItemStack> manual = new java.util.ArrayList<>();
                for (ItemKey key : config.getOutputFilter(side)
                    .getItems()) manual.add(appeng.util.item.AEItemStack.create(key.prototype()));
                return manual;
            });
        }

        @Override
        public int calculateAmountToSend() {
            return config.getItemsPerOperation();
        }

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return null;
        }

        @Override
        protected TickRateModulation doBusWork() {
            return executing == this ? super.doBusWork() : TickRateModulation.SLEEP;
        }

        @Override
        protected Object getTarget() {
            return guard == null ? null : guard.adaptor((InventoryAdaptor) super.getTarget());
        }

        @Override
        protected IMEMonitor<IAEItemStack> getMonitor() {
            IMEMonitor<IAEItemStack> monitor = super.getMonitor();
            selection.prepare(monitor, config.getOutputRevision());
            return guard.monitor(monitor, AEItemStackType.ITEM_STACK_TYPE, false, false);
        }

        @Override
        protected int availableSlots() {
            return selection == null ? 0 : manualOnly ? selection.manualCount() : selection.getSizeInventory();
        }

        @Override
        public IAEStackInventory getAEInventoryByName(StorageName name) {
            return name == StorageName.CONFIG && selection != null ? selection : super.getAEInventoryByName(name);
        }

        @Override
        public int getInstalledUpgrades(Upgrades upgrade) {
            return 0;
        }

        @Override
        public void securityBreak() {
            pause(new IllegalStateException("Native IO security break"));
        }
    }

    private final class FluidImport extends PartFluidImportBus {

        private final NativeTransferGuard guard;

        FluidImport(ForgeDirection side, NativeTransferGuard guard) {
            super(new ItemStack(ItemAndBlockHolder.FLUID_IMPORT_BUS));
            this.guard = guard;
        }

        @Override
        public int calculateAmountToSend() {
            return config.getFluidPerOperation();
        }

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return null;
        }

        @Override
        protected int availableSlots() {
            return 0;
        }

        @Override
        protected TickRateModulation doBusWork() {
            return executing == this ? super.doBusWork() : TickRateModulation.SLEEP;
        }

        @Override
        protected Object getTarget() {
            return guard == null ? null : guard.fluid((IFluidHandler) super.getTarget());
        }

        @Override
        protected IMEMonitor<IAEFluidStack> getMonitor() {
            return guard.monitor(super.getMonitor(), AEFluidStackType.FLUID_STACK_TYPE, true, true);
        }

        @Override
        public int getInstalledUpgrades(Upgrades upgrade) {
            return 0;
        }

        @Override
        public void securityBreak() {
            pause(new IllegalStateException("Native IO security break"));
        }
    }

    private final class FluidExport extends PartFluidExportBus {

        private final NativeTransferGuard guard;
        private final NativeExportConfig<IAEFluidStack> selection;

        FluidExport(ForgeDirection side, NativeTransferGuard guard) {
            super(new ItemStack(ItemAndBlockHolder.FLUID_EXPORT_BUS));
            this.guard = guard;
            selection = new NativeExportConfig<>(
                stack -> config.matchesOutput(side, FluidKey.fromNative(stack)),
                () -> {
                    java.util.List<IAEFluidStack> manual = new java.util.ArrayList<>();
                    for (FluidKey key : config.getOutputFilter(side)
                        .getFluids()) manual.add(appeng.util.item.AEFluidStack.create(key.prototype()));
                    return manual;
                });
        }

        @Override
        public int calculateAmountToSend() {
            return config.getFluidPerOperation();
        }

        @Override
        public TickingRequest getTickingRequest(IGridNode node) {
            return null;
        }

        @Override
        protected TickRateModulation doBusWork() {
            return executing == this ? super.doBusWork() : TickRateModulation.SLEEP;
        }

        @Override
        protected Object getTarget() {
            return guard == null ? null : guard.adaptor((InventoryAdaptor) super.getTarget());
        }

        @Override
        protected IMEMonitor<IAEFluidStack> getMonitor() {
            IMEMonitor<IAEFluidStack> monitor = super.getMonitor();
            selection.prepare(monitor, config.getOutputRevision());
            return guard.monitor(monitor, AEFluidStackType.FLUID_STACK_TYPE, false, true);
        }

        @Override
        protected int availableSlots() {
            return selection == null ? 0 : selection.getSizeInventory();
        }

        @Override
        public IAEStackInventory getAEInventoryByName(StorageName name) {
            return name == StorageName.CONFIG && selection != null ? selection : super.getAEInventoryByName(name);
        }

        @Override
        public int getInstalledUpgrades(Upgrades upgrade) {
            return 0;
        }

        @Override
        public void securityBreak() {
            pause(new IllegalStateException("Native IO security break"));
        }
    }
}
