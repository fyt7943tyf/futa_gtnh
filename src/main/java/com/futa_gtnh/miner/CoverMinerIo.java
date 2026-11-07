package com.futa_gtnh.miner;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;

import com.futa_gtnh.Config;
import com.futa_gtnh.aewireless.AeSignalRegistry;
import com.futa_gtnh.aewireless.AeSignalTeam;
import com.futa_gtnh.aewireless.TileAeSignalTransmitter;

import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.Platform;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEItemStackType;
import gregtech.api.covers.CoverContext;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTUtility;
import gregtech.common.tileentities.machines.multi.MTEOilDrillBase;
import gregtech.common.tileentities.machines.multi.MTEOreDrillingPlantBase;

/**
 * 「AE 采矿枢纽」覆盖板：贴在采矿场/油气钻机的<b>主控制器</b>上（正面除外），
 * 通过本模组的 AE 无线发信器定位 ME 网络（跨维度可，无需任何线缆），全自动
 * 接管整台机器的物料进出：
 *
 * <ul>
 * <li><b>补给</b>：采矿场 → 钻井液补进输入仓（缓冲量见 {@link Config#minerIoFluidBuffer}）、
 * 采矿管道补进输入总线（{@link Config#minerIoPipeBuffer}）；油气钻机 → 只补采矿管道；</li>
 * <li><b>回收</b>：输出总线里的矿物、输出仓里的油气，全部泵回 ME 网络。</li>
 * </ul>
 *
 * <p>
 * 每次搬运从发信器所在网络的能源缓存里扣 AE 电（{@code Platform.poweredInsert/
 * poweredExtraction}，与 AE2 自己的导入/导出总线同一套功耗模型）；发信器离线/
 * 冲突/不同队伍时安静待机。绑定关系存在覆盖板 NBT 里，跨维度按坐标解析。
 * 未绑定且机器所有者同队伍的发信器<b>恰好只有一台</b>时自动绑定——多数情况零配置。
 */
public class CoverMinerIo extends gregtech.common.covers.Cover {

    /** 链路状态（GUI 显示 + 逻辑分派）。 */
    public enum LinkState {

        OK,
        /** 还没选发信器。 */
        NOT_SET,
        /** 目标位置没有发信器（区块未加载或被拆了）。 */
        NOT_FOUND,
        /** 机器所有者和发信器所有者不同队伍。 */
        NO_TEAM,
        /** 发信器不在线（没电 / 没通道 / 出错）。 */
        OFFLINE,
        /** 目标网络里有多个发信器，目标不是生效的那个。 */
        CONFLICT;

        public static final LinkState[] VALUES = values();
    }

    /** 单次搬运上限（mB），防止一 tick 抽干网络或撑爆输入仓。 */
    private static final int MAX_FLUID_PER_PASS = 4000;

    /** 单次搬运上限（件）。 */
    private static final int MAX_ITEMS_PER_PASS = 128;

    /** 只能贴在受支持的钻机控制器上，且整机限一个。 */
    public static boolean isCoverPlaceable(ForgeDirection side, ItemStack coverItem, ICoverable coverable) {
        MetaTileEntity mte = metaTileEntityOf(coverable);
        if (!(mte instanceof MTEOreDrillingPlantBase) && !(mte instanceof MTEOilDrillBase)) return false;
        for (final ForgeDirection tSide : ForgeDirection.VALID_DIRECTIONS) {
            if (coverable.getCoverAtSide(tSide) instanceof CoverMinerIo) return false;
        }
        return true;
    }

    private static MetaTileEntity metaTileEntityOf(ICoverable coverable) {
        return coverable instanceof IGregTechTileEntity gte && gte.getMetaTileEntity() instanceof MetaTileEntity mte
            ? mte
            : null;
    }

    // ------------------------------------------------------------------
    // 配置（NBT 持久化）
    // ------------------------------------------------------------------

    private int targetDim = 0, targetX = 0, targetY = 0, targetZ = 0;
    private boolean hasTarget = false;

    // 运行期状态（服务端更新，GUI 通过 sync value 展示）
    private LinkState linkState = LinkState.NOT_SET;
    private long lastMoved = -1;

    public CoverMinerIo(CoverContext context, ITexture coverTexture) {
        super(context, coverTexture);
    }

    public boolean hasTarget() {
        return hasTarget;
    }

    public int getTargetDim() {
        return targetDim;
    }

    public int getTargetX() {
        return targetX;
    }

    public int getTargetY() {
        return targetY;
    }

    public int getTargetZ() {
        return targetZ;
    }

    public void setTarget(int dim, int x, int y, int z) {
        this.targetDim = dim;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.hasTarget = true;
        markDirty();
    }

    public void clearTarget() {
        this.hasTarget = false;
        markDirty();
    }

    public LinkState getLinkState() {
        return linkState;
    }

    /** 上一次实际搬运的量（物品 = 件，流体 = mB）；-1 = 这轮没搬。 */
    public long getLastMoved() {
        return lastMoved;
    }

    private void markDirty() {
        ICoverable coverable = getTile();
        if (coverable != null) coverable.markDirty();
    }

    // ------------------------------------------------------------------
    // 每刻逻辑
    // ------------------------------------------------------------------

    @Override
    public void doCoverThings(byte aInputRedstone, long aTimer) {
        ICoverable coverable = getTile();
        MetaTileEntity mte = metaTileEntityOf(coverable);
        if (!(mte instanceof MTEOreDrillingPlantBase) && !(mte instanceof MTEOilDrillBase)) {
            linkState = LinkState.NOT_SET;
            return;
        }
        UUID machineOwner = coverable instanceof IGregTechTileEntity gte ? gte.getOwnerUuid() : null;

        // 未绑定且同队伍发信器唯一 → 自动绑定（零配置路径）
        if (!hasTarget && machineOwner != null) {
            List<AeSignalRegistry.Entry> visible = AeSignalRegistry.listVisible(machineOwner);
            if (visible.size() == 1) {
                AeSignalRegistry.Entry entry = visible.get(0);
                setTarget(entry.dim, entry.x, entry.y, entry.z);
            }
        }

        TileAeSignalTransmitter transmitter = hasTarget
            ? TileAeSignalTransmitter.resolve(targetDim, targetX, targetY, targetZ)
            : null;

        if (!hasTarget) {
            linkState = LinkState.NOT_SET;
            lastMoved = -1;
            return;
        }
        if (transmitter == null) {
            linkState = LinkState.NOT_FOUND;
            lastMoved = -1;
            return;
        }
        if (!AeSignalTeam.sameTeam(machineOwner, transmitter.getOwner())) {
            linkState = LinkState.NO_TEAM;
            lastMoved = -1;
            return;
        }
        switch (transmitter.getState()) {
            case OFFLINE -> {
                linkState = LinkState.OFFLINE;
                lastMoved = -1;
                return;
            }
            case CONFLICT -> {
                linkState = LinkState.CONFLICT;
                lastMoved = -1;
                return;
            }
            default -> {}
        }

        linkState = LinkState.OK;
        long moved;
        if (mte instanceof MTEOreDrillingPlantBase ore) {
            moved = runOreDrillIo(ore, transmitter);
        } else {
            moved = runOilDrillIo((MTEOilDrillBase) mte, transmitter);
        }
        if (moved > 0) lastMoved = moved;
    }

    /** @return 本次搬运总量（mB + 件数按各自口径相加，>0 即有过实际搬运）。 */
    private long runOreDrillIo(MTEOreDrillingPlantBase ore, TileAeSignalTransmitter transmitter) {
        long moved = 0;
        moved += supplyDrillingFluid(ore, transmitter);
        moved += supplyPipes(ore.mInputBusses, transmitter);
        moved += drainOutputBuses(ore.mOutputBusses, transmitter);
        return moved;
    }

    private long runOilDrillIo(MTEOilDrillBase oil, TileAeSignalTransmitter transmitter) {
        long moved = 0;
        moved += supplyPipes(oil.mInputBusses, transmitter);
        moved += drainOutputHatches(oil.mOutputHatches, transmitter);
        return moved;
    }

    // ------------------------------------------------------------------
    // 补给：钻井液
    // ------------------------------------------------------------------

    private long supplyDrillingFluid(MTEOreDrillingPlantBase ore, TileAeSignalTransmitter transmitter) {
        // GT 把钻井液注册成 ItemList 上的静态 Fluid 字段（配方逻辑同款引用）
        Fluid drilling = ItemList.sDrillingFluid;
        if (drilling == null) return 0;

        List<MTEHatchInput> hatches = alive(ore.mInputHatches);
        if (hatches.isEmpty()) return 0;

        int have = 0;
        for (MTEHatchInput hatch : hatches) {
            FluidStack tank = hatch.mFluid;
            if (tank != null && tank.getFluid() == drilling) have += tank.amount;
        }
        int deficit = Config.minerIoFluidBuffer - have;
        if (deficit <= 0) return 0;
        int want = Math.min(deficit, MAX_FLUID_PER_PASS);

        IAEFluidStack extracted = extractFluid(transmitter, new FluidStack(drilling, want));
        if (extracted == null) return 0;

        int leftover = (int) extracted.getStackSize();
        for (MTEHatchInput hatch : hatches) {
            if (leftover <= 0) break;
            FluidStack tank = hatch.mFluid;
            if (tank != null && tank.getFluid() != drilling) continue; // 仓里已是别的流体，不动
            leftover -= hatch.fill(new FluidStack(drilling, leftover), true);
        }
        if (leftover > 0) injectFluid(transmitter, new FluidStack(drilling, leftover)); // 填不完的退回网络
        return extracted.getStackSize() - leftover;
    }

    // ------------------------------------------------------------------
    // 补给：采矿管道
    // ------------------------------------------------------------------

    private long supplyPipes(List<MTEHatchInputBus> buses, TileAeSignalTransmitter transmitter) {
        if (buses.isEmpty()) return 0;
        ItemStack pipe = GTModHandler.getIC2Item("miningPipe", 1);
        if (pipe == null) return 0;

        int have = 0;
        for (MTEHatchInputBus bus : alive(buses)) {
            IGregTechTileEntity base = bus.getBaseMetaTileEntity();
            for (int i = 0; i < base.getSizeInventory(); i++) {
                ItemStack slot = base.getStackInSlot(i);
                if (slot != null && slot.isItemEqual(pipe)) have += slot.stackSize;
            }
        }
        int deficit = Config.minerIoPipeBuffer - have;
        if (deficit <= 0) return 0;
        int want = Math.min(deficit, MAX_ITEMS_PER_PASS);

        IAEItemStack extracted = extractItems(transmitter, GTUtility.copyAmount(want, pipe));
        if (extracted == null) return 0;

        int leftover = (int) extracted.getStackSize();
        for (MTEHatchInputBus bus : alive(buses)) {
            if (leftover <= 0) break;
            IGregTechTileEntity base = bus.getBaseMetaTileEntity();
            for (int i = 0; i < base.getSizeInventory() && leftover > 0; i++) {
                ItemStack slot = base.getStackInSlot(i);
                if (slot == null) {
                    int put = Math.min(leftover, pipe.getMaxStackSize());
                    base.setInventorySlotContents(i, GTUtility.copyAmount(put, pipe));
                    leftover -= put;
                } else if (slot.isItemEqual(pipe) && slot.stackSize < slot.getMaxStackSize()) {
                    int put = Math.min(leftover, slot.getMaxStackSize() - slot.stackSize);
                    slot.stackSize += put;
                    leftover -= put;
                }
            }
        }
        if (leftover > 0) injectItems(transmitter, GTUtility.copyAmount(leftover, pipe)); // 装不完的退回网络
        return extracted.getStackSize() - leftover;
    }

    // ------------------------------------------------------------------
    // 回收：输出总线（矿物）
    // ------------------------------------------------------------------

    private long drainOutputBuses(List<MTEHatchOutputBus> buses, TileAeSignalTransmitter transmitter) {
        long moved = 0;
        for (MTEHatchOutputBus bus : alive(buses)) {
            IGregTechTileEntity base = bus.getBaseMetaTileEntity();
            for (int i = 0; i < base.getSizeInventory(); i++) {
                ItemStack slot = base.getStackInSlot(i);
                if (slot == null) continue;
                int want = Math.min(slot.stackSize, MAX_ITEMS_PER_PASS);
                IAEItemStack rejected = injectItems(transmitter, GTUtility.copyAmount(want, slot));
                int accepted = want - (rejected == null ? 0 : (int) rejected.getStackSize());
                if (accepted <= 0) continue;
                base.decrStackSize(i, accepted);
                moved += accepted;
            }
        }
        return moved;
    }

    // ------------------------------------------------------------------
    // 回收：输出仓（油气）
    // ------------------------------------------------------------------

    private long drainOutputHatches(List<MTEHatchOutput> hatches, TileAeSignalTransmitter transmitter) {
        long moved = 0;
        for (MTEHatchOutput hatch : alive(hatches)) {
            FluidStack tank = hatch.mFluid;
            if (tank == null || tank.amount <= 0) continue;
            int want = Math.min(tank.amount, MAX_FLUID_PER_PASS);
            IAEFluidStack rejected = injectFluid(transmitter, new FluidStack(tank.getFluid(), want));
            int accepted = want - (rejected == null ? 0 : (int) rejected.getStackSize());
            if (accepted <= 0) continue;
            hatch.drain(accepted, true);
            moved += accepted;
        }
        return moved;
    }

    /** 活着的仓体子集（{@code GTUtility.validMTEList} 的返回类型不是 List，这里自写同语义过滤）。 */
    private static <T extends MetaTileEntity> List<T> alive(List<T> list) {
        List<T> out = new ArrayList<>(list.size());
        for (T mte : list) {
            if (mte == null) continue;
            IGregTechTileEntity base = mte.getBaseMetaTileEntity();
            if (!(base instanceof TileEntity tile) || tile.isInvalid()) continue;
            out.add(mte);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // AE 搬运（从发信器所在网络扣 AE 电）
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static IMEMonitor<IAEItemStack> itemMonitor(AENetworkProxy proxy) throws GridAccessException {
        IStorageGrid storage = proxy.getStorage();
        IMEMonitor<?> monitor = storage.getMEMonitor(AEItemStackType.ITEM_STACK_TYPE);
        return monitor instanceof IMEMonitor ? (IMEMonitor<IAEItemStack>) monitor : null;
    }

    @SuppressWarnings("unchecked")
    private static IMEMonitor<IAEFluidStack> fluidMonitor(AENetworkProxy proxy) throws GridAccessException {
        IStorageGrid storage = proxy.getStorage();
        IMEMonitor<?> monitor = storage.getMEMonitor(AEFluidStackType.FLUID_STACK_TYPE);
        return monitor instanceof IMEMonitor ? (IMEMonitor<IAEFluidStack>) monitor : null;
    }

    private IAEItemStack extractItems(TileAeSignalTransmitter transmitter, ItemStack stack) {
        if (stack == null) return null;
        try {
            AENetworkProxy proxy = transmitter.getProxy();
            IMEMonitor<IAEItemStack> monitor = itemMonitor(proxy);
            if (monitor == null) return null;
            IAEItemStack request = AEItemStack.create(stack);
            if (request == null) return null;
            return Platform.poweredExtraction(proxy.getEnergy(), monitor, request, new MachineSource(transmitter));
        } catch (GridAccessException | IllegalStateException ignored) {
            return null;
        }
    }

    private IAEItemStack injectItems(TileAeSignalTransmitter transmitter, ItemStack stack) {
        if (stack == null) return null;
        try {
            AENetworkProxy proxy = transmitter.getProxy();
            IMEMonitor<IAEItemStack> monitor = itemMonitor(proxy);
            if (monitor == null) return null;
            IAEItemStack input = AEItemStack.create(stack);
            if (input == null) return null;
            return Platform.poweredInsert(proxy.getEnergy(), monitor, input, new MachineSource(transmitter));
        } catch (GridAccessException | IllegalStateException ignored) {
            return null;
        }
    }

    private IAEFluidStack extractFluid(TileAeSignalTransmitter transmitter, FluidStack fluid) {
        if (fluid == null) return null;
        try {
            AENetworkProxy proxy = transmitter.getProxy();
            IMEMonitor<IAEFluidStack> monitor = fluidMonitor(proxy);
            if (monitor == null) return null;
            IAEFluidStack request = AEFluidStack.create(fluid);
            if (request == null) return null;
            return Platform.poweredExtraction(proxy.getEnergy(), monitor, request, new MachineSource(transmitter));
        } catch (GridAccessException | IllegalStateException ignored) {
            return null;
        }
    }

    private IAEFluidStack injectFluid(TileAeSignalTransmitter transmitter, FluidStack fluid) {
        if (fluid == null) return null;
        try {
            AENetworkProxy proxy = transmitter.getProxy();
            IMEMonitor<IAEFluidStack> monitor = fluidMonitor(proxy);
            if (monitor == null) return null;
            IAEFluidStack input = AEFluidStack.create(fluid);
            if (input == null) return null;
            return Platform.poweredInsert(proxy.getEnergy(), monitor, input, new MachineSource(transmitter));
        } catch (GridAccessException | IllegalStateException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 覆盖板通用行为
    // ------------------------------------------------------------------

    @Override
    public int getMinimumTickRate() {
        return Config.minerIoCoverInterval;
    }

    @Override
    public boolean allowsTickRateAddition() {
        // 搬运频率由 config 定，别让杰克锤那套 tick 速率调节叠加进来
        return false;
    }

    // ------------------------------------------------------------------
    // NBT
    // ------------------------------------------------------------------

    @Override
    protected @NotNull NBTBase saveDataToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("hasTarget", hasTarget);
        if (hasTarget) {
            tag.setInteger("dim", targetDim);
            tag.setInteger("x", targetX);
            tag.setInteger("y", targetY);
            tag.setInteger("z", targetZ);
        }
        return tag;
    }

    @Override
    protected void readDataFromNbt(NBTBase nbt) {
        if (!(nbt instanceof NBTTagCompound tag)) return;
        hasTarget = tag.getBoolean("hasTarget");
        if (hasTarget) {
            targetDim = tag.getInteger("dim");
            targetX = tag.getInteger("x");
            targetY = tag.getInteger("y");
            targetZ = tag.getInteger("z");
        }
    }

    // ------------------------------------------------------------------
    // 别挡机器的路
    // ------------------------------------------------------------------

    @Override
    public boolean letsEnergyIn() {
        return true;
    }

    @Override
    public boolean letsEnergyOut() {
        return true;
    }

    @Override
    public boolean letsFluidIn(Fluid fluid) {
        return true;
    }

    @Override
    public boolean letsFluidOut(Fluid fluid) {
        return true;
    }

    @Override
    public boolean letsItemsIn(int slot) {
        return true;
    }

    @Override
    public boolean letsItemsOut(int slot) {
        return true;
    }

    @Override
    public boolean isRedstoneSensitive(long aTimer) {
        return false;
    }

    @Override
    public @NotNull List<String> getAdditionalTooltip() {
        return com.google.common.collect.ImmutableList
            .of(linkState == LinkState.OK && lastMoved >= 0 ? linkState + " " + lastMoved : linkState.toString());
    }

    @Override
    public String getDescription() {
        return hasTarget ? linkState.toString() : LinkState.NOT_SET.toString();
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------

    @Override
    protected @NotNull gregtech.common.gui.modularui.cover.base.CoverBaseGui<?> getCoverGui() {
        return new CoverMinerIoGui(this);
    }

    @Override
    public boolean hasCoverGUI() {
        return true;
    }
}
