package com.futa_gtnh.block;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 共享终端方块。输入直通共享存储；每面的输出模式和白名单同时约束主动推送与管道抽取。
 * 物品通过按面分配的虚拟槽位提供所有匹配条目，流体通过 Forge 的方向参数检查来源面。
 * 方块不缓存真实物品或流体，所有扣取与存入都结算到共享仓库。
 */
public class TileEntitySharedTerminal extends TileEntity implements IFluidHandler, ISidedInventory {

    private static final int INPUT_SLOT = 0;
    private static final int[] INPUT_ONLY = { INPUT_SLOT };

    /** 输出槽位按「条目索引 × 六面 + 面编号」分配，生命周期内不复用，避免旧槽位指向另一条目。 */
    private final List<ItemKey> outputKeys = new ArrayList<>();
    private final Map<ItemKey, Integer> outputIndices = new HashMap<>();
    /** 上次提供给调用方的虚拟堆叠数量，用于支持漏斗回滚和直接写回剩余堆叠。 */
    private final Map<Integer, Integer> exposedAmounts = new HashMap<>();
    private SharedStorage indexedStorage;
    private int indexedRevision = -1;
    private int indexedOutputRevision = -1;

    /** 六个面的主动搬运方向与主动/被动共用的输出白名单。输入不使用筛选。 */
    private final TerminalIoConfig io = new TerminalIoConfig();
    /** 主动搬运的节流计时，单位 tick。 */
    private int ioTimer;

    public TerminalIoConfig getIo() {
        return io;
    }

    /**
     * 六个面里哪几个面挨着能搬东西的方块（位掩码），给配置界面画那圈半透明方块用。
     *
     * @param fluid true = 看流体（储罐/机器），false = 看物品（箱子/机器）
     */
    public int getTargetMask(boolean fluid) {
        if (worldObj == null) return 0;
        return TerminalIoEngine.targetMask(worldObj, xCoord, yCoord, zCoord, fluid);
    }

    /** 配置改了之后调一次：存档要重新落盘。 */
    public void onIoChanged() {
        markDirty();
    }

    /**
     * 主动搬运的心跳。
     *
     * <p>
     * 没有配任何面时第一件事就返回（一次六个 boolean 的判断），
     * 所以放着几百个没配置的终端也不会有什么开销。
     */
    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;
        if (!io.hasAnyMode()) return;

        int interval = Math.max(1, io.getIntervalTicks());
        if (++ioTimer < interval) return;
        ioTimer = 0;

        try {
            TerminalIoEngine.tick(worldObj, xCoord, yCoord, zCoord, io);
        } catch (Throwable t) {
            // 相邻方块是别的模组写的，出什么怪事都不该把整个服务端 tick 带崩
            FutaGtnhMod.LOG.warn("共享终端：主动搬运时出错（{} {} {}），这个面这一轮跳过", xCoord, yCoord, zCoord, t);
        }
    }

    private boolean allowsOutput(ForgeDirection face, ItemStack stack) {
        return io.getMode(face, false) == TerminalIoConfig.Mode.PUSH && io.matchesOutput(face, stack);
    }

    private boolean allowsOutput(ForgeDirection face, FluidStack stack) {
        return io.getMode(face, true) == TerminalIoConfig.Mode.PUSH && io.matchesOutput(face, stack);
    }

    // ==================================================================
    // IFluidHandler：输入不筛选；所有输出查询和实际抽取都按来源面校验。
    // ==================================================================

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        if (resource == null || resource.getFluid() == null || resource.amount <= 0) return 0;
        FluidKey key = FluidKey.of(resource);
        if (key == null) return 0;
        SharedStorage storage = SharedStorageManager.getStorage();
        long space = Math.max(0L, SharedStorage.MAX_AMOUNT - storage.getFluidAmount(key));
        int acceptable = (int) Math.min(resource.amount, space);
        if (!doFill || acceptable <= 0) return acceptable;
        long stored = storage.insertFluid(key, acceptable);
        if (stored > 0L) SharedStorageManager.broadcastFluidChange(key);
        return (int) stored;
    }

    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        if (resource == null || resource.amount <= 0 || !allowsOutput(from, resource)) return null;
        return drainFluid(FluidKey.of(resource), resource.amount, doDrain);
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        if (maxDrain <= 0 || io.getMode(from, true) != TerminalIoConfig.Mode.PUSH) return null;
        for (Map.Entry<FluidKey, Long> entry : SharedStorageManager.getStorage()
            .snapshotFluids()) {
            FluidKey key = entry.getKey();
            if (entry.getValue() > 0L && allowsOutput(from, key.prototype())) {
                return drainFluid(key, maxDrain, doDrain);
            }
        }
        return null;
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
        if (io.getMode(from, true) != TerminalIoConfig.Mode.PUSH) return false;
        for (Map.Entry<FluidKey, Long> entry : SharedStorageManager.getStorage()
            .snapshotFluids()) {
            FluidKey key = entry.getKey();
            if (entry.getValue() > 0L && (fluid == null || fluid == key.getFluid())
                && allowsOutput(from, key.prototype())) return true;
        }
        return false;
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        List<FluidTankInfo> tanks = new ArrayList<>();
        if (io.getMode(from, true) == TerminalIoConfig.Mode.PUSH) {
            for (Map.Entry<FluidKey, Long> entry : SharedStorageManager.getStorage()
                .snapshotFluids()) {
                FluidKey key = entry.getKey();
                if (entry.getValue() > 0L && allowsOutput(from, key.prototype())) {
                    tanks.add(new FluidTankInfo(key.prototype(entry.getValue()), Integer.MAX_VALUE));
                }
            }
        }
        // 即使没有允许输出的流体，仍提供空罐信息，让管道能够往里灌。
        if (tanks.isEmpty()) tanks.add(new FluidTankInfo(null, Integer.MAX_VALUE));
        return tanks.toArray(new FluidTankInfo[tanks.size()]);
    }

    // ==================================================================
    // ISidedInventory：一个共用输入槽；每个条目的六个虚拟输出槽分别代表六个面。
    // ==================================================================

    private void refreshOutputKeys() {
        SharedStorage storage = SharedStorageManager.getStorage();
        int outputRevision = io.getOutputRevision();
        if (indexedStorage == storage && indexedRevision == storage.getRevision()
            && indexedOutputRevision == outputRevision) return;
        boolean hasItemOutput = false;
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            if (io.getMode(face, false) == TerminalIoConfig.Mode.PUSH && !io.getOutputFilter(face)
                .isEmpty(false)) {
                hasItemOutput = true;
                break;
            }
        }
        if (hasItemOutput) {
            for (Map.Entry<ItemKey, Long> entry : storage.snapshotItems()) {
                ItemKey key = entry.getKey();
                if (outputIndices.containsKey(key)) continue;
                for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                    if (allowsOutput(face, key.prototype())) {
                        outputIndices.put(key, outputKeys.size());
                        outputKeys.add(key);
                        break;
                    }
                }
            }
        }
        indexedStorage = storage;
        indexedRevision = storage.getRevision();
        indexedOutputRevision = outputRevision;
    }

    private ItemKey keyForSlot(int slot) {
        int index = (slot - 1) / TerminalIoConfig.FACES;
        return slot > INPUT_SLOT && index < outputKeys.size() ? outputKeys.get(index) : null;
    }

    private ForgeDirection faceForSlot(int slot) {
        return slot <= INPUT_SLOT ? ForgeDirection.UNKNOWN
            : ForgeDirection.getOrientation((slot - 1) % TerminalIoConfig.FACES);
    }

    private int visibleAmount(ItemKey key) {
        return (int) Math.min(
            SharedStorageManager.getStorage()
                .getItemAmount(key),
            key.prototype()
                .getMaxStackSize());
    }

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        ForgeDirection face = ForgeDirection.getOrientation(side);
        if (io.getMode(face, false) != TerminalIoConfig.Mode.PUSH) return INPUT_ONLY.clone();
        refreshOutputKeys();
        List<Integer> slots = new ArrayList<>();
        slots.add(INPUT_SLOT);
        for (int i = 0; i < outputKeys.size(); i++) {
            ItemKey key = outputKeys.get(i);
            if (visibleAmount(key) > 0 && allowsOutput(face, key.prototype())) {
                slots.add(1 + i * TerminalIoConfig.FACES + face.ordinal());
            }
        }
        int[] result = new int[slots.size()];
        for (int i = 0; i < result.length; i++) result[i] = slots.get(i);
        return result;
    }

    @Override
    public boolean canInsertItem(int slot, ItemStack stack, int side) {
        return isItemValidForSlot(slot, stack);
    }

    @Override
    public boolean canExtractItem(int slot, ItemStack stack, int side) {
        ItemKey key = keyForSlot(slot);
        ForgeDirection face = ForgeDirection.getOrientation(side);
        return key != null && faceForSlot(slot) == face
            && key.equals(ItemKey.of(stack))
            && allowsOutput(face, key.prototype())
            && visibleAmount(key) > 0;
    }

    @Override
    public int getSizeInventory() {
        refreshOutputKeys();
        return 1 + outputKeys.size() * TerminalIoConfig.FACES;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        ItemKey key = keyForSlot(slot);
        if (key == null || !allowsOutput(faceForSlot(slot), key.prototype())) return null;
        int amount = visibleAmount(key);
        exposedAmounts.put(slot, amount);
        return amount > 0 ? key.prototype(amount) : null;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        ItemKey key = keyForSlot(slot);
        if (key == null || amount <= 0 || !allowsOutput(faceForSlot(slot), key.prototype())) return null;
        int visible = visibleAmount(key);
        int exposed = exposedAmounts.containsKey(slot) ? exposedAmounts.get(slot) : visible;
        long taken = SharedStorageManager.getStorage()
            .extractItem(key, Math.min(amount, visible));
        exposedAmounts.put(slot, Math.max(0, exposed - (int) taken));
        if (taken <= 0L) return null;
        SharedStorageManager.broadcastItemChange(key);
        return key.prototype(taken);
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        return decrStackSize(slot, getInventoryStackLimit());
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        SharedStorage storage = SharedStorageManager.getStorage();
        if (slot == INPUT_SLOT) {
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return;
            ItemKey key = ItemKey.of(stack);
            if (key != null && storage.insertItem(key, stack.stackSize) > 0L) {
                SharedStorageManager.broadcastItemChange(key);
            }
            return;
        }

        ItemKey key = keyForSlot(slot);
        if (key == null || (stack != null && !key.equals(ItemKey.of(stack)))) return;
        // 写回的是所显示的一叠，而不是仓库的全部数量。支持直接减栈及漏斗抽取失败后的恢复。
        int before = exposedAmounts.containsKey(slot) ? exposedAmounts.get(slot) : visibleAmount(key);
        int after = stack == null ? 0
            : Math.max(
                0,
                Math.min(
                    stack.stackSize,
                    key.prototype()
                        .getMaxStackSize()));
        int delta = after - before;
        if (delta < 0 && !allowsOutput(faceForSlot(slot), key.prototype())) return;
        long changed = delta < 0 ? storage.extractItem(key, -delta) : storage.insertItem(key, delta);
        exposedAmounts.put(slot, delta < 0 ? before - (int) changed : before + (int) changed);
        if (changed > 0L) SharedStorageManager.broadcastItemChange(key);
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
        return slot == INPUT_SLOT && stack != null && stack.getItem() != null && stack.stackSize > 0;
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
        if (io.hasAnyMode() || io.hasAnyFilter() || io.hasCustomRates()) {
            tag.setTag("io", io.writeToNbt());
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        io.readFromNbt(tag.hasKey("io") ? tag.getCompoundTag("io") : null);
    }
}
