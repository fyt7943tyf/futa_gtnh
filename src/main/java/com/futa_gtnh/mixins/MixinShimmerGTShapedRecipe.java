/* Adapted from GTNL, LGPL-3.0; see META-INF/licenses/gtnl/NOTICE.txt. */
package com.futa_gtnh.mixins;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.futa_gtnh.disassembler.ShimmerCraftingRegistry;

import gregtech.api.util.GTShapedRecipe;

@Mixin(value = GTShapedRecipe.class, remap = false)
public class MixinShimmerGTShapedRecipe {

    @Inject(
        method = "<init>(Lnet/minecraft/item/ItemStack;ZZ[Lnet/minecraft/enchantment/Enchantment;[I[Ljava/lang/Object;)V",
        at = @At("RETURN"))
    private void init(ItemStack aResult, boolean aRemovableByGT, boolean aKeepingNBT, Enchantment[] aEnchantmentsAdded,
        int[] aEnchantmentLevelsAdded, Object[] aRecipe, CallbackInfo ci) {
        ShimmerCraftingRegistry.capture(aResult, aRecipe, true);
    }

}
