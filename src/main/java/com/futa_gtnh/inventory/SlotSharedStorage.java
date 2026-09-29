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

    /** 上一次算过的那一份显示栈，以及它的「只画图标」副本（引用比较用来判失效）。 */
    private ItemStack cachedDisplay;
    private ItemStack cachedRenderView;

    /**
     * 当前这一页在这一格显示的物品（共享存储里条目的展示形态）。
     *
     * <p>
     * <b>交给原版渲染的这一份永远是 {@code stackSize == 1}。</b>原版
     * {@code RenderItem.renderItemOverlayIntoGUI} 会给数量大于 1 的堆叠在右下角画一行
     * 数字，而我们自己画的也是右下角那行「真实数量」—— 流体条目正好踩在这上面：
     * GT 的流体显示物品把数量也放进了 {@code stackSize}（托盘里那一叠就是「N 份」），
     * 于是原版那行数字和我们这行叠在同一格，看起来就是「两个数字重叠」。
     *
     * <p>
     * <b>数量一个都没丢</b>：图标还是那一个；真实数量由 {@code GuiSharedTerminal} 自己画
     * （它读的是 {@code StorageViewEntry}，不是这个槽位），悬停提示、点击取料的语义
     * 也全走 {@code StorageViewEntry} / {@code pageItemKeys} / {@code pageFluidKeys}。
     * 这里改的只是「原版会不会顺手再画一遍数字」。
     *
     * <p>
     * 副本按引用缓存：{@code getStack} 每帧每格都会被调到，而 {@code ItemStack.copy()}
     * 连 NBT 一起复制（流体显示物品都带 NBT），每帧复制 45 次没必要。
     */
    @Override
    public ItemStack getStack() {
        ItemStack display = ghost.getDisplay(viewIndex);
        if (display == cachedDisplay) return cachedRenderView;

        cachedDisplay = display;
        if (display == null || display.stackSize <= 1) {
            cachedRenderView = display;
        } else {
            // 改的是副本：ghost 里那一份还要给 tooltip 和取料判断用，不能就地改小
            ItemStack single = display.copy();
            single.stackSize = 1;
            cachedRenderView = single;
        }
        return cachedRenderView;
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
