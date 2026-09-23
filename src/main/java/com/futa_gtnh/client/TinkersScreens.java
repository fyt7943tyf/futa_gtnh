package com.futa_gtnh.client;

import net.minecraft.client.gui.GuiScreen;

import com.futa_gtnh.tinkers.TinkersAutoFill;

/**
 * 「这个界面是不是匠魂的工作站」——客户端专用。
 *
 * <p>
 * 判定要引用 tconstruct 的 GUI 类，所以只能放在客户端侧的类里（服务端没有
 * {@code net.minecraft.client.*}），并且先问 {@link TinkersAutoFill#isAvailable()}
 * 再碰那些类：没装匠魂时这些类根本不存在，方法体里的引用是惰性解析的，
 * 探测没过就不会走到。
 */
public final class TinkersScreens {

    private TinkersScreens() {}

    public static boolean isWorkstation(GuiScreen screen) {
        if (screen == null || !TinkersAutoFill.isAvailable()) return false;
        try {
            return screen instanceof tconstruct.tools.gui.CraftingStationGui
                || screen instanceof tconstruct.tools.gui.PartCrafterGui
                || screen instanceof tconstruct.tools.gui.ToolStationGui
                || screen instanceof tconstruct.tools.gui.ToolForgeGui
                || screen instanceof tconstruct.smeltery.gui.SmelteryGui;
        } catch (Throwable t) {
            return false;
        }
    }
}
