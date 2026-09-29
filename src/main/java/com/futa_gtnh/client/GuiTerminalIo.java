package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * 共享终端方块「六个面怎么主动搬东西」的配置界面。
 *
 * <p>
 * 是一个<b>独立界面</b>（不是终端界面的一个面板）：终端界面本来就挤满了，
 * 而这里要塞下 6 个面 × 2 种东西的方向，再加一整块筛选编辑区。
 *
 * <p>
 * 它<b>不新建容器</b>：终端界面那个容器一直开着（服务端的信任校验就是靠
 * 「玩家真的开着这个方块终端的界面」），这里只是换了个 {@code GuiScreen} 在上面画。
 * 关掉时回到终端界面，玩家感觉就是「进了个子页面」。
 *
 * <p>
 * 数据来源：方向、筛选全部读 {@link ClientTerminalIo} 里那份服务端推过来的快照；
 * 每改一下就整份发回服务端（{@code SET_TERMINAL_IO}），服务端立刻回一份权威值把它覆盖。
 */
public class GuiTerminalIo extends GuiScreen {

    private static final int GUI_WIDTH = 300;
    private static final int GUI_HEIGHT = 196;

    private static final int BTN_FACE_BASE = 0; // 0..11：六个面 × 物品/流体
    private static final int BTN_KIND = 20;
    private static final int BTN_CLEAR = 21;
    private static final int BTN_PRESET_BASE = 30; // 30..34：五个预设
    private static final int BTN_DONE = 40;

    /** 筛选网格的列数 / 行数。 */
    private static final int COLS = 8;
    private static final int ROWS = 4;
    private static final int CELL = 18;
    private static final int PAGE = COLS * ROWS;

    private final ContainerSharedTerminal container;

    private int guiLeft;
    private int guiTop;

    private GuiTextField searchField;
    private GuiTextField prefixField;

    /** 当前页签：物品还是流体。 */
    private boolean fluidTab;

    /** 筛选网格里这一帧显示的东西。 */
    private final List<StorageViewEntry> shown = new ArrayList<>();
    private final List<GuiButton> faceButtons = new ArrayList<>();
    private final List<GuiButton> presetButtons = new ArrayList<>();

    private String lastQuery = "\u0000";
    private boolean fluidTabCache;
    private int revision = -1;
    /** 筛选列表有没有建过：内容为空时也要记得「已经建过了」，不然每帧都重排一遍。 */
    private boolean built;

    public GuiTerminalIo(ContainerSharedTerminal container) {
        this.container = container;
    }

    /** 打开这个界面。带上容器是因为它是「服务端认不认这次改动」的凭据。 */
    public static void open(ContainerSharedTerminal container) {
        if (container == null) return;
        net.minecraft.client.Minecraft.getMinecraft()
            .displayGuiScreen(new GuiTerminalIo(container));
    }

    @Override
    public void initGui() {
        super.initGui();
        guiLeft = (width - GUI_WIDTH) / 2;
        guiTop = (height - GUI_HEIGHT) / 2;

        buttonList.clear();
        faceButtons.clear();
        presetButtons.clear();

        // ---- 六个面 × 物品/流体 ----
        int faceY = guiTop + 34;
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            int row = face.ordinal();
            GuiButton item = new GuiButton(BTN_FACE_BASE + row * 2, guiLeft + 52, faceY + row * 20, 58, 18, "");
            GuiButton fluid = new GuiButton(BTN_FACE_BASE + row * 2 + 1, guiLeft + 114, faceY + row * 20, 58, 18, "");
            faceButtons.add(item);
            faceButtons.add(fluid);
            buttonList.add(item);
            buttonList.add(fluid);
        }

        // ---- 筛选区 ----
        int filterX = guiLeft + 190;
        buttonList.add(new GuiButton(BTN_KIND, filterX, guiTop + 30, 96, 18, ""));
        searchField = new GuiTextField(fontRendererObj, guiLeft + 8, guiTop + 54, 174, 16);
        searchField.setMaxStringLength(48);
        searchField.setText(lastQuery.equals("\u0000") ? "" : lastQuery);

        int presetY = guiTop + 100;
        TerminalIoConfig.Preset[] presets = TerminalIoConfig.Preset.values();
        for (int i = 0; i < presets.length; i++) {
            int col = i % 3;
            int row = i / 3;
            GuiButton button = new GuiButton(BTN_PRESET_BASE + i, filterX + col * 34, presetY + row * 20, 32, 18, "");
            presetButtons.add(button);
            buttonList.add(button);
        }

        prefixField = new GuiTextField(fontRendererObj, filterX, guiTop + 150, 96, 16);
        prefixField.setMaxStringLength(64);
        prefixField.setText(joinPrefixes());

        buttonList.add(new GuiButton(BTN_CLEAR, filterX, guiTop + 170, 46, 18, ""));
        buttonList.add(new GuiButton(BTN_DONE, guiLeft + GUI_WIDTH - 54, guiTop + 170, 46, 18, ""));

        refreshFilter();
        refreshLabels();
    }

    private String joinPrefixes() {
        StringBuilder builder = new StringBuilder();
        for (String prefix : ClientTerminalIo.get()
            .getCustomPrefixes()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(prefix);
        }
        return builder.toString();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ==================================================================
    // 数据
    // ==================================================================

    /** @return 这份配置是不是就是这个终端推过来的（不是就别让玩家改） */
    private boolean configReady() {
        if (container == null || container.getTerminal() == null) return false;
        net.minecraft.tileentity.TileEntity terminal = container.getTerminal();
        return ClientTerminalIo.matches(
            terminal.getWorldObj() == null ? 0 : terminal.getWorldObj().provider.dimensionId,
            terminal.xCoord,
            terminal.yCoord,
            terminal.zCoord);
    }

    private void refreshFilter() {
        String query = searchField == null ? "" : searchField.getText();
        int now = ClientStorageCache.getRevision();
        if (built && now == revision && query.equals(lastQuery) && fluidTab == fluidTabCache) return;

        revision = now;
        lastQuery = query;
        fluidTabCache = fluidTab;
        built = true;

        List<StorageViewEntry> source = fluidTab ? ClientStorageCache.fluids() : ClientStorageCache.items();
        StorageSearch.filter(source, StorageSearch.compile(query), shown);
        StorageSort.sort(shown, StorageSort.BY_NAME);

        // 只显示第一页：这是一个「从仓库里挑几样」的选择器，
        // 真正要找什么用上面的搜索框（和终端界面同一套语法）
        while (shown.size() > PAGE) {
            shown.remove(shown.size() - 1);
        }
    }

    /** 整份发回服务端。服务端会回一份权威值，本地不用猜。 */
    private void pushConfig() {
        if (!configReady()) return;
        try {
            NetworkHandler.INSTANCE.sendToServer(
                PacketStorageAction.nbt(
                    PacketStorageAction.SET_TERMINAL_IO,
                    ClientTerminalIo.get()
                        .writeToNbt()));
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("共享存储：发送终端面配置失败", t);
        }
    }

    private void requestConfig() {
        try {
            NetworkHandler.INSTANCE.sendToServer(new PacketStorageAction(PacketStorageAction.REQUEST_TERMINAL_IO));
        } catch (Throwable ignored) {
            // 发不出去就显示「同步中」，下一帧还会再试
        }
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (searchField != null) searchField.updateCursorCounter();
        if (prefixField != null) prefixField.updateCursorCounter();

        // 还没拿到这个终端的配置就再问一次（每 10 tick 一次，够快也不至于刷包）
        if (!configReady() && ++requestTimer >= 10) {
            requestTimer = 0;
            requestConfig();
        }

        refreshFilter();
        refreshLabels();
    }

    private int requestTimer;

    private void refreshLabels() {
        TerminalIoConfig config = ClientTerminalIo.get();

        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            int row = face.ordinal();
            String itemLabel = tr(
                config.getMode(face, false)
                    .getLangKey());
            String fluidLabel = tr(
                config.getMode(face, true)
                    .getLangKey());
            faceButtons.get(row * 2).displayString = itemLabel;
            faceButtons.get(row * 2 + 1).displayString = fluidLabel;

            // 有动作的面用亮色标一下，一眼看出哪几个面在动
            faceButtons.get(row * 2).enabled = true;
            faceButtons.get(row * 2 + 1).enabled = true;
        }

        int kindIndex = buttonIndexOf(BTN_KIND);
        if (kindIndex >= 0) {
            buttonList.get(kindIndex).displayString = tr(
                fluidTab ? "futa_gtnh.gui.terminal.io.kind.fluid" : "futa_gtnh.gui.terminal.io.kind.item");
        }

        TerminalIoConfig.Preset[] presets = TerminalIoConfig.Preset.values();
        for (int i = 0; i < presets.length; i++) {
            GuiButton button = presetButtons.get(i);
            boolean on = config.getPresets()
                .contains(presets[i]);
            button.displayString = (on ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                + shortPresetName(presets[i]);
        }
        for (GuiButton button : buttonList) {
            if (button.id == BTN_CLEAR) button.displayString = tr("futa_gtnh.gui.terminal.io.clear");
            if (button.id == BTN_DONE) button.displayString = tr("futa_gtnh.gui.terminal.io.done");
        }
    }

    private static String shortPresetName(TerminalIoConfig.Preset preset) {
        switch (preset) {
            case RAW_ORE:
                return tr("futa_gtnh.gui.terminal.io.preset.raw.short");
            case CRUSHED:
                return tr("futa_gtnh.gui.terminal.io.preset.crushed.short");
            case PURIFIED:
                return tr("futa_gtnh.gui.terminal.io.preset.purified.short");
            case IMPURE:
                return tr("futa_gtnh.gui.terminal.io.preset.impure.short");
            default:
                return tr("futa_gtnh.gui.terminal.io.preset.centrifuged.short");
        }
    }

    private int buttonIndexOf(int id) {
        for (int i = 0; i < buttonList.size(); i++) {
            if (buttonList.get(i).id == id) return i;
        }
        return -1;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawRect(guiLeft, guiTop, guiLeft + GUI_WIDTH, guiTop + GUI_HEIGHT, 0xC0101010);
        drawBorder(guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT, 0xFF808080);

        fontRendererObj.drawStringWithShadow(tr("futa_gtnh.gui.terminal.io.title"), guiLeft + 8, guiTop + 8, 0xFFFFFF);

        if (!configReady()) {
            fontRendererObj
                .drawStringWithShadow(tr("futa_gtnh.gui.terminal.io.syncing"), guiLeft + 8, guiTop + 8 + 120, 0xFFFF55);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        // ---- 面的表头与名字 ----
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.face"), guiLeft + 8, guiTop + 24, 0xA0A0A0);
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.items"), guiLeft + 52, guiTop + 24, 0xA0A0A0);
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.fluids"), guiLeft + 114, guiTop + 24, 0xA0A0A0);

        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            int row = face.ordinal();
            fontRendererObj
                .drawString(faceName(face), guiLeft + 10, guiTop + 39 + row * 20, isActive(face) ? 0x55FF55 : 0xE0E0E0);
        }

        // ---- 筛选区 ----
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.filter"), guiLeft + 190, guiTop + 8, 0xFFFFFF);
        drawFilterGrid(mouseX, mouseY);

        if (searchField != null) searchField.drawTextBox();
        if (searchField != null && searchField.getText()
            .isEmpty()) {
            fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.search"), guiLeft + 11, guiTop + 58, 0x707070);
        }
        if (prefixField != null) prefixField.drawTextBox();
        if (prefixField != null && prefixField.getText()
            .isEmpty()) {
            fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.prefix"), guiLeft + 193, guiTop + 154, 0x707070);
        }

        fontRendererObj.drawString(
            tr("futa_gtnh.gui.terminal.io.selected") + " "
                + selectedCount()
                + (ClientTerminalIo.get()
                    .isFilterEmpty() ? tr("futa_gtnh.gui.terminal.io.no_filter") : ""),
            guiLeft + 8,
            guiTop + 176,
            0xA0A0A0);

        super.drawScreen(mouseX, mouseY, partialTicks);
        drawTooltips(mouseX, mouseY);
    }

    private void drawTooltips(int mouseX, int mouseY) {
        int index = gridIndexAt(mouseX, mouseY);
        if (index < 0) return;

        StorageViewEntry entry = shown.get(index);
        ItemStack display = entry.getDisplay();
        if (display != null) {
            drawHoveringText(
                display.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips),
                mouseX,
                mouseY,
                fontRendererObj);
        }
    }

    private boolean isActive(ForgeDirection face) {
        TerminalIoConfig config = ClientTerminalIo.get();
        return config.getMode(face, false) != TerminalIoConfig.Mode.OFF
            || config.getMode(face, true) != TerminalIoConfig.Mode.OFF;
    }

    private int selectedCount() {
        TerminalIoConfig config = ClientTerminalIo.get();
        return config.getItems()
            .size()
            + config.getFluids()
                .size()
            + config.getPresets()
                .size()
            + config.getCustomPrefixes()
                .size();
    }

    private void drawFilterGrid(int mouseX, int mouseY) {
        int gridX = guiLeft + 8;
        int gridY = guiTop + 74;

        for (int i = 0; i < PAGE; i++) {
            int col = i % COLS;
            int row = i / COLS;
            int x = gridX + col * CELL;
            int y = gridY + row * CELL;

            boolean hovered = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            drawRect(x, y, x + CELL, y + CELL, hovered ? 0x60FFFFFF : 0x30FFFFFF);
            drawBorder(x, y, CELL, CELL, 0x60FFFFFF);
        }

        // 物品图标必须开 GUI 标准光照，否则画出来是黑的（和终端界面同一个道理）
        net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
        for (int i = 0; i < shown.size() && i < PAGE; i++) {
            StorageViewEntry entry = shown.get(i);
            int col = i % COLS;
            int row = i / COLS;
            int x = gridX + col * CELL + 1;
            int y = gridY + row * CELL + 1;

            ItemStack display = entry.getDisplay();
            if (display != null) {
                itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), display, x, y);
            }
            // 选中的画一圈绿框：一眼看出筛选里已经有哪些
            if (isSelected(entry)) {
                drawBorder(x - 1, y - 1, CELL - 1, CELL - 1, 0xFF55FF55);
                drawBorder(x - 2, y - 2, CELL + 1, CELL + 1, 0xFF55FF55);
            }
        }
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();

        if (shown.isEmpty()) {
            fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.empty"), gridX + 2, gridY + 2, 0x808080);
        }
    }

    private boolean isSelected(StorageViewEntry entry) {
        TerminalIoConfig config = ClientTerminalIo.get();
        if (entry.isFluid()) {
            FluidKey key = entry.getFluidKey();
            return key != null && config.containsFluid(key);
        }
        ItemKey key = entry.getItemKey();
        return key != null && config.containsItem(key);
    }

    private void drawBorder(int x, int y, int width, int height, int color) {
        drawRect(x, y, x + width, y + 1, color);
        drawRect(x, y + height - 1, x + width, y + height, color);
        drawRect(x, y, x + 1, y + height, color);
        drawRect(x + width - 1, y, x + width, y + height, color);
    }

    private static String faceName(ForgeDirection face) {
        return tr(
            "futa_gtnh.gui.terminal.io.dir." + face.name()
                .toLowerCase(java.util.Locale.ROOT));
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    // ==================================================================
    // 输入
    // ==================================================================

    @Override
    protected void actionPerformed(GuiButton button) {
        TerminalIoConfig config = ClientTerminalIo.get();

        if (button.id >= BTN_FACE_BASE && button.id < BTN_FACE_BASE + 12) {
            int index = button.id - BTN_FACE_BASE;
            ForgeDirection face = ForgeDirection.getOrientation(index / 2);
            boolean fluid = index % 2 == 1;
            config.setMode(
                face,
                fluid,
                config.getMode(face, fluid)
                    .next());
            refreshLabels();
            pushConfig();
            return;
        }

        if (button.id >= BTN_PRESET_BASE && button.id < BTN_PRESET_BASE + TerminalIoConfig.Preset.values().length) {
            config.togglePreset(TerminalIoConfig.Preset.values()[button.id - BTN_PRESET_BASE]);
            refreshLabels();
            pushConfig();
            return;
        }

        switch (button.id) {
            case BTN_KIND:
                fluidTab = !fluidTab;
                shown.clear();
                refreshFilter();
                refreshLabels();
                break;
            case BTN_CLEAR:
                config.clearFilter();
                prefixField.setText("");
                refreshLabels();
                pushConfig();
                break;
            case BTN_DONE:
                close();
                break;
            default:
                break;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (searchField != null) searchField.mouseClicked(mouseX, mouseY, mouseButton);
        if (prefixField != null) prefixField.mouseClicked(mouseX, mouseY, mouseButton);

        int index = gridIndexAt(mouseX, mouseY);
        if (index < 0 || mouseButton != 0) return;

        StorageViewEntry entry = shown.get(index);
        TerminalIoConfig config = ClientTerminalIo.get();
        if (entry.isFluid()) {
            config.toggleFluid(entry.getFluidKey());
        } else {
            config.toggleItem(entry.getItemKey());
        }
        pushConfig();
    }

    private int gridIndexAt(int mouseX, int mouseY) {
        int gridX = guiLeft + 8;
        int gridY = guiTop + 74;
        if (mouseX < gridX || mouseY < gridY) return -1;

        int col = (mouseX - gridX) / CELL;
        int row = (mouseY - gridY) / CELL;
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return -1;

        int index = row * COLS + col;
        return index < shown.size() ? index : -1;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (searchField != null && searchField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                close();
                return;
            }
            searchField.textboxKeyTyped(typedChar, keyCode);
            shown.clear();
            refreshFilter();
            return;
        }

        if (prefixField != null && prefixField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                close();
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN) {
                applyPrefixes();
                return;
            }
            prefixField.textboxKeyTyped(typedChar, keyCode);
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            close();
        }
    }

    private void applyPrefixes() {
        if (prefixField == null) return;
        ClientTerminalIo.get()
            .setCustomPrefixes(prefixField.getText());
        refreshLabels();
        pushConfig();
    }

    @Override
    public void onGuiClosed() {
        // 前缀是「按回车才生效」的，直接关界面时补一次，免得玩家以为改了没反应
        applyPrefixes();
        super.onGuiClosed();
    }

    /** 回终端界面 —— 容器一直开着，这里只是把界面换回去。 */
    private void close() {
        applyPrefixes();
        if (container != null && mc.thePlayer != null && mc.thePlayer.openContainer == container) {
            mc.displayGuiScreen(GuiSharedTerminal.create(container));
            return;
        }
        mc.displayGuiScreen((GuiScreen) null);
    }

    /** 供 {@link GuiSharedTerminal} 判断「这个容器能不能打开面配置」。 */
    public static boolean availableFor(GuiContainer gui) {
        return gui != null && gui.inventorySlots instanceof ContainerSharedTerminal
            && ((ContainerSharedTerminal) gui.inventorySlots).getTerminal() != null;
    }
}
