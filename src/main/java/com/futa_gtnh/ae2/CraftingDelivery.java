package com.futa_gtnh.ae2;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

/** Moves real stock into the crafting cache; ordinary network cells must never receive this offer. */
final class CraftingDelivery {

    private CraftingDelivery() {}

    static void item(SharedStorage storage, ItemKey key, IAEItemStack template, IMEInventory<IAEItemStack> crafting,
        BaseActionSource source) {
        transfer(storage.reserveItemForCrafting(key, template.getStackSize()), template, crafting, source);
    }

    static void fluid(SharedStorage storage, FluidKey key, IAEFluidStack template, IMEInventory<IAEFluidStack> crafting,
        BaseActionSource source) {
        transfer(storage.reserveFluidForCrafting(key, template.getStackSize()), template, crafting, source);
    }

    private static <T extends IAEStack<T>> void transfer(SharedStorage.CraftingReservation reservation, T template,
        IMEInventory<T> crafting, BaseActionSource source) {
        if (reservation == null) return;
        T offer;
        try {
            offer = template.copy();
            offer.setStackSize(reservation.getAmount());
        } catch (RuntimeException | Error error) {
            // No AE call has happened yet, so the entire reservation is still ours.
            reservation.finish(reservation.getAmount());
            throw error;
        }
        // On an exception the CPU may already have changed its inventory or completed a job.
        // Keep the reservation in durable escrow instead of guessing and duplicating a refund.
        T remainder = crafting.injectItems(offer, Actionable.MODULATE, source);
        long returned = remainder == null ? 0L : remainder.getStackSize();
        if (returned < 0L || returned > reservation.getAmount()
            || (remainder != null && !template.isSameType(remainder))) {
            throw new IllegalStateException("Crafting cache returned an invalid remainder; stock retained in escrow");
        }
        // A final output may return in full after the CPU has acknowledged it. That is a valid transfer.
        reservation.finish(returned);
    }
}
