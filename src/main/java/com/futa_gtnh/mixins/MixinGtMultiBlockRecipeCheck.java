package com.futa_gtnh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.ae2.StockingBusGuard;

import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/** Marks the span of {@code checkRecipe()} so the stocking bus hook knows a failed pull really aborted a recipe. */
@Mixin(value = MTEMultiBlockBase.class, remap = false)
public abstract class MixinGtMultiBlockRecipeCheck {

    @Inject(method = "checkRecipe()Z", at = @At("HEAD"), require = 1, remap = false)
    private void futa$enterRecipeCheck(CallbackInfoReturnable<Boolean> cir) {
        StockingBusGuard.enterRecipeCheck();
    }

    @Inject(method = "checkRecipe()Z", at = @At("RETURN"), require = 1, remap = false)
    private void futa$leaveRecipeCheck(CallbackInfoReturnable<Boolean> cir) {
        StockingBusGuard.leaveRecipeCheck();
    }
}
