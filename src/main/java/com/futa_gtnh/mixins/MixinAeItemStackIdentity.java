package com.futa_gtnh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import com.futa_gtnh.me.NativeMeItemIdentity;

import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemDef;
import appeng.util.item.AEItemStack;

/** Copy the definition as well as the stack: ordinary AEItemStack.copy shares its mutable definition. */
@Mixin(value = AEItemStack.class, remap = false)
public abstract class MixinAeItemStackIdentity implements NativeMeItemIdentity {

    @Shadow
    private AEItemDef def;

    @Override
    public IAEItemStack futa$copyIdentity() {
        if (!((Object) def instanceof MixinAeItemDefIdentity))
            return AEItemStack.create(((AEItemStack) (Object) this).getItemStack());
        IAEItemStack result = ((AEItemStack) (Object) this).copy();
        AEItemDef privateDefinition = ((MixinAeItemDefIdentity) (Object) def).futa$copyDefinition();
        ((MixinAeItemDefIdentity) (Object) privateDefinition).futa$rehashDefinition();
        ((MixinAeItemStackIdentity) (Object) result).def = privateDefinition;
        return result;
    }
}
