package com.futa_gtnh.spacecenter;

import java.util.ArrayList;
import java.util.List;

import com.futa_gtnh.block.BlockSpaceCenterPart;

/**
 * 从方块内容里摸出一台指挥中心的几何锚点：客户端特效（{@code SpaceCenterEffects}）用它定位
 * 全息核心、顶盖和天线。
 *
 * <p>
 * <b>为什么要摸而不是算：</b>客户端的 MTE 没有成型的层数与朝向（{@code mMachine}、{@code height}
 * 都不同步），所以锚点只能从世界里已经摆出来的方块反推。
 *
 * <p>
 * <b>几何（与 {@link SpaceCenterStructure} 的形状一致，回归检查会拿真实形状喂进来核对）：</b>
 * 控制器在底座<b>前缘</b>正中，结构是 7×7，所以结构的水平中心是控制器往后 3 格——
 * 各中间层的全息核心、顶盖的中心天线都在这一列上，而控制器自己那一列往上是各层的桁架。
 * 从控制器正上方去找全息核心是找不到的，只会把第一层中间层误当成顶盖。
 *
 * <p>
 * 本类不依赖任何客户端类：「某处是什么形态的结构方块」由调用方以一个闭包提供
 * （生产里就是 {@link BlockSpaceCenterPart#partMeta}），回归检查因此能用纯内存网格核对几何。
 */
public final class SpaceCenterGeometry {

    /**
     * 查某个位置是什么形态的结构方块；约定 -1 = 不是本方块组。
     *
     * <p>
     * 独立成接口是为了让回归检查能喂纯内存网格，不用造真的 {@code IBlockAccess}。
     */
    public interface PartMetaLookup {

        int metaAt(int x, int y, int z);
    }

    /** 一台指挥中心的锚点（世界坐标）。 */
    public static final class Anchors {

        /** 背向控制器的水平方向（单位向量）。 */
        public int backX, backZ;
        /** 结构的水平中心（控制器往后 3 格）。 */
        public int centerX, centerZ;
        /** 顶盖所在的 y。 */
        public int topY;
        /** 全息核心位置：各中间层正中的那一列 + 顶盖内圈的一环。 */
        public final List<int[]> holo = new ArrayList<>();
        /** 信号天线位置：顶盖正中 + 外围一圈。 */
        public final List<int[]> antennas = new ArrayList<>();
    }

    private static final int CENTER_OFFSET = SpaceCenterStructure.WIDTH / 2;
    private static final int[][] HORIZONTAL = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };

    private SpaceCenterGeometry() {}

    /**
     * @param x, y, z 控制器的坐标
     * @return 锚点；结构不完整（找不到背向、底座中心的全息柱或顶盖中心天线）时 null
     */
    public static Anchors scan(PartMetaLookup metaAt, int x, int y, int z) {
        // 1) 背向控制器的方向。从控制器沿「往后」走 3 格到底座正中，它和左右相邻的两格
        // 永远都是桁架（只有四边能贴仓/口）。只看单独一格不够：侧向 3 格外那一格是底座边上的
        // 「仓/口或桁架」，玩家拿桁架填上就一样是本方块组的方块——所以必须三格连判。
        int backX = 0, backZ = 0;
        boolean found = false;
        for (int[] dir : HORIZONTAL) {
            int sideX = -dir[1], sideZ = dir[0];
            int cx = x + CENTER_OFFSET * dir[0], cz = z + CENTER_OFFSET * dir[1];
            if (isTruss(metaAt, cx, y, cz) && isTruss(metaAt, cx + sideX, y, cz + sideZ)
                && isTruss(metaAt, cx - sideX, y, cz - sideZ)) {
                backX = dir[0];
                backZ = dir[1];
                found = true;
                break;
            }
        }
        if (!found) return null;

        Anchors anchors = new Anchors();
        anchors.backX = backX;
        anchors.backZ = backZ;
        int centerX = x + CENTER_OFFSET * backX, centerZ = z + CENTER_OFFSET * backZ;
        anchors.centerX = centerX;
        anchors.centerZ = centerZ;

        // 2) 顺着中心列往上：每一层的全息核心，一直到顶盖的中心天线
        int level = y + 1;
        while (metaAt.metaAt(centerX, level, centerZ) == BlockSpaceCenterPart.META_HOLO_CORE) {
            anchors.holo.add(new int[] { centerX, level, centerZ });
            level++;
        }
        if (metaAt.metaAt(centerX, level, centerZ) != BlockSpaceCenterPart.META_ANTENNA) return null;
        anchors.topY = level;

        // 3) 顶盖那一层：内圈的全息核心 + 中心与外圈的天线
        int sideX = -backZ, sideZ = backX;
        for (int back = 0; back < SpaceCenterStructure.WIDTH; back++) {
            for (int side = -CENTER_OFFSET; side <= CENTER_OFFSET; side++) {
                int wx = x + backX * back + sideX * side;
                int wz = z + backZ * back + sideZ * side;
                int meta = metaAt.metaAt(wx, anchors.topY, wz);
                if (meta == BlockSpaceCenterPart.META_ANTENNA) {
                    anchors.antennas.add(new int[] { wx, anchors.topY, wz });
                } else if (meta == BlockSpaceCenterPart.META_HOLO_CORE) {
                    anchors.holo.add(new int[] { wx, anchors.topY, wz });
                }
            }
        }
        return anchors;
    }

    private static boolean isTruss(PartMetaLookup metaAt, int x, int y, int z) {
        return metaAt.metaAt(x, y, z) == BlockSpaceCenterPart.META_TRUSS;
    }
}
