package com.futa_gtnh.client;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.AxisAlignedBB;
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
 * <li><b>全息投影核心（meta 1）</b>：不满格的 10px 发光核心 + 一圈按方块坐标哈希
 * 随机朝向的「星环」；核心贴图是 32 帧动画条（扫描线自上而下滚动），贴图动画由
 * 原版 TextureAtlasSprite 管线驱动，GPU 侧零开销；</li>
 * <li><b>信号天线阵列（meta 2）</b>：基座 + 桅杆 + 顶部发射头三段造型，发射头贴图
 * 用另一张动画条（呼吸灯亮度脉冲）。</li>
 * </ul>
 *
 * <p>
 * <b>为什么动画放在贴图上而不是每帧旋转几何：</b>1.7.10 的区块渲染把 ISBRH 输出
 * 烘焙进显示列表，只有区块重建时才重新求值 —— 在这里读「当前时间」做旋转只会
 * 得到一张静止快照。真正的逐帧动感（星环旋转错觉、扫描线、呼吸灯）拆成两路：
 * 贴图帧动画（本类）+ 运行光柱（{@link SpaceCenterBeamRenderer}，RenderWorldLastEvent）。
 * </p>
 *
 * <p>
 * 物品栏不画 3D（{@code shouldRender3DInInventory} = false），平面图标即动画贴图。
 */
@SideOnly(Side.CLIENT)
public class SpaceCenterPartRenderer implements ISimpleBlockRenderingHandler {

    /** 星环缎带的青色乘子（和光柱同色系）。 */
    private static final float RING_R = 0.45F, RING_G = 0.90F, RING_B = 1.00F;

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
        BlockSpaceCenterPart part = (BlockSpaceCenterPart) block;
        int meta = world.getBlockMetadata(x, y, z);

        if (meta == BlockSpaceCenterPart.META_TRUSS) {
            // 桁架就是普通满格外壳
            return renderer.renderStandardBlock(block, x, y, z);
        }

        Tessellator tessellator = Tessellator.instance;
        tessellator.setColorOpaque_F(1.0F, 1.0F, 1.0F);
        tessellator.addTranslation(x, y, z);
        try {
            tessellator.setBrightness(world.getLightBrightnessForSkyBlocks(x, y, z, 0));
            if (meta == BlockSpaceCenterPart.META_HOLO_CORE) {
                renderHoloCore(tessellator, part, x, y, z);
            } else {
                renderAntenna(tessellator, part);
            }
        } finally {
            tessellator.addTranslation(-x, -y, -z);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 全息投影核心
    // ------------------------------------------------------------------

    private static void renderHoloCore(Tessellator tessellator, BlockSpaceCenterPart part, int x, int y, int z) {
        IIcon icon = part.iconForMeta(BlockSpaceCenterPart.META_HOLO_CORE);

        // 发光核心：10×10×10，居中
        drawBox(tessellator, box(3, 0, 3, 13, 10, 13), icon, 1.0F, 1.0F, 1.0F, 0, 0, 1, 1);

        // 星环：绕 Y 轴的方形缎带，朝向由方块坐标哈希决定（同根柱子上每格不同，
        // 看起来像一组错落的环）。烘焙时固定，旋转感由贴图动画补足。
        float angle = (float) ((posHash(x, y, z) % 360) * Math.PI / 180.0);
        float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
        float outer = 7.2F / 16.0F, inner = 5.4F / 16.0F;
        float y0 = 11.0F / 16.0F, y1 = 12.5F / 16.0F;

        // 方形环的四条边：按旋转后的四个角连四边形
        float[][] corners = { { outer, outer }, { -outer, outer }, { -outer, -outer }, { outer, -outer } };
        float[][] cornersIn = { { inner, inner }, { -inner, inner }, { -inner, -inner }, { inner, -inner } };
        for (int i = 0; i < 4; i++) {
            float[] a = rotate(corners[i], cos, sin);
            float[] b = rotate(corners[(i + 1) % 4], cos, sin);
            float[] a2 = rotate(cornersIn[i], cos, sin);
            float[] b2 = rotate(cornersIn[(i + 1) % 4], cos, sin);
            drawQuad(
                tessellator,
                icon,
                RING_R,
                RING_G,
                RING_B,
                a[0],
                y0,
                a[1],
                b[0],
                y0,
                b[1],
                b2[0],
                y1,
                b2[1],
                a2[0],
                y1,
                a2[1]);
        }
    }

    private static float[] rotate(float[] v, float cos, float sin) {
        return new float[] { v[0] * cos - v[1] * sin, v[0] * sin + v[1] * cos };
    }

    /** 方块坐标的稳定哈希（同一位形下每次区块重建结果一致，不闪烁）。 */
    private static long posHash(int x, int y, int z) {
        long h = x * 3129871L ^ (long) z * 116129781L ^ (long) y;
        h = h * h * 42317861L + h * 11L;
        return (h >> 16) ^ h;
    }

    // ------------------------------------------------------------------
    // 信号天线阵列
    // ------------------------------------------------------------------

    private static void renderAntenna(Tessellator tessellator, BlockSpaceCenterPart part) {
        IIcon icon = part.iconForMeta(BlockSpaceCenterPart.META_ANTENNA);

        // 基座：8×8×3
        drawBox(tessellator, box(4, 0, 4, 12, 3, 12), icon, 0.85F, 0.85F, 0.85F, 0, 0.5F, 0.5F, 1);
        // 桅杆：2×2，从基座顶到 13px
        drawBox(tessellator, box(7, 3, 7, 9, 13, 9), icon, 1.0F, 1.0F, 1.0F, 0.5F, 0, 1, 0.5F);
        // 发射头：6×6×5，贴图右上角的灯板窗口（呼吸灯动画在那格贴图里）
        drawBox(tessellator, box(5, 10, 5, 11, 15, 11), icon, 1.0F, 1.0F, 1.0F, 0.5F, 0.05F, 0.95F, 0.5F);
    }

    // ------------------------------------------------------------------
    // 几何工具
    // ------------------------------------------------------------------

    private static AxisAlignedBB box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return AxisAlignedBB
            .getBoundingBox(minX / 16.0, minY / 16.0, minZ / 16.0, maxX / 16.0, maxY / 16.0, maxZ / 16.0);
    }

    /**
     * 画一个贴 {@code icon}、整体乘 {@code (r,g,b)} 的长方体。UV 取图标的
     * {@code (u0..u1, v0..v1)} 子窗口（比例），再按各面像素尺寸从中裁取 ——
     * 这样天线不同段落可以共用一张贴图的不同区域。
     */
    private static void drawBox(Tessellator tessellator, AxisAlignedBB box, IIcon icon, float r, float g, float b,
        float u0, float v0, float u1, float v1) {
        if (icon == null) return;

        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;

        float iu0 = icon.getMinU(), iv0 = icon.getMinV();
        float du = (icon.getMaxU() - iu0), dv = (icon.getMaxV() - iv0);
        // 窗口内的可用 UV 范围
        float wu0 = iu0 + du * u0, wv0 = iv0 + dv * v0;
        float wdu = du * (u1 - u0), wdv = dv * (v1 - v0);

        // 每个面的两个边长（像素），决定 UV 取多宽
        float xw = (x1 - x0) * 16.0F;
        float yh = (y1 - y0) * 16.0F;
        float zw = (z1 - z0) * 16.0F;

        // 顶（+Y）与底（-Y）
        faceY(tessellator, r, g, b, x0, x1, z0, z1, y1, wu0, wv0, wdu, wdv, xw, zw, true);
        faceY(tessellator, r, g, b, x0, x1, z0, z1, y0, wu0, wv0, wdu, wdv, xw, zw, false);

        // 四个侧面
        faceSide(tessellator, r, g, b, x0, x1, y0, y1, z0, wu0, wv0, wdu, wdv, xw, yh, true);
        faceSide(tessellator, r, g, b, x0, x1, y0, y1, z1, wu0, wv0, wdu, wdv, xw, yh, true);
        faceSide(tessellator, r, g, b, z0, z1, y0, y1, x0, wu0, wv0, wdu, wdv, zw, yh, false);
        faceSide(tessellator, r, g, b, z0, z1, y0, y1, x1, wu0, wv0, wdu, wdv, zw, yh, false);
    }

    private static void faceY(Tessellator t, float r, float g, float b, float x0, float x1, float z0, float z1, float y,
        float wu0, float wv0, float wdu, float wdv, float xw, float zw, boolean up) {
        float u1 = wu0 + wdu * uvFrac(xw), v1 = wv0 + wdv * uvFrac(zw);
        if (up) {
            uv(t, wu0, wv0, r, g, b);
            t.addVertex(x0, y, z0);
            uv(t, wu0, v1, r, g, b);
            t.addVertex(x0, y, z1);
            uv(t, u1, v1, r, g, b);
            t.addVertex(x1, y, z1);
            uv(t, u1, wv0, r, g, b);
            t.addVertex(x1, y, z0);
        } else {
            uv(t, wu0, wv0, r, g, b);
            t.addVertex(x0, y, z0);
            uv(t, u1, wv0, r, g, b);
            t.addVertex(x1, y, z0);
            uv(t, u1, v1, r, g, b);
            t.addVertex(x1, y, z1);
            uv(t, wu0, v1, r, g, b);
            t.addVertex(x0, y, z1);
        }
    }

    private static void faceSide(Tessellator t, float r, float g, float b, float a0, float a1, float y0, float y1,
        float c, float wu0, float wv0, float wdu, float wdv, float aw, float yh, boolean cIsZ) {
        float u1 = wu0 + wdu * uvFrac(aw), v1 = wv0 + wdv * uvFrac(yh);
        // cIsZ = true：c 是 z 坐标（北/南面）；false：c 是 x 坐标（西/东面）
        if (cIsZ) {
            uv(t, wu0, wv0, r, g, b);
            t.addVertex(a0, y0, c);
            uv(t, wu0, v1, r, g, b);
            t.addVertex(a0, y1, c);
            uv(t, u1, v1, r, g, b);
            t.addVertex(a1, y1, c);
            uv(t, u1, wv0, r, g, b);
            t.addVertex(a1, y0, c);
        } else {
            uv(t, wu0, wv0, r, g, b);
            t.addVertex(c, y0, a0);
            uv(t, u1, wv0, r, g, b);
            t.addVertex(c, y0, a1);
            uv(t, u1, v1, r, g, b);
            t.addVertex(c, y1, a1);
            uv(t, wu0, v1, r, g, b);
            t.addVertex(c, y1, a0);
        }
    }

    private static float uvFrac(float pixels) {
        return Math.max(0.0F, Math.min(1.0F, pixels / 16.0F));
    }

    private static void uv(Tessellator t, float u, float v, float r, float g, float b) {
        t.setColorOpaque_F(r, g, b);
        t.setTextureUV(u, v);
    }

    /** 单个四边形（顶点按 CCW=正面绕序），星环用。 */
    private static void drawQuad(Tessellator t, IIcon icon, float r, float g, float b, float ax, float ay, float az,
        float bx, float by, float bz, float cx, float cy, float cz, float dx, float dy, float dz) {
        float um = (icon.getMinU() + icon.getMaxU()) / 2.0F;
        float vm = (icon.getMinV() + icon.getMaxV()) / 2.0F;
        uv(t, um, vm, r, g, b);
        t.addVertex(ax, ay, az);
        t.addVertex(bx, by, bz);
        t.addVertex(cx, cy, cz);
        t.addVertex(dx, dy, dz);
    }
}
