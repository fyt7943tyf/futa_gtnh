package com.futa_gtnh.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.GuiToolButton;

/**
 * MUI2 control tree for screens whose authoritative vanilla containers must stay intact.
 * The overlay construction API deliberately avoids replacing their inventory/window IDs.
 * Canvas content (maps, virtual storage and IME fields) stays with its owning screen.
 */
public final class FutaUiLayer {

    private final GuiScreen owner;
    private final Consumer<GuiButton> action;
    private final List<GuiButton> controls = new ArrayList<>();
    private final List<ButtonWidget<?>> widgets = new ArrayList<>();
    private ModularScreen screen;
    private int width, height;

    public FutaUiLayer(GuiScreen owner, Consumer<GuiButton> action) {
        this.owner = owner;
        this.action = action;
    }

    public void draw(List<GuiButton> buttons, int mouseX, int mouseY, float partialTicks) {
        prepare(buttons);
        screen.getContext()
            .updateState(mouseX, mouseY, partialTicks);
        screen.onFrameUpdate();
        GL11.glPushAttrib(
            GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_DEPTH_BUFFER_BIT
                | GL11.GL_LIGHTING_BIT
                | GL11.GL_CURRENT_BIT);
        try {
            screen.drawScreen();
            screen.drawForeground();
        } finally {
            GL11.glPopAttrib();
        }
    }

    private void prepare(List<GuiButton> buttons) {
        boolean changed = screen == null || width != owner.width
            || height != owner.height
            || controls.size() != buttons.size();
        if (!changed) {
            for (int i = 0; i < buttons.size(); i++) {
                GuiButton button = buttons.get(i);
                ButtonWidget<?> widget = widgets.get(i);
                if (controls.get(i) != button || widget.getArea().x != button.xPosition
                    || widget.getArea().y != button.yPosition
                    || widget.getArea().width != button.width
                    || widget.getArea().height != button.height) {
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            close();
            width = owner.width;
            height = owner.height;
            controls.addAll(buttons);
            ModularPanel panel = new ModularPanel("controls").pos(0, 0)
                .size(width, height)
                .invisible();
            for (GuiButton button : buttons) {
                ButtonWidget<?> widget = new ButtonWidget<>().pos(button.xPosition, button.yPosition)
                    .size(button.width, button.height)
                    .background(FutaUiTheme.BUTTON)
                    .hoverBackground(FutaUiTheme.BUTTON_HOVER)
                    .overlay(
                        IKey.dynamic(() -> button.displayString)
                            .color(0xFFE0E0E0))
                    .onMousePressed(mouseButton -> {
                        if (mouseButton != 0 || !button.visible || !button.enabled) return false;
                        GuiScreenEvent.ActionPerformedEvent.Pre event = new GuiScreenEvent.ActionPerformedEvent.Pre(
                            owner,
                            button,
                            controls);
                        if (!MinecraftForge.EVENT_BUS.post(event)) {
                            action.accept(event.button);
                            if (Minecraft.getMinecraft().currentScreen == owner) {
                                MinecraftForge.EVENT_BUS
                                    .post(new GuiScreenEvent.ActionPerformedEvent.Post(owner, event.button, controls));
                            }
                        }
                        return true;
                    });
                if (button instanceof GuiToolButton tool) {
                    widget.overlay(new ItemDrawable().setItem(tool.entry() == null ? null : tool.entry().icon));
                } else if (button instanceof FutaIconButton icon && icon.icon != null) {
                    widget.overlay(new ItemDrawable(icon.icon));
                }
                widgets.add(widget);
                panel.child(widget);
            }
            screen = new ModularScreen(FutaGtnhMod.MODID, panel).drawDarkBackground(false);
            screen.constructOverlay(owner);
            screen.onResize(width, height);
        }
        for (int i = 0; i < controls.size(); i++) {
            GuiButton button = controls.get(i);
            ButtonWidget<?> widget = widgets.get(i);
            widget.setEnabled(button.visible);
            widget.background(button.enabled ? FutaUiTheme.BUTTON : FutaUiTheme.BUTTON_DISABLED);
            if (button instanceof FutaIconButton icon && icon.active) widget.background(FutaUiTheme.BUTTON_HOVER);
            if (button instanceof FutaMarkedButton marked && marked.isMarked())
                widget.background(FutaUiTheme.BUTTON_HOVER);
            widget.hoverBackground(button.enabled ? FutaUiTheme.BUTTON_HOVER : FutaUiTheme.BUTTON_DISABLED);
            if (button instanceof GuiToolButton tool) {
                widget.overlay(new ItemDrawable(tool.entry() == null ? null : tool.entry().icon));
            }
        }
    }

    /** Consume only control rectangles; inventory clicks and world dragging keep their existing routes. */
    public boolean click(List<GuiButton> buttons, int mouseX, int mouseY, int mouseButton) {
        for (GuiButton button : buttons) {
            if (contains(button, mouseX, mouseY)) {
                prepare(buttons);
                screen.getContext()
                    .updateState(mouseX, mouseY, 0);
                // Refresh the hover tree even when a resize or tab switch happened between frames.
                screen.onFrameUpdate();
                widgets.get(controls.indexOf(button))
                    .onMousePressed(mouseButton);
                return true;
            }
        }
        return false;
    }

    public static boolean contains(GuiButton button, int x, int y) {
        return button.visible && x >= button.xPosition
            && y >= button.yPosition
            && x < button.xPosition + button.width
            && y < button.yPosition + button.height;
    }

    public void close() {
        if (screen != null) {
            screen.getPanelManager()
                .closeAll();
            screen.getPanelManager()
                .dispose();
            screen = null;
        }
        controls.clear();
        widgets.clear();
    }
}
