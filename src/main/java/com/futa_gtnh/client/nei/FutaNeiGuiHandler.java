package com.futa_gtnh.client.nei;

import java.util.List;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.client.Accessors;
import com.futa_gtnh.client.GuiSharedTerminal;
import com.futa_gtnh.client.NeiAwareGui;

import codechicken.nei.VisiblityData;
import codechicken.nei.api.INEIGuiAdapter;

/**
 * 本模组界面的<b>统一</b> NEI 适配器：注册一次，对所有实现了
 * {@link NeiAwareGui} 的界面生效。
 *
 * <p>
 * 以前的做法是「哪个界面要动 NEI 就给它写一个 handler 类再逐个注册」；
 * 现在界面只实现 {@link NeiAwareGui} 声明意图（要不要收面板、哪些区域要遮），
 * 这里负责把声明翻译成 NEI 的语言。本类只在装了 NEI 时才会被加载
 * （见 {@code NeiIntegration} 的守卫），所以可以直接 import {@code codechicken.nei}。
 *
 * <p>
 * 全局单例：{@code API.registerNEIGuiHandler} 的 handler 表对每个打开的
 * GUI 都会跑一遍 {@code modifyVisiblity}，一个实例就够。
 */
public final class FutaNeiGuiHandler extends INEIGuiAdapter {

    public static final FutaNeiGuiHandler INSTANCE = new FutaNeiGuiHandler();

    private FutaNeiGuiHandler() {}

    @Override
    public boolean handleDragNDrop(GuiContainer gui, int mouseX, int mouseY, ItemStack stack, int button) {
        if (!(gui instanceof GuiSharedTerminal) || !((GuiSharedTerminal) gui).acceptSearchDrop(mouseX, mouseY, stack))
            return false;
        // NEI 的虚拟拖拽栈归零才会结束拖拽；不是玩家背包里的真实物品。
        stack.stackSize = 0;
        return true;
    }

    @Override
    public VisiblityData modifyVisiblity(GuiContainer gui, VisiblityData currentVisibility) {
        // 只在界面明确要求时才收面板；默认（SHOW）什么都不做，
        // NEI 的物品面板和搜索条照常显示 —— 搜索条在「跟随面板」布局下
        // 会随面板一起被 NEI 收掉，这正是以前「搜索栏不见了」的根源。
        boolean hide = gui instanceof NeiAwareGui && ((NeiAwareGui) gui).hideNeiItemPanel();
        if (currentVisibility.showItemPanel && hide) {
            currentVisibility.showItemPanel = false;
        }
        return currentVisibility;
    }

    @Override
    public boolean hideItemPanelSlot(GuiContainer gui, int x, int y, int w, int h) {
        if (!(gui instanceof NeiAwareGui)) return false;

        List<int[]> areas = ((NeiAwareGui) gui).neiMaskedAreas();
        if (areas == null || areas.isEmpty()) return false;

        // NEI 给的是屏幕坐标，遮罩区声明的是 GUI 本地坐标，先换算再求交。
        // guiLeft/guiTop 是 protected，本类不是 GuiContainer 子类，走反射（结果有缓存）。
        int guiLeft = Accessors.intField(gui, "guiLeft", "field_147003_i");
        int guiTop = Accessors.intField(gui, "guiTop", "field_147009_r");

        for (int[] area : areas) {
            if (area == null || area.length < 4) continue;
            int ax = guiLeft + area[0];
            int ay = guiTop + area[1];
            if (x < ax + area[2] && x + w > ax && y < ay + area[3] && y + h > ay) return true;
        }
        return false;
    }
}
