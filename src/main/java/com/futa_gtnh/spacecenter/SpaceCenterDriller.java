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
 * {@code 0.8 × R×R 油田区块的只读上界产量之和}（固定产量、不衰减，见
 * {@link VirtualOilField}），流体按该星球 GUI 里选中的油田（流体）过滤；</li>
 * <li>不消耗钻井液（真实油气钻机同样不耗）。</li>
 * </ul>
 */
public class SpaceCenterDriller extends SpaceCenterMachine {

    /** 剩余下潜方块数。 */
    public int descendRemaining = DRILLER_DESCEND_DEPTH;
    /** 到底后的固定产出（mB/周期，选中流体；-1 = 未计算）。 */
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
        return 7L << (voltageTier() << 1);
    }

    @Override
    public int cycleTicks() {
        return descendRemaining > 0 ? Math.max(1, 120 >> voltageTier()) : 8;
    }

    @Override
    public void advance(int ticks, MTESpaceMiningCenter host) {
        for (int i = 0; i < ticks; i++) {
            if (pauseReason != null) return;
            progress++;
            if (progress < cycleTicks()) continue;
            progress = 0;
            doCycle(host);
        }
    }

    private void doCycle(MTESpaceMiningCenter host) {
        if (descendRemaining > 0) {
            if (!host.consumeMiningPipe()) {
                pauseReason = "no_mining_pipe";
                return;
            }
            descendRemaining--;
            return;
        }
        if (cachedRate < 0) cachedRate = computeRate(host);
        if (cachedRate <= 0) {
            pauseReason = "no_oil_field";
            return;
        }
        host.emitOil(planet, cachedRate);
    }

    /**
     * 固定产量：R×R 工作区（锚定在区块原点）内、流体匹配所选油田的区块的
     * 只读上界产量之和 × 配置系数（默认 0.8）。
     */
    private int computeRate(MTESpaceMiningCenter host) {
        Long seed = host.planetSeed(planet);
        if (seed == null) return 0;
        PlanetRegistry.PlanetInfo info = PlanetRegistry.byName(planet);
        if (info == null) return 0;
        String fluidKey = host.selectedFluidKey(planet);
        int range = DRILLER_RANGE_CHUNKS[tier - 1];
        long total = 0;
        for (int cx = 0; cx < range; cx++) {
            for (int cz = 0; cz < range; cz++) {
                VirtualOilField.PristineField field = VirtualOilField.pristine(info.dimensionId, seed, cx, cz);
                if (field == null) continue;
                if (fluidKey != null && !fluidKey.equals(field.fluid.Registry)) continue;
                total += field.upperBoundPerCycle();
            }
        }
        return (int) (total * host.oilYieldFactor());
    }

    /** GUI 展示用：每秒产量（mB）。 */
    public int ratePerSecond() {
        return cachedRate > 0 ? cachedRate * 20 / cycleTicks() : 0;
    }

    public boolean atBottom() {
        return descendRemaining <= 0;
    }

    /** 换油田/换星球后重算固定产量（GUI 回调用）。 */
    public void resetRate() {
        cachedRate = -1;
    }

    @Override
    public void save(NBTTagCompound nbt) {
        saveCommon(nbt, this);
        nbt.setInteger("descend", descendRemaining);
        nbt.setInteger("rate", cachedRate);
    }

    @Override
    public void load(NBTTagCompound nbt) {
        descendRemaining = nbt.getInteger("descend");
        if (descendRemaining <= 0) descendRemaining = 0;
        cachedRate = nbt.getInteger("rate");
    }
}
