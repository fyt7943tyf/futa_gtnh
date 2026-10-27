package com.futa_gtnh.me.logistics;

import java.util.function.Supplier;

import com.futa_gtnh.ae2.PublicStorageAccess;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.storagebus.TileEntityStorageBus;

import appeng.api.config.Actionable;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

/** Exception boundary around native external adaptors. Unknown foreign results stay unavailable for reconciliation. */
public final class NativeBusSafety {

    private NativeBusSafety() {}

    public static <T extends IAEStack<T>> T transfer(TileEntityStorageBus tile, T request, Actionable mode,
        boolean input, Supplier<T> nativeOperation) {
        if (request == null) return nativeOperation.get();
        if (!tile.failure()
            .isEmpty()) return input ? request : null;
        if (mode != Actionable.MODULATE) return nativeOperation.get();
        Object key = request instanceof IAEItemStack item ? ItemKey.fromNative(item)
            : FluidKey.fromNative((IAEFluidStack) request);
        // A guarded native IO worker already owns the outer operation and its escrow.
        if (PublicStorageAccess.ownedTransferCredit(key) > 0) {
            try {
                return nativeOperation.get();
            } catch (RuntimeException | LinkageError error) {
                tile.nativeBus()
                    .fault(error);
                throw error;
            }
        }
        if (tile.getWorldObj() != null) SharedStorageManager.requireNativeLogisticsGuard();
        SharedStorage storage = SharedStorageManager.getStorage();
        SharedStorage.OwnedTransfer hold = key instanceof ItemKey item
            ? storage.holdOwnedItemTransfer(item, request.getStackSize())
            : storage.holdOwnedFluidTransfer((FluidKey) key, request.getStackSize());
        if (hold == null) return input ? request : null;
        try {
            T result = PublicStorageAccess.withOwnedTransferCredit(key, request.getStackSize(), nativeOperation);
            if (result != null && (!request.isSameType(result) || result.getStackSize() < 0
                || result.getStackSize() > request.getStackSize()))
                throw new IllegalStateException("Invalid native external adaptor result");
            // The normal native return transfers ownership to/from its caller. No stock is held by this adapter.
            hold.setOwnedAmount(0);
            hold.finish(0);
            return result;
        } catch (RuntimeException | LinkageError error) {
            tile.nativeBus()
                .fault(error);
            throw error;
        }
    }
}
