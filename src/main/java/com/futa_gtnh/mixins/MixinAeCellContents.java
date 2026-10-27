package com.futa_gtnh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import appeng.api.storage.data.IItemList;
import appeng.me.storage.CellInventory;

/** Force the native decoder and visit its existing contents without constructing a second complete item list. */
@Mixin(value = CellInventory.class, remap = false)
public interface MixinAeCellContents {

    @Invoker(value = "getCellStacks", remap = false)
    IItemList<?> futa$loadedStacks();
}
