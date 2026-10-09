package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.block.TerminalOutputFilter;
import com.futa_gtnh.inventory.ContainerIoNode;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketIoNodeConfig;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * IO 节点方块的配置界面：右键<b>某根连接臂</b>打开，配置的就是那个面。
 *
 * <p>
 * 三个页签，各管一件事：
 * <ul>
 * <li><b>面配置</b>：这一面（←/→ 在有连接的面之间切换）的物品 / 流体方向 ——
 * 关 / 抽入 / 输出 / 输入输出 四态（EnderIO 的语义），默认全部<b>关</b>；</li>
 * <li><b>输出筛选</b>：这一面的输出白名单，条目来自共享存储（和共享终端的筛选页同一套，
 * 但<b>默认按数量倒序</b>），外加矿辞预设和自定义前缀；</li>
 * <li><b>节奏</b>：这台节点自己的间隔与每轮数量。</li>
 * </ul>
 *
 * <p>
 * 数据来源和同步策略与 {@link GuiTerminalIo} 相同：全部读 {@link ClientIoNode} 里
 * 服务端推过来的快照；每改一下就整份发回服务端（{@code PacketIoNodeConfig}），
 * 服务端立刻回一份权威值覆盖本地。
 */
public class GuiIoNode extends FutaGuiContainer {

    private static final int GUI_WIDTH = 300;
    private static final int GUI_HEIGHT = 200;

    // ---- 按钮 id ----
    private static final int BTN_TAB_BASE = 0; // 0..2 页签
    private static final int BTN_PREV_FACE = 10;
    private static final int BTN_NEXT_FACE = 11;
    private static final int BTN_MODE_BASE = 20; // 20..27：物品/流体 × 关/抽/输/双向
    private static final int BTN_KIND = 30;
    private static final int BTN_CLEAR = 31;
    private static final int BTN_DONE = 32;
    private static final int BTN_SORT = 33;
    private static final int BTN_PRESET_BASE = 40; // 40..44
    private static final int BTN_RATE_BASE = 50; // 50..55：三行 × 减/加
    private static final int BTN_STATS_TOGGLE = 60;
    private static final int BTN_STATS_MODE = 61;

    private static final int TAB_MAIN = 0;
    private static final int TAB_FILTER = 1;
    private static final int TAB_RATE = 2;
    private static final int TAB_STATS = 3;
    private static final int TAB_COUNT = 4;

    /** 筛选网格：10 列 × 4 行（和共享终端的筛选页一个规格）。 */
    private static final int COLS = 10;
    private static final int ROWS = 4;
    private static final int CELL = 18;
    private static final int PAGE = COLS * ROWS;

    private final ContainerIoNode container;
    /**
     * 这个界面当前配置的面。打开时是右键点到的那根连接臂（写进了 GUI id），
     * 之后可以在有连接的面之间切换 —— 配置上行永远是整份六面的，切面不用重开界面。
     */
    private int face;

    private int tab = TAB_MAIN;

    private GuiTextField searchField;
    private GuiTextField prefixField;
    /** 统计页的节点名输入框。回车 / 切页签 / 关界面时才生效（和前缀输入一个习惯）。 */
    private GuiTextField statsNameField;

    private boolean fluidTab;
    /** 筛选条目的排序方式，默认「数量倒序」（IO 节点的约定，不是终端那个按名称）。 */
    private int sortMode = StorageSort.BY_AMOUNT;

    private final List<StorageViewEntry> shown = new ArrayList<>();

    private String lastQuery = "\u0000";
    private boolean fluidTabCache;
    private int revision = -1;
    private boolean built;
    private int requestTimer;

    public GuiIoNode(ContainerIoNode container, int face) {
        super(container);
        this.container = container;
        this.face = face;
        this.xSize = GUI_WIDTH;
        this.ySize = GUI_HEIGHT;
    }

    /**
     * 自绘的扁平按钮 —— 和 {@link GuiTerminalIo} 里的那一个同一套：
     * 深灰底、悬停提亮、选中绿色填充，一眼分清「能点 / 正指着 / 就是它」。
     */
    private final class FlatButton extends GuiButton {

        /** 选中态（当前页签 / 当前方向 / 已勾选的预设）。 */
        private boolean marked;

        private FlatButton(int id, int x, int y, int width, int height, String label) {
            super(id, x, y, width, height, label);
        }

        private void setMarked(boolean value) {
            this.marked = value;
        }

        @Override
        public void drawButton(net.minecraft.client.Minecraft mc, int mouseX, int mouseY) {
            if (!this.visible) return;

            boolean hovered = mouseX >= this.xPosition && mouseY >= this.yPosition
                && mouseX < this.xPosition + this.width
                && mouseY < this.yPosition + this.height;

            int fill;
            int edge;
            if (marked) {
                fill = hovered ? 0xFF3E8E45 : 0xFF2E6B34;
                edge = 0xFF7BE38A;
            } else {
                fill = hovered ? 0xFF4C4C4C : 0xFF333333;
                edge = 0xFF6E6E6E;
            }

            drawRect(this.xPosition, this.yPosition, this.xPosition + this.width, this.yPosition + this.height, fill);
            drawBorder(this.xPosition, this.yPosition, this.width, this.height, edge);

            // 画字之前把颜色状态复位：drawRect 会把填充色留在 GL 状态里
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            String label = this.displayString == null ? "" : this.displayString;
            fontRendererObj.drawStringWithShadow(
                label,
                this.xPosition + (this.width - fontRendererObj.getStringWidth(label)) / 2,
                this.yPosition + (this.height - 8) / 2,
                0xFFFFFF);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    // ==================================================================
    // 布局
    // ==================================================================

    @Override
    public void initGui() {
        super.initGui();
        guiLeft = (width - GUI_WIDTH) / 2;
        guiTop = (height - GUI_HEIGHT) / 2;

        buttonList.clear();
        searchField = null;
        prefixField = null;
        statsNameField = null;

        for (int i = 0; i < TAB_COUNT; i++) {
            buttonList.add(new FlatButton(BTN_TAB_BASE + i, guiLeft + 8 + i * 66, guiTop + 20, 64, 16, ""));
        }

        // 只给当前页签建控件：GuiScreen 会把 buttonList 里的按钮全画出来，
        // disabled 只是画成灰的，不能拿它代替「不显示」
        if (tab == TAB_MAIN) {
            buildMainTab();
        } else if (tab == TAB_FILTER) {
            buildFilterTab();
        } else if (tab == TAB_RATE) {
            buildRateTab();
        } else {
            buildStatsTab();
        }

        buttonList.add(new FlatButton(BTN_DONE, guiLeft + GUI_WIDTH - 68, guiTop + 168, 60, 16, ""));

        refreshFilter();
        refreshLabels();
    }

    private void buildMainTab() {
        buttonList.add(new FlatButton(BTN_PREV_FACE, guiLeft + 10, guiTop + 44, 24, 16, "←"));
        buttonList.add(new FlatButton(BTN_NEXT_FACE, guiLeft + GUI_WIDTH - 34, guiTop + 44, 24, 16, "→"));

        // 物品 / 流体各一组方向按钮：关 / 抽入 / 输出 / 输入输出
        for (int kind = 0; kind < 2; kind++) {
            for (int mode = 0; mode < 4; mode++) {
                buttonList.add(
                    new FlatButton(
                        BTN_MODE_BASE + kind * 4 + mode,
                        guiLeft + 62 + mode * 56,
                        guiTop + 92 + kind * 28,
                        54,
                        16,
                        ""));
            }
        }
    }

    private void buildFilterTab() {
        buttonList.add(new FlatButton(BTN_KIND, guiLeft + 8, guiTop + 40, 76, 16, ""));
        searchField = new GuiTextField(fontRendererObj, guiLeft + 90, guiTop + 40, 172, 16);
        searchField.setMaxStringLength(48);
        searchField.setText(lastQuery.equals("\u0000") ? "" : lastQuery);
        buttonList.add(new FlatButton(BTN_SORT, guiLeft + 264, guiTop + 40, 28, 16, ""));

        TerminalIoConfig.Preset[] presets = fluidTab ? new TerminalIoConfig.Preset[0]
            : TerminalIoConfig.Preset.values();
        for (int i = 0; i < presets.length; i++) {
            int col = i % 3;
            int row = i / 3;
            buttonList
                .add(new FlatButton(BTN_PRESET_BASE + i, guiLeft + 200 + col * 32, guiTop + 88 + row * 20, 32, 18, ""));
        }

        if (!fluidTab) {
            prefixField = new GuiTextField(fontRendererObj, guiLeft + 200, guiTop + 146, 92, 16);
            prefixField.setMaxStringLength(64);
            prefixField.setText(joinPrefixes());
        }

        buttonList.add(new FlatButton(BTN_CLEAR, guiLeft + 8, guiTop + 168, 60, 16, ""));
    }

    private void buildRateTab() {
        for (int row = 0; row < 3; row++) {
            buttonList.add(new FlatButton(BTN_RATE_BASE + row * 2, guiLeft + 210, guiTop + 78 + row * 28, 18, 18, "-"));
            buttonList
                .add(new FlatButton(BTN_RATE_BASE + row * 2 + 1, guiLeft + 232, guiTop + 78 + row * 28, 18, 18, "+"));
        }
    }

    private void buildStatsTab() {
        buttonList.add(new FlatButton(BTN_STATS_TOGGLE, guiLeft + 232, guiTop + 46, 60, 16, ""));
        buttonList.add(new FlatButton(BTN_STATS_MODE, guiLeft + 12, guiTop + 100, 120, 16, ""));

        statsNameField = new GuiTextField(fontRendererObj, guiLeft + 12, guiTop + 66, 180, 16);
        statsNameField.setMaxStringLength(TerminalIoConfig.STATS_NAME_LIMIT);
        statsNameField.setText(currentStatsName());
    }

    /** @return 统计页输入框应该显示的名字（同步没到 / 还没改过时就是配置里的名字） */
    private String currentStatsName() {
        return ClientIoNode.get()
            .getStatsName();
    }

    private String joinPrefixes() {
        StringBuilder builder = new StringBuilder();
        for (String prefix : currentFilter().getCustomPrefixes()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(prefix);
        }
        return builder.toString();
    }

    private TerminalOutputFilter currentFilter() {
        return ClientIoNode.get()
            .getOutputFilter(ForgeDirection.getOrientation(face));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** @return 这份配置是不是就是这个节点推过来的（不是就别让玩家改） */
    private boolean configReady() {
        if (container == null || container.getNode() == null) return false;
        net.minecraft.tileentity.TileEntity node = container.getNode();
        return ClientIoNode.matches(
            node.getWorldObj() == null ? 0 : node.getWorldObj().provider.dimensionId,
            node.xCoord,
            node.yCoord,
            node.zCoord);
    }

    // ==================================================================
    // 数据
    // ==================================================================

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
        // IO 节点的约定：默认按数量倒序（共享存储里攒得最多的排最前）
        StorageSort.sort(shown, sortMode);
        while (shown.size() > PAGE) {
            shown.remove(shown.size() - 1);
        }
    }

    private void pushConfig() {
        if (!configReady()) return;
        try {
            NetworkHandler.INSTANCE.sendToServer(
                PacketIoNodeConfig.update(
                    ClientIoNode.getX(),
                    ClientIoNode.getY(),
                    ClientIoNode.getZ(),
                    ClientIoNode.get()
                        .writeToNbt()));
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("IO 节点：发送面配置失败", t);
        }
    }

    private void requestConfig() {
        if (container == null || container.getNode() == null) return;
        try {
            NetworkHandler.INSTANCE.sendToServer(
                PacketIoNodeConfig
                    .request(container.getNode().xCoord, container.getNode().yCoord, container.getNode().zCoord));
        } catch (Throwable ignored) {
            // 发不出去就继续显示「同步中」，过一会儿再试
        }
    }

    /** 在「有连接的面」之间切换；一面都没连就在全部六面里循环。 */
    private int switchFace(int delta) {
        int next = face;
        for (int step = 0; step < 6; step++) {
            next = (next + delta + 6) % 6;
            if (ClientIoNode.hasItemConnection(next) || ClientIoNode.hasFluidConnection(next)) return next;
        }
        return (face + delta + 6) % 6;
    }

    // ==================================================================
    // 每帧
    // ==================================================================

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (searchField != null) searchField.updateCursorCounter();
        if (prefixField != null) prefixField.updateCursorCounter();
        if (statsNameField != null) statsNameField.updateCursorCounter();
        if (!configReady() && ++requestTimer >= 10) {
            requestTimer = 0;
            requestConfig();
        }
        if (tab == TAB_FILTER) refreshFilter();
        refreshLabels();
    }

    private void refreshLabels() {
        TerminalIoConfig config = ClientIoNode.get();

        String[] tabKeys = { "futa_gtnh.gui.ionode.tab.main", "futa_gtnh.gui.ionode.tab.filter",
            "futa_gtnh.gui.terminal.io.tab.rate", "futa_gtnh.gui.terminal.io.tab.stats" };
        for (int i = 0; i < TAB_COUNT; i++) {
            GuiButton button = findButton(BTN_TAB_BASE + i);
            if (button == null) continue;
            button.displayString = tr(tabKeys[i]);
            button.enabled = true;
            if (button instanceof FlatButton) ((FlatButton) button).setMarked(i == tab);
        }

        boolean ready = configReady();
        boolean main = tab == TAB_MAIN && ready;
        boolean filter = tab == TAB_FILTER && ready;
        boolean rate = tab == TAB_RATE && ready;
        boolean stats = tab == TAB_STATS && ready;

        // 面配置：两组方向按钮（注意只在面配置页签下才建得出来）
        setEnabled(BTN_PREV_FACE, main);
        setEnabled(BTN_NEXT_FACE, main);
        for (int kind = 0; kind < 2; kind++) {
            boolean fluid = kind == 1;
            TerminalIoConfig.Mode current = config.getMode(ForgeDirection.getOrientation(face), fluid);
            for (int mode = 0; mode < 4; mode++) {
                GuiButton button = findButton(BTN_MODE_BASE + kind * 4 + mode);
                if (button == null) continue;
                button.enabled = main;
                TerminalIoConfig.Mode value = TerminalIoConfig.Mode.values()[mode];
                button.displayString = tr(value.getLangKey());
                if (button instanceof FlatButton) ((FlatButton) button).setMarked(value == current);
            }
        }

        // 筛选页
        setEnabled(BTN_KIND, filter);
        setEnabled(BTN_CLEAR, filter);
        setEnabled(BTN_SORT, filter);
        if (searchField != null) {
            searchField.setVisible(tab == TAB_FILTER);
            searchField.setEnabled(filter);
        }
        if (prefixField != null) {
            prefixField.setVisible(tab == TAB_FILTER && !fluidTab);
            prefixField.setEnabled(filter && !fluidTab);
        }

        TerminalIoConfig.Preset[] presets = TerminalIoConfig.Preset.values();
        for (int i = 0; i < presets.length; i++) {
            GuiButton button = findButton(BTN_PRESET_BASE + i);
            if (button == null) continue;
            button.enabled = filter && !fluidTab;
            boolean on = currentFilter().getPresets()
                .contains(presets[i]);
            button.displayString = shortPresetName(presets[i]);
            if (button instanceof FlatButton) ((FlatButton) button).setMarked(on);
        }
        GuiButton kindButton = findButton(BTN_KIND);
        if (kindButton != null) {
            kindButton.displayString = tr(
                fluidTab ? "futa_gtnh.gui.terminal.io.kind.fluid" : "futa_gtnh.gui.terminal.io.kind.item");
        }
        GuiButton clearButton = findButton(BTN_CLEAR);
        if (clearButton != null) clearButton.displayString = tr("futa_gtnh.gui.terminal.io.clear");

        GuiButton sortButton = findButton(BTN_SORT);
        if (sortButton != null) sortButton.displayString = tr(StorageSort.translationKey(sortMode));

        // 节奏页
        for (int i = 0; i < 6; i++) {
            setEnabled(BTN_RATE_BASE + i, rate);
        }

        // 统计页
        GuiButton statsToggle = findButton(BTN_STATS_TOGGLE);
        if (statsToggle != null) {
            statsToggle.enabled = stats;
            statsToggle.displayString = tr(
                config.isStatsEnabled() ? "futa_gtnh.gui.terminal.io.stats.on" : "futa_gtnh.gui.terminal.io.stats.off");
            if (statsToggle instanceof FlatButton) ((FlatButton) statsToggle).setMarked(config.isStatsEnabled());
        }
        GuiButton statsMode = findButton(BTN_STATS_MODE);
        if (statsMode != null) {
            statsMode.enabled = stats;
            statsMode.displayString = tr("futa_gtnh.gui.terminal.io.stats.mode") + ": "
                + tr(
                    config.getStatsMode()
                        .getLangKey());
        }
        if (statsNameField != null) {
            statsNameField.setVisible(tab == TAB_STATS);
            statsNameField.setEnabled(stats);
        }

        GuiButton doneButton = findButton(BTN_DONE);
        if (doneButton != null) doneButton.displayString = tr("futa_gtnh.gui.terminal.io.done");
    }

    private GuiButton findButton(int id) {
        for (Object object : buttonList) {
            if (object instanceof GuiButton && ((GuiButton) object).id == id) return (GuiButton) object;
        }
        return null;
    }

    private void setEnabled(int id, boolean enabled) {
        GuiButton button = findButton(id);
        if (button != null) button.enabled = enabled;
    }

    private static String shortPresetName(TerminalIoConfig.Preset preset) {
        return tr(
            "futa_gtnh.gui.terminal.io.preset." + preset.name()
                .toLowerCase(Locale.ROOT) + ".short");
    }

    private static String faceName(ForgeDirection face) {
        return tr(
            "futa_gtnh.gui.terminal.io.dir." + face.name()
                .toLowerCase(Locale.ROOT));
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 顺序有讲究：GuiContainer.drawScreen 一开头会调 drawDefaultBackground()
        // 把整个屏幕压暗 —— 面板必须画在它<b>之后</b>（也就是背景层里），否则会被压暗一遍。
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (!configReady()) {
            fontRendererObj
                .drawStringWithShadow(tr("futa_gtnh.gui.ionode.syncing"), guiLeft + 8, guiTop + 96, 0xFFFF55);
            return;
        }

        // 内容层画在按钮之后（和 GuiTerminalIo 一个顺序）：状态再复位一遍，
        // 免得按钮/槽位渲染留下什么 GL 状态影响文字
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        switch (tab) {
            case TAB_MAIN:
                drawMainTab();
                break;
            case TAB_FILTER:
                drawFilterTab(mouseX, mouseY);
                break;
            case TAB_RATE:
                drawRateTab();
                break;
            default:
                drawStatsTab();
                break;
        }
        drawTooltips(mouseX, mouseY);
    }

    /** 面板底和标题：这里在 {@code drawDefaultBackground} 之后、按钮之前，层次才对。 */
    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawRect(guiLeft, guiTop, guiLeft + GUI_WIDTH, guiTop + GUI_HEIGHT, 0xC0101010);
        drawBorder(guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT, 0xFF808080);
        fontRendererObj.drawStringWithShadow(tr("futa_gtnh.gui.ionode.title"), guiLeft + 8, guiTop + 6, 0xFFFFFF);
    }

    // ---- 面配置页 ----

    private void drawMainTab() {
        ForgeDirection dir = ForgeDirection.getOrientation(face);

        String heading = faceName(dir) + " " + EnumChatFormatting.GRAY + connectionSummary(dir);
        fontRendererObj.drawStringWithShadow(
            heading,
            guiLeft + (GUI_WIDTH - fontRendererObj.getStringWidth(heading)) / 2,
            guiTop + 48,
            0xFFFFFF);

        fontRendererObj.drawString(tr("futa_gtnh.gui.ionode.face.hint"), guiLeft + 12, guiTop + 68, 0x909090);

        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.items"), guiLeft + 14, guiTop + 96, 0xC0C0C0);
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.fluids"), guiLeft + 14, guiTop + 124, 0xC0C0C0);

        fontRendererObj.drawString(tr("futa_gtnh.gui.ionode.mode.hint"), guiLeft + 12, guiTop + 150, 0x909090);
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.pull.hint"), guiLeft + 12, guiTop + 162, 0x707070);
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.push.hint"), guiLeft + 12, guiTop + 174, 0x707070);
    }

    /** @return 这一面接了什么（物品 / 流体），给面配置页的标题用。 */
    private static String connectionSummary(ForgeDirection face) {
        boolean items = ClientIoNode.hasItemConnection(face.ordinal());
        boolean fluids = ClientIoNode.hasFluidConnection(face.ordinal());
        if (items && fluids)
            return "[" + tr("futa_gtnh.gui.terminal.io.items") + "+" + tr("futa_gtnh.gui.terminal.io.fluids") + "]";
        if (items) return "[" + tr("futa_gtnh.gui.terminal.io.items") + "]";
        if (fluids) return "[" + tr("futa_gtnh.gui.terminal.io.fluids") + "]";
        return "[" + tr("futa_gtnh.gui.ionode.connected.none") + "]";
    }

    // ---- 筛选页（布局与共享终端的筛选页同一套） ----

    private void drawFilterTab(int mouseX, int mouseY) {
        // 右上角标出当前在配哪个面，和主页的切换按钮对应
        String faceLabel = faceName(ForgeDirection.getOrientation(face));
        fontRendererObj.drawStringWithShadow(
            faceLabel,
            guiLeft + GUI_WIDTH - 10 - fontRendererObj.getStringWidth(faceLabel),
            guiTop + 6,
            0xFFFF55);

        drawFilterGrid(mouseX, mouseY);

        // 文本框要自己画：GuiScreen 只画 buttonList 里的按钮，输入框不在里面
        if (searchField != null) searchField.drawTextBox();
        if (prefixField != null) prefixField.drawTextBox();

        if (searchField != null && searchField.getVisible()
            && searchField.getText()
                .isEmpty()) {
            fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.search"), guiLeft + 93, guiTop + 44, 0x707070);
        }
        if (prefixField != null && prefixField.getVisible()
            && prefixField.getText()
                .isEmpty()) {
            fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.prefix"), guiLeft + 203, guiTop + 150, 0x707070);
        }

        if (!fluidTab) {
            fontRendererObj
                .drawString(tr("futa_gtnh.gui.terminal.io.preset.title"), guiLeft + 200, guiTop + 74, 0xC0C0C0);
            fontRendererObj
                .drawString(tr("futa_gtnh.gui.terminal.io.prefix.title"), guiLeft + 200, guiTop + 134, 0xC0C0C0);
        }
        String summary = tr("futa_gtnh.gui.terminal.io.selected") + " "
            + selectedCount()
            + (currentFilter().isEmpty(fluidTab) ? tr("futa_gtnh.gui.terminal.io.no_filter") : "");
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(summary, 184), guiLeft + 8, guiTop + 145, 0xA0A0A0);
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(tr("futa_gtnh.gui.terminal.io.filter.hint"), 184),
            guiLeft + 8,
            guiTop + 156,
            0x909090);
    }

    private void drawFilterGrid(int mouseX, int mouseY) {
        int gridX = guiLeft + 8;
        int gridY = guiTop + 66;

        for (int i = 0; i < PAGE; i++) {
            int x = gridX + (i % COLS) * CELL;
            int y = gridY + (i / COLS) * CELL;
            boolean hovered = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            drawRect(x, y, x + CELL, y + CELL, hovered ? 0x60FFFFFF : 0x30FFFFFF);
            drawBorder(x, y, CELL, CELL, 0x60FFFFFF);
        }

        net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
        for (int i = 0; i < shown.size() && i < PAGE; i++) {
            StorageViewEntry entry = shown.get(i);
            int x = gridX + (i % COLS) * CELL + 1;
            int y = gridY + (i / COLS) * CELL + 1;

            ItemStack display = entry.getDisplay();
            if (display != null) {
                itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), display, x, y);
            }
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
        TerminalOutputFilter config = currentFilter();
        if (entry.isFluid()) {
            FluidKey key = entry.getFluidKey();
            return key != null && config.containsFluid(key);
        }
        ItemKey key = entry.getItemKey();
        return key != null && config.containsItem(key);
    }

    private int selectedCount() {
        TerminalOutputFilter filter = currentFilter();
        return fluidTab ? filter.getFluids()
            .size()
            : filter.getItems()
                .size()
                + filter.getPresets()
                    .size()
                + filter.getCustomPrefixes()
                    .size();
    }

    // ---- 节奏页（与共享终端的节奏页同一套） ----

    private void drawRateTab() {
        TerminalIoConfig config = ClientIoNode.get();

        fontRendererObj.drawStringWithShadow(tr("futa_gtnh.gui.terminal.io.rate"), guiLeft + 8, guiTop + 44, 0xFFFFFF);
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.rate.hint"), guiLeft + 8, guiTop + 60, 0x909090);

        String[] labels = { tr("futa_gtnh.gui.terminal.io.rate.interval"), tr("futa_gtnh.gui.terminal.io.rate.items"),
            tr("futa_gtnh.gui.terminal.io.rate.fluid") };
        String[] values = { config.getIntervalTicks() + " tick",
            config.getItemsPerOperation() + " " + tr("futa_gtnh.gui.terminal.io.rate.per"),
            config.getFluidPerOperation() + " mB " + tr("futa_gtnh.gui.terminal.io.rate.per") };

        for (int row = 0; row < 3; row++) {
            int y = guiTop + 78 + row * 28;
            fontRendererObj.drawString(labels[row], guiLeft + 14, y + 5, 0xC0C0C0);
            String value = values[row];
            fontRendererObj.drawString(value, guiLeft + 200 - fontRendererObj.getStringWidth(value), y + 5, 0xFFFF55);
        }
    }

    // ---- 统计页 ----

    private void drawStatsTab() {
        fontRendererObj.drawStringWithShadow(tr("futa_gtnh.gui.terminal.io.stats"), guiLeft + 8, guiTop + 44, 0xFFFFFF);

        if (statsNameField != null) {
            statsNameField.drawTextBox();
            if (statsNameField.getVisible() && statsNameField.getText()
                .isEmpty()) {
                fontRendererObj
                    .drawString(tr("futa_gtnh.gui.terminal.io.stats.name.hint"), guiLeft + 15, guiTop + 70, 0x707070);
            }
        }

        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.stats.hint"), guiLeft + 12, guiTop + 130, 0x909090);
        fontRendererObj
            .drawString(tr("futa_gtnh.gui.terminal.io.stats.web.hint"), guiLeft + 12, guiTop + 144, 0x707070);
    }

    private void drawTooltips(int mouseX, int mouseY) {
        if (tab != TAB_FILTER) return;

        int index = gridIndexAt(mouseX, mouseY);
        if (index < 0) return;

        ItemStack display = shown.get(index)
            .getDisplay();
        if (display != null) {
            drawHoveringText(
                display.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips),
                mouseX,
                mouseY,
                fontRendererObj);
        }
    }

    private void drawBorder(int x, int y, int width, int height, int color) {
        drawRect(x, y, x + width, y + 1, color);
        drawRect(x, y + height - 1, x + width, y + height, color);
        drawRect(x, y, x + 1, y + height, color);
        drawRect(x + width - 1, y, x + width, y + height, color);
    }

    // ==================================================================
    // 输入
    // ==================================================================

    @Override
    protected void actionPerformed(GuiButton button) {
        TerminalIoConfig config = ClientIoNode.get();

        if (button.id >= BTN_TAB_BASE && button.id < BTN_TAB_BASE + TAB_COUNT) {
            applyPrefixes();
            applyStatsName();
            tab = button.id - BTN_TAB_BASE;
            // 换页签要重建控件，不然几个页签的按钮会叠在一起
            initGui();
            return;
        }

        if (button.id != BTN_DONE && !configReady()) return;

        if (button.id == BTN_PREV_FACE || button.id == BTN_NEXT_FACE) {
            applyPrefixes();
            face = switchFace(button.id == BTN_NEXT_FACE ? 1 : -1);
            built = false;
            refreshLabels();
            return;
        }

        if (button.id >= BTN_MODE_BASE && button.id < BTN_MODE_BASE + 8) {
            int index = button.id - BTN_MODE_BASE;
            boolean fluid = index >= 4;
            TerminalIoConfig.Mode mode = TerminalIoConfig.Mode.values()[index % 4];
            config.setMode(ForgeDirection.getOrientation(face), fluid, mode);
            refreshLabels();
            pushConfig();
            return;
        }

        if (button.id == BTN_SORT) {
            sortMode = StorageSort.next(sortMode);
            built = false;
            refreshFilter();
            refreshLabels();
            return;
        }

        if (button.id >= BTN_PRESET_BASE && button.id < BTN_PRESET_BASE + TerminalIoConfig.Preset.values().length) {
            currentFilter().togglePreset(TerminalIoConfig.Preset.values()[button.id - BTN_PRESET_BASE]);
            refreshLabels();
            pushConfig();
            return;
        }

        if (button.id >= BTN_RATE_BASE && button.id < BTN_RATE_BASE + 6) {
            int index = button.id - BTN_RATE_BASE;
            config.stepRate(index / 2, index % 2 == 0 ? -1 : 1);
            pushConfig();
            return;
        }

        if (button.id == BTN_STATS_TOGGLE) {
            applyStatsName();
            config.setStatsEnabled(!config.isStatsEnabled());
            refreshLabels();
            pushConfig();
            return;
        }

        if (button.id == BTN_STATS_MODE) {
            applyStatsName();
            config.setStatsMode(
                config.getStatsMode()
                    .next());
            refreshLabels();
            pushConfig();
            return;
        }

        switch (button.id) {
            case BTN_KIND:
                applyPrefixes();
                fluidTab = !fluidTab;
                shown.clear();
                initGui();
                break;
            case BTN_CLEAR:
                currentFilter().clear(fluidTab);
                if (prefixField != null && !fluidTab) prefixField.setText("");
                refreshLabels();
                pushConfig();
                break;
            case BTN_DONE:
                mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        if (tab == TAB_FILTER) {
            if (searchField != null) searchField.mouseClicked(mouseX, mouseY, mouseButton);
            if (prefixField != null) prefixField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (tab == TAB_STATS && statsNameField != null) {
            statsNameField.mouseClicked(mouseX, mouseY, mouseButton);
        }

        if (tab != TAB_FILTER || mouseButton != 0 || !configReady()) return;

        int index = gridIndexAt(mouseX, mouseY);
        if (index < 0) return;

        StorageViewEntry entry = shown.get(index);
        if (entry.isFluid()) {
            currentFilter().toggleFluid(entry.getFluidKey());
        } else {
            currentFilter().toggleItem(entry.getItemKey());
        }
        pushConfig();
    }

    private int gridIndexAt(int mouseX, int mouseY) {
        int gridX = guiLeft + 8;
        int gridY = guiTop + 66;
        if (mouseX < gridX || mouseY < gridY) return -1;

        int col = (mouseX - gridX) / CELL;
        int row = (mouseY - gridY) / CELL;
        if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return -1;

        int index = row * COLS + col;
        return index < shown.size() ? index : -1;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (tab == TAB_FILTER && searchField != null && searchField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                mc.displayGuiScreen(null);
                return;
            }
            searchField.textboxKeyTyped(typedChar, keyCode);
            shown.clear();
            refreshFilter();
            return;
        }

        if (tab == TAB_FILTER && prefixField != null && prefixField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                mc.displayGuiScreen(null);
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN) {
                applyPrefixes();
                return;
            }
            prefixField.textboxKeyTyped(typedChar, keyCode);
            return;
        }

        if (tab == TAB_STATS && statsNameField != null && statsNameField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                mc.displayGuiScreen(null);
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN) {
                applyStatsName();
                return;
            }
            statsNameField.textboxKeyTyped(typedChar, keyCode);
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
        }
    }

    /** 前缀是「回车才生效」的，切页签 / 关界面时补一次。 */
    private void applyPrefixes() {
        if (tab != TAB_FILTER || fluidTab || prefixField == null || !prefixField.getVisible() || !configReady()) return;
        if (prefixField.getText()
            .equals(joinPrefixes())) return;
        currentFilter().setCustomPrefixes(prefixField.getText());
        refreshLabels();
        pushConfig();
    }

    /** 节点名同样「回车才生效」：切页签 / 关界面 / 按统计页按钮时补一次。 */
    private void applyStatsName() {
        if (tab != TAB_STATS || statsNameField == null || !statsNameField.getVisible() || !configReady()) return;
        if (statsNameField.getText()
            .equals(
                ClientIoNode.get()
                    .getStatsName()))
            return;
        ClientIoNode.get()
            .setStatsName(statsNameField.getText());
        // 服务端会再做一次清洗（长度 / 格式码），回推的权威值会盖回输入框
        pushConfig();
        if (!ClientIoNode.get()
            .getStatsName()
            .equals(statsNameField.getText())) {
            statsNameField.setText(
                ClientIoNode.get()
                    .getStatsName());
        }
    }

    @Override
    public void onGuiClosed() {
        applyPrefixes();
        applyStatsName();
        super.onGuiClosed();
    }
}
