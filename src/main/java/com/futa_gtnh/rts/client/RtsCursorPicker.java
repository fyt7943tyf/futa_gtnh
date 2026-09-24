package com.futa_gtnh.rts.client;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import org.lwjgl.input.Mouse;

import com.futa_gtnh.Config;

/**
 * 俯瞰模式的光标拾取：由「鼠标在屏幕上的位置 + 相机姿态 + FOV」反投影出
 * 一条射线，方块 / 实体择近。
 *
 * <p>
 * <b>反投影的数学</b>（不依赖原版准星 —— 准星永远在屏幕中心，而俯瞰模式
 * 要点的是光标指的地方）：
 * <ol>
 * <li>原版投影是竖直 FOV = {@code fovSetting}（相机实体不吃疾跑/飞行/濒死
 * 的 FOV 修正，见 {@code RtsCameraEntity}），横向由宽高比展开；</li>
 * <li>鼠标像素 → NDC：x ∈ [-1,1] 向右为正，y 向上为正（LWJGL 的
 * {@code Mouse.getY()} 本来就从底部起算，正好）；</li>
 * <li>视线方向 = normalize(前向 + 右向·nx·tanHalfH + 上向·ny·tanHalfV)，
 * 这是标准针孔相机模型，屏幕中心的射线就是相机前向，用来和画面核对最方便。</li>
 * </ol>
 *
 * <p>
 * <b>拾取要用插值姿态</b>：帧是在两个 tick 之间画的（partialTicks），
 * 相机在动的时候用 tick 姿态会差出几格 —— 必须和渲染器读同一个插值
 * （partialTicks 由 drawScreen 每帧存进来，见 {@link #setPartialTicks}）。
 *
 * <p>
 * 实体射线用「slab 法」求射线-AABB 入射距离，和方块命中的距离比远近。
 * 掉落物/经验球/箭不参与拾取：原版对「攻击它们」是直接踢人的，与其在
 * 两个端各自防，不如一开始就不让它们成为目标。
 */
public final class RtsCursorPicker {

    private RtsCursorPicker() {}

    /** 最近一次拾取的结果（drawScreen 每帧刷新，渲染高亮用它）。 */
    private static RtsPickResult lastPick;

    /**
     * 最近一次渲染帧的 partialTicks。
     *
     * <p>
     * 拾取要用插值姿态（见 {@link #pick()}），而 {@code Minecraft.timer} 在本
     * 工程的映射里是 private —— 由 {@code GuiRtsOverlay#drawScreen} 每帧把
     * 它收到的 partialTicks 存进来，效果等价（GUI 绘制与世界渲染同属一帧）。
     */
    private static float lastPartialTicks;

    public static void setPartialTicks(float partialTicks) {
        lastPartialTicks = partialTicks;
    }

    public static RtsPickResult getLastPick() {
        return lastPick;
    }

    /** 做一次拾取并记录为最近结果。每次调用都是完整计算（点击时重算保证新鲜）。 */
    public static RtsPickResult pick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || RtsCameraController.getCameraEntity() == null) {
            lastPick = RtsPickResult.miss(0, 0, 0, 0, 0, 0);
            return lastPick;
        }

        float partialTicks = lastPartialTicks;
        double[] origin = RtsCameraController.getCameraPosition(partialTicks);
        float yaw = RtsCameraController.getCameraYaw(partialTicks);
        float pitch = RtsCameraController.getCameraPitch(partialTicks);
        double[] dir = cursorRayDirection(mc, yaw, pitch);

        World world = mc.theWorld;
        double range = Config.rtsPickRange;
        Vec3 from = Vec3.createVectorHelper(origin[0], origin[1], origin[2]);
        Vec3 to = Vec3
            .createVectorHelper(origin[0] + dir[0] * range, origin[1] + dir[1] * range, origin[2] + dir[2] * range);

        // --- 方块命中 -------------------------------------------------------
        // 2 参重载 = (from, to, stopOnLiquid=false, ignoreBlockWithoutBoundingBox=false,
        // returnLastUncollidable=false)，与原版准星同款参数（原版实现就是这三参委托）
        MovingObjectPosition blockHit = world.rayTraceBlocks(from, to);
        double blockDist = Double.MAX_VALUE;
        if (blockHit != null && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            && blockHit.hitVec != null) {
            blockDist = from.distanceTo(blockHit.hitVec);
        } else {
            blockHit = null;
        }

        // --- 实体命中：沿射线步进采样 ----------------------------------------
        // 1.3.0 用整条射线的大 AABB 一次查询（边长可达 2×128 格），每帧跑一次在
        // 实体密集场景明显掉帧。改为沿射线每 4 格取样一个 ±4 盒子逐段查询：
        // 覆盖连续、开销有界；一旦已找到的实体入射距离不大于当前段起点即可停
        // （更远的段里不可能有更近的命中）。
        Entity nearestEntity = null;
        double entityDist = Double.MAX_VALUE;
        double entityHitX = 0, entityHitY = 0, entityHitZ = 0;
        double blockLimit = blockHit != null ? blockDist : range;
        final double STEP = 4.0D;
        for (double t = 0.0D; t < blockLimit; t += STEP) {
            double sx = from.xCoord + dir[0] * t;
            double sy = from.yCoord + dir[1] * t;
            double sz = from.zCoord + dir[2] * t;
            AxisAlignedBB segmentBox = AxisAlignedBB
                .getBoundingBox(sx - STEP, sy - STEP, sz - STEP, sx + STEP, sy + STEP, sz + STEP);
            List<Entity> candidates = world.getEntitiesWithinAABB(Entity.class, segmentBox);
            for (Entity entity : candidates) {
                if (!isPickable(mc, entity)) continue;
                double entry = rayAabbEntry(from, dir, entity.boundingBox);
                if (entry >= 0.0D && entry <= blockLimit && entry < entityDist) {
                    entityDist = entry;
                    nearestEntity = entity;
                    entityHitX = from.xCoord + dir[0] * entry;
                    entityHitY = from.yCoord + dir[1] * entry;
                    entityHitZ = from.zCoord + dir[2] * entry;
                }
            }
            if (nearestEntity != null && entityDist <= t) break;
        }

        // --- 择近 -----------------------------------------------------------
        if (nearestEntity != null && entityDist < blockDist) {
            lastPick = RtsPickResult.entity(
                nearestEntity,
                entityHitX,
                entityHitY,
                entityHitZ,
                entityDist,
                origin[0],
                origin[1],
                origin[2],
                dir[0],
                dir[1],
                dir[2]);
        } else if (blockHit != null) {
            lastPick = RtsPickResult.block(
                blockHit.blockX,
                blockHit.blockY,
                blockHit.blockZ,
                blockHit.sideHit,
                blockHit.hitVec.xCoord,
                blockHit.hitVec.yCoord,
                blockHit.hitVec.zCoord,
                blockDist,
                origin[0],
                origin[1],
                origin[2],
                dir[0],
                dir[1],
                dir[2]);
        } else {
            lastPick = RtsPickResult.miss(origin[0], origin[1], origin[2], dir[0], dir[1], dir[2]);
        }
        return lastPick;
    }

    /** 反投影：鼠标位置 + 相机朝向 → 世界空间单位射线方向。 */
    private static double[] cursorRayDirection(Minecraft mc, float yaw, float pitch) {
        int displayWidth = Math.max(1, mc.displayWidth);
        int displayHeight = Math.max(1, mc.displayHeight);
        double nx = 2.0D * Mouse.getX() / displayWidth - 1.0D;
        double ny = 2.0D * Mouse.getY() / displayHeight - 1.0D;

        double fovRad = Math.toRadians(mc.gameSettings.fovSetting);
        double tanHalfV = Math.tan(fovRad / 2.0D);
        double tanHalfH = tanHalfV * (double) displayWidth / (double) displayHeight;

        // 相机基：前向 f、屏幕右 r = normalize(f × 上)、上 u = r × f
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double fx = -Math.sin(yawRad) * Math.cos(pitchRad);
        double fy = -Math.sin(pitchRad);
        double fz = Math.cos(yawRad) * Math.cos(pitchRad);
        // f × (0,1,0)：俯角限制在 ±89°，cos(pitch) 不会为 0，可以安全归一化
        // （校验：yaw=0 朝南时 f=(0,0,1)，屏幕右应是西 (-1,0,0)，与本式一致）
        double rx = -fz;
        double ry = 0.0D;
        double rz = fx;
        double rLen = Math.sqrt(rx * rx + rz * rz);
        rx /= rLen;
        rz /= rLen;
        // u = r × f
        double ux = ry * fz - rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy - ry * fx;

        double dx = fx + rx * (nx * tanHalfH) + ux * (ny * tanHalfV);
        double dy = fy + ry * (nx * tanHalfH) + uy * (ny * tanHalfV);
        double dz = fz + rz * (nx * tanHalfH) + uz * (ny * tanHalfV);
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[] { dx / len, dy / len, dz / len };
    }

    private static boolean isPickable(Minecraft mc, Entity entity) {
        if (entity == null || entity.isDead) return false;
        if (entity == mc.thePlayer) return false;
        if (entity instanceof RtsCameraEntity) return false;
        if (entity instanceof EntityItem || entity instanceof EntityXPOrb || entity instanceof EntityArrow)
            return false;
        return true;
    }

    /**
     * 拾取目标是否超出「玩家周围的操作半径」（服务端的同款立方体规则，见
     * {@code RtsActionGuard}）。
     *
     * <p>
     * 相机钳制半径与拾取距离都是 128 时，对角线方向的目标离玩家可达 ~181 格，
     * 必然被服务端拒绝 —— 1.3.0 里这类拒绝全部静默（上机反馈「点了没反应」
     * 的一个来源）。现在客户端提前用同一规则判定：渲染高亮变红 + 状态行提示，
     * 把「为什么点了没反应」提前暴露出来。
     */
    public static boolean isBeyondPlayerRange(RtsPickResult pick) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || pick == null || pick.type == RtsPickResult.Type.MISS) return false;

        double tx, ty, tz;
        if (pick.type == RtsPickResult.Type.BLOCK) {
            tx = pick.blockX + 0.5D;
            ty = pick.blockY + 0.5D;
            tz = pick.blockZ + 0.5D;
        } else if (pick.entity != null) {
            tx = pick.entity.posX;
            ty = pick.entity.posY;
            tz = pick.entity.posZ;
        } else {
            return false;
        }

        double r = Config.rtsMaxActionRadius;
        return Math.abs(tx - mc.thePlayer.posX) > r || Math.abs(ty - mc.thePlayer.posY) > r
            || Math.abs(tz - mc.thePlayer.posZ) > r;
    }

    /**
     * 射线-AABB 入射距离（slab 法）。
     *
     * @return 入射参数 t（射线原点起算的距离）；不相交或盒子在身后返回 -1
     */
    private static double rayAabbEntry(Vec3 origin, double[] dir, AxisAlignedBB bb) {
        double tmin = 0.0D;
        double tmax = Double.MAX_VALUE;

        // X 轴
        if (Math.abs(dir[0]) < 1.0E-9D) {
            if (origin.xCoord < bb.minX || origin.xCoord > bb.maxX) return -1.0D;
        } else {
            double t1 = (bb.minX - origin.xCoord) / dir[0];
            double t2 = (bb.maxX - origin.xCoord) / dir[0];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
        }

        // Y 轴
        if (Math.abs(dir[1]) < 1.0E-9D) {
            if (origin.yCoord < bb.minY || origin.yCoord > bb.maxY) return -1.0D;
        } else {
            double t1 = (bb.minY - origin.yCoord) / dir[1];
            double t2 = (bb.maxY - origin.yCoord) / dir[1];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
        }

        // Z 轴
        if (Math.abs(dir[2]) < 1.0E-9D) {
            if (origin.zCoord < bb.minZ || origin.zCoord > bb.maxZ) return -1.0D;
        } else {
            double t1 = (bb.minZ - origin.zCoord) / dir[2];
            double t2 = (bb.maxZ - origin.zCoord) / dir[2];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tmin = Math.max(tmin, t1);
            tmax = Math.min(tmax, t2);
        }

        if (tmax < tmin) return -1.0D;
        return tmin;
    }
}
