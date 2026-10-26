package com.futa_gtnh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.me.PublicMeFlowTracking;

import appeng.api.networking.IGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEStack;
import appeng.me.cache.NetworkMonitor;

/** AE invokes monitorDifference only after a real, outermost inject/extract operation returns. */
@Mixin(value = NetworkMonitor.class, remap = false)
public abstract class MixinAeNetworkMonitorSupply {

    @Shadow
    public abstract IGrid getGrid();

    @Inject(method = "monitorDifference", at = @At("HEAD"), require = 1, remap = false)
    private void futa$committedNetworkTransfer(IAEStack<?> request, IAEStack<?> result, boolean extraction,
        BaseActionSource source, CallbackInfoReturnable<IAEStack<?>> cir) {
        PublicMeFlowTracking.committed(getGrid(), request, result, extraction, source);
    }
}
