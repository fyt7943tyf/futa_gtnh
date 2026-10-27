package com.futa_gtnh.mixins;

import org.spongepowered.asm.lib.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.futa_gtnh.ae2.AeSupplyTracking;

import appeng.api.storage.data.IAEStackType;
import appeng.me.storage.MEInventoryHandler;
import appeng.parts.misc.PartStorageBus;

/** Wrap the native result after construction; AE 1069 and 1082 use different constructor signatures. */
@Mixin(value = PartStorageBus.class, remap = false)
public abstract class MixinAeStorageBusSupply {

    @Shadow
    private MEInventoryHandler handler;

    @Redirect(
        method = "getInternalHandler",
        at = @At(
            value = "FIELD",
            target = "Lappeng/parts/misc/PartStorageBus;handler:Lappeng/me/storage/MEInventoryHandler;",
            opcode = Opcodes.PUTFIELD),
        require = 1,
        remap = false)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void futa$supplyInlet(PartStorageBus part, MEInventoryHandler nativeHandler) {
        handler = nativeHandler == null ? null
            : AeSupplyTracking.wrap(nativeHandler, (IAEStackType) part.getStackType(), part);
    }
}
