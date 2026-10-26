package com.futa_gtnh.storagebus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.ae2.PublicStorageAccess;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorageManager;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.ICellProvider;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;

/** Existing placed Futa buses become native ME inventories without copying their containers. */
public final class LegacyMeBusProvider implements ICellProvider {

    private static final LegacyMeBusProvider INSTANCE = new LegacyMeBusProvider();
    private static final long REPORT_CAP = (1L << 52) - 1;
    private final Map<StorageBusLink, Binding> bindings = new LinkedHashMap<>();
    private final Map<TileEntity, View> views = new IdentityHashMap<>();
    private IGrid grid;
    private boolean changed;
    private boolean operating;

    private LegacyMeBusProvider() {}

    public static void install(IGrid grid) {
        if (grid == null) {
            clear();
            return;
        }
        if (INSTANCE.grid == grid) return;
        clear();
        INSTANCE.grid = grid;
        INSTANCE.refresh();
        IStorageGrid storage = grid.getCache(IStorageGrid.class);
        storage.registerCellProvider(INSTANCE);
        grid.postEvent(new MENetworkCellArrayUpdate());
        INSTANCE.changed = false;
    }

    public static void tick() {
        if (INSTANCE.grid == null || !SharedStorageManager.isServerThread()) return;
        INSTANCE.refresh();
        if (INSTANCE.changed) {
            INSTANCE.changed = false;
            INSTANCE.grid.postEvent(new MENetworkCellArrayUpdate());
        }
    }

    public static void clear() {
        if (INSTANCE.grid != null) {
            IStorageGrid storage = INSTANCE.grid.getCache(IStorageGrid.class);
            storage.unregisterCellProvider(INSTANCE);
        }
        INSTANCE.grid = null;
        INSTANCE.bindings.clear();
        INSTANCE.views.clear();
        INSTANCE.changed = false;
        INSTANCE.operating = false;
    }

    /** Checks lightweight link/config revisions; only changed targets copy inventory mirrors. */
    private void refresh() {
        Map<StorageBusLink, Binding> next = new LinkedHashMap<>();
        boolean topology = false;
        for (StorageBusLink link : StorageBusRegistry.links()) {
            TileEntity target = link.targetTile();
            if (target == null || recursive(target)) continue;
            if (!link.hasItemTarget() && !link.hasFluidTarget()) continue;
            Binding binding = bindings.get(link);
            if (binding == null || binding.target != target
                || binding.configRevision != link.getConfig()
                    .getRevision()) {
                binding = new Binding(link, target);
                topology = true;
            }
            next.put(link, binding);
        }
        topology |= next.size() != bindings.size() || !next.keySet()
            .equals(bindings.keySet());
        bindings.clear();
        bindings.putAll(next);
        if (topology) {
            views.clear();
            for (Binding binding : bindings.values())
                views.computeIfAbsent(binding.target, ignored -> new View()).links.add(binding);
            for (View view : views.values()) view.capture();
            changed = true;
            return;
        }
        for (View view : views.values()) {
            for (Binding binding : view.links) {
                if (binding.mirrorRevision != binding.link.getMirrorRevision()) {
                    Snapshot before = view.snapshot;
                    view.capture();
                    if (!before.same(view.snapshot)) changed = true;
                    break;
                }
            }
        }
    }

    private boolean recursive(TileEntity target) {
        if (target instanceof com.futa_gtnh.block.TileEntitySharedTerminal
            || target instanceof com.futa_gtnh.block.TileEntityIoNode
            || target instanceof TileEntityStorageBus) return true;
        if (sameGrid(target)) return true;
        if (target instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity) {
            Object machine = ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) target).getMetaTileEntity();
            return sameGrid(machine);
        }
        return false;
    }

    private boolean sameGrid(Object host) {
        try {
            if (host instanceof IActionHost) {
                IGridNode node = ((IActionHost) host).getActionableNode();
                if (node != null && node.getGrid() == grid) return true;
            }
            if (host instanceof IGridHost) {
                for (ForgeDirection side : ForgeDirection.values()) {
                    IGridNode node = ((IGridHost) host).getGridNode(side);
                    if (node != null && node.getGrid() == grid) return true;
                }
            }
            return false;
        } catch (RuntimeException error) {
            // A host whose network cannot be identified must not create an unchecked recursion path.
            return true;
        }
    }

    private boolean available(Binding binding) {
        return grid != null && !operating
            && SharedStorageManager.isServerThread()
            && !PublicStorageAccess.poolOnly()
            && PublicStorageAccess.excludedTile() != binding.target
            && bindings.get(binding.link) == binding
            && binding.link.targetTile() == binding.target
            && binding.link.getConfig() != null
            && binding.link.getConfig()
                .getRevision() == binding.configRevision
            && !recursive(binding.target);
    }

    /** Refresh only this physical container before and after a real operation. */
    private void refreshTarget(View view) {
        boolean previous = operating;
        operating = true;
        try {
            for (Binding binding : view.links) {
                try {
                    StorageBusRegistry.refresh(binding.link);
                } catch (RuntimeException error) {
                    // Preserve a completed operation's return value even when a foreign inventory's
                    // refresh fails; a later full update will reconcile the now-unknown view.
                    binding.link.clearCache();
                    changed = true;
                }
            }
            view.capture();
        } finally {
            operating = previous;
        }
    }

    private long transfer(Binding binding, Object key, long amount, boolean insert, boolean fluid) {
        View view = views.get(binding.target);
        if (view == null) return 0;
        Snapshot previous = view.snapshot;
        refreshTarget(view);
        Snapshot before = view.snapshot;
        if (!previous.same(before)) changed = true;
        long moved = 0;
        operating = true;
        try {
            if (fluid) moved = insert ? binding.link.insertFluid((FluidKey) key, amount)
                : binding.link.extractFluid((FluidKey) key, amount);
            else moved = insert ? binding.link.insertItem((ItemKey) key, amount)
                : binding.link.extractItem((ItemKey) key, amount);
            return moved;
        } finally {
            operating = false;
            refreshTarget(view);
            boolean visible = false;
            for (Binding member : view.links) {
                if (member.link.getConfig() != null && member.link.isReadable()
                    && (fluid ? member.link.getConfig()
                        .allowsFluid((FluidKey) key)
                        : member.link.getConfig()
                            .allowsItem((ItemKey) key)))
                    visible = true;
            }
            long expected = visible ? (insert ? moved : -moved) : 0;
            // The native monitor already records this transfer. Only unrelated inventory changes
            // need a later cell-array update; reporting our own delta again would double-account it.
            if (!before.matchesTransfer(view.snapshot, key, expected, fluid)) changed = true;
        }
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<IMEInventoryHandler> getCellArray(IAEStackType<?> type) {
        if (grid == null) return Collections.emptyList();
        List<IMEInventoryHandler> result = new ArrayList<>();
        for (Binding binding : bindings.values()) {
            if (type == AEItemStackType.ITEM_STACK_TYPE && binding.link.hasItemTarget()) result.add(binding.items);
            if (type == AEFluidStackType.FLUID_STACK_TYPE && binding.link.hasFluidTarget()) result.add(binding.fluids);
        }
        return result;
    }

    private final class Binding {

        final StorageBusLink link;
        final TileEntity target;
        final int configRevision;
        final int priority;
        final ItemInventory items;
        final FluidInventory fluids;
        int mirrorRevision;

        Binding(StorageBusLink link, TileEntity target) {
            this.link = link;
            this.target = target;
            configRevision = link.getConfig()
                .getRevision();
            priority = link.getConfig()
                .getPriority();
            items = new ItemInventory(this);
            fluids = new FluidInventory(this);
        }
    }

    private static final class Snapshot {

        final Map<ItemKey, Long> items = new LinkedHashMap<>();
        final Map<FluidKey, Long> fluids = new LinkedHashMap<>();

        boolean same(Snapshot other) {
            return items.equals(other.items) && fluids.equals(other.fluids);
        }

        boolean matchesTransfer(Snapshot after, Object key, long delta, boolean fluid) {
            if (fluid) {
                Map<FluidKey, Long> expected = new LinkedHashMap<>(fluids);
                adjust(expected, (FluidKey) key, delta);
                return items.equals(after.items) && expected.equals(after.fluids);
            }
            Map<ItemKey, Long> expected = new LinkedHashMap<>(items);
            adjust(expected, (ItemKey) key, delta);
            return fluids.equals(after.fluids) && expected.equals(after.items);
        }

        private static <K> void adjust(Map<K, Long> values, K key, long delta) {
            long before = values.getOrDefault(key, 0L);
            long next = delta > 0 ? before + Math.min(Long.MAX_VALUE - before, delta) : Math.max(0, before + delta);
            if (next == 0) values.remove(key);
            else values.put(key, next);
        }
    }

    private final class View {

        final List<Binding> links = new ArrayList<>();
        final Map<ItemKey, Binding> itemOwners = new LinkedHashMap<>();
        final Map<FluidKey, Binding> fluidOwners = new LinkedHashMap<>();
        Snapshot snapshot = new Snapshot();

        void capture() {
            Snapshot next = new Snapshot();
            itemOwners.clear();
            fluidOwners.clear();
            for (Binding binding : links) {
                binding.mirrorRevision = binding.link.getMirrorRevision();
                if (binding.link.getConfig() == null) continue;
                if (!binding.link.isReadable()) continue;
                for (Map.Entry<ItemKey, Long> row : binding.link.itemSnapshot()) {
                    if (!binding.link.getConfig()
                        .allowsItem(row.getKey())) continue;
                    if (row.getValue() > next.items.getOrDefault(row.getKey(), 0L)) {
                        next.items.put(row.getKey(), row.getValue());
                        itemOwners.put(row.getKey(), binding);
                    }
                }
                for (Map.Entry<FluidKey, Long> row : binding.link.fluidSnapshot()) {
                    if (!binding.link.getConfig()
                        .allowsFluid(row.getKey())) continue;
                    if (row.getValue() > next.fluids.getOrDefault(row.getKey(), 0L)) {
                        next.fluids.put(row.getKey(), row.getValue());
                        fluidOwners.put(row.getKey(), binding);
                    }
                }
            }
            snapshot = next;
        }
    }

    private abstract class Inventory<T extends IAEStack<T>, K> implements IMEInventoryHandler<T> {

        final Binding binding;
        final boolean fluid;

        Inventory(Binding binding, boolean fluid) {
            this.binding = binding;
            this.fluid = fluid;
        }

        abstract K key(T value);

        abstract T stack(K key, long amount);

        abstract long simulate(K key, long amount, boolean insert);

        abstract Map<K, Long> amounts(View view);

        abstract Map<K, Binding> owners(View view);

        @Override
        public T injectItems(T input, Actionable mode, BaseActionSource source) {
            if (input == null || input.getStackSize() <= 0 || !available(binding)) return input;
            K key = key(input);
            long moved = mode == Actionable.SIMULATE ? simulate(key, input.getStackSize(), true)
                : transfer(binding, key, input.getStackSize(), true, fluid);
            if (moved >= input.getStackSize()) return null;
            T rest = input.copy();
            rest.setStackSize(input.getStackSize() - moved);
            return rest;
        }

        @Override
        public T extractItems(T input, Actionable mode, BaseActionSource source) {
            if (input == null || input.getStackSize() <= 0 || !available(binding)) return null;
            K key = key(input);
            long moved = mode == Actionable.SIMULATE ? simulate(key, input.getStackSize(), false)
                : transfer(binding, key, input.getStackSize(), false, fluid);
            return moved <= 0 ? null : stack(key, moved);
        }

        @Override
        public IItemList<T> getAvailableItems(IItemList<T> output, int iteration) {
            if (!available(binding) || !binding.link.isReadable()) return output;
            View view = views.get(binding.target);
            if (view != null) for (Map.Entry<K, Long> row : amounts(view).entrySet()) {
                if (owners(view).get(row.getKey()) == binding)
                    output.add(stack(row.getKey(), Math.min(REPORT_CAP, row.getValue())));
            }
            return output;
        }

        @Override
        public T getAvailableItem(T input, int iteration) {
            if (input == null || !available(binding) || !binding.link.isReadable()) return null;
            View view = views.get(binding.target);
            K key = key(input);
            if (view == null || owners(view).get(key) != binding) return null;
            long amount = amounts(view).getOrDefault(key, 0L);
            return amount <= 0 ? null : stack(key, Math.min(REPORT_CAP, amount));
        }

        @Override
        public AccessRestriction getAccess() {
            if (!available(binding)) return AccessRestriction.NO_ACCESS;
            return binding.link.isReadable()
                ? (binding.link.isWritable() ? AccessRestriction.READ_WRITE : AccessRestriction.READ)
                : (binding.link.isWritable() ? AccessRestriction.WRITE : AccessRestriction.NO_ACCESS);
        }

        @Override
        public boolean isPrioritized(T input) {
            if (input == null) return false;
            View view = views.get(binding.target);
            return view != null && amounts(view).getOrDefault(key(input), 0L) > 0;
        }

        @Override
        public boolean canAccept(T input) {
            return input != null && available(binding) && simulate(key(input), 1, true) > 0;
        }

        @Override
        public int getPriority() {
            return binding.priority;
        }

        @Override
        public int getSlot() {
            return binding.link.getTargetSide()
                .ordinal();
        }

        @Override
        public boolean validForPass(int pass) {
            return true;
        }
    }

    private final class ItemInventory extends Inventory<IAEItemStack, ItemKey> {

        ItemInventory(Binding binding) {
            super(binding, false);
        }

        @Override
        ItemKey key(IAEItemStack value) {
            return ItemKey.of(value.getItemStack());
        }

        @Override
        IAEItemStack stack(ItemKey key, long amount) {
            IAEItemStack value = AEItemStack.create(key.prototype());
            value.setStackSize(amount);
            return value;
        }

        @Override
        long simulate(ItemKey key, long amount, boolean insert) {
            return insert ? binding.link.simulateInsertItem(key, amount)
                : binding.link.simulateExtractItem(key, amount);
        }

        @Override
        Map<ItemKey, Long> amounts(View view) {
            return view.snapshot.items;
        }

        @Override
        Map<ItemKey, Binding> owners(View view) {
            return view.itemOwners;
        }

        @Override
        public IAEStackType<?> getStackType() {
            return AEItemStackType.ITEM_STACK_TYPE;
        }
    }

    private final class FluidInventory extends Inventory<IAEFluidStack, FluidKey> {

        FluidInventory(Binding binding) {
            super(binding, true);
        }

        @Override
        FluidKey key(IAEFluidStack value) {
            return FluidKey.of(value.getFluidStack());
        }

        @Override
        IAEFluidStack stack(FluidKey key, long amount) {
            IAEFluidStack value = AEFluidStack.create(key.prototype());
            value.setStackSize(amount);
            return value;
        }

        @Override
        long simulate(FluidKey key, long amount, boolean insert) {
            return insert ? binding.link.simulateInsertFluid(key, amount)
                : binding.link.simulateExtractFluid(key, amount);
        }

        @Override
        Map<FluidKey, Long> amounts(View view) {
            return view.snapshot.fluids;
        }

        @Override
        Map<FluidKey, Binding> owners(View view) {
            return view.fluidOwners;
        }

        @Override
        public IAEStackType<?> getStackType() {
            return AEFluidStackType.FLUID_STACK_TYPE;
        }
    }
}
