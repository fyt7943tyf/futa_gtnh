package com.futa_gtnh.rts.shape;

import java.util.ArrayList;
import java.util.List;

/**
 * 形状坐标生成器：由（形状、填充、两个角点）生成格子坐标列表。
 *
 * <p>
 * <b>两端共用同一份实现</b>：客户端拿它画幽灵预览，服务端拿它权威生成批量
 * 目标 —— 同一份代码保证「预览见到的 = 实际建造的」，客户端也永远不传坐标
 * 列表（只传两个角点和形状参数，防伪造成零成本）。
 *
 * <p>
 * 实现：先算出形状的整数包围盒（≤ 32³ 由调用方校验），逐格判定谓词。
 * 32³ = 32768 次简单运算，两端都毫无压力。圆/球的半径判定用「实心取
 * (r+0.5)²、壳层取两个同心圆之间」的像素化画圆惯例，避免生成出锯齿怪异
 * 的轮廓。
 */
public final class ShapeGenerator {

    private ShapeGenerator() {}

    /**
     * 生成形状的全部格子坐标。
     *
     * @return 每项是 {x, y, z}；顺序稳定（包围盒扫描序），便于两端行为一致
     */
    public static List<int[]> generate(ShapeType type, ShapeFill fill, int ax, int ay, int az, int bx, int by, int bz) {
        List<int[]> out = new ArrayList<>();
        int minX = Math.min(ax, bx), maxX = Math.max(ax, bx);
        int minY = Math.min(ay, by), maxY = Math.max(ay, by);
        int minZ = Math.min(az, bz), maxZ = Math.max(az, bz);

        switch (type) {
            case LINE:
                generateLine(out, ax, ay, az, bx, by, bz);
                break;
            case WALL:
                generateWall(out, fill, ax, az, bx, bz, minY, maxY);
                break;
            case PLANE:
                generatePlane(out, fill, minX, ax, az, bx, bz);
                break;
            case CIRCLE:
                generateCircle(out, fill, ax, az, bx, bz, ay, ay);
                break;
            case CYLINDER:
                generateCircle(out, fill, ax, az, bx, bz, minY, maxY);
                break;
            case SPHERE:
                generateSphere(out, fill, minX, maxX, minY, maxY, minZ, maxZ);
                break;
            case BOX:
            default:
                generateBox(out, fill, minX, maxX, minY, maxY, minZ, maxZ);
                break;
        }
        return out;
    }

    /** 角点包围盒的各轴跨度（校验维度上限用）。 */
    public static int extentX(int ax, int bx) {
        return Math.abs(bx - ax) + 1;
    }

    public static int extentY(int ay, int by) {
        return Math.abs(by - ay) + 1;
    }

    public static int extentZ(int az, int bz) {
        return Math.abs(bz - az) + 1;
    }

    // ------------------------------------------------------------------
    // 直线：最长轴步进、其余轴按比例取整（三维 Bresenham 的插值近似）
    // ------------------------------------------------------------------

    private static void generateLine(List<int[]> out, int ax, int ay, int az, int bx, int by, int bz) {
        int steps = Math.max(Math.abs(bx - ax), Math.max(Math.abs(by - ay), Math.abs(bz - az)));
        if (steps == 0) {
            out.add(new int[] { ax, ay, az });
            return;
        }
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            out.add(
                new int[] { ax + (int) Math.round((bx - ax) * t), ay + (int) Math.round((by - ay) * t),
                    az + (int) Math.round((bz - az) * t) });
        }
    }

    // ------------------------------------------------------------------
    // 墙：A、B 的水平连线撑开一堵竖墙。SOLID = 整面；HOLLOW = 上下两条边；
    // WIREFRAME = 四个角
    // ------------------------------------------------------------------

    private static void generateWall(List<int[]> out, ShapeFill fill, int ax, int az, int bx, int bz, int minY,
        int maxY) {
        int steps = Math.max(Math.abs(bx - ax), Math.abs(bz - az));
        if (steps == 0) steps = 1;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            int x = ax + (int) Math.round((bx - ax) * t);
            int z = az + (int) Math.round((bz - az) * t);
            for (int y = minY; y <= maxY; y++) {
                boolean edgeColumn = i == 0 || i == steps;
                boolean edgeRow = y == minY || y == maxY;
                if (fill == ShapeFill.SOLID || edgeColumn || (fill == ShapeFill.HOLLOW && edgeRow)) {
                    out.add(new int[] { x, y, z });
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 水平矩形：SOLID 全填；HOLLOW/WIREFRAME 只描边
    // ------------------------------------------------------------------

    private static void generatePlane(List<int[]> out, ShapeFill fill, int y, int ax, int az, int bx, int bz) {
        int minX = Math.min(ax, bx), maxX = Math.max(ax, bx);
        int minZ = Math.min(az, bz), maxZ = Math.max(az, bz);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                boolean edge = x == minX || x == maxX || z == minZ || z == maxZ;
                if (fill == ShapeFill.SOLID || edge) {
                    out.add(new int[] { x, y, z });
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 圆盘/圆柱：圆心取水平中点、半径取水平跨度的一半。y0==y1 时是圆盘；
    // 否则是圆柱（y 从 y0 到 y1）
    // ------------------------------------------------------------------

    private static void generateCircle(List<int[]> out, ShapeFill fill, int ax, int az, int bx, int bz, int y0,
        int y1) {
        double cx = (ax + bx) / 2.0D;
        double cz = (az + bz) / 2.0D;
        double radius = Math.max(Math.abs(bx - ax), Math.abs(bz - az)) / 2.0D;
        if (radius < 0.5D) radius = 0.5D;
        double solidSq = (radius + 0.5D) * (radius + 0.5D);
        double ringOuterSq = solidSq;
        double ringInnerSq = (radius - 0.5D) * (radius - 0.5D);

        int minX = (int) Math.floor(cx - radius - 1.0D), maxX = (int) Math.ceil(cx + radius + 1.0D);
        int minZ = (int) Math.floor(cz - radius - 1.0D), maxZ = (int) Math.ceil(cz + radius + 1.0D);
        if (y1 < y0) y1 = y0;

        for (int y = y0; y <= y1; y++) {
            boolean edgeLayer = y == y0 || y == y1;
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double dx = x - cx, dz = z - cz;
                    double distSq = dx * dx + dz * dz;
                    boolean inside = distSq <= solidSq;
                    if (!inside) continue;
                    boolean ring = distSq >= ringInnerSq && distSq <= ringOuterSq;
                    switch (fill) {
                        case SOLID:
                            out.add(new int[] { x, y, z });
                            break;
                        case HOLLOW:
                            // 圆盘 = 圆环；圆柱 = 侧面 + 上下两个圆环
                            if (ring || (y0 != y1 && edgeLayer)) out.add(new int[] { x, y, z });
                            break;
                        case WIREFRAME:
                        default:
                            // 骨架：只有上、下两层的圆环（单层圆盘就是圆环本身）
                            if (ring && (y0 == y1 || edgeLayer)) out.add(new int[] { x, y, z });
                            break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 球：圆心取角点中点、半径取最长跨度的一半
    // ------------------------------------------------------------------

    private static void generateSphere(List<int[]> out, ShapeFill fill, int minX, int maxX, int minY, int maxY,
        int minZ, int maxZ) {
        double cx = (minX + maxX) / 2.0D;
        double cy = (minY + maxY) / 2.0D;
        double cz = (minZ + maxZ) / 2.0D;
        double radius = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)) / 2.0D;
        if (radius < 0.5D) radius = 0.5D;
        double solidSq = (radius + 0.5D) * (radius + 0.5D);
        double shellInnerSq = (radius - 0.5D) * (radius - 0.5D);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double dx = x - cx, dy = y - cy, dz = z - cz;
                    double distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq > solidSq) continue;
                    // SOLID/HOLLOW/WIREFRAME 对球不区分实心：建筑上球只需要球壳
                    if (fill == ShapeFill.SOLID || distSq >= shellInnerSq) {
                        out.add(new int[] { x, y, z });
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 长方体：SOLID 全填；HOLLOW 六个面；WIREFRAME 12 条棱
    // ------------------------------------------------------------------

    private static void generateBox(List<int[]> out, ShapeFill fill, int minX, int maxX, int minY, int maxY, int minZ,
        int maxZ) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    boolean edgeX = x == minX || x == maxX;
                    boolean edgeY = y == minY || y == maxY;
                    boolean edgeZ = z == minZ || z == maxZ;
                    switch (fill) {
                        case SOLID:
                            out.add(new int[] { x, y, z });
                            break;
                        case HOLLOW:
                            if (edgeX || edgeY || edgeZ) out.add(new int[] { x, y, z });
                            break;
                        case WIREFRAME:
                        default:
                            // 至少两根轴都在边界上 = 12 条棱
                            if ((edgeX ? 1 : 0) + (edgeY ? 1 : 0) + (edgeZ ? 1 : 0) >= 2) {
                                out.add(new int[] { x, y, z });
                            }
                            break;
                    }
                }
            }
        }
    }
}
