package com.futa_gtnh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.ae2.NeoEcoAeUploadCompat;

/**
 * Recognizes ProgrammableHatches slave dual-input hatches as programmable targets in {@code neoecoae}.
 */
@Pseudo
@Mixin(targets = "cn.dancingsnow.neoecoae.compat.proghatches.upload.ProgrammableHatchUpload", remap = false)
public abstract class MixinNeoEcoAeProgrammableHatchUpload {

    @Inject(
        method = "isProgrammingInput(Ljava/lang/Object;)Z",
        at = @At("RETURN"),
        cancellable = true,
        require = 1,
        remap = false)
    private static void futa$recognizePhSlaveHatch(Object target, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ() && NeoEcoAeUploadCompat.isPhSlaveProgrammingInput(target)) {
            cir.setReturnValue(true);
        }
    }
}
