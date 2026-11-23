package com.futa_gtnh.spacecenter;

import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widget.Widget;

/**
 * 星球图标控件：从 {@code assets/futa_gtnh/textures/gui/planets.png}（32 格、每格 16×16、
 * 16 列 × 2 行）里按星球名哈希取一格绘制，GTNEIOrePlugin 的维度图标同款观感。
 *
 * <p>
 * 服务端也会实例化（buildUI 两侧都跑），但 {@link #draw} 只在客户端被调用。
 */
public class PlanetIconWidget extends Widget<PlanetIconWidget> {

    /** 贴图列数。 */
    private static final int COLS = 16;
    /** 贴图总格数。 */
    private static final int SLOTS = 32;
    /** 单格边长（像素）。 */
    private static final int CELL = 16;

    private static UITexture sheet;

    private final int slot;

    public PlanetIconWidget(String planetName) {
        this.slot = Math.floorMod(planetName.hashCode(), SLOTS);
        size(CELL, CELL);
    }

    private static UITexture sheet() {
        if (sheet == null) {
            sheet = UITexture.builder()
                .location("futa_gtnh", "gui/planets")
                .imageSize(COLS * CELL, 2 * CELL)
                .build();
        }
        return sheet;
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        float u0 = (float) (slot % COLS) / COLS;
        float v0 = (float) (slot / COLS) / 2;
        sheet().drawSubArea(0, 0, CELL, CELL, u0, v0, u0 + 1f / COLS, v0 + 0.5f);
    }
}
