package com.futa_gtnh.client.nei;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.client.ClientStorageCache;
import com.futa_gtnh.client.GuiSharedTerminal;
import com.futa_gtnh.client.TinkersScreens;
import com.futa_gtnh.inventory.SlotSharedStorage;
import com.futa_gtnh.station.SharedStorageInventory;

import codechicken.nei.ItemStackAmount;

/**
 * 把共享存储的权威客户端缓存接到 NEI 合成链的本地库存视图。
 *
 * <p>
 * 共享终端和匠魂合成站里的存储格是展示槽，不代表真实堆叠数量。NEI 原本会把这些
 * 槽位各算成 1 个物品，既会污染链式计算，也会把当前页误当成全部库存。这里保留
 * 玩家真实槽位和合成栏，再把共享存储的完整键值快照合并进去。
 */
public final class NeiInventoryBridge {

    private NeiInventoryBridge() {}

    /** 替换 NEI 对共享存储界面的本地库存统计。 */
    public static void replaceSharedInventory(GuiContainer gui, ItemStackAmount inventory) {
        if (gui == null || inventory == null || !isSharedCraftingGui(gui)) return;

        List<ItemStack> realStacks = new ArrayList<>();
        for (Slot slot : gui.inventorySlots.inventorySlots) {
            if (slot == null || !slot.getHasStack() || slot instanceof SlotCrafting || isSharedSlot(slot)) continue;
            if (!slot.canTakeStack(gui.mc.thePlayer)) continue;

            ItemStack stack = slot.getStack();
            if (stack != null && stack.stackSize > 0) {
                realStacks.add(stack.copy());
            }
        }

        inventory.clear();
        inventory.addAll(realStacks);

        for (ClientStorageCache.ItemAmount stored : ClientStorageCache.itemAmounts()) {
            if (stored == null || stored.getKey() == null || stored.getAmount() <= 0L) continue;
            inventory.add(
                stored.getKey()
                    .prototype(Math.min(stored.getAmount(), Integer.MAX_VALUE)),
                stored.getAmount());
        }
    }

    private static boolean isSharedCraftingGui(GuiContainer gui) {
        if (gui instanceof GuiSharedTerminal) return true;

        try {
            return TinkersScreens.sharedChestStation(gui) != null;
        } catch (Throwable ignored) {
            // 匠魂缺席或版本不匹配时不接管其它界面的 NEI 库存统计。
            return false;
        }
    }

    private static boolean isSharedSlot(Slot slot) {
        return slot instanceof SlotSharedStorage || slot.inventory instanceof SharedStorageInventory;
    }
}
