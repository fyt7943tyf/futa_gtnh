package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 从玩家眼睛连到目标方块的那条追踪光线。
 *
 * <p>
 * <b>坐标系提醒（1.7.10 特有）：</b>世界渲染阶段里模型视图矩阵的原点并不在世界原点，
 * 而是在<b>相机</b>上 —— 见 {@code RenderGlobal} 里围绕静态实体显示列表的
 * {@code glTranslated(-d3, -d4, -d5)}，以及 {@code RenderManager.renderEntityStatic}
 * 里手工减掉 {@code renderPosX/Y/Z}。所以这里要画世界坐标，必须自己先
 * {@code glTranslated(-RenderManager.renderPosX, ...)} 抵消掉。
 *
 * <p>
 * 用 {@code RenderManager.renderPos*} 而不是 {@code mc.thePlayer.pos*}：第三人称、
 * 观察者模式、以及玩家开了视角摇晃时，相机位置和玩家位置并不相同。
 *
 * <p>
 * 光线用「两片互相垂直的缎带」而不是 {@code GL_LINES}：线宽在不同显卡上上限差别很大
 * （有些驱动只保证 1.0），而缎带是按顶点画的四边形，粗细一定对。十字交叉则是为了
 * 不管从哪个角度看都能看见 —— 单片缎带在使用者正好侧对光束时会缩成一条缝。
 */
public class LocatorBeamRenderer {

    /** 光束宽度（方块）。 */
    private static final double BEAM_WIDTH = 0.055D;
    /** 目标处光柱的宽度。 */
    private static final double PILLAR_WIDTH = 0.09D;
    /** 目标处光柱的高度。 */
    private static final double PILLAR_HEIGHT = 3.5D;
    /** 沿光束流动的高亮段长度上限（方块）。 */
    private static final double PULSE_LENGTH = 6.0D;
    /** 同时存在几段流动高亮。 */
    private static final int PULSE_COUNT = 3;

    /** 出过异常就闭嘴，避免每帧刷日志把游戏卡死。 */
    private static boolean loggedFailure;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null || mc.theWorld == null) return;

        // 手持或佩戴魔杖时都保持当前追踪光线；取下并切换物品后才隐藏。
        if (!LocatorState.isHoldingWand(player)) return;
        if (!LocatorState.hasBeam()) return;
        // 换维度之后坐标就失效了（搜索只在当前维度做），别把玩家往错的地方引
        if (LocatorState.getResultDimension() != player.dimension) return;

        try {
            render(event.partialTicks, player);
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                FutaGtnhMod.LOG.warn("寻物魔杖：绘制追踪光线失败（后续不再重复报告）", t);
            }
        }
    }

    private void render(float partialTicks, EntityPlayer player) {
        double eyeX = lerp(player.prevPosX, player.posX, partialTicks);
        double eyeY = lerp(player.prevPosY, player.posY, partialTicks) + player.getEyeHeight();
        double eyeZ = lerp(player.prevPosZ, player.posZ, partialTicks);

        double targetX = LocatorState.getPosX() + 0.5D;
        double targetY = LocatorState.getPosY() + 0.5D;
        double targetZ = LocatorState.getPosZ() + 0.5D;

        double dirX = targetX - eyeX;
        double dirY = targetY - eyeY;
        double dirZ = targetZ - eyeZ;
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (length < 0.05D) return; // 已经站在目标方块上了，没什么好指的

        dirX /= length;
        dirY /= length;
        dirZ /= length;

        // 光束的两个宽度方向。第一个由「光束方向 × 世界上方」得到；
        // 光束接近垂直时这个叉积退化，换成拿 X 轴当参考。
        double[] right = new double[3];
        double[] up = new double[3];
        buildBasis(dirX, dirY, dirZ, right, up);

        // 起点从眼前往准星方向推一点，否则近处的缎带会糊在屏幕上
        double startX = eyeX + dirX * 0.7D;
        double startY = eyeY + dirY * 0.7D;
        double startZ = eyeZ + dirZ * 0.7D;

        GL11.glPushMatrix();
        // 见类注释：把模型视图矩阵搬回世界原点
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        // 加色混合：光线叠在方块上会发亮，看起来像「光」而不是一条塑料带
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);

        Tessellator tessellator = Tessellator.instance;

        // --- 主光束 -----------------------------------------------------
        tessellator.startDrawingQuads();
        double[] from = { startX, startY, startZ };
        double[] to = { targetX, targetY, targetZ };
        // 起点几乎透明、终点亮：贴脸的那一段不挡视线
        drawRibbon(tessellator, from, to, right, BEAM_WIDTH, 0.10F, 0.55F, 0.35F, 0.85F, 1.0F);
        drawRibbon(tessellator, from, to, up, BEAM_WIDTH, 0.10F, 0.55F, 0.35F, 0.85F, 1.0F);
        tessellator.draw();

        // --- 流动的高亮段 ------------------------------------------------
        long now = System.currentTimeMillis();
        double pulseLength = Math.min(PULSE_LENGTH, length * 0.2D);
        tessellator.startDrawingQuads();
        for (int i = 0; i < PULSE_COUNT; i++) {
            double t = (now / 1400.0D + (double) i / PULSE_COUNT) % 1.0D;
            double head = t * length;
            double tail = Math.max(0.0D, head - pulseLength);
            if (head <= 0.0D) continue;

            double[] pulseFrom = { eyeX + dirX * tail, eyeY + dirY * tail, eyeZ + dirZ * tail };
            double[] pulseTo = { eyeX + dirX * head, eyeY + dirY * head, eyeZ + dirZ * head };
            double fade = Math.sin(t * Math.PI); // 两端淡入淡出，避免突兀地冒出来
            float alpha = (float) (0.55D * fade);
            if (alpha <= 0.02F) continue;
            drawRibbon(tessellator, pulseFrom, pulseTo, right, BEAM_WIDTH * 1.9D, 0.0F, alpha, 1.0F, 1.0F, 1.0F);
            drawRibbon(tessellator, pulseFrom, pulseTo, up, BEAM_WIDTH * 1.9D, 0.0F, alpha, 1.0F, 1.0F, 1.0F);
        }
        tessellator.draw();

        // --- 目标处的光柱 -------------------------------------------------
        double pillarBottom = LocatorState.getPosY() - 0.5D;
        double[] pillarFrom = { targetX, pillarBottom, targetZ };
        double[] pillarTo = { targetX, pillarBottom + PILLAR_HEIGHT, targetZ };
        // 光柱自己撑起一组基准：竖直方向的「宽度方向」取世界 X/Z 轴
        double[] pillarRight = { 1.0D, 0.0D, 0.0D };
        double[] pillarUp = { 0.0D, 0.0D, 1.0D };
        double breathe = 0.75D + 0.25D * Math.sin(now / 320.0D);
        tessellator.startDrawingQuads();
        drawRibbon(
            tessellator,
            pillarFrom,
            pillarTo,
            pillarRight,
            PILLAR_WIDTH,
            (float) (0.55D * breathe),
            0.0F,
            0.55F,
            1.0F,
            0.85F);
        drawRibbon(
            tessellator,
            pillarFrom,
            pillarTo,
            pillarUp,
            PILLAR_WIDTH,
            (float) (0.55D * breathe),
            0.0F,
            0.55F,
            1.0F,
            0.85F);
        tessellator.draw();

        // --- 把目标方块框出来 ---------------------------------------------
        drawBlockOutline(LocatorState.getPosX(), LocatorState.getPosY(), LocatorState.getPosZ(), (float) breathe);

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /** 目标方块外面那圈线框。用 GL_LINES 就够了 —— 这条线只是标示位置，不需要粗细。 */
    private void drawBlockOutline(int blockX, int blockY, int blockZ, float alpha) {
        double x0 = blockX - 0.002D;
        double y0 = blockY - 0.002D;
        double z0 = blockZ - 0.002D;
        double x1 = blockX + 1.002D;
        double y1 = blockY + 1.002D;
        double z1 = blockZ + 1.002D;

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(1.6F);
        GL11.glColor4f(0.7F, 1.0F, 1.0F, Math.min(1.0F, alpha));

        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_LINES);

        // 上下两个面的四条边
        line(tessellator, x0, y0, z0, x1, y0, z0);
        line(tessellator, x1, y0, z0, x1, y0, z1);
        line(tessellator, x1, y0, z1, x0, y0, z1);
        line(tessellator, x0, y0, z1, x0, y0, z0);

        line(tessellator, x0, y1, z0, x1, y1, z0);
        line(tessellator, x1, y1, z0, x1, y1, z1);
        line(tessellator, x1, y1, z1, x0, y1, z1);
        line(tessellator, x0, y1, z1, x0, y1, z0);

        // 四条竖边
        line(tessellator, x0, y0, z0, x0, y1, z0);
        line(tessellator, x1, y0, z0, x1, y1, z0);
        line(tessellator, x1, y0, z1, x1, y1, z1);
        line(tessellator, x0, y0, z1, x0, y1, z1);

        tessellator.draw();
    }

    private static void line(Tessellator tessellator, double x0, double y0, double z0, double x1, double y1,
        double z1) {
        tessellator.addVertex(x0, y0, z0);
        tessellator.addVertex(x1, y1, z1);
    }

    /**
     * 画一片四边形缎带：以 {@code from -> to} 为轴，沿 {@code widthDir} 撑开 {@code width}。
     *
     * <p>
     * 顶点顺序必须构成不自交的四边形，这里按「起点左、起点右、终点右、终点左」放。
     * 配合上面关掉的背面剔除，从两边看都是实心的。
     */
    private static void drawRibbon(Tessellator tessellator, double[] from, double[] to, double[] widthDir, double width,
        float alphaFrom, float alphaTo, float red, float green, float blue) {
        double rx = widthDir[0] * width;
        double ry = widthDir[1] * width;
        double rz = widthDir[2] * width;

        tessellator.setColorRGBA_F(red, green, blue, alphaFrom);
        tessellator.addVertex(from[0] - rx, from[1] - ry, from[2] - rz);
        tessellator.addVertex(from[0] + rx, from[1] + ry, from[2] + rz);

        tessellator.setColorRGBA_F(red, green, blue, alphaTo);
        tessellator.addVertex(to[0] + rx, to[1] + ry, to[2] + rz);
        tessellator.addVertex(to[0] - rx, to[1] - ry, to[2] - rz);
    }

    /**
     * 由光束方向构造两个互相垂直的宽度方向。
     *
     * <p>
     * {@code right = normalize(dir × (0,1,0))}。当光束几乎竖直时这个叉积的长度趋近 0，
     * 归一化会得到 NaN（顶点变 NaN 之后整片区域会消失，还很难查），所以这时改拿 X 轴当参考。
     */
    private static void buildBasis(double dirX, double dirY, double dirZ, double[] rightOut, double[] upOut) {
        double horizontal = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (horizontal < 1.0E-4D) {
            // 竖直光束：随便取一组正交基
            rightOut[0] = 1.0D;
            rightOut[1] = 0.0D;
            rightOut[2] = 0.0D;
        } else {
            rightOut[0] = -dirZ / horizontal;
            rightOut[1] = 0.0D;
            rightOut[2] = dirX / horizontal;
        }

        // up = dir × right（两个单位向量正交，结果已经是单位长度）
        upOut[0] = dirY * rightOut[2] - dirZ * rightOut[1];
        upOut[1] = dirZ * rightOut[0] - dirX * rightOut[2];
        upOut[2] = dirX * rightOut[1] - dirY * rightOut[0];
    }

    private static double lerp(double from, double to, float partialTicks) {
        return from + (to - from) * partialTicks;
    }
}
