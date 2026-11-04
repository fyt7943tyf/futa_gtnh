package com.futa_gtnh.reactor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;

import baubles.api.BaublesApi;

/**
 * Baubles 饰品栏的隔离访问层 —— <b>只有这个类 import baubles 类型</b>。
 *
 * <p>
 * 与 {@code com.futa_gtnh.lootassist.LootgameSneakComplete} 同一个套路：import
 * 联动模组类型的类必须只在联动在场时才被加载，守卫（{@code Loader.isModLoaded}）
 * 和首次调用写在同一行（见 {@link ReactorAeTerminal#find}）。没装 Baubles 时
 * 这个类永远不会被触碰，签名也只暴露 ItemStack，不泄露 baubles 类型。
 */
final class ReactorAeBaubles {

    private ReactorAeBaubles() {}

    /** @return 饰品栏里当前所有非空物品栈（Bauble 容器里的无线终端就是从这找到的） */
    static List<ItemStack> scan(net.minecraft.entity.player.EntityPlayerMP player) {
        List<ItemStack> stacks = new ArrayList<>();
        try {
            net.minecraft.inventory.IInventory inventory = BaublesApi.getBaubles(player);
            if (inventory == null) return stacks;
            for (int i = 0; i < inventory.getSizeInventory(); i++) {
                ItemStack stack = inventory.getStackInSlot(i);
                if (stack != null && stack.getItem() != null) stacks.add(stack);
            }
        } catch (Throwable ignored) {
            // 第三方 Baubles 分支的 API 差异不该拖垮终端查找
        }
        return stacks;
    }
}
