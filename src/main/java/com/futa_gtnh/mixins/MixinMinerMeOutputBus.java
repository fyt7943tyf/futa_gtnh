package com.futa_gtnh.mixins;

import java.util.function.ToIntFunction;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.futa_gtnh.miner.MinerMeOutputBus;

import appeng.api.storage.data.IAEItemStack;
import gregtech.common.tileentities.machines.outputme.MTEHatchOutputBusME;
import gregtech.common.tileentities.machines.outputme.base.MTEHatchOutputMEBase;

@Mixin(value = MTEHatchOutputBusME.class, remap = false)
public abstract class MixinMinerMeOutputBus implements MinerMeOutputBus {

    @Shadow
    @Final
    private MTEHatchOutputMEBase<IAEItemStack> provider;

    @Override
    @SuppressWarnings("unchecked")
    public int futa$drainPendingItems(int budget, ToIntFunction<IAEItemStack> insert) {
        if (!(provider instanceof PendingCache<?> pending)) return 0;
        return ((PendingCache<IAEItemStack>) pending).futa$drainPending(budget, insert);
    }
}
