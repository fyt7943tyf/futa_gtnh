package com.futa_gtnh.block;

import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * IO 节点方块的几何：中心一个不占满整格的小方块，向「有输入输出能力的邻面」伸出连接臂。
 *
 * <p>
 * 做法（EnderIO 导管那一套）：
 * <ul>
 * <li><b>中心核</b>：6×6×6 像素的小方块，悬在格子正中；</li>
 * <li><b>连接臂</b>：4×4 像素见方，从中心核的边一直伸到格子边缘 —— 只有那一面真的挨着
 * 能搬东西的方块时才存在（{@link TileEntityIoNode} 的连接掩码决定哪几个方向有臂）；</li>
 * <li><b>端帽</b>：贴在格子边缘内侧的 8×8×2 像素盖板，是「这一面接上了」的可视化，
 * 渲染时按该面的模式着色（灰=关、绿=抽入、橙=输出）。</li>
 * </ul>
 *
 * <p>
 * 所有尺寸都按像素（1/16 格）对齐，碰撞箱（服务端）和渲染（客户端）用的是同一份数据，
 * 不会出现「看得见的臂挡不住、看不见的臂撞得到」的错位。
 *
 * <p>
 * 这个类必须在两端都能加载：碰撞箱是服务端逻辑，所以这里不能出现任何客户端类型。
 */
public final class IoNodeGeometry {

    private IoNodeGeometry() {}

    private static final float P = 1.0F / 16.0F;

    /** 中心核：5..11 像素（6px 见方）。 */
    public static final float CORE_MIN = 5 * P;
    public static final float CORE_MAX = 11 * P;

    /** 连接臂截面：6..10 像素（4px 见方）。 */
    private static final float ARM_MIN = 6 * P;
    private static final float ARM_MAX = 10 * P;

    /** 端帽截面：4..12 像素（8px 见方），厚 2px，贴在格子边缘内侧。 */
    private static final float CAP_MIN = 4 * P;
    private static final float CAP_MAX = 12 * P;
    private static final float CAP_THICKNESS = 2 * P;

    /** 中心核的碰撞/点选盒（格子局部坐标）。 */
    public static AxisAlignedBB coreBox() {
        return box(CORE_MIN, CORE_MIN, CORE_MIN, CORE_MAX, CORE_MAX, CORE_MAX);
    }

    /** {@code face} 方向连接臂的碰撞/点选盒；臂从中心核边缘伸到格子边缘。 */
    public static AxisAlignedBB armBox(ForgeDirection face) {
        switch (face) {
            case DOWN:
                return box(ARM_MIN, 0.0F, ARM_MIN, ARM_MAX, CORE_MIN, ARM_MAX);
            case UP:
                return box(ARM_MIN, CORE_MAX, ARM_MIN, ARM_MAX, 1.0F, ARM_MAX);
            case NORTH:
                return box(ARM_MIN, ARM_MIN, 0.0F, ARM_MAX, ARM_MAX, CORE_MIN);
            case SOUTH:
                return box(ARM_MIN, ARM_MIN, CORE_MAX, ARM_MAX, ARM_MAX, 1.0F);
            case WEST:
                return box(0.0F, ARM_MIN, ARM_MIN, CORE_MIN, ARM_MAX, ARM_MAX);
            case EAST:
            default:
                return box(CORE_MAX, ARM_MIN, ARM_MIN, 1.0F, ARM_MAX, ARM_MAX);
        }
    }

    /** {@code face} 方向端帽的碰撞/点选盒，属于该面连接臂的一部分（点它等于点这根臂）。 */
    public static AxisAlignedBB capBox(ForgeDirection face) {
        switch (face) {
            case DOWN:
                return box(CAP_MIN, 0.0F, CAP_MIN, CAP_MAX, CAP_THICKNESS, CAP_MAX);
            case UP:
                return box(CAP_MIN, 1.0F - CAP_THICKNESS, CAP_MIN, CAP_MAX, 1.0F, CAP_MAX);
            case NORTH:
                return box(CAP_MIN, CAP_MIN, 0.0F, CAP_MAX, CAP_MAX, CAP_THICKNESS);
            case SOUTH:
                return box(CAP_MIN, CAP_MIN, 1.0F - CAP_THICKNESS, CAP_MAX, CAP_MAX, 1.0F);
            case WEST:
                return box(0.0F, CAP_MIN, CAP_MIN, CAP_THICKNESS, CAP_MAX, CAP_MAX);
            case EAST:
            default:
                return box(1.0F - CAP_THICKNESS, CAP_MIN, CAP_MIN, 1.0F, CAP_MAX, CAP_MAX);
        }
    }

    private static AxisAlignedBB box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return AxisAlignedBB.getBoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
