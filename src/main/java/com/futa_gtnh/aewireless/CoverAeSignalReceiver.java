package com.futa_gtnh.aewireless;

import java.util.List;
import java.util.UUID;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.utils.item.ItemStackHandler;

import gregtech.api.covers.CoverContext;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IMachineProgress;

/**
 * 「AE 无线接收端」覆盖板：贴在单方块机器 / 多方块主控制器上（正面除外），
 * 远程查询某个 ME 网络里一种物品或流体的存量，按阈值决定机器<b>启用还是停用</b>。
 *
 * <p>
 * 相当于把 AE2 的发信器（Level Emitter）从「贴在网络里输出红石」改成
 * 「贴在机器上直接控制启停」，并且无线 —— 发信端和机器之间不需要任何线缆。
 *
 * <p>
 * 工作方式：每 {@link #intervalTicks} 刻解析一次发信端（按坐标，跨维度可），
 * 校验同队伍（GTNHLib TeamManager，见 {@link AeSignalTeam}）和在线状态后查询存量，
 * 满足/不满足条件分别对应「启用/停用」或反过来（可配置）。链路断开时按
 * 「保持 / 启用 / 停用」三选一处理，默认保持 —— 网络重启不会把产线意外点起来。
 *
 * <p>
 * 机器启停走 {@link IMachineProgress#disableWorking()} /
 * {@link IMachineProgress#enableWorking()}，和 GT 自带的机器控制器覆盖板同一条路。
 */
public class CoverAeSignalReceiver extends gregtech.common.covers.Cover {

    /** 触发条件。 */
    public enum ConditionMode {

        /** 存量 ≥ 阈值时触发。 */
        ABOVE,

        /** 存量 ≤ 阈值时触发。 */
        BELOW;

        public static final ConditionMode[] VALUES = values();
    }

    /** 条件满足时对机器做什么。 */
    public enum OnCondition {

        /** 满足条件 → 启用机器（不满足 → 停用）。 */
        ENABLE,

        /** 满足条件 → 停用机器（不满足 → 启用）。 */
        DISABLE;

        public static final OnCondition[] VALUES = values();
    }

    /** 链路断开（离线/找不到/不同队）时的行为。 */
    public enum LinkLostBehavior {

        /** 什么都不做，保持上一次的启停状态。 */
        KEEP,

        /** 启用机器。 */
        ENABLE,

        /** 停用机器。 */
        DISABLE;

        public static final LinkLostBehavior[] VALUES = values();
    }

    /** 链路状态（GUI 显示 + 逻辑分派）。 */
    public enum LinkState {

        OK,
        /** 还没选发信端。 */
        NOT_SET,
        /** 目标位置没有发信端（区块未加载或被拆了）。 */
        NOT_FOUND,
        /** 机器所有者和发信端所有者不同队。 */
        NO_TEAM,
        /** 发信端不在线（没电 / 没通道 / 出错）。 */
        OFFLINE,
        /** 目标网络里有多个发信端，目标不是生效的那个。 */
        CONFLICT,
        /** 还没配置要监听的物品/流体。 */
        NO_MONITOR;

        public static final LinkState[] VALUES = values();
    }

    /** 只能贴在能被控制启停的机器上，且整机限一个。 */
    public static boolean isCoverPlaceable(ForgeDirection side, ItemStack coverItem, ICoverable coverable) {
        if (!(coverable instanceof IMachineProgress)) return false;
        for (final ForgeDirection tSide : ForgeDirection.VALID_DIRECTIONS) {
            if (coverable.getCoverAtSide(tSide) instanceof CoverAeSignalReceiver) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 配置（NBT 持久化）
    // ------------------------------------------------------------------

    private int targetDim = 0, targetX = 0, targetY = 0, targetZ = 0;
    private boolean hasTarget = false;
    private boolean fluidMode = false;
    private String fluidName = "";
    private final ItemStackHandler filter = new ItemStackHandler(1);
    private int threshold = 64;
    private ConditionMode condition = ConditionMode.ABOVE;
    private OnCondition action = OnCondition.DISABLE;
    private int intervalTicks = 20;
    private LinkLostBehavior linkLost = LinkLostBehavior.KEEP;

    // 运行期状态（服务端更新，GUI 通过 sync value 展示）
    private long lastAmount = -1;
    private LinkState linkState = LinkState.NOT_SET;

    public CoverAeSignalReceiver(CoverContext context, ITexture coverTexture) {
        super(context, coverTexture);
    }

    // ------------------------------------------------------------------
    // 配置读写
    // ------------------------------------------------------------------

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

    public boolean isFluidMode() {
        return fluidMode;
    }

    public void setFluidMode(boolean fluidMode) {
        this.fluidMode = fluidMode;
        markDirty();
    }

    /** 监听的物品（物品模式的幽灵槽背后就是它）。 */
    public ItemStackHandler getFilter() {
        return filter;
    }

    public String getFluidName() {
        return fluidName;
    }

    public void setFluidName(String fluidName) {
        this.fluidName = fluidName == null ? "" : fluidName;
        markDirty();
    }

    public int getThreshold() {
        return threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = Math.max(0, threshold);
        markDirty();
    }

    public ConditionMode getCondition() {
        return condition;
    }

    public void setCondition(ConditionMode condition) {
        this.condition = condition;
        markDirty();
    }

    public OnCondition getAction() {
        return action;
    }

    public void setAction(OnCondition action) {
        this.action = action;
        markDirty();
    }

    public int getIntervalTicks() {
        return intervalTicks;
    }

    public void setIntervalTicks(int intervalTicks) {
        this.intervalTicks = Math.min(600, Math.max(5, intervalTicks));
        markDirty();
    }

    public LinkLostBehavior getLinkLost() {
        return linkLost;
    }

    public void setLinkLost(LinkLostBehavior linkLost) {
        this.linkLost = linkLost;
        markDirty();
    }

    public long getLastAmount() {
        return lastAmount;
    }

    public LinkState getLinkState() {
        return linkState;
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
        if (!(coverable instanceof IMachineProgress machine)) return;

        UUID machineOwner = coverable instanceof IGregTechTileEntity gte ? gte.getOwnerUuid() : null;

        TileAeSignalTransmitter transmitter = hasTarget
            ? TileAeSignalTransmitter.resolve(targetDim, targetX, targetY, targetZ)
            : null;

        long amount = -1;
        if (!hasTarget) {
            linkState = LinkState.NOT_SET;
        } else if (transmitter == null) {
            linkState = LinkState.NOT_FOUND;
        } else if (!AeSignalTeam.sameTeam(machineOwner, transmitter.getOwner())) {
            linkState = LinkState.NO_TEAM;
        } else {
            switch (transmitter.getState()) {
                case OFFLINE -> linkState = LinkState.OFFLINE;
                case CONFLICT -> linkState = LinkState.CONFLICT;
                case ONLINE -> {
                    amount = query(transmitter);
                    if (amount < 0) linkState = LinkState.OFFLINE;
                    else {
                        linkState = LinkState.OK;
                        lastAmount = amount;
                    }
                }
            }
        }

        boolean shouldRun;
        if (linkState == LinkState.OK) {
            boolean met = condition == ConditionMode.ABOVE ? amount >= threshold : amount <= threshold;
            shouldRun = met == (action == OnCondition.ENABLE);
        } else {
            lastAmount = -1;
            shouldRun = switch (linkLost) {
                case KEEP -> machine.isAllowedToWork();
                case ENABLE -> true;
                case DISABLE -> false;
            };
        }

        if (shouldRun) {
            if (!machine.isAllowedToWork()) machine.enableWorking();
        } else {
            if (machine.isAllowedToWork()) machine.disableWorking();
        }
    }

    /** 查询监听目标的存量；没配置或查询失败返回 -1。 */
    private long query(TileAeSignalTransmitter transmitter) {
        if (fluidMode) {
            Fluid fluid = FluidRegistry.getFluid(fluidName);
            if (fluid == null) return linkStateNoMonitor();
            return transmitter.queryFluidAmount(new FluidStack(fluid, 1));
        }
        ItemStack stack = filter.getStackInSlot(0);
        if (stack == null) return linkStateNoMonitor();
        return transmitter.queryItemAmount(stack);
    }

    private long linkStateNoMonitor() {
        linkState = LinkState.NO_MONITOR;
        return -1;
    }

    @Override
    public int getMinimumTickRate() {
        return intervalTicks;
    }

    @Override
    public boolean allowsTickRateAddition() {
        // 监听频率由 GUI 配置，杰克锤（jackhammer）那套「调 tick 速率」别再叠加进来
        return false;
    }

    @Override
    public void onCoverRemoval() {
        if (getTile() instanceof IMachineProgress machine) machine.enableWorking();
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
        tag.setBoolean("fluidMode", fluidMode);
        tag.setString("fluidName", fluidName);
        ItemStack monitored = filter.getStackInSlot(0);
        if (monitored != null) tag.setTag("monitor", monitored.writeToNBT(new NBTTagCompound()));
        tag.setInteger("threshold", threshold);
        tag.setInteger("condition", condition.ordinal());
        tag.setInteger("action", action.ordinal());
        tag.setInteger("interval", intervalTicks);
        tag.setInteger("linkLost", linkLost.ordinal());
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
        fluidMode = tag.getBoolean("fluidMode");
        fluidName = tag.getString("fluidName");
        filter.setStackInSlot(0, ItemStack.loadItemStackFromNBT(tag.getCompoundTag("monitor")));
        threshold = Math.max(0, tag.getInteger("threshold"));
        condition = ConditionMode.VALUES[Math.floorMod(tag.getInteger("condition"), ConditionMode.VALUES.length)];
        action = OnCondition.VALUES[Math.floorMod(tag.getInteger("action"), OnCondition.VALUES.length)];
        intervalTicks = Math.min(600, Math.max(5, tag.hasKey("interval") ? tag.getInteger("interval") : 20));
        linkLost = LinkLostBehavior.VALUES[Math.floorMod(tag.getInteger("linkLost"), LinkLostBehavior.VALUES.length)];
    }

    // ------------------------------------------------------------------
    // 覆盖板通用行为：别挡机器的路
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
            .of(linkState == LinkState.OK && lastAmount >= 0 ? linkState + " " + lastAmount : linkState.toString());
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
        return new CoverAeSignalReceiverGui(this);
    }

    @Override
    public boolean hasCoverGUI() {
        return true;
    }
}
