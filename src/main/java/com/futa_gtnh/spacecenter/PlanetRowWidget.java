package com.futa_gtnh.spacecenter;

import java.util.function.BooleanSupplier;

import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.screen.viewport.ModularGuiContext;
import com.cleanroommc.modularui.theme.WidgetThemeEntry;
import com.cleanroommc.modularui.widgets.ButtonWidget;

/**
 * 星球列表里的一行按钮。这一版 MUI2 没有运行时的显隐开关（{@code invisible()} 只是去掉主题背景），
 * 而列表的行数必须两侧在 buildUI 时就定死——所以固定建出一页的行数，
 * 由同步串决定每一行有没有星球：<b>没有星球的行整行不画、不响应点击</b>。
 *
 * <p>
 * 这样客户端完全不依赖本地的星球表：专用服务器的客户端即使矿脉/油田配置与服务端不一致，
 * 列表也与服务端一模一样。
 */
public class PlanetRowWidget extends ButtonWidget<PlanetRowWidget> {

    private final BooleanSupplier hasPlanet;

    public PlanetRowWidget(BooleanSupplier hasPlanet) {
        this.hasPlanet = hasPlanet;
    }

    private boolean visible() {
        return hasPlanet.getAsBoolean();
    }

    // 按钮的覆盖文字是它的子控件，随 drawForeground 一起画，四个钩子一起守卫即可整行隐形
    @Override
    public void drawBackground(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        if (visible()) super.drawBackground(context, widgetTheme);
    }

    @Override
    public void draw(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        if (visible()) super.draw(context, widgetTheme);
    }

    @Override
    public void drawOverlay(ModularGuiContext context, WidgetThemeEntry<?> widgetTheme) {
        if (visible()) super.drawOverlay(context, widgetTheme);
    }

    @Override
    public void drawForeground(ModularGuiContext context) {
        if (visible()) super.drawForeground(context);
    }

    @Override
    public Interactable.Result onMousePressed(int mouseButton) {
        return visible() ? super.onMousePressed(mouseButton) : Interactable.Result.IGNORE;
    }
}
