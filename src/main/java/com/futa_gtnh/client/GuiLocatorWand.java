package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.biome.BiomeGenBase;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.futa_gtnh.locator.OreVeinCatalog;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketLocatorAction;
import com.futa_gtnh.network.PacketLocatorResult;

/**
 * 寻物魔杖的选择界面。
 *
 * <p>
 * 四个页签：
 *
 * <ul>
 * <li><b>方块</b>：所有方块的网格，选一个就找最近的那一种方块。</li>
 * <li><b>物品</b>：所有物品的网格，选一个就找附近箱子库存中含有它的箱子。</li>
 * <li><b>矿脉</b>：GT 的矿脉类型列表，选一条就找最近的<b>那条矿脉</b>。</li>
 * <li><b>生物群系</b>：选一个当前维度的生物群系，按地图数据搜索最近位置。</li>
 * </ul>
 *
 * <p>
 * 矿脉和方块的区别不只是「粒度」：一条矿脉由四种材料铺成，而且同一条矿脉
 * 在不同的石头里是<b>不同的方块</b>（GTNH 里石头、花岗岩、深海石头各一个）。
 * 所以按方块找永远只能找到其中一个石种的那份，按矿脉找才能「不管它长在哪种
 * 石头里，都是同一种矿」。细节见 {@code locator.LocatorScan}。
 *
 * <p>
 * 故意做成纯客户端 {@code GuiScreen} 而不是 {@code GuiContainer}：
 * 这里没有任何「槽位」—— 选中的东西只是一个名字，不是要拿走的物品。
 *
 * <p>
 * 关闭界面<b>不会</b>取消追踪 —— 玩家正是要关掉界面去看那道光束的。
 * 取消有两个入口：界面里的「停止」按钮，或者潜行右键魔杖。
 */
public class GuiLocatorWand extends GuiScreen {

    // ---- 布局 ----
    private static final int GUI_WIDTH = 268;
    private static final int GUI_HEIGHT = 190;
    private static final int TITLE_Y = 4;
    private static final int SEARCH_Y = 20;
    private static final int GRID_X = 6;
    private static final int GRID_Y = 40;
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
    private static final int BTN_TAB_BLOCKS = 2;
    private static final int BTN_TAB_ITEMS = 3;
    private static final int BTN_TAB_VEINS = 4;
    private static final int BTN_TAB_BIOMES = 5;

    private static final int TAB_BLOCKS = 0;
    private static final int TAB_ITEMS = 1;
    private static final int TAB_VEINS = 2;
    private static final int TAB_BIOMES = 3;

    private static final int COLOR_PANEL = 0xC0101010;
    private static final int COLOR_SLOT = 0x40FFFFFF;
    private static final int COLOR_SLOT_HOVER = 0x80FFFFFF;
    private static final int COLOR_BORDER = 0xFF808080;
    private static final int COLOR_SELECTED = 0xFF55FF55;

    private static final RenderItem ITEM_RENDER = RenderItem.getInstance();

    /** 方块页签的过滤结果，就是 {@link BlockIndex} 里那一份。 */
    private final List<ItemStack> blockResults = new ArrayList<>();
    /** 物品页签的过滤结果，就是 {@link ItemIndex} 里那一份。 */
    private final List<ItemStack> itemResults = new ArrayList<>();
    /** 矿脉页签的全部候选（已按当前维度过滤）。 */
    private final List<OreVeinCatalog.Entry> allVeins = new ArrayList<>();
    /** 矿脉页签的过滤结果。 */
    private final List<OreVeinCatalog.Entry> veinResults = new ArrayList<>();
    /** 与 {@link #allVeins} 一一对应的搜索文本（标题 + 材料 + 拼音）。 */
    private final List<String> veinSearch = new ArrayList<>();
    /** 所有已注册的生物群系及当前搜索结果。 */
    private final List<BiomeGenBase> allBiomes = new ArrayList<>();
    private final List<BiomeGenBase> biomeResults = new ArrayList<>();
    private final List<String> biomeSearch = new ArrayList<>();

    private int guiLeft;
    private int guiTop;
    private GuiTextField searchField;
    private GuiButton teleportButton;
    private GuiButton stopButton;
    private GuiButton blocksTab;
    private GuiButton itemsTab;
    private GuiButton veinsTab;
    private GuiButton biomesTab;

    private int tab = TAB_BLOCKS;
    /** 当前页第一个结果所在的行。 */
    private int scrollRow;
    private boolean viewDirty = true;
    /** 建索引期间隔多少次 tick 重新过滤一遍（见 {@link #updateScreen}）。 */
    private int buildRefreshTimer;

    /** 建索引时多久重新过滤一次列表，单位是 tick。 */
    private static final int BUILD_REFRESH_INTERVAL = 10;

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
        // 默认不给焦点，点一下才进输入状态 —— 和共享背包那边保持一致
        // （以前是强制常驻焦点，玩家点别处也退不出来）
        searchField.setFocused(false);
        Keyboard.enableRepeatEvents(true);

        buttonList.clear();

        // 页签按钮放在标题那一行的右端
        biomesTab = new GuiSmallButton(
            BTN_TAB_BIOMES,
            guiLeft + GUI_WIDTH - 6 - 184,
            guiTop + TITLE_Y,
            44,
            16,
            tr("futa_gtnh.gui.locator.tab.biomes"));
        blocksTab = new GuiSmallButton(
            BTN_TAB_BLOCKS,
            guiLeft + GUI_WIDTH - 6 - 138,
            guiTop + TITLE_Y,
            44,
            16,
            tr("futa_gtnh.gui.locator.tab.blocks"));
        itemsTab = new GuiSmallButton(
            BTN_TAB_ITEMS,
            guiLeft + GUI_WIDTH - 6 - 92,
            guiTop + TITLE_Y,
            44,
            16,
            tr("futa_gtnh.gui.locator.tab.items"));
        veinsTab = new GuiSmallButton(
            BTN_TAB_VEINS,
            guiLeft + GUI_WIDTH - 6 - 46,
            guiTop + TITLE_Y,
            40,
            16,
            tr("futa_gtnh.gui.locator.tab.veins"));
        buttonList.add(biomesTab);
        buttonList.add(blocksTab);
        buttonList.add(itemsTab);
        buttonList.add(veinsTab);
        // 拿不到 GT 的矿脉数据时干脆不显示这个页签，免得点进去一片空白
        veinsTab.enabled = OreVeinCatalog.isAvailable();
        if (!veinsTab.enabled) tab = TAB_BLOCKS;

        teleportButton = new GuiSmallButton(
            BTN_TELEPORT,
            guiLeft + PANEL_X,
            guiTop + BUTTON_Y,
            42,
            18,
            tr("futa_gtnh.gui.locator.teleport"));
        stopButton = new GuiSmallButton(
            BTN_STOP,
            guiLeft + PANEL_X + 46,
            guiTop + BUTTON_Y,
            44,
            18,
            tr("futa_gtnh.gui.locator.stop"));
        buttonList.add(teleportButton);
        buttonList.add(stopButton);

        loadVeins();
        loadBiomes();
        BlockIndex.ensureStarted();
        ItemIndex.ensureStarted();
        viewDirty = true;
    }

    /**
     * 读一次矿脉列表。
     *
     * <p>
     * 和方块那几万条不一样，矿脉只有一百来条，不需要分帧建索引 ——
     * 一次读完就好。按当前维度过滤掉不可能生成的，省得玩家白选。
     */
    private void loadVeins() {
        allVeins.clear();
        veinSearch.clear();
        veinResults.clear();
        if (!OreVeinCatalog.isAvailable()) return;

        List<OreVeinCatalog.Entry> candidates = OreVeinCatalog.forWorld(mc == null ? null : mc.theWorld);
        for (OreVeinCatalog.Entry entry : candidates) {
            allVeins.add(entry);
            // 搜索文本里带上拼音（自研回退路径用；lwjgl3ify 下可以直接打中文，
            // 装了 NEChar 时拼音匹配也交给它 —— 见 filterVeins / NecharBridge）
            veinSearch.add(
                (entry.getTitle() + ' ' + entry.getMaterials()).toLowerCase(Locale.ROOT)
                    + Pinyin.searchSuffix(entry.getTitle()));
        }
    }

    /** 生物群系已由游戏注册，不需要扫描区块来建立候选列表。 */
    private void loadBiomes() {
        allBiomes.clear();
        biomeSearch.clear();
        biomeResults.clear();

        for (BiomeGenBase biome : BiomeGenBase.getBiomeGenArray()) {
            if (biome != null && biome.biomeName != null && !biome.biomeName.isEmpty()) allBiomes.add(biome);
        }
        allBiomes.sort(Comparator.comparing(GuiLocatorWand::biomeTitle, String.CASE_INSENSITIVE_ORDER));

        for (BiomeGenBase biome : allBiomes) {
            String title = biomeTitle(biome);
            biomeSearch.add(
                (title + ' ' + biome.biomeName + ' ' + biome.biomeID).toLowerCase(Locale.ROOT)
                    + Pinyin.searchSuffix(title));
        }
    }

    private static String biomeTitle(BiomeGenBase biome) {
        return biome == null ? "" : biome.biomeName;
    }

    private static ItemStack biomeIcon(BiomeGenBase biome) {
        if (biome == null || biome.topBlock == null) return new ItemStack(Blocks.grass);
        Item item = Item.getItemFromBlock(biome.topBlock);
        return item == null ? new ItemStack(Blocks.grass) : new ItemStack(item, 1, 0);
    }

    private void filterBiomes(String query) {
        biomeResults.clear();
        if (query == null || query.trim()
            .isEmpty()) {
            biomeResults.addAll(allBiomes);
            return;
        }

        String[] words = query.trim()
            .toLowerCase(Locale.ROOT)
            .split("\\s+");
        for (int i = 0; i < allBiomes.size(); i++) {
            String haystack = biomeSearch.get(i);
            boolean matches = true;
            for (String word : words) {
                String needle = word.startsWith("@") ? word.substring(1) : word;
                if (needle.isEmpty()) continue;
                if (!haystack.contains(needle) && !NecharBridge.matches(biomeTitle(allBiomes.get(i)), needle)) {
                    matches = false;
                    break;
                }
            }
            if (matches) biomeResults.add(allBiomes.get(i));
        }
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

        if (tab == TAB_BLOCKS) {
            boolean wasBuilding = BlockIndex.isBuilding();
            BlockIndex.tick();
            // 索引还在长的时候要定期重新过滤，否则列表会冻结在打开界面那一瞬间的快照。
            //
            // 但<b>不能每 tick 都过滤</b>：过滤是拿查询串扫一遍全表，而 GTNH 的方块条目
            // 有好几万条，建索引的后期每 tick 扫一遍就是十几毫秒 —— 正好在玩家盯着
            // 进度条的时候把帧率拖垮。一秒刷 6 次足够了，进度条又不是仪表盘。
            if (wasBuilding && ++buildRefreshTimer >= BUILD_REFRESH_INTERVAL) {
                buildRefreshTimer = 0;
                viewDirty = true;
            }
        } else if (tab == TAB_ITEMS) {
            boolean wasBuilding = ItemIndex.isBuilding();
            ItemIndex.tick();
            if (wasBuilding && ++buildRefreshTimer >= BUILD_REFRESH_INTERVAL) {
                buildRefreshTimer = 0;
                viewDirty = true;
            }
        }

        if (viewDirty) {
            viewDirty = false;
            rebuildResults();
        }

        updateTabLabels();
        refreshButtons();
    }

    private void rebuildResults() {
        String query = searchField == null ? "" : searchField.getText();
        if (tab == TAB_BLOCKS) {
            BlockIndex.filter(query, blockResults);
        } else if (tab == TAB_ITEMS) {
            ItemIndex.filter(query, itemResults);
        } else if (tab == TAB_VEINS) {
            filterVeins(query);
        } else {
            filterBiomes(query);
        }
        scrollRow = 0;
    }

    private void filterVeins(String query) {
        veinResults.clear();
        if (query == null || query.trim()
            .isEmpty()) {
            veinResults.addAll(allVeins);
            return;
        }

        String[] words = query.trim()
            .toLowerCase(Locale.ROOT)
            .split("\\s+");
        for (int i = 0; i < allVeins.size(); i++) {
            String haystack = veinSearch.get(i);
            boolean matches = true;
            for (String word : words) {
                String needle = word.startsWith("@") ? word.substring(1) : word;
                if (needle.isEmpty()) continue;
                if (!haystack.contains(needle) && !NecharBridge.matches(
                    allVeins.get(i)
                        .getTitle(),
                    needle)) {
                    matches = false;
                    break;
                }
            }
            if (matches) veinResults.add(allVeins.get(i));
        }
    }

    /** 当前页签选中的那个按钮要显示成「按下」的样子。 */
    private void updateTabLabels() {
        if (blocksTab == null) return;
        biomesTab.enabled = tab != TAB_BIOMES;
        blocksTab.enabled = tab != TAB_BLOCKS;
        itemsTab.enabled = tab != TAB_ITEMS;
        veinsTab.enabled = tab != TAB_VEINS && OreVeinCatalog.isAvailable();
    }

    private void refreshButtons() {
        if (teleportButton == null || mc == null || mc.thePlayer == null) return;

        teleportButton.enabled = LocatorState.getState() == PacketLocatorResult.STATE_FOUND
            && LocatorState.getResultDimension() == mc.thePlayer.dimension;

        // 传送过一次之后按钮换成「已传送」。光把按钮灰掉不说明原因，
        // 玩家只会以为魔杖坏了或者找不到目标了
        String label = tr(
            LocatorState.isTeleported() ? "futa_gtnh.gui.locator.teleported" : "futa_gtnh.gui.locator.teleport");
        if (!label.equals(teleportButton.displayString)) {
            teleportButton.displayString = label;
        }

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
            .drawStringWithShadow(tr("futa_gtnh.gui.locator.title"), guiLeft + GRID_X + 3, guiTop + 8, 0xFFFFFF);

        drawSearchBox();
        drawGrid(mouseX, mouseY);
        drawStatusPanel();

        // 按钮画在网格之后，保证盖在网格边框上
        super.drawScreen(mouseX, mouseY, partialTicks);

        if (searchField != null) {
            searchField.drawTextBox();
        }

        List<String> tooltip = tooltipAt(mouseX, mouseY);
        if (tooltip != null) {
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
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

        // 聚焦时描一圈亮边：现在默认不是焦点了，得能一眼看出在不在输入状态
        if (searchField != null && searchField.isFocused()) {
            drawBorder(guiLeft + GRID_X - 1, guiTop + SEARCH_Y - 1, GUI_WIDTH - 2 * GRID_X + 2, 18, 0xFF55FF55);
        }

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

        int count = resultCount();

        // 物品渲染必须开 GUI 光照，否则方块会是一片黑
        RenderHelper.enableGUIStandardItemLighting();
        float previousZ = ITEM_RENDER.zLevel;
        ITEM_RENDER.zLevel = 100.0F;

        for (int slot = 0; slot < COLS * ROWS; slot++) {
            int index = scrollRow * COLS + slot;
            if (index >= count) break;

            int cellX = guiLeft + GRID_X + (slot % COLS) * CELL;
            int cellY = guiTop + GRID_Y + (slot / COLS) * CELL;

            boolean isHovered = mouseX >= cellX && mouseX < cellX + CELL && mouseY >= cellY && mouseY < cellY + CELL;
            drawRect(
                cellX + 1,
                cellY + 1,
                cellX + CELL - 1,
                cellY + CELL - 1,
                isHovered ? COLOR_SLOT_HOVER : COLOR_SLOT);

            // 选中的那个描一圈绿边，不然滚走之后就找不着了
            if (isSelectedAt(index)) {
                drawBorder(cellX + 1, cellY + 1, CELL - 2, CELL - 2, COLOR_SELECTED);
            }

            ItemStack stack = iconAt(index);
            if (stack == null) continue;
            try {
                ITEM_RENDER
                    .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, cellX + 1, cellY + 1);
            } catch (Throwable ignored) {
                // 个别模组的物品渲染会抛异常；不能让一个坏物品废掉整个界面
            }
        }

        ITEM_RENDER.zLevel = previousZ;
        RenderHelper.disableStandardItemLighting();

        drawGridFooter(count);
    }

    /** 网格下面的状态行 + 滚动条。 */
    private void drawGridFooter(int count) {
        int bottom = guiTop + GRID_Y + GRID_H;

        if (tab == TAB_BLOCKS && BlockIndex.isBuilding()) {
            fontRendererObj.drawStringWithShadow(
                StatCollector.translateToLocalFormatted(
                    "futa_gtnh.gui.locator.indexing",
                    (int) (BlockIndex.getProgress() * 100.0F)),
                guiLeft + GRID_X + 2,
                bottom + 3,
                0xFFAA00);
        } else if (tab == TAB_ITEMS && ItemIndex.isBuilding()) {
            fontRendererObj.drawStringWithShadow(
                StatCollector.translateToLocalFormatted(
                    "futa_gtnh.gui.locator.indexing.items",
                    (int) (ItemIndex.getProgress() * 100.0F)),
                guiLeft + GRID_X + 2,
                bottom + 3,
                0xFFAA00);
        } else if (count == 0) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GRAY + tr(
                    tab == TAB_BLOCKS ? "futa_gtnh.gui.locator.empty"
                        : tab == TAB_ITEMS ? "futa_gtnh.gui.locator.empty.items"
                            : tab == TAB_VEINS ? "futa_gtnh.gui.locator.empty.veins"
                                : "futa_gtnh.gui.locator.empty.biomes"),
                guiLeft + GRID_X + 2,
                bottom + 3,
                0xFFFFFF);
        } else {
            String text = tab == TAB_BLOCKS
                ? StatCollector.translateToLocalFormatted("futa_gtnh.gui.locator.count", count, BlockIndex.size())
                : tab == TAB_ITEMS
                    ? StatCollector
                        .translateToLocalFormatted("futa_gtnh.gui.locator.count.items", count, ItemIndex.size())
                    : tab == TAB_VEINS
                        ? StatCollector
                            .translateToLocalFormatted("futa_gtnh.gui.locator.count.veins", count, allVeins.size())
                        : StatCollector
                            .translateToLocalFormatted("futa_gtnh.gui.locator.count.biomes", count, allBiomes.size());
            fontRendererObj.drawStringWithShadow(text, guiLeft + GRID_X + 2, bottom + 3, 0xA0A0A0);
        }

        // 滚动条：只有真的滚得动时才画
        int totalRows = totalRows(count);
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
        int hintY = guiTop + GRID_Y + GRID_H - 26;

        ItemStack icon = LocatorState.getIcon();
        if (icon == null) {
            fontRendererObj
                .drawStringWithShadow(EnumChatFormatting.GRAY + tr("futa_gtnh.gui.locator.pick"), x, y + 4, 0xFFFFFF);
            drawHintLines(x, hintY);
            return;
        }

        RenderHelper.enableGUIStandardItemLighting();
        float previousZ = ITEM_RENDER.zLevel;
        ITEM_RENDER.zLevel = 100.0F;
        try {
            ITEM_RENDER.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), icon, x, y);
        } catch (Throwable ignored) {
            // 同上：坏物品不该带崩界面
        }
        ITEM_RENDER.zLevel = previousZ;
        RenderHelper.disableStandardItemLighting();

        fontRendererObj.drawStringWithShadow(
            fontRendererObj.trimStringToWidth(LocatorState.getTitle(), PANEL_W - 28),
            x + 20,
            y + 4,
            0xFFFFFF);

        int textY = y + 22;
        // 矿脉模式多一行材料列表 —— 「铁矿脉」这种名字光看它自己说明不了什么
        String subtitle = LocatorState.getSubtitle();
        if (!subtitle.isEmpty()) {
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.GRAY + fontRendererObj.trimStringToWidth(subtitle, PANEL_W - 8),
                x,
                textY,
                0xFFFFFF);
            textY += 12;
        }

        drawStatusText(x, textY);
        drawHintLines(x, hintY);
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

        if (state != PacketLocatorResult.STATE_FOUND && state != PacketLocatorResult.STATE_ARRIVED) return;

        // 「已传送」和「已找到」共用下面这一整块：坐标、距离、光束提示全都要照画 ——
        // 传送之后玩家最需要的就是这些信息
        boolean arrived = state == PacketLocatorResult.STATE_ARRIVED;
        fontRendererObj.drawStringWithShadow(
            EnumChatFormatting.GREEN
                + tr(arrived ? "futa_gtnh.gui.locator.state.arrived" : "futa_gtnh.gui.locator.state.found"),
            x,
            y,
            0xFFFFFF);

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

        if (arrived) {
            // 说清楚为什么传送按钮点不动了：结果还在、还能看光束，只是这一个结果
            // 已经用过一次传送
            fontRendererObj.drawStringWithShadow(
                EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.locator.teleported.note"),
                x,
                y + 70,
                0xFFFFFF);
        }
    }

    private void drawHintLines(int x, int y) {
        fontRendererObj.drawStringWithShadow(
            EnumChatFormatting.DARK_GRAY + tr(
                tab == TAB_ITEMS ? "futa_gtnh.gui.locator.hint1.items"
                    : tab == TAB_BIOMES ? "futa_gtnh.gui.locator.hint1.biomes" : "futa_gtnh.gui.locator.hint1"),
            x,
            y,
            0xFFFFFF);
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
    // 结果访问（屏蔽三个页签的差异）
    // ==================================================================

    private int resultCount() {
        if (tab == TAB_BLOCKS) return blockResults.size();
        if (tab == TAB_ITEMS) return itemResults.size();
        if (tab == TAB_VEINS) return veinResults.size();
        return biomeResults.size();
    }

    private int totalRows(int count) {
        return (count + COLS - 1) / COLS;
    }

    private ItemStack iconAt(int index) {
        if (tab == TAB_BLOCKS) {
            return index < blockResults.size() ? blockResults.get(index) : null;
        }
        if (tab == TAB_ITEMS) return index < itemResults.size() ? itemResults.get(index) : null;
        if (tab == TAB_VEINS) {
            if (index >= veinResults.size()) return null;
            return OreVeinCatalog.iconOf(veinResults.get(index));
        }
        return index < biomeResults.size() ? biomeIcon(biomeResults.get(index)) : null;
    }

    private boolean isSelectedAt(int index) {
        if (tab == TAB_BLOCKS) {
            return index < blockResults.size() && LocatorState.isBlockSelected(blockResults.get(index));
        }
        if (tab == TAB_ITEMS) return index < itemResults.size() && LocatorState.isItemSelected(itemResults.get(index));
        if (tab == TAB_VEINS) {
            return index < veinResults.size() && LocatorState.isVeinSelected(
                veinResults.get(index)
                    .getKey());
        }
        return index < biomeResults.size() && LocatorState.isBiomeSelected(biomeResults.get(index).biomeID);
    }

    /** @return 鼠标指着的那个格子的提示文字；不在格子上或格子里没东西时返回 null */
    private List<String> tooltipAt(int mouseX, int mouseY) {
        int index = indexAt(mouseX, mouseY);
        if (index < 0) return null;

        if (tab == TAB_BLOCKS) {
            if (index >= blockResults.size()) return null;
            ItemStack stack = blockResults.get(index);
            return stack == null ? null : stack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips);
        }

        if (tab == TAB_ITEMS) {
            if (index >= itemResults.size()) return null;
            ItemStack stack = itemResults.get(index);
            if (stack == null) return null;
            List<String> lines = new ArrayList<>(stack.getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips));
            lines.add(EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.locator.items.tip"));
            return lines;
        }

        if (tab == TAB_BIOMES) {
            if (index >= biomeResults.size()) return null;
            BiomeGenBase biome = biomeResults.get(index);
            List<String> lines = new ArrayList<>();
            lines.add(EnumChatFormatting.GOLD + biomeTitle(biome));
            lines.add(
                EnumChatFormatting.GRAY
                    + StatCollector.translateToLocalFormatted("futa_gtnh.gui.locator.biomes.id", biome.biomeID));
            lines.add(EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.locator.biomes.tip"));
            return lines;
        }

        if (index >= veinResults.size()) return null;
        OreVeinCatalog.Entry entry = veinResults.get(index);

        List<String> lines = new ArrayList<>();
        lines.add(EnumChatFormatting.GOLD + entry.getTitle());
        lines.add(EnumChatFormatting.GRAY + entry.getMaterials());
        int[] range = OreVeinCatalog.heightRange(entry, mc.theWorld);
        if (range != null) {
            lines.add(
                EnumChatFormatting.DARK_GRAY
                    + StatCollector.translateToLocalFormatted("futa_gtnh.gui.locator.vein.range", range[0], range[1]));
        }
        lines.add(EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.gui.locator.vein.tip"));
        return lines;
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
            // GuiTextField.mouseClicked 自己会按「点在不在框内」决定聚焦/失焦
            searchField.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        int index = indexAt(mouseX, mouseY);
        if (index >= 0 && index < resultCount()) {
            if (searchField != null) searchField.setFocused(false);
            select(index);
            return;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
        // 点到别处 = 退出输入状态。不用怕「敲字母会关掉界面」：
        // 这个界面没有快捷键，而 GuiContainer 只对「打开背包」那个键关界面。
        if (searchField != null) {
            searchField.setFocused(false);
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

        int maxScroll = Math.max(0, totalRows(resultCount()) - ROWS);
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
            scrollRow = Math.min(Math.max(0, totalRows(resultCount()) - ROWS), scrollRow + ROWS);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /**
     * 旧输入层（LWJGL2 + InputFix 一类）的兜底：把「只有字符、没有按键」的事件也转给
     * {@link #keyTyped}。原版 {@code GuiScreen#handleKeyboardInput()} 只在
     * {@code Keyboard.getEventKeyState()} 为真时才转发字符，keyState 为假的事件
     * 会被直接丢掉，而那些辅助层送来的恰恰就是这种事件。
     *
     * <p>
     * lwjgl3ify 环境下不走这条路（见 {@link ImeCompat}）：输入法文字会被镜像成
     * keyState 为真的事件、由原版路径自己转发，这里再补一刀就会重复插入。
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
    public void onGuiClosed() {
        super.onGuiClosed();
        Keyboard.enableRepeatEvents(false);
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
            case BTN_TAB_BLOCKS:
            case BTN_TAB_ITEMS:
            case BTN_TAB_VEINS:
            case BTN_TAB_BIOMES:
                switchTab(
                    button.id == BTN_TAB_BLOCKS ? TAB_BLOCKS
                        : button.id == BTN_TAB_ITEMS ? TAB_ITEMS : button.id == BTN_TAB_VEINS ? TAB_VEINS : TAB_BIOMES);
                break;
            default:
                break;
        }
    }

    private void switchTab(int newTab) {
        if (tab == newTab) return;
        tab = newTab;
        // 搜索词在所有页签里都保留，切过去照样能用
        viewDirty = true;
    }

    private void select(int index) {
        if (tab == TAB_BLOCKS) {
            ItemStack stack = index < blockResults.size() ? blockResults.get(index) : null;
            if (stack == null) return;
            // 先更新本地状态让界面立刻有反馈，真结果等服务端推回来
            LocatorState.setBlockTarget(stack);
            NetworkHandler.INSTANCE.sendToServer(PacketLocatorAction.start(stack));
        } else if (tab == TAB_ITEMS) {
            ItemStack stack = index < itemResults.size() ? itemResults.get(index) : null;
            if (stack == null) return;
            LocatorState.setItemTarget(stack);
            NetworkHandler.INSTANCE.sendToServer(PacketLocatorAction.startItem(stack));
        } else if (tab == TAB_VEINS) {
            if (index >= veinResults.size()) return;
            OreVeinCatalog.Entry entry = veinResults.get(index);
            LocatorState
                .setVeinTarget(entry.getKey(), entry.getTitle(), entry.getMaterials(), OreVeinCatalog.iconOf(entry));
            NetworkHandler.INSTANCE.sendToServer(PacketLocatorAction.startVein(entry.getKey()));
        } else {
            if (index >= biomeResults.size()) return;
            BiomeGenBase biome = biomeResults.get(index);
            LocatorState.setBiomeTarget(biome.biomeID, biomeTitle(biome), biomeIcon(biome));
            NetworkHandler.INSTANCE.sendToServer(PacketLocatorAction.startBiome(biome.biomeID));
        }
        refreshButtons();
    }

    // ==================================================================
    // 工具
    // ==================================================================

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
        int index = scrollRow * COLS + row * COLS + col;
        return index < resultCount() ? index : -1;
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }
}
