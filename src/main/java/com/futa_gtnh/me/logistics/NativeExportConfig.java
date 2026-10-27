package com.futa_gtnh.me.logistics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;

import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IBaseMonitor;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.IMEMonitorHandlerReceiver;
import appeng.api.storage.data.IAEStack;
import appeng.tile.inventory.IAEStackInventory;

/** A selector, never stock: native export workers still perform every actual extraction and insertion. */
final class NativeExportConfig<T extends IAEStack<T>> extends IAEStackInventory
    implements IMEMonitorHandlerReceiver<T> {

    private final Predicate<T> filter;
    private final Supplier<List<T>> manualSupplier;
    private final Map<T, T> manual = new LinkedHashMap<>();
    private final Map<T, T> matching = new LinkedHashMap<>();
    private List<T> slots = new ArrayList<>();
    private IMEMonitor<T> monitor;
    private int revision = Integer.MIN_VALUE;
    private boolean changed;

    NativeExportConfig(Predicate<T> filter, Supplier<List<T>> manualSupplier) {
        super(null, 0);
        this.filter = filter;
        this.manualSupplier = manualSupplier;
    }

    void prepare(IMEMonitor<T> current, int filterRevision) {
        if (monitor != current || revision != filterRevision) {
            close();
            monitor = current;
            revision = filterRevision;
            matching.clear();
            manual.clear();
            for (T stack : manualSupplier.get()) if (filter.test(stack)) manual.put(stack, stack);
            if (monitor != null) {
                for (T stack : monitor.getStorageList()) update(stack);
                monitor.addListener(this, monitor);
            }
            changed = true;
        }
        if (changed) {
            slots = new ArrayList<>(manual.values());
            for (T stack : matching.values()) if (!manual.containsKey(stack)) slots.add(stack);
            changed = false;
        }
    }

    private void update(T stack) {
        T identity = stack.copy();
        identity.setStackSize(1);
        if (stack.getStackSize() > 0 && filter.test(stack)) {
            if (!matching.containsKey(identity)) {
                matching.put(identity, identity);
                changed = true;
            }
        } else if (matching.remove(identity) != null) changed = true;
    }

    void close() {
        if (monitor != null) monitor.removeListener(this);
        monitor = null;
    }

    int manualCount() {
        return manual.size();
    }

    @Override
    public int getSizeInventory() {
        return slots.size();
    }

    @Override
    public T getAEStackInSlot(int index) {
        return slots.get(index);
    }

    @Override
    public void putAEStackInSlot(int index, IAEStack<?> stack) {
        throw new UnsupportedOperationException("Derived native export selector");
    }

    @Override
    public boolean isValid(Object token) {
        return monitor == token;
    }

    @Override
    public void postChange(IBaseMonitor<T> sender, Iterable<T> changes, BaseActionSource source) {
        if (monitor == null) return;
        for (T delta : changes) {
            T actual = monitor.getStorageList()
                .findPrecise(delta);
            if (actual == null) {
                T empty = delta.copy();
                empty.setStackSize(0);
                update(empty);
            } else update(actual);
        }
    }

    @Override
    public void onListUpdate() {
        revision = Integer.MIN_VALUE;
    }
}
