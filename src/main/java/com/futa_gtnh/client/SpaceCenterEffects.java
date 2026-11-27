package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.BlockSpaceCenterPart;
import com.futa_gtnh.spacecenter.MTESpaceMiningCenter;
import com.futa_gtnh.spacecenter.PlanetIconWidget;
import com.futa_gtnh.spacecenter.SpaceCenterGeometry;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 深空采矿指挥中心的逐帧特效，全部在 {@code RenderWorldLastEvent} 里画，不进区块显示列表：
 *
 * <ul>
 * <li><b>星环</b>：每个全息投影核心外面两道倾角相反的轨道，亮头拖着彗尾沿轨道跑——
 * 均匀的环绕自己的轴转是看不出来的，动感的来源是亮度沿轨道流动；</li>
 * <li><b>上行链路</b>：顶盖每根天线射出一道细光并带着错相的上行脉冲，只在机器运行时出现；</li>
 * <li><b>全息星球</b>：从顶盖中心天线张开的投影锥托着一颗缓缓旋转的星球，
 * 显示的是这台机器当前在开采的那一颗（图标槽位由服务端经 GT 的 custom data 同步）。</li>
 * </ul>
 *
 * <p>
 * <b>坐标系提醒（1.7.10 特有）：</b>世界渲染阶段里模型视图矩阵的原点在相机而不在
 * 世界原点（{@code RenderGlobal} 的 {@code glTranslated(-d3,-d4,-d5)} 与
 * {@code RenderManager.renderEntityStatic} 的手工抵消），所以画世界坐标前必须先
 * {@code glTranslated(-RenderManager.renderPos*)} 搬回世界原点 —— 与
 * {@link LocatorBeamRenderer} 同一条铁律。反过来说，{@code renderPos} 本身就是相机的世界坐标，
 * 朝向相机的缎带（星环每一段）要靠它算宽度方向。
 *
 * <p>
 * <b>锚点不靠同步、靠扫描。</b>客户端的 MTE 没有成型的层数与朝向（{@code mMachine}、
 * {@code height} 都不同步），所以直接摸方块：背向控制器的方向、结构中心那一列
 * （控制器往后 3 格）上的全息柱、顶盖内圈的全息核心环与天线——这套几何在
 * {@link SpaceCenterGeometry}（两端共用的纯逻辑，回归检查拿真实形状核对过），
 * 这里只负责喂 {@code partMeta} 闭包和拿结果。每 {@value #SCAN_INTERVAL} tick
 * 扫一次（一台机器不到一百次方块查询），其余帧只画。
 *
 * <p>
 * <b>性能：</b>加色混合、不写深度、不做光照；星环与全息星球只在 {@value #DETAIL_DISTANCE} 格内画，
 * 天线束到 {@value #SCAN_DISTANCE} 格。扫描或绘制任一异常只报一次。
 */
@SideOnly(Side.CLIENT)
public class SpaceCenterEffects {

    private static final ResourceLocation PLANETS = new ResourceLocation(FutaGtnhMod.MODID, "textures/gui/planets.png");

    private static final int SCAN_INTERVAL = 20;
    private static final double SCAN_DISTANCE = 128D;
    private static final double DETAIL_DISTANCE = 64D;
    private static final double PARTICLE_DISTANCE = 48D;

    /** 轨道分段数。 */
    private static final int ORBIT_SEGMENTS = 28;
    /** 轨道缎带的半宽（方块）。 */
    private static final double ORBIT_WIDTH = 0.022D;
    /** 轨道倾角（绕水平轴），环 A 正倾、环 B 反倾。 */
    private static final double TILT_SIN = Math.sin(Math.toRadians(24D));
    private static final double TILT_COS = Math.cos(Math.toRadians(24D));

    private static final double ANTENNA_BEAM_HEIGHT = 6D;
    /** 全息星球底边离顶盖上表面的高度。 */
    private static final double HOLO_HEIGHT = 2.2D;
    private static final double HOLO_SIZE = 1.5D;

    /** 出过异常就闭嘴，避免每帧刷日志把游戏卡死。 */
    private static boolean loggedFailure;

    /** 一台指挥中心在客户端的几何锚点。 */
    private static final class Rig {

        final int x, y, z;
        final boolean active;
        /** 全息星球图标槽位；-1 = 不投影。 */
        final int iconSlot;
        /** 结构的水平中心（控制器往后 3 格；世界坐标）；{@link #hasTop} 为 false 时无意义。 */
        int centerX, centerZ;
        int topY;
        boolean hasTop;
        /** 全息核心：各中间层正中的那一列 + 顶盖内圈的一环。 */
        final List<int[]> holo = new ArrayList<>();
        final List<int[]> antennas = new ArrayList<>();
        double distanceSq;

        Rig(int x, int y, int z, boolean active, int iconSlot) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.active = active;
            this.iconSlot = iconSlot;
        }
    }

    private long lastScanTick = Long.MIN_VALUE;
    private final List<Rig> rigs = new ArrayList<>();

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;

        long tick = mc.theWorld.getTotalWorldTime();
        // tick 比上次还小说明换了世界（计数器从头开始），缓存必须作废
        if (tick < lastScanTick || tick - lastScanTick >= SCAN_INTERVAL) {
            lastScanTick = tick;
            try {
                scan(mc);
            } catch (Throwable t) {
                logOnce(t);
                return;
            }
        }
        if (rigs.isEmpty()) return;

        try {
            render();
        } catch (Throwable t) {
            logOnce(t);
        }
    }

    // ------------------------------------------------------------------
    // 扫描：从控制器摸出整台机器的锚点
    // ------------------------------------------------------------------

    private void scan(Minecraft mc) {
        rigs.clear();
        double px = mc.thePlayer.posX, py = mc.thePlayer.posY, pz = mc.thePlayer.posZ;
        double scanSq = SCAN_DISTANCE * SCAN_DISTANCE;
        for (Object o : mc.theWorld.loadedTileEntityList) {
            if (!(o instanceof IGregTechTileEntity gte)) continue;
            if (!(gte.getMetaTileEntity() instanceof MTESpaceMiningCenter center)) continue;
            double dx = gte.getXCoord() + 0.5D - px;
            double dy = gte.getYCoord() + 0.5D - py;
            double dz = gte.getZCoord() + 0.5D - pz;
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > scanSq) continue;

            Rig rig = scanRig(
                mc.theWorld,
                gte.getXCoord(),
                gte.getYCoord(),
                gte.getZCoord(),
                gte.isActive(),
                center.clientHoloIconSlot());
            if (rig == null) continue;
            rig.distanceSq = distanceSq;
            rigs.add(rig);
            if (rig.active && rig.hasTop && distanceSq <= PARTICLE_DISTANCE * PARTICLE_DISTANCE) {
                spawnParticles(mc.theWorld, rig);
            }
        }
    }

    /**
     * @return 结构不完整（找不到底座中心的全息柱与顶盖）时 null
     */
    private static Rig scanRig(World world, int x, int y, int z, boolean active, int iconSlot) {
        // 几何全在 SpaceCenterGeometry：从底座找出背向控制器的方向，再顺结构中心那一列
        // （控制器往后 3 格）往上找全息柱与顶盖——不是在控制器正上方（那一列往上是桁架）
        SpaceCenterGeometry.Anchors anchors = SpaceCenterGeometry
            .scan((wx, wy, wz) -> BlockSpaceCenterPart.partMeta(world, wx, wy, wz), x, y, z);
        if (anchors == null) return null;

        Rig rig = new Rig(x, y, z, active, iconSlot);
        rig.centerX = anchors.centerX;
        rig.centerZ = anchors.centerZ;
        rig.topY = anchors.topY;
        rig.hasTop = true;
        rig.holo.addAll(anchors.holo);
        rig.antennas.addAll(anchors.antennas);
        return rig;
    }

    private static void spawnParticles(World world, Rig rig) {
        Random random = world.rand;
        double hx = rig.centerX + 0.5D;
        double hy = rig.topY + 1D + HOLO_HEIGHT + HOLO_SIZE * 0.5D;
        double hz = rig.centerZ + 0.5D;
        // 全息星球周围飘的微光：贴着球面随机冒出来、慢慢上浮
        for (int i = 0; i < 2; i++) {
            double theta = random.nextDouble() * Math.PI * 2.0D;
            double radius = HOLO_SIZE * 0.42D + random.nextDouble() * 0.18D;
            world.spawnParticle(
                "magicCrit",
                hx + Math.cos(theta) * radius,
                hy + (random.nextDouble() - 0.5D) * HOLO_SIZE * 0.8D,
                hz + Math.sin(theta) * radius,
                0.0D,
                0.02D + random.nextDouble() * 0.03D,
                0.0D);
        }
        if (!rig.antennas.isEmpty()) {
            int[] antenna = rig.antennas.get(random.nextInt(rig.antennas.size()));
            world.spawnParticle(
                "portal",
                antenna[0] + 0.5D,
                antenna[1] + 1.05D,
                antenna[2] + 0.5D,
                0.0D,
                0.05D + random.nextDouble() * 0.08D,
                0.0D);
        }
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    private void render() {
        double time = System.currentTimeMillis() / 1000.0D;
        Tessellator tessellator = Tessellator.instance;

        GL11.glPushMatrix();
        // 见类注释：把模型视图矩阵搬回世界原点
        GL11.glTranslated(-RenderManager.renderPosX, -RenderManager.renderPosY, -RenderManager.renderPosZ);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_CULL_FACE);
        // 不关深度测试：光被地形挡住的部分就该看不见，穿墙反而显得廉价
        GL11.glEnable(GL11.GL_BLEND);
        // 加色混合：叠在星空/方块上会发亮，看起来像「光」而不是塑料带
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        GL11.glDepthMask(false);

        double camX = RenderManager.renderPosX, camY = RenderManager.renderPosY, camZ = RenderManager.renderPosZ;
        double detailSq = DETAIL_DISTANCE * DETAIL_DISTANCE;
        for (Rig rig : rigs) {
            boolean near = rig.distanceSq <= detailSq;
            if (near) drawOrbits(tessellator, rig, time, camX, camY, camZ);
            if (rig.active && rig.hasTop) {
                drawUplink(tessellator, rig, time);
                if (near && rig.iconSlot >= 0) drawHologram(tessellator, rig, time);
            }
        }

        GL11.glPopAttrib();
        GL11.glPopMatrix();
    }

    /** 每个全息核心外面两道反向倾斜的轨道；机器停着时只剩很暗的一圈。 */
    private static void drawOrbits(Tessellator t, Rig rig, double time, double camX, double camY, double camZ) {
        float base = rig.active ? 0.10F : 0.04F;
        float comet = rig.active ? 0.80F : 0.28F;
        // 转速是「圈 / 秒」：环 A 正转，环 B 反转
        double phaseA = frac(time * 0.42D);
        double phaseB = frac(-time * 0.33D);
        t.startDrawingQuads();
        for (int[] pos : rig.holo) {
            double cx = pos[0] + 0.5D, cy = pos[1] + 0.5D, cz = pos[2] + 0.5D;
            // 环 A：绕 X 轴倾斜（青色）
            orbit(
                t,
                cx,
                cy,
                cz,
                camX,
                camY,
                camZ,
                0.46D,
                1D,
                0D,
                0D,
                0D,
                TILT_SIN,
                TILT_COS,
                phaseA,
                base,
                comet,
                0.35F,
                0.90F,
                1.00F);
            // 环 B：绕 Z 轴反倾（品红）
            orbit(
                t,
                cx,
                cy,
                cz,
                camX,
                camY,
                camZ,
                0.40D,
                0D,
                0D,
                1D,
                -TILT_SIN,
                TILT_COS,
                0D,
                phaseB,
                base,
                comet,
                0.80F,
                0.45F,
                1.00F);
        }
        t.draw();
    }

    /** 天线束 + 中央投影锥。 */
    private static void drawUplink(Tessellator t, Rig rig, double time) {
        double bx = rig.centerX + 0.5D, by = rig.topY + 1D, bz = rig.centerZ + 0.5D;
        float breathe = 0.75F + 0.25F * (float) Math.sin(time * 2.2D);
        t.startDrawingQuads();

        int index = 0;
        for (int[] antenna : rig.antennas) {
            double ax = antenna[0] + 0.5D, az = antenna[2] + 0.5D, top = antenna[1] + 1D;
            beam(
                t,
                ax,
                top,
                az,
                ax,
                top + ANTENNA_BEAM_HEIGHT,
                az,
                0.035D,
                0.012D,
                0.45F * breathe,
                0F,
                0.60F,
                1.00F,
                1.00F);
            // 上行脉冲：各根天线错开相位，像一串错落上行的小包
            double phase = frac(time * 0.55D + index * 0.38D);
            double head = phase * ANTENNA_BEAM_HEIGHT;
            double tail = Math.max(0D, head - 1.4D);
            if (head > 0.05D) {
                beam(
                    t,
                    ax,
                    top + tail,
                    az,
                    ax,
                    top + head,
                    az,
                    0.075D,
                    0.030D,
                    0.90F * (float) Math.sin(phase * Math.PI),
                    0F,
                    0.85F,
                    1.00F,
                    1.00F);
            }
            index++;
        }

        // 投影锥：从顶盖中心张到全息星球
        beam(
            t,
            bx,
            by,
            bz,
            bx,
            by + HOLO_HEIGHT + HOLO_SIZE * 0.5D,
            bz,
            0.10D,
            HOLO_SIZE * 0.42D,
            0.30F * breathe,
            0.03F,
            0.35F,
            0.85F,
            1.00F);
        // 底座光环
        annulus(t, bx, by + 0.02D, bz, 0.16D, 0.46D, 0.16D + 0.10D * breathe, 0.40F, 0.90F, 1.00F);
        t.draw();
    }

    /** 全息星球：两片互成 90° 的贴图绕竖轴转 + 一条上扫的亮带。 */
    private static void drawHologram(Tessellator t, Rig rig, double time) {
        Minecraft mc = Minecraft.getMinecraft();
        double bob = Math.sin(time * 1.1D) * 0.08D;
        double cx = rig.centerX + 0.5D;
        double cy = rig.topY + 1D + HOLO_HEIGHT + HOLO_SIZE * 0.5D + bob;
        double cz = rig.centerZ + 0.5D;
        // 轻微闪烁：两个不成倍数的正弦叠加，看着不机械
        float flicker = 0.74F + 0.09F * (float) Math.sin(time * 17.3D) + 0.05F * (float) Math.sin(time * 5.1D);
        double angle = time * 0.9D;
        double half = HOLO_SIZE * 0.5D;
        double dx = Math.cos(angle) * half, dz = Math.sin(angle) * half;

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        mc.getTextureManager()
            .bindTexture(PLANETS);

        int cell = PlanetIconWidget.CELL;
        double texelU = 1.0D / (PlanetIconWidget.COLS * cell);
        double texelV = 1.0D / (PlanetIconWidget.ROWS * cell);
        int col = rig.iconSlot % PlanetIconWidget.COLS, row = rig.iconSlot / PlanetIconWidget.COLS;
        // 内缩半个像素：图集按线性/最近邻采样时，贴边的 UV 会把隔壁那颗星球的颜色渗进来
        float u0 = (float) ((col * cell + 0.5D) * texelU), u1 = (float) ((col * cell + cell - 0.5D) * texelU);
        float v0 = (float) ((row * cell + 0.5D) * texelV), v1 = (float) ((row * cell + cell - 0.5D) * texelV);

        t.startDrawingQuads();
        planetSheet(t, cx, cy, cz, dx, dz, half, u0, v0, u1, v1, flicker);
        planetSheet(t, cx, cy, cz, -dz, dx, half, u0, v0, u1, v1, flicker);
        t.draw();
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // 扫描亮带：一条细亮的横线从下往上扫过星球
        double sweepY = cy - half + frac(time * 0.45D) * HOLO_SIZE;
        double band = HOLO_SIZE * 0.55D;
        t.startDrawingQuads();
        sheet(t, cx, sweepY, cz, dx / half * band, dz / half * band, 0.016D, 0.70F, 0.95F, 1.00F, 0.55F);
        sheet(t, cx, sweepY, cz, -dz / half * band, dx / half * band, 0.016D, 0.70F, 0.95F, 1.00F, 0.55F);
        t.draw();
    }

    // ------------------------------------------------------------------
    // 图元
    // ------------------------------------------------------------------

    /** 一圈轨道：亮头 + 三次方衰减的彗尾，每一段都是朝向相机的缎带（侧对时不会缩成一条缝）。 */
    private static void orbit(Tessellator t, double cx, double cy, double cz, double camX, double camY, double camZ,
        double radius, double e1x, double e1y, double e1z, double e2x, double e2y, double e2z, double phase, float base,
        float comet, float r, float g, float b) {
        double px = 0D, py = 0D, pz = 0D;
        float previousAlpha = 0F;
        for (int i = 0; i <= ORBIT_SEGMENTS; i++) {
            double angle = (double) i / ORBIT_SEGMENTS * Math.PI * 2.0D;
            double cos = Math.cos(angle), sin = Math.sin(angle);
            double x = cx + radius * (cos * e1x + sin * e2x);
            double y = cy + radius * (cos * e1y + sin * e2y);
            double z = cz + radius * (cos * e1z + sin * e2z);
            float alpha = base + comet * (float) Math.pow(frac(phase - (double) i / ORBIT_SEGMENTS), 3.0D);
            if (i > 0) {
                segment(t, px, py, pz, x, y, z, previousAlpha, alpha, camX, camY, camZ, r, g, b);
            }
            px = x;
            py = y;
            pz = z;
            previousAlpha = alpha;
        }
    }

    private static void segment(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1,
        float a0, float a1, double camX, double camY, double camZ, float r, float g, float b) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double vx = camX - (x0 + x1) * 0.5D, vy = camY - (y0 + y1) * 0.5D, vz = camZ - (z0 + z1) * 0.5D;
        // 宽度方向 = 线段方向 × 视线方向：同时垂直于两者，缎带永远正对相机
        double wx = dy * vz - dz * vy, wy = dz * vx - dx * vz, wz = dx * vy - dy * vx;
        double length = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (length < 1.0E-6D) return; // 正对着这一小段看，宽度方向退化，跳过
        double k = ORBIT_WIDTH / length;
        wx *= k;
        wy *= k;
        wz *= k;

        t.setColorRGBA_F(r, g, b, a0);
        t.addVertex(x0 - wx, y0 - wy, z0 - wz);
        t.addVertex(x0 + wx, y0 + wy, z0 + wz);
        t.setColorRGBA_F(r, g, b, a1);
        t.addVertex(x1 + wx, y1 + wy, z1 + wz);
        t.addVertex(x1 - wx, y1 - wy, z1 - wz);
    }

    /** 竖直光束：两片互相垂直的缎带（单片侧对时会缩成一条缝），两端宽度与透明度可以不同。 */
    private static void beam(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1, double w0,
        double w1, float a0, float a1, float r, float g, float b) {
        ribbon(t, x0, y0, z0, x1, y1, z1, w0, w1, true, a0, a1, r, g, b);
        ribbon(t, x0, y0, z0, x1, y1, z1, w0, w1, false, a0, a1, r, g, b);
    }

    private static void ribbon(Tessellator t, double x0, double y0, double z0, double x1, double y1, double z1,
        double w0, double w1, boolean alongX, float a0, float a1, float r, float g, float b) {
        double ax = alongX ? w0 : 0D, az = alongX ? 0D : w0;
        double bx = alongX ? w1 : 0D, bz = alongX ? 0D : w1;
        t.setColorRGBA_F(r, g, b, a0);
        t.addVertex(x0 - ax, y0, z0 - az);
        t.addVertex(x0 + ax, y0, z0 + az);
        t.setColorRGBA_F(r, g, b, a1);
        t.addVertex(x1 + bx, y1, z1 + bz);
        t.addVertex(x1 - bx, y1, z1 - bz);
    }

    /** 水平的发光圆环（投影底座的光晕）。 */
    private static void annulus(Tessellator t, double cx, double y, double cz, double inner, double outer, double alpha,
        float r, float g, float b) {
        for (int i = 0; i < ORBIT_SEGMENTS; i++) {
            double a0 = (double) i / ORBIT_SEGMENTS * Math.PI * 2.0D;
            double a1 = (double) (i + 1) / ORBIT_SEGMENTS * Math.PI * 2.0D;
            t.setColorRGBA_F(r, g, b, (float) (alpha * 0.35D));
            t.addVertex(cx + Math.cos(a0) * inner, y, cz + Math.sin(a0) * inner);
            t.addVertex(cx + Math.cos(a1) * inner, y, cz + Math.sin(a1) * inner);
            t.setColorRGBA_F(r, g, b, (float) alpha);
            t.addVertex(cx + Math.cos(a1) * outer, y, cz + Math.sin(a1) * outer);
            t.addVertex(cx + Math.cos(a0) * outer, y, cz + Math.sin(a0) * outer);
        }
    }

    /** 贴图的一张竖片：以 (dx,dz) 为半宽方向、以 half 为半高。 */
    private static void planetSheet(Tessellator t, double cx, double cy, double cz, double dx, double dz, double half,
        float u0, float v0, float u1, float v1, float alpha) {
        // 略带青色：保留星球本色的同时有一点「投影」的冷调
        t.setColorRGBA_F(0.80F, 0.96F, 1.00F, alpha);
        t.setTextureUV(u0, v1);
        t.addVertex(cx - dx, cy - half, cz - dz);
        t.setTextureUV(u1, v1);
        t.addVertex(cx + dx, cy - half, cz + dz);
        t.setTextureUV(u1, v0);
        t.addVertex(cx + dx, cy + half, cz + dz);
        t.setTextureUV(u0, v0);
        t.addVertex(cx - dx, cy + half, cz - dz);
    }

    /** 不贴图的竖片（扫描亮带）。 */
    private static void sheet(Tessellator t, double cx, double cy, double cz, double dx, double dz, double half,
        float r, float g, float b, float alpha) {
        t.setColorRGBA_F(r, g, b, alpha);
        t.addVertex(cx - dx, cy - half, cz - dz);
        t.addVertex(cx + dx, cy - half, cz + dz);
        t.addVertex(cx + dx, cy + half, cz + dz);
        t.addVertex(cx - dx, cy + half, cz - dz);
    }

    private static double frac(double value) {
        return value - Math.floor(value);
    }

    private static void logOnce(Throwable t) {
        if (!loggedFailure) {
            loggedFailure = true;
            FutaGtnhMod.LOG.warn("深空采矿指挥中心：绘制特效失败（后续不再重复报告）", t);
        }
    }
}
