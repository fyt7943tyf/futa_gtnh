package com.futa_gtnh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.me.PublicMeFlowTracking;
import com.futa_gtnh.me.PublicMeTrackedInventory;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEStack;
import appeng.me.cache.NetworkMonitor;

/** AE invokes monitorDifference only after a real, outermost inject/extract operation returns. */
@Mixin(value = NetworkMonitor.class, remap = false)
public abstract class MixinAeNetworkMonitorSupply {

    @Shadow
    public abstract IGrid getGrid();

    @Shadow
    public abstract IMEInventoryHandler<?> getHandler();

    @Inject(method = "monitorDifference", at = @At("HEAD"), require = 1, remap = false)
    private void futa$committedNetworkTransfer(IAEStack<?> request, IAEStack<?> result, boolean extraction,
        BaseActionSource source, CallbackInfoReturnable<IAEStack<?>> cir) {
        // Native handlers count at their own boundary, including direct parent-network access.
        // Retain the monitor fallback for inventories supplied by other implementations.
        if (!(getHandler() instanceof PublicMeTrackedInventory))
            PublicMeFlowTracking.committed(getGrid(), request, result, extraction, source);
    }
}
