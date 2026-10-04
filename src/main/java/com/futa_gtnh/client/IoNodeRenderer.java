package com.futa_gtnh.client;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.block.BlockIoNode;
import com.futa_gtnh.block.IoNodeGeometry;
import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.block.TileEntityIoNode;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * IO 节点方块的渲染器（ISBRH，世界渲染期直接写 Tessellator）。
 *
 * <p>
 * 画三样东西（几何都在 {@link IoNodeGeometry}，和碰撞箱共用）：
 * <ul>
 * <li><b>中心核</b>：6×6×6 像素的小方块，永远画；</li>
 * <li><b>连接臂</b>：只画「那一面真的接着容器」的方向，从核伸到格子边缘；</li>
 * <li><b>端帽</b>：贴在格子边缘的 8×8 像素盖板，<b>按该面的模式着色</b> ——
 * 灰=关、绿=抽入、橙=输出、黄=双向。物品和流体同时接在同一面时端帽对半分，
 * 两半各按各的颜色。于是「放下来只是个带灰色连接的模型」这句话在世界里看得见。</li>
 * </ul>
 *
 * <p>
 * 渲染状态（哪几面有连接、每面的模式）由方块实体通过描述包同步过来
 * （{@link TileEntityIoNode#getDescriptionPacket()}），这里只读不写。
 * 物品栏里不画 3D（EIO 导管的做法）：{@code shouldRender3DInInventory} 返回
 * false，物品直接用方块图标的平面贴图。
 */
@SideOnly(Side.CLIENT)
public class IoNodeRenderer implements ISimpleBlockRenderingHandler {

    /** 端帽的各模式颜色（关=灰、抽入=绿、输出=橙、双向=黄）。 */
    private static final float[][] MODE_COLORS = { { 0.55F, 0.55F, 0.55F }, // OFF
        { 0.25F, 0.85F, 0.35F }, // PULL
        { 1.00F, 0.55F, 0.15F }, // PUSH
        { 0.95F, 0.85F, 0.25F }, // PUSH_PULL
    };

    @Override
    public void renderInventoryBlock(Block block, int metadata, int modelId, RenderBlocks renderer) {
        // 不画 3D：物品栏 / 合成表里用方块图标的平面贴图（EIO 导管同款做法）
    }

    @Override
    public boolean shouldRender3DInInventory(int modelId) {
        return false;
    }

    @Override
    public int getRenderId() {
        return BlockIoNode.renderId;
    }

    @Override
    public boolean renderWorldBlock(IBlockAccess world, int x, int y, int z, Block block, int modelId,
        RenderBlocks renderer) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityIoNode)) return false;
        if (!(block instanceof BlockIoNode)) return false;
        TileEntityIoNode node = (TileEntityIoNode) tile;
        BlockIoNode ioBlock = (BlockIoNode) block;

        Tessellator tessellator = Tessellator.instance;
        tessellator.setColorOpaque_F(1.0F, 1.0F, 1.0F);
        tessellator.addTranslation(x, y, z);
        tessellator.setBrightness(world.getLightBrightnessForSkyBlocks(x, y, z, 0));

        try {
            // 中心核：永远在
            drawBox(tessellator, IoNodeGeometry.coreBox(), ioBlock.getCoreIcon(), 1.0F, 1.0F, 1.0F);

            // 连接臂 + 端帽：只画真接了容器的方向
            for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
                boolean items = node.hasItemConnection(face);
                boolean fluids = node.hasFluidConnection(face);
                if (!items && !fluids) continue;

                drawBox(tessellator, IoNodeGeometry.armBox(face), ioBlock.getArmIcon(), 1.0F, 1.0F, 1.0F);
                drawCap(tessellator, node, face, ioBlock.getCapIcon(), items, fluids);
            }
        } finally {
            tessellator.addTranslation(-x, -y, -z);
        }
        return true;
    }

    /**
     * 端帽：只接了一种就整块按那种的颜色；两种都接就对半分
     * （物品一半 + 流体一半），两半各按各的模式着色。
     */
    private void drawCap(Tessellator tessellator, TileEntityIoNode node, ForgeDirection face, IIcon icon, boolean items,
        boolean fluids) {
        TerminalIoConfig config = node.getIo();
        float[] itemColor = colorFor(config.getMode(face, false));
        float[] fluidColor = colorFor(config.getMode(face, true));

        if (items && !fluids) {
            drawBox(tessellator, IoNodeGeometry.capBox(face), icon, itemColor[0], itemColor[1], itemColor[2]);
            return;
        }
        if (fluids && !items) {
            drawBox(tessellator, IoNodeGeometry.capBox(face), icon, fluidColor[0], fluidColor[1], fluidColor[2]);
            return;
        }

        // 两种都接：沿「面朝向之外的第一个水平/垂直轴」对半分
        AxisAlignedBB cap = IoNodeGeometry.capBox(face);
        double[] min = { cap.minX, cap.minY, cap.minZ };
        double[] max = { cap.maxX, cap.maxY, cap.maxZ };
        int axis = splitAxis(face);
        double mid = (min[axis] + max[axis]) / 2.0;

        double[] halfMax = max.clone();
        halfMax[axis] = mid;
        drawBox(tessellator, box(min, halfMax), icon, itemColor[0], itemColor[1], itemColor[2]);

        double[] halfMin = min.clone();
        halfMin[axis] = mid;
        drawBox(tessellator, box(halfMin, max), icon, fluidColor[0], fluidColor[1], fluidColor[2]);
    }

    private static int splitAxis(ForgeDirection face) {
        switch (face) {
            case UP:
            case DOWN:
                return 0; // 上下帽沿 X 分
            case EAST:
            case WEST:
                return 2; // 东西帽沿 Z 分
            default:
                return 1; // 南北帽沿 Y 分（上下两半）
        }
    }

    private static float[] colorFor(TerminalIoConfig.Mode mode) {
        int index = mode == null ? 0 : mode.ordinal();
        return MODE_COLORS[Math.max(0, Math.min(MODE_COLORS.length - 1, index))];
    }

    private static AxisAlignedBB box(double[] min, double[] max) {
        return AxisAlignedBB.getBoundingBox(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    /**
     * 画一个贴了 {@code icon}、整体乘 {@code (r,g,b)} 的长方体（格子局部坐标）。
     *
     * <p>
     * 六个面手写顶点（绕序按 CCW=正面，不然会被背面剔除吃掉），UV 按面的
     * 像素尺寸从图标的左上角按比例取 —— 贴图本身就是按 16px 画的，
     * 6px 的面取 6/16 宽，1:1 不拉伸。
     */
    private static void drawBox(Tessellator tessellator, AxisAlignedBB box, IIcon icon, float r, float g, float b) {
        if (icon == null) return;

        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;

        float u0 = icon.getMinU(), u1 = icon.getMaxU();
        float v0 = icon.getMinV(), v1 = icon.getMaxV();
        float du = u1 - u0, dv = v1 - v0;

        // 每个面的两个边长（像素），决定 UV 取多宽
        float xw = (x1 - x0) * 16.0F, yh = (y1 - y0) * 16.0F, zw = (z1 - z0) * 16.0F;

        // 顶（+Y）
        uv(tessellator, u0, v0, r, g, b);
        tessellator.addVertex(x0, y1, z0);
        uv(tessellator, u0, v0 + dv * clamp(zw), r, g, b);
        tessellator.addVertex(x0, y1, z1);
        uv(tessellator, u0 + du * clamp(xw), v0 + dv * clamp(zw), r, g, b);
        tessellator.addVertex(x1, y1, z1);
        uv(tessellator, u0 + du * clamp(xw), v0, r, g, b);
        tessellator.addVertex(x1, y1, z0);

        // 底（-Y）
        uv(tessellator, u0, v0, r, g, b);
        tessellator.addVertex(x0, y0, z0);
        uv(tessellator, u0 + du * clamp(xw), v0, r, g, b);
        tessellator.addVertex(x1, y0, z0);
        uv(tessellator, u0 + du * clamp(xw), v0 + dv * clamp(zw), r, g, b);
        tessellator.addVertex(x1, y0, z1);
        uv(tessellator, u0, v0 + dv * clamp(zw), r, g, b);
        tessellator.addVertex(x0, y0, z1);

        // 北（-Z）
        uv(tessellator, u0, v0, r, g, b);
        tessellator.addVertex(x0, y0, z0);
        uv(tessellator, u0, v0 + dv * clamp(yh), r, g, b);
        tessellator.addVertex(x0, y1, z0);
        uv(tessellator, u0 + du * clamp(xw), v0 + dv * clamp(yh), r, g, b);
        tessellator.addVertex(x1, y1, z0);
        uv(tessellator, u0 + du * clamp(xw), v0, r, g, b);
        tessellator.addVertex(x1, y0, z0);

        // 南（+Z）
        uv(tessellator, u0, v0, r, g, b);
        tessellator.addVertex(x1, y0, z1);
        uv(tessellator, u0, v0 + dv * clamp(yh), r, g, b);
        tessellator.addVertex(x1, y1, z1);
        uv(tessellator, u0 + du * clamp(xw), v0 + dv * clamp(yh), r, g, b);
        tessellator.addVertex(x0, y1, z1);
        uv(tessellator, u0 + du * clamp(xw), v0, r, g, b);
        tessellator.addVertex(x0, y0, z1);

        // 西（-X）
        uv(tessellator, u0, v0, r, g, b);
        tessellator.addVertex(x0, y0, z1);
        uv(tessellator, u0, v0 + dv * clamp(yh), r, g, b);
        tessellator.addVertex(x0, y1, z1);
        uv(tessellator, u0 + du * clamp(zw), v0 + dv * clamp(yh), r, g, b);
        tessellator.addVertex(x0, y1, z0);
        uv(tessellator, u0 + du * clamp(zw), v0, r, g, b);
        tessellator.addVertex(x0, y0, z0);

        // 东（+X）
        uv(tessellator, u0, v0, r, g, b);
        tessellator.addVertex(x1, y0, z0);
        uv(tessellator, u0, v0 + dv * clamp(yh), r, g, b);
        tessellator.addVertex(x1, y1, z0);
        uv(tessellator, u0 + du * clamp(zw), v0 + dv * clamp(yh), r, g, b);
        tessellator.addVertex(x1, y1, z1);
        uv(tessellator, u0 + du * clamp(zw), v0, r, g, b);
        tessellator.addVertex(x1, y0, z1);
    }

    private static float clamp(float pixels) {
        return Math.max(0.0F, Math.min(1.0F, pixels / 16.0F));
    }

    private static void uv(Tessellator tessellator, float u, float v, float r, float g, float b) {
        tessellator.setColorOpaque_F(r, g, b);
        tessellator.setTextureUV(u, v);
    }
}
