package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStationView;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.station.SharedStorageInventory;
import com.futa_gtnh.station.StationRef;

/**
 * 匠魂合成站那块「共享存储区」的<b>搜索栏</b>。
 *
 * <p>
 * 物品本身显示在合成站界面里匠魂原生的箱子区域（27 格真槽位，取放全走原版容器逻辑，
 * 见 {@code mixins/MixinCraftingStationLogic}）。这一层只干一件事：<b>决定那 27 格显示什么</b>
 * —— 搜索、排序、翻页都在客户端算，算完把这一页的物品键推给服务端，
 * 服务端的槽位内容随之变化，两边永远是同一页。
 *
 * <p>
 * 为什么搜索/排序不由服务端做：排序键是「本地化名字 + 拼音」，服务端没有语言文件，
 * 算出来的顺序和客户端不一样，界面就会和服务端对不上号（点第 3 格取出别的东西）。
 *
 * <p>
 * 按 {@code P} 切出搜索栏；不切出来也能用 —— 存储区默认显示按名称排序的第一页，
 * 滚轮在存储区上滚动即可翻页（页码显示在存储区标题后面）。
 *
 * <p>
 * <b>输入是怎么拦下来的</b>：1.7.10 的 Forge 没有可取消的鼠标/键盘事件，
 * 所以点击与键入交给 NEI 的 {@code IContainerInputHandler}
 * （返回 true 即消费掉，底层界面收不到）—— 见 {@code nei/StoragePanelInput}。
 * 没有 NEI 时搜索栏不启用，但存储区本身照常工作（它不需要拦任何输入）。
 */
public final class StoragePanel {

    /** 一页多少格。必须等于匠魂那边存储区的格数：两边显示的是同一页。 */
    private static final int PAGE = SharedStorageInventory.SIZE;

    private static final int PAD = 2;
    private static final int SEARCH_H = 14;
    private static final int FOOTER_H = 11;

    /** 切出/收起搜索栏的按键。 */
    private static final int TOGGLE_KEY = Keyboard.KEY_P;

    private static final StoragePanel INSTANCE = new StoragePanel();

    public static StoragePanel get() {
        return INSTANCE;
    }

    private final List<StorageViewEntry> filtered = new ArrayList<>();

    private GuiTextField search;
    private boolean focused;
    private int page;
    private int revision = -1;
    private String lastQuery = "\u0000";

    /** 搜索栏是不是被玩家切出来了（默认关：存储区是主力，它只负责搜索）。 */
    private boolean toggled;

    /** 这一帧算出来的几何信息，点击判定用 */
    private boolean visible;
    private GuiScreen lastScreen;
    private int x;
    private int y;
    private int width;

    /** 上一次推给服务端的那一页（顺序也算：顺序变了就得重推）。 */
    private List<ItemKey> sentKeys;
    /** 这次打开界面有没有要过全量快照。 */
    private boolean syncRequested;

    private StoragePanel() {}

    // ==================================================================
    // 每帧：算这一页 + 推给服务端（+ 需要时画搜索栏）
    // ==================================================================

    public void draw(GuiScreen screen, int mouseX, int mouseY) {
        visible = false;
        if (!(screen instanceof GuiContainer)) return;

        StationRef station = TinkersScreens.sharedChestStation(screen);
        if (station == null) {
            // 不是「挂着共享存储的合成站」：清掉状态，免得下次开界面时把新的界面
            // 当成同一块（那会导致这一页永远不重推）
            lastScreen = null;
            sentKeys = null;
            return;
        }

        if (screen != lastScreen) {
            lastScreen = screen;
            sentKeys = null;
            syncRequested = false;
        }

        if (!ClientStorageCache.isReady()) {
            // 手里还没有共享存储的快照（刚上线就直接来开合成站）：
            // 向服务端要一份，然后等 —— 没有数据时这一页只能是空的
            if (!syncRequested) {
                syncRequested = true;
                send(station, Collections.<ItemKey>emptyList(), true);
            }
            return;
        }

        rebuildIfNeeded();
        pushView(station);
        updateTitleSuffix(station);

        if (!toggled) return;

        layout((GuiContainer) screen, screen);

        // 背景 + 边框（原版箱子配色）
        fill(x - PAD, y - PAD, x + width + PAD, y + SEARCH_H + FOOTER_H + PAD, 0xFFC6C6C6);
        border(x - PAD, y - PAD, x + width + PAD, y + SEARCH_H + FOOTER_H + PAD, 0xFF555555);

        if (search == null) {
            search = new GuiTextField(
                net.minecraft.client.Minecraft.getMinecraft().fontRenderer,
                x + 2,
                y + 2,
                width - 4,
                SEARCH_H - 4);
            search.setMaxStringLength(64);
            search.setText(lastQuery.equals("\u0000") ? "" : lastQuery);
        }
        search.xPosition = x + 2;
        search.yPosition = y + 2;
        search.width = width - 4;
        search.setFocused(focused);
        search.drawTextBox();
        if (search.getText()
            .isEmpty()) {
            net.minecraft.client.Minecraft.getMinecraft().fontRenderer
                .drawStringWithShadow(StatCollector.translateToLocal("futa_gtnh.panel.search"), x + 5, y + 5, 0x808080);
        }

        int totalPages = totalPages();
        net.minecraft.client.Minecraft.getMinecraft().fontRenderer.drawString(
            StatCollector.translateToLocalFormatted("futa_gtnh.panel.page", page + 1, totalPages, filtered.size()),
            x + 1,
            y + SEARCH_H + 2,
            0x404040);

        visible = true;
    }

    /**
     * 贴着界面的正下方排。合成站界面固定 176×166，屏幕下方通常还剩一条，
     * 放得下 14 高的搜索框 + 11 高的页码行（GUI scale 2 时刚好够）；
     * 实在放不下就往上贴，宁可压住界面底边也不能跑到屏幕外。
     */
    private void layout(GuiContainer gui, GuiScreen screen) {
        width = 162;
        // guiLeft / guiTop 在 GuiContainer 里是 protected，跨包读不到，反射读一次就够（结果缓存）。
        // 开发环境是 MCP 名，正式 jar 经过 reobf 之后是 SRG 名，两个都试一遍。
        int guiLeft = Accessors.intField(gui, "guiLeft", "field_147003_i");
        int guiTop = Accessors.intField(gui, "guiTop", "field_147009_r");
        int guiW = Accessors.intField(gui, "xSize", "field_146999_f");
        int guiH = Accessors.intField(gui, "ySize", "field_147000_g");
        if (guiW <= 0) guiW = 176;
        if (guiH <= 0) guiH = 166;

        x = guiLeft + (guiW - width) / 2;
        y = guiTop + guiH + 2;
        int bottom = y + SEARCH_H + FOOTER_H + PAD;
        if (bottom > screen.height) {
            y = Math.max(0, screen.height - (SEARCH_H + FOOTER_H + PAD));
        }
    }

    /**
     * 重新过滤 + 排序（只在「内容变了」或「搜索词变了」的时候做）。
     *
     * <p>
     * 排序固定按名称：存储数量每时每刻都在变，如果按数量排，取走一组之后整片格子会重排，
     * 鼠标底下的东西、甚至服务端那一页都会跟着跳 —— 那种「点一下世界就变了」的体验
     * 比顺序不够漂亮糟糕得多。
     */
    private void rebuildIfNeeded() {
        String query = search == null ? "" : search.getText();
        int now = ClientStorageCache.getRevision();
        if (now == revision && query.equals(lastQuery)) return;
        revision = now;
        lastQuery = query;

        filtered.clear();
        StorageSearch.filter(ClientStorageCache.items(), StorageSearch.compile(query), filtered);
        StorageSort.sort(filtered, StorageSort.BY_NAME);

        int maxPage = totalPages() - 1;
        if (page > maxPage) page = maxPage;
        if (page < 0) page = 0;
    }

    private int totalPages() {
        return Math.max(1, (filtered.size() + PAGE - 1) / PAGE);
    }

    /** 这一页的物品键（长度恒为 {@link #PAGE}，空位是 null，位置就是槽位号）。 */
    private List<ItemKey> pageKeys() {
        List<ItemKey> keys = new ArrayList<>(PAGE);
        int start = page * PAGE;
        for (int i = 0; i < PAGE; i++) {
            int index = start + i;
            StorageViewEntry entry = index >= 0 && index < filtered.size() ? filtered.get(index) : null;
            keys.add(entry == null ? null : entry.getItemKey());
        }
        return keys;
    }

    /**
     * 把这一页推给服务端。
     *
     * <p>
     * 只在<b>顺序真的变了</b>的时候发：数量变化不影响「第 i 格是哪种物品」，
     * 服务端那边数量是现查存储的，所以取走一组、别人存进一组都不需要重推 ——
     * 否则每挖一块矿都要发一个包。
     */
    private void pushView(StationRef station) {
        List<ItemKey> keys = pageKeys();
        if (keys.equals(sentKeys)) return;
        sentKeys = keys;
        send(station, keys, false);
    }

    /**
     * 存储区标题后面的小字（页码 / 搜索命中最多条数）。
     *
     * <p>
     * 不打开搜索栏时，这是玩家唯一的「我在第几页、筛掉了多少」提示；
     * 标题是匠魂每帧从我们这里现取的，改了下一帧就变。
     */
    private void updateTitleSuffix(StationRef station) {
        StringBuilder suffix = new StringBuilder();
        if (!lastQuery.isEmpty()) {
            suffix.append(StatCollector.translateToLocalFormatted("futa_gtnh.station.title.items", filtered.size()));
        }
        int totalPages = totalPages();
        if (totalPages > 1) {
            suffix
                .append(StatCollector.translateToLocalFormatted("futa_gtnh.station.title.page", page + 1, totalPages));
        }

        // 每帧都会走到这里，字符串没变就别重建（标题是匠魂每帧现取的，不需要主动通知）
        String text = suffix.toString();
        if (text.equals(lastSuffix)) return;
        lastSuffix = text;
        station.inventory.setClientSuffix(text);
    }

    private String lastSuffix = "";

    private static void send(StationRef station, List<ItemKey> keys, boolean requestSync) {
        NetworkHandler.INSTANCE
            .sendToServer(new PacketStationView(station.dimension, station.x, station.y, station.z, keys, requestSync));
    }

    /** 这个界面里现在有没有画出搜索栏（输入钩子先问它，避免误吃别的事件）。 */
    public boolean isShownIn(GuiContainer gui) {
        return visible && gui == lastScreen;
    }

    /**
     * 原生存储区第 {@code slot} 格（0..26，行优先）这一帧对应的共享存储条目。
     *
     * <p>
     * 给「格子里画出真实数量」用（见 {@link StationAmounts}）：原生槽位里的物品栈
     * 只能显示到 64（超过会被原版整叠搬走，见 {@code SharedStorageInventory} 的说明），
     * 所以真正的数字得我们自己画。这里的顺序就是推给服务端的顺序，
     * 和服务端第 46+i 格一一对应。
     *
     * @return 条目；这一帧没有数据（快照没到 / 不是合成站 / 翻页还没生效）时返回 null
     */
    public StorageViewEntry entryAt(int slot) {
        if (slot < 0 || slot >= PAGE) return null;
        if (!ClientStorageCache.isReady()) return null;
        int index = page * PAGE + slot;
        if (index < 0 || index >= filtered.size()) return null;
        return filtered.get(index);
    }

    // ==================================================================
    // 输入（都由 NEI 的 IContainerInputHandler 转发过来）
    // ==================================================================

    /** @return true 表示这次点击被搜索栏吃掉了，底层界面不该再看到 */
    public boolean mouseClicked(GuiScreen screen, int mouseX, int mouseY, int button) {
        if (!visible) return false;

        if (insideSearch(mouseX, mouseY)) {
            focused = search != null;
            if (search != null) search.mouseClicked(mouseX, mouseY, button);
            focused = search != null && search.isFocused();
            return true;
        }

        // 搜索栏空白处点一下：退出输入状态（否则键盘一直被搜索框吃着）
        if (insidePanel(mouseX, mouseY)) {
            focused = false;
            return true;
        }
        return false;
    }

    /**
     * 滚轮翻页。
     *
     * <p>
     * 鼠标在<b>存储区</b>或者搜索栏上时才接管：NEI 的物品面板、别的界面区域照旧
     * 走它们自己的滚轮逻辑（NEI 的输入钩子先拿到事件，我们只在自己的地盘上抢）。
     */
    public boolean mouseScrolled(GuiScreen screen, int dir) {
        if (lastScreen == null) return false;

        int mouseX = mouseXOf(screen);
        int mouseY = mouseYOf(screen);
        boolean overChest = TinkersScreens.isMouseOverSharedChest(screen, mouseX, mouseY);
        if (!overChest && !(visible && insidePanel(mouseX, mouseY))) return false;

        int next = page + (dir > 0 ? -1 : 1);
        if (next < 0) next = 0;
        int maxPage = totalPages() - 1;
        if (next > maxPage) next = maxPage;
        page = next;
        // 翻页后即使什么都没变（已经在第一页）也要吃掉：滚轮在存储区上不该有别的作用
        return true;
    }

    /** @return true 表示这个按键被搜索栏吃掉了 */
    public boolean keyTyped(GuiScreen screen, char typed, int keyCode) {
        // 开关按键最先处理：搜索栏没显示时也要能按出来。
        // 只在「挂着共享存储的合成站」上接管 —— 别的匠魂工作站界面里 P 不是我们的键
        if (keyCode == TOGGLE_KEY && !focused && lastScreen != null) {
            toggled = !toggled;
            focused = false;
            return true;
        }
        if (!visible || !focused || search == null) return false;

        if (keyCode == 1) { // Esc：先退出输入状态，不关界面
            focused = false;
            search.setFocused(false);
            return true;
        }
        if (keyCode == 28 || keyCode == 156) { // 回车：收起键盘
            focused = false;
            search.setFocused(false);
            return true;
        }
        if (search.textboxKeyTyped(typed, keyCode)) return true;
        // 把「可能被原版当成快捷键」的字符也吃掉：聚焦时就该只在输入框里打字
        return keyCode != 0 || typed >= 32;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private boolean insidePanel(int mouseX, int mouseY) {
        return mouseX >= x - PAD && mouseX < x + width + PAD
            && mouseY >= y - PAD
            && mouseY < y + SEARCH_H + FOOTER_H + PAD;
    }

    private boolean insideSearch(int mouseX, int mouseY) {
        return mouseX >= x + 2 && mouseX < x + width - 2 && mouseY >= y + 2 && mouseY < y + SEARCH_H;
    }

    private static int mouseXOf(GuiScreen screen) {
        return Mouse.getEventX() * screen.width / net.minecraft.client.Minecraft.getMinecraft().displayWidth;
    }

    private static int mouseYOf(GuiScreen screen) {
        return screen.height
            - Mouse.getEventY() * screen.height / net.minecraft.client.Minecraft.getMinecraft().displayHeight
            - 1;
    }

    private static void fill(int x1, int y1, int x2, int y2, int argb) {
        float a = (argb >>> 24) / 255.0F;
        float r = ((argb >> 16) & 0xFF) / 255.0F;
        float g = ((argb >> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(r, g, b, a);
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertex(x1, y2, 0.0D);
        tess.addVertex(x2, y2, 0.0D);
        tess.addVertex(x2, y1, 0.0D);
        tess.addVertex(x1, y1, 0.0D);
        tess.draw();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void border(int x1, int y1, int x2, int y2, int argb) {
        fill(x1, y1, x2, y1 + 1, argb);
        fill(x1, y2 - 1, x2, y2, argb);
        fill(x1, y1, x1 + 1, y2, argb);
        fill(x2 - 1, y1, x2, y2, argb);
    }
}
