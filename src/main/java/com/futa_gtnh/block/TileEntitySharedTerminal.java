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

import com.futa_gtnh.Config;
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

    /**
     * 存入槽里「仓库收不下」的那点东西。
     *
     * <p>
     * 正常情况下是 null：放进去的东西都会立刻被吸进共享仓库。但仓库对某个物品设了
     * 存量上限之后，超出的部分收不下 —— 那部分留在这里，玩家看得见、拿得回，
     * 漏斗也能取走。**绝不能丢**。
     */
    private ItemStack inputLeftover;
    private SharedStorage indexedStorage;
    private int indexedRevision = -1;
    private int indexedOutputRevision = -1;

    /** 六个面的主动搬运方向与主动/被动共用的输出白名单。输入不使用筛选。 */
    private final TerminalIoConfig io = new TerminalIoConfig();
    /** 主动搬运的节流计时，单位 tick。 */
    private int ioTimer;
    /** 连续空轮数：驱动空闲退避（搬不动东西时逐步拉长间隔，见 {@link TerminalIoEngine}）。 */
    private int idleRounds;
    /**
     * 各条目的堆叠上限缓存。
     *
     * <p>
     * 堆叠上限是键（物品 + 元数据 + NBT）的确定性函数，算过一次就不会变；
     * 以前每次可见数量查询都为它造一个原型栈（NBT 深拷贝），是被动抽取热路径上
     * 纯粹的浪费。条目数以仓库里出现过的种类为界，不会失控。
     */
    private final Map<ItemKey, Integer> maxStackSizes = new HashMap<>();

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
        ioTimer = 0;
        idleRounds = 0;
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
        boolean fullScan = ++ioTimer >= TerminalIoEngine.idleWaitTicks(interval, idleRounds);
        if (!fullScan && ioTimer % interval != 0) return;
        if (fullScan) ioTimer = 0;

        boolean moved = false;
        try {
            moved = fullScan ? TerminalIoEngine.tick(worldObj, xCoord, yCoord, zCoord, io)
                : TerminalIoEngine.tickManualItems(worldObj, xCoord, yCoord, zCoord, io);
        } catch (Throwable t) {
            // 相邻方块是别的模组写的，出什么怪事都不该把整个服务端 tick 带崩
            FutaGtnhMod.LOG.warn("共享终端：主动搬运时出错（{} {} {}），这个面这一轮跳过", xCoord, yCoord, zCoord, t);
        }
        if (moved) {
            ioTimer = 0;
            idleRounds = 0;
        } else if (fullScan) {
            idleRounds = !Config.ioIdleBackoff ? 0
                : Math.min(idleRounds + 1, TerminalIoEngine.IDLE_BACKOFF_STEPS.length);
        }
    }

    private boolean allowsOutput(ForgeDirection face, ItemStack stack) {
        return io.getMode(face, false) == TerminalIoConfig.Mode.PUSH && io.matchesOutput(face, stack);
    }

    /** 规范化键直查版 {@link #allowsOutput(ForgeDirection, ItemStack)}：热路径不构造原型栈。 */
    private boolean allowsOutput(ForgeDirection face, ItemKey key) {
        return io.getMode(face, false) == TerminalIoConfig.Mode.PUSH && io.matchesOutput(face, key);
    }

    private boolean allowsOutput(ForgeDirection face, FluidStack stack) {
        return io.getMode(face, true) == TerminalIoConfig.Mode.PUSH && io.matchesOutput(face, stack);
    }

    private boolean allowsOutput(ForgeDirection face, FluidKey key) {
        return io.getMode(face, true) == TerminalIoConfig.Mode.PUSH && io.matchesOutput(face, key);
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
        // 问存储「还能收多少」，而不是拿全局上限自己算：这个流体可能被设过限额，
        // 拿全局上限算出来的「可接收量」会偏大，管道据此规划就会推超。
        // （真存的时候 insertFluid 还会再夹一次，所以不会丢东西，但预检查不该骗人）
        long space = storage.fluidSpaceLeft(key);
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
            .snapshotFluidsCached()) {
            FluidKey key = entry.getKey();
            if (entry.getValue() > 0L && allowsOutput(from, key)) {
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
            .snapshotFluidsCached()) {
            FluidKey key = entry.getKey();
            if (entry.getValue() > 0L && (fluid == null || fluid == key.getFluid()) && allowsOutput(from, key))
                return true;
        }
        return false;
    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        List<FluidTankInfo> tanks = new ArrayList<>();
        if (io.getMode(from, true) == TerminalIoConfig.Mode.PUSH) {
            for (Map.Entry<FluidKey, Long> entry : SharedStorageManager.getStorage()
                .snapshotFluidsCached()) {
                FluidKey key = entry.getKey();
                if (entry.getValue() > 0L && allowsOutput(from, key)) {
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
        int itemRevision = storage.getItemRevision();
        int outputRevision = io.getOutputRevision();
        if (indexedStorage == storage && indexedRevision == itemRevision && indexedOutputRevision == outputRevision)
            return;
        boolean hasItemOutput = false;
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            if (io.getMode(face, false) == TerminalIoConfig.Mode.PUSH && !io.getOutputFilter(face)
                .isEmpty(false)) {
                hasItemOutput = true;
                break;
            }
        }
        if (hasItemOutput) {
            for (Map.Entry<ItemKey, Long> entry : storage.snapshotItemsCached()) {
                ItemKey key = entry.getKey();
                if (entry.getValue() == null || entry.getValue()
                    .longValue() <= 0L || outputIndices.containsKey(key)) continue;
                for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                    if (allowsOutput(face, key)) {
                        outputIndices.put(key, outputKeys.size());
                        outputKeys.add(key);
                        break;
                    }
                }
            }
        }
        indexedStorage = storage;
        indexedRevision = itemRevision;
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
            maxStackSizeOf(key));
    }

    /** 这个条目的堆叠上限（缓存，见 {@link #maxStackSizes} 的说明）。 */
    private int maxStackSizeOf(ItemKey key) {
        Integer cached = maxStackSizes.get(key);
        if (cached != null) return cached.intValue();
        int limit = Math.max(
            1,
            key.prototype()
                .getMaxStackSize());
        maxStackSizes.put(key, Integer.valueOf(limit));
        return limit;
    }

    @Override
    public int[] getAccessibleSlotsFromSide(int side) {
        ForgeDirection face = ForgeDirection.getOrientation(side);
        if (io.getMode(face, false) != TerminalIoConfig.Mode.PUSH) return INPUT_ONLY.clone();
        refreshOutputKeys();
        TerminalOutputFilter filter = io.getOutputFilter(face);
        int visibleCount = 0;
        int manualCount = 0;
        for (int i = 0; i < outputKeys.size(); i++) {
            ItemKey key = outputKeys.get(i);
            if (visibleAmount(key) > 0 && allowsOutput(face, key)) {
                visibleCount++;
                if (filter.containsItem(key)) manualCount++;
            }
        }
        int[] result = new int[1 + visibleCount];
        result[0] = INPUT_SLOT;
        int manualIndex = 1;
        int oreIndex = 1 + manualCount;
        for (int i = 0; i < outputKeys.size(); i++) {
            ItemKey key = outputKeys.get(i);
            if (visibleAmount(key) > 0 && allowsOutput(face, key)) {
                // 只改变枚举顺序，槽号仍绑定原条目，避免管道的旧槽引用指向其他物品。
                result[filter.containsItem(key) ? manualIndex++ : oreIndex++] = 1 + i * TerminalIoConfig.FACES
                    + face.ordinal();
            }
        }
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
            && allowsOutput(face, key)
            && visibleAmount(key) > 0;
    }

    @Override
    public int getSizeInventory() {
        refreshOutputKeys();
        return 1 + outputKeys.size() * TerminalIoConfig.FACES;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        // 存入槽：它是个「漏斗口」，正常时永远是空的（东西一进来就被吸进仓库）。
        // 但仓库满了（物品设了上限）时，收不下的那部分必须留在这里让玩家拿回去 ——
        // 见 setInventorySlotContents 里的说明。
        if (slot == INPUT_SLOT) {
            return inputLeftover == null ? null : inputLeftover.copy();
        }
        ItemKey key = keyForSlot(slot);
        if (key == null || !allowsOutput(faceForSlot(slot), key)) return null;
        int amount = visibleAmount(key);
        exposedAmounts.put(slot, amount);
        return amount > 0 ? key.prototype(amount) : null;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        // 从存入槽里把没吸收掉的那点拿回去
        if (slot == INPUT_SLOT) {
            if (inputLeftover == null || amount <= 0) return null;
            int taken = Math.min(amount, inputLeftover.stackSize);
            ItemStack out = inputLeftover.copy();
            out.stackSize = taken;
            inputLeftover.stackSize -= taken;
            if (inputLeftover.stackSize <= 0) inputLeftover = null;
            return out;
        }
        ItemKey key = keyForSlot(slot);
        if (key == null || amount <= 0 || !allowsOutput(faceForSlot(slot), key)) return null;
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
            // ★ 收不下的东西留在这个槽里，绝不吞掉。
            //
            // 这个槽是个「漏斗口」：放进去就被吸进共享仓库，正常时槽里是空的。
            // 但某个物品被设了存量上限之后，insertItem 只会接收一部分 ——
            // 原来这里无论收下多少都直接 return（槽又读不出内容），
            // 于是「上限挡下来的那部分」会凭空消失。玩家看到的就是「塞进去，东西少了」。
            //
            // 现在改成：先尽量吸收，剩下的原样留在槽里，玩家能看见也能拿回去，
            // 漏斗/管道也能把它取走（getStackInSlot / decrStackSize 都认这个槽了）。
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
                if (inputLeftover != null) {
                    // 把一个空栈写进来 = 清空这个槽（GUI 里手动拿走时会发生）
                    inputLeftover = null;
                }
                return;
            }
            ItemKey key = ItemKey.of(stack);
            if (key == null) {
                inputLeftover = stack.copy();
                return;
            }
            long accepted = storage.insertItem(key, stack.stackSize);
            if (accepted > 0L) SharedStorageManager.broadcastItemChange(key);
            int left = stack.stackSize - (int) Math.min(accepted, stack.stackSize);
            if (left > 0) {
                ItemStack rest = stack.copy();
                rest.stackSize = left;
                inputLeftover = rest;
            } else {
                inputLeftover = null;
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
        if (delta < 0 && !allowsOutput(faceForSlot(slot), key)) return;
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
