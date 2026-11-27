package com.futa_gtnh.client;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;

import com.futa_gtnh.block.BlockSpaceCenterPart;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 深空采矿指挥中心结构方块的渲染器（ISBRH）。三种 meta 分开画：
 *
 * <ul>
 * <li><b>桁架（meta 0）</b>：满格外壳，直接走原版 {@code renderStandardBlock}；</li>
 * <li><b>全息投影核心（meta 1）</b>：居中一根 4×16×4 的能量柱，贴图是带下扫扫描线的动画条带；
 * 相邻两个全息核心之间自动连出一根 4×4 的横梁，所以中轴是一根通到底的光柱、
 * 顶盖上的「十字」连成一个整体，而不是一串互不相干的方块；</li>
 * <li><b>信号天线阵列（meta 2）</b>：基座 + 桅杆 + 发射头三段造型，发射头贴呼吸灯动画条带、
 * 自发光（不受环境光影响）。</li>
 * </ul>
 *
 * <p>
 * <b>这里只画静态几何。</b>1.7.10 的区块渲染会把 ISBRH 的输出烘焙进显示列表，只有区块重建时
 * 才重新求值——在这里读「当前时间」做旋转只会得到一张静止快照。所以逐帧的动感分两路：
 * 贴图帧动画（扫描线 / 呼吸灯，原版贴图管线驱动，GPU 侧零逐帧开销）和世界末渲染
 * （星环 / 光束 / 全息星球，见 {@link SpaceCenterEffects}）。
 * </p>
 *
 * <p>
 * 贴图是 32×32 的图集（{@code tools/GenSpaceCenterTextures}），这里按区域取用：
 * 全息核心的中间 8 列是能量柱条带、其中段的 8×8 是柱顶与横梁；天线图集里基座 / 桅杆 /
 * 发射头各占一块。
 * </p>
 *
 * <p>
 * 物品栏不画 3D（{@code shouldRender3DInInventory} = false），平面图标即整张图集的第一帧。
 */
@SideOnly(Side.CLIENT)
public class SpaceCenterPartRenderer implements ISimpleBlockRenderingHandler {

    /** 一帧贴图的边长（像素）。 */
    private static final float TEX = 32F;

    // ---- 全息核心图集分区 {u0, v0, u1, v1}（图集内比例）----
    /** 能量柱条带：中间 8 列 × 整列高，带下扫的扫描线。 */
    private static final float[] PILLAR = { 12 / TEX, 0 / TEX, 20 / TEX, 32 / TEX };
    /** 柱顶 / 柱底 / 横梁：条带中段的 8×8。 */
    private static final float[] PILLAR_CAP = { 12 / TEX, 12 / TEX, 20 / TEX, 20 / TEX };

    // ---- 天线图集分区 ----
    private static final float[] PLINTH = { 10 / TEX, 27 / TEX, 22 / TEX, 32 / TEX };
    private static final float[] MAST = { 15 / TEX, 11 / TEX, 17 / TEX, 27 / TEX };
    private static final float[] LAMP = { 12 / TEX, 3 / TEX, 20 / TEX, 11 / TEX };

    @Override
    public void renderInventoryBlock(Block block, int metadata, int modelId, RenderBlocks renderer) {
        // 物品栏 / 合成表里用方块图标的平面贴图（EIO 导管同款做法）
    }

    @Override
    public boolean shouldRender3DInInventory(int modelId) {
        return false;
    }

    @Override
    public int getRenderId() {
        return BlockSpaceCenterPart.renderId;
    }

    @Override
    public boolean renderWorldBlock(IBlockAccess world, int x, int y, int z, Block block, int modelId,
        RenderBlocks renderer) {
        if (!(block instanceof BlockSpaceCenterPart)) return false;
        int meta = world.getBlockMetadata(x, y, z);

        if (meta == BlockSpaceCenterPart.META_TRUSS) {
            // 桁架就是普通满格外壳
            return renderer.renderStandardBlock(block, x, y, z);
        }

        BlockSpaceCenterPart part = (BlockSpaceCenterPart) block;
        Tessellator tessellator = Tessellator.instance;
        tessellator.setColorOpaque_F(1.0F, 1.0F, 1.0F);
        tessellator.addTranslation(x, y, z);
        try {
            if (meta == BlockSpaceCenterPart.META_HOLO_CORE) {
                renderHoloCore(tessellator, part, world, x, y, z);
            } else {
                renderAntenna(tessellator, part, world, x, y, z);
            }
        } finally {
            tessellator.addTranslation(-x, -y, -z);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 全息投影核心
    // ------------------------------------------------------------------

    private static void renderHoloCore(Tessellator t, BlockSpaceCenterPart part, IBlockAccess world, int x, int y,
        int z) {
        float[] pillar = region(part.iconForMeta(BlockSpaceCenterPart.META_HOLO_CORE), PILLAR);
        float[] cap = region(part.iconForMeta(BlockSpaceCenterPart.META_HOLO_CORE), PILLAR_CAP);
        int light = world.getLightBrightnessForSkyBlocks(x, y, z, 0);

        // 能量柱：4×16×4 居中
        box(t, 6, 0, 6, 10, 16, 10, pillar, cap, light);

        // 横梁：只朝「相邻也是全息核心」的方向伸到格子边缘（顶盖十字靠它连成一体）
        if (BlockSpaceCenterPart.partMeta(world, x + 1, y, z) == BlockSpaceCenterPart.META_HOLO_CORE) {
            box(t, 10, 6, 6, 16, 10, 10, cap, cap, light);
        }
        if (BlockSpaceCenterPart.partMeta(world, x - 1, y, z) == BlockSpaceCenterPart.META_HOLO_CORE) {
            box(t, 0, 6, 6, 6, 10, 10, cap, cap, light);
        }
        if (BlockSpaceCenterPart.partMeta(world, x, y, z + 1) == BlockSpaceCenterPart.META_HOLO_CORE) {
            box(t, 6, 6, 10, 10, 10, 16, cap, cap, light);
        }
        if (BlockSpaceCenterPart.partMeta(world, x, y, z - 1) == BlockSpaceCenterPart.META_HOLO_CORE) {
            box(t, 6, 6, 0, 10, 10, 6, cap, cap, light);
        }
    }

    // ------------------------------------------------------------------
    // 信号天线阵列
    // ------------------------------------------------------------------

    private static void renderAntenna(Tessellator t, BlockSpaceCenterPart part, IBlockAccess world, int x, int y,
        int z) {
        IIcon icon = part.iconForMeta(BlockSpaceCenterPart.META_ANTENNA);
        int light = world.getLightBrightnessForSkyBlocks(x, y, z, 0);
        // 发射头自发光：夜里也要像一盏亮着的灯，而不是被环境光压暗的一块贴图
        int glow = 0xF000F0;

        // 基座 10×2×10
        box(t, 3, 0, 3, 13, 2, 13, region(icon, PLINTH), region(icon, PLINTH), light);
        // 桅杆 2×8×2
        box(t, 7, 2, 7, 9, 10, 9, region(icon, MAST), region(icon, MAST), light);
        // 发射头 6×4×6（呼吸灯）
        box(t, 5, 10, 5, 11, 14, 11, region(icon, LAMP), region(icon, LAMP), glow);
        // 顶针 2×2×2
        box(t, 7, 14, 7, 9, 16, 9, region(icon, MAST), region(icon, MAST), glow);
    }

    // ------------------------------------------------------------------
    // 几何工具
    // ------------------------------------------------------------------

    /** 把图集内的比例区域换算成图标的绝对 UV。 */
    private static float[] region(IIcon icon, float[] uv) {
        if (icon == null) return new float[] { 0, 0, 0, 0 };
        float u0 = icon.getMinU(), v0 = icon.getMinV();
        float du = icon.getMaxU() - u0, dv = icon.getMaxV() - v0;
        return new float[] { u0 + du * uv[0], v0 + dv * uv[1], u0 + du * uv[2], v0 + dv * uv[3] };
    }

    /**
     * 画一个长方体（格子内像素坐标，16 = 一格）：侧面贴 {@code side}、顶底贴 {@code top}。
     *
     * <p>
     * 六个面手写顶点，绕序沿用 {@link IoNodeRenderer#drawBox} 那一套（CCW = 正面，
     * 反了会被背面剔除吃掉）；UV 正着贴——世界坐标的下沿对应贴图的下沿。
     */
    private static void box(Tessellator t, int x0, int y0, int z0, int x1, int y1, int z1, float[] side, float[] top,
        int brightness) {
        float ax = x0 / 16F, ay = y0 / 16F, az = z0 / 16F;
        float bx = x1 / 16F, by = y1 / 16F, bz = z1 / 16F;
        t.setBrightness(brightness);
        t.setColorOpaque_F(1F, 1F, 1F);

        float su0 = side[0], sv0 = side[1], su1 = side[2], sv1 = side[3];
        float tu0 = top[0], tv0 = top[1], tu1 = top[2], tv1 = top[3];

        // 顶（+Y）
        v(t, ax, by, az, tu0, tv0);
        v(t, ax, by, bz, tu0, tv1);
        v(t, bx, by, bz, tu1, tv1);
        v(t, bx, by, az, tu1, tv0);

        // 底（-Y）
        v(t, ax, ay, az, tu0, tv0);
        v(t, bx, ay, az, tu1, tv0);
        v(t, bx, ay, bz, tu1, tv1);
        v(t, ax, ay, bz, tu0, tv1);

        // 北（-Z）
        v(t, ax, ay, az, su0, sv1);
        v(t, ax, by, az, su0, sv0);
        v(t, bx, by, az, su1, sv0);
        v(t, bx, ay, az, su1, sv1);

        // 南（+Z）
        v(t, bx, ay, bz, su1, sv1);
        v(t, bx, by, bz, su1, sv0);
        v(t, ax, by, bz, su0, sv0);
        v(t, ax, ay, bz, su0, sv1);

        // 西（-X）
        v(t, ax, ay, bz, su1, sv1);
        v(t, ax, by, bz, su1, sv0);
        v(t, ax, by, az, su0, sv0);
        v(t, ax, ay, az, su0, sv1);

        // 东（+X）
        v(t, bx, ay, az, su0, sv1);
        v(t, bx, by, az, su0, sv0);
        v(t, bx, by, bz, su1, sv0);
        v(t, bx, ay, bz, su1, sv1);
    }

    private static void v(Tessellator t, float x, float y, float z, float u, float v) {
        t.setTextureUV(u, v);
        t.addVertex(x, y, z);
    }
}
