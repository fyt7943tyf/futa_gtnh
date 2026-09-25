package com.futa_gtnh.mixins;

import net.minecraft.block.BlockReed;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** 移除原版甘蔗的三格生长高度上限。 */
@Mixin(BlockReed.class)
public abstract class MixinBlockReed {

    @ModifyConstant(method = "updateTick", constant = @Constant(intValue = 3), require = 1)
    private int futa$removeSugarCaneHeightLimit(int vanillaLimit) {
        return Integer.MAX_VALUE;
    }
}
