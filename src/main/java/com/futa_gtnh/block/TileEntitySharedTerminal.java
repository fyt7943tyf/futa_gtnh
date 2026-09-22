package com.futa_gtnh.block;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 共享终端方块。
 *
 * <p>
 * 除了「右键开界面」，它还把自己伪装成一个<b>无限容量的容器</b>，
 * 让 GT 的管道、泵、流体覆盖板能直接和共享存储对接：
 *
 * <ul>
 * <li>{@link IFluidHandler}：管道<b>抽</b>走的是这个终端「当前选中的流体」
 * （在界面里对着流体条目按中键设置）；管道<b>灌</b>进来的流体直接进共享存储。</li>
 * <li>{@link ISidedInventory}：物品管道往里塞的东西会被立刻吸收进共享存储。
 * 只进不出 —— 从方块抽物品需要「抽哪种」的语义，那个交给界面里的搜索来做，
 * 硬塞给管道反而会让人误操作。</li>
 * </ul>
 *
 * <p>
 * 所有 IO 都是<b>直通共享存储</b>的，方块自己不缓存任何东西，
 * 所以放多少个终端都不会出现「东西在哪个终端里」的问题。
 */
public class TileEntitySharedTerminal extends TileEntity implements IFluidHandler, ISidedInventory {

    private static final int[] ACCESSIBLE_SLOTS = new int[] { 0 };

    /** 这个终端往外输出的流体。null 表示还没选。 */
    private FluidKey outputFluid;

    public FluidKey getOutputFluid() {
        return outputFluid;
    }

    public void setOutputFluid(FluidKey key) {
        this.outputFluid = key;
        markDirty();
    }

    // ==================================================================
    // IFluidHandler
    // ==================================================================

    /** 管道往里灌：全部收下，直接进共享存储。 */
    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        if (resource == null || resource.getFluid() == null || resource.amount <= 0) return 0;

        FluidKey key = FluidKey.of(resource);
        if (key == null) return 0;

        long space = SharedStorage.MAX_AMOUNT - SharedStorageManager.getStorage()
            .getFluidAmount(key);
        int acceptable = (int) Math.min(Math.min((long) resource.amount, Math.max(space, 0L)), Integer.MAX_VALUE);

        // 模拟模式也要如实回答「还能收多少」。条目已经顶到 long 上限时还回一个
        // 「随便灌」，会让「先模拟再实灌」的调用方按错误的数字去安排后续操作
        if (!doFill) return acceptable;
        if (acceptable <= 0) return 0;

        long stored = SharedStorageManager.getStorage()
            .insertFluid(key, acceptable);
        if (stored > 0L) {
            SharedStorageManager.broadcastFluidChange(key);
        }
        return (int) Math.min(stored, Integer.MAX_VALUE);
    }

    /**
     * 管道指定要抽哪种流体。
     *
     * <p>
     * <b>必须和 {@link #canDrain} 保持一致：只放行当前选中的那种。</b>
     * 否则任何认识具体流体名的调用方（灌装类机器、按种类抽的泵）都能绕开
     * 终端上的选择器，把共享存储里<b>任意</b>一种流体抽走 ——
     * 那这个「选输出流体」的设定就形同虚设了。
     */
    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        if (resource == null || resource.getFluid() == null) return null;
        if (outputFluid == null || outputFluid.getFluid() != resource.getFluid()) return null;
        return drainFluid(outputFluid, resource.amount, doDrain);
    }

    /** 管道没指定种类：抽这个终端选中的那种。 */
    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        return drainFluid(outputFluid, maxDrain, doDrain);
    }

    private FluidStack drainFluid(FluidKey key, int maxDrain, boolean doDrain) {
        if (key == null || maxDrain <= 0) return null;

        SharedStorage storage = SharedStorageManager.getStorage();
        long available = storage.getFluidAmount(key);
        if (available <= 0L) return null;

        int amount = (int) Math.min(Math.min(available, maxDrain), Integer.MAX_VALUE);
        if (amount <= 0) return null;

        // 模拟模式绝对不能碰存储 —— GT 的管道会先模拟一次再真抽
        if (!doDrain) return key.prototype(amount);

        long taken = storage.extractFluid(key, amount);
        if (taken <= 0L) return null;

        SharedStorageManager.broadcastFluidChange(key);
        return key.prototype(taken);
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        return fluid != null;
    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        if (fluid == null) return outputFluid != null;
        return outputFluid != null && outputFluid.getFluid() == fluid;
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        if (outputFluid == null) {
            return new FluidTankInfo[] { new FluidTankInfo(null) };
        }

        long amount = SharedStorageManager.getStorage()
            .getFluidAmount(outputFluid);
        if (amount <= 0L) {
            return new FluidTankInfo[] { new FluidTankInfo(null) };
        }

        // 容量报 Integer.MAX_VALUE：共享存储实际上限是 long，
        // 但 IFluidHandler 的接口只认 int，报个「管道这边永远灌不满」就够了
        return new FluidTankInfo[] {
            new FluidTankInfo(outputFluid.prototype(Math.min(amount, Integer.MAX_VALUE)), Integer.MAX_VALUE) };
    }

    // ==================================================================
    // ISidedInventory：只进不出的物品吸收口
    // ==================================================================

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        return ACCESSIBLE_SLOTS;
    }

    @Override
    public boolean canInsertItem(int slot, ItemStack stack, int side) {
        return stack != null;
    }

    @Override
    public boolean canExtractItem(int slot, ItemStack stack, int side) {
        return false;
    }

    @Override
    public int getSizeInventory() {
        return 1;
    }

    /** 永远报空：塞进来的东西已经被吸收进共享存储了。 */
    @Override
    public ItemStack getStackInSlot(int slot) {
        return null;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        return null;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        return null;
    }

    /**
     * 物品管道/漏斗往里放东西时走这里：立刻吸收进共享存储。
     *
     * <p>
     * 刻意<b>不修改</b>传进来的 {@code stack}：调用方（GT 物品管道、原版漏斗）
     * 都是自己先算好搬多少、再从源容器扣，如果这里也去改它的数量，
     * 反而会让对方重复扣减。这里只负责「收下」。
     */
    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return;

        ItemKey key = ItemKey.of(stack);
        if (key == null) return;

        long stored = SharedStorageManager.getStorage()
            .insertItem(key, stack.stackSize);
        if (stored > 0L) {
            SharedStorageManager.broadcastItemChange(key);
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
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        return stack != null;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        if (worldObj == null || worldObj.getTileEntity(xCoord, yCoord, zCoord) != this) return false;
        return player.getDistanceSq(xCoord + 0.5D, yCoord + 0.5D, zCoord + 0.5D) <= 64.0D;
    }

    @Override
    public void openInventory() {}

    @Override
    public void closeInventory() {}

    // ==================================================================
    // 持久化
    // ==================================================================

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        if (outputFluid != null) {
            tag.setTag("outputFluid", outputFluid.writeToNbt());
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        outputFluid = tag.hasKey("outputFluid") ? FluidKey.readFromNbt(tag.getCompoundTag("outputFluid")) : null;
    }
}
