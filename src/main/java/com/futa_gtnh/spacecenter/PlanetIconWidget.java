package com.futa_gtnh.spacecenter;

import java.util.function.Supplier;

import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

/**
 * 星球图标控件：从 {@code assets/futa_gtnh/textures/gui/planets.png}（64 格、每格 32×32、
 * 16 列 × 4 行，由 {@code tools/GenSpaceCenterTextures} 生成）里按星球名哈希取一格，
 * 画进 {@code size × size} 的方格里。
 *
 * <p>
 * 星球名来自 GUI 同步串，所以这里<b>不读客户端本地的星球表</b>；名字为 null（这一行没有星球）
 * 时什么都不画。槽位只由名字决定，服务端与客户端各自算出来一定相同，不需要额外同步。
 *
 * <p>
 * 服务端也会实例化（buildUI 两侧都跑），但 {@link #draw} 只在客户端被调用。
 */
public class PlanetIconWidget extends Widget<PlanetIconWidget> {

    /** 图集列数 / 行数 / 单格边长，必须与生成脚本一致。 */
    public static final int COLS = 16;
    public static final int ROWS = 4;
    public static final int CELL = 32;

    private static UITexture sheet;

    private Supplier<String> planetName = () -> null;
    private int size = 16;

    public PlanetIconWidget planetName(Supplier<String> name) {
        this.planetName = name;
        return this;
    }

    @Override
    public PlanetIconWidget size(int width, int height) {
        this.size = width;
        return super.size(width, height);
    }

    private static UITexture sheet() {
        if (sheet == null) {
            sheet = UITexture.builder()
                .location("futa_gtnh", "gui/planets")
                .imageSize(COLS * CELL, ROWS * CELL)
                .build();
        }
        return sheet;
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        String name = planetName.get();
        if (name == null) return;
        int slot = PlanetRegistry.iconSlotOf(name);
        float u0 = (float) (slot % COLS) / COLS;
        float v0 = (float) (slot / COLS) / ROWS;
        sheet().drawSubArea(0, 0, size, size, u0, v0, u0 + 1f / COLS, v0 + 1f / ROWS);
    }
}
