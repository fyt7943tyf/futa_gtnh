package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketLocatorAction;
import com.futa_gtnh.network.PacketLocatorResult;

/**
 * 寻物魔杖的选择界面。
 *
 * <p>
 * 故意做成纯客户端 {@code GuiScreen} 而不是 {@code GuiContainer}：
 * 这里没有任何「槽位」—— 选中的方块只是一个名字，不是要拿走的物品。
 * 用容器的话反而要为一个根本不存在的东西造一堆空槽位。
 *
 * <p>
 * 界面里的一切都是<b>请求</b>而非事实：点一个方块只是发个 START 包出去，
 * 「最近的在哪儿」永远由服务端回答。进度条画的也是服务端推回来的进度。
 *
 * <p>
 * 关闭界面<b>不会</b>取消追踪 —— 玩家正是要关掉界面去看那道光束的。
 * 取消有两个入口：界面里的「停止」按钮，或者潜行右键魔杖。
 */
public class GuiLocatorWand extends GuiScreen {

    // ---- 布局 ----
    private static final int GUI_WIDTH = 268;
    private static final int GUI_HEIGHT = 190;
    private static final int SEARCH_Y = 18;
    private static final int GRID_X = 6;
    private static final int GRID_Y = 36;
    private static final int COLS = 9;
    private static final int ROWS = 7;
    private static final int CELL = 18;
    private static final int GRID_W = COLS * CELL;
    private static final int GRID_H = ROWS * CELL;
    private static final int PANEL_X = 172;
    private static final int PANEL_W = 90;
    private static final int BUTTON_Y = GRID_Y + GRID_H + 4;

    private static final int BTN_TELEPORT = 0;
    private static final int BTN_STOP = 1;

    private static final int COLOR_PANEL = 0xC0101010;
    private static final int COLOR_SLOT = 0x40FFFFFF;
    private static final int COLOR_SLOT_HOVER = 0x80FFFFFF;
    private static final int COLOR_BORDER = 0xFF808080;

    private static final RenderItem ITEM_RENDER = RenderItem.getInstance();

    private final List<ItemStack> results = new ArrayList<>();

    private int guiLeft;
    private int guiTop;
    private GuiTextField searchField;
    private GuiButton teleportButton;
    private GuiButton stopButton;

    /** 当前页第一个结果所在的行。 */
    private int scrollRow;
    private boolean viewDirty = true;

    @Override
    public boolean doesGuiPauseGame() {
        // 单人游戏里也不暂停：玩家多半正一边盯着光束一边往前走
        return false;
    }

    // ==================================================================
    // 初始化
    // ==================================================================

    @Override
    public void initGui() {
        super.initGui();
        this.guiLeft = (this.width - GUI_WIDTH) / 2;
        this.guiTop = (this.height - GUI_HEIGHT) / 2;

        String previous = searchField == null ? "" : searchField.getText();

        searchField = new GuiTextField(
            fontRendererObj,
            guiLeft + GRID_X + 3,
            guiTop + SEARCH_Y + 4,
            GUI_WIDTH - 2 * GRID_X - 6,
            12);
        searchField.setMaxStringLength(64);
        searchField.setEnableBackgroundDrawing(false);
        searchField.setText(previous);
        searchField.setFocused(true);

        buttonList.clear();
        teleportButton = new GuiButton(
            BTN_TELEPORT,
            guiLeft + PANEL_X,
            guiTop + BUTTON_Y,
            42,
            18,
            tr("futa_gtnh.gui.locator.teleport"));
        stopButton = new GuiButton(
            BTN_STOP,
            guiLeft + PANEL_X + 46,
            guiTop + BUTTON_Y,
            44,
            18,
            tr("futa_gtnh.gui.locator.stop"));
        buttonList.add(teleportButton);
        buttonList.add(stopButton);

        // 第一次打开界面时才真正开始索引；进度会显示在网格下面
        BlockIndex.ensureStarted();
        viewDirty = true;
    }

    // ==================================================================
    // 每 tick
    // ==================================================================

    @Override
    public void updateScreen() {
        super.updateScreen();

        if (searchField != null) {
            searchField.updateCursorCounter();
        }

        boolean wasBuilding = BlockIndex.isBuilding();
        BlockIndex.tick();
        // 索引还在长的时候每 tick 都要重新过滤，否则列表会冻结在打开界面那一瞬间的快照
        if (wasBuilding) {
            viewDirty = true;
        }

        if (viewDirty) {
            viewDirty = false;
            rebuildResults();
        }

        refreshButtons();
    }

    private void rebuildResults() {
        BlockIndex.filter(searchField == null ? "" : searchField.getText(), results);
        scrollRow = 0;
    }

    private void refreshButtons() {
        if (teleportButton == null || mc == null || mc.thePlayer == null) return;

        teleportButton.enabled = LocatorState.getState() == PacketLocatorResult.STATE_FOUND
            && LocatorState.getResultDimension() == mc.thePlayer.dimension;
        stopButton.enabled = LocatorState.getState() != LocatorState.STATE_NONE;
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawRect(guiLeft, guiTop, guiLeft + GUI_WIDTH, guiTop + GUI_HEIGHT, COLOR_PANEL);
        drawBorder(guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT);

        fontRendererObj
            .drawStringWithShadow(tr("futa_gtnh.gui.locator.title"), guiLeft + GRID_X + 3, guiTop + 6, 0xFFFFFF);

        drawSearchBox();
        drawGrid(mouseX, mouseY);
        drawStatusPanel();

        // 按钮画在网格之后，保证盖在网格边框上
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (searchField != null) {
            searchField.drawTextBox();
        }

        ItemStack hovered = itemAt(mouseX, mouseY);
        if (hovered != null) {
            renderToolTip(hovered, mouseX, mouseY);
        }
    }

    private void drawSearchBox() {
        drawRect(
            guiLeft + GRID_X,
            guiTop + SEARCH_Y,
            guiLeft + GRID_X + GUI_WIDTH - 2 * GRID_X,
            guiTop + SEARCH_Y + 16,
            0xFF000000);
        drawBorder(guiLeft + GRID_X, guiTop + SEARCH_Y, GUI_WIDTH - 2 * GRID_X, 16);

        if (searchField != null && searchField.getText()
            .isEmpty()) {
            fontRendererObj.drawString(
                EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.locator.search.hint"),
                guiLeft + GRID_X + 4,
                guiTop + SEARCH_Y + 5,
                0xFFFFFF);
        }
    }

    private void drawGrid(int mouseX, int mouseY) {
        drawRect(guiLeft + GRID_X, guiTop + GRID_Y, guiLeft + GRID_X + GRID_W, guiTop + GRID_Y + GRID_H, 0xFF000000);
        drawBorder(guiLeft + GRID_X, guiTop + GRID_Y, GRID_W, GRID_H);

        int visible = COLS * ROWS;

        // 物品渲染必须开 GUI 光照，否则方块会是一片黑
        RenderHelper.enableGUIStandardItemLighting();
        float previousZ = ITEM_RENDER.zLevel;
        ITEM_RENDER.zLevel = 100.0F;

        for (int slot = 0; slot < visible; slot++) {
            int itemIndex = scrollRow * COLS + slot;
            if (itemIndex >= results.size()) break;

            int cellX = guiLeft + GRID_X + (slot % COLS) * CELL;
            int cellY = guiTop + GRID_Y + (slot / COLS) * CELL;

            boolean isHovered = mouseX >= cellX && mouseX < cellX + CELL && mouseY >= cellY && mouseY < cellY + CELL;
            drawRect(
                cellX + 1,
                cellY + 1,
                cellX + CELL - 1,
                cellY + CELL - 1,
                isHovered ? COLOR_SLOT_HOVER : COLOR_SLOT);

            ItemStack stack = results.get(itemIndex);
            // 选中的那个描一圈绿边，不然滚走之后就找不着了
            if (isSelected(stack)) {
                drawBorder(cellX + 1, cellY + 1, CELL - 2, CELL - 2, 0xFF55FF55);
            }

            try {
                ITEM_RENDER
                    .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, cellX + 1, cellY + 1);
            } catch (Throwable ignored) {
                // 个别模组的物品渲染会抛异常；不能让一个坏物品废掉整个界面
            }
        }

        ITEM_RENDER.zLevel = previousZ;
        RenderHelper.disableStandardItemLighting();

        drawGridFooter();
    }

    /** 网格下面的状态行 + 滚动条。 */
    private void drawGridFooter() {
        int bottom = guiTop + GRID_Y + GRID_H;

        if (BlockIndex.isBuilding()) {
            fontRendererObj.drawStringWithShadow(
                StatCollector.translateToLocalFormatted(
                    "futa_gtnh.gui.locator.indexing",
                    (int) (BlockIndex.getProgress() * 100.0F)),
                guiLeft + GRID_X + 2,
                bottom + 3,
                0xFFAA00);
        } else if (results.isEmpty()) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GRAY + tr("futa_gtnh.gui.locator.empty"),
                guiLeft + GRID_X + 2,
                bottom + 3,
                0xFFFFFF);
        } else {
            fontRendererObj.drawStringWithShadow(
                StatCollector
                    .translateToLocalFormatted("futa_gtnh.gui.locator.count", results.size(), BlockIndex.size()),
                guiLeft + GRID_X + 2,
                bottom + 3,
                0xA0A0A0);
        }

        // 滚动条：只有真的滚得动时才画
        int totalRows = totalRows();
        if (totalRows > ROWS) {
            int trackX = guiLeft + GRID_X + GRID_W + 1;
            drawRect(trackX, guiTop + GRID_Y, trackX + 2, guiTop + GRID_Y + GRID_H, 0x40FFFFFF);

            int thumbHeight = Math.max(8, GRID_H * ROWS / totalRows);
            int maxScroll = totalRows - ROWS;
            int thumbY = guiTop + GRID_Y + (GRID_H - thumbHeight) * scrollRow / maxScroll;
            drawRect(trackX, thumbY, trackX + 2, thumbY + thumbHeight, 0xFFA0A0A0);
        }
    }

    private void drawStatusPanel() {
        drawRect(guiLeft + PANEL_X, guiTop + GRID_Y, guiLeft + PANEL_X + PANEL_W, guiTop + GRID_Y + GRID_H, 0xFF000000);
        drawBorder(guiLeft + PANEL_X, guiTop + GRID_Y, PANEL_W, GRID_H);

        int x = guiLeft + PANEL_X + 4;
        int y = guiTop + GRID_Y + 4;

        ItemStack target = LocatorState.getTarget();
        if (target == null) {
            fontRendererObj
                .drawStringWithShadow(EnumChatFormatting.GRAY + tr("futa_gtnh.gui.locator.pick"), x, y + 4, 0xFFFFFF);
            drawHintLines(x, guiTop + GRID_Y + GRID_H - 26);
            return;
        }

        RenderHelper.enableGUIStandardItemLighting();
        float previousZ = ITEM_RENDER.zLevel;
        ITEM_RENDER.zLevel = 100.0F;
        try {
            ITEM_RENDER.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), target, x, y);
        } catch (Throwable ignored) {
            // 同上：坏物品不该带崩界面
        }
        ITEM_RENDER.zLevel = previousZ;
        RenderHelper.disableStandardItemLighting();

        fontRendererObj.drawStringWithShadow(
            fontRendererObj.trimStringToWidth(target.getDisplayName(), PANEL_W - 28),
            x + 20,
            y + 4,
            0xFFFFFF);

        drawStatusText(x, y + 22);
        drawHintLines(x, guiTop + GRID_Y + GRID_H - 26);
    }

    private void drawStatusText(int x, int y) {
        byte state = LocatorState.getState();

        if (state == PacketLocatorResult.STATE_RUNNING) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.YELLOW + tr("futa_gtnh.gui.locator.state.running"),
                x,
                y,
                0xFFFFFF);
            drawProgressBar(x, y + 12, PANEL_W - 8, 6, LocatorState.getProgress());
            return;
        }

        if (state == PacketLocatorResult.STATE_NOT_FOUND) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.RED + tr("futa_gtnh.gui.locator.state.notfound"),
                x,
                y,
                0xFFFFFF);
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.locator.radius"),
                x,
                y + 12,
                0xFFFFFF);
            return;
        }

        if (state != PacketLocatorResult.STATE_FOUND) return;

        fontRendererObj
            .drawStringWithShadow(EnumChatFormatting.GREEN + tr("futa_gtnh.gui.locator.state.found"), x, y, 0xFFFFFF);

        // 出结果之后玩家可能已经走了传送门，那时候坐标指的是另一个世界的同一个数字
        if (LocatorState.getResultDimension() != mc.thePlayer.dimension) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.RED + tr("futa_gtnh.gui.locator.wrongdim"),
                x,
                y + 12,
                0xFFFFFF);
            return;
        }

        fontRendererObj.drawStringWithShadow(
            StatCollector.translateToLocalFormatted(
                "futa_gtnh.gui.locator.distance",
                String.format(Locale.ROOT, "%.1f", LocatorState.getDistance())),
            x,
            y + 12,
            0xFFFFFF);
        fontRendererObj
            .drawStringWithShadow(EnumChatFormatting.GRAY + "X " + LocatorState.getPosX(), x, y + 24, 0xFFFFFF);
        fontRendererObj
            .drawStringWithShadow(EnumChatFormatting.GRAY + "Y " + LocatorState.getPosY(), x, y + 34, 0xFFFFFF);
        fontRendererObj
            .drawStringWithShadow(EnumChatFormatting.GRAY + "Z " + LocatorState.getPosZ(), x, y + 44, 0xFFFFFF);
        fontRendererObj
            .drawStringWithShadow(EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.locator.beam"), x, y + 58, 0xFFFFFF);
    }

    private void drawHintLines(int x, int y) {
        fontRendererObj
            .drawStringWithShadow(EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.locator.hint1"), x, y, 0xFFFFFF);
        fontRendererObj.drawStringWithShadow(
            EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.locator.hint2"),
            x,
            y + 10,
            0xFFFFFF);
    }

    private void drawProgressBar(int x, int y, int width, int height, float progress) {
        int filled = Math.max(0, Math.min(width, (int) (progress * width)));
        drawRect(x, y, x + width, y + height, 0xFF303030);
        drawRect(x, y, x + filled, y + height, 0xFF55FF55);
        drawBorder(x, y, width, height);
    }

    private void drawBorder(int x, int y, int width, int height) {
        drawBorder(x, y, width, height, COLOR_BORDER);
    }

    private void drawBorder(int x, int y, int width, int height, int color) {
        drawRect(x, y, x + width, y + 1, color);
        drawRect(x, y + height - 1, x + width, y + height, color);
        drawRect(x, y, x + 1, y + height, color);
        drawRect(x + width - 1, y, x + width, y + height, color);
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (searchField != null && mouseX >= searchField.xPosition
            && mouseX < searchField.xPosition + searchField.width
            && mouseY >= searchField.yPosition
            && mouseY < searchField.yPosition + 12) {
            searchField.mouseClicked(mouseX, mouseY, mouseButton);
            // 搜索框始终保有焦点：否则点到空白处之后再敲字母会触发快捷键直接关掉界面
            searchField.setFocused(true);
            return;
        }

        int index = indexAt(mouseX, mouseY);
        if (index >= 0 && index < results.size()) {
            select(results.get(index));
            return;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (searchField != null) {
            searchField.setFocused(true);
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();

        int delta = Mouse.getEventDWheel();
        if (delta == 0) return;

        // 只在鼠标位于网格内时滚动，免得在按钮那边滚轮也把列表滚掉
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (!isInsideGrid(mouseX, mouseY)) return;

        int maxScroll = Math.max(0, totalRows() - ROWS);
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow + (delta > 0 ? -1 : 1)));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // 注意：1.7.10 的 GuiScreen#keyTyped 不声明 throws，
        // 覆写时加上 throws IOException 会直接编译不过
        if (searchField != null && searchField.textboxKeyTyped(typedChar, keyCode)) {
            viewDirty = true;
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            scrollRow = Math.max(0, scrollRow - ROWS);
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            scrollRow = Math.min(Math.max(0, totalRows() - ROWS), scrollRow + ROWS);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case BTN_TELEPORT:
                // 传不传得成由服务端裁决（要重新找安全落点），这里不预改本地状态
                NetworkHandler.INSTANCE.sendToServer(new PacketLocatorAction(PacketLocatorAction.TELEPORT));
                break;
            case BTN_STOP:
                NetworkHandler.INSTANCE.sendToServer(new PacketLocatorAction(PacketLocatorAction.CANCEL));
                LocatorState.clear();
                break;
            default:
                break;
        }
    }

    private void select(ItemStack stack) {
        if (stack == null) return;
        // 先更新本地状态让界面立刻有反馈，真结果等服务端推回来
        LocatorState.setTarget(stack);
        NetworkHandler.INSTANCE.sendToServer(PacketLocatorAction.start(stack));
        refreshButtons();
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private int totalRows() {
        return (results.size() + COLS - 1) / COLS;
    }

    private boolean isInsideGrid(int mouseX, int mouseY) {
        return mouseX >= guiLeft + GRID_X && mouseX < guiLeft + GRID_X + GRID_W
            && mouseY >= guiTop + GRID_Y
            && mouseY < guiTop + GRID_Y + GRID_H;
    }

    /**
     * @return 鼠标下的结果下标；不在网格里或落在空白格上时返回 -1
     */
    private int indexAt(int mouseX, int mouseY) {
        if (!isInsideGrid(mouseX, mouseY)) return -1;
        int col = (mouseX - guiLeft - GRID_X) / CELL;
        int row = (mouseY - guiTop - GRID_Y) / CELL;
        return scrollRow * COLS + row * COLS + col;
    }

    private ItemStack itemAt(int mouseX, int mouseY) {
        int index = indexAt(mouseX, mouseY);
        return index >= 0 && index < results.size() ? results.get(index) : null;
    }

    private boolean isSelected(ItemStack stack) {
        ItemStack target = LocatorState.getTarget();
        if (target == null || stack == null) return false;
        return target.getItem() == stack.getItem() && target.getItemDamage() == stack.getItemDamage();
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
