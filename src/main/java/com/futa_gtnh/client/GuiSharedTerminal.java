package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.exchange.InventoryExchange;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketAutoStore;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * 全服共享背包的主界面。
 *
 * <p>
 * 布局分四块：搜索框（+ NEI 联动模式按钮）、共享存储网格（分页）、
 * 翻页/排序/存入按钮、玩家背包。网格是<b>虚拟槽位</b> —— 见
 * {@link ContainerSharedTerminal} 的说明 —— 但对玩家和 NEI 来说它就是普通的
 * 物品格，所以对着共享存储里的东西直接按 R/U 查配方是可以用的。
 *
 * <p>
 * 继承 {@link FutaGuiContainer}：NEI 联动（面板可见性、遮罩区）走
 * {@link NeiAwareGui} 的统一入口，输入兜底在基类，这里只声明自己的差异。
 */
public class GuiSharedTerminal extends FutaGuiContainer {

    private static final ResourceLocation TEXTURE = new ResourceLocation(
        FutaGtnhMod.MODID,
        "textures/gui/shared_terminal.png");

    // 按钮 id
    private static final int BTN_TAB_ITEMS = 0;
    private static final int BTN_TAB_FLUIDS = 1;
    private static final int BTN_PREV = 2;
    private static final int BTN_NEXT = 3;
    private static final int BTN_SORT = 4;
    private static final int BTN_STORE_ALL = 5;
    private static final int BTN_STORE_HOTBAR = 6;
    private static final int BTN_STORE_MAIN = 7;
    private static final int BTN_DRAIN = 8;
    private static final int BTN_AUTO_STORE = 9;
    /** 打开「六个面怎么主动搬东西」的配置界面（只有从方块终端打开的界面才有）。 */
    private static final int BTN_TERMINAL_IO = 10;

    /**
     * 「返还原料」：把合成栏 9 格整份退回共享存储。
     *
     * <p>
     * 放在合成栏正上方（原来「合成」小标题那一行，标题上移 8px 让位）——
     * 按钮和它管的那块格子挨着，玩家不用猜它管什么。
     */
    private static final int BTN_CRAFT_DUMP = 11;

    /** 侧栏底部那两排「一键取工具」按钮：4 列 × 2 行，每个 13px，一共 8 个。 */
    private static final int TOOL_BTN_SIZE = 13;
    /** 第一排按钮的 y（侧栏底部：产物格画到 y=222，下面这段一直是空的）。 */
    private static final int TOOL_BTN_Y = 224;
    /** 两排之间的间隔。 */
    private static final int TOOL_BTN_ROW_GAP = 1;
    /** 工具栏位按钮的 id 从这里开始往后排。 */
    private static final int BTN_TOOL_FIRST = 20;

    /**
     * 「NEI 联动模式」循环按钮（PR #4）。
     *
     * <p>
     * 原本 PR 里给的是 10，但 main 这支的 10 已经是 {@link #BTN_TERMINAL_IO}、
     * 11 是 {@link #BTN_CRAFT_DUMP}、20 往后是那排工具按钮 —— 合并时改成 12，
     * 避免和已有的按钮 id 撞车（撞了会直接按到别的按钮上）。
     */
    private static final int BTN_SEARCH_MODE = 12;

    private static final int TAB_ITEMS = 0;
    private static final int TAB_FLUIDS = 1;

    // 搜索行：框变窄是为了给右侧的「NEI 联动模式」循环按钮腾位（见 initGui）
    private static final int SEARCH_FIELD_X = 8;
    private static final int SEARCH_FIELD_WIDTH = 116;
    private static final int SEARCH_MODE_X = 126;
    private static final int SEARCH_MODE_WIDTH = 40;

    private final ContainerSharedTerminal container;

    private FutaSearchField searchField;
    private GuiButton sortButton;
    private GuiButton autoStoreButton;
    private GuiButton searchModeButton;

    /** 侧栏底部那排「一键取工具」按钮（顺序同 {@link CraftingToolShortcuts#ORES}）。 */
    private final List<GuiToolButton> toolButtons = new ArrayList<>();
    /**
     * 上一次刷按钮时用的那份工具表。
     *
     * <p>
     * {@link CraftingToolShortcuts#entries()} 按库存修订号缓存，库存没动时返回的是
     * <b>同一个 List 对象</b> —— 拿它和这一份比一下就知道要不要重新刷按钮，
     * 免得每帧都去动按钮状态。
     */
    private List<CraftingToolShortcuts.Entry> lastToolEntries;

    /** 当前过滤 + 排序后的结果，是这一页内容的来源 */
    private final List<StorageViewEntry> filtered = new ArrayList<>();
    private final List<ItemStack> pageStacks = new ArrayList<>();
    /** 和 {@link #pageStacks} 一一对应的原始键，见 pushPage 里的说明 */
    private final List<com.futa_gtnh.shared.ItemKey> pageItemKeys = new ArrayList<>();
    private final List<com.futa_gtnh.shared.FluidKey> pageFluidKeys = new ArrayList<>();

    private int tab = TAB_ITEMS;
    /**
     * 列表的行滚动偏移（每行 {@code COLS} 格，视口 {@code ROWS} 行）。
     * 1.3.1 及以前是「整页翻页」（一次 45 格），上机反馈太粗糙 —— 现在滚轮逐行。
     */
    private int scrollRow;
    /** 排序方式，跨界面/跨重启记住（见 {@link Config#guiSortMode}），默认按数量。 */
    private int sortMode = StorageSort.fromIndex(Config.guiSortMode);

    private int lastRevision = -1;
    private boolean viewDirty = true;
    private Object lastSearchConfiguration;
    /**
     * 下一次重建视图时是否<b>重新排序</b>。
     *
     * <p>
     * 只有显式动作（打开界面、点排序按钮、改搜索词、切页签）才重排；
     * 服务端增量（shift 存放导致的数量变化）只刷新数字、<b>不重排</b> ——
     * 按数量排序时每取一个东西就整片重排、鼠标底下的东西跳走，是 1.3.1
     * 上机反馈的痛点（合成站存储面板早就为同样的原因锁死了按名称）。
     */
    private boolean pendingResort = true;
    /** 按钮上当前显示的自动入库状态，用来判断服务端回包后要不要重画文字 */
    private boolean autoStoreShown;
    /** 共享格的 Shift 点击已在按下时处理，对应松开事件不再进入原版双击转移。 */
    private int sharedShiftClickButtons;

    public GuiSharedTerminal(ContainerSharedTerminal container) {
        super(container);
        this.container = container;
        this.xSize = ContainerSharedTerminal.GUI_WIDTH;
        this.ySize = ContainerSharedTerminal.GUI_HEIGHT;
    }

    /**
     * 建界面。<b>所有打开终端的地方都要走这里，不要直接 new。</b>
     *
     * <p>
     * 装了 MouseTweaks 时返回它的兼容子类
     * （{@link MouseTweaksCompat.Gui}）：那里面实现 MouseTweaks 的
     * {@code IMTModGuiContainer} 接口，把「滚轮 tweak」在这个界面上关掉 ——
     * 否则滚轮每滚一格，MouseTweaks 就会替玩家点一下鼠标下的格子，
     * 而共享存储是虚拟槽位，应该只接受本界面明确处理的快捷动作。
     *
     * <p>
     * 子类只在装了 MouseTweaks 时才被加载（{@code instanceof} 检查要求接口真的存在，
     * 没装时加载它会 NoClassDefFoundError），所以用 modid 守卫 + try/catch 兜底。
     */
    public static GuiSharedTerminal create(ContainerSharedTerminal container) {
        if (MouseTweaksCompat.isAvailable()) {
            try {
                return new MouseTweaksCompat.Gui(container);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("MouseTweaks 兼容子类创建失败，退回普通界面", t);
            }
        }
        return new GuiSharedTerminal(container);
    }

    // ==================================================================
    // 初始化
    // ==================================================================

    @Override
    public void initGui() {
        super.initGui();

        String previousQuery = searchField == null ? "" : searchField.getText();

        searchField = new FutaSearchField(
            fontRendererObj,
            guiLeft + SEARCH_FIELD_X,
            guiTop + ContainerSharedTerminal.SEARCH_Y,
            SEARCH_FIELD_WIDTH,
            12);
        // 恢复上一次的搜索词：程序性写入，不触发变更回调
        // （initGui 结束时本来就会置 viewDirty，不需要平白多刷）
        searchField.setText(previousQuery, false);
        searchField.setChangeListener(this::onSearchTextChanged);
        // 联动模式为「自动聚焦」时直接进输入状态；其余模式点一下才聚焦。
        // NEI 输入桥会在腰带等容器快捷键之前把按键交给聚焦的输入框。
        searchField.setFocused(effectiveSearchMode().autoFocus());
        // 按住退格能连删。1.7.10 的 GuiTextField 不会自己开重复事件，
        // 这里开、关界面时关（和原版 GuiEditSign 同一个套路）
        Keyboard.enableRepeatEvents(true);

        buttonList.clear();

        int tabY = guiTop + ContainerSharedTerminal.TAB_Y;
        buttonList.add(new GuiSmallButton(BTN_TAB_ITEMS, guiLeft + 7, tabY, 38, 14, tr("futa_gtnh.gui.tab.items")));
        buttonList.add(new GuiSmallButton(BTN_TAB_FLUIDS, guiLeft + 48, tabY, 38, 14, tr("futa_gtnh.gui.tab.fluids")));
        // 页签这一行的右边是空的，正好放「拾取自动入库」开关
        autoStoreButton = new GuiSmallButton(BTN_AUTO_STORE, guiLeft + 89, tabY, 80, 14, autoStoreLabel());
        buttonList.add(autoStoreButton);

        searchModeButton = new GuiSmallButton(
            BTN_SEARCH_MODE,
            guiLeft + SEARCH_MODE_X,
            guiTop + ContainerSharedTerminal.SEARCH_Y - 1,
            SEARCH_MODE_WIDTH,
            14,
            searchModeLabel());
        buttonList.add(searchModeButton);

        int navY = guiTop + ContainerSharedTerminal.NAV_Y;
        buttonList.add(new GuiSmallButton(BTN_PREV, guiLeft + 7, navY, 14, 14, "<"));
        buttonList.add(new GuiSmallButton(BTN_NEXT, guiLeft + 23, navY, 14, 14, ">"));
        sortButton = new GuiSmallButton(BTN_SORT, guiLeft + 129, navY, 40, 14, sortLabel());
        buttonList.add(sortButton);

        int buttonY = guiTop + ContainerSharedTerminal.BUTTON_Y;
        buttonList.add(new GuiSmallButton(BTN_STORE_ALL, guiLeft + 7, buttonY, 40, 14, tr("futa_gtnh.gui.store.all")));
        buttonList
            .add(new GuiSmallButton(BTN_STORE_HOTBAR, guiLeft + 50, buttonY, 36, 14, tr("futa_gtnh.gui.store.hotbar")));
        buttonList
            .add(new GuiSmallButton(BTN_STORE_MAIN, guiLeft + 89, buttonY, 34, 14, tr("futa_gtnh.gui.store.main")));
        buttonList.add(new GuiSmallButton(BTN_DRAIN, guiLeft + 126, buttonY, 43, 14, tr("futa_gtnh.gui.store.drain")));

        // 「返还原料」：合成栏正上方那一行（标签上移 8px 让它）。
        // 宽度按侧栏内沿来（CRAFT_X..CRAFT_X+54 = 177..231），和 3×3 合成栏同宽。
        //
        // 高度只给 13：合成栏第一行槽位画在 y=133，而原版 {@code getSlotAtPosition}
        // 判定时上下各放宽 1 像素（132 也算命中槽位）。按钮下沿压在 131 就既贴着合成栏、
        // 又不会出现「点按钮顺手点到槽位」。
        buttonList.add(
            new GuiSmallButton(
                BTN_CRAFT_DUMP,
                guiLeft + ContainerSharedTerminal.CRAFT_X,
                guiTop + ContainerSharedTerminal.CRAFT_DUMP_Y,
                54,
                13,
                tr("futa_gtnh.gui.craft.dump")));

        // 「一键取工具」：侧栏最底下那两排（4×2）。
        //
        // 为什么在这儿：整个界面的每一行都被占满了 —— 左列从上到下是搜索框 / 页签 /
        // 9×5 仓库网格 / 翻页 / 存入 / 状态行 / 36 格背包 / 快捷栏，一格空位都没有；
        // 侧栏在产物格（画到 y=222）下面正好剩 30px 一条空带，够放两排 13px 的图标按钮。
        toolButtons.clear();
        java.util.List<CraftingToolShortcuts.Entry> tools = CraftingToolShortcuts.entries();
        for (int i = 0; i < tools.size(); i++) {
            int col = i % 4;
            int row = i / 4;
            GuiToolButton button = new GuiToolButton(
                BTN_TOOL_FIRST + i,
                guiLeft + ContainerSharedTerminal.SIDEBAR_X + 2 + col * TOOL_BTN_SIZE,
                guiTop + TOOL_BTN_Y + row * (TOOL_BTN_SIZE + TOOL_BTN_ROW_GAP),
                TOOL_BTN_SIZE,
                tools.get(i));
            button.setEntry(tools.get(i));
            toolButtons.add(button);
            buttonList.add(button);
        }
        lastToolEntries = tools;

        // 「面配置」只在真的从方块终端打开时出现：远程打开（B 键）没有方块，也就没有面可配。
        // 位置挑在<b>右上角那块空白</b>：搜索框到 x=168 就结束了，右侧侧栏（装备/合成，
        // ARMOR_X=191、CRAFT_X=177）要到 y=26 才开始，所以 172..228 / 5..19 谁都不占。
        // 翻页行和存入行都不能放 —— 那两行 x>172 整个落在侧栏的纵向范围里，按钮会压在侧栏上。
        // 另外标签必须短：GuiButton 的标签居中，字太长会溢出到相邻按钮上，看着就是两个按钮叠在一起。
        if (container.getTerminal() != null) {
            buttonList.add(
                new GuiSmallButton(
                    BTN_TERMINAL_IO,
                    guiLeft + 172,
                    guiTop + 5,
                    56,
                    14,
                    tr("futa_gtnh.gui.store.terminal_io")));
        }

        updateTabStates();
        viewDirty = true;
        pendingResort = true;
    }

    private void updateTabStates() {
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            if (button.id == BTN_TAB_ITEMS) button.enabled = tab != TAB_ITEMS;
            if (button.id == BTN_TAB_FLUIDS) button.enabled = tab != TAB_FLUIDS;
        }
        if (sortButton != null) {
            sortButton.displayString = sortLabel();
        }
        if (autoStoreButton != null) {
            autoStoreButton.displayString = autoStoreLabel();
        }
        if (searchModeButton != null) {
            searchModeButton.displayString = searchModeLabel();
        }

        // 容器需要知道当前页签，才能决定「光标上拿着装流体的容器时点一下」该倒料还是该当物品存。
        // 在切页签的当场就同步过去，不能等到下一次 updateScreen 重建视图 ——
        // 中间那一 tick 里点一下就会按旧页签处理。
        container.setFluidTabActive(tab == TAB_FLUIDS);
    }

    private String sortLabel() {
        return tr(StorageSort.translationKey(sortMode));
    }

    /**
     * 开关按钮上的文字。
     *
     * <p>
     * 状态直接写在文字里，而不是靠「按钮变灰」表示 —— 原版的禁用样式读起来是
     * 「这个功能不可用」，而不是「这个功能开着」。
     */
    private String autoStoreLabel() {
        return tr(ClientTerminalState.isAutoStore() ? "futa_gtnh.gui.auto.on" : "futa_gtnh.gui.auto.off");
    }

    // ==================================================================
    // 搜索框与 NEI 的联动（对齐 AE2 的搜索模式，见 Config#TerminalSearchMode）
    // ==================================================================

    /**
     * 实际生效的联动模式：没装 NEI 时，同步类模式退化为「自动聚焦」——
     * 配置里保留原值，装回 NEI 后自动恢复。
     */
    private Config.TerminalSearchMode effectiveSearchMode() {
        Config.TerminalSearchMode mode = Config.terminalSearchMode;
        if (mode.neiSync() && !NeiSearchBridge.isInstalled()) return Config.TerminalSearchMode.AUTO;
        return mode;
    }

    /** 搜索词变化（键入、右键清空）的唯一入口：刷新本界面列表 + 推给 NEI。 */
    private void onSearchTextChanged(String text) {
        viewDirty = true;
        pendingResort = true;
        if (Config.terminalSearchMode.neiSync()) {
            // 桥未安装（没装 NEI）时是空操作；推不推得动由实现里的判空兜底
            NeiSearchBridge.pushSearchText(text);
        }
    }

    private String searchModeLabel() {
        return tr(
            "futa_gtnh.gui.searchmode." + Config.terminalSearchMode.name()
                .toLowerCase(Locale.ROOT));
    }

    /** 循环切换联动模式并持久化。{@code backwards} = 反向（右键）。 */
    private void cycleSearchMode(boolean backwards) {
        Config.TerminalSearchMode[] modes = Config.TerminalSearchMode.values();
        int index = Config.terminalSearchMode.ordinal();
        int next = ((index + (backwards ? -1 : 1)) % modes.length + modes.length) % modes.length;
        Config.saveTerminalSearchMode(modes[next]);
        updateTabStates();
    }

    private boolean isHoveringButton(GuiButton button, int mouseX, int mouseY) {
        return button != null && button.visible
            && mouseX >= button.xPosition
            && mouseY >= button.yPosition
            && mouseX < button.xPosition + button.width
            && mouseY < button.yPosition + button.height;
    }

    /** 搜索联动按钮的悬停说明：四个模式各自的含义 + 当前选中。 */
    private void drawSearchModeTooltip(int mouseX, int mouseY) {
        if (!isHoveringButton(searchModeButton, mouseX, mouseY)) return;

        List<String> lines = new ArrayList<>();
        lines.add(EnumChatFormatting.GOLD + tr("futa_gtnh.gui.searchmode.tip.title"));
        for (Config.TerminalSearchMode mode : Config.TerminalSearchMode.values()) {
            String marker = mode == Config.terminalSearchMode ? EnumChatFormatting.GREEN + "\u25b8 "
                : EnumChatFormatting.GRAY + "  ";
            lines.add(
                marker + tr(
                    "futa_gtnh.gui.searchmode.tip." + mode.name()
                        .toLowerCase(Locale.ROOT)));
        }
        if (!NeiSearchBridge.isInstalled()) {
            lines.add(EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.searchmode.tip.none"));
        }
        drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    // ==================================================================
    // 视图维护
    // ==================================================================

    /**
     * 重新过滤 + （按需）排序 + 重算滚动范围，并把结果推给虚拟槽位。
     *
     * <p>
     * 只在「真的变了」的时候跑：内容版本号变了、搜索词变了、排序变了、换页签了。
     * 每帧都重排几千个条目是会把帧率吃掉的。
     *
     * @param resort 是否重新排序。增量刷新（shift 存放导致的数量变化）传 false：
     *               用「旧顺序排名」稳定重排 —— 已有条目保持原位、新条目追加到
     *               尾部、消失的条目移除。直接不过滤排序的话列表会退回存储的
     *               插入序，等于变相重排；真正的排序只在显式触发时做。
     */
    private void rebuildView(boolean resort) {
        List<StorageViewEntry> source = tab == TAB_FLUIDS ? ClientStorageCache.fluids() : ClientStorageCache.items();

        if (resort) {
            StorageSearch
                .filter(source, StorageSearch.compile(searchField == null ? "" : searchField.getText()), filtered);
            StorageSort.sort(filtered, sortMode);
        } else {
            // 记录当前显示顺序，过滤后按旧排名稳定排列（新条目排到最后）。
            // 用 IdentityHashMap：条目对象是同一个引用（增量是原地 setAmount），
            // 而且 StorageViewEntry 有自定义 equals 的风险也一并规避。
            java.util.Map<StorageViewEntry, Integer> rank = new java.util.IdentityHashMap<>();
            for (int i = 0; i < filtered.size(); i++) {
                rank.put(filtered.get(i), i);
            }
            StorageSearch
                .filter(source, StorageSearch.compile(searchField == null ? "" : searchField.getText()), filtered);
            java.util.Comparator<StorageViewEntry> stable = new java.util.Comparator<StorageViewEntry>() {

                @Override
                public int compare(StorageViewEntry a, StorageViewEntry b) {
                    int ra = rank.containsKey(a) ? rank.get(a) : Integer.MAX_VALUE;
                    int rb = rank.containsKey(b) ? rank.get(b) : Integer.MAX_VALUE;
                    // 新条目彼此之间按名称兜底，避免同一批新条目顺序抖动
                    if (ra != rb) return Integer.compare(ra, rb);
                    return StorageSort.comparator(sortMode)
                        .compare(a, b);
                }
            };
            java.util.Collections.sort(filtered, stable);
        }

        clampScrollRow();
        pushPage();
        viewDirty = false;
    }

    /** @return 行滚动下标上限（含）：条目不足一屏时为 0 */
    private int maxScrollRow() {
        return Math.max(0, (filtered.size() - 1) / ContainerSharedTerminal.COLS - (ContainerSharedTerminal.ROWS - 1));
    }

    private void clampScrollRow() {
        int max = maxScrollRow();
        if (scrollRow > max) scrollRow = max;
        if (scrollRow < 0) scrollRow = 0;
    }

    private void pushPage() {
        pageStacks.clear();
        pageItemKeys.clear();
        pageFluidKeys.clear();

        int start = scrollRow * ContainerSharedTerminal.COLS;
        for (int i = 0; i < ContainerSharedTerminal.SHARED_SLOTS; i++) {
            int index = start + i;
            StorageViewEntry entry = index < filtered.size() ? filtered.get(index) : null;

            pageStacks.add(entry == null ? null : entry.getDisplay());
            // 键跟着显示物品一起送进容器。
            //
            // 不能等点击时再从显示物品反推：有些模组的 getter 会改写物品栈
            // （GT 的 MetaGeneratedTool.getToolStats 就会重写 ench），
            // 界面一画出来显示栈就已经和存档里的不一样了，反推出来的键查不到东西。
            pageItemKeys.add(entry == null ? null : entry.getItemKey());
            pageFluidKeys.add(entry == null ? null : entry.getFluidKey());
        }

        container.setPageDisplay(pageStacks, pageItemKeys, pageFluidKeys);
    }

    /** 滚动 {@code deltaRows} 行（负 = 向列表开头）。 */
    private void scrollBy(int deltaRows) {
        scrollRow += deltaRows;
        clampScrollRow();
        pushPage();
    }

    // ==================================================================
    // 每帧 / 每 tick
    // ==================================================================

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (searchField != null) {
            searchField.updateCursorCounter();
        }

        // 服务端的权威值回来了就刷新按钮文字。
        // 只在真的变了的时候写，避免每 tick 都去动字符串。
        if (autoStoreButton != null && autoStoreShown != ClientTerminalState.isAutoStore()) {
            autoStoreShown = ClientTerminalState.isAutoStore();
            autoStoreButton.displayString = autoStoreLabel();
        }

        int revision = ClientStorageCache.getRevision();
        Object searchConfiguration = NeiSearchBridge.configurationToken();
        if (searchConfiguration != lastSearchConfiguration) {
            lastSearchConfiguration = searchConfiguration;
            viewDirty = true;
        }
        if (revision != lastRevision) {
            lastRevision = revision;
            // 增量只刷新数字、不重排（见 pendingResort 的注释）
            viewDirty = true;
        }
        if (viewDirty) {
            rebuildView(pendingResort);
            pendingResort = false;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        refreshToolButtons();
        super.drawScreen(mouseX, mouseY, partialTicks);
        // 搜索框画在 super 之后：原版会把 GUI 内容盖在按钮上，
        // 而文本框不是按钮，画早了会被后面的物品格盖住
        if (searchField != null) {
            searchField.drawTextBox();
        }
        drawAutoStoreTooltip(mouseX, mouseY);
        drawSearchModeTooltip(mouseX, mouseY);
        drawToolButtonTooltip(mouseX, mouseY);
        drawLimitDialog(mouseX, mouseY);
    }

    // ==================================================================
    // 存量上限对话框（中键点格子）
    // ==================================================================

    /**
     * 正在设上限的那个条目；{@code null} 表示对话框没开。
     *
     * <p>
     * 物品和流体共用这一个对话框：在物品页签里中键点就是给物品设，在流体页签里
     * 就是给流体设 —— 条目自己知道自己是哪一种（{@link StorageViewEntry#isFluid()}）。
     */
    private StorageViewEntry limitTarget;

    /** 上限输入框。整数字符串，空 = 不限制。 */
    private FutaSearchField limitField;

    private int limitPanelLeft;
    private int limitPanelTop;

    private static final int LIMIT_W = 176;
    private static final int LIMIT_H = 78;
    private static final int LIMIT_FIELD_W = 80;

    /** @return 处理掉了就返回 true（对话框开着时吞掉所有点击） */
    private boolean handleLimitDialogClick(int mouseX, int mouseY) {
        if (limitTarget == null) return false;

        int buttonY = limitPanelTop + LIMIT_H - 22;
        if (hit(mouseX, mouseY, limitPanelLeft + 6, buttonY, 52, 16)) {
            applyLimitFromField();
            return true;
        }
        if (hit(mouseX, mouseY, limitPanelLeft + 62, buttonY, 52, 16)) {
            // 「不限」= 取消上限。和「设成 0」区别开：0 是一件都不许再进
            sendLimit(-1L);
            return true;
        }
        if (hit(mouseX, mouseY, limitPanelLeft + 118, buttonY, 52, 16)) {
            closeLimitDialog();
            return true;
        }

        // 点输入框里就交给它（能拖光标），点在面板别处不关，点面板外关掉
        if (limitField != null && hit(mouseX, mouseY, limitPanelLeft + 8, limitPanelTop + 34, LIMIT_FIELD_W, 16)) {
            limitField.mouseClicked(mouseX, mouseY, 0);
            return true;
        }
        if (!hit(mouseX, mouseY, limitPanelLeft, limitPanelTop, LIMIT_W, LIMIT_H)) {
            closeLimitDialog();
        }
        return true;
    }

    /** @return 处理掉了就返回 true */
    private boolean handleLimitDialogKey(char typedChar, int keyCode) {
        if (limitTarget == null) return false;

        if (keyCode == Keyboard.KEY_ESCAPE) {
            closeLimitDialog();
            return true;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            applyLimitFromField();
            return true;
        }
        // 只收数字和退格：这个框里没有别的东西是合法的，
        // 放字母进来只会让「确定」按下去才发现解析失败
        if (limitField != null) {
            limitField.textboxKeyTyped(typedChar, keyCode);
        }
        return true;
    }

    private void applyLimitFromField() {
        if (limitField == null) {
            closeLimitDialog();
            return;
        }
        String text = limitField.getText();
        if (text == null || text.trim()
            .isEmpty()) {
            // 空框按「不限」处理 —— 这正是大多数人对「把上限删掉」的直觉
            sendLimit(-1L);
            return;
        }
        long limit;
        try {
            limit = Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            // 保留对话框，把话说清楚。直接关掉的话玩家只会觉得「按了没反应」
            limitError = "请填一个整数（留空或点「不限」= 取消上限）";
            return;
        }
        sendLimit(Math.max(0L, limit));
    }

    /** 输入不合法时显示在面板里的一句话；为空表示没有错误。 */
    private String limitError;

    private void sendLimit(long limit) {
        StorageViewEntry entry = limitTarget;
        closeLimitDialog();
        if (entry == null) return;

        if (entry.isFluid()) {
            FluidKey key = entry.getFluidKey();
            if (key != null) {
                NetworkHandler.INSTANCE
                    .sendToServer(PacketStorageAction.fluid(PacketStorageAction.SET_FLUID_LIMIT, key, limit));
            }
        } else {
            ItemKey key = entry.getItemKey();
            if (key != null) {
                NetworkHandler.INSTANCE
                    .sendToServer(PacketStorageAction.item(PacketStorageAction.SET_ITEM_LIMIT, key, limit));
            }
        }
    }

    private void closeLimitDialog() {
        limitTarget = null;
        if (limitField != null) limitField.setFocused(false);
    }

    /** 中键点共享网格：给这一格的东西设上限。 */
    private boolean handleLimitOpenClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 2 || limitTarget != null) return false;

        int index = hoveredGhostIndex(mouseX, mouseY);
        if (index < 0) return false;
        int absolute = scrollRow * ContainerSharedTerminal.COLS + index;
        if (absolute < 0 || absolute >= filtered.size()) return false;

        StorageViewEntry entry = filtered.get(absolute);
        if (entry == null) return false;

        limitTarget = entry;
        limitError = null;
        if (limitField == null) {
            limitField = new FutaSearchField(fontRendererObj, 0, 0, LIMIT_FIELD_W, 16);
        }
        limitField.setMaxStringLength(18);
        Long current = entry.isFluid() ? ClientStorageCache.getFluidLimit(entry.getFluidKey())
            : ClientStorageCache.getItemLimit(entry.getItemKey());
        // 预填当前上限；没设过就预填现有存量 —— 大多数人想设的就是「别再多了」
        long prefill = current != null ? current.longValue() : entry.getAmount();
        limitField.setText(String.valueOf(prefill), false);
        limitField.setFocused(true);

        limitPanelLeft = Math.max(4, Math.min(width - LIMIT_W - 4, mouseX - LIMIT_W / 2));
        limitPanelTop = Math.max(4, Math.min(height - LIMIT_H - 4, mouseY + 8));
        limitField.xPosition = limitPanelLeft + 8;
        limitField.yPosition = limitPanelTop + 34;
        return true;
    }

    /**
     * 画对话框。
     *
     * <p>
     * 这是玩家唯一能看到「这个东西最多能存多少」的地方，所以除了输入框，
     * 还要把<b>现有存量</b>摆在旁边：设上限时最要紧的判断就是「现在有多少」。
     */
    private void drawLimitDialog(int mouseX, int mouseY) {
        if (limitTarget == null) return;

        int left = limitPanelLeft;
        int top = limitPanelTop;

        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        drawRect(left - 1, top - 1, left + LIMIT_W + 1, top + LIMIT_H + 1, 0xFF000000);
        drawRect(left, top, left + LIMIT_W, top + LIMIT_H, 0xFF1A1C20);
        drawRect(left, top, left + LIMIT_W, top + 16, 0xFF2A2E36);

        boolean fluid = limitTarget.isFluid();
        String title = fluid ? "流体存量上限" : "物品存量上限";
        fontRendererObj.drawStringWithShadow(title, left + 6, top + 4, 0xFFFFFF);

        String name = limitTarget.getDisplayName();
        if (name != null && fontRendererObj.getStringWidth(name) > LIMIT_W - 16) {
            name = fontRendererObj.trimStringToWidth(name, LIMIT_W - 16) + "…";
        }
        fontRendererObj.drawStringWithShadow(String.valueOf(name), left + 8, top + 20, 0xE0E0E0);

        long have = limitTarget.getAmount();
        Long current = fluid ? ClientStorageCache.getFluidLimit(limitTarget.getFluidKey())
            : ClientStorageCache.getItemLimit(limitTarget.getItemKey());
        String haveText = "现有 " + GuiSharedTerminal.formatShort(have)
            + (current == null ? "（当前不限）" : "（上限 " + GuiSharedTerminal.formatShort(current.longValue()) + "）");
        fontRendererObj.drawStringWithShadow(haveText, left + 8, top + LIMIT_H - 38, 0xA0A0A0);

        if (limitError != null) {
            fontRendererObj.drawStringWithShadow(limitError, left + 8, top + 52, 0xFF5555);
        }

        if (limitField != null) limitField.drawTextBox();

        int buttonY = top + LIMIT_H - 22;
        drawLimitButton(left + 6, buttonY, 52, "确定", hit(mouseX, mouseY, left + 6, buttonY, 52, 16), 0xFF3E7D3E);
        drawLimitButton(left + 62, buttonY, 52, "不限", hit(mouseX, mouseY, left + 62, buttonY, 52, 16), 0xFF3A3F47);
        drawLimitButton(left + 118, buttonY, 52, "取消", hit(mouseX, mouseY, left + 118, buttonY, 52, 16), 0xFF3A3F47);

        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private void drawLimitButton(int x, int y, int w, String label, boolean hovered, int color) {
        drawRect(x, y, x + w, y + 16, hovered ? brighten(color) : color);
        drawRect(x, y, x + w, y + 1, 0xFF000000);
        drawRect(x, y + 15, x + w, y + 16, 0xFF000000);
        int textWidth = fontRendererObj.getStringWidth(label);
        fontRendererObj.drawStringWithShadow(label, x + (w - textWidth) / 2, y + 4, 0xFFFFFF);
    }

    private static int brighten(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 30);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 30);
        int b = Math.min(255, (argb & 0xFF) + 30);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static boolean hit(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /**
     * 按当前库存刷新那排工具按钮（能不能取、取的是哪一种）。
     *
     * <p>
     * 界面开着的时候仓库也可能在变（自动入库、别的终端在搬），所以每帧对一下修订号 ——
     * 没变就是一个引用比较，不产生任何分配。
     */
    private void refreshToolButtons() {
        List<CraftingToolShortcuts.Entry> entries = CraftingToolShortcuts.entries();
        if (entries == lastToolEntries) return;

        lastToolEntries = entries;
        for (int i = 0; i < toolButtons.size() && i < entries.size(); i++) {
            toolButtons.get(i)
                .setEntry(entries.get(i));
        }
    }

    /** 工具按钮的悬停说明：工具叫什么、点了会怎样、仓库里有没有。 */
    private void drawToolButtonTooltip(int mouseX, int mouseY) {
        for (GuiToolButton button : toolButtons) {
            if (!button.func_146115_a()) continue;

            CraftingToolShortcuts.Entry entry = button.entry();
            if (entry == null) return;

            String name = CraftingToolShortcuts.displayName(entry);
            drawHoveringText(
                java.util.Arrays.asList(
                    EnumChatFormatting.GOLD + (name.isEmpty() ? entry.ore : name),
                    EnumChatFormatting.GRAY
                        + tr(entry.isAvailable() ? "futa_gtnh.gui.tool.take" : "futa_gtnh.gui.tool.missing")),
                mouseX,
                mouseY,
                fontRendererObj);
            return;
        }

        drawSearchModeTooltip(mouseX, mouseY);
    }

    /**
     * 自动入库开关的悬停说明。
     *
     * <p>
     * 原版 {@code GuiButton} 不带 tooltip，而「自动入库」这四个字光看按钮本身
     * 说明不了它到底把东西收进哪儿、开关状态存在哪，所以自己画一个。
     */
    private void drawAutoStoreTooltip(int mouseX, int mouseY) {
        if (autoStoreButton == null || !autoStoreButton.func_146115_a()) return;

        drawHoveringText(
            java.util.Arrays.asList(
                EnumChatFormatting.GOLD + tr("futa_gtnh.gui.auto.tip.title"),
                EnumChatFormatting.GRAY + tr("futa_gtnh.gui.auto.tip.on"),
                EnumChatFormatting.GRAY + tr("futa_gtnh.gui.auto.tip.off"),
                EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.auto.tip.persist")),
            mouseX,
            mouseY,
            fontRendererObj);
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager()
            .bindTexture(TEXTURE);
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);

        // 聚焦时把搜索框描一圈亮边 —— 得让玩家一眼看出「现在敲键盘是往这里输」
        // 还是「不在输入状态」
        if (searchField != null && searchField.isFocused()) {
            drawRect(
                guiLeft + SEARCH_FIELD_X - 1,
                guiTop + ContainerSharedTerminal.SEARCH_Y - 1,
                guiLeft + SEARCH_FIELD_X - 1 + SEARCH_FIELD_WIDTH + 2,
                guiTop + ContainerSharedTerminal.SEARCH_Y,
                0xFF55FF55);
            drawRect(
                guiLeft + SEARCH_FIELD_X - 1,
                guiTop + ContainerSharedTerminal.SEARCH_Y + 12,
                guiLeft + SEARCH_FIELD_X - 1 + SEARCH_FIELD_WIDTH + 2,
                guiTop + ContainerSharedTerminal.SEARCH_Y + 13,
                0xFF55FF55);
        }

        // 搜索框的输入提示：只在空的时候显示（截到框宽，别压到右边的联动按钮上）
        if (searchField != null && searchField.getText()
            .isEmpty()) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.DARK_GRAY
                    + fontRendererObj.trimStringToWidth(tr("futa_gtnh.gui.search.hint"), SEARCH_FIELD_WIDTH - 4),
                guiLeft + SEARCH_FIELD_X + 1,
                guiTop + ContainerSharedTerminal.SEARCH_Y + 2,
                0xFFFFFF);
        }
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        // 这一层已经在 (guiLeft, guiTop) 的平移矩阵里了，坐标直接用 GUI 相对坐标

        // ---- 滚动位置信息 ----
        // 行滚动模型下按「屏」换算：一屏 = ROWS 行。用 translateToLocalFormatted
        // 而不是拼字符串：中文的语序是「第 1/5 页」，把「页」放在前缀里拼出来
        // 会变成「第 1/5」，少一个字
        String pageText = StatCollector.translateToLocalFormatted(
            "futa_gtnh.gui.page",
            filtered.isEmpty() ? 0 : scrollRow / ContainerSharedTerminal.ROWS + 1,
            filtered.isEmpty() ? 0 : maxScrollRow() / ContainerSharedTerminal.ROWS + 1);
        fontRendererObj.drawString(pageText, 42, ContainerSharedTerminal.NAV_Y + 3, 0x404040);

        String typeText = StatCollector.translateToLocalFormatted(
            tab == TAB_FLUIDS ? "futa_gtnh.gui.types.fluid" : "futa_gtnh.gui.types.item",
            filtered.size());
        fontRendererObj.drawString(
            typeText,
            42 + fontRendererObj.getStringWidth(pageText) + 6,
            ContainerSharedTerminal.NAV_Y + 3,
            0x707070);

        // ---- 状态行：只说「这个终端在干什么」 ----
        //
        // 以前这里优先显示鼠标指着的那一条的完整数量（物品名 × 1234567），本意是让大数量
        // 能看全。实际用起来是反效果：格子右下角已经有一份紧凑数量（1.23M 这种），
        // 悬停时下面又冒出同一份数字，看着像「同一个数量显示了两遍」，还容易被当成
        // 界面画重了。数量就只在格子里显示，这一行留给状态。
        String status;
        if (container.isRemoteAccess()) {
            status = EnumChatFormatting.GRAY + tr("futa_gtnh.gui.status.remote");
        } else {
            status = EnumChatFormatting.GRAY + tr("futa_gtnh.gui.status.output");
        }
        // 状态行只占左区宽度：GUI 变宽是因为右边多了侧栏，
        // 状态文字要是按整幅宽度去截，长物品名会横穿到侧栏上面去
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(status, ContainerSharedTerminal.SIDEBAR_X - 16),
            8,
            ContainerSharedTerminal.STATUS_Y,
            0x404040);

        drawSidebarLabels();

        // ---- 每一格的数量 ----
        drawSlotAmounts();
    }

    /** 侧栏那两块区域的小标题，让「装备」和「合成」不会和存储格看混。 */
    private void drawSidebarLabels() {
        String armor = tr("futa_gtnh.gui.armor");
        fontRendererObj.drawString(
            armor,
            ContainerSharedTerminal.ARMOR_X + 9 - fontRendererObj.getStringWidth(armor) / 2,
            ContainerSharedTerminal.ARMOR_LABEL_Y,
            0x404040);

        String crafting = tr("futa_gtnh.gui.crafting");
        fontRendererObj.drawString(
            crafting,
            // 合成栏是 3×3，居中按它自己的宽度算（侧栏比格子窄，不能用 ARMOR_X）
            ContainerSharedTerminal.CRAFT_X + ContainerSharedTerminal.CRAFT_SLOTS * 9
                - fontRendererObj.getStringWidth(crafting) / 2,
            ContainerSharedTerminal.CRAFT_LABEL_Y,
            0x404040);
    }

    /**
     * 格子右下角那行文字：有上限时写成「现有/上限」。
     *
     * <p>
     * 只在设过上限时才变长 —— 没设过的条目显示得和以前一模一样，
     * 不然整片格子都被无关的数字挤满。
     */
    static String amountText(StorageViewEntry entry) {
        String have = formatShort(entry.getAmount());
        Long limit = limitOf(entry);
        if (limit == null) return have;
        return have + "/" + formatShort(limit.longValue());
    }

    /**
     * 这行文字的颜色：到达上限时标红。
     *
     * <p>
     * 玩家最需要一眼看出来的就是「这个再也进不来了」—— 上限的用处全在这里，
     * 藏进提示里等于没做。
     */
    static int amountColor(StorageViewEntry entry) {
        Long limit = limitOf(entry);
        if (limit == null) return 0xFFFFFF;
        return entry.getAmount() >= limit.longValue() ? 0xFF5555 : 0xFFFFFF;
    }

    private static Long limitOf(StorageViewEntry entry) {
        if (entry == null) return null;
        if (entry.isFluid()) return ClientStorageCache.getFluidLimit(entry.getFluidKey());
        return ClientStorageCache.getItemLimit(entry.getItemKey());
    }

    /** 在网格里每一格的右下角画出「有多少」。 */
    private void drawSlotAmounts() {
        int start = scrollRow * ContainerSharedTerminal.COLS;
        for (int i = 0; i < ContainerSharedTerminal.SHARED_SLOTS; i++) {
            int index = start + i;
            if (index >= filtered.size()) break;

            StorageViewEntry entry = filtered.get(index);
            int col = i % ContainerSharedTerminal.COLS;
            int row = i / ContainerSharedTerminal.COLS;

            // +1 是为了和槽位对齐：Slot 的物品画在 GRID_X + 1 + col*18，
            // 少这个 +1 的话数量文字会整体偏左上 1 像素
            int x = ContainerSharedTerminal.GRID_X + 1 + col * 18;
            int y = ContainerSharedTerminal.GRID_Y + 1 + row * 18;

            drawAmountText(amountText(entry), x, y, amountColor(entry));
        }
    }

    /**
     * 把数量文字画在格子右下角。
     *
     * <p>
     * 位数多了就整体缩小，而不是截断 —— 看到 {@code 12.3M} 和看到 {@code 12.3…}
     * 完全是两回事。缩放矩阵用完必须还原，否则后面所有绘制都会跟着变小。
     */
    private void drawAmountText(String text, int slotX, int slotY, int color) {
        if (text == null || text.isEmpty()) return;

        float scale = text.length() > 6 ? 0.5F : (text.length() > 4 ? 0.75F : 1.0F);
        int width = fontRendererObj.getStringWidth(text);

        GL11.glPushMatrix();
        GL11.glTranslatef(slotX + 17.0F, slotY + 17.0F, 300.0F);
        GL11.glScalef(scale, scale, 1.0F);
        fontRendererObj.drawStringWithShadow(text, -width, -8, color);
        GL11.glPopMatrix();
    }

    // ==================================================================
    // 输入
    // ==================================================================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        // 上限对话框优先：开着的时候它吞掉所有点击，别让点击穿到下面的格子上去
        if (handleLimitDialogClick(mouseX, mouseY)) return;
        // 先更新焦点，按钮、批量操作等提前返回的路径也必须能失焦。
        if (handleSearchClick(mouseX, mouseY, mouseButton)) return;
        // 中键点共享格子 = 设存量上限（物品页签给物品设，流体页签给流体设）
        if (handleLimitOpenClick(mouseX, mouseY, mouseButton)) return;

        if (mouseButton == 0 || mouseButton == 1) {
            // 若窗口外松开时丢了事件，新一次按下不能继承上一次的释放标记。
            sharedShiftClickButtons &= ~(1 << mouseButton);
        }
        // 搜索联动按钮的右键反向循环：原版按钮只认左键，这里自己接。
        // 在 super 之前消费掉，避免这记右键继续落进容器/搜索框。
        if (mouseButton == 1 && isHoveringButton(searchModeButton, mouseX, mouseY)) {
            cycleSearchMode(true);
            return;
        }

        // Shift + 左键点产物格 = 批量合成（服务端一路补料），这一条必须拦在原版前面
        if (handleCraftResultClick(mouseX, mouseY, mouseButton)) return;

        // Inventory Bogo Sorter 的 Ctrl / Alt / Space 快捷键最终也是在这里落地。
        // 共享网格是虚拟槽位，不能让它继续走 Bogo 的本地 putStack 路径；先转换成
        // 我们自己的服务端权威动作，普通点击仍交给 GuiContainer。
        if (handleShortcutClick(mouseX, mouseY, mouseButton)) return;
        if (handleShiftDoubleClick(mouseX, mouseY, mouseButton)) return;
        if (handleSharedShiftClick(mouseX, mouseY, mouseButton)) return;

        // 「Shift + 按住左键滑动存入」只从背包上按下才算数：这样
        // 「Shift 点共享格取出 → 顺手划过背包」不会把刚取出来的东西又存回去。
        // 每次左键按下都重算一遍，鼠标在窗口外松开导致丢事件时也不会一直「武装」着
        if (mouseButton == 0) {
            Slot pressed = hoveredContainerSlot(mouseX, mouseY);
            sweepArmed = isShiftKeyDown() && isBackpackSlot(pressed);
            // 按下那一格交给原版的 Shift 单击处理，滑动不必再补一次
            if (sweepArmed) sweepSlot = pressed.slotNumber;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    /** NEI 的被动点击钩子也调用此处，面板消费的框外点击同样能失焦。 */
    public boolean handleSearchClick(int mouseX, int mouseY, int mouseButton) {
        if (limitTarget != null || searchField == null || (mouseButton != 0 && mouseButton != 1)) return false;
        boolean inside = searchField.contains(mouseX, mouseY);
        boolean before = searchField.isFocused();
        searchField.mouseClicked(mouseX, mouseY, mouseButton);
        if (before != searchField.isFocused()) viewDirty = true;
        return inside;
    }

    /** 聚焦输入框优先于 NEI/模组快捷键；未聚焦时保留原来的快捷键行为。 */
    public boolean handleSearchKey(char typedChar, int keyCode) {
        if (handleLimitDialogKey(typedChar, keyCode)) return true;
        if (searchField == null || !searchField.isFocused() || keyCode == Keyboard.KEY_ESCAPE) return false;
        searchField.textboxKeyTyped(typedChar, keyCode);
        // 即使字符因长度上限或输入规则未写入，也不能触发腰带、背包等快捷键。
        return true;
    }

    /** NEI 拖放写入按搜索模式转义的名称，使用与键入相同的过滤/同步回调。 */
    public boolean acceptSearchDrop(int mouseX, int mouseY, ItemStack stack) {
        if (limitTarget != null || searchField == null || stack == null || !searchField.contains(mouseX, mouseY))
            return false;
        searchField.setText(NeiSearchBridge.escapedSearchText(stack));
        searchField.setFocused(true);
        viewDirty = true;
        return true;
    }

    /**
     * 虚拟槽位取出后仍有显示栈，原版 Shift 双击会在松开时再转移一次。
     * 因此共享格的空手 Shift 点击直接派发一次，并消费对应的释放事件。
     * 普通点击和玩家背包的双击仍沿用各自的处理路径。
     */
    private boolean handleSharedShiftClick(int mouseX, int mouseY, int mouseButton) {
        if ((mouseButton != 0 && mouseButton != 1) || !isShiftKeyDown()
            || mc.thePlayer.inventory.getItemStack() != null) return false;
        // 18×18 网格单元包含原版槽位命中的一像素边缘，边缘点击也必须走同一条路径。
        int index = hoveredGhostIndex(mouseX, mouseY);
        if (index < 0) return false;
        Slot slot = container.getSlot(index);

        sweepArmed = false;
        sweepSlot = -1;
        sharedShiftClickButtons |= 1 << mouseButton;
        handleMouseClick(slot, slot.slotNumber, mouseButton, 1);
        return true;
    }

    // ==================================================================
    // Shift 批量存入：双击整类 + 按住滑动
    // ==================================================================

    /** 认双击的时间窗口，和原版 {@code GuiContainer} 用的一致。 */
    private static final long DOUBLE_CLICK_MS = 250L;

    /** 上一次「Shift 单击存入」的槽位 / 时刻 / 物品，用来认双击。 */
    private int lastShiftClickSlot = -1;
    private long lastShiftClickTime;
    private ItemKey lastShiftClickKey;

    /** 这一次按住鼠标是不是「从背包开始的 Shift + 左键」——只有它才允许滑动存入。 */
    private boolean sweepArmed;
    /** 上一次滑动扫过的槽位，避免同一格反复发请求。 */
    private int sweepSlot = -1;

    /**
     * Shift + 双击背包里的格子 = 把背包里<b>同种</b>物品一次全存进去。
     *
     * <p>
     * <b>为什么不能指望原版那套。</b>1.7.10 的 {@code GuiContainer} 确实写了
     * 「Shift + 双击 = 把同一个背包里所有同类物品都快速移动一遍」
     * （{@code mouseMovedOrUp} 里那个循环），但它依赖第一次点击时记住的那一叠
     * （{@code field_146994_N}）—— 而第一次点击<b>已经把那一格存走了</b>：
     * 第二次点击看到的是空格子，原版就把那个字段清成 null，于是松开鼠标时整个循环
     * 直接跳过。表现就是「只有点的那一格进了仓库，同类的其它叠都还留在背包里」。
     *
     * <p>
     * 所以这里自己在<b>第二次点击</b>时发一个「按条目存入全部」的服务端请求，
     * 并且吃掉这次点击（不交给原版，原版那套也就不会再跑一遍）。
     * 数量由服务端按背包实际内容封顶，客户端说了不算 —— 这条和别处一致。
     *
     * @return true 表示这次点击已经被处理掉，底层界面不该再看到
     */
    private boolean handleShiftDoubleClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 || !isShiftKeyDown()) return false;
        // 光标上举着东西时，Shift 点击是「放下」而不是「存走」
        if (mc.thePlayer.inventory.getItemStack() != null) return false;

        Slot slot = hoveredContainerSlot(mouseX, mouseY);
        if (!isBackpackSlot(slot)) return false;

        long now = System.currentTimeMillis();
        if (slot.slotNumber == lastShiftClickSlot && now - lastShiftClickTime <= DOUBLE_CLICK_MS) {
            ItemKey key = lastShiftClickKey;
            lastShiftClickSlot = -1;
            lastShiftClickTime = 0L;
            lastShiftClickKey = null;

            if (key != null) {
                // 0 = 有多少存多少（服务端按背包实际内容封顶）
                container.requestDepositMatching(key, 0L);
            }
            return true;
        }

        // 第一下：趁槽位里还有东西，把「这是哪一种物品」记下来
        // （这一下本身照旧由原版的 Shift 单击处理：存那一格）
        lastShiftClickSlot = slot.slotNumber;
        lastShiftClickTime = now;
        lastShiftClickKey = slot.getStack() == null ? null : ItemKey.of(slot.getStack());
        return false;
    }

    /**
     * Shift + 按住左键在背包上滑动 = 滑到哪一格就把哪一格整叠存进去。
     *
     * <p>
     * <b>为什么要自己写。</b>原版的拖拽协议（{@code Container.slotClick} 的 mode 5）
     * 只在<b>光标上举着东西</b>时才发：{@code GuiContainer.mouseClickMove} 第一句就是
     * 「光标里没有物品就什么也不做」。所以「空着手按住 Shift 划过背包」在原版里
     * 一个包都不会发出去，只能由界面这一层把它转成存入请求。
     *
     * <p>
     * 每一格只发一次（靠 {@link #sweepSlot} 去重）：滑过去之后那一格已经空了，
     * 反复发请求除了刷包没有任何意义。
     */
    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if ((clickedMouseButton == 0 || clickedMouseButton == 1)
            && (sharedShiftClickButtons & (1 << clickedMouseButton)) != 0) return;
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);

        if (!sweepArmed || clickedMouseButton != 0 || !isShiftKeyDown()) return;
        // 光标上举着东西时那是原版的「拖动分配物品」，不抢
        if (mc.thePlayer.inventory.getItemStack() != null) return;

        Slot slot = hoveredContainerSlot(mouseX, mouseY);
        if (!isBackpackSlot(slot) || slot.slotNumber == sweepSlot) return;

        sweepSlot = slot.slotNumber;
        if (slot.getStack() == null) return;

        // 和 Shift 单击走同一条路：整叠存入，数量与守恒由服务端裁决
        container.sendDepositFromSlot(slot.slotNumber, 0L);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        if ((state == 0 || state == 1) && (sharedShiftClickButtons & (1 << state)) != 0) {
            sharedShiftClickButtons &= ~(1 << state);
            sweepArmed = false;
            sweepSlot = -1;
            return;
        }
        super.mouseMovedOrUp(mouseX, mouseY, state);
        // 松开鼠标 = 这一轮滑动结束（state 是松开的那个键）
        sweepArmed = false;
        sweepSlot = -1;
    }

    /**
     * 这一格是不是玩家自己的 36 格背包（主背包 + 快捷栏）。
     *
     * <p>
     * <b>护甲和合成栏故意不算。</b>滑动存入是一条会一路扫过去的手势，
     * 顺手把身上的装备扒进仓库、或者把刚摆好的合成材料扫空，都不是玩家想要的；
     * 而且 {@code DEPOSIT_MATCHING} 在服务端也只遍历玩家的 36 格背包。
     * 这两种格子仍然可以照旧用 Shift 单击逐个存。
     */
    private boolean isBackpackSlot(Slot slot) {
        return slot != null && slot.slotNumber >= ContainerSharedTerminal.MAIN_START
            && slot.slotNumber < ContainerSharedTerminal.MAIN_END;
    }

    /**
     * Shift + 左键点<b>产物格</b> = 一次把合成栏里现有的原料做完：把现在这个摆法交给
     * 服务端，由它把整批合成跑完（见 {@link ContainerSharedTerminal#requestCraftFromGrid()}）。
     * <b>不动合成栏、也不从共享存储补料</b> —— 一次点击最多消耗掉你摆进去的那些。
     *
     * <p>
     * <b>为什么要在界面这一层拦住、不让原版去点。</b>原版 {@code slotClick} 的 Shift
     * 分支靠 {@code retrySlotClick} 反复「再点一次产物格」，每做一次都要客户端和服务端
     * 来回一趟；我们这条是「一次点击、服务端跑完整批」，快得多，也不会出现做了一半两边
     * 状态对不上。
     *
     * @return true 表示这次点击已经被处理掉，底层界面不该再看到
     */
    private boolean handleCraftResultClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 || !isShiftKeyDown()) return false;
        // 其它修饰键各有各的语义（Bogo 的搬运快捷键），先让它们落地
        if (isCtrlDown() || isAltDown() || isSpaceDown()) return false;
        // 光标上举着东西时的 Shift 点击是原版的「放下」，不抢
        if (mc.thePlayer.inventory.getItemStack() != null) return false;

        Slot slot = hoveredContainerSlot(mouseX, mouseY);
        if (slot == null || slot.slotNumber != ContainerSharedTerminal.RESULT_SLOT) return false;

        // 合成栏是空的时候没有配方可谈，交回原版（那边同样什么都不会做）
        return container.requestCraftFromGrid();
    }

    /**
     * 把 Bogo Sorter 的三种搬运快捷键映射成共享存储动作。
     *
     * <p>
     * 物品页的空格按 Bogo 语义把共享物品尽量转入玩家背包，Alt 只转当前同类；
     * 流体页则保留当前流体条目的填装语义。
     */
    private boolean handleShortcutClick(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0 && mouseButton != 1) return false;
        if (!isCtrlDown() && !isAltDown() && !isSpaceDown()) return false;
        if (mc.thePlayer.inventory.getItemStack() != null) return false;

        StorageViewEntry entry = getHoveredEntry(mouseX, mouseY);
        if (entry != null) {
            int index = hoveredGhostIndex(mouseX, mouseY);
            if ((isSpaceDown() || isAltDown()) && mouseButton != 0) return false;
            if (isSpaceDown()) {
                if (entry.isFluid()) container.requestWithdrawFromDisplay(index, 0L);
                else container.sendWithdrawAllItems();
                return true;
            }
            if (isCtrlDown()) {
                if (mouseButton == 1 && !entry.isFluid()) container.requestWithdrawFromDisplayToEmpty(index);
                else container.requestWithdrawFromDisplay(index, entry.isFluid() ? Config.fluidClickAmount : 1L);
                return true;
            }
            if (isAltDown()) {
                container.requestWithdrawFromDisplay(index, 0L);
                return true;
            }
        }

        Slot slot = hoveredContainerSlot(mouseX, mouseY);
        if (isSpaceDown()) {
            if (mouseButton != 0 || !isBackpackSlot(slot)) return false;
            container.sendDepositAll(InventoryExchange.SCOPE_MAIN);
            return true;
        }
        if (!isTransferSlot(slot) || slot.getStack() == null) return false;
        if (isAltDown() && isPlayerInventorySlot(slot)) {
            container.requestDepositMatching(slot.getStack(), 0L);
            return true;
        }
        if (isCtrlDown()) {
            container.sendDepositFromSlot(slot.slotNumber, 1L);
            return true;
        }
        return false;
    }

    private boolean isTransferSlot(Slot slot) {
        return slot != null && slot.slotNumber >= ContainerSharedTerminal.MAIN_START
            && slot.slotNumber < ContainerSharedTerminal.CRAFT_END;
    }

    private boolean isPlayerInventorySlot(Slot slot) {
        return slot != null && slot.slotNumber >= ContainerSharedTerminal.MAIN_START
            && slot.slotNumber < ContainerSharedTerminal.ARMOR_END;
    }

    private boolean isCtrlDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
    }

    private boolean isAltDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    private boolean isSpaceDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_SPACE);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();

        int delta = Mouse.getEventDWheel();
        if (delta == 0) return;

        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;

        // Shift + 滚轮 = 单个物品快速存取，不滚动列表。
        if (isShiftKeyDown()) {
            handleQuickWheel(mouseX, mouseY, delta);
            return;
        }

        // 触发区域从「网格内」扩大为网格包围盒外扩一圈（上机反馈不容易滚到）；
        // 不在区域里就不滚动，免得在侧栏/背包那边误翻
        if (!isInsideScrollRegion(mouseX, mouseY)) return;

        // 滚轮方向沿用工程约定：向上 = 向列表开头。格数双语义兼容：
        // lwjgl3ify 的 getEventDWheel() 一格返回 ±1，纯 LWJGL2 是 ±120
        int notches = Math.abs(delta) >= 120 ? delta / 120 : delta;
        scrollBy(-notches);
    }

    /**
     * Shift + 滚轮快速存取：向下从共享存储拿一个，向上向共享存储放一个。
     *
     * <p>
     * 一个 LWJGL 滚轮刻度对应一个物品；旧版 LWJGL2 的 ±120 在这里先折算成刻度数。
     * 流体页签仍以毫巴/容器为单位，避免把「一个流体显示条目」误发送成 1 mB。
     */
    private void handleQuickWheel(int mouseX, int mouseY, int delta) {
        int notches = Math.abs(delta) >= 120 ? delta / 120 : delta;
        if (notches == 0) return;

        StorageViewEntry entry = getHoveredEntry(mouseX, mouseY);
        if (entry != null && notches < 0) {
            int index = hoveredGhostIndex(mouseX, mouseY);
            long amount = entry.isFluid() ? Config.fluidClickAmount : 1L;
            for (int i = 0; i < -notches; i++) {
                container.requestWithdrawFromDisplay(index, amount);
            }
            return;
        }

        if (entry != null && notches > 0) {
            // 流体条目不是一个可拆分的物品堆，向上滚轮不伪造「存入 1 个显示物品」。
            // 物品条目则按 Bogo/InvTweaks 的单个物品语义，从玩家背包找一个同类存入。
            if (!entry.isFluid()) {
                ItemStack display = entry.getDisplay();
                for (int i = 0; i < notches; i++) {
                    container.requestDepositMatching(display, 1L);
                }
            }
            return;
        }

        // 向上滚轮悬停自己的真实槽位时，也表示放入一个；向下不从真实槽位反向拿取。
        Slot slot = hoveredContainerSlot(mouseX, mouseY);
        if (notches > 0 && isTransferSlot(slot)) {
            for (int i = 0; i < notches; i++) {
                container.sendDepositFromSlot(slot.slotNumber, 1L);
            }
        }
    }

    /**
     * 自查鼠标下的容器槽位。
     *
     * <p>
     * 原版 {@code GuiContainer.getSlotAtPosition} 是 private，这里按同样的
     * 16×16 规则遍历一遍（槽位坐标是 GUI 相对坐标，鼠标是绝对坐标，注意偏移）。
     */
    private Slot hoveredContainerSlot(int mouseX, int mouseY) {
        for (Object object : container.inventorySlots) {
            Slot slot = (Slot) object;
            int x = guiLeft + slot.xDisplayPosition;
            int y = guiTop + slot.yDisplayPosition;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) return slot;
        }
        return null;
    }

    /** @return 共享网格上悬停格对应的虚拟槽下标（0..44，页面内偏移）；网格外返回 -1 */
    private int hoveredGhostIndex(int mouseX, int mouseY) {
        int left = guiLeft + ContainerSharedTerminal.GRID_X;
        int top = guiTop + ContainerSharedTerminal.GRID_Y;
        if (!isInsideGrid(mouseX, mouseY)) return -1;
        int col = (mouseX - left) / 18;
        int row = (mouseY - top) / 18;
        return row * ContainerSharedTerminal.COLS + col;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (handleSearchKey(typedChar, keyCode)) return;
        if (keyCode == Keyboard.KEY_Q && (isSpaceDown() || isAltDown())) {
            int mouseX = Mouse.getX() * width / mc.displayWidth;
            int mouseY = height - Mouse.getY() * height / mc.displayHeight - 1;
            StorageViewEntry entry = getHoveredEntry(mouseX, mouseY);
            if (entry != null && !entry.isFluid()) {
                if (isSpaceDown()) container.sendDropAllItems();
                else container.sendDropMatching(entry.getItemKey());
                return;
            }
        }
        if (keyCode == Keyboard.KEY_PRIOR) { // PageUp / PageDown 按屏滚动
            scrollBy(-ContainerSharedTerminal.ROWS);
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            scrollBy(ContainerSharedTerminal.ROWS);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    // handleKeyboardInput 的 IME 兜底在 FutaGuiContainer 基类里，这里不用再写。

    @Override
    protected void actionPerformed(GuiButton button) {
        // 工具按钮：一次一件，从仓库取到背包（服务端按实际存量裁决，取不到就当没点）
        if (button instanceof GuiToolButton) {
            CraftingToolShortcuts.Entry entry = ((GuiToolButton) button).entry();
            if (entry != null && entry.stored != null) {
                NetworkHandler.INSTANCE
                    .sendToServer(PacketStorageAction.item(PacketStorageAction.WITHDRAW_ITEM, entry.stored, 1L));
            }
            return;
        }

        switch (button.id) {
            case BTN_TAB_ITEMS:
                tab = TAB_ITEMS;
                scrollRow = 0;
                updateTabStates();
                viewDirty = true;
                pendingResort = true;
                break;
            case BTN_TAB_FLUIDS:
                tab = TAB_FLUIDS;
                scrollRow = 0;
                updateTabStates();
                viewDirty = true;
                pendingResort = true;
                break;
            case BTN_PREV:
                scrollBy(-ContainerSharedTerminal.ROWS);
                break;
            case BTN_NEXT:
                scrollBy(ContainerSharedTerminal.ROWS);
                break;
            case BTN_SORT:
                sortMode = StorageSort.next(sortMode);
                Config.saveClientGuiSort(sortMode);
                updateTabStates();
                viewDirty = true;
                pendingResort = true;
                break;
            case BTN_STORE_ALL:
                container.sendDepositAll(InventoryExchange.SCOPE_ALL);
                break;
            case BTN_STORE_HOTBAR:
                container.sendDepositAll(InventoryExchange.SCOPE_HOTBAR);
                break;
            case BTN_STORE_MAIN:
                container.sendDepositAll(InventoryExchange.SCOPE_MAIN);
                break;
            case BTN_TERMINAL_IO:
                GuiTerminalIo.open(container);
                break;
            case BTN_DRAIN:
                container.sendDrainContainers();
                break;
            case BTN_CRAFT_DUMP:
                container.requestDumpCraftGrid();
                break;
            case BTN_AUTO_STORE:
                // 先本地翻转让按钮立刻响应，再发请求；服务端会用权威值回一份同步包把
                // 客户端这份纠正过来。不这么做的话，按钮要等一个来回才变。
                ClientTerminalState.setAutoStore(!ClientTerminalState.isAutoStore());
                updateTabStates();
                NetworkHandler.INSTANCE.sendToServer(new PacketAutoStore(ClientTerminalState.isAutoStore()));
                break;
            case BTN_SEARCH_MODE:
                cycleSearchMode(false);
                break;
            default:
                break;
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
        container.clearPageDisplay();
    }

    /**
     * 共享终端<b>不</b>登记 NEI 面板遮罩区。
     *
     * <p>
     * PR #4 原本登记了两块（搜索行、右侧装备/合成侧栏），想让压在界面上的 NEI 格子
     * 不再响应点击。实际后果是反的：NEI 拿到 true 会把那个格子记进
     * {@code ItemsGrid.invalidSlotMap}，而绘制循环开头就把它 {@code continue} 掉 ——
     * 遮罩等于「把这些格子从面板里删掉」。面板挂在右边时，侧栏那条遮罩把面板压住的
     * 整片格子摘掉，玩家看到的就是「右侧不显示 NEI 物品栏了」。
     *
     * <p>
     * 而这里没有折中：终端侧栏的格子和面板格子本来就是叠在同一片区域上
     * （要么面板可见、要么格子可点），面板可见显然更重要。
     * 界面自己的按钮不用管 —— NEI 会自动按 {@code buttonList} 遮。
     *
     * <p>
     * 详见 {@link NeiAwareGui#neiMaskedAreas()} 的说明；实机依据是
     * {@code codechicken.nei.ItemsGrid} 里那段
     * {@code hideItemPanelSlot(...) → invalidSlotMap[i] = true}。
     */
    @Override
    public List<int[]> neiMaskedAreas() {
        return null;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private boolean isInsideGrid(int mouseX, int mouseY) {
        int left = guiLeft + ContainerSharedTerminal.GRID_X;
        int top = guiTop + ContainerSharedTerminal.GRID_Y;
        return mouseX >= left && mouseX < left + ContainerSharedTerminal.COLS * 18
            && mouseY >= top
            && mouseY < top + ContainerSharedTerminal.ROWS * 18;
    }

    /**
     * 滚轮的触发区域：网格包围盒向外扩一圈（1.3.1 上机反馈只在网格内滚动
     * 太难滚准）。搜索栏/页签/导航行都在这个外扩圈里，滚轮照常生效；
     * 侧栏和玩家背包区不受影响。
     */
    private boolean isInsideScrollRegion(int mouseX, int mouseY) {
        final int margin = 16;
        int left = guiLeft + ContainerSharedTerminal.GRID_X - margin;
        int top = guiTop + ContainerSharedTerminal.GRID_Y - margin;
        int right = guiLeft + ContainerSharedTerminal.GRID_X + ContainerSharedTerminal.COLS * 18 + margin;
        int bottom = guiTop + ContainerSharedTerminal.GRID_Y + ContainerSharedTerminal.ROWS * 18 + margin;
        return mouseX >= left && mouseX < right && mouseY >= top && mouseY < bottom;
    }

    /** @return 鼠标指着的那一条；不在网格上或那一格是空的时返回 null */
    private StorageViewEntry getHoveredEntry(int mouseX, int mouseY) {
        int left = guiLeft + ContainerSharedTerminal.GRID_X;
        int top = guiTop + ContainerSharedTerminal.GRID_Y;
        if (!isInsideGrid(mouseX, mouseY)) return null;

        int col = (mouseX - left) / 18;
        int row = (mouseY - top) / 18;
        if (col < 0 || col >= ContainerSharedTerminal.COLS || row < 0 || row >= ContainerSharedTerminal.ROWS) {
            return null;
        }

        int index = scrollRow * ContainerSharedTerminal.COLS + row * ContainerSharedTerminal.COLS + col;
        return index < filtered.size() ? filtered.get(index) : null;
    }

    /** 数量量级后缀，和 {@link #formatShort} 的循环一一对应。 */
    private static final String[] MAGNITUDES = { "k", "M", "G", "T", "P", "E" };

    /**
     * 紧凑数量：1234 -> {@code 1.23k}，12345678 -> {@code 12.3M}。
     *
     * <p>
     * 格子里只有 18 像素宽，完整数字根本放不下；用 k/M/G/T/P/E 后缀
     * 既短又能一眼看出量级。
     *
     * <p>
     * 用一个「一直除到小于 999.5 为止」的循环，而不是按阈值写一串 if：
     * 后者在边界上会出洋相 —— {@code %.0f} 会把 999.5 进位成 1000，
     * 于是 999_500 个会显示成 {@code 1000k} 而不是 {@code 1.00M}。
     */
    static String formatShort(long amount) {
        if (amount < 1000L) return Long.toString(amount);

        double value = amount;
        int magnitude = -1;
        while (value >= 999.5D && magnitude < MAGNITUDES.length - 1) {
            value /= 1000.0D;
            magnitude++;
        }

        return trim(value) + MAGNITUDES[magnitude];
    }

    /** 保留 3 位有效数字：1.23 / 12.3 / 123。 */
    private static String trim(double value) {
        if (value < 10.0D) return String.format(Locale.ROOT, "%.2f", value);
        if (value < 100.0D) return String.format(Locale.ROOT, "%.1f", value);
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
