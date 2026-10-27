package com.futa_gtnh.me.logistics;

import java.util.Iterator;

import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import com.futa_gtnh.ae2.PublicStorageAccess;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.config.InsertionMode;
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
import appeng.util.inv.IInventoryDestination;
import appeng.util.inv.ItemSlot;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;

/** Ownership guards around native callbacks, never a second container scanner or transfer worker. */
final class NativeTransferGuard {

    private final NativeIoRuntime owner;
    private SharedStorage.OwnedTransfer escrow;
    private IAEStack<?> held;
    private long remaining;
    private boolean importing;
    private boolean stagedFluid;
    private IAEFluidStack staged;
    private BaseActionSource stagedSource;
    private IMEMonitor<IAEFluidStack> stagedMonitor;

    NativeTransferGuard(NativeIoRuntime owner) {
        this.owner = owner;
    }

    private boolean begin(IAEStack<?> stack) {
        if (owner != null) SharedStorageManager.requireNativeLogisticsGuard();
        finish();
        if (stack == null || stack.getStackSize() <= 0) return false;
        SharedStorage storage = SharedStorageManager.getStorage();
        escrow = stack instanceof IAEItemStack item
            ? storage.holdOwnedItemTransfer(ItemKey.fromNative(item), stack.getStackSize())
            : storage.holdOwnedFluidTransfer(FluidKey.fromNative((IAEFluidStack) stack), stack.getStackSize());
        if (escrow == null) return false;
        held = stack.copy();
        remaining = stack.getStackSize();
        return true;
    }

    private void actual(IAEStack<?> stack) {
        if (stack == null) {
            consumed(remaining);
            finish();
            return;
        }
        if (held == null || !held.isSameType(stack) || stack.getStackSize() > remaining) {
            consumed(remaining);
            finish();
            Object actualKey = stack instanceof IAEItemStack item ? ItemKey.fromNative(item)
                : FluidKey.fromNative((IAEFluidStack) stack);
            SharedStorageManager.getStorage()
                .quarantineIoReturn(actualKey, stack.getStackSize());
            throw new IllegalStateException(
                "Foreign callback changed the reserved resource or exceeded the requested quantity");
        }
        consumed(remaining - stack.getStackSize());
    }

    private void consumed(long amount) {
        if (amount < 0 || amount > remaining) throw new IllegalStateException("Invalid native transfer result");
        remaining -= amount;
        if (escrow != null) escrow.setOwnedAmount(remaining);
    }

    void finish() {
        if (escrow != null) {
            // Only known ownership reaches here. Unknown callback results are left in durable escrow by the caller.
            long restored = escrow.finish(0);
            if (owner != null && importing && restored > 0) owner.recovered(held, restored);
            if (owner != null && restored < remaining)
                owner.pause(new IllegalStateException("Native recovery remains in durable escrow"));
            escrow = null;
            held = null;
            remaining = 0;
        }
        staged = null;
        stagedSource = null;
        stagedMonitor = null;
    }

    <T extends IAEStack<T>> IMEMonitor<T> monitor(IMEMonitor<T> delegate, IAEStackType<T> type, boolean input,
        boolean fluid) {
        importing = input;
        stagedFluid = input && fluid;
        return new GuardMonitor<T>(delegate, type) {

            @Override
            public T injectItems(T stack, Actionable mode, BaseActionSource action) {
                if (stack == null) return null;
                if (stagedFluid && mode == Actionable.MODULATE) {
                    // AE2FC inserts before draining. Reserve capacity only; the guarded actual drain commits ownership.
                    T rest = delegate.injectItems(stack.copy(), Actionable.SIMULATE, action);
                    staged = (IAEFluidStack) stack.copy();
                    staged.setStackSize(stack.getStackSize() - size(rest));
                    stagedSource = action;
                    stagedMonitor = (IMEMonitor<IAEFluidStack>) delegate;
                    return rest;
                }
                long requested = stack.getStackSize();
                T rest = PublicStorageAccess
                    .withOwnedTransferCredit(key(), remaining, () -> delegate.injectItems(stack.copy(), mode, action));
                if (mode == Actionable.MODULATE && escrow != null) consumed(requested - size(rest));
                return rest;
            }

            @Override
            public T extractItems(T request, Actionable mode, BaseActionSource action) {
                if (mode != Actionable.MODULATE || request == null) return delegate.extractItems(request, mode, action);
                T expected = delegate.extractItems(request.copy(), Actionable.SIMULATE, action);
                if (!begin(expected)) return null;
                T result = PublicStorageAccess.withOwnedTransferCredit(
                    key(),
                    remaining,
                    () -> delegate.extractItems(request.copy(), mode, action));
                actual(result);
                return result;
            }
        };
    }

    InventoryAdaptor adaptor(InventoryAdaptor delegate) {
        return delegate == null ? null : new InventoryAdaptor() {

            @Override
            public ItemStack simulateRemove(int amount, ItemStack filter, IInventoryDestination destination) {
                return delegate.simulateRemove(amount, filter, destination);
            }

            @Override
            public ItemStack removeItems(int amount, ItemStack filter, IInventoryDestination destination) {
                ItemStack expected = delegate.simulateRemove(amount, filter, destination);
                if (!begin(AEItemStack.create(expected))) return null;
                ItemStack actual = delegate.removeItems(amount, filter, destination);
                NativeTransferGuard.this.actual(AEItemStack.create(actual));
                return actual;
            }

            @Override
            public ItemStack simulateSimilarRemove(int amount, ItemStack filter, FuzzyMode mode,
                IInventoryDestination destination) {
                return delegate.simulateSimilarRemove(amount, filter, mode, destination);
            }

            @Override
            public ItemStack removeSimilarItems(int amount, ItemStack filter, FuzzyMode mode,
                IInventoryDestination destination) {
                ItemStack expected = delegate.simulateSimilarRemove(amount, filter, mode, destination);
                if (!begin(AEItemStack.create(expected))) return null;
                ItemStack actual = delegate.removeSimilarItems(amount, filter, mode, destination);
                NativeTransferGuard.this.actual(AEItemStack.create(actual));
                return actual;
            }

            @Override
            public ItemStack addItems(ItemStack stack) {
                return addItems(stack, InsertionMode.DEFAULT);
            }

            @Override
            public ItemStack addItems(ItemStack stack, InsertionMode mode) {
                int before = stack.stackSize;
                ItemStack rest = delegate.addItems(stack.copy(), mode);
                if (escrow != null) consumed(before - (rest == null ? 0 : rest.stackSize));
                return rest;
            }

            @Override
            public ItemStack simulateAdd(ItemStack stack) {
                return delegate.simulateAdd(stack.copy());
            }

            @Override
            public ItemStack simulateAdd(ItemStack stack, InsertionMode mode) {
                return delegate.simulateAdd(stack.copy(), mode);
            }

            @Override
            public IAEStack<?> addStack(IAEStack<?> stack, InsertionMode mode) {
                long before = stack.getStackSize();
                IAEStack<?> rest = delegate.addStack(stack.copy(), mode);
                if (escrow != null) consumed(before - size(rest));
                return rest;
            }

            @Override
            public IAEStack<?> simulateAddStack(IAEStack<?> stack, InsertionMode mode) {
                return delegate.simulateAddStack(stack.copy(), mode);
            }

            @Override
            public boolean containsItems() {
                return delegate.containsItems();
            }

            @Override
            public Iterator<ItemSlot> iterator() {
                return delegate.iterator();
            }
        };
    }

    IFluidHandler fluid(IFluidHandler delegate) {
        return delegate == null ? null : new IFluidHandler() {

            @Override
            public FluidStack drain(ForgeDirection side, FluidStack request, boolean execute) {
                if (!execute) return delegate.drain(side, request.copy(), false);
                if (staged == null || stagedMonitor == null
                    || !staged.isSameType(AEFluidStack.create(request))
                    || staged.getStackSize() != request.amount)
                    throw new IllegalStateException("Unreserved native fluid drain");
                IAEFluidStack expected = staged.copy();
                IMEMonitor<IAEFluidStack> destination = stagedMonitor;
                BaseActionSource action = stagedSource;
                if (!begin(expected)) return null;
                FluidStack actual = delegate.drain(side, request.copy(), true);
                NativeTransferGuard.this.actual(AEFluidStack.create(actual));
                if (actual != null && actual.amount > 0) {
                    IAEFluidStack owned = AEFluidStack.create(actual);
                    IAEFluidStack rest = PublicStorageAccess.withOwnedTransferCredit(
                        key(),
                        remaining,
                        () -> destination.injectItems(owned.copy(), Actionable.MODULATE, action));
                    consumed(owned.getStackSize() - size(rest));
                }
                finish();
                return actual;
            }

            @Override
            public FluidStack drain(ForgeDirection side, int amount, boolean execute) {
                if (execute) throw new IllegalStateException("Native fluid import must drain a specific resource");
                return delegate.drain(side, amount, false);
            }

            @Override
            public int fill(ForgeDirection side, FluidStack stack, boolean execute) {
                return delegate.fill(side, stack, execute);
            }

            @Override
            public boolean canFill(ForgeDirection side, Fluid fluid) {
                return delegate.canFill(side, fluid);
            }

            @Override
            public boolean canDrain(ForgeDirection side, Fluid fluid) {
                return delegate.canDrain(side, fluid);
            }

            @Override
            public FluidTankInfo[] getTankInfo(ForgeDirection side) {
                return delegate.getTankInfo(side);
            }
        };
    }

    private abstract static class GuardMonitor<T extends IAEStack<T>> extends MEPassThrough<T>
        implements IMEMonitor<T> {

        private final IMEMonitor<T> delegate;

        GuardMonitor(IMEMonitor<T> delegate, IAEStackType<T> type) {
            super(delegate, type);
            this.delegate = delegate;
        }

        @Override
        public IItemList<T> getStorageList() {
            return delegate.getStorageList();
        }

        @Override
        public void addListener(IMEMonitorHandlerReceiver listener, Object token) {
            delegate.addListener(listener, token);
        }

        @Override
        public void removeListener(IMEMonitorHandlerReceiver listener) {
            delegate.removeListener(listener);
        }
    }

    private static long size(IAEStack<?> stack) {
        return stack == null ? 0 : stack.getStackSize();
    }

    private Object key() {
        return held instanceof IAEItemStack item ? ItemKey.fromNative(item)
            : held instanceof IAEFluidStack fluid ? FluidKey.fromNative(fluid) : null;
    }
}
