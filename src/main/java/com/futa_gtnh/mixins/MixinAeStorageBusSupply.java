package com.futa_gtnh.mixins;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.futa_gtnh.ae2.AeSupplyTracking;

import appeng.api.config.AccessRestriction;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStackType;
import appeng.me.storage.StorageBusInventoryHandler;
import appeng.parts.misc.PartStorageBus;

/** Wrap only storage-bus inventories, retaining all of AE's filtering and network-list optimizations. */
@Mixin(value = PartStorageBus.class, remap = false)
public abstract class MixinAeStorageBusSupply {

    @Redirect(
        method = "getInternalHandler",
        at = @At(value = "NEW", target = "appeng/me/storage/StorageBusInventoryHandler"),
        require = 1,
        remap = false)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private StorageBusInventoryHandler futa$supplyInlet(IMEInventory inventory, IAEStackType type,
        Supplier<AccessRestriction> access) {
        return AeSupplyTracking.bus(inventory, type, access, (PartStorageBus) (Object) this);
    }
}
