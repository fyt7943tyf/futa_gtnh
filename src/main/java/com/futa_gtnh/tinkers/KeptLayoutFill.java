package com.futa_gtnh.tinkers;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

import tconstruct.smeltery.inventory.SmelteryContainer;
import tconstruct.smeltery.logic.SmelteryLogic;
import tconstruct.tools.inventory.CraftingStationContainer;

/**
 * 「保持你摆好的样子」型补料：合成站（3×3 合成）和冶炼炉用它。
 *
 * <p>
 * 这两个地方没有「配方」可推 —— 合成台摆的就是配方本身、冶炼炉里放什么就熔什么。
 * 所以这里换一种更保守的规则：<b>记住玩家摆出来的样子，被消耗掉多少就补回多少，
 * 绝不往空格里塞新东西</b>。
 * <ul>
 * <li><b>合成站</b>：合成一次后每个非空格子各少 1 个，于是自动补回原样 ——
 * 你可以一直点产物格，材料不用自己搬；</li>
 * <li><b>冶炼炉</b>：炉子里的东西是慢慢熔掉的，补回原数量就等于「你摆多少，它就一直熔多少」，
 * 直到你关掉界面。</li>
 * </ul>
 *
 * <p>
 * <b>为什么要记忆</b>：不记忆的话「空格该放什么」是没法推的（合成台的空格可能是
 * 这个配方本来就不用的位置）。记忆挂在<b>打开着的容器对象</b>上（{@code WeakHashMap}）：
 * 关掉界面 → 容器被回收 → 记忆自动清空，下次打开是干净的。
 *
 * <p>
 * 补料顺序和别处一致：<b>玩家背包优先，其次共享存储</b>。界面不开着的时候什么都不做 ——
 * 冶炼炉关了界面还在熔，但我们不补，免得变成一个无人看管的自动熔炼机。
 */
final class KeptLayoutFill {

    /** 每格记住的东西：键 + 数量（数量是「玩家摆出来过的最大数量」）。 */
    private static final class Memory {

        ItemKey key;
        int count;
    }

    /** 打开着的容器 → 它那一份摆法记忆。容器关掉就被回收，记忆跟着消失。 */
    private static final Map<Container, Memory[]> MEMORIES = new WeakHashMap<>();

    private KeptLayoutFill() {}

    /** 合成站：配方栏是 {@code container.craftMatrix}（InventoryCraftingStation，内部对应容器库存的 1..9）。 */
    static void tickGrid(EntityPlayerMP player, Container container, CraftingStationContainer station,
        SharedStorage storage, DeltaRecorder recorder) {
        IInventory grid = station.craftMatrix;
        if (grid == null) return;
        tick(player, container, grid, 0, Math.min(9, grid.getSizeInventory()), storage, recorder);
    }

    /** 冶炼炉：待熔物就在 SmelteryLogic 自己的库存里。 */
    static void tickSmeltery(EntityPlayerMP player, Container container, SmelteryContainer smeltery,
        SharedStorage storage, DeltaRecorder recorder) {
        SmelteryLogic logic = smeltery.logic;
        if (logic == null) return;
        tick(player, container, logic, 0, logic.getSizeInventory(), storage, recorder);
    }

    private static void tick(EntityPlayerMP player, Container container, IInventory inv, int first, int count,
        SharedStorage storage, DeltaRecorder recorder) {
        if (count <= 0) return;

        Memory[] memory = MEMORIES.get(container);
        if (memory == null || memory.length != count) {
            memory = new Memory[count];
            MEMORIES.put(container, memory);
        }

        for (int i = 0; i < count; i++) {
            int slot = first + i;
            ItemStack current = FillUtil.at(inv, slot);
            ItemKey currentKey = current == null ? null : ItemKey.of(current);

            if (currentKey == null) {
                // 空格：只有「这里原本摆过东西」时才补 —— 这就是合成/熔炼后的自动回填
                if (memory[i] != null && memory[i].key != null) {
                    refill(player, inv, slot, memory[i], storage, recorder);
                }
                continue;
            }

            if (memory[i] == null || !currentKey.equals(memory[i].key)) {
                // 玩家自己动了这一格：以现状为准重新记
                Memory fresh = new Memory();
                fresh.key = currentKey;
                fresh.count = current.stackSize;
                memory[i] = fresh;
            } else if (current.stackSize < memory[i].count) {
                // 被合成 / 被熔掉了一部分：补回原数量
                refill(player, inv, slot, memory[i], storage, recorder);
            } else if (current.stackSize > memory[i].count) {
                // 玩家自己加料了：抬高记忆值
                memory[i].count = current.stackSize;
            }
        }
    }

    private static void refill(EntityPlayerMP player, IInventory inv, int slot, Memory memory, SharedStorage storage,
        DeltaRecorder recorder) {
        ItemStack current = FillUtil.at(inv, slot);
        int have = 0;
        if (current != null && memory.key.equals(ItemKey.of(current))) {
            have = current.stackSize;
        }
        int need = memory.count - have;
        if (need <= 0) return;

        ItemStack got = FillUtil.take(player, storage, recorder, memory.key, need);
        if (got == null) return; // 存储里也没了：留着空位，等玩家自己处理

        got.stackSize += have;
        inv.setInventorySlotContents(slot, got);
        inv.markDirty();
    }
}
