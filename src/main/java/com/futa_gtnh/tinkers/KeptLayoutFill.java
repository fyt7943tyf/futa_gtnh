package com.futa_gtnh.tinkers;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
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
 * <b>光看数量分不清「谁拿走的」</b>：合成吃掉 1 个和玩家拿走 1 个，在库存层面
 * 完全一样（都是 {@code getStackInSlot} 变小）。只按数量补，玩家<b>从九宫格里把材料
 * 拿回来</b>的下一 tick 就会被补回去 —— 看起来就是「东西拿不出来」。
 * 所以这里多记一份「玩家亲手动过哪些格子」（{@link #notePlayerClick}，
 * 由 {@code mixins/MixinContainer} 在 {@code Container.slotClick} 入口转发过来）：
 * 动过的格子以<b>现状</b>为准（拿走就是拿走，空着就忘掉），当次不补。
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

    /** 一个被跟踪的容器：它内部那份库存 + 摆法记忆。 */
    private static final class Tracked {

        final IInventory inventory;
        final int first;
        final int count;
        final Memory[] memories;

        /**
         * 本次 tick 之前有没有「拿走合成产物」这个动作。
         *
         * <p>
         * 这是本类<b>最可靠的那个信号</b>：只有拿产物才意味着合成栏被真的消耗了一次，
         * 也才该回填。它来自匠魂自己的 {@code SlotCraftingStation.onPickupFromSlot}
         * （见 {@code mixins/MixinSlotCraftingStation}），和玩家点什么键、怎么点都无关。
         */
        boolean crafted;

        Tracked(IInventory inventory, int first, int count) {
            this.inventory = inventory;
            this.first = first;
            this.count = count;
            this.memories = new Memory[count];
        }
    }

    /** 打开着的容器 → 它那一份摆法记忆。容器关掉就被回收，记忆跟着消失。 */
    private static final Map<Container, Tracked> TRACKED = new WeakHashMap<>();

    /**
     * 玩家<b>亲手</b>动过的格子：{@code 库存 → 下标集合}。
     *
     * <p>
     * 只在「点击 → 本 tick 的补料」这一小段时间里有意义：{@link #tick} 读到就把它消费掉
     * （去掉）。这样既不会越积越多，也不会因为一次点击而在之后一直不补料。
     */
    private static final Map<IInventory, Set<Integer>> TOUCHED = new WeakHashMap<>();

    private KeptLayoutFill() {}

    // ==================================================================
    // 玩家点击的入口（由 mixin 转发）
    // ==================================================================

    /**
     * 玩家点了容器里的一格 —— 记下「这一格是他自己动的」。
     *
     * <p>
     * 只有被跟踪的容器（合成站 / 冶炼炉）才会被记，而且记的是<b>槽位背后的库存与下标</b>
     * 而不是槽位号，所以两种工作站的槽位排布不一样也照样对得上。
     */
    static void notePlayerClick(Container container, int slotId, int mode) {
        if (TRACKED.isEmpty() || container == null) return;
        Tracked tracked = TRACKED.get(container);
        if (tracked == null) return;

        if (mode == 6) {
            // 双击收集：原版会翻遍整个容器把同种物品收进光标，整份库存都算「玩家动过」
            noteTouchedAll(container);
            return;
        }

        if (slotId < 0 || slotId >= container.inventorySlots.size()) return;
        Slot slot = (Slot) container.inventorySlots.get(slotId);
        if (slot == null || slot.inventory != tracked.inventory) return;
        mark(tracked.inventory, slot.getSlotIndex());
    }

    /**
     * 玩家<b>拿走了合成产物</b> —— 也就是说合成栏刚被真的消耗了一次，这一次才允许回填。
     *
     * <p>
     * 由 {@code mixins/MixinSlotCraftingStation} 挂在匠魂自己的成品槽上。
     * 有了这个信号，「回填」这件事就有了明确的前提，不再依赖「减少的数量看起来像不像合成」。
     */
    static void noteCraft(Container container) {
        if (TRACKED.isEmpty() || container == null) return;
        Tracked tracked = TRACKED.get(container);
        if (tracked != null) tracked.crafted = true;
    }

    /**
     * 玩家<b>一次性清空</b>了整份库存（「倒空合成栏」按钮那种不经过点击的直接写入）。
     *
     * <p>
     * 不标记的话，东西刚被倒进共享存储就会被补料原样搬回九宫格 —— 按钮看起来没反应。
     */
    static void noteTouchedAll(Container container) {
        if (TRACKED.isEmpty() || container == null) return;
        Tracked tracked = TRACKED.get(container);
        if (tracked == null) return;
        for (int i = 0; i < tracked.count; i++) {
            mark(tracked.inventory, tracked.first + i);
        }
    }

    private static void mark(IInventory inventory, int index) {
        Set<Integer> touched = TOUCHED.get(inventory);
        if (touched == null) {
            touched = new HashSet<>();
            TOUCHED.put(inventory, touched);
        }
        touched.add(index);
    }

    /** @return 这一格本 tick 是不是被玩家亲手动过（读过就清掉） */
    private static boolean consumeTouched(IInventory inventory, int index) {
        Set<Integer> touched = TOUCHED.get(inventory);
        return touched != null && touched.remove(index);
    }

    // ==================================================================
    // 补料
    // ==================================================================

    /** 合成站：配方栏是 {@code container.craftMatrix}（InventoryCraftingStation，内部对应容器库存的 1..9）。 */
    static void tickGrid(EntityPlayerMP player, Container container, CraftingStationContainer station,
        SharedStorage storage, DeltaRecorder recorder) {
        IInventory grid = station.craftMatrix;
        if (grid == null) return;
        // requireCraft = true：合成站只在「刚拿走过产物」时才回填。
        // 合成栏变少的原因太多了（玩家拿走、倒空、别的模组动过），
        // 只有「拿产物」这一个动作能证明是合成吃掉的。
        tick(player, container, grid, 0, Math.min(9, grid.getSizeInventory()), true, storage, recorder);
    }

    /** 冶炼炉：待熔物就在 SmelteryLogic 自己的库存里。 */
    static void tickSmeltery(EntityPlayerMP player, Container container, SmelteryContainer smeltery,
        SharedStorage storage, DeltaRecorder recorder) {
        SmelteryLogic logic = smeltery.logic;
        if (logic == null) return;
        // requireCraft = false：冶炼炉没有「拿产物」这个动作，熔掉的过程本身就是消耗，
        // 所以这里仍然按「少了就补」的老规则走（玩家亲手动过的格子照样跳过）
        tick(player, container, logic, 0, logic.getSizeInventory(), false, storage, recorder);
    }

    private static void tick(EntityPlayerMP player, Container container, IInventory inv, int first, int count,
        boolean requireCraft, SharedStorage storage, DeltaRecorder recorder) {
        if (count <= 0) return;

        Tracked tracked = TRACKED.get(container);
        if (tracked == null || tracked.inventory != inv || tracked.count != count) {
            boolean crafted = tracked != null && tracked.crafted;
            tracked = new Tracked(inv, first, count);
            tracked.crafted = crafted;
            TRACKED.put(container, tracked);
        }
        Memory[] memory = tracked.memories;

        // 本 tick 允许回填吗：冶炼炉永远允许；合成站要求「刚拿走过产物」。
        // 标记读完就清掉，所以一次合成只对应一次回填
        boolean mayRefill = !requireCraft || tracked.crafted;
        tracked.crafted = false;

        for (int i = 0; i < count; i++) {
            int slot = first + i;
            ItemStack current = FillUtil.at(inv, slot);
            ItemKey currentKey = current == null ? null : ItemKey.of(current);

            if (consumeTouched(inv, slot)) {
                // 玩家自己刚动过这一格：以现状为准（拿走就是拿走，空着就忘掉），本次不补。
                // 少了这一段，「从九宫格里把材料拿回来」会被下一 tick 立刻补回去
                memory[i] = snapshot(currentKey, current);
                continue;
            }

            if (currentKey == null) {
                // 空格：只有「这里原本摆过东西」时才补 —— 这就是合成后的回填
                if (memory[i] != null && memory[i].key != null) {
                    if (mayRefill) {
                        refill(player, inv, slot, memory[i], storage, recorder);
                    } else {
                        // 没人拿走产物，那这一格就是被玩家（或别的模组）搬走的：认账，别补
                        memory[i] = null;
                        noteSkippedRefill(player, false);
                    }
                }
                continue;
            }

            if (memory[i] == null || !currentKey.equals(memory[i].key)) {
                // 玩家自己动了这一格：以现状为准重新记
                memory[i] = snapshot(currentKey, current);
            } else if (current.stackSize < memory[i].count) {
                if (mayRefill) {
                    // 刚拿走过产物：这是被合成消耗掉的部分，补回原数量
                    refill(player, inv, slot, memory[i], storage, recorder);
                } else {
                    // 没拿过产物却变少了：是玩家自己拿走的，把记忆降到现状（下次合成只补到这么多）
                    memory[i].count = current.stackSize;
                    noteSkippedRefill(player, true);
                }
            } else if (current.stackSize > memory[i].count) {
                // 玩家自己加料了：抬高记忆值
                memory[i].count = current.stackSize;
            }
        }
    }

    /**
     * 跳过回填时各报一次日志（每个进程一次），并给玩家一行聊天提示。
     *
     * <p>
     * 这不是功能提示，而是「到底有没有生效」的唯一肉眼可见的凭据：
     * 「九宫格里的东西拿不出来」这个 bug 的前提是补料在跑、而抑制没跑，
     * 所以下次再出现时，先看有没有这一行 —— 没有就说明跑的还是没有这段逻辑的旧版本。
     */
    private static void noteSkippedRefill(EntityPlayerMP player, boolean partially) {
        if (skippedRefillLogged) return;
        skippedRefillLogged = true;
        FutaGtnhMod.LOG.info("匠魂自动补料：识别出合成栏是玩家自己动的（{}），已跳过回填 —— 这条只报一次", partially ? "数量变少但没拿过产物" : "整格被清空但没拿过产物");
        if (player != null) {
            player.addChatMessage(
                new net.minecraft.util.ChatComponentText("\u00a77[共享存储] 自动补料：这一格是你自己动的，已跳过回填（本提示每次启动只出现一次）"));
        }
    }

    private static boolean skippedRefillLogged;

    private static Memory snapshot(ItemKey key, ItemStack stack) {
        if (key == null || stack == null) return null;
        Memory fresh = new Memory();
        fresh.key = key;
        fresh.count = stack.stackSize;
        return fresh;
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
