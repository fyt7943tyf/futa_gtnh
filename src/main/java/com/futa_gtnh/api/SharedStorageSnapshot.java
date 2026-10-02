package com.futa_gtnh.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

/**
 * Detached snapshot of all currently readable item/fluid entries. Lists are immutable and prototypes
 * are copied on construction and access, including their NBT. Unreadable/quarantined save rows are
 * not exposed as usable resources. The original long amount is separate from the quantity-1 prototype.
 */
public final class SharedStorageSnapshot {

    private final SharedStorageReadStatus status;
    private final List<ItemEntry> items;
    private final List<FluidEntry> fluids;

    public SharedStorageSnapshot(SharedStorageReadStatus status, List<ItemEntry> items, List<FluidEntry> fluids) {
        this.status = Objects.requireNonNull(status, "status");
        this.items = immutableCopy(items);
        this.fluids = immutableCopy(fluids);
        if (!status.isReady() && (!this.items.isEmpty() || !this.fluids.isEmpty())) {
            throw new IllegalArgumentException("A warehouse that is not ready cannot expose inventory rows");
        }
    }

    private static <T> List<T> immutableCopy(List<T> rows) {
        List<T> copy = new ArrayList<>(Objects.requireNonNull(rows, "rows"));
        for (T row : copy) Objects.requireNonNull(row, "row");
        return Collections.unmodifiableList(copy);
    }

    public SharedStorageReadStatus getStatus() {
        return status;
    }

    public List<ItemEntry> getItems() {
        return items;
    }

    public List<FluidEntry> getFluids() {
        return fluids;
    }

    public static final class ItemEntry {

        private final ItemStack prototype;
        private final long amount;

        public ItemEntry(ItemStack prototype, long amount) {
            if (prototype == null || prototype.getItem() == null || amount <= 0L) {
                throw new IllegalArgumentException("Item entries require a valid prototype and positive long amount");
            }
            this.prototype = prototype.copy();
            this.prototype.stackSize = 1;
            this.amount = amount;
        }

        /** Each call returns a fresh quantity-1 stack with detached NBT. */
        public ItemStack getPrototype() {
            return prototype.copy();
        }

        public long getAmount() {
            return amount;
        }
    }

    public static final class FluidEntry {

        private final FluidStack prototype;
        private final long amount;

        public FluidEntry(FluidStack prototype, long amount) {
            if (prototype == null || prototype.getFluid() == null || amount <= 0L) {
                throw new IllegalArgumentException("Fluid entries require a valid prototype and positive long amount");
            }
            this.prototype = prototype.copy();
            this.prototype.amount = 1;
            this.amount = amount;
        }

        /** Each call returns a fresh 1 mB stack with detached NBT. */
        public FluidStack getPrototype() {
            return prototype.copy();
        }

        /** Millibuckets, without int/stack-size clamping. */
        public long getAmount() {
            return amount;
        }
    }
}
