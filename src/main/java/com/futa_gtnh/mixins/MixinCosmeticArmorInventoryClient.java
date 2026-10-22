package com.futa_gtnh.mixins;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.futa_gtnh.cosmetic.client.CosmeticArmorClient;

/** Both CAR sync packets and local GUI/render lookups use the server UUID's existing cache. */
@Pseudo
@Mixin(targets = "lain.mods.cos.client.InventoryManagerClient", remap = false)
public abstract class MixinCosmeticArmorInventoryClient {

    @ModifyVariable(method = "getCosArmorInventoryClient", at = @At("HEAD"), argsOnly = true, require = 1)
    private UUID futa$resolveCosmeticArmorIdentity(UUID requested) {
        return CosmeticArmorClient.resolve(requested);
    }
}
