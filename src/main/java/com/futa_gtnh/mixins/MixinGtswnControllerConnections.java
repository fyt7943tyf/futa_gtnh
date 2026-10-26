package com.futa_gtnh.mixins;

import java.util.EnumSet;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.futa_gtnh.me.GtswnQuantumCompat;

/** Extend the quantum face filter at its common commit point, including periodic/reload maintenance. */
@Pseudo
@Mixin(targets = "com.miaokatze.gtswn.common.quantum.QuantumControllerEventHandler", remap = false)
public abstract class MixinGtswnControllerConnections {

    @Inject(method = "setValidSidesIfChanged", at = @At("HEAD"), require = 1, remap = false)
    private static void futa$preservePublicPorts(TileEntity tile, EnumSet<ForgeDirection> allowed, CallbackInfo ci) {
        GtswnQuantumCompat.preservePorts(tile, allowed);
    }
}
