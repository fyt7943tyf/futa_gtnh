package com.futa_gtnh.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/**
 * 共享存储网格里的一格。
 *
 * <p>
 * 它<b>不是</b>一个真的物品栏槽位：背后没有 {@code ItemStack} 数组，
 * 只有当前这一页的显示用物品。真正的增删一律通过
 * {@link ContainerSharedTerminal} 发请求给服务端。
 *
 * <p>
 * 这么做换来的是兼容性：对整个原版 GUI 体系来说它就是普通 {@code Slot}，
 * 所以 NEI 能读到格子里的东西（对着共享存储里的物品按 R/U 查配方直接可用）、
 * 原版的 tooltip/高亮/Shift 点击分发也都照常走。
 */
public class SlotSharedStorage extends Slot {

    private final GhostInventory ghost;
    private final ContainerSharedTerminal container;
    private final int viewIndex;

    public SlotSharedStorage(GhostInventory ghost, ContainerSharedTerminal container, int viewIndex, int x, int y) {
        super(ghost, viewIndex, x, y);
        this.ghost = ghost;
        this.container = container;
        this.viewIndex = viewIndex;
    }

    public int getViewIndex() {
        return viewIndex;
    }

    /** 当前这一页在这一格显示的物品（共享存储里条目的展示形态）。 */
    @Override
    public ItemStack getStack() {
        return ghost.getDisplay(viewIndex);
    }

    /**
     * <b>刻意返回 false。</b>
     *
     * <p>
     * 直觉上这里应该返回 true（共享存储确实什么都收），但那是错的：
     * {@link #putStack} 是<b>有意的空实现</b> —— 往虚拟槽位直接塞东西这条路上，
     * 「物品从哪来」是说不清的，任何实现都可能变成刷物品漏洞。一个嘴上说
     * 「我能收」、实际 {@code putStack} 什么都不做的槽位是在撒谎。
     *
     * <p>
     * 而 {@code isItemValid} 被原版和各种模组用来找「这个物品能放哪」：
     * <ul>
     * <li>NEI 的配方转移会遍历容器找目标槽位。它要是挑中了这些虚拟格，
     * 整个转移就会悄无声息地什么也不做 —— 而界面里明明有真正的合成栏，
     * 它本该把材料放进那里。</li>
     * <li>原版拖拽（{@code GuiContainer.mouseClickMove}）也用这个判断收集
     * 划过的槽位。不过我们本来就覆写了 {@code canDragIntoSlot} 把它们排除掉了，
     * 所以这里改成 false 不损失任何已有功能。</li>
     * </ul>
     *
     * <p>
     * 玩家点这些格子时走的是 {@code ContainerSharedTerminal.slotClick} 里
     * 那条独立的拦截分支，<b>根本不查 isItemValid</b>，所以点击取用完全不受影响；
     * {@link #canTakeStack} 依然是 true，Shift 点击照样能发请求。
     */
    @Override
    public boolean isItemValid(ItemStack stack) {
        return false;
    }

    /**
     * 永远可以尝试取出。返回 false 会让原版在 Shift 点击时直接跳过这一格，
     * 连请求都不会发出去。
     */
    @Override
    public boolean canTakeStack(EntityPlayer player) {
        return true;
    }

    @Override
    public int getSlotStackLimit() {
        return 64;
    }

    /** 虚拟槽位不参与原版同步，避免 {@code detectAndSendChanges} 把它当成真状态。 */
    @Override
    public void onSlotChanged() {}

    /**
     * 直接往这一格里塞东西（原版拖拽、别的模组直接操作槽位时会走到）。
     * 这里刻意什么都不做 —— 见 {@link ContainerSharedTerminal} 的说明：
     * 所有真实改动都必须经过服务端校验的那一条路径，任何旁路都是刷物品的口子。
     */
    @Override
    public void putStack(ItemStack stack) {}

    @Override
    public ItemStack decrStackSize(int amount) {
        container.requestWithdrawFromDisplay(viewIndex, amount);
        return null;
    }

    @Override
    public void onPickupFromSlot(EntityPlayer player, ItemStack stack) {}
}
