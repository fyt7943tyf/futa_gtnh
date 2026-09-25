package com.futa_gtnh.mixins;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.util.DamageSource;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 避免火焰和岩浆伤害销毁掉落物。 */
@Mixin(EntityItem.class)
public abstract class MixinEntityItem {

    @Inject(method = "attackEntityFrom", at = @At("HEAD"), cancellable = true, require = 1)
    private void futa$ignoreFireDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (source != null && source.isFireDamage()) {
            cir.setReturnValue(false);
        }
    }
}
