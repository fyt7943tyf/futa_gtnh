package com.futa_gtnh.mixins;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.futa_gtnh.tinkers.TinkersAutoFill;

import tconstruct.tools.inventory.CraftingStationContainer;
import tconstruct.tools.inventory.SlotCraftingStation;

/**
 * 「玩家把合成产物拿走了」—— 合成站自动补料唯一认可的回填前提。
 *
 * <p>
 * 匠魂的成品槽就是 {@code SlotCraftingStation}，它覆写了 {@code onPickupFromSlot}
 * （先批量更新合成栏，再消耗每个格子的材料）。所以这一个方法就是「合成真的发生了一次」
 * 的准确定义：左键点产物、Shift + 左键点产物，都会走到这里。
 *
 * <p>
 * 为什么非要有这个信号：合成栏变少的原因有很多（玩家拿走、点「倒空」、别的模组动过），
 * 只有「拿走产物」能证明是合成吃掉的。没有它就只能靠猜，猜错的表现就是
 * <b>玩家从九宫格里把材料拿回来、下一 tick 又被补回去</b>（「拿不出来」）。
 *
 * <p>
 * 这个类自带 {@code CraftingStationContainer} 字段，所以不需要再从槽位反查容器。
 * {@code onPickupFromSlot} 是覆写原版 {@code Slot} 的方法，正式包里会被重命名，
 * 所以这里的 {@code @Inject} 保持默认的 {@code remap}（让 refmap 翻成 {@code func_82870_a}）。
 */
@Mixin(SlotCraftingStation.class)
public class MixinSlotCraftingStation {

    @Shadow(remap = false)
    private CraftingStationContainer container;

    @Inject(method = "onPickupFromSlot", at = @At("HEAD"))
    private void futa$noteCraftResultTaken(EntityPlayer player, ItemStack stack, CallbackInfo ci) {
        TinkersAutoFill.noteCraftResultTaken(this.container);
    }
}
