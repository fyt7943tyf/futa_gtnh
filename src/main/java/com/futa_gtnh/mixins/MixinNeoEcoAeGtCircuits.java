package com.futa_gtnh.mixins;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.futa_gtnh.ae2.NeoEcoAeUploadCompat;

/**
 * Allows {@code neoecoae} recipe verification to unwrap non-circuit items (molds, dies, lenses) wrapped in a
 * ProgrammableHatches {@code ItemProgrammingCircuit} without polluting the 0-24 GT circuit number routing system.
 */
@Pseudo
@Mixin(targets = "cn.dancingsnow.neoecoae.platform.gt.upload.GtCircuits", remap = false)
public abstract class MixinNeoEcoAeGtCircuits {

    @Redirect(
        method = "normalizeForRecipe(Lnet/minecraft/item/ItemStack;)Lnet/minecraft/item/ItemStack;",
        at = @At(
            value = "INVOKE",
            target = "Lcn/dancingsnow/neoecoae/platform/gt/upload/GtCircuits;unwrap(Lnet/minecraft/item/ItemStack;)Lnet/minecraft/item/ItemStack;"),
        require = 1,
        remap = false)
    private static ItemStack futa$unwrapAnyProgrammingItem(ItemStack stack) {
        return NeoEcoAeUploadCompat.unwrapProgrammingItem(stack);
    }
}
