package com.futa_gtnh.client.nei;

import net.minecraft.client.gui.inventory.GuiContainer;

import com.futa_gtnh.client.StoragePanel;
import com.futa_gtnh.client.TinkersScreens;

import codechicken.nei.guihook.IContainerInputHandler;

/**
 * 把共享存储面板的点击 / 滚轮 / 键盘接到 NEI 的输入钩子上。
 *
 * <p>
 * 1.7.10 的 Forge 只有 {@code GuiScreenEvent.DrawScreenEvent}（能画不能拦），
 * NEI 这套 {@code IContainerInputHandler} 是唯一「返回 true 就消费掉」的入口 ——
 * 这也是 NEI 自己那些覆盖层能正常收点击的原因。
 *
 * <p>
 * <b>注意只处理匠魂工作站界面</b>：面板只在那些界面上画出来，别的界面里这三个方法
 * 立刻返回 false，不干扰任何东西。
 */
public class StoragePanelInput implements IContainerInputHandler {

    @Override
    public boolean mouseClicked(GuiContainer gui, int mouseX, int mouseY, int button) {
        if (!TinkersScreens.isWorkstation(gui)) return false;
        return StoragePanel.get()
            .mouseClicked(gui, mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(GuiContainer gui, int mouseX, int mouseY, int scrollDir) {
        if (!TinkersScreens.isWorkstation(gui)) return false;
        return StoragePanel.get()
            .mouseScrolled(gui, scrollDir);
    }

    @Override
    public boolean keyTyped(GuiContainer gui, char typedChar, int keyCode) {
        if (!TinkersScreens.isWorkstation(gui)) return false;
        return StoragePanel.get()
            .keyTyped(gui, typedChar, keyCode);
    }

    // ---- 其余钩子用不到，全部放过去 ----

    @Override
    public void onKeyTyped(GuiContainer gui, char typedChar, int keyCode) {}

    @Override
    public boolean lastKeyTyped(GuiContainer gui, char typedChar, int keyCode) {
        return false;
    }

    @Override
    public void onMouseClicked(GuiContainer gui, int mouseX, int mouseY, int button) {}

    @Override
    public void onMouseUp(GuiContainer gui, int mouseX, int mouseY, int button) {}

    @Override
    public void onMouseScrolled(GuiContainer gui, int mouseX, int mouseY, int scrollDir) {}

    @Override
    public void onMouseDragged(GuiContainer gui, int mouseX, int mouseY, int button, long heldTime) {}
}
