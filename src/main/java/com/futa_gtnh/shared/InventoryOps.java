package com.futa_gtnh.shared;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

/**
 * 服务端消耗玩家物品的统一姿势。
 *
 * <p>
 * 直接 {@code --stackSize} 的写法有个实测过的坑：用掉最后一组时，槽位里会留下
 * 一个 size=0 的「幽灵堆叠」——客户端照样把它渲染成手里有东西（不显示数量），
 * 且后续 {@code getHeldItem()} 非 null、各种守卫全过，表现为<b>无限使用</b>。
 * 这里在扣减后按<b>实例</b>找到它所在的槽位清空（正常就是玩家此刻拿着的
 * currentItem 槽；按实例而不是按 currentItem 找，是为了兼容直接传入独立堆叠
 * 的回归测试夹具）。
 */
public final class InventoryOps {

    private InventoryOps() {}

    /** 扣 1 件；创造模式不扣。用完即从背包槽位移除（不留 size=0 残留）。 */
    public static void consumeOne(EntityPlayer player, ItemStack stack) {
        if (player == null || stack == null || player.capabilities.isCreativeMode) return;
        stack.stackSize -= 1;
        removeIfEmpty(player, stack);
    }

    /**
     * 堆叠已耗尽（{@code stackSize <= 0}，例如耐久工具断裂后的 vanilla
     * {@code damageItem} 残留）时，按实例清掉它所在的槽位；不在背包里则无事发生。
     */
    public static void removeIfEmpty(EntityPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.stackSize > 0) return;
        InventoryPlayer inventory = player.inventory;
        for (int i = 0; i < inventory.mainInventory.length; i++) {
            if (inventory.mainInventory[i] == stack) {
                inventory.mainInventory[i] = null;
                inventory.markDirty();
                return;
            }
        }
    }
}
