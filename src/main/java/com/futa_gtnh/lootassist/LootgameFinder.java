package com.futa_gtnh.lootassist;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import eu.usrv.legacylootgames.StructureGenerator;
import ru.timeconqueror.lootgames.common.config.LGConfigs;
import ru.timeconqueror.lootgames.registry.LGBlocks;

/**
 * lootgames 地牢位置的<b>纯种子推算</b> + 区块内验证。
 *
 * <p>
 * LootGames 的世界生成是确定性的：每个区块要不要长地牢，只由
 * 「世界种子 + 区块坐标 + 配置的菱形尺寸」决定（{@code LootGamesWorldGen.canSpawnInChunk_v3}），
 * 完全不查询地形。所以只要把那段数学原样搬过来，就能在<b>不加载任何区块</b>的
 * 前提下算出任意范围内所有「候选点」 —— 这是助手搜索零开销的原因。
 *
 * <p>
 * 但候选点≠真地牢：世界生成时还要在 21×21 的脚印里找到一段合法的地下空间
 * （{@code StructureGenerator.setSurfaceLevel}），找不到、或区域里有 TileEntity
 * （{@code checkForFreeSpace}），地牢就不会真的生成。另外 Y 坐标取决于地表高度，
 * 种子推不出来。所以候选点要靠<b>加载区块后扫方块</b>来确认
 * （{@link #findMasterY}），这一步由 {@link LootassistManager} 的渐进搜索负责。
 *
 * <p>
 * <b>算法保真的两处细节</b>（改动任何一处，推算结果就和 lootgames 实际生成对不上）：
 * <ol>
 * <li>种子哈希里的整数除法/移位/取模都按 Java 语义原样照抄，
 * 尤其 {@code mod()} 的负数修正（Java 的 {@code %} 对负数返回负值）；</li>
 * <li>{@link Random} 复用同一个实例、每次先 {@code setSeed} —— 和原版
 * {@code LootGamesWorldGen._mRnd} 的用法一致（setSeed 会完整重置状态，无泄漏）。</li>
 * </ol>
 */
public final class LootgameFinder {

    private LootgameFinder() {}

    /**
     * 入口/房间几何常量直接引用 LootGames 的 {@link StructureGenerator}（public 常量），
     * 不再手抄数字 —— 那边改了这边自动跟。
     */
    private static final int MASTER_TE_OFFSET = StructureGenerator.PUZZLEROOM_MASTER_TE_OFFSET;
    private static final int CENTER_TO_BORDER = StructureGenerator.PUZZLEROOM_CENTER_TO_BORDER;
    /** 入口阶梯最多挖 15 格（原版 {@code StructureGenerator} 的硬上限）。 */
    private static final int ENTRANCE_MAX_RUN = 15;

    /** 与原版相同的共享 Random：isCandidateChunk 每次调用都先 setSeed，复用无副作用。 */
    private static final Random RNG = new Random();

    /**
     * @return 该维度是否还可能生成地牢（lootgames 在场 + 未禁用世界生成 + 维度在白名单）
     */
    public static boolean isWorldGenEnabled(int dim) {
        if (!LootgamesCompat.isAvailable()) return false;
        if (LGConfigs.GENERAL.worldGen.disableDungeonGen) return false;
        return LGConfigs.GENERAL.worldGen.isDimensionEnabledForWG(dim);
    }

    /**
     * 种子推算：这个区块是否属于「会长地牢」的候选。
     *
     * <p>
     * 逐行对应 {@code LootGamesWorldGen.canSpawnInChunk_v3}，包括最后那对
     * {@code (pos1+1, pos2)} / {@code (pos1+1, pos2+1)} 的孪生候选 —— 原版在每个
     * 菱形单元里其实允许 3 个相邻的落点，漏掉任何一个就会漏报地牢。
     */
    public static boolean isCandidateChunk(long worldSeed, int dim, int chunkX, int chunkZ) {
        int rhombSize = LGConfigs.GENERAL.worldGen.getWorldGenRhombusSize(dim);
        if (rhombSize < 5) return false; // 配置校验本就拒绝 <5，这里兜底防除零/退化

        int xc = (chunkX * 2) + chunkZ;
        int zc = (chunkZ * 2) + chunkX;
        RNG.setSeed(worldSeed + (xc / (rhombSize * 2)) + ((zc / (rhombSize * 2)) << 14));

        int pos1 = 3 + RNG.nextInt(rhombSize * 2 - 3);
        int pos2 = 3 + RNG.nextInt(rhombSize * 2 - 3);

        int modX = mod(xc, rhombSize * 2);
        int modZ = mod(zc, rhombSize * 2);

        if (modX < 3 || modZ < 3) return false;
        return (modX == pos1 && modZ == pos2) || (modX == pos1 + 1 && (modZ == pos2 || modZ == pos2 + 1));
    }

    /** Java 的 % 对负数返回负值；原版用这个修正版本，这里必须一样。 */
    private static int mod(int x, int div) {
        int r = x % div;
        return r < 0 ? r + div : r;
    }

    /**
     * @return 区块中心（地牢中心）的世界坐标 x/z；候选区块 → {@code (cx<<4)+8, (cz<<4)+8}
     */
    public static int centerCoord(int chunkCoord) {
        return (chunkCoord << 4) + 8;
    }

    /**
     * 在已加载的区块里验证地牢：沿中心列扫描 Puzzle Master 方块。
     *
     * <p>
     * 主方块的 y = 房间底面 + 3，而房间底面 = 合法地表 - 10，合法地表在 21..128 之间，
     * 所以扫描 14..125 就能覆盖全部可能。只查一列（x/z 是精确已知的），
     * 一次一百来次 {@code getBlock}，微秒级。
     *
     * @return 主方块 y；这个区块里没有地牢时返回 -1
     */
    public static int findMasterY(World world, int x, int z) {
        Block master = LGBlocks.PUZZLE_MASTER;
        for (int y = 14; y <= 125; y++) {
            if (world.getBlock(x, y, z) == master) return y;
        }
        return -1;
    }

    /**
     * 推算<b>地表入口</b>：地牢生成完会从房间南缘沿对角阶梯向上挖穿地表
     * （{@code StructureGenerator} 末尾的 do-while），出口就是玩家在地面上看到的洞口。
     *
     * <p>
     * 阶梯在第 d 步清理 {@code y ∈ [底面+2+d, 底面+4+d]} 的三格 —— 一个斜向上的通道。
     * 「挖穿地表」的判据是通道顶 {@code 底面+4+d} 够到那一列的地表高度，
     * 找到第一个满足的 d 就是出口位置（和原版 do-while 的退出条件一致，
     * 上限同为 15）。只需要查 15 列的地表高度，同样微秒级。
     *
     * @param masterY 已验证的主方块 y
     * @return {@code {x, 站立y, z}}；推不出来时 y/z 为 -1（地牢本身仍然有效）
     */
    public static int[] findEntrance(World world, int x, int z, int masterY) {
        int bottom = masterY - MASTER_TE_OFFSET;

        for (int d = 1; d <= ENTRANCE_MAX_RUN; d++) {
            int entranceZ = z + CENTER_TO_BORDER + d;
            int ground = groundY(world, x, entranceZ);
            if (ground < 0) continue;
            if (bottom + 4 + d >= ground) {
                return new int[] { x, ground + 1, entranceZ };
            }
        }
        return new int[] { x, -1, -1 };
    }

    /** @return 该列最高处的实心方块 y（= 地表高度）；找不到时 -1 */
    private static int groundY(World world, int x, int z) {
        for (int y = 130; y > 10; y--) {
            if (world.getBlock(x, y, z)
                .getMaterial()
                .isSolid()) return y;
        }
        return -1;
    }

    /**
     * 以一个区块为中心、按半径收集全部候选区块（含距离排序）。
     *
     * <p>
     * 默认菱形尺寸 20 时候选密度约 1/533 区块，半径 1000 方块（62 区块）大约
     * 80 个候选 —— 计算本身是纯数学，瞬间完成。
     *
     * @return {@code {chunkX, chunkZ}} 数组，按到中心的距离从近到远
     */
    public static List<int[]> findCandidateChunks(long worldSeed, int dim, int centerChunkX, int centerChunkZ,
        int radiusBlocks) {
        List<int[]> result = new ArrayList<>();
        if (!isWorldGenEnabled(dim)) return result;

        int chunkRadius = MathHelper.floor_double(radiusBlocks / 16.0D);
        long radiusSq = (long) radiusBlocks * radiusBlocks;

        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                // 用方块坐标算距离，圆以外的不要（正方形扫描只是遍历方便）
                int dx = centerCoord(cx) - centerCoord(centerChunkX);
                int dz = centerCoord(cz) - centerCoord(centerChunkZ);
                if ((long) dx * dx + (long) dz * dz > radiusSq) continue;

                if (isCandidateChunk(worldSeed, dim, cx, cz)) {
                    result.add(new int[] { cx, cz });
                }
            }
        }

        result.sort((a, b) -> {
            long da = distSq(a, centerChunkX, centerChunkZ);
            long db = distSq(b, centerChunkX, centerChunkZ);
            return Long.compare(da, db);
        });
        return result;
    }

    private static long distSq(int[] chunk, int centerChunkX, int centerChunkZ) {
        long dx = centerCoord(chunk[0]) - centerCoord(centerChunkX);
        long dz = centerCoord(chunk[1]) - centerCoord(centerChunkZ);
        return dx * dx + dz * dz;
    }

    /** @return 该区块当前是否已在内存里（不会触发生成/读盘） */
    public static boolean isChunkLoaded(WorldServer world, int chunkX, int chunkZ) {
        return world.getChunkProvider()
            .chunkExists(chunkX, chunkZ);
    }
}
