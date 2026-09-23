package com.futa_gtnh.mixins;

import java.lang.ref.WeakReference;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.station.SharedStorageInventory;
import com.futa_gtnh.station.StationViews;

import tconstruct.tools.inventory.CraftingStationContainer;
import tconstruct.tools.logic.CraftingStationLogic;

/**
 * 给匠魂合成站挂上「共享存储箱子」。
 *
 * <p>
 * 匠魂的合成站本来就支持「旁边放个箱子」：{@code getGuiContainer} 会扫一圈六个方向，
 * 把找到的第一个容器记进 {@code chest} 字段，然后容器按 {@code slotCount} 加真槽位、
 * 界面按 {@code ChestLayout} 排版。我们要做的就一件事 ——
 * <b>扫完之后如果没找到任何箱子，就把 {@code chest} 指向共享存储</b>，
 * 剩下的全交给匠魂自己的代码。这也是「原生 IO」的字面实现：对匠魂来说，
 * 它旁边确实多了一个箱子，只不过这个箱子没有实体。
 *
 * <p>
 * 三个刻意的选择：
 * <ol>
 * <li><b>只在「旁边真的没有箱子」时挂</b>。玩家物理上摆了一个箱子/抽屉，
 * 那是他的明确意图，不能被我们顶掉 —— 所以注入点在方法末尾，看的是扫描结果。</li>
 * <li><b>挂完要重建容器</b>。原版那句 {@code new CraftingStationContainer(...)}
 * 是在 {@code chest == null} 的状态下跑的（没有侧边槽位），字段改了之后必须按新状态
 * 重建一个。容器构造函数只往里加槽位、算一次合成结果，没有别的副作用，重建是安全的。</li>
 * <li><b>顺手把 {@code inventories} 数组也补上</b>。那份引用列表是容器用来判断
 * 「玩家还能不能用这个界面」的（{@code canInteractWith}），少一个引用不影响正确性，
 * 但补上之后「共享存储不可用 → 界面自动关闭」这条原版语义才完整。</li>
 * </ol>
 *
 * <p>
 * 失败时（匠魂改了字段名、构造函数抛异常……）会把字段改回去再放行，
 * 让玩家看到的是<b>原版合成站</b>，而不是一个「界面以为有箱子、容器里却没有槽位」
 * 的错位状态 —— 那种状态下点一下就会和服务端对不上号。
 */
@Mixin(CraftingStationLogic.class)
public abstract class MixinCraftingStationLogic {

    // 注意下面每个 @Shadow / @Inject 都写了 remap = false：
    // 匠魂的类名和方法名<b>不参与原版混淆</b>（它不是 Minecraft 的一部分），
    // 而 Mixin 的注解处理器默认会把 "chest" / "getGuiContainer" 这类名字当成
    // 原版成员去查混淆表 —— getGuiContainer 在原版里根本不存在，编译期就会报
    // 「Unable to locate obfuscation mapping」。
    // 只针对匠魂自己的成员关掉重映射；方法体里对原版成员的引用不在此列。

    @Shadow(remap = false)
    public WeakReference chest;

    @Shadow(remap = false)
    public int chestSize;

    @Shadow(remap = false)
    public int invRows;

    @Shadow(remap = false)
    public int invColumns;

    @Shadow(remap = false)
    public int slotCount;

    @Shadow(remap = false)
    public ForgeDirection chestDirection;

    @Shadow(remap = false)
    public WeakReference patternChest;

    @Shadow(remap = false)
    public WeakReference furnace;

    @Shadow(remap = false)
    private WeakReference[] inventories;

    @Inject(method = "getGuiContainer", at = @At("RETURN"), cancellable = true, remap = false)
    private void futa$mountSharedStorage(InventoryPlayer inventoryplayer, World world, int x, int y, int z,
        CallbackInfoReturnable<Container> cir) {
        // 旁边真有容器：物理优先，什么都不做
        if (this.chest != null || world == null || inventoryplayer == null) return;

        CraftingStationLogic self = (CraftingStationLogic) (Object) this;
        SharedStorageInventory shared = null;

        try {
            // 客户端也要挂：客户端那份是「只读镜像」，槽位数量必须和服务端一致，
            // 否则窗口里的槽位号会对不上（界面上点第 50 格，服务端却是另一回事）
            shared = StationViews.get(self, world.isRemote);
            if (shared == null) return;

            this.chest = new WeakReference<IInventory>(shared);
            // 虚拟容器没有「哪一面」，UNKNOWN 会让匠魂把 accessSide 当成未知，
            // 对非 ISidedInventory 的容器没有任何影响
            this.chestDirection = ForgeDirection.UNKNOWN;
            this.chestSize = shared.getSizeInventory();
            this.slotCount = shared.getSizeInventory();
            this.invColumns = SharedStorageInventory.COLUMNS;
            this.invRows = SharedStorageInventory.ROWS;
            this.inventories = new WeakReference[] { this.chest, null, this.patternChest, this.furnace };

            cir.setReturnValue(new CraftingStationContainer(inventoryplayer, self, x, y, z));
            StationViews.announceMountOnce(world.isRemote);
        } catch (Throwable t) {
            // 回滚到「没有箱子」的状态：返回的那个容器是按 chest == null 建的
            this.chest = null;
            this.chestSize = 0;
            this.slotCount = 0;
            this.inventories = new WeakReference[] { null, null, this.patternChest, this.furnace };
            StationViews.reportMountFailure(t);
        }
    }
}
