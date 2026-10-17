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

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.block.TerminalOutputFilter;
import com.futa_gtnh.client.widget.FutaSearchField;
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
 * 但<b>默认按数量倒序</b>），外加矿辞预设和 AE2 矿典表达式；</li>
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
    private static final int FILTER_HEIGHT = 224;

    // ---- 按钮 id ----
    private static final int BTN_TAB_BASE = 0; // 0..2 页签
    private static final int BTN_PREV_FACE = 10;
    private static final int BTN_NEXT_FACE = 11;
    private static final int BTN_MODE_BASE = 20; // 20..27：物品/流体 × 关/抽/输/双向
    private static final int BTN_KIND = 30;
    private static final int BTN_CLEAR = 31;
    private static final int BTN_DONE = 32;
    private static final int BTN_SORT = 33;
    private static final int BTN_VIEW = 34; // 切换「全部 / 已选（白名单）」视图
    private static final int BTN_PRESET_BASE = 40; // 40..44
    private static final int BTN_RATE_BASE = 50; // 50..55：三行 × 减/加
    private static final int BTN_WHITELIST_PREV = 60;
    private static final int BTN_WHITELIST_NEXT = 61;

    private static final int TAB_MAIN = 0;
    private static final int TAB_FILTER = 1;
    private static final int TAB_RATE = 2;
    private static final int TAB_COUNT = 3;

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

    private FutaSearchField searchField;
    private GuiTextField oreFilterField;

    private boolean fluidTab;
    /**
     * 筛选条目的排序方式。<b>与共享终端 / 终端面配置共用同一个持久化配置</b>
     * （{@link Config#guiSortMode}）—— 白名单编辑界面的排序行为保持一致，
     * 在哪儿改过，三处都记住。
     */
    private int sortMode = StorageSort.fromIndex(Config.guiSortMode);
    /** {@link #refreshFilter} 失效条件用的水位：排序方式变了也要重建。 */
    private int sortModeCache = sortMode;
    /** true = 网格只显示当前面、当前类型的<b>白名单条目</b>（「已选」视图）。 */
    private boolean viewSelected;
    private boolean viewSelectedCache;

    private final List<StorageViewEntry> shown = new ArrayList<>();
    private final OutputWhitelistPanel whitelist = new OutputWhitelistPanel();
    private int filterRevision = -1;

    private String lastQuery = "\u0000";
    private Object lastSearchConfiguration;
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
    private final class FlatButton extends com.futa_gtnh.client.ui.FutaMarkedButton {

        private FlatButton(int id, int x, int y, int width, int height, String label) {
            super(id, x, y, width, height, label);
        }
    }

    // ==================================================================
    // 布局
    // ==================================================================

    @Override
    public void initGui() {
        ySize = tab == TAB_FILTER ? FILTER_HEIGHT : GUI_HEIGHT;
        super.initGui();
        guiLeft = (width - GUI_WIDTH) / 2;
        guiTop = (height - ySize) / 2;

        buttonList.clear();
        searchField = null;
        oreFilterField = null;

        for (int i = 0; i < TAB_COUNT; i++) {
            buttonList.add(new FlatButton(BTN_TAB_BASE + i, guiLeft + 8 + i * 66, guiTop + 20, 64, 16, ""));
        }

        // 只给当前页签建控件：GuiScreen 会把 buttonList 里的按钮全画出来，
        // disabled 只是画成灰的，不能拿它代替「不显示」
        if (tab == TAB_MAIN) {
            buildMainTab();
        } else if (tab == TAB_FILTER) {
            buildFilterTab();
        } else {
            buildRateTab();
        }

        buttonList.add(
            new FlatButton(BTN_DONE, guiLeft + GUI_WIDTH - 68, guiTop + (tab == TAB_FILTER ? 202 : 168), 60, 16, ""));

        // 只在筛选页刷新：别的页签 searchField 是 null，进来会把 lastQuery 覆写成空串 ——
        // 那是「切个页签回来搜索词就丢了」的根源。筛选页自己的刷新交给 updateScreen 的轮询。
        if (tab == TAB_FILTER) refreshFilter();
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
        // 搜索框收窄到 142：右侧让位给「已选」和排序两个按钮。
        // 统一用 FutaSearchField（右键清空、变更回调），和共享终端同一手感；
        // 它不画自带背景，暗底描边在 drawFilterTab 里补。
        searchField = new FutaSearchField(fontRendererObj, guiLeft + 90, guiTop + 40, 142, 16);
        searchField.setMaxStringLength(48);
        searchField.setText(lastQuery.equals("\u0000") ? "" : lastQuery, false);
        searchField.setChangeListener(text -> {
            built = false;
            refreshFilter();
        });
        buttonList.add(new FlatButton(BTN_VIEW, guiLeft + 234, guiTop + 40, 28, 16, ""));
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
            oreFilterField = new com.futa_gtnh.client.widget.FutaSearchField(
                fontRendererObj,
                guiLeft + 200,
                guiTop + 146,
                92,
                16);
            oreFilterField.setMaxStringLength(
                Math.max(
                    1024,
                    currentFilter().getOreFilter()
                        .length()));
            oreFilterField.setText(currentFilter().getOreFilter());
        }

        buttonList.add(new FlatButton(BTN_CLEAR, guiLeft + 8, guiTop + 202, 60, 16, ""));
        buttonList.add(new FlatButton(BTN_WHITELIST_PREV, guiLeft + 8, guiTop + 180, 14, 16, "<"));
        buttonList.add(new FlatButton(BTN_WHITELIST_NEXT, guiLeft + 174, guiTop + 180, 14, 16, ">"));
    }

    private void buildRateTab() {
        for (int row = 0; row < 3; row++) {
            buttonList.add(new FlatButton(BTN_RATE_BASE + row * 2, guiLeft + 210, guiTop + 78 + row * 28, 18, 18, "-"));
            buttonList
                .add(new FlatButton(BTN_RATE_BASE + row * 2 + 1, guiLeft + 232, guiTop + 78 + row * 28, 18, 18, "+"));
        }
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
        Object searchConfiguration = NeiSearchBridge.configurationToken();
        // 失效条件必须覆盖所有会影响结果集的输入：查询词、数据版本、页签、
        // 排序方式、视图（全部/已选）、NEI 解析器配置。少了哪一个，哪一个变化后
        // 列表就会停在旧状态 —— 之前搜索「看起来失效」正是这里漏了排序方式，
        // 加上列表被 keyTyped 先清空，最后呈现为「打了字也没结果，点一下排序又有了」。
        if (built && now == revision
            && filterRevision == currentFilter().getRevision()
            && query.equals(lastQuery)
            && fluidTab == fluidTabCache
            && sortMode == sortModeCache
            && viewSelected == viewSelectedCache
            && searchConfiguration == lastSearchConfiguration) return;
        lastSearchConfiguration = searchConfiguration;

        revision = now;
        filterRevision = currentFilter().getRevision();
        lastQuery = query;
        fluidTabCache = fluidTab;
        sortModeCache = sortMode;
        viewSelectedCache = viewSelected;
        built = true;

        List<StorageViewEntry> source;
        if (viewSelected) {
            source = selectedEntries();
        } else {
            source = fluidTab ? ClientStorageCache.fluids() : ClientStorageCache.items();
        }
        StorageSearch.filter(source, StorageSearch.compile(query), shown);
        // 排序方式与共享终端共用一个配置；配置默认值是「数量倒序」
        StorageSort.sort(shown, sortMode);
        while (shown.size() > PAGE) {
            shown.remove(shown.size() - 1);
        }
    }

    /**
     * 「已选（白名单）」视图的数据源：当前面、当前类型的全部白名单条目。
     *
     * <p>
     * 条目优先从客户端缓存取（带真实存量）；缓存里没有的（已被取空、快照还没到）
     * 用 0 存量合成一条 —— 白名单是一个「设置」，东西不在仓库里了也应该看得见、
     * 点得掉。GT 造不出显示物品的流体条目只好跳过（和全量视图同一约束）。
     */
    private List<StorageViewEntry> selectedEntries() {
        TerminalOutputFilter filter = currentFilter();
        List<StorageViewEntry> result = new ArrayList<>();
        if (fluidTab) {
            for (FluidKey key : filter.getFluids()) {
                StorageViewEntry entry = ClientStorageCache.fluidEntry(key);
                if (entry == null) entry = StorageViewEntry.ofFluid(key, 0L);
                if (entry != null) result.add(entry);
            }
        } else {
            for (ItemKey key : filter.getItems()) {
                StorageViewEntry entry = ClientStorageCache.itemEntry(key);
                if (entry == null) entry = StorageViewEntry.ofItem(key, 0L);
                result.add(entry);
            }
        }
        return result;
    }

    private void pushConfig() {
        if (!configReady()) return;
        built = false;
        whitelist.refresh(currentFilter(), fluidTab);
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
        if (oreFilterField != null) oreFilterField.updateCursorCounter();
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
            "futa_gtnh.gui.terminal.io.tab.rate" };
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
        setEnabled(BTN_VIEW, filter);
        if (searchField != null) {
            searchField.setVisible(tab == TAB_FILTER);
            searchField.setEnabled(filter);
        }
        if (oreFilterField != null) {
            oreFilterField.setVisible(tab == TAB_FILTER && !fluidTab);
            oreFilterField.setEnabled(filter && !fluidTab);
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
        GuiButton viewButton = findButton(BTN_VIEW);
        if (viewButton != null) {
            viewButton.displayString = tr(
                viewSelected ? "futa_gtnh.gui.terminal.io.view.selected" : "futa_gtnh.gui.terminal.io.view.all");
            if (viewButton instanceof FlatButton) ((FlatButton) viewButton).setMarked(viewSelected);
        }

        // 节奏页
        for (int i = 0; i < 6; i++) {
            setEnabled(BTN_RATE_BASE + i, rate);
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
            default:
                drawRateTab();
                break;
        }
        drawTooltips(mouseX, mouseY);
    }

    /** 面板底和标题：这里在 {@code drawDefaultBackground} 之后、按钮之前，层次才对。 */
    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        com.futa_gtnh.client.ui.FutaUiTheme.panel(guiLeft, guiTop, GUI_WIDTH, ySize);
        drawBorder(guiLeft, guiTop, GUI_WIDTH, ySize, 0xFF808080);
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

        // 文本框要自己画：GuiScreen 只画 buttonList 里的按钮，输入框不在里面。
        // FutaSearchField 不画自带背景，先补一个和原版输入框观感一致的暗底描边。
        if (searchField != null && searchField.getVisible()) {
            drawRect(guiLeft + 89, guiTop + 39, guiLeft + 233, guiTop + 57, 0xFFA0A0A0);
            drawRect(guiLeft + 90, guiTop + 40, guiLeft + 232, guiTop + 56, 0xFF000000);
        }
        if (searchField != null) searchField.drawTextBox();
        if (oreFilterField != null) oreFilterField.drawTextBox();

        if (searchField != null && searchField.getVisible()
            && searchField.getText()
                .isEmpty()) {
            fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.search"), guiLeft + 93, guiTop + 44, 0x707070);
        }
        if (oreFilterField != null && oreFilterField.getVisible()
            && oreFilterField.getText()
                .isEmpty()) {
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(tr("futa_gtnh.gui.terminal.io.prefix"), 86),
                guiLeft + 203,
                guiTop + 150,
                0x707070);
        }

        if (!fluidTab) {
            fontRendererObj
                .drawString(tr("futa_gtnh.gui.terminal.io.preset.title"), guiLeft + 200, guiTop + 74, 0xC0C0C0);
            fontRendererObj
                .drawString(tr("futa_gtnh.gui.terminal.io.prefix.title"), guiLeft + 200, guiTop + 134, 0xC0C0C0);
        }
        whitelist.refresh(currentFilter(), fluidTab);
        String summary = tr("futa_gtnh.gui.terminal.io.whitelist.title") + " " + whitelist.size();
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(summary, 184), guiLeft + 8, guiTop + 145, 0xA0FFA0);
        whitelist.draw(mc, fontRendererObj, itemRender, guiLeft + 8, guiTop + 158, mouseX, mouseY);
        String pageLabel = (whitelist.page() + 1) + "/" + whitelist.pageCount();
        fontRendererObj.drawString(pageLabel, guiLeft + 88, guiTop + 184, 0xA0A0A0);
        setEnabled(BTN_WHITELIST_PREV, whitelist.page() > 0);
        setEnabled(BTN_WHITELIST_NEXT, whitelist.page() + 1 < whitelist.pageCount());
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
            // 「已选」视图下白名单本来就是空的，别拿「没有匹配的条目」误导人
            fontRendererObj.drawString(
                tr(
                    viewSelected && currentFilter().isEmpty(fluidTab) ? "futa_gtnh.gui.terminal.io.view.empty"
                        : "futa_gtnh.gui.terminal.io.empty"),
                gridX + 2,
                gridY + 2,
                0x808080);
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
                + (filter.getOreFilter()
                    .trim()
                    .isEmpty() ? 0 : 1);
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

    private void drawTooltips(int mouseX, int mouseY) {

        if (tab == TAB_FILTER && !fluidTab
            && oreFilterField != null
            && mouseX >= guiLeft + 200
            && mouseX < guiLeft + 292
            && mouseY >= guiTop + 134
            && mouseY < guiTop + 162) {
            drawHoveringText(
                java.util.Arrays.asList(
                    tr("futa_gtnh.gui.terminal.io.ore.syntax"),
                    tr("futa_gtnh.gui.terminal.io.ore.example"),
                    tr("futa_gtnh.gui.terminal.io.ore.regex")),
                mouseX,
                mouseY,
                fontRendererObj);
            return;
        }
        if (tab != TAB_FILTER) return;

        StorageViewEntry selected = whitelist.entryAt(mouseX, mouseY, guiLeft + 8, guiTop + 158);
        if (selected != null) {
            List<String> tooltip = new ArrayList<>(
                selected.getDisplay()
                    .getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips));
            tooltip.add(tr("futa_gtnh.gui.terminal.io.whitelist.remove"));
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
            return;
        }

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
            applyOreFilter();
            tab = button.id - BTN_TAB_BASE;
            // 换页签要重建控件，不然几个页签的按钮会叠在一起
            initGui();
            return;
        }

        if (button.id != BTN_DONE && !configReady()) return;

        if (button.id == BTN_WHITELIST_PREV || button.id == BTN_WHITELIST_NEXT) {
            whitelist.movePage(button.id == BTN_WHITELIST_NEXT ? 1 : -1);
            return;
        }

        if (button.id == BTN_PREV_FACE || button.id == BTN_NEXT_FACE) {
            applyOreFilter();
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
            // 排序按钮只管排序：对现有结果就地重排，不重新过滤。
            // 以前这里 built=false 强刷，把「排序」和「重新查询」耦在了一起 ——
            // 在列表卡死的场景下看起来就像「排序按钮改变了搜索结果」。
            // 排序方式与共享终端共用，改过就写进配置。
            sortMode = StorageSort.next(sortMode);
            sortModeCache = sortMode;
            Config.saveClientGuiSort(sortMode);
            StorageSort.sort(shown, sortMode);
            refreshLabels();
            return;
        }

        if (button.id == BTN_VIEW) {
            viewSelected = !viewSelected;
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

        switch (button.id) {
            case BTN_KIND:
                applyOreFilter();
                fluidTab = !fluidTab;
                shown.clear();
                initGui();
                break;
            case BTN_CLEAR:
                currentFilter().clear(fluidTab);
                if (oreFilterField != null && !fluidTab) oreFilterField.setText("");
                refreshLabels();
                pushConfig();
                // 「已选」视图下清空白名单，网格要同步清空
                if (viewSelected) {
                    built = false;
                    refreshFilter();
                }
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
            if (oreFilterField != null) oreFilterField.mouseClicked(mouseX, mouseY, mouseButton);
        }

        if (tab != TAB_FILTER || mouseButton != 0 || !configReady()) return;

        if (whitelist.removeAt(mouseX, mouseY, guiLeft + 8, guiTop + 158)) {
            pushConfig();
            refreshFilter();
            return;
        }

        int index = gridIndexAt(mouseX, mouseY);
        if (index < 0) return;

        StorageViewEntry entry = shown.get(index);
        if (entry.isFluid()) {
            currentFilter().toggleFluid(entry.getFluidKey());
        } else {
            currentFilter().toggleItem(entry.getItemKey());
        }
        pushConfig();
        // 「已选」视图里点一下 = 移出白名单，列表要跟着收缩
        if (viewSelected) {
            built = false;
            refreshFilter();
        }
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
            // 这里以前有一句 shown.clear()：不改变文本的按键（Shift/方向键/输入法组合键……）
            // 会把列表清空后撞上 refreshFilter 的早退条件，网格从此空白 —— IO 节点又不是
            // 增量观众（viewers 不含它），revision 冻结，空列表就永远停在那儿。
            // refreshFilter 自己会清空并重填 shown，这里什么都不用做。
            refreshFilter();
            return;
        }

        if (tab == TAB_FILTER && oreFilterField != null && oreFilterField.isFocused()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                mc.displayGuiScreen(null);
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN) {
                applyOreFilter();
                return;
            }
            oreFilterField.textboxKeyTyped(typedChar, keyCode);
            return;
        }

        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
        }
    }

    /** 表达式是「回车才生效」的，切页签 / 关界面时补一次。 */
    private void applyOreFilter() {
        if (tab != TAB_FILTER || fluidTab || oreFilterField == null || !oreFilterField.getVisible() || !configReady())
            return;
        if (oreFilterField.getText()
            .equals(currentFilter().getOreFilter())) return;
        currentFilter().setOreFilter(oreFilterField.getText());
        refreshLabels();
        pushConfig();
    }

    @Override
    public void onGuiClosed() {
        applyOreFilter();
        super.onGuiClosed();
    }
}
