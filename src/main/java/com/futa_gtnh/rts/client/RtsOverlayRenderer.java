package com.futa_gtnh.rts.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 俯瞰模式的世界内渲染（注册在 {@code MinecraftForge.EVENT_BUS} 上）。
 *
 * <p>
 * M1 只画<b>操作范围边界</b>：以玩家（锚点）为中心、±{@code rtsMaxActionRadius}
 * 的方形范围，从允许的最低高度到最高高度。它告诉玩家「哪些地方点了会被服务端
 * 拒绝」—— 相机本身也被钳在这个范围里，所以边界永远在画面边缘附近。
 *
 * <p>
 * 只画线框不画面：半透明的「力场墙」虽然更像原版 mod 的效果，但会挡住
 * 边界附近的建筑本体；线框 + 关深度测试（隔着方块也看得见）信息量一样，
 * 干扰更小。后续版本在同一层里加：悬停高亮、形状幽灵预览。
 */
public class RtsOverlayRenderer {

    /** 出过异常就闭嘴，避免每帧刷日志把游戏卡死（参照 LocatorBeamRenderer）。 */
    private static boolean loggedFailure;

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null || mc.theWorld == null) return;
        if (!RtsClientState.isActive()) return;

        try {
            renderBoundary(player);
            // 悬停高亮与幽灵预览只在俯瞰 HUD 是当前界面时画（远程打开的容器
            // 界面盖在 HUD 上时，光标也在那个界面上）
            if (mc.currentScreen instanceof GuiRtsOverlay) {
                renderHoverHighlight();
                renderGhostPreview();
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                FutaGtnhMod.LOG.warn("俯瞰建筑：世界内渲染失败（后续不再重复报告）", t);
            }
        }
    }

    /**
     * 批量任务的幽灵预览：形状格子半透明着色（建造=蓝、破坏=红），
     * 加上 A/B 角点的线框标记。
     *
     * <p>
     * 预览超大（&gt; 8000 格）时只画 A、B 两点的角标和包围框 —— 在一帧里
     * 画三万个半透明立方体既卡又看不清。
     */
    private void renderGhostPreview() {
        if (!RtsBuildPlanner.hasFirstPoint()) return;

        // 预览色按模式：建造=蓝、破坏=红、互动=灰（互动模式下形状只是选着玩，
        // 回车提交会提示切模式）
        float r, g, b;
        switch (RtsBuildPlanner.getMode()) {
            case DESTROY:
                r = 1.0F;
                g = 0.35F;
                b = 0.3F;
                break;
            case BUILD:
                r = 0.35F;
                g = 0.8F;
                b = 1.0F;
                break;
            default:
                r = 0.6F;
                g = 0.65F;
                b = 0.7F;
                break;
        }

        GL11.glPushMatrix();
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_DEPTH_TEST);

        Tessellator tessellator = Tessellator.instance;

        // A 角标（黄）；B 已选时再画 B 角标（绿）
        int[] a = RtsBuildPlanner.getPointA();
        GL11.glLineWidth(2.0F);
        GL11.glColor4f(1.0F, 0.9F, 0.2F, 0.9F);
        tessellator.startDrawing(GL11.GL_LINES);
        boxOutline(
            tessellator,
            a[0] - 0.002D,
            a[1] - 0.002D,
            a[2] - 0.002D,
            a[0] + 1.002D,
            a[1] + 1.002D,
            a[2] + 1.002D);
        tessellator.draw();

        if (RtsBuildPlanner.isReady()) {
            int[] p = RtsBuildPlanner.getPointB();
            GL11.glColor4f(0.3F, 1.0F, 0.4F, 0.9F);
            tessellator.startDrawing(GL11.GL_LINES);
            boxOutline(
                tessellator,
                p[0] - 0.002D,
                p[1] - 0.002D,
                p[2] - 0.002D,
                p[0] + 1.002D,
                p[1] + 1.002D,
                p[2] + 1.002D);
            tessellator.draw();
        }

        // 形状本体
        java.util.List<int[]> positions = RtsBuildPlanner.getPreview();
        if (positions.isEmpty()) {
            if (RtsBuildPlanner.isReady()) {
                // 降级：只画角点包围框
                int[] p = RtsBuildPlanner.getPointB();
                double x0 = Math.min(a[0], p[0]) - 0.002D, x1 = Math.max(a[0], p[0]) + 1.002D;
                double y0 = Math.min(a[1], p[1]) - 0.002D, y1 = Math.max(a[1], p[1]) + 1.002D;
                double z0 = Math.min(a[2], p[2]) - 0.002D, z1 = Math.max(a[2], p[2]) + 1.002D;
                GL11.glColor4f(r, g, b, 0.6F);
                tessellator.startDrawing(GL11.GL_LINES);
                boxOutline(tessellator, x0, y0, z0, x1, y1, z1);
                tessellator.draw();
            }
        } else {
            GL11.glColor4f(r, g, b, 0.30F);
            tessellator.startDrawingQuads();
            for (int[] pos : positions) {
                solidBox(
                    tessellator,
                    pos[0] + 0.001D,
                    pos[1] + 0.001D,
                    pos[2] + 0.001D,
                    pos[0] + 0.999D,
                    pos[1] + 0.999D,
                    pos[2] + 0.999D);
            }
            tessellator.draw();
        }

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /** 12 条棱的线框盒。 */
    private static void boxOutline(Tessellator tessellator, double x0, double y0, double z0, double x1, double y1,
        double z1) {
        rectangle(tessellator, x0, y0, z0, x1, z1);
        rectangle(tessellator, x0, y1, z0, x1, z1);
        line(tessellator, x0, y0, z0, x0, y1, z0);
        line(tessellator, x1, y0, z0, x1, y1, z0);
        line(tessellator, x1, y0, z1, x1, y1, z1);
        line(tessellator, x0, y0, z1, x0, y1, z1);
    }

    /** 六个面的实心盒（半透明预览用）。顶点绕序配合关掉的背面剔除即可。 */
    private static void solidBox(Tessellator tessellator, double x0, double y0, double z0, double x1, double y1,
        double z1) {
        quad(tessellator, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1); // 底
        quad(tessellator, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0); // 顶
        quad(tessellator, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0); // 北
        quad(tessellator, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1); // 南
        quad(tessellator, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0); // 西
        quad(tessellator, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1); // 东
    }

    private static void quad(Tessellator tessellator, double ax, double ay, double az, double bx, double by, double bz,
        double cx, double cy, double cz, double dx, double dy, double dz) {
        tessellator.addVertex(ax, ay, az);
        tessellator.addVertex(bx, by, bz);
        tessellator.addVertex(cx, cy, cz);
        tessellator.addVertex(dx, dy, dz);
    }

    /**
     * 悬停目标的选中框，颜色按模式区分（1.3.0 恒黄色，「模式切换不明显」
     * 的反馈之一）：互动=黄、建造=蓝、破坏=红；目标超出操作半径时一律
     * 亮红（服务端会拒绝，提前说清楚）。
     */
    private void renderHoverHighlight() {
        RtsPickResult pick = RtsCursorPicker.getLastPick();
        if (pick == null || pick.type == RtsPickResult.Type.MISS) return;

        double x0, y0, z0, x1, y1, z1;
        if (pick.type == RtsPickResult.Type.BLOCK) {
            x0 = pick.blockX - 0.002D;
            y0 = pick.blockY - 0.002D;
            z0 = pick.blockZ - 0.002D;
            x1 = pick.blockX + 1.002D;
            y1 = pick.blockY + 1.002D;
            z1 = pick.blockZ + 1.002D;
        } else if (pick.entity != null) {
            AxisAlignedBB bb = pick.entity.boundingBox;
            x0 = bb.minX - 0.002D;
            y0 = bb.minY - 0.002D;
            z0 = bb.minZ - 0.002D;
            x1 = bb.maxX + 0.002D;
            y1 = bb.maxY + 0.002D;
            z1 = bb.maxZ + 0.002D;
        } else {
            return;
        }

        float r, g, b;
        if (RtsCursorPicker.isBeyondPlayerRange(pick)) {
            r = 1.0F;
            g = 0.2F;
            b = 0.2F;
        } else {
            switch (RtsBuildPlanner.getMode()) {
                case BUILD:
                    r = 0.35F;
                    g = 0.8F;
                    b = 1.0F;
                    break;
                case DESTROY:
                    r = 1.0F;
                    g = 0.35F;
                    b = 0.3F;
                    break;
                default:
                    r = 1.0F;
                    g = 0.9F;
                    b = 0.2F;
                    break;
            }
        }

        GL11.glPushMatrix();
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(false);
        // 关深度测试：隔着方块也能看见选中框（俯瞰时前面常有树/建筑遮挡）
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glLineWidth(2.0F);
        GL11.glColor4f(r, g, b, 0.9F);

        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_LINES);

        rectangle(tessellator, x0, y0, z0, x1, z1);
        rectangle(tessellator, x0, y1, z0, x1, z1);
        line(tessellator, x0, y0, z0, x0, y1, z0);
        line(tessellator, x1, y0, z0, x1, y1, z0);
        line(tessellator, x1, y0, z1, x1, y1, z1);
        line(tessellator, x0, y0, z1, x0, y1, z1);

        tessellator.draw();

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private void renderBoundary(EntityPlayer player) {
        double r = Config.rtsMaxActionRadius;
        double x0 = player.posX - r;
        double x1 = player.posX + r;
        double z0 = player.posZ - r;
        double z1 = player.posZ + r;
        double y0 = player.posY + Config.rtsHeightMinOffset;
        double y1 = player.posY + Config.rtsHeightMaxOffset;

        GL11.glPushMatrix();
        // 世界渲染阶段的模型视图原点在相机上（见 LocatorBeamRenderer 的类注释）
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glLineWidth(1.5F);
        GL11.glColor4f(0.25F, 0.7F, 1.0F, 0.5F);

        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawing(GL11.GL_LINES);

        // 顶面和底面的边框
        rectangle(tessellator, x0, y0, z0, x1, z1);
        rectangle(tessellator, x0, y1, z0, x1, z1);
        // 四条竖棱
        line(tessellator, x0, y0, z0, x0, y1, z0);
        line(tessellator, x1, y0, z0, x1, y1, z0);
        line(tessellator, x1, y0, z1, x1, y1, z1);
        line(tessellator, x0, y0, z1, x0, y1, z1);

        tessellator.draw();

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    private static void rectangle(Tessellator tessellator, double x0, double y, double z0, double x1, double z1) {
        line(tessellator, x0, y, z0, x1, y, z0);
        line(tessellator, x1, y, z0, x1, y, z1);
        line(tessellator, x1, y, z1, x0, y, z1);
        line(tessellator, x0, y, z1, x0, y, z0);
    }

    private static void line(Tessellator tessellator, double x0, double y0, double z0, double x1, double y1,
        double z1) {
        tessellator.addVertex(x0, y0, z0);
        tessellator.addVertex(x1, y1, z1);
    }
}
