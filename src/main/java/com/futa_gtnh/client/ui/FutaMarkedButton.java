package com.futa_gtnh.client.ui;

import net.minecraft.client.gui.GuiButton;

/** Selected-state model shared by IO page, direction and preset controls. */
public class FutaMarkedButton extends GuiButton {

    private boolean marked;

    public FutaMarkedButton(int id, int x, int y, int width, int height, String label) {
        super(id, x, y, width, height, label);
    }

    public void setMarked(boolean value) {
        marked = value;
    }

    public boolean isMarked() {
        return marked;
    }
}
