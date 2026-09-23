package com.futa_gtnh.mixins;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.station.SharedStorageInventory;
import com.futa_gtnh.station.StationViews;

import tconstruct.tools.inventory.CraftingStationContainer;
import tconstruct.tools.logic.CraftingStationLogic;

/**
 * 让匠魂合成站容器里的「共享存储区」按守恒的方式工作。
 *
 * <p>
 * 槽位本身是匠魂原生的 {@code ChestSlot}，取物走的也是原版那条路
 * （{@code slot.decrStackSize} → 我们的 {@code IInventory#decrStackSize}），
 * 所以这里只需要补三处「原版会绕过 IInventory 直接改物品栈」的地方 ——
 * 这三处如果不拦，轻则点了没反应，重则<b>凭空刷出物品或把物品吃掉</b>：
 *
 * <ol>
 * <li>{@code mergeItemStack} / {@code mergeItemStackRefill}：把一叠东西「并进」目标格时，
 * 原版拿到的是 {@code slot.getStack()} 返回的对象，然后直接改它的 {@code stackSize}。
 * 虚拟格子每次返回的都是新对象，改它等于改了个寂寞 —— 来源那一叠已经清零，
 * 东西就凭空消失了。这里改成直接存进共享存储。</li>
 * <li>{@code transferStackInSlot}（Shift + 左键从存储区搬进背包）：它先
 * {@code getStack()} 拿到一叠、塞进背包，再 {@code putStack(null)} 收尾。
 * 虚拟格子的 {@code putStack} 是「存入」，靠它减数量同样会把东西刷出来。
 * 这里改成先把东西真的取出来，塞不下的原样还回存储。</li>
 * <li>{@code slotClick}：光标上拿着东西左键点存储区。原版只在「空格子」时才放入，
 * 而这一页通常 27 格全是满的，等于<b>没法往存储里放东西</b>。这里定义成
 * 「拿着东西点存储区 = 存进去」（左键整叠、右键 1 个），与终端界面的手势一致。</li>
 * </ol>
 *
 * <p>
 * 只处理 {@code slotId >= 46} 那一段（匠魂把侧边容器放在玩家背包之后），
 * 其它槽位一律原样放行。
 */
@Mixin(CraftingStationContainer.class)
public abstract class MixinCraftingStationContainer extends Container {

    // remap 的取舍：这个类里的成员分两种。
    // - mergeItemStack / transferStackInSlot / slotClick 是<b>覆写原版 Container 的方法</b>，
    // 它们在正式包里跟着原版一起被重命名，所以保持默认的 remap = true，
    // 让 Mixin 用混淆表把它们翻成 SRG 名；
    // - mergeItemStackRefill 是匠魂自己加的方法，原版没有，必须 remap = false，
    // 否则注解处理器找不到映射，直接编译失败。
    //
    // 另外这个类<b>故意继承 Container</b>：这样 this.getSlot(..) / this.mergeItemStack(..)
    // 就是普通的继承调用，方法体里的引用会跟整个模组一起被 reobf，不需要 @Shadow，
    // 也就不会踩到「@Shadow 一个继承自原版的成员，正式包里名字对不上」的坑
    // （注解处理器不会把这种继承成员的映射写进 refmap）。

    @Shadow(remap = false)
    public CraftingStationLogic logic;

    // ==================================================================
    // 1) 「并进某一格」→ 直接存进共享存储
    // ==================================================================

    @Inject(method = "mergeItemStack", at = @At("HEAD"), cancellable = true)
    private void futa$depositOnMerge(ItemStack stack, int startIndex, int endIndex, boolean useEndIndex,
        CallbackInfoReturnable<Boolean> cir) {
        if (futa$depositToChest(stack, startIndex, endIndex)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "mergeItemStackRefill", at = @At("HEAD"), cancellable = true, remap = false)
    private void futa$depositOnRefill(ItemStack stack, int startIndex, int endIndex, boolean useEndIndex,
        CallbackInfoReturnable<Boolean> cir) {
        if (futa$depositToChest(stack, startIndex, endIndex)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * @return true 表示「已经处理掉了」（调用方应立刻返回成功）
     */
    @Unique
    private boolean futa$depositToChest(ItemStack stack, int startIndex, int endIndex) {
        if (stack == null || stack.stackSize <= 0) return false;

        SharedStorageInventory chest = futa$chest();
        if (chest == null) return false;
        // 只认「整段就是存储区」的调用（匠魂自己那三处都是这个范围）
        if (startIndex != StationViews.CHEST_FIRST || endIndex != StationViews.CHEST_FIRST + chest.getSizeInventory()) {
            return false;
        }

        long accepted = chest.deposit(stack);
        stack.stackSize -= (int) accepted;
        return accepted > 0L;
    }

    // ==================================================================
    // 2) Shift + 左键从存储区取一整叠
    // ==================================================================

    @Inject(method = "transferStackInSlot", at = @At("HEAD"), cancellable = true)
    private void futa$quickMoveOutOfChest(EntityPlayer player, int index, CallbackInfoReturnable<ItemStack> cir) {
        SharedStorageInventory chest = futa$chest();
        if (chest == null) return;
        if (index < StationViews.CHEST_FIRST || index >= StationViews.CHEST_FIRST + chest.getSizeInventory()) {
            return;
        }

        Slot slot = this.getSlot(index);
        if (slot == null || !slot.getHasStack()) {
            cir.setReturnValue(null);
            return;
        }

        // 先真的取出来（守恒的那一半），再把能塞进背包的塞进去，塞不下的还回存储。
        // 顺序很关键：绝不能出现「背包收到了、存储没扣」的中间状态。
        ItemStack taken = chest.withdraw(index - StationViews.CHEST_FIRST, SharedStorageInventory.MAX_DISPLAY);
        if (taken == null) {
            cir.setReturnValue(null);
            return;
        }

        ItemStack before = taken.copy();
        // 这一句会一路走回本类的 mergeItemStack（玩家背包那一段不会被拦，正常合并）
        this.mergeItemStack(taken, StationViews.PLAYER_FIRST, StationViews.PLAYER_END, false);

        int moved = before.stackSize - taken.stackSize;
        if (taken.stackSize > 0) {
            // 背包放不下的部分原样还回去：宁可没搬成，也不能让东西离开存储又没地方去
            chest.deposit(taken);
        }

        if (moved <= 0) {
            cir.setReturnValue(null);
            return;
        }

        ItemStack result = before.copy();
        result.stackSize = moved;
        cir.setReturnValue(result);
    }

    // ==================================================================
    // 3) 光标拿着东西点存储区 = 存进去
    // ==================================================================

    @Inject(method = "slotClick", at = @At("HEAD"), cancellable = true)
    private void futa$depositFromCursor(int slotId, int clickedButton, int mode, EntityPlayer player,
        CallbackInfoReturnable<ItemStack> cir) {
        // 只看普通左/右键（mode 0）：Shift 转移、拖拽、丢弃各有自己的语义，不抢
        if (mode != 0 || player == null) return;

        SharedStorageInventory chest = futa$chest();
        if (chest == null) return;
        if (slotId < StationViews.CHEST_FIRST || slotId >= StationViews.CHEST_FIRST + chest.getSizeInventory()) {
            return;
        }

        ItemStack cursor = player.inventory.getItemStack();
        if (cursor == null || cursor.stackSize <= 0) return;

        // 左键 = 整叠存进去；右键 = 存 1 个（和终端界面、和我们自己的搜索面板一致）
        int want = clickedButton == 0 ? cursor.stackSize : 1;
        ItemStack depositing = cursor.copy();
        depositing.stackSize = Math.min(want, cursor.stackSize);

        long accepted = chest.deposit(depositing);
        if (accepted > 0L) {
            cursor.stackSize -= (int) accepted;
            if (cursor.stackSize <= 0) {
                player.inventory.setItemStack(null);
            }
            // 光标是「玩家自己身上的东西」，客户端不会自己算，必须由服务端同步一次
            if (player instanceof EntityPlayerMP) {
                ((EntityPlayerMP) player).updateHeldItem();
            }
        }
        cir.setReturnValue(null);
    }

    // ==================================================================

    @Unique
    private SharedStorageInventory futa$chest() {
        SharedStorageInventory chest = StationViews.existing(this.logic);
        // 客户端容器不执行这些操作（1.7.10 的客户端只发包，真正的 slotClick 在服务端跑）；
        // 万一哪个模组在客户端模拟，也别让它改到共享存储
        return chest == null || chest.isRemote() ? null : chest;
    }
}
