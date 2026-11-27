package com.futa_gtnh.spacecenter;

/**
 * 虚拟机向宿主（指挥中心）要资源、交产物、查星球数据的全部出口。
 *
 * <p>
 * 把这些收成一个接口，是为了让 {@link SpaceCenterMiner} / {@link SpaceCenterDriller} 两个状态机
 * 不直接碰 GT 的世界生成与输入仓：它们只依赖本接口、NBT，材料对它们只是个原样传递的句柄，
 * 因而可以在不起游戏、不初始化 GT 的纯 JVM 里跑回归检查（{@code gradlew verifySpaceCenterMachines}）。
 * 状态机里的坑——比如下潜到底之后原地打转、读档后把挖过的矿再挖一遍——光靠编译是看不出来的。
 */
public interface MachineHost {

    /**
     * 该星球某区块在给定 era 下的模拟矿石。
     *
     * @return 矿石数据；星球未知（未勘查或已不在星球表里）时 null
     */
    SpaceCenterMachine.OreChunk simulateChunk(String planet, int era, int chunkX, int chunkZ);

    /**
     * 某区块里与 {@code fluidKey} 匹配的油田的只读上界产量（mB / 周期）。
     *
     * @return 上界产量；没有油田或流体不匹配返回 0，星球未知返回 -1
     */
    int oilUpperBound(String planet, String fluidKey, int chunkX, int chunkZ);

    /**
     * 钻机在该星球要抽的流体注册名（GUI 选中的那一个；没选则是权重最大的那个）。
     *
     * @return 流体注册名；星球没有油田或已不在星球表里时 null
     */
    String drillingFluid(String planet);

    /** 区域采完后是否自动 era+1 原地换矿。 */
    boolean autoResetVein();

    /** 钻机固定产量的配置系数。 */
    double oilYieldFactor();

    /** 从输入总线扣 1 根采矿管道。 */
    boolean consumeMiningPipe();

    /** 从输入仓扣一个矿方块份的钻井液。 */
    boolean consumeDrillingFluid();

    /** 交付一个矿石方块的产物（条目原样来自 {@link #simulateChunk} 的结果）。 */
    void emitOre(SpaceCenterMachine.OreChunk.Entry ore);

    /** 交付钻机抽出的流体（mB）。 */
    void emitOil(String planet, int amount);
}
