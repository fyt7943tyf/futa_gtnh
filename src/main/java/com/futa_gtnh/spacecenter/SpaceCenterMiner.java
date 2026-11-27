package com.futa_gtnh.spacecenter;

import java.util.ArrayDeque;

import net.minecraft.nbt.NBTTagCompound;

/**
 * 虚拟采矿场：模拟一台对应等级的 GT 采矿场（Ore Drilling Plant）在目标星球上开采。
 *
 * <p>
 * 行为（对齐 {@code MTEOreDrillingPlantBase} 的 DOWNWARD→AT_BOTTOM 状态机）：
 * <ol>
 * <li>下潜阶段：每周期消耗 1 根采矿管道，深度取当前区块确定性矿脉的 veinMinY；</li>
 * <li>开采阶段：每周期耗 2000mB 钻井液、挖掉当前区块模拟队列里的 1 个矿石方块，
 * 产物 = 该矿石打粉机产出的粉碎矿 ×(1~4)；</li>
 * <li>区块队列空 → 行优先推进到开采区域的下一个区块（跳到新区块重新下潜）；</li>
 * <li>整个 (2R)×(2R) 区域采完 → {@code era+1} 原地重置（AutoResetVein），
 * 或停在「区域采完」等玩家手动重置。</li>
 * </ol>
 *
 * <p>
 * <b>队列不持久化。</b>矿石由「星球 seed + era + 区块坐标」确定性决定，所以存档只记
 * 「当前区块还剩几个没挖」（{@code queued}），读档后把整块重新模拟出来、再从队头跳掉
 * 已经挖掉的那一截。为此宿主交进来的 {@link OreChunk#entries} 必须每次都是同一个顺序，
 * 跳过的数量必须是「总数 − 剩余」——方向反了就等于每次读档都把挖过的矿再发一遍。
 */
public class SpaceCenterMiner extends SpaceCenterMachine {

    /** 下潜深度下限：没有矿脉碰到的区块同样要把管道下到地下（真实采矿场是下到基岩）。 */
    private static final int MIN_DESCEND = 16;
    /** 下潜深度上限。 */
    private static final int MAX_DESCEND = 128;

    /** 随机纪元（0 = 复现真实矿脉；采完 +1 重置）。 */
    public int era;
    /** 开采区域内当前区块的线性下标（行优先）。 */
    public int chunkIndex;
    /** 剩余下潜方块数（= 剩余管道消耗）。 */
    public int descendRemaining;
    /** 当前区块待挖队列（进区块时按确定性模拟构建）。 */
    private final ArrayDeque<OreChunk.Entry> queue = new ArrayDeque<>();
    /** 当前区块的队列是否已建好：新建 / 读档 / 纪元重置后为 false，首次推进时重建。 */
    private boolean queueBuilt;
    /** 读档恢复：重启前当前区块还剩多少方块没挖；-1 = 不需要恢复。 */
    private int resumeRemaining = -1;

    public SpaceCenterMiner(int tier, String planet) {
        super(tier, planet);
        // 此刻没有宿主可查星球种子：下潜深度与队列都留到首次 advance 时定
    }

    @Override
    public Type type() {
        return Type.MINER;
    }

    @Override
    public long euPerTick() {
        return minerEu(tier);
    }

    @Override
    public int cycleTicks() {
        return minerCycle(tier);
    }

    /** 开采区域边长（区块）。 */
    public int areaSide() {
        return minerAreaSide(tier);
    }

    /** 当前区块坐标（行优先遍历，中心在原点附近）。 */
    public int[] currentChunk() {
        int side = areaSide();
        int radius = MINER_RADIUS_CHUNKS[tier - 1];
        int idx = Math.min(Math.max(0, chunkIndex), side * side - 1);
        return new int[] { idx % side - radius + 1, idx / side - radius + 1 };
    }

    @Override
    public void advance(int ticks, MachineHost host) {
        if (!queueBuilt) {
            // 读档恢复时下潜进度以存档为准，只重建队列；其余情况（新建 / 换纪元）连深度一起定
            boolean keepDepth = resumeRemaining >= 0;
            if (!buildChunk(host, !keepDepth)) {
                pauseReason = PAUSE_UNKNOWN_PLANET;
                return;
            }
        }
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
            // 下潜：1 根管道/周期。队列在进区块时就建好了，下潜到底直接转入开采——
            // 这里绝不能再调 buildChunk：它会按矿脉深度把 descendRemaining 重新填满，
            // 机器就永远在「下潜 → 到底 → 又满 → 再下潜」里打转，只烧管道一块矿不出
            if (!host.consumeMiningPipe()) {
                pauseReason = PAUSE_NO_MINING_PIPE;
                return;
            }
            descendRemaining--;
            return;
        }
        OreChunk.Entry ore = queue.poll();
        if (ore == null) {
            nextChunk(host);
            return;
        }
        if (!host.consumeDrillingFluid()) {
            pauseReason = PAUSE_NO_DRILLING_FLUID;
            // 放回队头：补上钻井液后挖的还是这一块
            queue.addFirst(ore);
            return;
        }
        host.emitOre(ore);
    }

    /**
     * 按确定性模拟（重新）构建当前区块的队列。
     *
     * @param setDepth 是否同时按下潜参考深度重设 {@link #descendRemaining}（读档恢复时为 false）
     * @return 星球未知时 false，此时队列保持「未建」状态
     */
    private boolean buildChunk(MachineHost host, boolean setDepth) {
        int[] chunk = currentChunk();
        OreChunk ore = host.simulateChunk(planet, era, chunk[0], chunk[1]);
        if (ore == null) return false;
        queue.clear();
        // entries 由宿主按材料 id 排好序：读档重建时跳过的才是同一批矿
        for (OreChunk.Entry entry : ore.entries) {
            for (int i = 0; i < entry.count; i++) queue.add(entry);
        }
        if (resumeRemaining >= 0) {
            // 整块重建的队列里，前「总数 − 剩余」个是重启前已经挖掉的
            int mined = Math.max(0, queue.size() - resumeRemaining);
            for (int i = 0; i < mined; i++) queue.poll();
            resumeRemaining = -1;
        }
        if (setDepth) {
            // 下潜深度 ≈ 矿脉高度（真实机器把管道下到矿层再扫柱）
            descendRemaining = ore.veinMinY == Integer.MAX_VALUE ? MIN_DESCEND
                : clamp(ore.veinMinY - 8, MIN_DESCEND, MAX_DESCEND);
        }
        queueBuilt = true;
        return true;
    }

    private void nextChunk(MachineHost host) {
        int total = areaSide() * areaSide();
        if (chunkIndex + 1 >= total) {
            if (host.autoResetVein()) {
                era++;
                chunkIndex = 0;
            } else {
                pauseReason = PAUSE_AREA_EXHAUSTED;
                return;
            }
        } else {
            chunkIndex++;
        }
        progress = 0;
        resumeRemaining = -1;
        queueBuilt = false;
        if (!buildChunk(host, true)) pauseReason = PAUSE_UNKNOWN_PLANET;
    }

    /**
     * 手动「矿脉重置」：era+1 回到区域起点。旧纪元的队列整体作废，
     * 下次推进时按新纪元重建——否则会先把旧队列挖完、还顺手跳过新纪元的第 0 块。
     */
    public void resetVein() {
        era++;
        chunkIndex = 0;
        progress = 0;
        queue.clear();
        queueBuilt = false;
        resumeRemaining = -1;
        pauseReason = null;
    }

    /** 当前区块剩余待挖方块数。 */
    public int remainingBlocks() {
        return queue.size();
    }

    @Override
    public void save(NBTTagCompound nbt) {
        nbt.setInteger("era", era);
        nbt.setInteger("chunkIndex", chunkIndex);
        nbt.setInteger("descend", descendRemaining);
        // -1 = 队列还没建过（从没推进过）：读档后按新区块对待，下潜深度重新算
        nbt.setInteger("queued", queueBuilt ? queue.size() : -1);
    }

    @Override
    public void load(NBTTagCompound nbt) {
        era = Math.max(0, nbt.getInteger("era"));
        chunkIndex = clamp(nbt.getInteger("chunkIndex"), 0, areaSide() * areaSide() - 1);
        descendRemaining = Math.max(0, nbt.getInteger("descend"));
        resumeRemaining = Math.max(-1, nbt.getInteger("queued"));
        queueBuilt = false;
    }
}
