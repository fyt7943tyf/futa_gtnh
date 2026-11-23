package com.futa_gtnh.mixins;

import java.util.function.ToIntFunction;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.futa_gtnh.miner.MinerMeOutputBus;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import appeng.api.storage.data.IAEStack;
import gregtech.common.tileentities.machines.outputme.base.MTEHatchOutputMEBase;
import gregtech.common.tileentities.machines.outputme.util.AECacheCounter;

@Mixin(value = MTEHatchOutputMEBase.class, remap = false)
public abstract class MixinMinerMeOutputProvider<T extends IAEStack<T>> implements MinerMeOutputBus.PendingCache<T> {

    @Shadow
    @Final
    private MTEHatchOutputMEBase.Environment<T> env;

    @Shadow
    @Final
    protected AECacheCounter<T> cache;

    @Unique
    private boolean futa$flushingCache;

    // AE insertion can invoke callbacks before the cache entry is updated. Serialize both flush paths on this provider.
    @WrapMethod(method = "flushCachedStack()V", require = 1, remap = false)
    private void futa$guardNativeFlush(Operation<Void> original) {
        if (!(env instanceof MinerMeOutputBus)) {
            original.call();
            return;
        }
        if (futa$flushingCache) return;
        futa$flushingCache = true;
        try {
            original.call();
        } finally {
            futa$flushingCache = false;
        }
    }

    @Override
    public int futa$drainPending(int budget, ToIntFunction<T> insert) {
        if (budget <= 0 || futa$flushingCache || cache.isEmpty()) return 0;
        int[] moved = { 0 };
        futa$flushingCache = true;
        try {
            cache.updateAll((key, amount) -> {
                int offered = (int) Math.min(amount, budget - moved[0]);
                if (offered <= 0) return amount;
                // The provider's cache key must keep its original identity and tags.
                T stack = key.copy()
                    .setStackSize(offered);
                int accepted = Math.max(0, Math.min(offered, insert.applyAsInt(stack)));
                moved[0] += accepted;
                return amount - accepted;
            });
        } finally {
            futa$flushingCache = false;
            if (moved[0] > 0) env.dispatchMarkDirty();
        }
        return moved[0];
    }
}
