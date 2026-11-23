package com.futa_gtnh.spacecenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gtnewhorizon.gtnhlib.hash.Fnv1a64;

import galacticgreg.api.ModDimensionDef;
import galacticgreg.api.enums.DimensionDef;
import gregtech.api.enums.GTValues;
import gregtech.api.interfaces.IOreMaterial;
import gregtech.api.objects.XSTR;
import gregtech.common.GTWorldgenerator;
import gregtech.common.WorldgenGTOreLayer;
import gregtech.common.worldgen.WorldgenQuery;

/**
 * 虚拟矿脉模拟器：离线（不加载区块）复现 GT 5.09.54.175 的矿脉生成算法，输入
 * {@code 维度键 + 世界 seed + era + 区块坐标}，输出该区块会生成哪些矿石方块。
 *
 * <p>
 * 端口自 GT5u 源码（保持 RNG 调用次序完全一致）：
 * <ul>
 * <li>{@code GTWorldgenerator.WorldGenContainer.generateVein}：种子链
 * {@code oreveinSeed=(seed<<16)^((dim&0xff)<<56|(sx&0xfffffff)<<28|(sz&0xfffffff))}
 * → {@code nextInt(100) < 矿脉概率} → {@code FNV1a64(oreveinSeed,i)} →
 * {@link WorldgenQuery} 按维度权重抽取矿脉；</li>
 * <li>{@code WorldgenGTOreLayer.resolveVeinPlacement}：五次 {@code nextInt}
 * （minY/westX/eastX/northZ/southZ）后取 {@code rng.getSeed()} 当放置种子；</li>
 * <li>{@code generateWithPlacement}：9 层（底 3 层 secondary、2-4 层 between、顶 2 层
 * primary，每列至多 1 块、类别命中即 continue）+ placer 小矿
 * {@code 重叠面积×密度/10×倍率}。</li>
 * </ul>
 *
 * <p>
 * 与真实生成的已知偏差（都偏保守或中性，见 README）：
 * <ol>
 * <li>矿脉种类取「首个尝试」（真实世界由首个处理该种子的区块锁定，虚拟矿机从 (0,0)
 * 向外扩张与常规世界生成顺序一致，绝大多数情况相同）；</li>
 * <li>假设放置目标永远是石头（真实世界洞穴/空气会使部分列放不下 → 虚拟产量略高于实地）；</li>
 * <li>{@code era>0}（区域采完原地重置）时把 oreveinSeed 先过一遍 FNV 哈希再混入 era，
 * 生成「分布规律与真实星球一致」的新矿脉，但内容与真实地形无关。</li>
 * </ol>
 */
public final class VirtualVeinSim {

    /** 一个区块的模拟结果：矿脉名列表 + 每种矿石的方块数（含 placer 小矿，分开记）。 */
    public static final class ChunkOre {

        /** 触碰到本区块的矿脉（去重后的名字，用于 GUI 展示）。 */
        public final List<String> veinNames = new ArrayList<>();
        /** 矿脉大方块：材料 → 数量。 */
        public final Map<IOreMaterial, Integer> veinBlocks = new HashMap<>();
        /** 矿脉附带小矿：材料 → 数量。 */
        public final Map<IOreMaterial, Integer> smallOres = new HashMap<>();
        /** 触碰本区块的矿脉中最低的 veinMinY（虚拟下潜深度参考）。 */
        public int minVeinY = Integer.MAX_VALUE;

        public boolean isEmpty() {
            return veinBlocks.isEmpty() && smallOres.isEmpty();
        }

        public int totalBlocks() {
            int total = 0;
            for (int n : veinBlocks.values()) total += n;
            for (int n : smallOres.values()) total += n;
            return total;
        }
    }

    /** 模拟结果 LRU 缓存（区块粒度，最多保留这么多区块）。 */
    private static final int CACHE_LIMIT = 4096;
    private static final LinkedHashMap<Long, ChunkOre> CACHE = new LinkedHashMap<>(256, 0.75f, true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ChunkOre> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    /** era 重置会让缓存整体失效，直接按 era 进 key 即可。 */
    private static long cacheKey(String dimName, int era, int chunkX, int chunkZ) {
        long h = dimName.hashCode();
        return (h << 32) ^ ((era & 0xFFFFL) << 20) ^ ((chunkX & 0x3FFFL) << 8) ^ (chunkZ & 0xFFL) ^ (h >>> 48);
    }

    private VirtualVeinSim() {}

    /** 模拟某星球某区块的矿石（带缓存）。 */
    public static ChunkOre simulateChunk(String dimName, long worldSeed, int dimensionId, int era, int chunkX,
        int chunkZ) {
        long key = cacheKey(dimName, era, chunkX, chunkZ);
        synchronized (CACHE) {
            ChunkOre cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        ChunkOre result = simulateUncached(dimName, worldSeed, dimensionId, era, chunkX, chunkZ);
        synchronized (CACHE) {
            CACHE.put(key, result);
        }
        return result;
    }

    public static void invalidateCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static ChunkOre simulateUncached(String dimName, long worldSeed, int dimensionId, int era, int chunkX,
        int chunkZ) {
        ChunkOre result = new ChunkOre();
        ModDimensionDef def = DimensionDef.getDefByName(dimName);
        if (def == null || !def.generatesOre()) return result;

        // MAX_VEIN_SIZE = 2：种子区块半径 2 内的矿脉都可能碰到本区块
        for (int sx = chunkX - 2; sx <= chunkX + 2; sx++) {
            for (int sz = chunkZ - 2; sz <= chunkZ + 2; sz++) {
                if (!GTWorldgenerator.isOreChunk(sx, sz)) continue;
                contributeSeed(result, dimName, worldSeed, dimensionId, era, chunkX, chunkZ, sx, sz, def);
            }
        }
        return result;
    }

    private static void contributeSeed(ChunkOre result, String dimName, long worldSeed, int dimensionId, int era,
        int chunkX, int chunkZ, int seedChunkX, int seedChunkZ, ModDimensionDef def) {
        long oreveinSeed = (worldSeed << 16)
            ^ (((dimensionId & 0xffL) << 56) | (((long) seedChunkX & 0x000000000fffffffL) << 28)
                | ((long) seedChunkZ & 0x000000000fffffffL));
        if (era > 0) {
            // 原地重置：换一个确定性偏移，分布规律不变但内容重新随机
            oreveinSeed = Fnv1a64.hashStep(Fnv1a64.hashStep(Fnv1a64.initialState(), oreveinSeed), era);
        }

        XSTR oreveinRNG = new XSTR(oreveinSeed);
        int roll = oreveinRNG.nextInt(100);
        if (roll >= def.getOreVeinChance()) return; // NoOresInVein

        // 尝试 i=0（离线口径：首个尝试即锁定，见类注释）
        long attemptSeed = Fnv1a64.hashStep(Fnv1a64.hashStep(Fnv1a64.initialState(), oreveinSeed), 0);
        XSTR veinRNG = new XSTR(attemptSeed);
        WorldgenGTOreLayer layer = WorldgenQuery.veins()
            .inDimension(dimName)
            .findRandom(veinRNG);
        if (layer == null || layer.mPrimary == null) return;

        long placementSeed = Fnv1a64.hashStep(attemptSeed, layer.mPrimary.getId());
        veinRNG.setSeed(placementSeed);

        // resolveVeinPlacement（星球 respectsOreVeinHeights，跳过末地分支）
        int minY = layer.getMinY(dimName);
        int maxY = layer.getMaxY(dimName);
        int veinMinY = minY + veinRNG.nextInt(Math.max(1, maxY - minY - 5));
        int seedBlockX = seedChunkX * 16;
        int seedBlockZ = seedChunkZ * 16;
        int veinWestX = seedBlockX - veinRNG.nextInt(layer.mSize);
        int veinEastX = seedBlockX + 16 + veinRNG.nextInt(layer.mSize);
        int veinNorthZ = seedBlockZ - veinRNG.nextInt(layer.mSize);
        int veinSouthZ = seedBlockZ + 16 + veinRNG.nextInt(layer.mSize);
        long generationSeed = veinRNG.getSeed();

        // 本区块的相交范围（+2 偏置与真实世界一致）
        int chunkBlockX = chunkX * 16;
        int chunkBlockZ = chunkZ * 16;
        int limitWestX = Math.max(veinWestX, chunkBlockX + 2);
        int limitEastX = Math.min(veinEastX, chunkBlockX + 2 + 16);
        int limitNorthZ = Math.max(veinNorthZ, chunkBlockZ + 2);
        int limitSouthZ = Math.min(veinSouthZ, chunkBlockZ + 2 + 16);
        if (limitWestX >= limitEastX || limitNorthZ >= limitSouthZ) return; // 矿脉碰不到本区块

        result.veinNames.add(layer.getName());
        result.minVeinY = Math.min(result.minVeinY, veinMinY);

        double dxc = chunkX - seedChunkX;
        double dzc = chunkZ - seedChunkZ;
        int localDensity = Math.max(1, layer.mDensity / (int) Math.sqrt(2 + dxc * dxc + dzc * dzc));

        XSTR rng = new XSTR(generationSeed);
        // 9 层：底 3 层 secondary，2~4 层 between，顶 2 层 primary（level 从 veinMinY-1 开始）
        boolean[][] layerFlags = { { true, false, false }, { true, false, false }, { true, false, false },
            { true, true, false }, { false, true, false }, { false, true, true }, { false, true, true },
            { false, false, true }, { false, false, true } };
        for (boolean[] flags : layerFlags) {
            generateLayer(
                result,
                rng,
                flags[0],
                flags[1],
                flags[2],
                limitWestX,
                limitEastX,
                limitNorthZ,
                limitSouthZ,
                veinWestX,
                veinEastX,
                veinNorthZ,
                veinSouthZ,
                localDensity,
                layer);
        }

        // placer 小矿：真实世界在与矿脉相交的区块里额外撒的小矿（每轮 4 种材料各 1 个）
        if (GTValues.oreveinPlacerOres) {
            int smallOres = (limitEastX - limitWestX) * (limitSouthZ - limitNorthZ)
                * layer.mDensity
                / 10
                * GTValues.oreveinPlacerOresMultiplier;
            if (smallOres > 0) {
                addOne(result.smallOres, layer.mPrimary);
                if (layer.mSecondary != null) addOne(result.smallOres, layer.mSecondary);
                if (layer.mBetween != null) addOne(result.smallOres, layer.mBetween);
                if (layer.mSporadic != null) addOne(result.smallOres, layer.mSporadic);
                // 每轮 4 个、共 smallOres 轮
                for (int i = 1; i < smallOres; i++) {
                    addOne(result.smallOres, layer.mPrimary);
                    addOne(result.smallOres, layer.mSecondary);
                    addOne(result.smallOres, layer.mBetween);
                    addOne(result.smallOres, layer.mSporadic);
                }
            }
        }
    }

    /** 端口自 {@code WorldgenGTOreLayer.LayerGenerator.generateLayer}（假设目标永远是石头）。 */
    private static void generateLayer(ChunkOre result, XSTR rng, boolean secondary, boolean between, boolean primary,
        int limitWestX, int limitEastX, int limitNorthZ, int limitSouthZ, int veinWestX, int veinEastX, int veinNorthZ,
        int veinSouthZ, int localDensity, WorldgenGTOreLayer layer) {
        for (int tX = limitWestX; tX < limitEastX; tX++) {
            int chanceX = Math.max(1, Math.max(Math.abs(veinWestX - tX), Math.abs(veinEastX - tX)) / localDensity);
            for (int tZ = limitNorthZ; tZ < limitSouthZ; tZ++) {
                int chanceZ = Math
                    .max(1, Math.max(Math.abs(veinSouthZ - tZ), Math.abs(veinNorthZ - tZ)) / localDensity);
                // 次序与真实代码一致：primary → between → secondary → sporadic
                if (primary) {
                    if (rng.nextInt(chanceZ) == 0 || rng.nextInt(chanceX) == 0) {
                        addOne(result.veinBlocks, layer.mPrimary);
                        continue;
                    }
                }
                if (between && layer.mBetween != null) {
                    if (rng.nextInt(chanceZ) == 0 || rng.nextInt(chanceX) == 0) {
                        addOne(result.veinBlocks, layer.mBetween);
                        continue;
                    }
                }
                if (secondary && layer.mSecondary != null) {
                    if (rng.nextInt(chanceZ) == 0 || rng.nextInt(chanceX) == 0) {
                        addOne(result.veinBlocks, layer.mSecondary);
                        continue;
                    }
                }
                if (rng.nextInt(7) == 0 && (rng.nextInt(chanceZ) == 0 || rng.nextInt(chanceX) == 0)
                    && layer.mSporadic != null) {
                    addOne(result.veinBlocks, layer.mSporadic);
                }
            }
        }
    }

    private static void addOne(Map<IOreMaterial, Integer> map, IOreMaterial material) {
        if (material == null) return;
        Integer current = map.get(material);
        map.put(material, current == null ? 1 : current + 1);
    }
}
