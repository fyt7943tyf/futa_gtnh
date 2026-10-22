package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketReactorLayoutAction;
import com.futa_gtnh.reactor.ReactorLayout;

/**
 * 反应堆布局档案浏览器（纯客户端 {@code GuiScreen}，与寻物魔杖/小游戏助手同一套）。
 *
 * <p>
 * 两种打开方式（由 {@code ItemReactorLayoutTool} 决定）：
 * <ul>
 * <li><b>带部署目标</b>：右键反应堆进入，顶部显示目标坐标，「部署 / 建造外壳 /
 * 保存当前布局」三个按钮可用；</li>
 * <li><b>管理模式</b>：空中右键进入，只能浏览 / 预览 / 改名 / 删除。</li>
 * </ul>
 *
 * <p>
 * 左栏是布局列表（服务端元数据，{@code ClientReactorLayoutCache} 镜像）；
 * 右栏是选中布局的 6×N 网格预览（IC2 的网格是「N 列 × 固定 6 行」，N = 3 + 反应堆仓数）
 * 加上需求清单 —— 每种元件「需要多少 / 背包多少 / 共享背包多少 / 是否要靠
 * 不同损耗的同类顶替」，全部由服务端算好（{@code PacketReactorLayoutPreview}），
 * 这里只管画。所有动作都是发一个包，结果走服务端聊天栏与增量同步。
 */
public class GuiReactorLayoutBrowser extends FutaGuiScreen {

    // ---- 布局 ----
    private static final int GUI_WIDTH = 346;
    private static final int GUI_HEIGHT = 236;
    private static final int PAD = 6;

    private static final int TITLE_Y = 6;
    private static final int TARGET_Y = 17;
    private static final int LIST_Y = 32;
    private static final int ROW_HEIGHT = 14;
    private static final int LIST_ROWS = 11;
    private static final int LIST_HEIGHT = LIST_ROWS * ROW_HEIGHT;
    private static final int LIST_WIDTH = 124;
    private static final int RENAME_Y = LIST_Y + LIST_HEIGHT + 5;
    private static final int LEFT_BUTTON_Y = RENAME_Y + 16;

    /** 右栏（预览）的起点。 */
    private static final int RIGHT_X = PAD + LIST_WIDTH + 6;
    private static final int RIGHT_WIDTH = GUI_WIDTH - RIGHT_X - PAD;
    private static final int GRID_Y = LIST_Y;
    private static final int CELL = 18;
    private static final int MANIFEST_Y = GRID_Y + ReactorLayout.ROWS * CELL + 6;

    private static final int RIGHT_ROW1_Y = RENAME_Y;
    private static final int RIGHT_ROW2_Y = LEFT_BUTTON_Y;
    private static final int RIGHT_BUTTON_WIDTH = (RIGHT_WIDTH - 4) / 2;

    // ---- 按钮编号 ----
    private static final int BTN_DEPLOY = 0;
    private static final int BTN_SHELL = 1;
    private static final int BTN_SAVE_CURRENT = 2;
    private static final int BTN_NBT_TOGGLE = 3;
    private static final int BTN_RENAME = 4;
    private static final int BTN_DELETE = 5;

    private static final int COLOR_PANEL = 0xC0101010;
    private static final int COLOR_BORDER = 0xFF808080;
    private static final int COLOR_ROW_ALT = 0x20FFFFFF;
    private static final int COLOR_ROW_HOVER = 0x40FFFFFF;
    private static final int COLOR_SELECTED = 0x5030A030;
    private static final int COLOR_FLUID = 0xFF40A0FF;
    private static final int COLOR_SOLID = 0xFF50C050;
    private static final int COLOR_OK = 0xFF55FF55;

    /** 打开方式：带部署目标的坐标；{@code hasTarget=false} 时忽略坐标。 */
    private final boolean hasTarget;
    private final int targetX;
    private final int targetY;
    private final int targetZ;

    private int guiLeft;
    private int guiTop;
    private FutaSearchField renameField;
    private GuiButton deployButton;
    private GuiButton shellButton;
    private GuiButton saveCurrentButton;
    private GuiButton renameButton;
    private GuiButton deleteButton;

    /** NBT 宽松匹配开关（部署取料是否接受不同损耗/NBT 的同类元件顶替）。 */
    private boolean looseNbt = true;
    /** 当前选中的布局 id；-1 = 无。 */
    private long selectedId = -1L;
    private int scrollRow;
    /** 删除按钮的两段式确认：第一次点变成「确认删除？」，换选中/关界面复位。 */
    private boolean deleteArmed;
    /** 上次重绘列表时的缓存版本。 */
    private int lastRevision = -1;
    private boolean viewDirty = true;

    /** 过滤无关状态：可见列表直接用服务端顺序（档案通常几十条，不值得做搜索）。 */
    private final List<ClientReactorLayoutCache.Summary> visible = new ArrayList<>();

    public GuiReactorLayoutBrowser(boolean hasTarget, int x, int y, int z) {
        this.hasTarget = hasTarget;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
    }

    @Override
    public boolean doesGuiPauseGame() {
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

        Keyboard.enableRepeatEvents(true);

        String previous = renameField == null ? "" : renameField.getText();
        renameField = new FutaSearchField(
            fontRendererObj,
            guiLeft + PAD + 2,
            guiTop + RENAME_Y + 2,
            LIST_WIDTH - 4,
            12);
        renameField.setText(previous, false);
        renameField.setFocused(false);

        buttonList.clear();
        int col2 = guiLeft + RIGHT_X + RIGHT_BUTTON_WIDTH + 4;
        int btnW = RIGHT_BUTTON_WIDTH;
        deployButton = addButton(
            BTN_DEPLOY,
            guiLeft + RIGHT_X,
            guiTop + RIGHT_ROW1_Y,
            btnW,
            tr("futa_gtnh.gui.reactor.deploy"));
        shellButton = addButton(BTN_SHELL, col2, guiTop + RIGHT_ROW1_Y, btnW, tr("futa_gtnh.gui.reactor.shell"));
        saveCurrentButton = addButton(
            BTN_SAVE_CURRENT,
            guiLeft + RIGHT_X,
            guiTop + RIGHT_ROW2_Y,
            btnW,
            tr("futa_gtnh.gui.reactor.save_current"));
        addButton(BTN_NBT_TOGGLE, col2, guiTop + RIGHT_ROW2_Y, btnW, nbtLabel());

        int half = (LIST_WIDTH - 4) / 2;
        renameButton = addButton(
            BTN_RENAME,
            guiLeft + PAD,
            guiTop + LEFT_BUTTON_Y,
            half,
            tr("futa_gtnh.gui.reactor.rename"));
        deleteButton = addButton(BTN_DELETE, guiLeft + PAD + half + 4, guiTop + LEFT_BUTTON_Y, half, deleteLabel());

        // 打开界面 = 向服务端要一份列表（同时登记为 viewer，之后的改动会推过来）
        NetworkHandler.INSTANCE.sendToServer(new PacketReactorLayoutAction(PacketReactorLayoutAction.SYNC));
        viewDirty = true;
    }

    private GuiButton addButton(int id, int x, int y, int width, String label) {
        GuiButton button = new com.futa_gtnh.client.GuiSmallButton(id, x, y, width, 16, label);
        buttonList.add(button);
        return button;
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        NetworkHandler.INSTANCE.sendToServer(new PacketReactorLayoutAction(PacketReactorLayoutAction.SYNC_LEAVE));
    }

    // ==================================================================
    // 每 tick
    // ==================================================================

    @Override
    public void updateScreen() {
        super.updateScreen();
        renameField.updateCursorCounter();

        if (viewDirty || lastRevision != ClientReactorLayoutCache.revision) {
            viewDirty = false;
            lastRevision = ClientReactorLayoutCache.revision;
            rebuildVisible();
            // 选中的布局被删了就清掉选中态
            if (selectedId >= 0 && ClientReactorLayoutCache.get(selectedId) == null) {
                select(-1L);
            }
        }

        ClientReactorLayoutCache.Summary selected = selectedId >= 0 ? ClientReactorLayoutCache.get(selectedId) : null;
        boolean hasSelection = selected != null;

        deployButton.enabled = hasTarget && hasSelection;
        shellButton.enabled = hasTarget;
        saveCurrentButton.enabled = hasTarget;
        renameButton.enabled = hasSelection && renameField != null
            && !renameField.getText()
                .trim()
                .isEmpty();
        deleteButton.enabled = hasSelection;
        deleteButton.displayString = deleteLabel();

        // 按钮标签里的开关状态可能被自己改过
        String nbtLabel = nbtLabel();
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            if (button.id == BTN_NBT_TOGGLE && !nbtLabel.equals(button.displayString)) {
                button.displayString = nbtLabel;
            }
        }
    }

    private void rebuildVisible() {
        visible.clear();
        Collection<ClientReactorLayoutCache.Summary> summaries = ClientReactorLayoutCache.snapshot();
        visible.addAll(summaries);
        int maxScroll = Math.max(0, visible.size() - LIST_ROWS);
        scrollRow = Math.min(scrollRow, maxScroll);
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        com.futa_gtnh.client.ui.FutaUiTheme.panel(guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT);
        drawBorder(guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT);

        fontRendererObj
            .drawStringWithShadow(tr("futa_gtnh.gui.reactor.title"), guiLeft + PAD + 3, guiTop + TITLE_Y, 0xFFFFFF);
        drawTargetLine();

        drawList(mouseX, mouseY);
        drawRenameBox();
        drawPreview();
        drawManifest();

        super.drawScreen(mouseX, mouseY, partialTicks);
        renameField.drawTextBox();

        List<String> tooltip = tooltipAt(mouseX, mouseY);
        if (tooltip != null) {
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        }
    }

    private void drawTargetLine() {
        String text;
        if (hasTarget) {
            text = EnumChatFormatting.GREEN
                + StatCollector.translateToLocalFormatted("futa_gtnh.gui.reactor.target", targetX, targetY, targetZ);
        } else {
            text = EnumChatFormatting.GRAY + tr("futa_gtnh.gui.reactor.no_target");
        }
        fontRendererObj.drawStringWithShadow(text, guiLeft + PAD + 3, guiTop + TARGET_Y, 0xFFFFFF);
    }

    private void drawList(int mouseX, int mouseY) {
        int listX = guiLeft + PAD;
        int listW = LIST_WIDTH;

        drawRect(listX, guiTop + LIST_Y, listX + listW, guiTop + LIST_Y + LIST_HEIGHT, 0xFF000000);
        drawBorder(listX, guiTop + LIST_Y, listW, LIST_HEIGHT);

        for (int row = 0; row < LIST_ROWS; row++) {
            int index = scrollRow + row;
            if (index >= visible.size()) break;

            ClientReactorLayoutCache.Summary summary = visible.get(index);
            int rowY = guiTop + LIST_Y + row * ROW_HEIGHT;
            boolean hovered = mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            boolean selected = summary.id == selectedId;
            if (selected) {
                drawRect(listX + 1, rowY + 1, listX + listW - 1, rowY + ROW_HEIGHT - 1, COLOR_SELECTED);
            } else if (row % 2 == 1) {
                drawRect(listX + 1, rowY + 1, listX + listW - 1, rowY + ROW_HEIGHT - 1, COLOR_ROW_ALT);
            }
            if (hovered) {
                drawRect(listX + 1, rowY + 1, listX + listW - 1, rowY + ROW_HEIGHT - 1, COLOR_ROW_HOVER);
            }

            // 类型色块：蓝=流体，绿=固体
            drawRect(listX + 3, rowY + 4, listX + 9, rowY + 10, summary.fluidCooled ? COLOR_FLUID : COLOR_SOLID);

            String name = summary.name.isEmpty() ? ("#" + summary.id) : summary.name;
            name = fontRendererObj.trimStringToWidth(name, listW - 30);
            fontRendererObj.drawStringWithShadow(name, listX + 13, rowY + 3, 0xFFFFFF);

            String cols = summary.columns + "×6";
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GRAY + cols,
                listX + listW - 4 - fontRendererObj.getStringWidth(cols),
                rowY + 3,
                0xFFFFFF);
        }

        // 滚动条
        if (visible.size() > LIST_ROWS) {
            int trackX = listX + listW - 5;
            drawRect(trackX, guiTop + LIST_Y + 1, trackX + 3, guiTop + LIST_Y + LIST_HEIGHT - 1, 0x40FFFFFF);
            int thumbHeight = Math.max(8, LIST_HEIGHT * LIST_ROWS / visible.size());
            int maxScroll = visible.size() - LIST_ROWS;
            int thumbY = guiTop + LIST_Y + 1 + (LIST_HEIGHT - 2 - thumbHeight) * scrollRow / maxScroll;
            drawRect(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFFA0A0A0);
        }

        if (visible.isEmpty()) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GRAY + tr("futa_gtnh.gui.reactor.empty"),
                listX + 4,
                guiTop + LIST_Y + 6,
                0xFFFFFF);
        }
    }

    private void drawRenameBox() {
        int boxX = guiLeft + PAD;
        int boxY = guiTop + RENAME_Y;
        drawRect(boxX, boxY, boxX + LIST_WIDTH, boxY + 16, 0xFF000000);
        drawBorder(boxX, boxY, LIST_WIDTH, 16);
        if (renameField.isFocused()) {
            drawBorder(boxX - 1, boxY - 1, LIST_WIDTH + 2, 18, COLOR_OK);
        }
    }

    /** 6×N 网格预览（GuiLootMachine 的绘制套路：标准物品光照 + zLevel 抬高 + 状态复原）。 */
    private void drawPreview() {
        int gridX = guiLeft + RIGHT_X;
        int gridTop = guiTop + GRID_Y;

        // 每格的底框始终画（空格也是格），列数按预览数据来
        int columns = previewColumns();
        for (int row = 0; row < ReactorLayout.ROWS; row++) {
            for (int column = 0; column < columns; column++) {
                int x = gridX + column * CELL;
                int y = gridTop + row * CELL;
                drawRect(x, y, x + CELL, y + CELL, 0xFF8B8B8B);
                drawRect(x + 1, y + 1, x + CELL - 1, y + CELL - 1, 0xFF373737);
            }
        }

        ItemStack[] stacks = ClientReactorLayoutCache.previewStacks;
        if (stacks.length == 0 || ClientReactorLayoutCache.previewId != selectedId) return;

        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        itemRender.zLevel = 100.0F;
        for (int row = 0; row < ReactorLayout.ROWS; row++) {
            for (int column = 0; column < columns; column++) {
                ItemStack stack = stacks[row * columns + column];
                if (stack == null) continue;
                int x = gridX + column * CELL + 1;
                int y = gridTop + row * CELL + 1;
                try {
                    itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, x, y);
                    itemRender.renderItemOverlayIntoGUI(fontRendererObj, mc.getTextureManager(), stack, x, y);
                } catch (Throwable ignored) {
                    // 一个画不出来的物品不该拖垮整个预览
                }
            }
        }
        itemRender.zLevel = 0.0F;
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        RenderHelper.disableStandardItemLighting();
    }

    private int previewColumns() {
        int columns = selectedId >= 0 && ClientReactorLayoutCache.previewId == selectedId
            ? ClientReactorLayoutCache.previewColumns
            : (selectedId >= 0 && ClientReactorLayoutCache.get(selectedId) != null
                ? ClientReactorLayoutCache.get(selectedId).columns
                : ReactorLayout.MIN_COLUMNS);
        return Math.max(ReactorLayout.MIN_COLUMNS, Math.min(ReactorLayout.MAX_COLUMNS, columns));
    }

    private void drawManifest() {
        int x = guiLeft + RIGHT_X;
        int y = guiTop + MANIFEST_Y;
        int maxLines = (RIGHT_ROW1_Y - 4 - MANIFEST_Y) / 12;

        if (selectedId < 0) {
            fontRendererObj
                .drawStringWithShadow(EnumChatFormatting.GRAY + tr("futa_gtnh.gui.reactor.pick_hint"), x, y, 0xFFFFFF);
            return;
        }
        if (ClientReactorLayoutCache.previewId != selectedId) {
            fontRendererObj
                .drawStringWithShadow(EnumChatFormatting.GRAY + tr("futa_gtnh.gui.reactor.loading"), x, y, 0xFFFFFF);
            return;
        }

        List<ClientReactorLayoutCache.ManifestLine> lines = ClientReactorLayoutCache.previewManifest;
        for (int i = 0; i < lines.size() && i < maxLines; i++) {
            ClientReactorLayoutCache.ManifestLine line = lines.get(i);
            int available = line.havePlayer + line.haveStorage;
            EnumChatFormatting color = available >= line.needed
                ? (line.exact ? EnumChatFormatting.GREEN : EnumChatFormatting.YELLOW)
                : EnumChatFormatting.RED;
            String text = color + line.displayName
                + EnumChatFormatting.WHITE
                + " ×"
                + line.needed
                + EnumChatFormatting.GRAY
                + "  "
                + tr("futa_gtnh.gui.reactor.manifest.player")
                + " "
                + (available >= line.needed && line.havePlayer > 0 ? EnumChatFormatting.WHITE : EnumChatFormatting.GRAY)
                + line.havePlayer
                + EnumChatFormatting.GRAY
                + " "
                + tr("futa_gtnh.gui.reactor.manifest.storage")
                + " "
                + (available >= line.needed && line.haveStorage > 0 ? EnumChatFormatting.WHITE
                    : EnumChatFormatting.GRAY)
                + line.haveStorage;
            fontRendererObj
                .drawStringWithShadow(fontRendererObj.trimStringToWidth(text, RIGHT_WIDTH), x, y + i * 12, 0xFFFFFF);
        }
        if (lines.size() > maxLines) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GRAY + StatCollector
                    .translateToLocalFormatted("futa_gtnh.gui.reactor.manifest.more", lines.size() - maxLines),
                x,
                y + maxLines * 12,
                0xFFFFFF);
        }
    }

    private List<String> tooltipAt(int mouseX, int mouseY) {
        List<String> lines = new ArrayList<>();

        // 网格悬浮：那格元件的完整 tooltip
        ItemStack[] stacks = ClientReactorLayoutCache.previewStacks;
        if (ClientReactorLayoutCache.previewId == selectedId && stacks.length > 0) {
            int column = (mouseX - guiLeft - RIGHT_X) / CELL;
            int row = (mouseY - guiTop - GRID_Y) / CELL;
            int columns = previewColumns();
            if (column >= 0 && column < columns && row >= 0 && row < ReactorLayout.ROWS) {
                int x = guiLeft + RIGHT_X + column * CELL;
                int y = guiTop + GRID_Y + row * CELL;
                if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
                    ItemStack stack = stacks[row * columns + column];
                    if (stack != null) {
                        lines.addAll(stack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips));
                    }
                }
            }
        }

        // 需求清单悬浮：这一行的可用量拆解与顶替说明
        ClientReactorLayoutCache.ManifestLine manifest = manifestAt(mouseX, mouseY);
        if (manifest != null) {
            if (!lines.isEmpty()) lines.add("");
            lines.add(EnumChatFormatting.GOLD + manifest.displayName);
            int available = manifest.havePlayer + manifest.haveStorage;
            lines.add(
                EnumChatFormatting.GRAY + tr("futa_gtnh.gui.reactor.manifest.need")
                    + " "
                    + EnumChatFormatting.WHITE
                    + manifest.needed
                    + EnumChatFormatting.GRAY
                    + " / "
                    + tr("futa_gtnh.gui.reactor.manifest.have")
                    + " "
                    + (available >= manifest.needed ? EnumChatFormatting.GREEN : EnumChatFormatting.RED)
                    + available);
            lines
                .add(EnumChatFormatting.GRAY + tr("futa_gtnh.gui.reactor.manifest.player") + " " + manifest.havePlayer);
            lines.add(
                EnumChatFormatting.GRAY + tr("futa_gtnh.gui.reactor.manifest.storage") + " " + manifest.haveStorage);
            lines.add(
                manifest.exact ? EnumChatFormatting.GREEN + tr("futa_gtnh.gui.reactor.manifest.exact")
                    : EnumChatFormatting.YELLOW + tr("futa_gtnh.gui.reactor.manifest.substituted"));
        }

        if (mouseX >= guiLeft + PAD && mouseX < guiLeft + PAD + LIST_WIDTH
            && mouseY >= guiTop + LIST_Y
            && mouseY < guiTop + LIST_Y + LIST_HEIGHT
            && lines.isEmpty()) {
            ClientReactorLayoutCache.Summary summary = entryAt(mouseX, mouseY);
            if (summary != null) {
                lines.add(EnumChatFormatting.GOLD + summary.name);
                lines.add(
                    EnumChatFormatting.GRAY + tr(
                        summary.fluidCooled ? "futa_gtnh.gui.reactor.type.fluid" : "futa_gtnh.gui.reactor.type.solid")
                        + " · "
                        + summary.columns
                        + "×6 · "
                        + StatCollector.translateToLocalFormatted("futa_gtnh.gui.reactor.list.used", summary.used));
            }
        }
        return lines.isEmpty() ? null : lines;
    }

    private ClientReactorLayoutCache.ManifestLine manifestAt(int mouseX, int mouseY) {
        if (selectedId < 0 || ClientReactorLayoutCache.previewId != selectedId) return null;
        int x = guiLeft + RIGHT_X;
        int y = guiTop + MANIFEST_Y;
        if (mouseX < x || mouseX >= x + RIGHT_WIDTH) return null;
        int index = (mouseY - y) / 12;
        List<ClientReactorLayoutCache.ManifestLine> lines = ClientReactorLayoutCache.previewManifest;
        if (index < 0 || index >= lines.size()) return null;
        return lines.get(index);
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (renameField.contains(mouseX, mouseY)) {
            renameField.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        ClientReactorLayoutCache.Summary summary = entryAt(mouseX, mouseY);
        if (summary != null) {
            select(summary.id);
            return;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (renameField != null) {
            renameField.setFocused(false);
        }
    }

    private void select(long id) {
        if (selectedId == id) return;
        selectedId = id;
        deleteArmed = false;
        ClientReactorLayoutCache.clearPreview();
        if (id >= 0) {
            NetworkHandler.INSTANCE.sendToServer(PacketReactorLayoutAction.preview(id, looseNbt));
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();

        int delta = Mouse.getEventDWheel();
        if (delta == 0) return;

        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (!isInsideList(mouseX, mouseY)) return;

        int maxScroll = Math.max(0, visible.size() - LIST_ROWS);
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow + (delta > 0 ? -1 : 1)));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // 注意：1.7.10 的 GuiScreen#keyTyped 不声明 throws，覆写时不能加 throws IOException
        if (renameField != null && renameField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            scrollRow = Math.max(0, scrollRow - LIST_ROWS);
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            scrollRow = Math.min(Math.max(0, visible.size() - LIST_ROWS), scrollRow + LIST_ROWS);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /**
     * 旧输入层兜底（与 GuiMinigameHelper 相同的说明：lwjgl3ify 下不走这条路，
     * 否则会重复插入输入法文字）。
     */
    @Override
    public void handleKeyboardInput() {
        if (!ImeCompat.hasNativeIme()) {
            char injected = Keyboard.getEventCharacter();
            if (!Keyboard.getEventKeyState() && injected > 255) {
                this.keyTyped(injected, 0);
            }
        }
        super.handleKeyboardInput();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case BTN_DEPLOY:
                if (hasTarget && selectedId >= 0) {
                    NetworkHandler.INSTANCE.sendToServer(
                        PacketReactorLayoutAction.withTarget(
                            PacketReactorLayoutAction.DEPLOY,
                            targetX,
                            targetY,
                            targetZ,
                            selectedId,
                            looseNbt));
                }
                break;
            case BTN_SHELL:
                if (hasTarget) {
                    NetworkHandler.INSTANCE.sendToServer(
                        PacketReactorLayoutAction
                            .withTarget(PacketReactorLayoutAction.BUILD_SHELL, targetX, targetY, targetZ, -1L, false));
                }
                break;
            case BTN_SAVE_CURRENT:
                if (hasTarget) {
                    NetworkHandler.INSTANCE.sendToServer(
                        PacketReactorLayoutAction
                            .withTarget(PacketReactorLayoutAction.SAVE_CURRENT, targetX, targetY, targetZ, -1L, false));
                }
                break;
            case BTN_NBT_TOGGLE:
                looseNbt = !looseNbt;
                button.displayString = nbtLabel();
                if (selectedId >= 0) {
                    // 开关影响清单口径，重取一份预览
                    NetworkHandler.INSTANCE.sendToServer(PacketReactorLayoutAction.preview(selectedId, looseNbt));
                }
                break;
            case BTN_RENAME:
                if (selectedId >= 0 && renameField != null) {
                    String name = renameField.getText()
                        .trim();
                    if (!name.isEmpty()) {
                        NetworkHandler.INSTANCE.sendToServer(PacketReactorLayoutAction.rename(selectedId, name));
                    }
                }
                break;
            case BTN_DELETE:
                if (selectedId >= 0) {
                    if (deleteArmed) {
                        NetworkHandler.INSTANCE.sendToServer(PacketReactorLayoutAction.delete(selectedId));
                        deleteArmed = false;
                        select(-1L);
                    } else {
                        deleteArmed = true;
                    }
                }
                break;
            default:
                break;
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private boolean isInsideList(int mouseX, int mouseY) {
        return mouseX >= guiLeft + PAD && mouseX < guiLeft + PAD + LIST_WIDTH
            && mouseY >= guiTop + LIST_Y
            && mouseY < guiTop + LIST_Y + LIST_HEIGHT;
    }

    private ClientReactorLayoutCache.Summary entryAt(int mouseX, int mouseY) {
        if (!isInsideList(mouseX, mouseY)) return null;
        int row = (mouseY - guiTop - LIST_Y) / ROW_HEIGHT;
        int index = scrollRow + row;
        return index >= 0 && index < visible.size() ? visible.get(index) : null;
    }

    private String nbtLabel() {
        return StatCollector.translateToLocalFormatted(
            "futa_gtnh.gui.reactor.nbt",
            tr(looseNbt ? "futa_gtnh.gui.reactor.nbt.on" : "futa_gtnh.gui.reactor.nbt.off"));
    }

    private String deleteLabel() {
        return tr(deleteArmed ? "futa_gtnh.gui.reactor.delete_confirm" : "futa_gtnh.gui.reactor.delete");
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

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
