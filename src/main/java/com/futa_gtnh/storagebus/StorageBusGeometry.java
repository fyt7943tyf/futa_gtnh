package com.futa_gtnh.storagebus;

import net.minecraft.block.Block;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * 存储总线面板的几何。
 *
 * <p>
 * 尺寸参考 AE2 {@code PartStorageBus#getBoxes}：不是一块等厚平板，而是三层阶梯：
 * <ul>
 * <li>10×10、1 像素厚的底板，贴目标面；</li>
 * <li>12×12、1 像素厚的中层；</li>
 * <li>6×6、2 像素厚的凸起接口，在最外侧。</li>
 * </ul>
 * 整个面板占目标面外 4 像素深，四周留 2 像素边距。
 *
 * <p>
 * 碰撞/点选用三层盒子的并集，渲染器单独画三层；这样点击范围和 AE 部件一样，
 * 覆盖整个面板而不只是中间凸起。
 */
public final class StorageBusGeometry {

    private StorageBusGeometry() {}

    private static final float P = 1.0F / 16.0F;

    /** 面板总深度：4 像素（底板 1 + 中层 1 + 接口 2）。 */
    public static final float THICKNESS = 4.0F * P;

    /** 面板四周留白：2 像素。 */
    private static final float MARGIN = 2.0F * P;
    private static final float INNER_MIN = MARGIN;
    private static final float INNER_MAX = 1.0F - MARGIN;

    /** AE 底板的 10×10 范围（3..13 像素）。 */
    private static final float BASE_MIN = 3.0F * P;
    private static final float BASE_MAX = 13.0F * P;

    /** AE 凸起接口的 6×6 范围（5..11 像素）。 */
    private static final float CORE_MIN = 5.0F * P;
    private static final float CORE_MAX = 11.0F * P;

    /** 面板整体（三层并集）的碰撞/点选盒。 */
    public static AxisAlignedBB plateBox(ForgeDirection target) {
        return layer(target, INNER_MIN, INNER_MAX, 0.0F, 4.0F);
    }

    /** 贴目标面的 10×10×1 底板。 */
    public static AxisAlignedBB baseBox(ForgeDirection target) {
        return layer(target, BASE_MIN, BASE_MAX, 0.0F, 1.0F);
    }

    /** 中间的 12×12×1 层。 */
    public static AxisAlignedBB middleBox(ForgeDirection target) {
        return layer(target, INNER_MIN, INNER_MAX, 1.0F, 2.0F);
    }

    /** 最外侧 6×6×2 的凸起接口。 */
    public static AxisAlignedBB connectorBox(ForgeDirection target) {
        return layer(target, CORE_MIN, CORE_MAX, 2.0F, 4.0F);
    }

    /**
     * 生成一个“朝向目标面”的盒子。
     *
     * @param xyMin    面板在另外两个轴上的最小像素（0..16）
     * @param xyMax    面板在另外两个轴上的最大像素
     * @param depthMin 从目标面算起的起始深度（像素）
     * @param depthMax 从目标面算起的结束深度（像素）
     */
    private static AxisAlignedBB layer(ForgeDirection target, float xyMin, float xyMax, float depthMin,
        float depthMax) {
        ForgeDirection side = target == null || target == ForgeDirection.UNKNOWN ? ForgeDirection.DOWN : target;
        float d0 = depthMin * P;
        float d1 = depthMax * P;
        switch (side) {
            case DOWN:
                return box(xyMin, d0, xyMin, xyMax, d1, xyMax);
            case UP:
                return box(xyMin, 1.0F - d1, xyMin, xyMax, 1.0F - d0, xyMax);
            case NORTH:
                return box(xyMin, xyMin, d0, xyMax, xyMax, d1);
            case SOUTH:
                return box(xyMin, xyMin, 1.0F - d1, xyMax, xyMax, 1.0F - d0);
            case WEST:
                return box(d0, xyMin, xyMin, d1, xyMax, xyMax);
            case EAST:
            default:
                return box(1.0F - d1, xyMin, xyMin, 1.0F - d0, xyMax, xyMax);
        }
    }

    /** 把 {@code block} 的渲染/射线/碰撞边界设成对应方向的薄板。 */
    public static void setBounds(Block block, ForgeDirection target) {
        AxisAlignedBB box = plateBox(target);
        block.setBlockBounds(
            (float) box.minX,
            (float) box.minY,
            (float) box.minZ,
            (float) box.maxX,
            (float) box.maxY,
            (float) box.maxZ);
    }

    /**
     * 物品栏里的 3D 形态：把面板转成一块居中的 4 像素厚板。
     * 直接用目标面的阶梯盒会贴在某一边，物品图标看起来像缺了一半。
     */
    public static void setItemBounds(Block block) {
        block.setBlockBounds(INNER_MIN, 6.0F * P, INNER_MIN, INNER_MAX, 10.0F * P, INNER_MAX);
    }

    public static AxisAlignedBB worldBox(ForgeDirection target, int x, int y, int z) {
        return plateBox(target).getOffsetBoundingBox(x, y, z);
    }

    private static AxisAlignedBB box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return AxisAlignedBB.getBoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
