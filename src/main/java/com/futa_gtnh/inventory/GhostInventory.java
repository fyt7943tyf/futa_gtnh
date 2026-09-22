package com.futa_gtnh.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * 共享存储网格在客户端那一侧的「假背包」。
 *
 * <p>
 * 为什么需要它：原版 {@link net.minecraft.inventory.Slot} 必须挂在一个
 * {@link IInventory} 上，而 {@code GuiContainer} 画格子、NEI 读格子内容、
 * 原版拖拽逻辑，走的都是 {@code slot.getStack()}。共享存储可能有上万个条目、
 * 还是全服动态变化的，不可能给每个条目都建一个真的 {@code Slot}。
 * 所以这里只放<b>当前这一页</b>的显示用物品，让原版那一整套机制照常工作。
 *
 * <p>
 * 服务端也有一份（构造 {@code Container} 时两边跑的是同一份代码），
 * 但它永远是空的 —— 服务端的权威数据在 {@code SharedStorage} 里，
 * 不在这里。这一点顺带保证了一件重要的事：服务端 {@code detectAndSendChanges}
 * 看到的这些槽位恒为 null，所以永远不会把「显示用物品」当成真物品同步给客户端。
 *
 * <p>
 * <b>注意</b>：容器重新同步时（开界面、服务端整包刷新）原版会对每个槽位调用
 * {@code putStack(null)}。这里的写入必须对 null 免疫，否则会凭空往共享存储里
 * 塞东西 —— 那就是刷物品漏洞。
 */
public class GhostInventory implements IInventory {

    private final ItemStack[] display;
    private final ContainerSharedTerminal container;

    public GhostInventory(int size, ContainerSharedTerminal container) {
        this.display = new ItemStack[size];
        this.container = container;
    }

    /** 客户端专用：把当前页的显示物品铺进来。 */
    public void setDisplay(int index, ItemStack stack) {
        if (index < 0 || index >= display.length) return;
        display[index] = stack;
    }

    public void clearDisplay() {
        java.util.Arrays.fill(display, null);
    }

    public int getDisplaySize() {
        return display.length;
    }

    /** @return 这个显示物品对应的共享存储条目（可能为 null） */
    public ItemStack getDisplay(int index) {
        return index < 0 || index >= display.length ? null : display[index];
    }

    // ------------------------------------------------------------------
    // IInventory
    // ------------------------------------------------------------------

    @Override
    public int getSizeInventory() {
        return display.length;
    }

    @Override
    public ItemStack getStackInSlot(int index) {
        return getDisplay(index);
    }

    /**
     * 只有当别的模组绕过 {@code slotClick} 直接操作槽位时才会走到这里。
     * 转成一次「取出」请求；返回 null 是刻意的 —— 这个槽位并没有真的减少，
     * 真实数量要等服务端回包才知道。
     */
    @Override
    public ItemStack decrStackSize(int index, int amount) {
        container.requestWithdrawFromDisplay(index, amount);
        return null;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int index) {
        return null;
    }

    /**
     * 同上，转成「存入」请求。
     *
     * <p>
     * {@code stack == null} 必须直接忽略：容器同步时原版会依次对每个槽位调用
     * {@code putStack(null)}，若把它当成一次存入，就会变成刷物品漏洞。
     */
    @Override
    public void setInventorySlotContents(int index, ItemStack stack) {
        if (stack != null) {
            container.requestDepositFromExternal(stack);
        }
    }

    @Override
    public String getInventoryName() {
        return "container.futa_gtnh.shared_terminal";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return false;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public void markDirty() {
        // 显示用背包没有持久化状态，无需处理
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return true;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    @Override
    public boolean isItemValidForSlot(int index, ItemStack stack) {
        return stack != null;
    }
}
