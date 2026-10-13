package com.futa_gtnh.client.ui;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.item.ItemStack;

/** Control model for MUI2 toolbar buttons; rendering and input belong to FutaUiLayer. */
public final class FutaIconButton extends GuiButton {

    public final ItemStack icon;
    public boolean active;

    public FutaIconButton(int id, int x, int y, int width, int height, String text, ItemStack icon) {
        super(id, x, y, width, height, text);
        this.icon = icon;
    }
}
