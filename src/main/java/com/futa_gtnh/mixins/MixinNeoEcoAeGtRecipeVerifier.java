package com.futa_gtnh.mixins;

import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.futa_gtnh.ae2.NeoEcoAeUploadCompat;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import gregtech.api.util.GTRecipe;

/**
 * Extends {@code GtRecipeVerifier.ratio()} so {@code GTRecipe_WithAlt} ore-dict alternatives (such as colored laser
 * engraver lenses) match when the pattern uses a non-representative lens variant.
 */
@Pseudo
@Mixin(targets = "cn.dancingsnow.neoecoae.platform.gt.upload.GtRecipeVerifier", remap = false)
public abstract class MixinNeoEcoAeGtRecipeVerifier {

    @WrapOperation(
        method = "ratio(Lgregtech/api/util/GTRecipe;Lcn/dancingsnow/neoecoae/platform/gt/upload/GtRecipeVerifier$Pattern;)Lcn/dancingsnow/neoecoae/platform/gt/upload/GtRecipeVerifier$Verdict;",
        at = @At(
            value = "INVOKE",
            target = "Lcn/dancingsnow/neoecoae/platform/gt/upload/GtRecipeVerifier;sameItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;)Z"),
        require = 1,
        remap = false)
    private static boolean futa$matchOreDictAlt(ItemStack recipeInput, ItemStack patternItem,
        Operation<Boolean> original, @Local(argsOnly = true) GTRecipe recipe) {
        return original.call(recipeInput, patternItem)
            || NeoEcoAeUploadCompat.matchesRecipeAltInput(recipe, recipeInput, patternItem);
    }
}
