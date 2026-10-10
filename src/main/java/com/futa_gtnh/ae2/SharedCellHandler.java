package com.futa_gtnh.ae2;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.SharedStorageManager;

import appeng.api.AEApi;
import appeng.api.implementations.tiles.IChestOrDrive;
import appeng.api.storage.ICellHandler;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEStackType;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStackType;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 把共享背包元件物品接进 AE2 的 cell registry（{@code ICellHandler}）。
 * 物品 / 流体通道各一个实例 —— 分开而不是共用一个，是因为
 * {@link #getTopTexture_Light()} 等 ME 箱子顶面贴图方法拿不到元件物品参数，
 * 想让两种元件在箱子顶上显示各自的贴图，就得靠 handler 实例区分。
 */
final class SharedCellHandler implements ICellHandler {

    private final boolean fluid;

    private SharedCellHandler(boolean fluid) {
        this.fluid = fluid;
    }

    static SharedCellHandler forItems() {
        return new SharedCellHandler(false);
    }

    static SharedCellHandler forFluids() {
        return new SharedCellHandler(true);
    }

    private boolean isOurCell(ItemStack stack) {
        return stack != null && stack.getItem() instanceof ItemSharedStorageCell cell && cell.isFluidCell() == fluid;
    }

    @Override
    public boolean isCell(ItemStack is) {
        return isOurCell(is);
    }

    /**
     * 只认领自己通道的查询 —— 驱动器/箱子会对<b>所有</b>注册的 stack type
     * 逐个调用这里（含第三方通道），对不匹配的必须返回 null，
     * 否则会被错误地登记到别的通道上。
     *
     * <p>
     * host 为 null 的调用来自 {@code Platform.postChanges}（元件插拔差分），
     * 顺手给注册表盖章（供 tick 末淘汰），见 {@link AeCellBridge} 的时序注释。
     */
    @Override
    public IMEInventoryHandler getCellInventory(ItemStack is, ISaveProvider host, IAEStackType<?> type) {
        if (!isOurCell(is)) return null;
        boolean matches = fluid ? type == AEFluidStackType.FLUID_STACK_TYPE : type == AEItemStackType.ITEM_STACK_TYPE;
        if (!matches) return null;
        if (host == null) AeCellBridge.markNullHostQuery(is);
        return fluid ? new SharedFluidCellInventory(is, null) : new SharedItemCellInventory(is, host);
    }

    // ------------------------------------------------------------------
    // ME 箱子
    // ------------------------------------------------------------------

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getTopTexture_Light() {
        return topTexture();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getTopTexture_Medium() {
        return topTexture();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getTopTexture_Dark() {
        return topTexture();
    }

    @SideOnly(Side.CLIENT)
    private IIcon topTexture() {
        ItemSharedStorageCell item = fluid ? FutaGtnhMod.aeSharedCellFluid : FutaGtnhMod.aeSharedCellItem;
        return item == null ? null : item.getIconFromDamage(0);
    }

    /** 照抄 BasicCellHandler 的标准做法：在箱子侧打开通用 ME 终端界面。 */
    @Override
    public void openChestGui(EntityPlayer player, IChestOrDrive chest, ICellHandler cellHandler,
        IMEInventoryHandler inv, ItemStack is, StorageChannel chan) {
        appeng.util.Platform.openGUI(player, (TileEntity) chest, chest.getUp(), appeng.core.sync.GuiBridge.GUI_ME);
    }

    // ------------------------------------------------------------------
    // 驱动器 / 箱子的 LED 与功耗
    // ------------------------------------------------------------------

    /**
     * 0 无 / 1 绿 / 2 蓝 / 3 橙 / 4 红。共享背包没有「满」的概念：
     * 存储就绪且有内容 = 蓝（有余量），空 = 绿（全空），尚未随存档载入 = 红。
     */
    @Override
    public int getStatusForCell(ItemStack is, IMEInventory handler) {
        if (!SharedStorageManager.isLoaded()) return 4;
        boolean empty = fluid ? SharedStorageManager.getStorage()
            .fluidTypeCount() == 0
            : SharedStorageManager.getStorage()
                .itemTypeCount() == 0;
        return empty ? 1 : 2;
    }

    @Override
    public double cellIdleDrain(ItemStack is, IMEInventory handler) {
        // 和一枚普通存储元件同量级。见 README：共享背包本身不耗电，这是占一个驱动器槽位的代价。
        return 1.0;
    }

    /** 给注册日志用的通道名。 */
    @Override
    public String toString() {
        return "SharedCellHandler[" + (fluid ? "fluid" : "item") + "]";
    }

    /** 静默使用（AEApi 在 preInit 就可用，这里只是防御性确认注册表存在）。 */
    static void registerBoth() {
        AEApi.instance()
            .registries()
            .cell()
            .addCellHandler(forItems());
        AEApi.instance()
            .registries()
            .cell()
            .addCellHandler(forFluids());
    }
}
