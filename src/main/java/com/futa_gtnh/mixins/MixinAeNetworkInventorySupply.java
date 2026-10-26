package com.futa_gtnh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.futa_gtnh.me.PublicMeFlowTracking;
import com.futa_gtnh.me.PublicMeTrackedInventory;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEStack;
import appeng.me.storage.NetworkInventoryHandler;

/** The native parent inventory is the common boundary for normal and optimized subnet transfers. */
@Mixin(value = NetworkInventoryHandler.class, remap = false)
public abstract class MixinAeNetworkInventorySupply implements PublicMeTrackedInventory {

    @Unique
    private int futa$flowDepth;

    @WrapMethod(method = "extractItems", require = 1, remap = false)
    private IAEStack<?> futa$recordNativeExtraction(IAEStack<?> request, Actionable mode, BaseActionSource source,
        Operation<IAEStack<?>> original) {
        return futa$transfer(request, mode, source, true, original);
    }

    @WrapMethod(method = "injectItems", require = 1, remap = false)
    private IAEStack<?> futa$recordNativeInsertion(IAEStack<?> input, Actionable mode, BaseActionSource source,
        Operation<IAEStack<?>> original) {
        return futa$transfer(input, mode, source, false, original);
    }

    @Unique
    private IAEStack<?> futa$transfer(IAEStack<?> input, Actionable mode, BaseActionSource source, boolean extraction,
        Operation<IAEStack<?>> original) {
        IGrid grid = mode == Actionable.MODULATE && input != null && futa$flowDepth == 0
            ? PublicMeFlowTracking.inventoryGrid((IMEInventoryHandler<?>) (Object) this)
            : null;
        if (grid == null) return original.call(input, mode, source);
        IAEStack<?> snapshot;
        try {
            snapshot = input.copy();
        } catch (RuntimeException unavailable) {
            return original.call(input, mode, source);
        }
        futa$flowDepth++;
        try {
            IAEStack<?> result = original.call(input, mode, source);
            PublicMeFlowTracking.committed(grid, snapshot, result, extraction, source);
            return result;
        } finally {
            futa$flowDepth--;
        }
    }
}
