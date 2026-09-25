package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.futa_gtnh.Config;
import com.futa_gtnh.lootassist.LootassistEntry;
import com.futa_gtnh.lootassist.LootgamesCompat;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketLootassistAction;

/**
 * 小游戏助手界面：全服共享的 lootgames 地牢清单。
 *
 * <p>
 * 和寻物魔杖一样是纯客户端 {@code GuiScreen}（没有槽位，一切操作走显式网络包）：
 *
 * <ul>
 * <li><b>打开时</b>发 {@code SYNC} 包 —— 服务端登记 viewer（后续增量会推过来）
 * 并回一份全量快照；</li>
 * <li><b>「搜索附近」</b>发 {@code SEARCH} 包 —— 服务端按发起者位置推算候选点，
 * 立刻进共享列表（待验证），然后逐个加载区块确认，进度包实时刷新；</li>
 * <li><b>点一行</b>切换「已完成」标记（全服共享，会显示标记人）。</li>
 * </ul>
 *
 * <p>
 * 每条记录的主点位是<b>地表入口</b>（地牢生成时挖穿地表的那个洞口，玩家导航用），
 * 悬浮提示里带地下主方块坐标。
 */
public class GuiMinigameHelper extends GuiScreen {

    // ---- 布局 ----
    private static final int GUI_WIDTH = 330;
    private static final int GUI_HEIGHT = 196;
    private static final int PAD = 6;
    private static final int TITLE_Y = 5;
    private static final int SEARCH_Y = 22;
    private static final int LIST_Y = 42;
    private static final int ROW_HEIGHT = 14;
    private static final int ROWS = 8;
    private static final int LIST_HEIGHT = ROWS * ROW_HEIGHT;
    private static final int FOOTER_Y = LIST_Y + LIST_HEIGHT + 4;

    private static final int BTN_SEARCH = 0;

    private static final int COLOR_PANEL = 0xC0101010;
    private static final int COLOR_BORDER = 0xFF808080;
    private static final int COLOR_ROW_ALT = 0x20FFFFFF;
    private static final int COLOR_ROW_HOVER = 0x40FFFFFF;
    private static final int COLOR_DONE = 0xFF55FF55;
    private static final int COLOR_VERIFIED = 0xFFAAAAAA;
    private static final int COLOR_PENDING = 0xFFDDA0DD;
    private static final int COLOR_PROGRESS = 0xFF55FF55;

    /** 过滤 + 排序后的可见行。 */
    private final List<LootassistEntry> visible = new ArrayList<>();

    private int guiLeft;
    private int guiTop;
    private GuiTextField filterField;
    private GuiButton searchButton;
    private int scrollRow;
    private boolean viewDirty = true;
    /** 上次构建可见列表时的缓存版本号；变了才重排。 */
    private int lastRevision = -1;
    private int lastPlayerDim = Integer.MIN_VALUE;

    @Override
    public boolean doesGuiPauseGame() {
        // 地牢清单是边跑图边看的东西，不暂停游戏
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

        String previous = filterField == null ? "" : filterField.getText();

        filterField = new GuiTextField(
            fontRendererObj,
            guiLeft + PAD + 3,
            guiTop + SEARCH_Y + 3,
            GUI_WIDTH - 2 * PAD - 6,
            12);
        filterField.setMaxStringLength(64);
        filterField.setEnableBackgroundDrawing(false);
        filterField.setText(previous);
        filterField.setFocused(false);
        Keyboard.enableRepeatEvents(true);

        buttonList.clear();
        searchButton = new com.futa_gtnh.client.GuiSmallButton(
            BTN_SEARCH,
            guiLeft + GUI_WIDTH - PAD - 90,
            guiTop + TITLE_Y - 1,
            90,
            16,
            StatCollector.translateToLocalFormatted("futa_gtnh.gui.lootassist.search", Config.lootassistSearchRadius));
        buttonList.add(searchButton);

        // 打开界面 = 向服务端要一份快照（同时登记为 viewer，之后的增量会推过来）
        NetworkHandler.INSTANCE.sendToServer(new PacketLootassistAction(PacketLootassistAction.SYNC));
        viewDirty = true;
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
    }

    // ==================================================================
    // 每 tick
    // ==================================================================

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (filterField != null) {
            filterField.updateCursorCounter();
        }

        int playerDim = mc == null || mc.thePlayer == null ? Integer.MIN_VALUE : mc.thePlayer.dimension;
        // 数据变了 / 换了维度（排序按距离）才重排；玩家走动引起的距离漂移不重排，
        // 否则列表会在脚下一直跳动没法点
        if (viewDirty || lastRevision != ClientLootassistCache.revision || lastPlayerDim != playerDim) {
            viewDirty = false;
            lastRevision = ClientLootassistCache.revision;
            lastPlayerDim = playerDim;
            rebuildVisible();
        }

        if (searchButton != null) {
            searchButton.enabled = !ClientLootassistCache.searching;
            String label = StatCollector.translateToLocalFormatted(
                ClientLootassistCache.searching ? "futa_gtnh.gui.lootassist.searching"
                    : "futa_gtnh.gui.lootassist.search",
                Config.lootassistSearchRadius);
            if (!label.equals(searchButton.displayString)) {
                searchButton.displayString = label;
            }
        }
    }

    private void rebuildVisible() {
        visible.clear();
        String query = filterField == null ? ""
            : filterField.getText()
                .trim()
                .toLowerCase(Locale.ROOT);

        for (LootassistEntry entry : ClientLootassistCache.snapshot()) {
            if (!query.isEmpty() && !matches(entry, query)) continue;
            visible.add(entry);
        }

        visible.sort((a, b) -> {
            // 当前维度的排前面（按到入口的距离），其他维度按坐标排
            int dim = mc.thePlayer.dimension;
            if (a.dim != b.dim) return a.dim == dim ? -1 : (b.dim == dim ? 1 : Integer.compare(a.dim, b.dim));
            if (a.dim == dim) {
                return Double.compare(distanceSq(a), distanceSq(b));
            }
            return Integer.compare(a.x, b.x);
        });

        int maxScroll = Math.max(0, visible.size() - ROWS);
        scrollRow = Math.min(scrollRow, maxScroll);
    }

    private boolean matches(LootassistEntry entry, String query) {
        String haystack = entry.key() + " "
            + entry.entranceX
            + " "
            + entry.entranceY
            + " "
            + entry.entranceZ
            + " "
            + entry.completedBy;
        return haystack.toLowerCase(Locale.ROOT)
            .contains(query);
    }

    private double distanceSq(LootassistEntry entry) {
        if (mc == null || mc.thePlayer == null) return 0;
        double dx = entry.entranceX >= 0 ? entry.entranceX : entry.x;
        double dz = entry.entranceZ >= 0 ? entry.entranceZ : entry.z;
        double px = mc.thePlayer.posX - dx;
        double pz = mc.thePlayer.posZ - dz;
        return px * px + pz * pz;
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawRect(guiLeft, guiTop, guiLeft + GUI_WIDTH, guiTop + GUI_HEIGHT, COLOR_PANEL);
        drawBorder(guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT);

        fontRendererObj.drawStringWithShadow(
            tr("futa_gtnh.gui.lootassist.title"),
            guiLeft + PAD + 3,
            guiTop + TITLE_Y + 2,
            0xFFFFFF);

        drawFilterBox();
        drawList(mouseX, mouseY);
        drawFooter();

        super.drawScreen(mouseX, mouseY, partialTicks);
        if (filterField != null) {
            filterField.drawTextBox();
        }

        List<String> tooltip = tooltipAt(mouseX, mouseY);
        if (tooltip != null) {
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        }
    }

    private void drawFilterBox() {
        int boxX = guiLeft + PAD;
        int boxY = guiTop + SEARCH_Y;
        int boxW = GUI_WIDTH - 2 * PAD;
        drawRect(boxX, boxY, boxX + boxW, boxY + 16, 0xFF000000);
        drawBorder(boxX, boxY, boxW, 16);
        if (filterField != null && filterField.isFocused()) {
            drawBorder(boxX - 1, boxY - 1, boxW + 2, 18, COLOR_DONE);
        }
        if (filterField != null && filterField.getText()
            .isEmpty()) {
            fontRendererObj.drawString(
                EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.lootassist.filter.hint"),
                boxX + 4,
                boxY + 5,
                0xFFFFFF);
        }
    }

    private void drawList(int mouseX, int mouseY) {
        int listX = guiLeft + PAD;
        int listW = GUI_WIDTH - 2 * PAD;

        drawRect(listX, guiTop + LIST_Y, listX + listW, guiTop + LIST_Y + LIST_HEIGHT, 0xFF000000);
        drawBorder(listX, guiTop + LIST_Y, listW, LIST_HEIGHT);

        for (int row = 0; row < ROWS; row++) {
            int index = scrollRow + row;
            if (index >= visible.size()) break;

            LootassistEntry entry = visible.get(index);
            int rowY = guiTop + LIST_Y + row * ROW_HEIGHT;
            boolean hovered = mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT;
            if (row % 2 == 1) {
                drawRect(listX + 1, rowY + 1, listX + listW - 1, rowY + ROW_HEIGHT - 1, COLOR_ROW_ALT);
            }
            if (hovered) {
                drawRect(listX + 1, rowY + 1, listX + listW - 1, rowY + ROW_HEIGHT - 1, COLOR_ROW_HOVER);
            }

            // 状态色块：绿=已完成，灰=已验证，紫=待验证
            int statusColor = entry.completed ? COLOR_DONE : (entry.verified() ? COLOR_VERIFIED : COLOR_PENDING);
            drawRect(listX + 4, rowY + 4, listX + 10, rowY + 10, statusColor);

            int dim = mc == null || mc.thePlayer == null ? 0 : mc.thePlayer.dimension;
            StringBuilder line = new StringBuilder();
            line.append("dim ")
                .append(entry.dim)
                .append("  ");
            if (entry.entranceZ >= 0) {
                line.append(EnumChatFormatting.GRAY)
                    .append(tr("futa_gtnh.gui.lootassist.entrance"))
                    .append(" ");
            }
            line.append(EnumChatFormatting.WHITE)
                .append(entry.entranceX >= 0 ? entry.entranceX : entry.x)
                .append(", ")
                .append(entry.entranceY >= 0 ? entry.entranceY : entry.y)
                .append(", ")
                .append(entry.entranceZ >= 0 ? entry.entranceZ : entry.z);

            if (entry.completed) {
                line.append(EnumChatFormatting.GREEN)
                    .append("  ")
                    .append(tr("futa_gtnh.gui.lootassist.done"));
                if (!entry.completedBy.isEmpty()) {
                    line.append(" · ")
                        .append(entry.completedBy);
                }
            }
            fontRendererObj
                .drawStringWithShadow(line.toString(), listX + 14, rowY + 3, entry.completed ? COLOR_DONE : 0xFFFFFF);

            // 同维度时右对齐显示距离
            if (entry.dim == dim && mc != null && mc.thePlayer != null) {
                int dist = Math.round((float) Math.sqrt(distanceSq(entry)));
                String distText = dist + "m";
                fontRendererObj.drawStringWithShadow(
                    EnumChatFormatting.GRAY + distText,
                    listX + listW - 4 - fontRendererObj.getStringWidth(distText),
                    rowY + 3,
                    0xFFFFFF);
            }
        }

        // 滚动条
        if (visible.size() > ROWS) {
            int trackX = listX + listW - 5;
            drawRect(trackX, guiTop + LIST_Y + 1, trackX + 3, guiTop + LIST_Y + LIST_HEIGHT - 1, 0x40FFFFFF);
            int thumbHeight = Math.max(8, LIST_HEIGHT * ROWS / visible.size());
            int maxScroll = visible.size() - ROWS;
            int thumbY = guiTop + LIST_Y + 1 + (LIST_HEIGHT - 2 - thumbHeight) * scrollRow / maxScroll;
            drawRect(trackX, thumbY, trackX + 3, thumbY + thumbHeight, 0xFFA0A0A0);
        }
    }

    private void drawFooter() {
        int y = guiTop + FOOTER_Y;
        int x = guiLeft + PAD + 3;

        if (!LootgamesCompat.isAvailable()) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.RED + tr("futa_gtnh.gui.lootassist.no_lootgames"),
                x,
                y,
                0xFFFFFF);
            return;
        }

        if (ClientLootassistCache.searching) {
            String text = StatCollector.translateToLocalFormatted(
                "futa_gtnh.gui.lootassist.progress",
                ClientLootassistCache.progressDone,
                ClientLootassistCache.progressTotal,
                ClientLootassistCache.progressFound);
            fontRendererObj.drawStringWithShadow(EnumChatFormatting.YELLOW + text, x, y, 0xFFFFFF);
            // 细进度条
            float progress = ClientLootassistCache.progressTotal <= 0 ? 0
                : (float) ClientLootassistCache.progressDone / ClientLootassistCache.progressTotal;
            int barY = y + 11;
            drawRect(x, barY, x + GUI_WIDTH - 2 * PAD - 6, barY + 3, 0xFF303030);
            drawRect(x, barY, x + (int) (progress * (GUI_WIDTH - 2 * PAD - 6)), barY + 3, COLOR_PROGRESS);
            return;
        }

        if (visible.isEmpty() && ClientLootassistCache.isEmpty()) {
            fontRendererObj
                .drawStringWithShadow(EnumChatFormatting.GRAY + tr("futa_gtnh.gui.lootassist.empty"), x, y, 0xFFFFFF);
        } else {
            fontRendererObj.drawStringWithShadow(
                StatCollector.translateToLocalFormatted("futa_gtnh.gui.lootassist.count", visible.size()),
                x,
                y,
                0xA0A0A0);
        }
    }

    private List<String> tooltipAt(int mouseX, int mouseY) {
        LootassistEntry entry = entryAt(mouseX, mouseY);
        if (entry == null) return null;

        List<String> lines = new ArrayList<>();
        lines.add(EnumChatFormatting.GOLD + tr("futa_gtnh.gui.lootassist.title"));
        lines.add(
            EnumChatFormatting.GRAY + tr("futa_gtnh.gui.lootassist.entrance")
                + " "
                + EnumChatFormatting.WHITE
                + (entry.entranceX >= 0 ? entry.entranceX + ", " + entry.entranceY + ", " + entry.entranceZ
                    : tr("futa_gtnh.gui.lootassist.unknown")));
        lines.add(
            EnumChatFormatting.GRAY + tr("futa_gtnh.gui.lootassist.underground")
                + " "
                + EnumChatFormatting.WHITE
                + (entry.verified() ? entry.x + ", " + entry.y + ", " + entry.z
                    : tr("futa_gtnh.gui.lootassist.pending")));
        if (entry.completed) {
            lines.add(
                EnumChatFormatting.GREEN + tr("futa_gtnh.gui.lootassist.done")
                    + (entry.completedBy.isEmpty() ? "" : " · " + entry.completedBy));
        } else {
            lines.add(EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.lootassist.mark_tip"));
        }
        if (!entry.verified()) {
            lines.add(EnumChatFormatting.LIGHT_PURPLE + tr("futa_gtnh.gui.lootassist.pending_tip"));
        }
        return lines;
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (filterField != null && mouseX >= filterField.xPosition
            && mouseX < filterField.xPosition + filterField.width
            && mouseY >= filterField.yPosition
            && mouseY < filterField.yPosition + 12) {
            filterField.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        LootassistEntry entry = entryAt(mouseX, mouseY);
        if (entry != null) {
            if (filterField != null) filterField.setFocused(false);
            // 点一行 = 切换完成标记；本地先不动，等服务端增量包回来（回包快，不闪）
            NetworkHandler.INSTANCE.sendToServer(PacketLootassistAction.mark(entry.key(), !entry.completed));
            return;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (filterField != null) {
            filterField.setFocused(false);
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

        int maxScroll = Math.max(0, visible.size() - ROWS);
        scrollRow = Math.max(0, Math.min(maxScroll, scrollRow + (delta > 0 ? -1 : 1)));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // 注意：1.7.10 的 GuiScreen#keyTyped 不声明 throws，覆写时不能加 throws IOException
        if (filterField != null && filterField.textboxKeyTyped(typedChar, keyCode)) {
            viewDirty = true;
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            scrollRow = Math.max(0, scrollRow - ROWS);
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            scrollRow = Math.min(Math.max(0, visible.size() - ROWS), scrollRow + ROWS);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /**
     * 旧输入层兜底（与 {@link GuiLocatorWand} 相同的说明：lwjgl3ify 下不走这条路，
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
        if (button.id == BTN_SEARCH) {
            NetworkHandler.INSTANCE.sendToServer(new PacketLootassistAction(PacketLootassistAction.SEARCH));
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private boolean isInsideList(int mouseX, int mouseY) {
        return mouseX >= guiLeft + PAD && mouseX < guiLeft + GUI_WIDTH - PAD
            && mouseY >= guiTop + LIST_Y
            && mouseY < guiTop + LIST_Y + LIST_HEIGHT;
    }

    /** @return 鼠标下的条目；不在列表里或行外时返回 null */
    private LootassistEntry entryAt(int mouseX, int mouseY) {
        if (!isInsideList(mouseX, mouseY)) return null;
        int row = (mouseY - guiTop - LIST_Y) / ROW_HEIGHT;
        int index = scrollRow + row;
        return index >= 0 && index < visible.size() ? visible.get(index) : null;
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
