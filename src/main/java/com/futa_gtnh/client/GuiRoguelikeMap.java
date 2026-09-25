package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** Roguelike Dungeons 全屏地图。 */
public final class GuiRoguelikeMap extends GuiScreen {

    private static final int MAP_LEFT = 8;
    private static final int MAP_TOP = 42;
    private static final int MAP_BOTTOM = 28;
    private static final int BUTTON_REFRESH = 20;
    private static final int BUTTON_MINI = 21;
    private static final int BUTTON_LEVEL_BASE = 100;

    private RoguelikeMapRenderer.View view;
    private int selectedLevel;
    private long stateGeneration;
    private boolean userSelectedLevel;

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void initGui() {
        super.initGui();
        selectedLevel = RoguelikeMapClient.state()
            .getCurrentLevel();
        stateGeneration = RoguelikeMapClient.state()
            .getGeneration();
        userSelectedLevel = false;
        buttonList.clear();
        int buttonWidth = 46;
        int startX = width - 6 - buttonWidth * RoguelikeMapSource.LEVEL_COUNT;
        for (int level = 0; level < RoguelikeMapSource.LEVEL_COUNT; level++) {
            buttonList.add(
                new GuiButton(
                    BUTTON_LEVEL_BASE + level,
                    startX + level * buttonWidth,
                    20,
                    buttonWidth - 2,
                    18,
                    "第 " + (level + 1) + " 层"));
        }
        buttonList.add(new GuiButton(BUTTON_REFRESH, 8, 20, 54, 18, "重新扫描"));
        buttonList.add(new GuiButton(BUTTON_MINI, 66, 20, 70, 18, "切换小地图"));
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!RoguelikeMapClient.isAvailable()) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        RoguelikeMapState state = RoguelikeMapClient.state();
        if (stateGeneration != state.getGeneration()) {
            selectedLevel = state.getCurrentLevel();
            stateGeneration = state.getGeneration();
            userSelectedLevel = false;
            view = null;
        } else if (!userSelectedLevel) {
            // 未手动选层时跟随玩家当前层，避免扫描重建后仍停留在空楼层。
            selectedLevel = state.getCurrentLevel();
        }

        drawDefaultBackground();
        drawRect(4, 4, width - 4, height - 4, 0xD0101010);

        fontRendererObj.drawStringWithShadow("Roguelike 地牢地图", 8, 8, 0xFFFFFF);
        int level = selectedLevel;
        fontRendererObj.drawStringWithShadow(
            "当前层：第 " + (level + 1) + " 层  高度基准：" + RoguelikeMapSource.levelY(level),
            160,
            8,
            0xB0B0B0);

        int mapWidth = width - MAP_LEFT * 2;
        int mapHeight = height - MAP_TOP - MAP_BOTTOM;
        int playerX = mc.thePlayer == null ? state.getCenterX() : (int) Math.floor(mc.thePlayer.posX);
        int playerZ = mc.thePlayer == null ? state.getCenterZ() : (int) Math.floor(mc.thePlayer.posZ);
        view = RoguelikeMapRenderer
            .draw(state.getFloor(level), playerX, playerZ, MAP_LEFT, MAP_TOP, mapWidth, mapHeight, true, true);

        if (view == null) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GRAY + "尚未发现来源方块。请在地牢内移动，地图会按已加载区块逐步建立。",
                18,
                MAP_TOP + 12,
                0xFFFFFF);
        }

        RoguelikeMapRenderer.drawLegend(fontRendererObj, 8, height - 18);
        fontRendererObj.drawStringWithShadow("滚轮 / ↑↓ 切换楼层，R 重新扫描，M 关闭", width - 250, height - 18, 0xB0B0B0);

        super.drawScreen(mouseX, mouseY, partialTicks);
        drawMarkerTooltip(mouseX, mouseY, level);
    }

    private void drawMarkerTooltip(int mouseX, int mouseY, int level) {
        if (view == null) return;
        Collection<RoguelikeMapState.Marker> markers = view.markersAt(
            RoguelikeMapClient.state()
                .getFloor(level),
            mouseX,
            mouseY);
        if (markers == null || markers.isEmpty()) return;

        List<String> lines = new ArrayList<String>();
        for (RoguelikeMapState.Marker marker : markers) {
            lines.add(marker.kind + (marker.detail.isEmpty() ? "" : "：" + marker.detail));
            lines.add("坐标：" + marker.x + ", " + marker.y + ", " + marker.z);
        }
        drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id >= BUTTON_LEVEL_BASE && button.id < BUTTON_LEVEL_BASE + RoguelikeMapSource.LEVEL_COUNT) {
            selectedLevel = button.id - BUTTON_LEVEL_BASE;
            userSelectedLevel = true;
            return;
        }
        if (button.id == BUTTON_REFRESH) {
            RoguelikeMapClient.resetScan();
            return;
        }
        if (button.id == BUTTON_MINI) {
            RoguelikeMapClient.toggleMiniMap();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_M || keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_R) {
            RoguelikeMapClient.resetScan();
            selectedLevel = RoguelikeMapClient.state()
                .getCurrentLevel();
            stateGeneration = RoguelikeMapClient.state()
                .getGeneration();
            userSelectedLevel = false;
            view = null;
            return;
        }
        if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_LEFT) {
            selectedLevel = Math.max(0, selectedLevel - 1);
            userSelectedLevel = true;
            return;
        }
        if (keyCode == Keyboard.KEY_DOWN || keyCode == Keyboard.KEY_RIGHT) {
            selectedLevel = Math.min(RoguelikeMapSource.LEVEL_COUNT - 1, selectedLevel + 1);
            userSelectedLevel = true;
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel > 0) {
            selectedLevel = Math.max(0, selectedLevel - 1);
            userSelectedLevel = true;
        } else if (wheel < 0) {
            selectedLevel = Math.min(RoguelikeMapSource.LEVEL_COUNT - 1, selectedLevel + 1);
            userSelectedLevel = true;
        }
    }

}
