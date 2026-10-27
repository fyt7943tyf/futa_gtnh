package com.futa_gtnh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.util.item.AEItemDef;

/** Use AE's own definition copy, then rehash it just as AE's native fuzzy-bound copies do. */
@Mixin(value = AEItemDef.class, remap = false)
public interface MixinAeItemDefIdentity {

    @Invoker(value = "copy", remap = false)
    AEItemDef futa$copyDefinition();

    @Invoker(value = "reHash", remap = false)
    void futa$rehashDefinition();
}
