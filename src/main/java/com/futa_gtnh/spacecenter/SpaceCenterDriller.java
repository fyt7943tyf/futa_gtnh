package com.futa_gtnh.spacecenter;

import net.minecraft.nbt.NBTTagCompound;

/**
 * 虚拟流体钻机：模拟一台对应等级的 GT 油气钻机（Oil Drilling Plant）在目标星球抽油。
 *
 * <p>
 * 行为（对齐 {@code MTEOilDrillBase}）：
 * <ol>
 * <li>下潜阶段：每周期（{@code 120>>(t+1)} tick）消耗 1 根采矿管道，共
 * {@link #DRILLER_DESCEND_DEPTH} 根（真实机器下到基岩）；</li>
 * <li>到底后周期恒为 8t（{@code (64·R²)>>(t-1)>>(t)} 恰好归一到 8），每周期产出
 * {@code 0.8 × R×R 油田区块中、流体与所选油田相同的区块的只读上界产量之和}
 * （固定产量、不衰减，见 {@link VirtualOilField}）；</li>
 * <li>不消耗钻井液（真实油气钻机同样不耗）。</li>
 * </ol>
 *
 * <p>
 * <b>流体口径：</b>产量只累计「流体 = 所选油田」的区块，产出也只会是这一种流体。
 * 没选油田时取该星球权重最大的那种——而不是把所有流体的存量加在一起再全部当成
 * 权重最大的那种吐出来（那等于把别的流体凭空变成它）。
 */
public class SpaceCenterDriller extends SpaceCenterMachine {

    /** 剩余下潜方块数。 */
    public int descendRemaining = DRILLER_DESCEND_DEPTH;
    /** 到底后的固定产出（mB/周期，所选流体；-1 = 未计算）。 */
    private int cachedRate = -1;

    public SpaceCenterDriller(int tier, String planet) {
        super(tier, planet);
    }

    @Override
    public Type type() {
        return Type.DRILLER;
    }

    @Override
    public long euPerTick() {
        return drillerEu(tier);
    }

    @Override
    public int cycleTicks() {
        return descendRemaining > 0 ? Math.max(1, 120 >> voltageTier()) : 8;
    }

    @Override
    public void advance(int ticks, MachineHost host) {
        for (int i = 0; i < ticks; i++) {
            if (pauseReason != null) return;
            progress++;
            if (progress < cycleTicks()) continue;
            progress = 0;
            doCycle(host);
        }
    }

    private void doCycle(MachineHost host) {
        if (descendRemaining > 0) {
            if (!host.consumeMiningPipe()) {
                pauseReason = PAUSE_NO_MINING_PIPE;
                return;
            }
            descendRemaining--;
            return;
        }
        if (cachedRate < 0) cachedRate = computeRate(host);
        if (cachedRate < 0) {
            pauseReason = PAUSE_UNKNOWN_PLANET;
        } else if (cachedRate == 0) {
            pauseReason = PAUSE_NO_OIL_FIELD;
        } else {
            host.emitOil(planet, cachedRate);
        }
    }

    /**
     * 固定产量：R×R 工作区（锚定在区块原点）内、流体与 {@link MachineHost#drillingFluid} 一致的
     * 区块的只读上界产量之和 × 配置系数（默认 0.8）。
     *
     * @return mB/周期；星球未知返回 -1，所选流体在工作区里没有油田返回 0
     */
    private int computeRate(MachineHost host) {
        String fluid = host.drillingFluid(planet);
        if (fluid == null) return 0;
        int range = DRILLER_RANGE_CHUNKS[tier - 1];
        long total = 0;
        for (int cx = 0; cx < range; cx++) {
            for (int cz = 0; cz < range; cz++) {
                int bound = host.oilUpperBound(planet, fluid, cx, cz);
                if (bound < 0) return -1;
                total += bound;
            }
        }
        return (int) (total * host.oilYieldFactor());
    }

    public boolean atBottom() {
        return descendRemaining <= 0;
    }

    /** 换油田 / 换星球后重算固定产量（GUI 回调用）。 */
    public void resetRate() {
        cachedRate = -1;
    }

    @Override
    public void save(NBTTagCompound nbt) {
        nbt.setInteger("descend", descendRemaining);
        nbt.setInteger("rate", cachedRate);
    }

    @Override
    public void load(NBTTagCompound nbt) {
        descendRemaining = Math.max(0, nbt.getInteger("descend"));
        // 不信任存档里的固定产量：配置系数、所选油田、星球表都可能和存档时不一样，下个周期重算
        cachedRate = -1;
    }
}
