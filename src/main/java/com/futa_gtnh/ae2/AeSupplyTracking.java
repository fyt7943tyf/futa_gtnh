package com.futa_gtnh.ae2;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.supply.SupplySource;
import com.futa_gtnh.supply.SupplyTracker;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
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
import appeng.me.storage.StorageBusInventoryHandler;
import appeng.parts.AEBasePart;
import appeng.parts.misc.PartStorageBus;

/** Preserve cross-network inlet identity on real operations; queries and priority-list enumeration are unchanged. */
public final class AeSupplyTracking {

    private static final ThreadLocal<SupplySource> INLET = new ThreadLocal<>();
    private static final Map<BaseActionSource, CachedSource> SOURCES = new WeakHashMap<>();

    private AeSupplyTracking() {}

    public static <T extends IAEStack<T>> StorageBusInventoryHandler<T> bus(IMEInventory<T> inventory,
        IAEStackType<T> type, Supplier<AccessRestriction> access, PartStorageBus part) {
        if (SharedStorageManager.isServerThread()) SOURCES.clear(); // Rebuilds, not steady-state transfers.
        return new TrackedBus<>(inventory, type, access, part);
    }

    public static void record(Object key, long amount, boolean incoming, BaseActionSource action, IGrid mainGrid) {
        if (!SupplyTracker.enabled() || amount <= 0 || action instanceof ReshuffleActionSource) return;
        SupplySource source = INLET.get();
        if (source == null) source = resolve(action, mainGrid);
        SupplyTracker.record(key, source, amount, incoming);
    }

    private static SupplySource resolve(BaseActionSource action, IGrid mainGrid) {
        if (!SharedStorageManager.isServerThread() || action == null) return SupplySource.UNIDENTIFIED;
        IGrid origin = null;
        if (action instanceof MachineSource) {
            try {
                IActionHost host = ((MachineSource) action).via;
                IGridNode node = host == null ? null : host.getActionableNode();
                origin = node == null ? null : node.getGrid();
            } catch (RuntimeException ignored) {}
        }
        CachedSource cache = SOURCES.get(action);
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
                source = hostSource(host);
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
        SOURCES.put(action, new CachedSource(mainGrid, origin, source, SupplyTracker.clock()));
        return source;
    }

    private static SupplySource hostSource(IActionHost host) {
        if (host instanceof AEBasePart) {
            AEBasePart part = (AEBasePart) host;
            return SupplySource.at(
                host instanceof PartStorageBus ? SupplySource.AE_BUS : SupplySource.AE_MACHINE,
                part.getHost()
                    .getTile(),
                part.getSide());
        }
        return host instanceof TileEntity ? SupplySource.at(SupplySource.AE_MACHINE, (TileEntity) host, null)
            : SupplySource.UNIDENTIFIED;
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

    static final class TrackedBus<T extends IAEStack<T>> extends StorageBusInventoryHandler<T> {

        private final PartStorageBus part;
        private SupplySource source;

        TrackedBus(IMEInventory<T> inventory, IAEStackType<T> type, Supplier<AccessRestriction> access,
            PartStorageBus part) {
            super(inventory, type, access);
            this.part = part;
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
        public T extractItems(T request, Actionable mode, BaseActionSource action) {
            if (excluded()) return null;
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
            if (mode != Actionable.MODULATE || !SupplyTracker.enabled()) return super.injectItems(input, mode, action);
            SupplySource previous = INLET.get();
            INLET.set(inlet());
            try {
                return super.injectItems(input, mode, action);
            } finally {
                INLET.set(previous);
            }
        }
    }
}
