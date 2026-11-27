package com.futa_gtnh.spacecenter;

import gregtech.GTMod;
import gregtech.api.objects.GTUOFluid;
import gregtech.api.objects.XSTR;
import gregtech.common.UndergroundOil;

/**
 * 虚拟油田：离线复现 GT 的地下流体生成（端口自 {@code UndergroundOil.getPristineAmount}，
 * RNG 次序完全一致），再按 {@code MTEOilDrillBase.pumpOil} 的只读（simulate）口径算
 * 「上界产量」。
 *
 * <p>
 * 真实公式：
 * <ul>
 * <li>8×8 区块共享一个油藏：{@code XSTR(seed + dim*2 + (cx>>3) + 8267*(cz>>3))}；</li>
 * <li>流体按该维度权重抽取，再 {@code GTUOFluid.getRandomAmount}（五次幂分布）得基准量；</li>
 * <li>逐区块丢 {@code ((cx&7)<<3 | cz&7)} 次 {@code next(24)} 后
 * {@code amount = base*(0.75 + nextFloat()/2)}（±25% 抖动）；</li>
 * <li>每周期每区块上界产量 = {@code floor(amount * speed / 5000)}（speed=0.5 默认档）。</li>
 * </ul>
 */
public final class VirtualOilField {

    /** GT 的产量分母（{@link UndergroundOil#DIVIDER}）。 */
    public static final int DIVIDER = 5000;

    /** 默认（无超频）档的抽取速度，见 {@code MTEOilDrillBase.computeSpeed}。 */
    public static final float DEFAULT_SPEED = 0.5F;

    private VirtualOilField() {}

    /**
     * 计算一个区块的原始油田数据（不衰减）。
     *
     * @param dimId         数字维度 id
     * @param worldSeed     该维度世界 seed
     * @param chunkX/chunkZ 目标区块
     * @return 该区块实际会生成的流体与数量；该区块没油或维度无油田配置时 null
     */
    public static PristineField pristine(int dimId, long worldSeed, int chunkX, int chunkZ) {
        gregtech.api.objects.GTUODimension dimension = GTMod.proxy.mUndergroundOil.GetDimension(dimId);
        if (dimension == null) return null;
        final XSTR rng = new XSTR(worldSeed + dimId * 2L + (chunkX >> 3) + 8267L * (chunkZ >> 3));
        GTUOFluid fluid = dimension.getRandomFluid(rng);
        if (fluid == null || fluid.getFluid() == null) return null;
        int veinAverage = fluid.getRandomAmount(rng);
        for (int i = 0; i < (((chunkX & 0x7) << 3) | chunkZ & 0x7); i++) {
            rng.next(24);
        }
        int amount = (int) ((float) veinAverage * (0.75f + rng.nextFloat() / 2f));
        return new PristineField(fluid, amount);
    }

    /** 一个区块的原始（未开采）油田。 */
    public static final class PristineField {

        public final GTUOFluid fluid;
        /** 基准存量（桶口径，与 GT 内部一致）。 */
        public final int amount;

        PristineField(GTUOFluid fluid, int amount) {
            this.fluid = fluid;
            this.amount = amount;
        }

        /** 只读（simulate）口径的每周期上界产量。 */
        public int upperBoundPerCycle() {
            return (int) Math.floor(amount * (double) DEFAULT_SPEED / DIVIDER);
        }
    }
}
