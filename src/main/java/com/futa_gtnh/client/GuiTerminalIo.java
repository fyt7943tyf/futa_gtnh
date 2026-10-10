package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IIcon;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.BlockSharedTerminal;
import com.futa_gtnh.block.TerminalIoConfig;
import com.futa_gtnh.block.TerminalOutputFilter;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageAction;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * 共享终端方块「六个面怎么主动搬东西」的配置界面。
 *
 * <p>
 * 分三个页签，各管一件事，免得全挤在一起互相压：
 * <ul>
 * <li><b>面配置</b>：中间一个 <b>3D 小方块</b>（EIO 那套做法），贴的就是方块在世界上
 * 用的那六个材质 —— 世界里看到的点数记号，和这里点的是同一套。
 * 点一个面选中它，右边用「关 / 抽入 / 输出」两组按钮分别设它的物品和流体方向；</li>
 * <li><b>筛选</b>：每面独立的输出白名单：物品/流体条目 + 矿辞预设 + 自定义前缀；</li>
 * <li><b>节奏</b>：这台终端自己的间隔与每轮数量。</li>
 * </ul>
 *
 * <p>
 * 立体方块只画得出三个面，所以有个「翻转视角」按钮切到对面那一角，六个面都点得到。
 * 点击判定和绘制用的是同一套坐标（凸多边形同侧判定），不会出现「看着点到了、
 * 其实选的是别的面」。
 *
 * <p>
 * 数据来源：全部读 {@link ClientTerminalIo} 里那份服务端推过来的快照；每改一下就整份
 * 发回服务端（{@code SET_TERMINAL_IO}），服务端立刻回一份权威值覆盖本地。
 * 界面<b>不新建容器</b>：终端界面那个容器一直开着，关掉这里就回到终端界面。
 */
public class GuiTerminalIo extends GuiScreen {

    /** {@code GL_CONSTANT_COLOR}：混合因子取 {@code glBlendColor} 设的常量色（LWJGL 没导出这两个）。 */
    private static final int GL_CONSTANT_COLOR = 0x8001;
    /** {@code GL_ONE_MINUS_CONSTANT_ALPHA}：同上，取常量 alpha 的补。 */
    private static final int GL_ONE_MINUS_CONSTANT_ALPHA = 0x8002;

    private static final int GUI_WIDTH = 300;
    private static final int GUI_HEIGHT = 200;

    // ---- 按钮 id ----
    private static final int BTN_TAB_BASE = 0; // 0..2 页签
    private static final int BTN_FACE_RESET = 10;
    private static final int BTN_MODE_BASE = 20; // 20..25：物品/流体 × 关/抽/输
    private static final int BTN_KIND = 30;
    private static final int BTN_CLEAR = 31;
    private static final int BTN_DONE = 32;
    private static final int BTN_FILTER_FACE = 33;
    private static final int BTN_PRESET_BASE = 40; // 40..44
    private static final int BTN_RATE_BASE = 50; // 50..55：三行 × 减/加

    private static final int TAB_FACES = 0;
    private static final int TAB_FILTER = 1;
    private static final int TAB_RATE = 2;
    private static final int TAB_COUNT = 3;

    /** 筛选网格：10 列 × 4 行。 */
    private static final int COLS = 10;
    private static final int ROWS = 4;
    private static final int CELL = 18;
    private static final int PAGE = COLS * ROWS;

    /**
     * 立体方块画在哪、多大（半宽，像素）。
     *
     * <p>
     * 尺寸是按<b>周围那圈邻居方块</b>定的，不是按本体：邻居中心在 2 格外，加上自身半径，
     * 整圈实测是中心 ±3.1 * scale。scale=18 时约 ±56 / ±60px，
     * 于是整圈落在 x 24..136、y 38..158 —— 正好在页签行（到 y=36）之下、
     * 提示文字（y=160）之上，右边也不会顶到 x=166 那一列。
     */
    private static final int CUBE_CX = 80;
    private static final int CUBE_CY = 98;
    private static final int CUBE_SCALE = 18;
    /** 拖动多少像素算「转了一下」而不是「点了一下」。 */
    private static final int DRAG_THRESHOLD = 3;

    private final ContainerSharedTerminal container;

    private int guiLeft;
    private int guiTop;

    private int tab = TAB_FACES;

    private GuiTextField searchField;
    private GuiTextField prefixField;

    private boolean fluidTab;
    private int selectedFace = ForgeDirection.SOUTH.ordinal();

    /** 那个可以转的方块。数学在 {@link CubeView} 里，可以离线验证。 */
    private final CubeView cube = new CubeView();
    /** 这次按住是不是从方块（本体或周围的幽灵）上开始的 —— 决定能不能拖动。 */
    private boolean cubePressed;
    /** 松手时要不要顺手选中一个面：只有按在<b>本体</b>上才算数，按幽灵不算。 */
    private boolean selectArmed;
    private boolean cubeDragged;
    private int lastMouseX;
    private int lastMouseY;

    private final List<StorageViewEntry> shown = new ArrayList<>();
    private final List<GuiButton> faceModeButtons = new ArrayList<>();

    private String lastQuery = "\u0000";
    private Object lastSearchConfiguration;
    private boolean fluidTabCache;
    private int revision = -1;
    private boolean built;
    private int requestTimer;

    public GuiTerminalIo(ContainerSharedTerminal container) {
        this.container = container;
    }

    /**
     * 自绘的扁平按钮。
     *
     * <p>
     * <b>为什么不用原版按钮</b>：{@code widgets.png} 那套底图本来就是灰的，未悬停时还只有 0.8 亮度，
     * 放在深色面板上怎么看都像「禁用」—— 试过改字色，没用，因为问题出在底图上。
     * 这里直接画自己的底：普通态深灰、悬停态提亮、选中态绿色填充 + 亮边，
     * 一眼就能分清「能点」「正指着」「就是它」。
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

            // 画字之前把状态显式复位：drawRect 会把当前颜色留在它设的填充色上，
            // 而这些扁平按钮是自己画的，不能像原版按钮那样指望别人替你收拾干净。
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

    /** 打开这个界面。带上容器是因为它是「服务端认不认这次改动」的凭据。 */
    public static void open(ContainerSharedTerminal container) {
        if (container == null) return;
        net.minecraft.client.Minecraft.getMinecraft()
            .displayGuiScreen(new GuiTerminalIo(container));
    }

    // ==================================================================
    // 布局
    // ==================================================================

    @Override
    public void initGui() {
        super.initGui();
        guiLeft = (width - GUI_WIDTH) / 2;
        guiTop = (height - GUI_HEIGHT) / 2;

        // 每开一次界面重新记一遍诊断：写死成「一个进程只记一次」的话，
        // 第二次开界面就没有日志了（排查时正是要看第二次）
        ghostDiagLogged = false;
        ghostDetailLogged = false;

        buttonList.clear();
        faceModeButtons.clear();
        searchField = null;
        prefixField = null;

        // ---- 页签 ----
        for (int i = 0; i < TAB_COUNT; i++) {
            buttonList.add(new FlatButton(BTN_TAB_BASE + i, guiLeft + 8 + i * 66, guiTop + 20, 64, 16, ""));
        }

        // 只给<b>当前页签</b>建控件。GuiScreen 会把 buttonList 里的按钮全画出来
        // （disabled 只是画成灰的），所以这里不能用 disabled 代替「不显示」——
        // 第一版就是这么翻车的：三个页签的按钮同时画，预设按钮压在方向按钮上。
        if (tab == TAB_FACES) {
            buildFacesTab();
        } else if (tab == TAB_FILTER) {
            buildFilterTab();
        } else {
            buildRateTab();
        }

        buttonList.add(new FlatButton(BTN_DONE, guiLeft + GUI_WIDTH - 68, guiTop + 168, 60, 16, ""));

        refreshFilter();
        refreshLabels();
    }

    private void buildFacesTab() {
        buttonList.add(new FlatButton(BTN_FACE_RESET, guiLeft + 10, guiTop + 170, 68, 16, ""));
        // 先物品后流体，各三个方向按钮：关 / 抽入 / 输出
        for (int kind = 0; kind < 2; kind++) {
            for (int mode = 0; mode < 3; mode++) {
                GuiButton button = new FlatButton(
                    BTN_MODE_BASE + kind * 3 + mode,
                    guiLeft + 166 + mode * 40,
                    guiTop + 70 + kind * 34,
                    38,
                    16,
                    "");
                faceModeButtons.add(button);
                buttonList.add(button);
            }
        }

    }

    private void buildFilterTab() {
        buttonList.add(new FlatButton(BTN_FILTER_FACE, guiLeft + 210, guiTop + 20, 82, 16, ""));
        buttonList.add(new FlatButton(BTN_KIND, guiLeft + 8, guiTop + 40, 76, 16, ""));
        searchField = new GuiTextField(fontRendererObj, guiLeft + 90, guiTop + 40, 202, 16);
        searchField.setMaxStringLength(48);
        searchField.setText(lastQuery.equals("\u0000") ? "" : lastQuery);

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

    private String joinPrefixes() {
        StringBuilder builder = new StringBuilder();
        for (String prefix : currentFilter().getCustomPrefixes()) {
            if (builder.length() > 0) builder.append(' ');
            builder.append(prefix);
        }
        return builder.toString();
    }

    private TerminalOutputFilter currentFilter() {
        return ClientTerminalIo.get()
            .getOutputFilter(ForgeDirection.getOrientation(selectedFace));
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

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

    // ==================================================================
    // 数据
    // ==================================================================

    private void refreshFilter() {
        String query = searchField == null ? "" : searchField.getText();
        int now = ClientStorageCache.getRevision();
        Object searchConfiguration = NeiSearchBridge.configurationToken();
        if (built && now == revision
            && query.equals(lastQuery)
            && fluidTab == fluidTabCache
            && searchConfiguration == lastSearchConfiguration) return;
        lastSearchConfiguration = searchConfiguration;

        revision = now;
        lastQuery = query;
        fluidTabCache = fluidTab;
        built = true;

        List<StorageViewEntry> source = fluidTab ? ClientStorageCache.fluids() : ClientStorageCache.items();
        StorageSearch.filter(source, StorageSearch.compile(query), shown);
        StorageSort.sort(shown, StorageSort.BY_NAME);
        while (shown.size() > PAGE) {
            shown.remove(shown.size() - 1);
        }
    }

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
            // 发不出去就继续显示「同步中」，过一会儿再试
        }
    }

    // ==================================================================
    // 每帧
    // ==================================================================

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (searchField != null) searchField.updateCursorCounter();
        if (prefixField != null) prefixField.updateCursorCounter();
        if (!configReady() && ++requestTimer >= 10) {
            requestTimer = 0;
            requestConfig();
        }
        if (tab == TAB_FILTER) refreshFilter();
        refreshLabels();
    }

    /** 按当前页签决定哪些控件可见 —— 用不到的按钮直接 disabled，免得看不见却点得到。 */
    private void refreshLabels() {
        TerminalIoConfig config = ClientTerminalIo.get();

        String[] tabKeys = { "futa_gtnh.gui.terminal.io.tab.faces", "futa_gtnh.gui.terminal.io.tab.filter",
            "futa_gtnh.gui.terminal.io.tab.rate" };
        for (int i = 0; i < TAB_COUNT; i++) {
            GuiButton button = findButton(BTN_TAB_BASE + i);
            if (button == null) continue;
            // 当前页签靠 FlatButton 的绿色填充标出来（字是白的），按钮本身保持可用
            // （点自己就是重建一次，没有副作用）—— 用 disabled 表示「当前」会把它画成灰的，反而像坏了。
            button.displayString = tr(tabKeys[i]);
            button.enabled = true;
            if (button instanceof FlatButton) ((FlatButton) button).setMarked(i == tab);
        }

        boolean ready = configReady();
        boolean faces = tab == TAB_FACES && ready;
        boolean filter = tab == TAB_FILTER && ready;
        boolean rate = tab == TAB_RATE && ready;

        // 面配置：选中面的两组方向按钮。
        // 注意 faceModeButtons 只在面配置页签下才建得出来（见 initGui），别的页签时它是空的 ——
        // 这里必须先判空，不能直接 get()。
        ForgeDirection face = ForgeDirection.getOrientation(selectedFace);
        for (int kind = 0; kind < 2; kind++) {
            boolean fluid = kind == 1;
            TerminalIoConfig.Mode current = config.getMode(face, fluid);
            for (int mode = 0; mode < 3; mode++) {
                int index = kind * 3 + mode;
                if (index >= faceModeButtons.size()) break;
                GuiButton button = faceModeButtons.get(index);
                if (button == null) continue;
                button.enabled = faces;
                TerminalIoConfig.Mode value = TerminalIoConfig.Mode.values()[mode];
                // 选中态用 FlatButton 的绿色填充表示，字统一白色 —— 不再靠颜色代码
                button.displayString = tr(value.getLangKey());
                if (button instanceof FlatButton) ((FlatButton) button).setMarked(value == current);
            }
        }
        setEnabled(BTN_FACE_RESET, faces);

        // 筛选页
        setEnabled(BTN_KIND, filter);
        setEnabled(BTN_CLEAR, filter);
        setEnabled(BTN_FILTER_FACE, filter);
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

        GuiButton filterFaceButton = findButton(BTN_FILTER_FACE);
        if (filterFaceButton != null) filterFaceButton.displayString = faceName(face) + " (" + pipCount(face) + ") >";

        // 节奏页
        for (int i = 0; i < 6; i++) {
            setEnabled(BTN_RATE_BASE + i, rate);
        }

        GuiButton resetButton = findButton(BTN_FACE_RESET);
        if (resetButton != null) resetButton.displayString = tr("futa_gtnh.gui.terminal.io.reset");

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
        drawDefaultBackground();

        drawRect(guiLeft, guiTop, guiLeft + GUI_WIDTH, guiTop + GUI_HEIGHT, 0xC0101010);
        drawBorder(guiLeft, guiTop, GUI_WIDTH, GUI_HEIGHT, 0xFF808080);
        fontRendererObj.drawStringWithShadow(tr("futa_gtnh.gui.terminal.io.title"), guiLeft + 8, guiTop + 6, 0xFFFFFF);

        if (!configReady()) {
            fontRendererObj
                .drawStringWithShadow(tr("futa_gtnh.gui.terminal.io.syncing"), guiLeft + 8, guiTop + 96, 0xFFFF55);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        drawActiveMarkers();

        switch (tab) {
            case TAB_FACES:
                drawFacesTab(mouseX, mouseY);
                break;
            case TAB_FILTER:
                drawFilterTab(mouseX, mouseY);
                break;
            default:
                drawRateTab();
                break;
        }

        // 画按钮之前把状态设死：箱子/机器那类 TESR 会改深度、光照、贴图开关，
        // 而扁平按钮是自绘的（底色用 Tessellator、文字用立即模式），必须有个干净起点。
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawTooltips(mouseX, mouseY);
    }

    /**
     * 给「当前选中的那一个」画一圈亮边。
     *
     * <p>
     * 为什么要这么干：原版按钮的底图本来就是灰的，只把字改颜色很容易被当成禁用（事实上
     * 之前的灰色标签就是这么被误会的）。描边画在按钮<b>之前</b>，按钮盖住中间、只露一圈边，
     * 所以不管按钮本身什么底图，选中的那个都能一眼看出来。
     */
    private void drawActiveMarkers() {
        TerminalIoConfig config = ClientTerminalIo.get();
        GuiButton tabButton = findButton(BTN_TAB_BASE + tab);
        outline(tabButton, 0xFFFFD54A);

        if (tab != TAB_FACES) return;

        ForgeDirection face = ForgeDirection.getOrientation(selectedFace);
        for (int kind = 0; kind < 2; kind++) {
            TerminalIoConfig.Mode current = config.getMode(face, kind == 1);
            int index = kind * 3 + current.ordinal();
            if (index < faceModeButtons.size()) outline(faceModeButtons.get(index), 0xFF55FF55);
        }
    }

    private void outline(GuiButton button, int color) {
        if (button == null) return;
        drawBorder(button.xPosition - 1, button.yPosition - 1, button.width + 2, button.height + 2, color);
    }

    // ---- 面配置页 ----

    private void drawFacesTab(int mouseX, int mouseY) {
        drawCube(mouseX, mouseY);

        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.cube.hint"), guiLeft + 10, guiTop + 160, 0x909090);

        ForgeDirection face = ForgeDirection.getOrientation(selectedFace);
        fontRendererObj.drawStringWithShadow(
            tr("futa_gtnh.gui.terminal.io.current") + " "
                + faceName(face)
                + " "
                + EnumChatFormatting.GRAY
                + tr("futa_gtnh.gui.terminal.io.pips")
                + " "
                + pipCount(face),
            guiLeft + 166,
            guiTop + 40,
            0xFFFFFF);

        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.items"), guiLeft + 166, guiTop + 58, 0xC0C0C0);
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.fluids"), guiLeft + 166, guiTop + 92, 0xC0C0C0);

        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.pull.hint"), guiLeft + 166, guiTop + 122, 0x909090);
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.push.hint"), guiLeft + 166, guiTop + 134, 0x909090);
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.ghost.hint"), guiLeft + 166, guiTop + 150, 0x909090);
        fontRendererObj.drawString(tr("futa_gtnh.gui.terminal.io.ghost.colors"), guiLeft + 166, guiTop + 162, 0x909090);
    }

    /** 每个面印的点数，和材质一一对应（下1 上2 北3 南4 西5 东6）。 */
    private static int pipCount(ForgeDirection face) {
        switch (face) {
            case DOWN:
                return 1;
            case UP:
                return 2;
            case NORTH:
                return 3;
            case SOUTH:
                return 4;
            case WEST:
                return 5;
            default:
                return 6;
        }
    }

    /**
     * 画那个可以转的 3D 小方块，并把鼠标压着的面 / 选中的面高亮出来。
     *
     * <p>
     * 六个面都真的参与绘制：先按深度从远到近排序，再逐个投影成多边形，
     * 背面（法线背对镜头的）直接跳过 —— 所以转到哪个角度看到的都是正确的三个面。
     * 每个面贴的是方块自己的材质，并按受光强弱压一点亮度，不然六个面一样亮、看着是平的。
     *
     * <p>
     * 本体周围还会把<b>真的接了东西的那几面</b>画成一圈半透明方块（服务端算好推过来的，
     * 见 {@code ClientTerminalIo.hasItemTarget/hasFluidTarget}），颜色区分能搬什么：
     * 橙 = 只有物品容器，蓝 = 只有流体储罐，绿 = 两样都有。
     * 邻居方块一律先画、本体最后画，这样本体永远压在它们上面、看得清。
     */
    private void drawCube(int mouseX, int mouseY) {
        BlockSharedTerminal block = CommonProxy.blockSharedTerminal;
        if (block == null) return;

        int hovered = cubeFaceAt(mouseX, mouseY);
        List<Integer> visible = visibleFaces();
        // 远的先画：近的盖在上面，接缝才对
        visible.sort(Comparator.comparingDouble(cube::depth));

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_LIGHTING);
        // 显式打开贴图：界面背景那些 drawRect/drawGradientRect 会在结束时把 GL_TEXTURE_2D 关掉/开回来，
        // 不能指望它一定给你留着 —— 关着的时候画出来的是一块纯色，看着就像「材质没显示」。
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);

        // 幽灵**全部**等本体画完再画（半透明盖上去）。
        //
        // 原来按深度分了两拨：在本体后面的先画、前面的后画。结果是「背面那一侧的邻居
        // 先画 → 被不透明的本体整个盖住 → 界面上什么都没有」——玩家贴着 GT 输入仓、
        // 而它在默认视角的背面时，看到的就是「3D 完全不显示」。
        // 幽灵本来就是 0.45 透明度的，压在本体上只会把它染个色，不会把本体遮死，
        // 所以统一放到本体之后画，背面的邻居也能透出来（要正对着看就按「翻转视角」）。
        List<Integer> ghostFaces = new ArrayList<>();
        for (int face = 0; face < CubeView.FACES; face++) {
            if (!ClientTerminalIo.hasItemTarget(face) && !ClientTerminalIo.hasFluidTarget(face)) continue;
            ghostFaces.add(face);
        }
        logGhostDiagOnce(ghostFaces.size());

        // 关键：幽灵方块（尤其是 TESR 那一路）会把纹理绑成它自己的贴图，
        // 本体的面还按方块图集的 UV 采样 —— 不重新绑回来的话就会采到别的图（通常是全透明），
        // 表现就是「本体材质没了、只剩一个绿框」。
        mc.getTextureManager()
            .bindTexture(TextureMap.locationBlocksTexture);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        for (int face : visible) {
            drawQuad(
                block.getFaceIcon(face),
                cube.project(face, cubeX(), cubeY(), CUBE_SCALE),
                cube.brightness(face),
                hovered == face,
                selectedFace == face);
        }

        // 本体画完，再把所有幽灵半透明地盖上去（含背面的那几面）
        drawNeighbourGhosts(ghostFaces, visible);

        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LIGHTING);
        // 把界面该有的状态还回去：TESR 那一路会打开深度测试并往深度缓冲里写东西，
        // 之后画的本体方块、按钮、按钮上的字都会被深度挡掉
        // （症状就是「箱子盖住本体」和「按钮没字」，而没跑过 TESR 的页签一切正常）。
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(true);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    /** 画本体周围那圈「这一面真的有东西可搬」的半透明方块。 */
    /**
     * 一次性诊断：客户端这一侧认为「有几面连着东西」。
     *
     * <p>
     * 和 {@code PacketTerminalIoSync} 里那条服务端日志配对：服务端说算了什么、
     * 客户端说画了几面，一对比就知道问题出在哪一边。每次开界面只打一条。
     */
    private void logGhostDiagOnce(int facesWithTarget) {
        if (ghostDiagLogged) return;
        ghostDiagLogged = true;
        StringBuilder connected = new StringBuilder();
        for (int face = 0; face < CubeView.FACES; face++) {
            if (ClientTerminalIo.hasItemTarget(face)) connected.append(' ')
                .append(face)
                .append(":物品");
            if (ClientTerminalIo.hasFluidTarget(face)) connected.append(' ')
                .append(face)
                .append(":流体");
        }
        com.futa_gtnh.FutaGtnhMod.LOG.info(
            "共享终端 IO 诊断（客户端）：有目标的面 {} 个{}；方块中心=({}, {}) 缩放={} 可见面={} 界面={}x{}",
            facesWithTarget,
            connected.length() == 0 ? "（一个都没有）" : connected.toString(),
            (int) cubeX(),
            (int) cubeY(),
            CUBE_SCALE,
            visibleFaces(),
            width,
            height);
    }

    private static boolean ghostDiagLogged;

    /** 每个幽灵面只详记一次（每次开界面重置）。 */
    private static boolean ghostDetailLogged;

    private void drawNeighbourGhosts(List<Integer> faces, List<Integer> visible) {
        double[] tint = new double[3];
        for (int face : faces) {
            boolean items = ClientTerminalIo.hasItemTarget(face);
            boolean fluids = ClientTerminalIo.hasFluidTarget(face);

            // GT 的机器/总线/仓：只用纯色半透明块，不贴方块图标（理由见 neighbourTileOf）
            boolean gtNeighbour = neighbourTileOf(
                face) instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity;

            if (items && fluids) {
                tint[0] = 0.55D;
                tint[1] = 1.0D;
                tint[2] = 0.70D;
            } else if (items) {
                tint[0] = 1.0D;
                tint[1] = 0.78D;
                tint[2] = 0.42D;
            } else {
                tint[0] = 0.48D;
                tint[1] = 0.78D;
                tint[2] = 1.0D;
            }

            // 邻居方块贴的是<b>它自己</b>的材质（服务端把注册名发过来了）：边上放的是箱子就画成箱子，
            // 而不是画成又一个共享终端。认不出来（方块没了、没有图标之类）才退化成纯色半透明块。
            Block neighbour = neighbourBlock(face);
            byte meta = ClientTerminalIo.getNeighbourMeta(face);

            double[] offset = CubeView.neighbourOffset(face);

            // 先试「按它在世界里的样子画」：箱子、带特殊渲染器的机器，方块图标根本不是它的样子
            // （原版箱子的方块图标就是橡木木板），只有走 TESR 才画得出真正的箱子。
            boolean specialDrawn = renderSpecialGhost(face, offset, tint);
            if (specialDrawn && !ghostDetailLogged) {
                ghostDetailLogged = true;
                com.futa_gtnh.FutaGtnhMod.LOG.info("共享终端 IO 诊断（客户端）：面 {} 的邻居有特殊渲染器，已按真实样子画", face);
            }
            // GT 的邻居即使画出了真实样子，也要再叠一层纯色块：一来颜色编码（橙/蓝/绿）才完整，
            // 二来万一它的渲染器什么都没画出来（坐标约定对不上），至少还有块东西看得见。
            // 其它方块维持原样：TESR 画成功就不再多画一块，免得把箱子上色。
            if (specialDrawn && !gtNeighbour) continue;

            // 邻居方块和本体同朝向，所以看得见的还是那三个面
            for (int visibleFace : visible) {
                double[][] quad = cube
                    .projectOffset(visibleFace, offset[0], offset[1], offset[2], cubeX(), cubeY(), CUBE_SCALE);

                IIcon icon = null;
                if (gtNeighbour) {
                    // GT 的邻居：要它自己的真实贴图（拿不到才退回纯色块）
                    icon = gtFaceIcon(neighbourTileOf(face), neighbour, visibleFace);
                } else if (neighbour != null) {
                    try {
                        icon = neighbour.getIcon(visibleFace, meta);
                    } catch (Throwable ignored) {
                        icon = null;
                    }
                }

                if (!ghostDetailLogged) {
                    ghostDetailLogged = true;
                    com.futa_gtnh.FutaGtnhMod.LOG.info(
                        "共享终端 IO 诊断（客户端）：面 {} 邻居={} meta={} 偏移=({}, {}, {}) 图标={} 首个顶点=({}, {})",
                        face,
                        ClientTerminalIo.getNeighbourName(face),
                        meta,
                        (int) offset[0],
                        (int) offset[1],
                        (int) offset[2],
                        icon == null ? "空（走纯色回退）" : "有",
                        (int) quad[0][0],
                        (int) quad[0][1]);
                }

                if (icon != null) {
                    drawGhost(icon, quad, tint, cube.brightness(visibleFace));
                } else {
                    drawFlatGhost(quad, tint, cube.brightness(visibleFace));
                }
            }
        }
    }

    /**
     * 把邻居方块用它<b>自己的特殊渲染器</b>画出来 —— 也就是它在世界里的真实样子。
     *
     * <p>
     * 为什么必须这么干：箱子这类方块注册的「方块图标」跟它长什么样毫无关系
     * （原版 {@code BlockChest.registerBlockIcons} 注册的就是 {@code planks_oak}，
     * 真正的箱子是 TileEntitySpecialRenderer 画出来的模型）。只贴图标的话，
     * 玩家在边上放个箱子、界面上却显示一块木板。
     *
     * <p>
     * 半透明是用 {@code glBlendColor} + {@code GL_CONSTANT_COLOR} 做的：渲染器自己会
     * {@code glColor4f(...)} 把颜色覆盖掉，所以顶点色那条路指望不上，
     * 只能让混合因子从常量里取。这样模型整体按这个色和透明度合成，
     * 和原来的橙/蓝/绿区分也一致。
     *
     * @return 画成功了没有；false 时调用方退回 2D 贴图方块
     */
    private boolean renderSpecialGhost(int face, double[] offset, double[] tint) {
        net.minecraft.world.World world = mc.theWorld;
        if (world == null) return false;

        net.minecraft.tileentity.TileEntity terminal = container == null ? null : container.getTerminal();
        if (terminal == null) return false;

        int bx = terminal.xCoord + (int) Math.round(offset[0] / 2.0D);
        int by = terminal.yCoord + (int) Math.round(offset[1] / 2.0D);
        int bz = terminal.zCoord + (int) Math.round(offset[2] / 2.0D);

        net.minecraft.tileentity.TileEntity neighbour = world.getTileEntity(bx, by, bz);
        if (neighbour == null) return false;

        // ★ GT 的机器 / 输入总线 / 输入仓不走这条路。
        //
        // 它们的 TESR 是按方块实体<b>自己的世界坐标</b>定位的（渲染器内部读 te.xCoord 那一套），
        // 而这里只能把模型摆在 0,0,0 —— 于是模型被画到屏幕外去了。日志里能直接看到这一条：
        // 「面 2 走了 TESR 渲染器（应当看得见）」，屏幕上却什么都没有。
        // 原版箱子没这问题，因为箱子渲染器只用传进去的那三个坐标。
        //
        // GT 的渲染器会按方块实体自己的身体坐标定位（内部做 te.xCoord - x 那一套），
        // 所以这里必须把它<b>自己的坐标</b>传进去 —— 那样相对位置正好是 (0,0,0)，
        // 模型就落在我们当前摆好的位置上。传 0,0,0 的话它会算出 240 格开外，画到屏幕外。
        boolean gt = neighbour instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity;

        net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher dispatcher = net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.instance;
        if (!dispatcher.hasSpecialRenderer(neighbour)) return false;

        double[] screen = cube.projectPoint(offset[0], offset[1], offset[2], cubeX(), cubeY(), CUBE_SCALE);
        double[] angles = cube.getAngles();

        GL11.glPushMatrix();
        try {
            GL11.glTranslated(screen[0], screen[1], 0.0D);
            GL11.glScaled(2.0D * CUBE_SCALE, -2.0D * CUBE_SCALE, 2.0D * CUBE_SCALE);
            // 和这些多边形同一套朝向：先 pitch 再 yaw（GL 的矩阵是从后往前乘的）。
            // yaw 必须取负：glRotate 绕 +Y 的正方向，和 CubeView.rotateVector 里那套约定正好相反 ——
            // 不取负的话模型会朝着跟方块相反的方向转，看着就是「错乱自转」。
            GL11.glRotated(angles[1], 1.0D, 0.0D, 0.0D);
            GL11.glRotated(-angles[0], 0.0D, 1.0D, 0.0D);
            GL11.glTranslated(-0.5D, -0.5D, -0.5D);

            net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

            // 屏幕 y 朝下，所以上面的负缩放等于给模型做了一次镜像 —— 镜像会把手性翻过来，
            // 正反面互换，开着剔除时转起来面就忽隐忽现（看着像「错乱地自转」）。
            // 半透明方块本来就该六面都画，所以这里直接关掉剔除。
            GL11.glDisable(GL11.GL_CULL_FACE);

            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendColor((float) tint[0], (float) tint[1], (float) tint[2], 0.45F);
            // 这两个常量 LWJGL 的 GL14 里没有导出，直接写 GL 规范里的值（0x8001 / 0x8002）
            GL11.glBlendFunc(GL_CONSTANT_COLOR, GL_ONE_MINUS_CONSTANT_ALPHA);

            dispatcher.renderTileEntityAt(
                neighbour,
                gt ? (double) neighbour.xCoord : 0.0D,
                gt ? (double) neighbour.yCoord : 0.0D,
                gt ? (double) neighbour.zCoord : 0.0D,
                0.0F);

            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            return true;
        } catch (Throwable t) {
            // 别的模组的渲染器在 GUI 里抽风是常事：退回 2D 方块，不要把整个界面带崩
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            FutaGtnhMod.LOG.debug("共享存储：邻居方块的特殊渲染器画不出来，退回方块图标", t);
            return false;
        } finally {
            GL11.glPopMatrix();
        }
    }

    /** 按注册名把邻居方块解析出来；认不出来返回 null。 */
    /**
     * 某一面邻居的方块实体（客户端世界里的那个）。
     *
     * <p>
     * GT 的机器 / 输入总线 / 输入仓要按 GT 判断，不能只看方块图标：它们的方块图标是
     * 通用机壳，真正长什么样由 MTE 决定，而 MTE 的贴图只能通过 GT 自己的 ISBR 上下文画，
     * 拿不出一个 IIcon 来贴。硬贴会画出一台"普通机器"，比不贴更误导。
     */
    /**
     * 向 GT 的方块实体要「这一面长什么样」的真实贴图。
     *
     * <p>
     * GT 的机器 / 输入总线 / 输入仓，方块图标是通用机壳，真正的外观由方块实体（MTE）
     * 通过 {@code ITexuredTileEntity.getTexture} 给出。那些 ITexture 只能通过 GT 自己的
     * ISBR 上下文渲染，拿不到简单图标 —— 但标准实现 {@code GTRenderedTexture} 实现了
     * {@code IIconTexture}，可以直接问它要 {@code IIcon}（上下文只在少数特殊贴图里用，
     * 这里传 null，出问题就被 catch 住退回纯色块）。
     */
    /** 机器朝哪一面（GT 的正面）。拿不到就返回 null，调用方按「没有正面」处理。 */
    private static net.minecraftforge.common.util.ForgeDirection frontFacingOf(
        net.minecraft.tileentity.TileEntity tile) {
        if (!(tile instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity)) return null;
        try {
            return ((gregtech.api.interfaces.tileentity.IGregTechTileEntity) tile).getFrontFacing();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从一组 ITexture 里取出能直接当图标用的那一个。 */
    private static net.minecraft.util.IIcon firstIcon(gregtech.api.interfaces.ITexture[] textures, int forgeSide) {
        if (textures == null) return null;
        for (int i = 0; i < textures.length; i++) {
            if (textures[i] instanceof gregtech.common.render.IIconTexture) {
                try {
                    net.minecraft.util.IIcon icon = ((gregtech.common.render.IIconTexture) textures[i])
                        .getIcon(forgeSide, null);
                    if (icon != null) return icon;
                } catch (Throwable ignored) {
                    // 这一张拿不到，试下一张
                }
            }
        }
        return null;
    }

    private static net.minecraft.util.IIcon gtFaceIcon(net.minecraft.tileentity.TileEntity tile, Block block,
        int forgeSide) {
        if (!(tile instanceof gregtech.api.interfaces.tileentity.ITexturedTileEntity)) return null;
        if (forgeSide < 0 || forgeSide >= net.minecraftforge.common.util.ForgeDirection.values().length) return null;
        try {
            net.minecraftforge.common.util.ForgeDirection side = net.minecraftforge.common.util.ForgeDirection
                .values()[forgeSide];
            gregtech.api.interfaces.ITexture[] textures = ((gregtech.api.interfaces.tileentity.ITexturedTileEntity) tile)
                .getTexture(block, side);
            if (textures == null || textures.length == 0) return null;
            net.minecraft.util.IIcon icon = firstIcon(textures, forgeSide);
            if (icon != null) return icon;

            // 这一面没给图标（GT 有些面只在特定上下文里才有贴图）：
            // 退而用其它面的 —— 机器的外壳基本是同一套，总比一块纯色强
            // 退而用其它面的。★ 顺序很重要：先背面、再四个侧面，最后才是顶/底，
            // 而且**永远不用正面** —— 机器的正面是那块有辨识度的面板（屏幕/输入口），
            // 拿它铺满其余各面会变成「六面都是正面」，比一块纯色还离谱。
            net.minecraftforge.common.util.ForgeDirection front = frontFacingOf(tile);
            int[] order = { 3, 2, 4, 5, 1, 0 };
            for (int candidate : order) {
                if (candidate == forgeSide) continue;
                if (front != null && candidate == front.ordinal()) continue;
                net.minecraft.util.IIcon fallback = firstIcon(
                    ((gregtech.api.interfaces.tileentity.ITexturedTileEntity) tile)
                        .getTexture(block, net.minecraftforge.common.util.ForgeDirection.values()[candidate]),
                    candidate);
                if (fallback != null) return fallback;
            }
        } catch (Throwable t) {
            // 拿不到就当没有：退回纯色块，不影响别的
            return null;
        }
        return null;
    }

    private net.minecraft.tileentity.TileEntity neighbourTileOf(int face) {
        net.minecraft.tileentity.TileEntity terminal = container == null ? null : container.getTerminal();
        if (terminal == null || terminal.getWorldObj() == null) return null;
        double[] off = CubeView.neighbourOffset(face);
        return terminal.getWorldObj()
            .getTileEntity(
                terminal.xCoord + (int) Math.round(off[0] / 2.0D),
                terminal.yCoord + (int) Math.round(off[1] / 2.0D),
                terminal.zCoord + (int) Math.round(off[2] / 2.0D));
    }

    private static Block neighbourBlock(int face) {
        String name = ClientTerminalIo.getNeighbourName(face);
        if (name.isEmpty()) return null;
        try {
            return (Block) Block.blockRegistry.getObject(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 半透明的一个面，贴邻居方块自己的材质。 */
    private void drawGhost(IIcon icon, double[][] quad, double[] tint, float brightness) {
        if (icon == null) return;

        GL11.glEnable(GL11.GL_BLEND);
        // 不透明度比纯色块那条路高得多：贴的是邻居的真实贴图，太淡就看不出是什么机器了。
        // 颜色仍然乘上去，橙/蓝/绿的编码还在，只是不再盖过贴图本身。
        GL11.glColor4f((float) tint[0] * brightness, (float) tint[1] * brightness, (float) tint[2] * brightness, 0.85F);
        emitTexturedQuad(icon, quad);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** 认不出邻居是什么方块时的退路：纯色半透明，只表达「这儿有东西」。 */
    private void drawFlatGhost(double[][] quad, double[] tint, float brightness) {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glColor4f((float) tint[0] * brightness, (float) tint[1] * brightness, (float) tint[2] * brightness, 0.35F);
        GL11.glBegin(GL11.GL_QUADS);
        for (int i = quad.length - 1; i >= 0; i--) {
            GL11.glVertex2d(quad[i][0], quad[i][1]);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
    }

    /** @return 当前视角看得见的那些面（数学在 {@link CubeView} 里） */
    private List<Integer> visibleFaces() {
        List<Integer> faces = new ArrayList<>(3);
        for (int face = 0; face < CubeView.FACES; face++) {
            if (cube.isVisible(face)) faces.add(face);
        }
        return faces;
    }

    private int cubeX() {
        return guiLeft + CUBE_CX;
    }

    private int cubeY() {
        return guiTop + CUBE_CY;
    }

    /**
     * 发一个贴了方块材质的四边形（当前颜色/混合状态由调用方负责）。
     *
     * <p>
     * 用立即模式而不是 Tessellator：和原版 {@code drawRect} 走同一条路 ——
     * 界面里已经证明能画出来的东西都是这么画的，少一个变量。
     *
     * <p>
     * 顶点顺序是<b>反着</b>发的（0,3,2,1）。原因：投影时 y 轴是翻过来的（屏幕 y 向下），
     * 于是这些面在屏幕坐标里是顺时针的，而 GL 默认 {@code glFrontFace(GL_CCW)} +
     * 开着 {@code GL_CULL_FACE} 时，顺时针 = 背面 = 直接被丢掉 —— 表现就是
     * 「只有选中面的绿框、没有材质」（线框不受剔除影响，所以框还在）。
     * 反着发一圈就变成正面；UV 跟着顶点一起走，贴图方向不变。
     */
    private void emitTexturedQuad(IIcon icon, double[][] quad) {
        if (icon == null) return;

        float minU = icon.getMinU();
        float maxU = icon.getMaxU();
        float minV = icon.getMinV();
        float maxV = icon.getMaxV();

        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(minU, minV);
        GL11.glVertex2d(quad[0][0], quad[0][1]);
        GL11.glTexCoord2f(minU, maxV);
        GL11.glVertex2d(quad[3][0], quad[3][1]);
        GL11.glTexCoord2f(maxU, maxV);
        GL11.glVertex2d(quad[2][0], quad[2][1]);
        GL11.glTexCoord2f(maxU, minV);
        GL11.glVertex2d(quad[1][0], quad[1][1]);
        GL11.glEnd();
    }

    /**
     * 画一个贴了方块材质的四边形。
     *
     * <p>
     * 高亮分两种：鼠标压着（白色提亮）和当前选中（绿色提亮 + 描边）。
     * 都用半透明覆盖画，不动材质本身，所以点数标记一直看得见。
     */
    private void drawQuad(IIcon icon, double[][] quad, float brightness, boolean hovered, boolean selected) {
        if (icon == null) return;

        // 贴图这一道<b>关掉混合</b>再画：混合开着时，只要采样到的 alpha 是 0 就什么都看不见，
        // 而方块图集里未使用的区域确实是透明的 —— 关掉混合后画的一定是贴图本身的颜色。
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(brightness, brightness, brightness, 1.0F);
        emitTexturedQuad(icon, quad);

        if (hovered || selected) {
            GL11.glEnable(GL11.GL_BLEND);
            if (selected) {
                GL11.glColor4f(0.35F, 1.0F, 0.35F, 0.45F);
            } else {
                GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.25F);
            }
            GL11.glBegin(GL11.GL_QUADS);
            for (int i = quad.length - 1; i >= 0; i--) {
                GL11.glVertex2d(quad[i][0], quad[i][1]);
            }
            GL11.glEnd();
            GL11.glDisable(GL11.GL_BLEND);
        }
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);

        if (!selected) return;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(2.0F);
        GL11.glColor4f(0.35F, 1.0F, 0.35F, 1.0F);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (double[] corner : quad) {
            GL11.glVertex2d(corner[0], corner[1]);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    /** @return 鼠标压在哪个面上（-1 = 不在方块上） */
    private int cubeFaceAt(int mouseX, int mouseY) {
        return cube.hitTest(mouseX, mouseY, cubeX(), cubeY(), CUBE_SCALE);
    }

    /** @return 鼠标是不是压在周围那圈幽灵方块上（这些只用来拖动，不参与选面） */
    private boolean ghostAt(int mouseX, int mouseY) {
        for (int face = 0; face < CubeView.FACES; face++) {
            if (!ClientTerminalIo.hasItemTarget(face) && !ClientTerminalIo.hasFluidTarget(face)) continue;

            double[] offset = CubeView.neighbourOffset(face);
            for (int visibleFace = 0; visibleFace < CubeView.FACES; visibleFace++) {
                if (!cube.isVisible(visibleFace)) continue;
                double[][] quad = cube
                    .projectOffset(visibleFace, offset[0], offset[1], offset[2], cubeX(), cubeY(), CUBE_SCALE);
                if (CubeView.inside(quad, mouseX, mouseY)) return true;
            }
        }
        return false;
    }

    // ---- 筛选页 ----

    private void drawFilterTab(int mouseX, int mouseY) {
        // 这里刻意不再画一行「筛选」小标题：页签按钮本身就写着，位置又正好在页签底下会压上
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

    // ---- 节奏页 ----

    private void drawRateTab() {
        TerminalIoConfig config = ClientTerminalIo.get();

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
        TerminalIoConfig config = ClientTerminalIo.get();

        if (button.id >= BTN_TAB_BASE && button.id < BTN_TAB_BASE + TAB_COUNT) {
            applyPrefixes();
            tab = button.id - BTN_TAB_BASE;
            // 换页签要重建控件：GuiScreen 会把 buttonList 里<b>所有</b>按钮都画出来，
            // 只是 disabled 而已 —— 不重建的话三个页签的按钮会叠在一起
            // （第一版就是这么翻车的：预设按钮压在方向按钮上）
            initGui();
            return;
        }

        if (button.id != BTN_DONE && !configReady()) return;

        if (button.id == BTN_FILTER_FACE) {
            applyPrefixes();
            selectedFace = (selectedFace + 1) % TerminalIoConfig.FACES;
            initGui();
            return;
        }

        if (button.id == BTN_FACE_RESET) {
            cube.reset();
            selectedFace = ForgeDirection.SOUTH.ordinal();
            refreshLabels();
            return;
        }

        if (button.id >= BTN_MODE_BASE && button.id < BTN_MODE_BASE + 6) {
            int index = button.id - BTN_MODE_BASE;
            boolean fluid = index >= 3;
            TerminalIoConfig.Mode mode = TerminalIoConfig.Mode.values()[index % 3];
            config.setMode(ForgeDirection.getOrientation(selectedFace), fluid, mode);
            refreshLabels();
            pushConfig();
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
                close();
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

        if (tab == TAB_FACES && mouseButton == 0) {
            // 按在方块上：先记住，松手时再决定这是「转了一下」还是「点了一个面」。
            // 按在周围那圈幽灵方块上同样能拖 —— 不然手一偏到邻居身上就转不动了。
            selectArmed = cubeFaceAt(mouseX, mouseY) >= 0;
            cubePressed = selectArmed || ghostAt(mouseX, mouseY);
            cubeDragged = false;
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            return;
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

    /**
     * 按住左键在方块上拖 = 转动它。
     *
     * <p>
     * 移动超过 {@link #DRAG_THRESHOLD} 像素才算拖动，松手时才<b>不会</b>顺手选中一个面 ——
     * 不然想转到背面的人每次松手都会把当前那个面选走。
     */
    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
        if (!cubePressed || tab != TAB_FACES || clickedMouseButton != 0) return;

        int dx = mouseX - lastMouseX;
        int dy = mouseY - lastMouseY;
        if (!cubeDragged && Math.abs(dx) + Math.abs(dy) < DRAG_THRESHOLD) return;

        cubeDragged = true;
        // 方向按玩家手感调的：往右拖，方块跟着手走（之前是反的）
        cube.rotate(-dx * 1.2D, dy * 1.2D);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        super.mouseMovedOrUp(mouseX, mouseY, state);
        if (state != 0 || !cubePressed) return;

        boolean wasDrag = cubeDragged;
        boolean armed = selectArmed;
        cubePressed = false;
        selectArmed = false;
        cubeDragged = false;
        if (wasDrag || !armed || tab != TAB_FACES) return;

        int face = cubeFaceAt(mouseX, mouseY);
        if (face >= 0) {
            selectedFace = face;
            refreshLabels();
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
                close();
                return;
            }
            searchField.textboxKeyTyped(typedChar, keyCode);
            shown.clear();
            refreshFilter();
            return;
        }

        if (tab == TAB_FILTER && prefixField != null && prefixField.isFocused()) {
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

    /** 前缀是「回车才生效」的，切页签 / 关界面时补一次。 */
    private void applyPrefixes() {
        if (tab != TAB_FILTER || fluidTab || prefixField == null || !prefixField.getVisible() || !configReady()) return;
        if (prefixField.getText()
            .equals(joinPrefixes())) return;
        currentFilter().setCustomPrefixes(prefixField.getText());
        refreshLabels();
        pushConfig();
    }

    @Override
    public void onGuiClosed() {
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
