package com.futa_gtnh.me;

import java.util.function.Supplier;

import com.futa_gtnh.ae2.AeSupplyTracking;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.supply.SupplyTracker;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.security.ReshuffleActionSource;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

/** Counts committed outer network transfers, regardless of which ME cell actually stored the contents. */
public final class PublicMeFlowTracking {

    private static final ThreadLocal<Integer> SUPPRESS = ThreadLocal.withInitial(() -> 0);

    private PublicMeFlowTracking() {}

    public static boolean isSuppressed() {
        return SUPPRESS.get() > 0;
    }

    public static boolean isCapturing() {
        return PublicMeNetwork.isRunning() && !isSuppressed();
    }

    public static <T> T suppress(Supplier<T> action) {
        int previous = SUPPRESS.get();
        SUPPRESS.set(previous + 1);
        try {
            return action.get();
        } finally {
            if (previous == 0) SUPPRESS.remove();
            else SUPPRESS.set(previous);
        }
    }

    public static void suppress(Runnable action) {
        suppress(() -> {
            action.run();
            return null;
        });
    }

    public static void committed(IGrid grid, IAEStack<?> request, IAEStack<?> result, boolean extraction,
        BaseActionSource source) {
        if (!SupplyTracker.enabled() || isSuppressed()
            || !PublicMeNetwork.isRunning()
            || grid == null
            || grid != PublicMeNetwork.grid()
            || request == null
            || source instanceof ReshuffleActionSource) return;
        // The core is a routing host for Futa calls, not a physical production machine.
        if (source instanceof MachineSource machine && machine.via instanceof TilePublicMeCore) return;
        long amount = extraction ? (result == null ? 0L : result.getStackSize())
            : request.getStackSize() - (result == null ? 0L : result.getStackSize());
        if (amount <= 0) return;
        try {
            Object key = request instanceof IAEItemStack item ? ItemKey.of(item.getItemStack())
                : request instanceof IAEFluidStack fluid ? FluidKey.of(fluid.getFluidStack()) : null;
            if (key != null) AeSupplyTracking.record(key, amount, !extraction, source, grid);
        } catch (RuntimeException ignored) {
            // Diagnostics must never change the result of a completed storage operation.
        }
    }

    public static void clear() {
        SUPPRESS.remove();
    }
}
