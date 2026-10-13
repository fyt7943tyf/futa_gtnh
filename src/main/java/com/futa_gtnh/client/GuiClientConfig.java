package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 客户端功能设置界面 —— 一个纯客户端的开关面板，逐项停用本模组的客户端功能。
 *
 * <p>
 * <b>边界先说清</b>：这里关掉的只是<b>客户端这一侧</b>的入口、界面、渲染和内嵌服务
 * （按键、GUI、光束、假光源、HTTP 服务……）。服务端逻辑与别人的服务端完全不受影响；
 * 单人模式下两端读同一份配置文件，等效整体停用。具体每一项拦哪儿，
 * 见 {@link Config} 里 {@code clientEnable*} 七个键的注释。
 *
 * <p>
 * <b>界面形态</b>参照 EZMiner 的配置面板（深色三层 drawRect + 分组小标题 +
 * 左标签右开关），控件由 MUI2 控件树绘制并处理点击，{@link GuiButton} 仅保存业务数据；七个开关一屏放得下，不需要滚动。
 *
 * <p>
 * <b>生效方式</b>是两步的（同 EZMiner）：点开关只改界面上的工作副本，
 * 「应用 / 保存并完成」才写入 {@code futa_gtnh.cfg}（{@link Config#saveClientFeatureToggles}）
 * 并执行运行时副作用（停网页服务、收光束/假光源、关小地图）。
 * ESC 直接关闭，<b>未应用的改动会被丢弃</b>。
 *
 * <p>
 * <b>入口</b>有两个：原版背包/创造模式界面里注入的齿轮按钮
 * （{@link ClientFeatureButtonOverlay}），以及 Mods 列表的 Config 按钮
 * （{@link ClientGuiFactory}，FML 会用 {@code (GuiScreen)} 单参构造器反射实例化
 * 本类，参数作 ESC 的返回目标）。
 */
@SideOnly(Side.CLIENT)
public class GuiClientConfig extends FutaGuiScreen {

    private static final int GUI_WIDTH = 290;
    private static final int TITLE_H = 24;
    private static final int SECTION_H = 18;
    private static final int ROW_H = 20;
    private static final int HINT_H = 12;
    private static final int BOTTOM_H = 26;
    private static final int PAD = 8;

    private static final int BTN_TOGGLE_BASE = 1; // 1..7 开关
    private static final int BTN_APPLY = 20;
    private static final int BTN_DEFAULTS = 21;
    private static final int BTN_SAVE_EXIT = 22;

    private static final int BUTTON_W = 34;
    private static final int BUTTON_H = 16;

    // EZMiner 同款配色：外阴影 / 面板 / 内衬 / 顶部强调线
    private static final int COLOR_SHADOW = 0x88000000;
    private static final int COLOR_PANEL = 0xCC0A0A14;
    private static final int COLOR_INNER = 0xFF16162A;
    private static final int COLOR_ACCENT = 0xFF4466CC;

    /** 一个开关行的定义：语言键 + 读写 Config 静态字段的手段（id 驱动，见 writeToConfig）。 */
    private static final class Section {

        final String titleKey;
        final int[] toggleIds;

        Section(String titleKey, int... toggleIds) {
            this.titleKey = titleKey;
            this.toggleIds = toggleIds;
        }
    }

    private static final int TOGGLE_SHARED_BAG = 0;
    private static final int TOGGLE_RTS = 1;
    private static final int TOGGLE_WEB_GUIDE = 2;
    private static final int TOGGLE_DUNGEON_MAP = 3;
    private static final int TOGGLE_LOCATOR = 4;
    private static final int TOGGLE_SWIFT_STEP = 5;
    private static final int TOGGLE_MINIGAME = 6;
    private static final int TOGGLE_COUNT = 7;

    // 注意：这几个数组存的是「去掉 futa_gtnh.clientconfig. 前缀」的短键，
    // tr() 会统一拼前缀 —— 存完整键的话前缀会拼两遍，翻译查不到就把原键吐出来
    private static final String[] NAME_KEYS = { "shared_bag_key.name", "rts.name", "web_guide.name", "dungeon_map.name",
        "locator.name", "swift_step.name", "minigame.name" };

    private static final String[] DESC_KEYS = { "shared_bag_key.desc", "rts.desc", "web_guide.desc", "dungeon_map.desc",
        "locator.desc", "swift_step.desc", "minigame.desc" };

    private static final Section[] SECTIONS = {
        new Section("section.keys", TOGGLE_SHARED_BAG, TOGGLE_RTS, TOGGLE_WEB_GUIDE, TOGGLE_DUNGEON_MAP),
        new Section("section.ui", TOGGLE_LOCATOR, TOGGLE_SWIFT_STEP, TOGGLE_MINIGAME) };

    /** ESC 返回的界面（模组列表入口进来时是 Mods 列表；null = 回游戏）。 */
    private final GuiScreen parentScreen;

    /** 工作副本：点开关只改这里，「应用」才写进 Config。 */
    private final boolean[] values = new boolean[TOGGLE_COUNT];

    public GuiClientConfig() {
        this(null);
    }

    /** FML 的 Mods 列表 Config 按钮走这个构造器（反射，单参 {@code GuiScreen}）。 */
    public GuiClientConfig(GuiScreen parentScreen) {
        this.parentScreen = parentScreen;
        for (int i = 0; i < TOGGLE_COUNT; i++) {
            values[i] = readFromConfig(i);
        }
    }

    private static boolean readFromConfig(int id) {
        switch (id) {
            case TOGGLE_SHARED_BAG:
                return Config.clientEnableSharedBagKey;
            case TOGGLE_RTS:
                return Config.clientEnableRts;
            case TOGGLE_WEB_GUIDE:
                return Config.clientEnableWebGuide;
            case TOGGLE_DUNGEON_MAP:
                return Config.clientEnableDungeonMap;
            case TOGGLE_LOCATOR:
                return Config.clientEnableLocatorUi;
            case TOGGLE_SWIFT_STEP:
                return Config.clientEnableSwiftStepClient;
            case TOGGLE_MINIGAME:
                return Config.clientEnableMinigameHelper;
            default:
                return true;
        }
    }

    private static void writeToConfig(int id, boolean value) {
        switch (id) {
            case TOGGLE_SHARED_BAG:
                Config.clientEnableSharedBagKey = value;
                break;
            case TOGGLE_RTS:
                Config.clientEnableRts = value;
                break;
            case TOGGLE_WEB_GUIDE:
                Config.clientEnableWebGuide = value;
                break;
            case TOGGLE_DUNGEON_MAP:
                Config.clientEnableDungeonMap = value;
                break;
            case TOGGLE_LOCATOR:
                Config.clientEnableLocatorUi = value;
                break;
            case TOGGLE_SWIFT_STEP:
                Config.clientEnableSwiftStepClient = value;
                break;
            case TOGGLE_MINIGAME:
                Config.clientEnableMinigameHelper = value;
                break;
            default:
                break;
        }
    }

    // ==================================================================
    // 布局
    // ==================================================================

    private int guiLeft;
    private int guiTop;
    private int panelHeight;
    private int rowHeight = ROW_H;

    @Override
    public void initGui() {
        super.initGui();
        buttonList.clear();

        int fixedHeight = TITLE_H + SECTIONS.length * SECTION_H + HINT_H + BOTTOM_H + PAD * 2;
        rowHeight = Math.max(BUTTON_H, Math.min(ROW_H, (height - fixedHeight - 8) / TOGGLE_COUNT));
        int contentH = TITLE_H;
        for (Section section : SECTIONS) {
            contentH += SECTION_H + section.toggleIds.length * rowHeight;
        }
        contentH += HINT_H + BOTTOM_H + PAD * 2;
        panelHeight = contentH;
        guiLeft = (width - GUI_WIDTH) / 2;
        guiTop = Math.max(4, (height - contentH) / 2);

        int y = guiTop + PAD + TITLE_H;
        for (Section section : SECTIONS) {
            y += SECTION_H;
            for (int toggleId : section.toggleIds) {
                buttonList.add(
                    new GuiButton(
                        BTN_TOGGLE_BASE + toggleId,
                        guiLeft + GUI_WIDTH - PAD - BUTTON_W,
                        y + (rowHeight - BUTTON_H) / 2,
                        BUTTON_W,
                        BUTTON_H,
                        boolLabel(values[toggleId])));
                y += rowHeight;
            }
        }

        int bottomY = guiTop + panelHeight - PAD - BOTTOM_H + 5;
        int buttonW = (GUI_WIDTH - PAD * 2 - 12) / 3;
        buttonList.add(new GuiButton(BTN_APPLY, guiLeft + PAD, bottomY, buttonW, BUTTON_H, tr("apply")));
        buttonList
            .add(new GuiButton(BTN_DEFAULTS, guiLeft + PAD + buttonW + 6, bottomY, buttonW, BUTTON_H, tr("defaults")));
        buttonList.add(
            new GuiButton(
                BTN_SAVE_EXIT,
                guiLeft + PAD + (buttonW + 6) * 2,
                bottomY,
                buttonW,
                BUTTON_H,
                tr("save_exit")));
    }

    // ==================================================================
    // 动作
    // ==================================================================

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id >= BTN_TOGGLE_BASE && button.id < BTN_TOGGLE_BASE + TOGGLE_COUNT) {
            int id = button.id - BTN_TOGGLE_BASE;
            values[id] = !values[id];
            button.displayString = boolLabel(values[id]);
            return;
        }

        switch (button.id) {
            case BTN_APPLY:
                applyChanges();
                break;
            case BTN_DEFAULTS:
                for (int i = 0; i < TOGGLE_COUNT; i++) {
                    values[i] = true;
                }
                refreshToggleLabels();
                break;
            case BTN_SAVE_EXIT:
                applyChanges();
                close();
                break;
            default:
                break;
        }
    }

    /** 写配置 + 执行运行时副作用（停服务、收光束/假光源/小地图）。 */
    private void applyChanges() {
        for (int i = 0; i < TOGGLE_COUNT; i++) {
            writeToConfig(i, values[i]);
        }
        Config.saveClientFeatureToggles();

        // —— 运行时副作用：让「关掉」立刻可见，而不是等下一个自然触发点 ——
        if (!Config.clientEnableWebGuide) {
            com.futa_gtnh.web.WebRecipeService.stop();
        }
        if (!Config.clientEnableDungeonMap) {
            RoguelikeMapClient.closeAll();
        }
        if (!Config.clientEnableLocatorUi) {
            // 光束是客户端画的，清掉本地状态 + 通知服务端取消追踪
            FutaGtnhMod.proxy.clearLocatorTracking();
        }
        if (!Config.clientEnableSwiftStepClient) {
            // 不再 tick 之后，把已经放出去的隐形光源收干净
            com.futa_gtnh.client.SwiftStepLight.reset();
        }
    }

    private void refreshToggleLabels() {
        for (Object button : buttonList) {
            GuiButton guiButton = (GuiButton) button;
            if (guiButton.id >= BTN_TOGGLE_BASE && guiButton.id < BTN_TOGGLE_BASE + TOGGLE_COUNT) {
                guiButton.displayString = boolLabel(values[guiButton.id - BTN_TOGGLE_BASE]);
            }
        }
    }

    private void close() {
        mc.displayGuiScreen(parentScreen);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        super.keyTyped(typedChar, keyCode);
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // 未应用的改动直接丢弃（界面注释和 tooltip 里都说清了这一点）
            close();
        }
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        // 深色面板三层叠（EZMiner 同款）：外阴影 → 面板 → 内衬，顶上一条强调线
        drawRect(guiLeft - 3, guiTop - 3, guiLeft + GUI_WIDTH + 3, guiTop + panelHeight + 3, COLOR_SHADOW);
        com.futa_gtnh.client.ui.FutaUiTheme.panel(guiLeft, guiTop, GUI_WIDTH, panelHeight);
        drawRect(guiLeft + 1, guiTop + 1, guiLeft + GUI_WIDTH - 1, guiTop + 3, COLOR_ACCENT);

        String title = tr("title");
        fontRendererObj.drawStringWithShadow(
            title,
            guiLeft + (GUI_WIDTH - fontRendererObj.getStringWidth(title)) / 2,
            guiTop + PAD + 4,
            0xFF55FFFF);

        int y = guiTop + PAD + TITLE_H;
        for (Section section : SECTIONS) {
            String header = EnumChatFormatting.BLUE + "— "
                + EnumChatFormatting.GRAY
                + tr(section.titleKey)
                + EnumChatFormatting.BLUE
                + " —";
            fontRendererObj.drawStringWithShadow(
                header,
                guiLeft + (GUI_WIDTH - fontRendererObj.getStringWidth(header)) / 2,
                y + 3,
                0xFFFFFF);
            y += SECTION_H;

            for (int toggleId : section.toggleIds) {
                fontRendererObj
                    .drawStringWithShadow(tr(NAME_KEYS[toggleId]), guiLeft + 14, y + (rowHeight - 8) / 2, 0xFFFFFF);
                y += rowHeight;
            }
        }

        fontRendererObj.drawString(tr("hint"), guiLeft + PAD + 2, guiTop + panelHeight - PAD - BOTTOM_H - 2, 0xB0B0B0);

        super.drawScreen(mouseX, mouseY, partialTicks);
        drawTooltipForHover(mouseX, mouseY);
    }

    /** 鼠标悬停在某一行的标签/按钮上时显示该功能的说明（拦截点与边界）。 */
    private void drawTooltipForHover(int mouseX, int mouseY) {
        int row = hitRow(mouseX, mouseY);
        if (row < 0) return;
        List<String> lines = new ArrayList<>();
        lines.add(EnumChatFormatting.YELLOW + tr(NAME_KEYS[row]));
        lines.add(EnumChatFormatting.GRAY + tr(DESC_KEYS[row]));
        drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
    }

    /** @return 鼠标所在的开关行序号（0..6），不在任何一行返回 -1。 */
    private int hitRow(int mouseX, int mouseY) {
        int y = guiTop + PAD + TITLE_H;
        for (Section section : SECTIONS) {
            y += SECTION_H;
            for (int toggleId : section.toggleIds) {
                if (mouseY >= y && mouseY < y + rowHeight
                    && mouseX >= guiLeft + PAD
                    && mouseX < guiLeft + GUI_WIDTH - PAD) {
                    return toggleId;
                }
                y += rowHeight;
            }
        }
        return -1;
    }

    // ==================================================================
    // 杂项
    // ==================================================================

    private static String boolLabel(boolean value) {
        return value ? EnumChatFormatting.GREEN + "ON" : EnumChatFormatting.RED + "OFF";
    }

    private static String tr(String suffix) {
        return StatCollector.translateToLocal("futa_gtnh.clientconfig." + suffix);
    }
}
