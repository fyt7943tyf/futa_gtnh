package com.futa_gtnh.ae2;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.me.logistics.NativeBusDedup;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.supply.SupplySource;
import com.futa_gtnh.supply.SupplyTracker;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.config.IncludeExclude;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.security.ReshuffleActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.IGridProxyable;
import appeng.me.storage.MEInventoryHandler;
import appeng.me.storage.StorageBusInventoryHandler;
import appeng.parts.AEBasePart;
import appeng.parts.misc.PartStorageBus;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStackType;
import appeng.util.item.NetworkItemList;
import appeng.util.item.PrioritizedNetworkItemList;
import appeng.util.prioitylist.IPartitionList;

/** Preserve cross-network inlet identity on real operations; queries and priority-list enumeration are unchanged. */
public final class AeSupplyTracking {

    private static final ThreadLocal<SupplySource> INLET = new ThreadLocal<>();
    private static final Map<BaseActionSource, CachedSource[]> SOURCES = new WeakHashMap<>();

    private AeSupplyTracking() {}

    /** Native-constructor fixture entry; production wraps the already constructed native handler. */
    @SuppressWarnings("unchecked")
    public static <T extends IAEStack<T>> MEInventoryHandler<T> bus(IMEInventory<T> inventory, IAEStackType<T> type,
        Supplier<AccessRestriction> access, PartStorageBus part) {
        try {
            MEInventoryHandler<T> nativeHandler;
            try {
                nativeHandler = (MEInventoryHandler<T>) StorageBusInventoryHandler.class
                    .getConstructor(IMEInventory.class, IAEStackType.class, Supplier.class)
                    .newInstance(inventory, type, access);
            } catch (NoSuchMethodException oldApi) {
                nativeHandler = (MEInventoryHandler<T>) StorageBusInventoryHandler.class
                    .getConstructor(IMEInventory.class, IAEStackType.class)
                    .newInstance(inventory, type);
            }
            return wrap(nativeHandler, type, part);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Unsupported native storage bus API", error);
        }
    }

    public static <T extends IAEStack<T>> MEInventoryHandler<T> wrap(MEInventoryHandler<T> inventory,
        IAEStackType<T> type, PartStorageBus part) {
        if (SharedStorageManager.isServerThread()) SOURCES.clear();
        return new TrackedBus<>(inventory, type, part);
    }

    public static boolean isTrackingHandler(IMEInventory<?> inventory) {
        return inventory instanceof TrackedBus;
    }

    public static void record(Object key, long amount, boolean incoming, BaseActionSource action, IGrid mainGrid) {
        if (!SupplyTracker.enabled() || amount <= 0 || action instanceof ReshuffleActionSource) return;
        SupplySource source = INLET.get();
        if (source == null) source = resolve(action, mainGrid, key);
        SupplyTracker.record(key, source, amount, incoming);
    }

    private static SupplySource resolve(BaseActionSource action, IGrid mainGrid, Object key) {
        if (!SharedStorageManager.isServerThread() || action == null) return SupplySource.UNIDENTIFIED;
        int channel = key instanceof ItemKey ? 0 : key instanceof FluidKey ? 1 : 2;
        IAEStackType<?> stackType = channel == 0 ? AEItemStackType.ITEM_STACK_TYPE
            : channel == 1 ? AEFluidStackType.FLUID_STACK_TYPE : null;
        IGrid origin = null;
        if (action instanceof MachineSource) {
            try {
                IActionHost host = ((MachineSource) action).via;
                IGridNode node = host == null ? null : host.getActionableNode();
                origin = node == null ? null : node.getGrid();
            } catch (RuntimeException ignored) {}
        }
        CachedSource[] channels = SOURCES.get(action);
        CachedSource cache = channels == null ? null : channels[channel];
        if (cache != null && cache.main.get() == mainGrid
            && cache.origin.get() == origin
            && SupplyTracker.clock() - cache.time < 30_000L) return cache.source;
        SupplySource source = SupplySource.UNIDENTIFIED;
        try {
            if (action instanceof PlayerSource) {
                net.minecraft.entity.player.EntityPlayer player = ((PlayerSource) action).player;
                if (player != null)
                    source = new SupplySource(SupplySource.PLAYER, 0, 0, 0, 0, -1, player.getCommandSenderName());
            } else if (action instanceof MachineSource) {
                IActionHost host = ((MachineSource) action).via;
                source = sourceFor(host);
                IGridNode node = host == null ? null : host.getActionableNode();
                IGrid grid = node == null ? null : node.getGrid();
                if (grid != null && mainGrid != null && grid != mainGrid) {
                    // Some AE/GT optimized requests address the parent inventory directly. Resolve only the
                    // requester's own subnet, with a hard visit budget and a 30s cache, never the entire base.
                    SupplySource candidate = null;
                    int visited = 0;
                    boolean ambiguous = false;
                    outer: for (Class<? extends IGridHost> type : grid.getMachinesClasses()) {
                        if (++visited > 64) {
                            ambiguous = true;
                            break;
                        }
                        if (!PartStorageBus.class.isAssignableFrom(type)) continue;
                        for (IGridNode busNode : grid.getMachines(type)) {
                            if (++visited > 64) {
                                ambiguous = true;
                                break outer;
                            }
                            PartStorageBus bus = (PartStorageBus) busNode.getMachine();
                            if (stackType != null && bus.getStackType() != stackType) continue;
                            TileEntity tile = bus.getHost()
                                .getTile();
                            ForgeDirection side = bus.getSide();
                            if (tile == null || tile.getWorldObj() == null
                                || !tile.getWorldObj()
                                    .blockExists(
                                        tile.xCoord + side.offsetX,
                                        tile.yCoord + side.offsetY,
                                        tile.zCoord + side.offsetZ))
                                continue;
                            if (bus.getConnectedGrid() != mainGrid) continue;
                            if (candidate != null) {
                                ambiguous = true;
                                break outer;
                            }
                            candidate = SupplySource.at(SupplySource.AE_BUS, tile, side);
                        }
                    }
                    if (!ambiguous && candidate != null) source = candidate;
                }
            }
        } catch (RuntimeException ignored) {
            // A detached grid/host cannot invalidate a completed inventory transfer.
        }
        if (SOURCES.size() >= 1024) SOURCES.clear();
        channels = SOURCES.computeIfAbsent(action, ignored -> new CachedSource[3]);
        channels[channel] = new CachedSource(mainGrid, origin, source, SupplyTracker.clock());
        return source;
    }

    public static SupplySource sourceFor(IActionHost host) {
        if (host instanceof AEBasePart) {
            AEBasePart part = (AEBasePart) host;
            if (part.getHost() == null) return SupplySource.UNIDENTIFIED;
            return SupplySource.at(
                host instanceof PartStorageBus ? SupplySource.AE_BUS
                    : part.getHost()
                        .getTile() instanceof com.futa_gtnh.block.TileEntityIoNode
                        || part.getHost()
                            .getTile() instanceof com.futa_gtnh.block.TileEntitySharedTerminal ? SupplySource.IO
                                : SupplySource.AE_MACHINE,
                part.getHost()
                    .getTile(),
                part.getSide());
        }
        if (host instanceof TileEntity tile) return SupplySource.at(SupplySource.AE_MACHINE, tile, null);
        if (host instanceof IGridProxyable proxyable) {
            DimensionalCoord location = proxyable.getLocation();
            if (location != null) return new SupplySource(
                SupplySource.AE_MACHINE,
                location.getDimension(),
                location.x,
                location.y,
                location.z,
                -1,
                "");
        }
        return SupplySource.UNIDENTIFIED;
    }

    public static void clear() {
        INLET.remove();
        SOURCES.clear();
    }

    private static final class CachedSource {

        final WeakReference<IGrid> main;
        final WeakReference<IGrid> origin;
        final SupplySource source;
        final long time;

        CachedSource(IGrid grid, IGrid origin, SupplySource source, long time) {
            main = new WeakReference<>(grid);
            this.origin = new WeakReference<>(origin);
            this.source = source;
            this.time = time;
        }
    }

    static final class TrackedBus<T extends IAEStack<T>> extends MEInventoryHandler<T> {

        private final PartStorageBus part;
        private SupplySource source;

        TrackedBus(MEInventoryHandler<T> inventory, IAEStackType<T> type, PartStorageBus part) {
            super(inventory, type);
            this.part = part;
        }

        @SuppressWarnings("unchecked")
        private MEInventoryHandler<T> nativeHandler() {
            return (MEInventoryHandler<T>) super.getInternal();
        }

        // PartStorageBus configures its handler after assigning the field. Keep those policies on the actual
        // native storage-bus handler as well, including its version-specific cross-network enumeration behavior.
        @Override
        public void setBaseAccess(AccessRestriction access) {
            nativeHandler().setBaseAccess(access);
            super.setBaseAccess(access);
        }

        @Override
        public void setWhitelist(IncludeExclude mode) {
            nativeHandler().setWhitelist(mode);
            super.setWhitelist(mode);
        }

        @Override
        public void setPriority(int priority) {
            nativeHandler().setPriority(priority);
            super.setPriority(priority);
        }

        @Override
        public void setPartitionList(IPartitionList<T> list) {
            nativeHandler().setPartitionList(list);
            super.setPartitionList(list);
        }

        @Override
        public void setExtractPartitionList(IPartitionList<T> list) {
            nativeHandler().setExtractPartitionList(list);
            super.setExtractPartitionList(list);
        }

        @Override
        public void setIsExtractFilterActive(boolean enabled) {
            nativeHandler().setIsExtractFilterActive(enabled);
            super.setIsExtractFilterActive(enabled);
        }

        @Override
        public void setSticky(boolean enabled) {
            nativeHandler().setSticky(enabled);
            super.setSticky(enabled);
        }

        @Override
        public boolean isVisible() {
            return nativeHandler().isVisible();
        }

        @Override
        public PrioritizedNetworkItemList<T> getAvailableItemsWithPriority(int iteration) {
            return nativeHandler().getAvailableItemsWithPriority(iteration);
        }

        private SupplySource inlet() {
            try {
                TileEntity tile = part.getHost() == null ? null
                    : part.getHost()
                        .getTile();
                ForgeDirection side = part.getSide();
                if (source == null || source == SupplySource.UNIDENTIFIED
                    || tile == null
                    || tile.getWorldObj() == null
                    || source.x != tile.xCoord
                    || source.y != tile.yCoord
                    || source.z != tile.zCoord
                    || source.dim != tile.getWorldObj().provider.dimensionId
                    || source.side != side.ordinal()) source = SupplySource.at(SupplySource.AE_BUS, tile, side);
                return source;
            } catch (RuntimeException detached) {
                return SupplySource.UNIDENTIFIED;
            }
        }

        private boolean excluded() {
            TileEntity excluded = PublicStorageAccess.excludedTile();
            if (excluded == null || part.getHost() == null) return false;
            TileEntity tile = part.getHost()
                .getTile();
            ForgeDirection side = part.getSide();
            return tile != null && tile.getWorldObj() == excluded.getWorldObj()
                && tile.xCoord + side.offsetX == excluded.xCoord
                && tile.yCoord + side.offsetY == excluded.yCoord
                && tile.zCoord + side.offsetZ == excluded.zCoord;
        }

        @Override
        public IItemList<T> getAvailableItems(IItemList<T> out, int iteration, Optional<Predicate<T>> filter) {
            if (!NativeBusDedup.multiple(part)) return super.getAvailableItems(out, iteration, filter);
            IItemList<T> local = super.getAvailableItems((IItemList<T>) getStackType().createList(), iteration, filter);
            if (local instanceof NetworkItemList) return local;
            for (T stack : local) if (NativeBusDedup.visible(part, stack, iteration)) out.add(stack);
            return out;
        }

        @Override
        public T getAvailableItem(T request, int iteration) {
            T local = super.getAvailableItem(request, iteration);
            return NativeBusDedup.visible(part, local, iteration) ? local : null;
        }

        @Override
        public T extractItems(T request, Actionable mode, BaseActionSource action) {
            if (excluded()) return null;
            return guarded(request, mode, false, stack -> trackedExtraction(stack, mode, action));
        }

        private T trackedExtraction(T request, Actionable mode, BaseActionSource action) {
            if (mode != Actionable.MODULATE || !SupplyTracker.enabled())
                return super.extractItems(request, mode, action);
            SupplySource previous = INLET.get();
            INLET.set(inlet());
            try {
                return super.extractItems(request, mode, action);
            } finally {
                INLET.set(previous);
            }
        }

        @Override
        public T injectItems(T input, Actionable mode, BaseActionSource action) {
            if (PublicStorageAccess.poolOnly() || excluded()) return input;
            return guarded(input, mode, true, stack -> trackedInjection(stack, mode, action));
        }

        private T trackedInjection(T input, Actionable mode, BaseActionSource action) {
            if (mode != Actionable.MODULATE || !SupplyTracker.enabled()) return super.injectItems(input, mode, action);
            SupplySource previous = INLET.get();
            INLET.set(inlet());
            try {
                return super.injectItems(input, mode, action);
            } finally {
                INLET.set(previous);
            }
        }

        private T guarded(T request, Actionable mode, boolean input, Function<T, T> operation) {
            if (part.getHost() != null && part.getHost()
                .getTile() instanceof com.futa_gtnh.storagebus.TileEntityStorageBus tile) {
                T stable = request == null ? null : request.copy();
                return com.futa_gtnh.me.logistics.NativeBusSafety
                    .transfer(tile, stable, mode, input, () -> operation.apply(stable == null ? null : stable.copy()));
            }
            return operation.apply(request);
        }

    }
}
