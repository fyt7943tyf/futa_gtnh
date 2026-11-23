package com.futa_gtnh.spacecenter;

import java.util.ArrayDeque;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import gregtech.api.enums.OrePrefixes;
import gregtech.api.interfaces.IOreMaterial;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTUtility;

/**
 * 虚拟采矿场：模拟一台对应等级的 GT 采矿场（Ore Drilling Plant）在目标星球上开采。
 *
 * <p>
 * 行为（对齐 {@code MTEOreDrillingPlantBase} 的 DOWNWARD→AT_BOTTOM 状态机）：
 * <ol>
 * <li>下潜阶段：每周期消耗 1 根采矿管道，深度取当前区块确定性矿脉的 veinMinY；</li>
 * <li>开采阶段：每周期耗 2000mB 钻井液、挖掉当前区块模拟队列里的 1 个矿石方块，
 * 产物 = 该矿石打粉机产出的粉碎矿 ×(1~4)；</li>
 * <li>区块队列空 → 行优先推进到开采区域的下一个区块（跳到新区块重新下潜）；
 * <li>整个 (2R)×(2R) 区域采完 → {@code era+1} 原地重置（AutoResetVein），
 * 或停在「区域采完」等玩家手动重置。</li>
 * </ol>
 */
public class SpaceCenterMiner extends SpaceCenterMachine {

    /** 待挖矿石条目。 */
    private static final class OrePop {

        final IOreMaterial material;
        /** true = placer 小矿（产出不乘 1~4）。 */
        final boolean small;

        OrePop(IOreMaterial material, boolean small) {
            this.material = material;
            this.small = small;
        }
    }

    /** 随机纪元（0 = 复现真实矿脉；采完 +1 重置）。 */
    public int era;
    /** 开采区域内当前区块的线性下标（行优先）。 */
    public int chunkIndex;
    /** 剩余下潜方块数（= 剩余管道消耗）。 */
    public int descendRemaining;
    /** 当前区块待挖队列（进入新区块时构建）。 */
    private final ArrayDeque<OrePop> queue = new ArrayDeque<>();
    /** 存档恢复时待跳过的已挖方块数（首次 tick 重建队列后消费）。 */
    private int pendingQueueSkip = -1;

    public SpaceCenterMiner(int tier, String planet) {
        super(tier, planet);
        enterFreshChunk(null);
    }

    @Override
    public Type type() {
        return Type.MINER;
    }

    @Override
    public long euPerTick() {
        return 3L << (voltageTier() << 1);
    }

    @Override
    public int cycleTicks() {
        return Math.max(1, MINER_BASE_TIME[tier - 1] >> voltageTier());
    }

    /** 开采区域边长（区块）。 */
    public int areaSide() {
        return 2 * MINER_RADIUS_CHUNKS[tier - 1];
    }

    /** 当前区块坐标（行优先遍历，中心在原点附近）。 */
    public int[] currentChunk() {
        int side = areaSide();
        int radius = MINER_RADIUS_CHUNKS[tier - 1];
        int idx = Math.min(chunkIndex, side * side - 1);
        return new int[] { idx % side - radius + 1, idx / side - radius + 1 };
    }

    @Override
    public void advance(int ticks, MTESpaceMiningCenter host) {
        // 存档恢复：重建当前区块队列并跳过重启前已挖掉的部分
        if (pendingQueueSkip >= 0 && descendRemaining <= 0 && queue.isEmpty()) {
            enterFreshChunk(host);
            for (int i = 0; i < pendingQueueSkip && !queue.isEmpty(); i++) {
                queue.poll();
            }
            pendingQueueSkip = -1;
        }
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
            // 下潜：1 根管道/周期
            if (!host.consumeMiningPipe()) {
                pauseReason = "no_mining_pipe";
                return;
            }
            descendRemaining--;
            if (descendRemaining <= 0) enterFreshChunk(host);
            return;
        }
        OrePop pop = queue.poll();
        if (pop == null) {
            nextChunk(host);
            return;
        }
        if (!host.consumeDrillingFluid()) {
            pauseReason = "no_drilling_fluid";
            queue.push(pop);
            return;
        }
        host.emitOre(pop.material, !pop.small);
    }

    /** 进入当前 chunkIndex 的区块（构建队列 + 设定下潜深度）。 */
    private void enterFreshChunk(MTESpaceMiningCenter host) {
        queue.clear();
        Long seed = host != null ? host.planetSeed(planet) : null;
        if (seed != null) {
            PlanetRegistry.PlanetInfo info = PlanetRegistry.byName(planet);
            if (info != null) {
                int[] chunk = currentChunk();
                VirtualVeinSim.ChunkOre ore = VirtualVeinSim
                    .simulateChunk(planet, seed, info.dimensionId, era, chunk[0], chunk[1]);
                for (Map.Entry<IOreMaterial, Integer> entry : ore.veinBlocks.entrySet()) {
                    for (int i = 0; i < entry.getValue(); i++) queue.add(new OrePop(entry.getKey(), false));
                }
                for (Map.Entry<IOreMaterial, Integer> entry : ore.smallOres.entrySet()) {
                    for (int i = 0; i < entry.getValue(); i++) queue.add(new OrePop(entry.getKey(), true));
                }
                // 下潜深度 ≈ 矿脉高度（真实机器把管道下到矿层再扫柱）
                descendRemaining = ore.minVeinY == Integer.MAX_VALUE ? 16 : GTUtility.clamp(ore.minVeinY - 8, 16, 128);
                return;
            }
        }
        // 星球未知（未勘查）：队列空、下潜 16，等宿主重试
        descendRemaining = 16;
    }

    private void nextChunk(MTESpaceMiningCenter host) {
        int side = areaSide();
        int total = side * side;
        if (chunkIndex + 1 >= total) {
            if (host != null && host.autoResetVein()) {
                era++;
                chunkIndex = 0;
            } else {
                pauseReason = "area_exhausted";
                return;
            }
        } else {
            chunkIndex++;
        }
        progress = 0;
        enterFreshChunk(host);
    }

    /** 当前区块剩余待挖方块数（GUI 展示）。 */
    public int remainingBlocks() {
        return queue.size();
    }

    @Override
    public void save(NBTTagCompound nbt) {
        saveCommon(nbt, this);
        nbt.setInteger("era", era);
        nbt.setInteger("chunkIndex", chunkIndex);
        nbt.setInteger("descend", descendRemaining);
        nbt.setInteger("queued", queue.size());
    }

    @Override
    public void load(NBTTagCompound nbt) {
        era = nbt.getInteger("era");
        chunkIndex = nbt.getInteger("chunkIndex");
        descendRemaining = nbt.getInteger("descend");
        // 队列不持久化：首次 tick 用确定性模拟重建，再跳过重启前已挖的数量
        pendingQueueSkip = nbt.getInteger("queued");
    }

    /** 供 GUI 取粉碎矿预览：材料的 ore 物品（与产出同一口径）。 */
    public static ItemStack orePreview(IOreMaterial material) {
        if (material instanceof gregtech.api.enums.Materials m) {
            return GTOreDictUnificator.get(OrePrefixes.ore, m, 1);
        }
        return null;
    }
}
