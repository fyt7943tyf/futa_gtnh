package com.futa_gtnh.ae2;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;

import com.futa_gtnh.me.PublicMeNetwork;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageListener;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.shared.StorageNetworkAccess;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;

/**
 * One authoritative ME route. The native cell array publishes actual AE storage; this class provides routing and
 * compatibility views.
 */
public final class PublicStorageAccess implements StorageNetworkAccess {

    private static final PublicStorageAccess INSTANCE = new PublicStorageAccess();
    private static final ThreadLocal<Context> CONTEXT = ThreadLocal.withInitial(Context::new);
    private static final long REPORT_CAP = (1L << 52) - 1;
    private IGrid grid;
    private SharedStorage pool;
    private IMEMonitor<IAEItemStack> itemMonitor;
    private IMEMonitor<IAEFluidStack> fluidMonitor;
    private int itemRevision, fluidRevision;
    private boolean refresh, sendSnapshot, wasOnline;
    private int providerWrites;
    private final java.util.Set<ItemKey> changedItems = new java.util.LinkedHashSet<>();
    private final java.util.Set<FluidKey> changedFluids = new java.util.LinkedHashSet<>();
    private final Listener<IAEItemStack> itemListener = new Listener<>(true);
    private final Listener<IAEFluidStack> fluidListener = new Listener<>(false);
    private final SharedStorageListener poolListener = new SharedStorageListener() {

        @Override
        public void onItemChanged(ItemKey key) {
            if (providerWrites == 0) refresh = true;
        }

        @Override
        public void onFluidChanged(FluidKey key) {
            if (providerWrites == 0) refresh = true;
        }

        @Override
        public void onStorageReplaced() {
            refresh = true;
        }
    };
    private com.futa_gtnh.me.NativeMeCellArray nativeCells;

    private PublicStorageAccess() {}

    private static final class Context {

        boolean manual, direct;
        int routeDepth, queryDepth;
        TileEntity excluded;
        BaseActionSource source;
        Object creditKey;
        long credit;
    }

    public static TileEntity excludedTile() {
        return CONTEXT.get().excluded;
    }

    public static boolean manual() {
        return CONTEXT.get().manual;
    }

    public static boolean poolOnly() {
        return CONTEXT.get().direct;
    }

    public static long ownedTransferCredit(Object key) {
        Context context = CONTEXT.get();
        return key != null && key.equals(context.creditKey) ? context.credit : 0;
    }

    /** A worker's own escrow reserves ownership, not an additional copy of the incoming stock. */
    public static <T> T withOwnedTransferCredit(Object key, long amount, Supplier<T> operation) {
        Context context = CONTEXT.get();
        Object oldKey = context.creditKey;
        long oldCredit = context.credit;
        context.creditKey = key;
        context.credit = amount;
        try {
            return operation.get();
        } finally {
            context.creditKey = oldKey;
            context.credit = oldCredit;
        }
    }

    /** Native compatibility workers may never route a transfer back into its own source/target container. */
    public static <T> T withExcludedTarget(TileEntity target, Supplier<T> operation) {
        return INSTANCE.inContext(false, false, target, operation);
    }

    public static <T> T withPlayer(EntityPlayer player, Supplier<T> operation) {
        if (!PublicMeNetwork.isRunning()) return operation.get();
        Context context = CONTEXT.get();
        BaseActionSource previous = context.source;
        context.source = new PlayerSource(player, PublicMeNetwork.core());
        try {
            return operation.get();
        } finally {
            context.source = previous;
        }
    }

    public static <T> T withSource(BaseActionSource source, Supplier<T> operation) {
        Context context = CONTEXT.get();
        BaseActionSource previous = context.source;
        context.source = source;
        try {
            return operation.get();
        } finally {
            context.source = previous;
        }
    }

    public static void begin() {
        INSTANCE.attachPool();
        if (INSTANCE.grid != null) {
            INSTANCE.refresh = false;
            INSTANCE.grid.postEvent(new MENetworkCellArrayUpdate());
        }
        INSTANCE.changed();
    }

    private void attachPool() {
        SharedStorage current = SharedStorageManager.getStorage();
        if (pool == current) return;
        if (grid != null && nativeCells != null) {
            ((IStorageGrid) grid.getCache(IStorageGrid.class)).unregisterCellProvider(nativeCells);
            nativeCells.onTopologyChanged(null);
            nativeCells.setPublished(false);
        }
        if (pool != null) {
            pool.setNetworkAccess(null);
            pool.removeListener(poolListener);
        }
        pool = current;
        nativeCells = current.nativeCells();
        if (nativeCells != null) {
            nativeCells.onTopologyChanged(() -> refresh = true);
            nativeCells.setPublished(grid != null);
            if (grid != null) ((IStorageGrid) grid.getCache(IStorageGrid.class)).registerCellProvider(nativeCells);
        }
        pool.addListener(poolListener);
        pool.setNetworkAccess(this);
        refresh = true;
    }

    public static void install(IGrid newGrid) {
        if (INSTANCE.grid == newGrid) return;
        INSTANCE.detachGrid();
        INSTANCE.attachPool();
        INSTANCE.changed();
        if (newGrid == null) return;
        INSTANCE.grid = newGrid;
        if (INSTANCE.nativeCells == null) throw new IllegalStateException("Public ME requires native AE cell storage");
        INSTANCE.nativeCells.onTopologyChanged(() -> INSTANCE.refresh = true);
        INSTANCE.nativeCells.setPublished(true);
        IStorageGrid storage = newGrid.getCache(IStorageGrid.class);
        storage.registerCellProvider(INSTANCE.nativeCells);
        INSTANCE.itemMonitor = (IMEMonitor<IAEItemStack>) storage.getMEMonitor(AEItemStackType.ITEM_STACK_TYPE);
        INSTANCE.fluidMonitor = (IMEMonitor<IAEFluidStack>) storage.getMEMonitor(AEFluidStackType.FLUID_STACK_TYPE);
        INSTANCE.itemMonitor.addListener(INSTANCE.itemListener, newGrid);
        INSTANCE.fluidMonitor.addListener(INSTANCE.fluidListener, newGrid);
        INSTANCE.changed();
    }

    private void detachGrid() {
        if (itemMonitor != null) itemMonitor.removeListener(itemListener);
        if (fluidMonitor != null) fluidMonitor.removeListener(fluidListener);
        if (grid != null) {
            IStorageGrid storage = grid.getCache(IStorageGrid.class);
            if (nativeCells != null) storage.unregisterCellProvider(nativeCells);
            if (nativeCells != null) {
                nativeCells.onTopologyChanged(null);
                nativeCells.setPublished(false);
            }
        }
        itemMonitor = null;
        fluidMonitor = null;
        grid = null;
    }

    public static void clear() {
        INSTANCE.detachGrid();
        if (INSTANCE.pool != null) {
            INSTANCE.pool.setNetworkAccess(null);
            INSTANCE.pool.removeListener(INSTANCE.poolListener);
        }
        INSTANCE.pool = null;
        INSTANCE.refresh = false;
        INSTANCE.wasOnline = false;
        INSTANCE.sendSnapshot = false;
        INSTANCE.changedItems.clear();
        INSTANCE.changedFluids.clear();
        CONTEXT.remove();
    }

    public static void tick() {
        if (!PublicMeNetwork.isRunning()) return;
        INSTANCE.attachPool();
        boolean online = INSTANCE.online();
        if (online != INSTANCE.wasOnline) {
            INSTANCE.wasOnline = online;
            INSTANCE.changed();
        }
        if (INSTANCE.refresh && INSTANCE.grid != null) {
            INSTANCE.refresh = false;
            INSTANCE.grid.postEvent(new MENetworkCellArrayUpdate());
            INSTANCE.changed();
        }
        if (INSTANCE.sendSnapshot) {
            INSTANCE.sendSnapshot = false;
            SharedStorageManager.invalidateSnapshot();
            // Batched once per tick regardless of how many native AE operations occurred.
            SharedStorageManager.resyncAll();
            INSTANCE.changedItems.clear();
            INSTANCE.changedFluids.clear();
        } else if (!INSTANCE.changedItems.isEmpty() || !INSTANCE.changedFluids.isEmpty()) {
            com.futa_gtnh.network.PacketStorageDelta delta = new com.futa_gtnh.network.PacketStorageDelta();
            for (ItemKey key : INSTANCE.changedItems) delta.addItem(key, INSTANCE.itemAmount(key));
            for (FluidKey key : INSTANCE.changedFluids) delta.addFluid(key, INSTANCE.fluidAmount(key));
            INSTANCE.changedItems.clear();
            INSTANCE.changedFluids.clear();
            if (delta.hasOverflowed()) SharedStorageManager.resyncAll();
            else SharedStorageManager.broadcastDelta(delta);
        }
    }

    private void changed() {
        itemRevision++;
        fluidRevision++;
        sendSnapshot = true;
    }

    private boolean online() {
        return SharedStorageManager.isServerThread() && PublicMeNetwork.isOnline() && grid == PublicMeNetwork.grid();
    }

    private BaseActionSource source() {
        return CONTEXT.get().source == null ? PublicMeNetwork.actionSource() : CONTEXT.get().source;
    }

    @Override
    public long itemAmount(ItemKey key) {
        if (key == null || !online() || itemMonitor == null) return 0;
        return querying(0L, () -> {
            IAEItemStack found = itemMonitor.getStorageList()
                .findPrecise(AEItemStack.create(key.prototype()));
            return found == null ? 0 : Math.max(0, found.getStackSize());
        });
    }

    @Override
    public long fluidAmount(FluidKey key) {
        if (key == null || !online() || fluidMonitor == null) return 0;
        return querying(0L, () -> {
            IAEFluidStack found = fluidMonitor.getStorageList()
                .findPrecise(AEFluidStack.create(key.prototype()));
            return found == null ? 0 : Math.max(0, found.getStackSize());
        });
    }

    private <T> T querying(T recursiveResult, Supplier<T> operation) {
        Context context = CONTEXT.get();
        if (context.queryDepth > 0) return recursiveResult;
        context.queryDepth++;
        try {
            return operation.get();
        } finally {
            context.queryDepth--;
        }
    }

    private <T> T inContext(boolean manual, boolean direct, TileEntity excluded, Supplier<T> operation) {
        Context old = CONTEXT.get();
        Context next = new Context();
        next.manual = manual;
        next.direct = direct;
        next.excluded = excluded;
        next.source = old.source;
        next.creditKey = old.creditKey;
        next.credit = old.credit;
        next.routeDepth = old.routeDepth + 1;
        next.queryDepth = old.queryDepth;
        CONTEXT.set(next);
        try {
            return operation.get();
        } finally {
            CONTEXT.set(old);
        }
    }

    @Override
    public long insertItem(ItemKey key, long amount, boolean manual, boolean direct) {
        if (key == null || amount <= 0 || !SharedStorageManager.isServerThread() || CONTEXT.get().routeDepth > 0)
            return 0;
        return inContext(manual, direct, null, () -> {
            long accepted = 0;
            if (online() && itemMonitor != null) {
                IAEItemStack input = AEItemStack.create(key.prototype());
                input.setStackSize(amount);
                IAEItemStack rest = itemMonitor.injectItems(input, Actionable.MODULATE, source());
                accepted = accepted(amount, rest);
            }
            // Manual callers already own these items (crafting returns, replacement tools, recovery).
            // A network outage must not discard them; native AE cells remain the recovery destination.
            if (manual && accepted < amount) accepted += pool.poolInsertItem(key, amount - accepted, true);
            return accepted;
        });
    }

    @Override
    public long insertFluid(FluidKey key, long amount, boolean manual, boolean direct) {
        if (key == null || amount <= 0 || !SharedStorageManager.isServerThread() || CONTEXT.get().routeDepth > 0)
            return 0;
        return inContext(manual, direct, null, () -> {
            long accepted = 0;
            if (online() && fluidMonitor != null) {
                IAEFluidStack input = AEFluidStack.create(key.prototype());
                input.setStackSize(amount);
                IAEFluidStack rest = fluidMonitor.injectItems(input, Actionable.MODULATE, source());
                accepted = accepted(amount, rest);
            }
            if (manual && accepted < amount) accepted += pool.poolInsertFluid(key, amount - accepted, true);
            return accepted;
        });
    }

    private static long accepted(long offered, IAEStack<?> rest) {
        long remaining = rest == null ? 0 : rest.getStackSize();
        if (remaining < 0 || remaining > offered) throw new IllegalStateException("Invalid AE insertion remainder");
        return offered - remaining;
    }

    @Override
    public long extractItem(ItemKey key, long amount, TileEntity excluded) {
        if (key == null || amount <= 0 || !online() || itemMonitor == null || CONTEXT.get().routeDepth > 0) return 0;
        return inContext(false, false, excluded, () -> {
            IAEItemStack request = AEItemStack.create(key.prototype());
            request.setStackSize(amount);
            IAEItemStack result = itemMonitor.extractItems(request, Actionable.MODULATE, source());
            return result == null ? 0 : result.getStackSize();
        });
    }

    @Override
    public long extractFluid(FluidKey key, long amount, TileEntity excluded) {
        if (key == null || amount <= 0 || !online() || fluidMonitor == null || CONTEXT.get().routeDepth > 0) return 0;
        return inContext(false, false, excluded, () -> {
            IAEFluidStack request = AEFluidStack.create(key.prototype());
            request.setStackSize(amount);
            IAEFluidStack result = fluidMonitor.extractItems(request, Actionable.MODULATE, source());
            return result == null ? 0 : result.getStackSize();
        });
    }

    @Override
    public long itemSpace(ItemKey key, boolean manual) {
        if (key == null || !SharedStorageManager.isServerThread() || CONTEXT.get().routeDepth > 0) return 0;
        if (!online()) return manual ? pool.poolItemSpace(key, true) : 0;
        return inContext(manual, false, null, () -> {
            IAEItemStack input = AEItemStack.create(key.prototype());
            input.setStackSize(REPORT_CAP);
            return accepted(REPORT_CAP, itemMonitor.injectItems(input, Actionable.SIMULATE, source()));
        });
    }

    @Override
    public long fluidSpace(FluidKey key, boolean manual) {
        if (key == null || !SharedStorageManager.isServerThread() || CONTEXT.get().routeDepth > 0) return 0;
        if (!online()) return manual ? pool.poolFluidSpace(key, true) : 0;
        return inContext(manual, false, null, () -> {
            IAEFluidStack input = AEFluidStack.create(key.prototype());
            input.setStackSize(REPORT_CAP);
            return accepted(REPORT_CAP, fluidMonitor.injectItems(input, Actionable.SIMULATE, source()));
        });
    }

    @Override
    public List<Map.Entry<ItemKey, Long>> items() {
        if (!online() || itemMonitor == null) return Collections.emptyList();
        return querying(Collections.emptyList(), () -> {
            List<Map.Entry<ItemKey, Long>> result = new ArrayList<>();
            for (IAEItemStack row : itemMonitor.getStorageList()) if (row.getStackSize() > 0) {
                ItemKey key = ItemKey.of(row.getItemStack());
                if (key != null) result.add(new AbstractMap.SimpleImmutableEntry<>(key, row.getStackSize()));
            }
            return Collections.unmodifiableList(result);
        });
    }

    @Override
    public List<Map.Entry<FluidKey, Long>> fluids() {
        if (!online() || fluidMonitor == null) return Collections.emptyList();
        return querying(Collections.emptyList(), () -> {
            List<Map.Entry<FluidKey, Long>> result = new ArrayList<>();
            for (IAEFluidStack row : fluidMonitor.getStorageList()) if (row.getStackSize() > 0) {
                FluidKey key = FluidKey.of(row.getFluidStack());
                if (key != null) result.add(new AbstractMap.SimpleImmutableEntry<>(key, row.getStackSize()));
            }
            return Collections.unmodifiableList(result);
        });
    }

    @Override
    public int itemRevision() {
        return itemRevision;
    }

    @Override
    public int fluidRevision() {
        return fluidRevision;
    }

    private final class Listener<T extends IAEStack<T>> implements IMEMonitorHandlerReceiver<T> {

        final boolean item;

        Listener(boolean item) {
            this.item = item;
        }

        @Override
        public boolean isValid(Object token) {
            return grid != null && token == grid;
        }

        @Override
        public void onListUpdate() {
            changed();
        }

        @Override
        public void postChange(IBaseMonitor<T> monitor, Iterable<T> changes, BaseActionSource action) {
            if (item) itemRevision++;
            else fluidRevision++;
            // Tell compatibility gateways without reporting the same change back into this grid.
            providerWrites++;
            try {
                for (T value : changes) {
                    if (value instanceof IAEItemStack) {
                        ItemKey key = ItemKey.of(((IAEItemStack) value).getItemStack());
                        if (key != null) {
                            changedItems.add(key);
                            pool.notifyExternalItemChanged(key);
                        }
                    } else if (value instanceof IAEFluidStack) {
                        FluidKey key = FluidKey.of(((IAEFluidStack) value).getFluidStack());
                        if (key != null) {
                            changedFluids.add(key);
                            pool.notifyExternalFluidChanged(key);
                        }
                    }
                }
            } finally {
                providerWrites--;
            }
        }
    }

}
