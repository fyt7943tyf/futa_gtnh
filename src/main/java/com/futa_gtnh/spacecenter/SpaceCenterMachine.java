package com.futa_gtnh.spacecenter;

import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.interfaces.IOreMaterial;

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

    // 暂停原因 token：原样同步给客户端再本地化（见 SpaceCenterGui），不要在服务端拼人话
    /** 输入总线里没有采矿管道。 */
    public static final String PAUSE_NO_MINING_PIPE = "no_mining_pipe";
    /** 输入仓里钻井液不够 2000mB。 */
    public static final String PAUSE_NO_DRILLING_FLUID = "no_drilling_fluid";
    /** 钻机工作区里没有所选流体的油田。 */
    public static final String PAUSE_NO_OIL_FIELD = "no_oil_field";
    /** 整个区域采完且配置为不自动换矿（硬停，等玩家手动重置）。 */
    public static final String PAUSE_AREA_EXHAUSTED = "area_exhausted";
    /** 目标星球查不到种子或已不在星球表里。 */
    public static final String PAUSE_UNKNOWN_PLANET = "unknown_planet";

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

    // 按等级取数值：状态机自己用，GUI 的 tooltip 也在客户端直接调，不经过同步

    /**
     * 一个区块里模拟出来的矿石，交给状态机按顺序消费的形态。
     *
     * <p>
     * {@link #entries} 必须是确定顺序（宿主按材料 id 排好）：队列不持久化，读档后靠
     * 「整块重建 + 跳过已挖数量」恢复，顺序一变跳过的就不是同一批矿。
     */
    public static final class OreChunk {

        /** 一类矿石。 */
        public static final class Entry {

            /** 材料；对状态机是不透明句柄，原样交给 {@link MachineHost#emitOre}。 */
            public final IOreMaterial material;
            /** 材料内部名（排查问题与回归检查用，状态机不读）。 */
            public final String name;
            public final int count;
            /** true = 矿脉附带的小矿（产出不乘 1~4）。 */
            public final boolean small;

            public Entry(IOreMaterial material, String name, int count, boolean small) {
                this.material = material;
                this.name = name;
                this.count = count;
                this.small = small;
            }
        }

        /** 触碰本区块的矿脉中最低的 veinMinY；没有矿脉时 {@link Integer#MAX_VALUE}。 */
        public final int veinMinY;
        public final List<Entry> entries;

        public OreChunk(int veinMinY, List<Entry> entries) {
            this.veinMinY = veinMinY;
            this.entries = entries;
        }
    }

    /** 虚拟采矿场 t=1..4 的功耗：{@code 3·4^(t+1)} = 48 / 192 / 768 / 3072 EU/t。 */
    public static long minerEu(int tier) {
        return 3L << ((tier + 1) << 1);
    }

    /** 虚拟采矿场的工作周期：{@code baseTime>>(t+1)} = 240 / 100 / 40 / 15 tick。 */
    public static int minerCycle(int tier) {
        return Math.max(1, MINER_BASE_TIME[tier - 1] >> (tier + 1));
    }

    /** 虚拟采矿场开采区域的边长（区块）：{@code 2R} = 6 / 8 / 12 / 18。 */
    public static int minerAreaSide(int tier) {
        return 2 * MINER_RADIUS_CHUNKS[tier - 1];
    }

    /** 虚拟流体钻机 t=1..4 的功耗：{@code 7·4^(t+1)} = 112 / 448 / 1792 / 7168 EU/t。 */
    public static long drillerEu(int tier) {
        return 7L << ((tier + 1) << 1);
    }

    /** 每 tick 功耗（正数，报告用）。 */
    public abstract long euPerTick();

    /** 当前状态的周期长度（tick）。 */
    public abstract int cycleTicks();

    /**
     * 推进若干 tick（一个批次 20t）。缺料时置 {@link #pauseReason} 并停在本周期内。
     */
    public abstract void advance(int ticks, MachineHost host);

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

    /**
     * 多方块的层数判定（纯逻辑，回归检查直接调）：结构 = 底座（1 层）+ 若干中间层 + 顶盖（1 层）。
     *
     * <p>
     * 顶盖出现在底座上方第 n 格时，总层数恰好是 n+1，<b>不能再自增</b>——否则 2 层结构会被
     * 当成 3 层（机位 16 而不是 8）。到层数上限时也不能直接结束：必须把顶盖验到位，
     * 否则「底座 + 7 层中间层、顶盖缺失或被换成别的东西」的残缺结构会被当成满层成型。
     *
     * @param topAt   底座上方第 n 格（n ≥ 1）是不是顶盖
     * @param layerAt 第 n 格是不是中间层
     * @return 总层数；结构不成立返回 -1
     */
    public static int resolveHeight(int minHeight, int maxHeight, java.util.function.IntPredicate topAt,
        java.util.function.IntPredicate layerAt) {
        int height = minHeight;
        while (true) {
            if (topAt.test(height - 1)) return height;
            // 没到上限时这一格还有可能是中间层；到上限了顶盖就是硬性要求
            if (height >= maxHeight || !layerAt.test(height - 1)) return -1;
            height++;
        }
    }

    /** 不借 GTUtility 的钳制：状态机要能在不加载 GT 的纯 JVM 里跑回归检查。 */
    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
