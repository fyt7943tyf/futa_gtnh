package com.futa_gtnh.client;

import net.minecraft.client.gui.GuiButton;

/** GT tool control model; the MUI2 ItemDrawable handles scaling, lighting and enchantment effects. */
public class GuiToolButton extends GuiButton {

    private CraftingToolShortcuts.Entry entry;

    public GuiToolButton(int id, int x, int y, int size, CraftingToolShortcuts.Entry entry) {
        super(id, x, y, size, size, "");
        setEntry(entry);
    }

    public void setEntry(CraftingToolShortcuts.Entry entry) {
        this.entry = entry;
        enabled = entry != null && entry.isAvailable();
    }

    public CraftingToolShortcuts.Entry entry() {
        return entry;
    }
}
