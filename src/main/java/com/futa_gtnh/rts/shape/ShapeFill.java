package com.futa_gtnh.rts.shape;

/**
 * 批量操作的填充模式（对形状的「实心/空心/线框」三档）。
 *
 * <p>
 * 各形状对三种模式的解读（建筑工具语义，不是数学严格定义）：
 * <ul>
 * <li>SOLID：整个体积；</li>
 * <li>HOLLOW：只要「外壳」（盒子的六个面、圆的圆环、球的球壳…）——
 * 盖房子最常用；</li>
 * <li>WIREFRAME：只要「棱」（盒子的 12 条边、圆柱的上下两个圆环…）——
 * 搭脚手架/骨架最常用。</li>
 * </ul>
 */
public enum ShapeFill {

    SOLID,
    HOLLOW,
    WIREFRAME;

    /** 语言键（HUD 显示用）：填充模式的显示名。 */
    public String langKey() {
        return "futa_gtnh.rts.fill." + name().toLowerCase();
    }
}
