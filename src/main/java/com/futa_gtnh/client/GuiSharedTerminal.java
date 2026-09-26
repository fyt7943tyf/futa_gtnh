package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
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
import com.futa_gtnh.exchange.InventoryExchange;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketAutoStore;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * 全服共享背包的主界面。
 *
 * <p>
 * 布局分四块：搜索框、共享存储网格（分页）、翻页/排序/存入按钮、玩家背包。
 * 网格是<b>虚拟槽位</b> —— 见 {@link ContainerSharedTerminal} 的说明 ——
 * 但对玩家和 NEI 来说它就是普通的物品格，所以对着共享存储里的东西直接按
 * R/U 查配方是可以用的。
 */
public class GuiSharedTerminal extends GuiContainer {

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

    private static final int TAB_ITEMS = 0;
    private static final int TAB_FLUIDS = 1;

    private final ContainerSharedTerminal container;

    private GuiTextField searchField;
    private GuiButton sortButton;
    private GuiButton autoStoreButton;

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

        searchField = new GuiTextField(
            fontRendererObj,
            guiLeft + 8,
            guiTop + ContainerSharedTerminal.SEARCH_Y,
            160,
            12);
        searchField.setMaxStringLength(64);
        searchField.setEnableBackgroundDrawing(false);
        searchField.setText(previousQuery);
        // 默认不给焦点。
        //
        // 以前这里是 setFocused(true) + 点击时强制回焦点，理由是
        // 「点到空白处再敲字母会触发快捷键直接关界面」。那个担心其实不成立：
        // KeyHandler 里有 currentScreen != null 就 return 的守卫，B 键不可能关掉界面；
        // 而 GuiContainer 只对「打开背包」那个键（默认 E）关界面，字母键不会。
        // 代价是搜索框永远失不了焦，玩家点别处也没法退出输入状态，很别扭。
        searchField.setFocused(false);
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
        super.drawScreen(mouseX, mouseY, partialTicks);
        // 搜索框画在 super 之后：原版会把 GUI 内容盖在按钮上，
        // 而文本框不是按钮，画早了会被后面的物品格盖住
        if (searchField != null) {
            searchField.drawTextBox();
        }
        drawAutoStoreTooltip(mouseX, mouseY);
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

        // 聚焦时把搜索框描一圈亮边 —— 现在它默认不是焦点了，
        // 得让玩家一眼看出「现在敲键盘是往这里输」还是「不在输入状态」
        if (searchField != null && searchField.isFocused()) {
            drawRect(
                guiLeft + 7,
                guiTop + ContainerSharedTerminal.SEARCH_Y - 1,
                guiLeft + 7 + 162,
                guiTop + ContainerSharedTerminal.SEARCH_Y,
                0xFF55FF55);
            drawRect(
                guiLeft + 7,
                guiTop + ContainerSharedTerminal.SEARCH_Y + 12,
                guiLeft + 7 + 162,
                guiTop + ContainerSharedTerminal.SEARCH_Y + 13,
                0xFF55FF55);
        }

        // 搜索框的输入提示：只在空的时候显示
        if (searchField != null && searchField.getText()
            .isEmpty()) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.search.hint"),
                guiLeft + 9,
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

        // ---- 状态行：优先显示鼠标指着的那一条的完整数量 ----
        StorageViewEntry hovered = getHoveredEntry(mouseX, mouseY);
        String status;
        if (hovered != null) {
            status = EnumChatFormatting.GOLD + hovered.getDisplayName()
                + " "
                + EnumChatFormatting.WHITE
                + "\u00d7 "
                + formatFull(hovered.getAmount())
                + (hovered.isFluid() ? " L" : "");
        } else if (container.isRemoteAccess()) {
            status = EnumChatFormatting.GRAY + tr("futa_gtnh.gui.status.remote");
        } else if (tab == TAB_ITEMS) {
            ItemKey output = ClientTerminalState.getOutputItem();
            status = EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted(
                "futa_gtnh.gui.status.output",
                output == null ? tr("futa_gtnh.gui.status.output.none") : safeItemName(output));
        } else {
            FluidKey output = ClientTerminalState.getOutputFluid();
            status = EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted(
                "futa_gtnh.gui.status.output",
                output == null ? tr("futa_gtnh.gui.status.output.none") : safeFluidName(output));
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

            String text = formatShort(entry.getAmount());
            drawAmountText(text, x, y);
        }
    }

    /**
     * 把数量文字画在格子右下角。
     *
     * <p>
     * 位数多了就整体缩小，而不是截断 —— 看到 {@code 12.3M} 和看到 {@code 12.3…}
     * 完全是两回事。缩放矩阵用完必须还原，否则后面所有绘制都会跟着变小。
     */
    private void drawAmountText(String text, int slotX, int slotY) {
        if (text == null || text.isEmpty()) return;

        float scale = text.length() > 6 ? 0.5F : (text.length() > 4 ? 0.75F : 1.0F);
        int width = fontRendererObj.getStringWidth(text);

        GL11.glPushMatrix();
        GL11.glTranslatef(slotX + 17.0F, slotY + 17.0F, 300.0F);
        GL11.glScalef(scale, scale, 1.0F);
        fontRendererObj.drawStringWithShadow(text, -width, -8, 0xFFFFFF);
        GL11.glPopMatrix();
    }

    // ==================================================================
    // 输入
    // ==================================================================

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        // Inventory Bogo Sorter 的 Ctrl / Alt / Space 快捷键最终也是在这里落地。
        // 共享网格是虚拟槽位，不能让它继续走 Bogo 的本地 putStack 路径；先转换成
        // 我们自己的服务端权威动作，普通点击仍交给 GuiContainer。
        if (handleShortcutClick(mouseX, mouseY, mouseButton)) return;

        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (searchField != null) {
            boolean before = searchField.isFocused();
            // GuiTextField.mouseClicked 自己就会按「点在不在框内」决定聚焦还是失焦
            // （canLoseFocus 默认 true），所以这里不要再补一句 setFocused(true)——
            // 那样搜索框就永远退不出输入状态了。
            searchField.mouseClicked(mouseX, mouseY, mouseButton);
            if (before != searchField.isFocused()) viewDirty = true;
        }
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
        if (!isTransferSlot(slot) || slot.getStack() == null) return false;

        if (isSpaceDown()) {
            container.sendDepositAll(InventoryExchange.SCOPE_ALL);
            return true;
        }
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
        // 注意：1.7.10 的 GuiScreen#keyTyped 没有声明 throws，
        // 覆写时加上 throws IOException 会直接编译不过
        if (searchField != null && searchField.textboxKeyTyped(typedChar, keyCode)) {
            viewDirty = true;
            pendingResort = true;
            return;
        }
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

    /**
     * 旧输入层（LWJGL2 + InputFix 一类）的兜底：把「只有字符、没有按键」的事件也转给
     * {@link #keyTyped}。原版 {@code GuiScreen#handleKeyboardInput()} 只在
     * {@code Keyboard.getEventKeyState()} 为真时才转发字符，而那些辅助层送来的正是
     * keyState 为假的事件 —— 汉字就是这么没的。
     *
     * <p>
     * <b>lwjgl3ify 环境下不需要也不走这条路</b>（见 {@link ImeCompat}）：它把输入法
     * 提交的文字镜像成 keyState 为真、key 为 0 的事件塞进传统队列，原版路径自己就能
     * 收到，GuiTextField 里的文字由 lwjgl3ify 的 mixin 负责注入。这种情况下这段兜底
     * 必须闭嘴，否则同一批字符会被送进去两遍。
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
            case BTN_DRAIN:
                container.sendDrainContainers();
                break;
            case BTN_AUTO_STORE:
                // 先本地翻转让按钮立刻响应，再发请求；服务端会用权威值回一份同步包把
                // 客户端这份纠正过来。不这么做的话，按钮要等一个来回才变。
                ClientTerminalState.setAutoStore(!ClientTerminalState.isAutoStore());
                updateTabStates();
                NetworkHandler.INSTANCE.sendToServer(new PacketAutoStore(ClientTerminalState.isAutoStore()));
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

    private static String safeItemName(ItemKey key) {
        try {
            return key.prototype()
                .getDisplayName();
        } catch (Throwable t) {
            return String.valueOf(key);
        }
    }

    private static String safeFluidName(FluidKey key) {
        try {
            return key.getFluid()
                .getLocalizedName(key.prototype());
        } catch (Throwable t) {
            return String.valueOf(key.getFluid());
        }
    }

    /** 完整数量，带千位分隔符。 */
    static String formatFull(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
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
