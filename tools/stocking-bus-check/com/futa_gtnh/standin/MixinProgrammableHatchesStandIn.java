package com.futa_gtnh.standin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;
import appeng.util.Platform;
import gregtech.common.tileentities.machines.MTEHatchInputBusME;

/**
 * Same shape as ProgrammableHatches' {@code MixinMEBusOverride}: a required redirect of the powered extraction call
 * inside the stocking bus. If FutaGTNH claimed that call site this mixin would fail with "0/1 succeeded" at class load,
 * which is exactly the crash seen with ProgrammableHatches 0.2.1p7.
 */
@Mixin(value = MTEHatchInputBusME.class, remap = false)
public abstract class MixinProgrammableHatchesStandIn {

    @Redirect(
        method = "endRecipeProcessing",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/util/Platform;poweredExtraction(Lappeng/api/networking/energy/IEnergySource;Lappeng/api/storage/IMEInventory;Lappeng/api/storage/data/IAEStack;Lappeng/api/networking/security/BaseActionSource;)Lappeng/api/storage/data/IAEStack;"),
        require = 1,
        remap = false)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private IAEStack standIn(IEnergySource energy, IMEInventory cell, IAEStack request, BaseActionSource src) {
        com.futa_gtnh.ae2.StandInCalls.calls++;
        return Platform.poweredExtraction(energy, cell, request, src);
    }
}
