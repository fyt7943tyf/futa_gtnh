package com.futa_gtnh.me;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.futa_gtnh.ae2.PublicStorageAccess;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.glodblock.github.loader.ItemAndBlockHolder;

import appeng.api.AEApi;
import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.ICellInventoryHandler;
import appeng.api.storage.ICellProvider;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.me.storage.CellInventory;
import appeng.me.storage.MEInventoryHandler;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;

/** A drive array of unmodified AE/AE2FC cell items. No independent item/fluid quantity ledger exists here. */
public final class FrozenNativeMeCellArray1342 implements ICellProvider, ISaveProvider {

    private final Channel<IAEItemStack, ItemKey> items = new Channel<>(
        AEItemStackType.ITEM_STACK_TYPE,
        key -> AEItemStack.create(key.prototype()),
        value -> ItemKey.of(value.getItemStack()));
    private final Channel<IAEFluidStack, FluidKey> fluids = new Channel<>(
        AEFluidStackType.FLUID_STACK_TYPE,
        key -> AEFluidStack.create(key.prototype()),
        value -> FluidKey.of(value.getFluidStack()));
    private final Function<Boolean, ItemStack> definitions;
    private Runnable dirty = () -> {};
    private Runnable topology = () -> {};
    private SharedStorage settings;
    private int directWrites;
    private int nativeMutationDepth;
    private final Map<IMEInventory, Runnable> nativeChanges = new IdentityHashMap<>();
    private boolean published;

    public void setPublished(boolean value) {
        published = value;
    }

    public FrozenNativeMeCellArray1342() {
        this(
            fluid -> fluid
                ? (ItemAndBlockHolder.ARTIFICIAL_UNIVERSE_CELL == null ? null
                    : new ItemStack(ItemAndBlockHolder.ARTIFICIAL_UNIVERSE_CELL))
                : AEApi.instance()
                    .definitions()
                    .items()
                    .cellUniverse()
                    .maybeStack(1)
                    .orNull());
    }

    /**
     * Factory injection is for the standalone native-engine regression; production always uses registered native items.
     */
    public FrozenNativeMeCellArray1342(Function<Boolean, ItemStack> definitions) {
        this.definitions = definitions;
    }

    public void validateMedia() {
        items.new Slot(definitions.apply(false));
        fluids.new Slot(definitions.apply(true));
    }

    public void attach(SharedStorage settings, Runnable dirty) {
        this.settings = settings;
        this.dirty = dirty;
    }

    public void onTopologyChanged(Runnable callback) {
        topology = callback == null ? () -> {} : callback;
    }

    public Map<ItemKey, Long> itemView() {
        return items.view;
    }

    public Map<FluidKey, Long> fluidView() {
        return fluids.view;
    }

    public int cellCount() {
        return items.slots.size() + fluids.slots.size();
    }

    public int frozenCellCount() {
        return items.frozen.size() + fluids.frozen.size();
    }

    /** Legacy stock remains owned by its decoder until both native channels have accepted every row. */
    public void importStock(Map<ItemKey, Long> itemStock, Map<FluidKey, Long> fluidStock) {
        directWrites++;
        try {
            items.importRows(itemStock);
            fluids.importRows(fluidStock);
        } finally {
            directWrites--;
        }
    }

    public long insertItem(ItemKey key, long amount) {
        directWrites++;
        try {
            IAEItemStack rest = items.insert(
                AEItemStack.create(key.prototype())
                    .setStackSize(amount),
                Actionable.MODULATE,
                null);
            return amount - (rest == null ? 0 : rest.getStackSize());
        } finally {
            directWrites--;
        }
    }

    public long insertFluid(FluidKey key, long amount) {
        directWrites++;
        try {
            IAEFluidStack rest = fluids.insert(
                AEFluidStack.create(key.prototype())
                    .setStackSize(amount),
                Actionable.MODULATE,
                null);
            return amount - (rest == null ? 0 : rest.getStackSize());
        } finally {
            directWrites--;
        }
    }

    public void writeToNBT(NBTTagCompound tag) {
        tag.setTag("itemCells", items.write());
        tag.setTag("fluidCells", fluids.write());
    }

    public void readFromNBT(NBTTagCompound tag) {
        if (!tag.hasKey("itemCells", 9) || !tag.hasKey("fluidCells", 9))
            throw new IllegalStateException("Missing native AE cell arrays");
        items.read(tag.getTagList("itemCells", 10));
        fluids.read(tag.getTagList("fluidCells", 10));
    }

    @Override
    public void saveChanges(IMEInventory inventory) {
        dirty.run();
        // Native integrations can deliberately unwrap a handler. Repair that cell only; ordinary controlled
        // transfers already know the affected key and never take this fallback.
        if (nativeMutationDepth == 0) {
            Runnable repair = nativeChanges.get(inventory);
            if (repair != null) repair.run();
        }
    }

    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public List<IMEInventoryHandler> getCellArray(IAEStackType<?> type) {
        if (type == items.type) return items.handlers();
        if (type == fluids.type) return fluids.handlers();
        return Collections.emptyList();
    }

    private final class Channel<T extends IAEStack<T>, K> {

        final IAEStackType<T> type;
        final Function<K, T> stack;
        final Function<T, K> key;
        final List<Slot> slots = new ArrayList<>();
        final List<NBTTagCompound> frozen = new ArrayList<>();
        // Locations only. Quantities always come from AE's own CellInventory.
        final Map<K, Resource> locations = new LinkedHashMap<>();
        // Private native identity keys never change; their count is only a key marker, never inventory.
        final Map<T, Resource> identities = new HashMap<>();
        final Set<Slot> openSlots = new LinkedHashSet<>();
        final Set<Slot> batchChanged = new LinkedHashSet<>();
        boolean importing;
        long structure;
        long probeStructure = -1;
        T probeIdentity;
        Resource probe;
        final Map<K, Long> view = new AbstractMap<K, Long>() {

            @Override
            public Long get(Object raw) {
                Resource found = locations.get(raw);
                if (found == null) return null;
                @SuppressWarnings("unchecked")
                K requested = (K) raw;
                long amount = amount(requested);
                return amount == 0 ? null : amount;
            }

            @Override
            public int size() {
                return locations.size();
            }

            @Override
            public boolean isEmpty() {
                return locations.isEmpty();
            }

            @Override
            public Long put(K requested, Long amount) {
                long before = amount(requested), target = amount == null ? 0 : amount;
                if (target < 0) throw new IllegalArgumentException("Negative native AE quantity");
                T request = stack.apply(requested);
                directWrites++;
                try {
                    if (target > before) {
                        request.setStackSize(target - before);
                        T rest = insert(request, Actionable.MODULATE, null);
                        if (rest != null && rest.getStackSize() > 0) throw new IllegalStateException(
                            "Native AE storage rejected legacy stock; original source retained: " + requested);
                    } else if (target < before) {
                        request.setStackSize(before - target);
                        T taken = extract(request, Actionable.MODULATE, null);
                        if (taken == null || taken.getStackSize() != before - target)
                            throw new IllegalStateException("Native AE extraction did not conserve quantity");
                    }
                    return before == 0 ? null : before;
                } finally {
                    directWrites--;
                }
            }

            @Override
            public Long remove(Object raw) {
                if (!locations.containsKey(raw)) return null;
                @SuppressWarnings("unchecked")
                K requested = (K) raw;
                return put(requested, 0L);
            }

            @Override
            public void clear() {
                for (Slot slot : slots) {
                    slot.active = false;
                    nativeChanges.remove(((ICellInventoryHandler<?>) slot.nativeInventory).getCellInv());
                }
                slots.clear();
                locations.clear();
                identities.clear();
                structure++;
                probe = null;
                probeIdentity = null;
                openSlots.clear();
                frozen.clear();
                dirty.run();
                topology.run();
            }

            @Override
            public Set<Entry<K, Long>> entrySet() {
                Set<Entry<K, Long>> result = new LinkedHashSet<>();
                for (K requested : new ArrayList<>(locations.keySet())) {
                    long amount = amount(requested);
                    if (amount > 0) result.add(new SimpleImmutableEntry<>(requested, amount));
                }
                return Collections.unmodifiableSet(result);
            }
        };
        final IMEInventoryHandler<T> allocator;

        IMEInventoryHandler<T> newAllocator() {
            return new MEInventoryHandler<T>(new IMEInventory<T>() {

                @Override
                public T injectItems(T input, Actionable mode, BaseActionSource source) {
                    return published ? insert(input, mode, source, true) : input;
                }

                @Override
                public T extractItems(T input, Actionable mode, BaseActionSource source) {
                    return null;
                }

                @Override
                public IItemList<T> getAvailableItems(IItemList<T> out, int iteration) {
                    return out;
                }

                @Override
                public IAEStackType<?> getStackType() {
                    return type;
                }
            }, type) {

                @Override
                public int getPriority() {
                    return Integer.MIN_VALUE;
                }
            };
        }

        Channel(IAEStackType<T> type, Function<K, T> stack, Function<T, K> key) {
            this.type = type;
            this.stack = stack;
            this.key = key;
            allocator = newAllocator();
        }

        long amount(K requested) {
            Resource found = locations.get(requested);
            return found == null ? 0 : amount(found, found.identity);
        }

        long amount(Resource found, T request) {
            long amount = 0;
            for (Slot slot : found.slots) {
                T stored = slot.nativeInventory.getAvailableItem(request, 0);
                long n = stored == null ? 0 : stored.getStackSize();
                if (n > Long.MAX_VALUE - amount) throw new IllegalStateException("Native AE quantity overflow");
                amount += n;
            }
            return amount;
        }

        long room(T request) {
            Resource resource = locate(request);
            K requested = resource == null ? key.apply(request) : resource.key;
            if (requested == null) return 0;
            long ceiling = Long.MAX_VALUE;
            boolean fluid = type == AEFluidStackType.FLUID_STACK_TYPE;
            if (settings != null && directWrites == 0 && settings.hasNativeConstraints(fluid)) {
                ceiling = fluid ? settings.nativeFluidCeiling((FluidKey) requested, PublicStorageAccess.manual())
                    : settings.nativeItemCeiling((ItemKey) requested, PublicStorageAccess.manual());
            }
            long stored = resource == null ? 0 : amount(resource, request);
            return Math.max(0, ceiling - stored);
        }

        T insert(T input, Actionable mode, BaseActionSource source) {
            return insert(input, mode, source, false);
        }

        T insert(T input, Actionable mode, BaseActionSource source, boolean allocationOnly) {
            if (input == null || input.getStackSize() <= 0) return input;
            long available = room(input);
            if (allocationOnly && mode == Actionable.SIMULATE) {
                // Native handlers precede this lowest-priority allocator. Count only capacity they cannot supply;
                // otherwise a partially capped simulated insertion would advertise the same room twice.
                for (Slot slot : slots) {
                    if (available <= 0) break;
                    T remainder = slot.nativeInventory.injectItems(
                        input.copy()
                            .setStackSize(available),
                        mode,
                        source);
                    available = remainder == null ? 0 : remainder.getStackSize();
                }
            }
            long accepted = Math.min(input.getStackSize(), available);
            if (accepted <= 0) return input;
            T remaining = input.copy()
                .setStackSize(accepted);
            Resource present = locate(input);
            List<Slot> known = present == null ? Collections.emptyList() : new ArrayList<>(present.slots);
            if (!allocationOnly) for (Slot slot : known) {
                remaining = inject(slot, remaining, mode, source);
                if (remaining == null || remaining.getStackSize() == 0) break;
            }
            if (!allocationOnly && remaining != null && remaining.getStackSize() > 0)
                for (Slot slot : new ArrayList<>(openSlots)) {
                    if (known.contains(slot)) continue;
                    remaining = inject(slot, remaining, mode, source);
                    if (remaining == null || remaining.getStackSize() == 0) break;
                }
            while (remaining != null && remaining.getStackSize() > 0) {
                ItemStack cell = definitions.apply(type == AEFluidStackType.FLUID_STACK_TYPE);
                if (cell == null || cell.getItem() == null)
                    throw new IllegalStateException("Native Universe storage cell is disabled");
                Slot fresh = new Slot(cell);
                long before = remaining.getStackSize();
                T next;
                nativeMutationDepth++;
                try {
                    next = fresh.nativeInventory.injectItems(remaining.copy(), mode, source);
                } finally {
                    nativeMutationDepth--;
                }
                long after = next == null ? 0 : next.getStackSize();
                if (after >= before) break; // Native blacklists remain native; never allocate endlessly or void.
                if (mode == Actionable.MODULATE) {
                    slots.add(fresh);
                    watch(fresh);
                    inserted(fresh, remaining);
                    capacity(fresh);
                    dirty.run();
                    topology.run();
                }
                remaining = next;
            }
            long rest = input.getStackSize() - accepted + (remaining == null ? 0 : remaining.getStackSize());
            return rest == 0 ? null
                : input.copy()
                    .setStackSize(rest);
        }

        T extract(T request, Actionable mode, BaseActionSource source) {
            if (request == null || request.getStackSize() <= 0) return null;
            long taken = 0;
            Resource found = locate(request);
            if (found == null) return null;
            for (Slot slot : new ArrayList<>(found.slots)) {
                T result = extract(
                    slot,
                    request.copy()
                        .setStackSize(request.getStackSize() - taken),
                    mode,
                    source);
                if (result != null) taken += result.getStackSize();
                if (taken == request.getStackSize()) break;
            }
            return taken == 0 ? null
                : request.copy()
                    .setStackSize(taken);
        }

        /** Load, bulk-import completion, unwrapped native writes and exception repair can enumerate a cell. */
        void index(Slot slot) {
            IItemList<T> content = slot.nativeInventory.getAvailableItems(type.createList(), 0);
            if (slot.keys.isEmpty()) {
                for (T value : content) if (value.getStackSize() > 0) register(slot, value);
                capacity(slot);
                return;
            }
            Set<Resource> retained = new HashSet<>();
            for (T value : content) if (value.getStackSize() > 0) retained.add(register(slot, value));
            for (Resource old : new ArrayList<>(slot.keys)) if (!retained.contains(old)) unlink(slot, old);
            capacity(slot);
        }

        Resource register(Slot slot, T value) {
            Resource resource = locate(value);
            if (resource == null) {
                K decoded = key.apply(value);
                if (decoded == null) throw new IllegalStateException("Unregistered native resource identity");
                // AEItemStack.copy shares its mutable AEItemDef. Recreate a private identity from our immutable key.
                resource = new Resource(
                    decoded,
                    stack.apply(decoded)
                        .setStackSize(1));
                locations.put(resource.key, resource);
                identities.put(resource.identity, resource);
                structure++;
            }
            if (slot.keys.add(resource)) resource.slots.add(slot);
            return resource;
        }

        void unlink(Slot slot, Resource resource) {
            slot.keys.remove(resource);
            resource.slots.remove(slot);
            if (resource.slots.isEmpty()) {
                locations.remove(resource.key);
                identities.remove(resource.identity);
                structure++;
            }
        }

        /** Compare a private identity, never a caller-owned mutable stack. Only one lookup is retained per channel. */
        Resource locate(T request) {
            if (probeStructure == structure && probeIdentity != null && probeIdentity.equals(request)) return probe;
            probe = identities.get(request);
            if (probe == null) {
                K decoded = key.apply(request);
                probeIdentity = decoded == null ? null
                    : stack.apply(decoded)
                        .setStackSize(1);
            } else probeIdentity = probe.identity;
            probeStructure = structure;
            return probe;
        }

        void capacity(Slot slot) {
            boolean available = ((ICellInventoryHandler<?>) slot.nativeInventory).getCellInv()
                .canHoldNewItem();
            if (available == slot.open) return;
            slot.open = available;
            if (available) openSlots.add(slot);
            else openSlots.remove(slot);
        }

        void inserted(Slot slot, T value) {
            if (importing) batchChanged.add(slot);
            else register(slot, value);
        }

        T inject(Slot slot, T input, Actionable mode, BaseActionSource source) {
            long before = input.getStackSize();
            if (importing && mode == Actionable.MODULATE) batchChanged.add(slot);
            T rest;
            nativeMutationDepth++;
            try {
                rest = slot.nativeInventory.injectItems(input, mode, source);
            } catch (RuntimeException failure) {
                index(slot);
                throw failure;
            } finally {
                nativeMutationDepth--;
            }
            if (mode == Actionable.MODULATE && (rest == null || rest.getStackSize() < before)) {
                inserted(slot, input);
                capacity(slot);
            }
            return rest;
        }

        T extract(Slot slot, T input, Actionable mode, BaseActionSource source) {
            T result;
            nativeMutationDepth++;
            try {
                result = slot.nativeInventory.extractItems(input, mode, source);
            } catch (RuntimeException failure) {
                index(slot);
                throw failure;
            } finally {
                nativeMutationDepth--;
            }
            if (mode == Actionable.MODULATE && result != null && result.getStackSize() > 0) {
                T remaining = slot.nativeInventory.getAvailableItem(input, 0);
                if (remaining == null || remaining.getStackSize() == 0) {
                    Resource resource = locate(input);
                    if (resource != null) unlink(slot, resource);
                }
                capacity(slot);
            }
            return result;
        }

        void watch(Slot slot) {
            nativeChanges.put(((ICellInventoryHandler<?>) slot.nativeInventory).getCellInv(), () -> index(slot));
        }

        void importRows(Map<K, Long> stock) {
            if (stock.isEmpty()) return;
            if (published || importing)
                throw new IllegalStateException("Bulk import requires unpublished native cells");
            importing = true;
            try {
                for (Map.Entry<K, Long> row : stock.entrySet()) {
                    if (row.getValue() == null || row.getValue() <= 0)
                        throw new IllegalArgumentException("Invalid legacy quantity");
                    Math.addExact(amount(row.getKey()), row.getValue());
                    T rest = insert(
                        stack.apply(row.getKey())
                            .setStackSize(row.getValue()),
                        Actionable.MODULATE,
                        null);
                    if (rest != null && rest.getStackSize() > 0) throw new IllegalStateException(
                        "Native AE rejected legacy stock; source retained: " + row.getKey());
                }
            } finally {
                importing = false;
                try {
                    for (Slot slot : batchChanged) index(slot);
                } finally {
                    batchChanged.clear();
                }
            }
        }

        final class Resource {

            final K key;
            final T identity;
            final List<Slot> slots = new ArrayList<>();

            Resource(K key, T identity) {
                this.key = key;
                this.identity = identity;
            }
        }

        @SuppressWarnings("rawtypes")
        List<IMEInventoryHandler> handlers() {
            List<IMEInventoryHandler> result = new ArrayList<>();
            for (Slot slot : slots) result.add(slot.limited);
            result.add(allocator);
            return result;
        }

        NBTTagList write() {
            NBTTagList result = new NBTTagList();
            for (Slot slot : slots) result.appendTag(slot.cell.writeToNBT(new NBTTagCompound()));
            for (NBTTagCompound raw : frozen) result.appendTag(raw.copy());
            return result;
        }

        void read(NBTTagList list) {
            for (Slot slot : slots) {
                slot.active = false;
                nativeChanges.remove(((ICellInventoryHandler<?>) slot.nativeInventory).getCellInv());
            }
            slots.clear();
            locations.clear();
            identities.clear();
            structure++;
            probe = null;
            probeIdentity = null;
            openSlots.clear();
            frozen.clear();
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound raw = list.getCompoundTagAt(i);
                ItemStack cell = ItemStack.loadItemStackFromNBT(raw);
                if (cell == null || cell.stackSize != 1) throw new IllegalStateException(
                    "Missing or invalid native storage cell item; refusing an empty replacement");
                validateContents(cell);
                if (hasMissingContents(cell)) {
                    frozen.add((NBTTagCompound) raw.copy());
                    continue;
                }
                Slot slot = new Slot(cell);
                slots.add(slot);
                watch(slot);
                index(slot);
            }
        }

        void validateContents(ItemStack cell) {
            NBTTagCompound tag = cell.getTagCompound();
            if (tag == null) return;
            boolean item = type == AEItemStackType.ITEM_STACK_TYPE;
            int types = tag.getShort(item ? "it" : "ft");
            long count = tag.getLong(item ? "ic" : "fc");
            if (types < 0 || types > 63 || count < 0) throw new IllegalStateException("Invalid native cell counters");
            long actual = 0;
            for (int index = 0; index < types; index++) {
                if (!tag.hasKey("#" + index, 10)) throw new IllegalStateException("Missing native cell stack");
                long amount = tag.getLong("@" + index);
                if (amount <= 0) amount = tag.getCompoundTag("#" + index)
                    .getLong("Cnt");
                if (amount <= 0) throw new IllegalStateException("Invalid native cell quantity");
                actual = Math.addExact(actual, amount);
            }
            if (actual != count) throw new IllegalStateException("Native cell counters do not match stack quantities");
        }

        boolean hasMissingContents(ItemStack cell) {
            NBTTagCompound tag = cell.getTagCompound();
            if (tag == null) return false;
            for (Object raw : tag.func_150296_c()) {
                String name = (String) raw;
                if (!name.startsWith("#")) continue;
                NBTTagCompound value = tag.getCompoundTag(name);
                if (type == AEItemStackType.ITEM_STACK_TYPE ? AEItemStack.loadItemStackFromNBT(value) == null
                    : AEFluidStack.loadFluidStackFromNBT(value) == null) return true;
            }
            return false;
        }

        final class Slot {

            final ItemStack cell;
            final IMEInventoryHandler<T> nativeInventory;
            final IMEInventoryHandler<T> limited;
            final Set<Resource> keys = new HashSet<>();
            boolean active = true;
            boolean open;

            @SuppressWarnings("unchecked")
            Slot(ItemStack cell) {
                if (cell == null || cell.getItem() == null)
                    throw new IllegalStateException("Native AE/AE2FC Universe storage cells must be enabled");
                this.cell = cell;
                if (type == AEItemStackType.ITEM_STACK_TYPE
                    ? !(cell.getItem() instanceof appeng.items.storage.ItemBasicStorageCell)
                    : !(cell.getItem() instanceof com.glodblock.github.common.item.FCBaseItemCell))
                    throw new IllegalStateException("Public storage requires native AE/AE2FC storage media");
                nativeInventory = (IMEInventoryHandler<T>) CellInventory.getCell(cell, FrozenNativeMeCellArray1342.this, type);
                if (!(nativeInventory instanceof ICellInventoryHandler<?>))
                    throw new IllegalStateException("Not a native AE CellInventory: " + cell);
                if (((ICellInventoryHandler<?>) nativeInventory).getCellInv()
                    .isOverflow())
                    throw new IllegalStateException("Void-overflow upgrades are not valid public storage");
                limited = new MEInventoryHandler<T>(nativeInventory, type) {

                    @Override
                    public AccessRestriction getAccess() {
                        return nativeInventory.getAccess();
                    }

                    @Override
                    public boolean isPrioritized(T input) {
                        return nativeInventory.isPrioritized(input);
                    }

                    @Override
                    public boolean canAccept(T input) {
                        return active && published && nativeInventory.canAccept(input);
                    }

                    @Override
                    public boolean validForPass(int pass) {
                        return active && published && nativeInventory.validForPass(pass);
                    }

                    @Override
                    public T injectItems(T input, Actionable mode, BaseActionSource source) {
                        if (!active || !published || input == null || input.getStackSize() <= 0) return input;
                        long allowed = Math.min(input.getStackSize(), room(input));
                        if (allowed <= 0) return input;
                        T request = allowed == input.getStackSize() ? input
                            : input.copy()
                                .setStackSize(allowed);
                        T rest = inject(Slot.this, request, mode, source);
                        long remaining = input.getStackSize() - allowed + (rest == null ? 0 : rest.getStackSize());
                        return remaining == 0 ? null
                            : input.copy()
                                .setStackSize(remaining);
                    }

                    @Override
                    public T extractItems(T input, Actionable mode, BaseActionSource source) {
                        if (!active || !published) return null;
                        if (input == null || input.getStackSize() <= 0) return null;
                        Resource present = locate(input);
                        if (present == null || !present.slots.contains(Slot.this)) return null;
                        return extract(Slot.this, input, mode, source);
                    }

                    @Override
                    public T getAvailableItem(T request, int iteration) {
                        return active && published ? nativeInventory.getAvailableItem(request, iteration) : null;
                    }

                    @Override
                    public IItemList<T> getAvailableItems(IItemList<T> out, int iteration) {
                        return active && published ? nativeInventory.getAvailableItems(out, iteration) : out;
                    }
                };
            }
        }
    }
}
