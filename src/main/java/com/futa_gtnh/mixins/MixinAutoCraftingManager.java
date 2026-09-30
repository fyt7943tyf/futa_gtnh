package com.futa_gtnh.mixins;

import net.minecraft.client.gui.inventory.GuiContainer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.client.nei.NeiInventoryBridge;

import codechicken.nei.ItemStackAmount;
import codechicken.nei.recipe.AutoCraftingManager;

/**
 * 让 NEI 的链式计算看到共享存储的完整库存，而不是当前页面的虚拟展示槽。
 *
 * <p>
 * 这个目标类属于可选的 NEI，只在 NEI 存在时由 {@code FutaGtnhMixinPlugin} 启用。
 * 方法本身只改返回的本地统计，不改变 NEI 的配方计算和书签数据。
 */
@Mixin(value = AutoCraftingManager.class)
public abstract class MixinAutoCraftingManager {

    @Inject(method = "getInventoryItems", at = @At("RETURN"), cancellable = true, remap = false)
    private static void futa$includeSharedStorage(GuiContainer gui, CallbackInfoReturnable<ItemStackAmount> cir) {
        NeiInventoryBridge.replaceSharedInventory(gui, cir.getReturnValue());
    }
}
