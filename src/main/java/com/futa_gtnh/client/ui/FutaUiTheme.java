package com.futa_gtnh.client.ui;

import com.cleanroommc.modularui.drawable.UITexture;

/** Resource-pack replaceable GT/AE panel and button nine-slices. */
public final class FutaUiTheme {

    public static final UITexture PANEL = texture("panel");
    public static final UITexture BUTTON = texture("button");
    public static final UITexture BUTTON_HOVER = texture("button_hover");
    public static final UITexture BUTTON_DISABLED = texture("button_disabled");
    public static final UITexture SLOT = texture("slot");

    private FutaUiTheme() {}

    private static UITexture texture(String name) {
        return UITexture.builder()
            .location("futa_gtnh", "gui/mui2/" + name)
            .imageSize(32, 32)
            .adaptable(3)
            .build();
    }

    public static void panel(int x, int y, int width, int height) {
        PANEL.applyColor(0xFFFFFFFF);
        PANEL.draw(x, y, width, height);
    }

    public static void slot(int x, int y) {
        SLOT.applyColor(0xFFFFFFFF);
        SLOT.draw(x, y, 18, 18);
    }

    public static void button(int x, int y, int width, int height, boolean hovered) {
        BUTTON.applyColor(0xFFFFFFFF);
        (hovered ? BUTTON_HOVER : BUTTON).draw(x, y, width, height);
    }
}
