package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import com.futa_gtnh.client.ui.FutaUiLayer;

/** Shared MUI2 control lifecycle for non-inventory screens and custom map canvases. */
public abstract class FutaGuiScreen extends GuiScreen {

    private final FutaUiLayer ui = new FutaUiLayer(this, this::actionPerformed);

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        List<GuiButton> controls = new ArrayList<>(buttonList);
        buttonList.clear();
        try {
            super.drawScreen(mouseX, mouseY, partialTicks);
        } finally {
            buttonList.addAll(controls);
        }
        ui.draw(controls, mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (!ui.click(buttonList, mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void onGuiClosed() {
        ui.close();
        super.onGuiClosed();
    }
}
