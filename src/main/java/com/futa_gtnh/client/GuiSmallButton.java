package com.futa_gtnh.client;

import net.minecraft.client.gui.GuiButton;

/** Compact button model. The MUI2 layer draws an adaptable background at its actual height. */
public class GuiSmallButton extends GuiButton {

    public GuiSmallButton(int id, int x, int y, int width, int height, String label) {
        super(id, x, y, width, height, label);
    }
}
