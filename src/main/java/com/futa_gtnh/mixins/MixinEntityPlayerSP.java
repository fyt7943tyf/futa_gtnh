package com.futa_gtnh.mixins;

import net.minecraft.client.entity.EntityPlayerSP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.futa_gtnh.item.ItemSwiftStep;

/**
 * 把迅步的飞行倍率同时应用到原版的上升/下降输入。
 * 原版 EntityPlayerSP 在飞行时对跳跃和潜行各加减 0.15D；修改这两个常量即可保留原版控制方式。
 */
@Mixin(EntityPlayerSP.class)
public abstract class MixinEntityPlayerSP {

    @ModifyConstant(method = "onLivingUpdate", constant = @Constant(doubleValue = 0.15D))
    private double futa$scaleSwiftStepVerticalFlight(double vanillaImpulse) {
        return ItemSwiftStep.scaleVerticalFlightImpulse((EntityPlayerSP) (Object) this, vanillaImpulse);
    }
}
