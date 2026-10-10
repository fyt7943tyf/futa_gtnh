package com.futa_gtnh.client.nei;

import net.minecraft.client.gui.inventory.GuiContainer;

import com.futa_gtnh.client.GuiSharedTerminal;

import codechicken.nei.guihook.GuiContainerManager;
import codechicken.nei.guihook.IContainerInputHandler;

/** 搜索框先收键盘；被动点击钩子补齐被 NEI 面板消费掉的焦点事件。 */
public final class TerminalSearchInput implements IContainerInputHandler {

    private static final TerminalSearchInput INSTANCE = new TerminalSearchInput();

    public static void register() {
        // NEI 普通注册追加到末尾，匠魂腰带处理器会先于 GUI.keyTyped 运行。
        // 放在最前仅优先处理本终端的聚焦输入框，其他界面与未聚焦状态仍放行。
        if (!GuiContainerManager.inputHandlers.contains(INSTANCE)) {
            GuiContainerManager.inputHandlers.addFirst(INSTANCE);
        }
    }

    @Override
    public boolean keyTyped(GuiContainer gui, char typedChar, int keyCode) {
        return gui instanceof GuiSharedTerminal && ((GuiSharedTerminal) gui).handleSearchKey(typedChar, keyCode);
    }

    @Override
    public void onMouseClicked(GuiContainer gui, int mouseX, int mouseY, int button) {
        if (gui instanceof GuiSharedTerminal) ((GuiSharedTerminal) gui).handleSearchClick(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseClicked(GuiContainer gui, int mouseX, int mouseY, int button) {
        // 让 NEI 的拖拽栈先交给 handleDragNDrop，不能提前消费搜索框内的点击。
        return false;
    }

    @Override
    public boolean mouseScrolled(GuiContainer gui, int mouseX, int mouseY, int scrollDir) {
        return false;
    }

    @Override
    public boolean lastKeyTyped(GuiContainer gui, char typedChar, int keyCode) {
        return false;
    }

    @Override
    public void onKeyTyped(GuiContainer gui, char typedChar, int keyCode) {}

    @Override
    public void onMouseUp(GuiContainer gui, int mouseX, int mouseY, int button) {}

    @Override
    public void onMouseScrolled(GuiContainer gui, int mouseX, int mouseY, int scrollDir) {}

    @Override
    public void onMouseDragged(GuiContainer gui, int mouseX, int mouseY, int button, long heldTime) {}
}
