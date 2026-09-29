package com.futa_gtnh.client;

/**
 * 「一个可以转的小方块」的全部数学：旋转、投影、背面剔除、命中判定。
 *
 * <p>
 * <b>为什么单独一个类、而且一行 MC 都不引：</b>配置界面里那个 3D 方块是这块界面
 * 最容易出错的地方（哪个面朝前、点到的到底是哪个面、哪几个面该画），
 * 而它在游戏里只能靠肉眼验证。把数学抽出来之后可以直接用 {@code javac} + {@code java}
 * 跑一遍，把各个角度下投出来的多边形和可见面打印/画出来对着看 ——
 * 这一步在离线环境里就能做完，不用开客户端。
 *
 * <p>
 * 坐标约定（和 MC 一致）：<b>+x = 东，+y = 上，+z = 南</b>。
 * 立方体是 -1..1 的单位方块；投影是正交投影（不做透视），
 * 默认视角是「从东南上方看」，也就是能看见 上 / 南 / 东 三个面。
 * 面的编号直接用 {@code ForgeDirection} 的顺序：下0 上1 北2 南3 西4 东5。
 */
public final class CubeView {

    public static final int FACES = 6;

    /** 每个面的外法线，下标同 ForgeDirection。 */
    private static final double[][] NORMALS = { { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 }, { 0, 0, 1 }, { -1, 0, 0 },
        { 1, 0, 0 } };

    /**
     * 每个面的四个角（本地坐标）。
     *
     * <p>
     * 顶点顺序统一成「从外面看逆时针」，贴图的两个轴就是
     * (第 0 个角 -&gt; 第 1 个角) 和 (第 0 个角 -&gt; 第 3 个角)。
     */
    private static final double[][][] CORNERS = {
        // 下：贴在 y = -1，从下方看逆时针
        { { -1, -1, 1 }, { 1, -1, 1 }, { 1, -1, -1 }, { -1, -1, -1 } },
        // 上：贴在 y = +1
        { { -1, 1, -1 }, { 1, 1, -1 }, { 1, 1, 1 }, { -1, 1, 1 } },
        // 北：贴在 z = -1
        { { 1, 1, -1 }, { -1, 1, -1 }, { -1, -1, -1 }, { 1, -1, -1 } },
        // 南：贴在 z = +1
        { { -1, 1, 1 }, { 1, 1, 1 }, { 1, -1, 1 }, { -1, -1, 1 } },
        // 西：贴在 x = -1
        { { -1, 1, -1 }, { -1, 1, 1 }, { -1, -1, 1 }, { -1, -1, -1 } },
        // 东：贴在 x = +1
        { { 1, 1, 1 }, { 1, 1, -1 }, { 1, -1, -1 }, { 1, -1, 1 } } };

    private double yaw;
    private double pitch;

    public CubeView() {
        reset();
    }

    /**
     * 默认视角：从东南上方看的 3/4 视角（看得见 上 / 南 / 东）。
     *
     * <p>
     * 两个角都<b>刻意不取 0</b>：正对着某一面时，左右两个面正好和视线平行（法线 z 分量为 0），
     * 会被背面剔除掉 —— 画出来就是个正方形加一个面，看着不像方块。
     */
    public void reset() {
        yaw = 30.0D;
        pitch = 25.0D;
    }

    public double getYaw() {
        return yaw;
    }

    public double getPitch() {
        return pitch;
    }

    /** 拖动时用：横向转 yaw，纵向转 pitch（pitch 夹在 ±89°，免得翻过头看不出上下）。 */
    public void rotate(double deltaYaw, double deltaPitch) {
        yaw = wrap(yaw + deltaYaw);
        pitch = Math.max(-89.0D, Math.min(89.0D, pitch + deltaPitch));
    }

    private static double wrap(double degrees) {
        double value = degrees % 360.0D;
        return value < 0.0D ? value + 360.0D : value;
    }

    /** @return 这个面现在是不是朝着镜头 */
    public boolean isVisible(int face) {
        double[] normal = rotateVector(NORMALS[face][0], NORMALS[face][1], NORMALS[face][2]);
        // 镜头在 +z 方向往 -z 看：法线的 z 分量为正才朝着镜头
        return normal[2] > 1.0E-6D;
    }

    /** @return 这个面中心的深度（越大越靠近镜头），用来决定绘制顺序 */
    public double depth(int face) {
        double[] center = rotateVector(NORMALS[face][0], NORMALS[face][1], NORMALS[face][2]);
        return center[2];
    }

    /**
     * 把一个面的四个角投到屏幕上。
     *
     * @param scale 半宽（像素）：立方体棱长对应 2 * scale
     * @return 4 个 {x, y} 屏幕坐标，顺序和 {@link #CORNERS} 一致
     */
    public double[][] project(int face, double centerX, double centerY, double scale) {
        return projectOffset(face, 0.0D, 0.0D, 0.0D, centerX, centerY, scale);
    }

    /**
     * 同上，但方块整体先平移一段（单位是「半格」：本体边长正好是 2）。
     *
     * <p>
     * 用来画本体周围那圈邻居方块：{@code ForgeDirection} 的偏移量 ×2 就是邻居中心的位置。
     */
    public double[][] projectOffset(int face, double offsetX, double offsetY, double offsetZ, double centerX,
        double centerY, double scale) {
        double[][] result = new double[4][2];
        for (int i = 0; i < 4; i++) {
            double[] rotated = rotateVector(
                CORNERS[face][i][0] + offsetX,
                CORNERS[face][i][1] + offsetY,
                CORNERS[face][i][2] + offsetZ);
            result[i][0] = centerX + rotated[0] * scale;
            result[i][1] = centerY - rotated[1] * scale;
        }
        return result;
    }

    /** 某个方向的邻居方块中心相对本体的位移（单位同 {@link #projectOffset}）。 */
    public static double[] neighbourOffset(int face) {
        return new double[] { NORMALS[face][0] * 2.0D, NORMALS[face][1] * 2.0D, NORMALS[face][2] * 2.0D };
    }

    /** 把立方体空间里的一个点投到屏幕上（渲染 3D 模型时要知道它该画在哪儿）。 */
    public double[] projectPoint(double offsetX, double offsetY, double offsetZ, double centerX, double centerY,
        double scale) {
        double[] rotated = rotateVector(offsetX, offsetY, offsetZ);
        return new double[] { centerX + rotated[0] * scale, centerY - rotated[1] * scale };
    }

    /** 当前 yaw / pitch，让 3D 模型能和这些多边形用同一套朝向。 */
    public double[] getAngles() {
        return new double[] { yaw, pitch };
    }

    /**
     * 鼠标点在哪个面上。
     *
     * <p>
     * 从最近的可见面开始判（和绘制顺序相反），这样「压在上面」的那个面先命中 ——
     * 和眼睛看到的完全一致。
     *
     * @return 面编号；没点在立方体上返回 -1
     */
    public int hitTest(double mouseX, double mouseY, double centerX, double centerY, double scale) {
        int best = -1;
        double bestDepth = Double.NEGATIVE_INFINITY;
        for (int face = 0; face < FACES; face++) {
            if (!isVisible(face)) continue;
            if (!inside(project(face, centerX, centerY, scale), mouseX, mouseY)) continue;

            double depth = depth(face);
            if (depth > bestDepth) {
                bestDepth = depth;
                best = face;
            }
        }
        return best;
    }

    /** 点在不在这个凸四边形里（同侧判定：所有边的叉积同号才算在里面）。 */
    public static boolean inside(double[][] quad, double x, double y) {
        boolean positive = false;
        boolean negative = false;
        for (int i = 0; i < quad.length; i++) {
            double[] a = quad[i];
            double[] b = quad[(i + 1) % quad.length];
            double cross = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
            if (cross > 0.0D) positive = true;
            if (cross < 0.0D) negative = true;
        }
        return !(positive && negative);
    }

    /**
     * 一个面受光强弱，用来给贴图叠一点明暗 —— 不然六个面一样亮，看起来是平的。
     * 顶面最亮，朝着镜头的侧面次之，其余按法线方向给个中间值。
     */
    public float brightness(int face) {
        double[] normal = rotateVector(NORMALS[face][0], NORMALS[face][1], NORMALS[face][2]);
        // 光从「左上前方」来：和竖直角度的点积给底色，再按朝向微调
        double light = 0.55D + 0.30D * Math.max(0.0D, normal[1])
            + 0.20D * Math.max(0.0D, normal[2])
            + 0.10D * Math.max(0.0D, -normal[0]);
        return (float) Math.max(0.35D, Math.min(1.0D, light));
    }

    private double[] rotateVector(double x, double y, double z) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);

        double cosYaw = Math.cos(yawRad);
        double sinYaw = Math.sin(yawRad);
        double cosPitch = Math.cos(pitchRad);
        double sinPitch = Math.sin(pitchRad);

        // 先绕 y 轴转（水平打量），再绕 x 轴转（抬头/低头）
        double x1 = x * cosYaw - z * sinYaw;
        double z1 = x * sinYaw + z * cosYaw;

        double y2 = y * cosPitch - z1 * sinPitch;
        double z2 = y * sinPitch + z1 * cosPitch;
        return new double[] { x1, y2, z2 };
    }
}
