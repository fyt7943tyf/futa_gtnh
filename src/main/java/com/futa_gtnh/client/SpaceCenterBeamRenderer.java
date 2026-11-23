package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.spacecenter.MTESpaceMiningCenter;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 深空采矿指挥中心运行时的「上行链路光柱」：从顶盖天线阵列射向天空。
 *
 * <p>
 * 只有控制器 {@code isActive()}（正在跑虚拟机）时才画。逐帧渲染在
 * {@code RenderWorldLastEvent} 里做，不进区块显示列表，机器一停光就没了。
 *
 * <p>
 * <b>坐标系提醒（1.7.10 特有）：</b>世界渲染阶段里模型视图矩阵的原点在相机而不在
 * 世界原点（{@code RenderGlobal} 的 {@code glTranslated(-d3,-d4,-d5)} 与
 * {@code RenderManager.renderEntityStatic} 的手工抵消），所以画世界坐标前必须先
 * {@code glTranslated(-RenderManager.renderPos*)} 搬回世界原点 —— 与
 * {@link LocatorBeamRenderer} 同一条铁律。
 *
 * <p>
 * <b>性能：</b>每 20 tick 才扫一遍已加载方块实体缓存天线锚点（几千次 instanceof，
 * 亚毫秒级），渲染本身每帧只有几条缎带 + 若干脉冲段；扫描或绘制任一异常只报一次。
 */
@SideOnly(Side.CLIENT)
public class SpaceCenterBeamRenderer {

    /** 光柱离天线的可见高度（方块）。 */
    private static final double BEAM_HEIGHT = 14.0D;
    /** 缩著看也醒目的最小宽度。 */
    private static final double BEAM_WIDTH = 0.14D;
    /** 同时上浮的高亮段数。 */
    private static final int PULSE_COUNT = 3;
    /** 锚点缓存刷新间隔（tick）。 */
    private static final int SCAN_INTERVAL = 20;
    /** 只给离玩家这么远以内的机器画光柱。 */
    private static final double MAX_DISTANCE_SQ = 96.0D * 96.0D;

    /** 出过异常就闭嘴，避免每帧刷日志把游戏卡死。 */
    private static boolean loggedFailure;

    /** 上次扫描时的游戏刻。 */
    private long lastScanTick = Long.MIN_VALUE;
    /** 天线锚点（世界坐标，指向天线格上表面中心）。 */
    private final List<double[]> anchors = new ArrayList<>();

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;

        long tick = mc.theWorld.getTotalWorldTime();
        if (tick - lastScanTick >= SCAN_INTERVAL || lastScanTick == Long.MIN_VALUE) {
            lastScanTick = tick;
            try {
                scanAnchors(mc);
            } catch (Throwable t) {
                logOnce(t);
                return;
            }
        }
        if (anchors.isEmpty()) return;

        try {
            render(System.currentTimeMillis());
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    /** 扫描已加载的 GT 方块实体，收集正在运行的指挥中心的天线锚点。 */
    private void scanAnchors(Minecraft mc) {
        anchors.clear();
        double px = mc.thePlayer.posX, py = mc.thePlayer.posY, pz = mc.thePlayer.posZ;
        for (Object o : mc.theWorld.loadedTileEntityList) {
            if (!(o instanceof IGregTechTileEntity)) continue;
            IGregTechTileEntity gte = (IGregTechTileEntity) o;
            if (!gte.isActive() || gte.getMetaTileEntity() == null) continue;
            if (!(gte.getMetaTileEntity() instanceof MTESpaceMiningCenter)) continue;

            double dx = gte.getXCoord() + 0.5D - px;
            double dy = gte.getYCoord() + 0.5D - py;
            double dz = gte.getZCoord() + 0.5D - pz;
            if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE_SQ) continue;

            MTESpaceMiningCenter center = (MTESpaceMiningCenter) gte.getMetaTileEntity();
            anchors.add(center.beamAnchor());
        }
    }

    private void render(long now) {
        GL11.glPushMatrix();
        // 见类注释：把模型视图矩阵搬回世界原点
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        // 不关深度测试：光柱穿墙透视会显得廉价，被塔身挡住的部分就该被挡住
        GL11.glEnable(GL11.GL_BLEND);
        // 加色混合：叠在星空/方块上会发亮，看起来像「光」而不是塑料带
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);

        Tessellator tessellator = Tessellator.instance;
        double breathe = 0.8D + 0.2D * Math.sin(now / 420.0D);

        for (double[] anchor : anchors) {
            double x = anchor[0], y = anchor[1], z = anchor[2];

            // 主光柱：两片互相垂直的缎带（单片侧对时会缩成一条缝）
            tessellator.startDrawingQuads();
            ribbon(
                tessellator,
                x,
                y,
                z,
                x + 1.0D,
                y + BEAM_HEIGHT,
                z,
                BEAM_WIDTH * breathe,
                0.85F,
                0.30F,
                0.35F,
                0.85F,
                1.0F);
            ribbon(
                tessellator,
                x,
                y,
                z,
                x,
                y + BEAM_HEIGHT,
                z + 1.0D,
                BEAM_WIDTH * breathe,
                0.85F,
                0.30F,
                0.35F,
                0.85F,
                1.0F);
            tessellator.draw();

            // 上浮的高亮段：像数据包沿着链路上行
            tessellator.startDrawingQuads();
            for (int i = 0; i < PULSE_COUNT; i++) {
                double t = ((now / 1100.0D) + (double) i / PULSE_COUNT) % 1.0D;
                double head = t * BEAM_HEIGHT;
                double tail = Math.max(0.0D, head - 2.5D);
                if (head <= 0.0D) continue;
                double fade = Math.sin(t * Math.PI);
                float alpha = (float) (0.6D * fade);
                if (alpha <= 0.02F) continue;
                ribbon(
                    tessellator,
                    x,
                    y + tail,
                    z,
                    x + 1.0D,
                    y + head,
                    z,
                    BEAM_WIDTH * 2.0D,
                    alpha,
                    alpha,
                    0.6F,
                    1.0F,
                    1.0F);
                ribbon(
                    tessellator,
                    x,
                    y + tail,
                    z,
                    x,
                    y + head,
                    z + 1.0D,
                    BEAM_WIDTH * 2.0D,
                    alpha,
                    alpha,
                    0.6F,
                    1.0F,
                    1.0F);
            }
            tessellator.draw();
        }

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /**
     * 竖直缎带：{@code from -> to} 为轴（X/Z 分量只用来定宽度方向），沿该方向撑开
     * {@code width}。顶点顺序构成不自交四边形，配合关掉的背面剔除两面都实心。
     */
    private static void ribbon(Tessellator t, double fx, double fy, double fz, double tx, double ty, double tz,
        double width, float alphaBottom, float alphaTop, float r, float g, float b) {
        double horizontal = Math.sqrt((tx - fx) * (tx - fx) + (tz - fz) * (tz - fz));
        double rx, rz;
        if (horizontal < 1.0E-4D) {
            rx = 1.0D;
            rz = 0.0D;
        } else {
            rx = -(tz - fz) / horizontal;
            rz = (tx - fx) / horizontal;
        }
        rx *= width;
        rz *= width;

        t.setColorRGBA_F(r, g, b, alphaBottom);
        t.addVertex(fx - rx, fy, fz - rz);
        t.addVertex(fx + rx, fy, fz + rz);
        t.setColorRGBA_F(r, g, b, alphaTop);
        t.addVertex(tx + rx, ty, tz + rz);
        t.addVertex(tx - rx, ty, tz - rz);
    }

    private static void logOnce(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            FutaGtnhMod.LOG.warn("深空采矿指挥中心：绘制上行链路光柱失败（后续不再重复报告）", t);
        }
    }
}
