package com.futa_gtnh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.llamalad7.mixinextras.sugar.Local;

/**
 * Ensures patterns containing non-circuit PH programming items (molds, dies, lenses) still require a programmable
 * target, demoting plain buses/interfaces to {@code CIRCUIT_CONFLICT} so PH hatches and programming covers rank first.
 */
@Pseudo
@Mixin(targets = "cn.dancingsnow.neoecoae.platform.gt.upload.GtPatternRoutes", remap = false)
public abstract class MixinNeoEcoAeGtPatternRoutes {

    @ModifyVariable(
        method = "evaluate(Lcn/dancingsnow/neoecoae/platform/gt/upload/GtRecipeVerifier$Cache;Lcn/dancingsnow/neoecoae/platform/gt/upload/GtPatternRoutes$Route;Lcn/dancingsnow/neoecoae/crafting/upload/PatternContext;Ljava/lang/String;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemStack;ZZ)Lcn/dancingsnow/neoecoae/platform/gt/upload/GtPatternRoutes$Candidate;",
        at = @At(
            value = "INVOKE",
            target = "Lcn/dancingsnow/neoecoae/platform/gt/upload/GtRecipeVerifier$Cache;verify(Lgregtech/api/recipe/RecipeMap;Lappeng/api/networking/crafting/ICraftingPatternDetails;Lnet/minecraft/item/ItemStack;)Lcn/dancingsnow/neoecoae/platform/gt/upload/GtRecipeVerifier$Verdict;"),
        index = 9,
        require = 1,
        remap = false)
    private static String futa$requireProgrammingTarget(String conflictReason,
        @Local(argsOnly = true, ordinal = 0) boolean hasProgrammingCircuit,
        @Local(argsOnly = true, ordinal = 1) boolean allowProgramming) {
        if (conflictReason == null && hasProgrammingCircuit && !allowProgramming) {
            return "gui.neoecoae.pattern_upload.reason.needs_programming";
        }
        return conflictReason;
    }
}
