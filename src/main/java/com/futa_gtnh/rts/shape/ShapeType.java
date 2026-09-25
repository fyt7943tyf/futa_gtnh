package com.futa_gtnh.rts.shape;

/**
 * 批量操作的形状类型。两端共用（客户端做幽灵预览、服务端权威生成），
 * 所以必须是纯数据、不碰任何 Minecraft 类。
 *
 * <p>
 * 形状都由两个角点 A、B 定义（俯瞰模式下 Ctrl+左键选的两个方块位置）。
 * 各形状怎么解读这对角点见 {@link ShapeGenerator}。
 */
public enum ShapeType {

    /** A 到 B 的直线（三维，逐格）。 */
    LINE,
    /** 竖直墙面：A、B 连线（水平投影）撑开、从低 Y 到高 Y。 */
    WALL,
    /** 水平平面：A、B 在 XZ 上撑开的矩形，Y 取 A 的 Y。 */
    PLANE,
    /** 水平圆盘：圆心取 A、B 水平中点，半径取水平距离，Y 取 A 的 Y。 */
    CIRCLE,
    /** 圆柱：CIRCLE 的形状从低 Y 挤出到高 Y。 */
    CYLINDER,
    /** 球：圆心取 A、B 中点，半径取两点距离的一半。 */
    SPHERE,
    /** 长方体：A、B 就是对角。 */
    BOX;

    /** 语言键（HUD 显示用）：形状的显示名。 */
    public String langKey() {
        return "futa_gtnh.rts.shape." + name().toLowerCase();
    }
}
