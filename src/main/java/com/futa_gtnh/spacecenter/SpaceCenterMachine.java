package com.futa_gtnh.spacecenter;

import net.minecraft.nbt.NBTTagCompound;

/**
 * 深空采矿指挥中心里一台「虚拟机器」的运行时状态机。
 *
 * <p>
 * 数值口径全部取自 GT5u 5.09.54.175 原版机器（默认能量档、无超频）：
 * <ul>
 * <li>虚拟采矿场（{@code MTEOreDrillingPlant1..4}）：EU/t = {@code 3·4^(t+1)}（48/192/768/3072），
 * 周期 = {@code baseTime>>(t+1)}（240/100/40/15t），每周期挖 1 个矿方块、耗 2000mB 钻井液，
 * 开采区域 = (2R)×(2R) 区块（R=3/4/6/9）；</li>
 * <li>虚拟流体钻机（{@code MTEOilDrill1..4}）：EU/t = {@code 7·4^(t+1)}（112/448/1792/7168），
 * 到底周期恒为 8t，每周期抽 R×R 油田的只读上界产量。</li>
 * </ul>
 */
public abstract class SpaceCenterMachine {

    /** 真实采矿场半径表（区块），下标 = 等级-1。 */
    public static final int[] MINER_RADIUS_CHUNKS = { 3, 4, 6, 9 };
    /** 真实采矿场基准周期（tick），下标 = 等级-1。 */
    public static final int[] MINER_BASE_TIME = { 960, 800, 640, 480 };
    /** 真实流体钻机工作区边长（区块），下标 = 等级-1。 */
    public static final int[] DRILLER_RANGE_CHUNKS = { 1, 2, 4, 8 };
    /** 虚拟钻机的一次性下潜深度（管道消耗量，真实机器下到基岩，离线取定值）。 */
    public static final int DRILLER_DESCEND_DEPTH = 64;

    /** 机器类型。 */
    public enum Type {
        MINER,
        DRILLER
    }

    /** 1-4 级。 */
    public final int tier;
    /** 目标星球（GT 维度键）。 */
    public final String planet;
    /** 当前周期内已推进的 tick。 */
    public int progress;
    /** 缺料等原因暂停中（资源到位后由宿主清除重试）。 */
    public String pauseReason;

    SpaceCenterMachine(int tier, String planet) {
        this.tier = tier;
        this.planet = planet;
    }

    /** 电压档索引（真实机器的最低能量档：2=MV..5=IV），EU/t 与周期位移都由它派生。 */
    public final int voltageTier() {
        return tier + 1;
    }

    public abstract Type type();

    /** 每 tick 功耗（正数，报告用）。 */
    public abstract long euPerTick();

    /** 当前状态的周期长度（tick）。 */
    public abstract int cycleTicks();

    /**
     * 推进若干 tick（一个批次 20t）。缺料时置 {@link #pauseReason} 并停在本周期内。
     */
    public abstract void advance(int ticks, MTESpaceMiningCenter host);

    /** 运行状态持久化。 */
    public abstract void save(NBTTagCompound nbt);

    public abstract void load(NBTTagCompound nbt);

    public static void saveCommon(NBTTagCompound nbt, SpaceCenterMachine machine) {
        nbt.setInteger("tier", machine.tier);
        nbt.setString("planet", machine.planet);
        nbt.setInteger("progress", machine.progress);
        if (machine.pauseReason != null) nbt.setString("pause", machine.pauseReason);
    }

    public static SpaceCenterMachine create(Type type, int tier, String planet) {
        return type == Type.MINER ? new SpaceCenterMiner(tier, planet) : new SpaceCenterDriller(tier, planet);
    }
}
