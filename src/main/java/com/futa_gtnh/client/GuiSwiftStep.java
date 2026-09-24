package com.futa_gtnh.client;

import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.futa_gtnh.item.ItemSwiftStep;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketSetSwiftStep;

/**
 * 迅步的调整界面：飞行速度、移动速度、照明亮度各一组控件。
 *
 * <p>
 * 普通 {@link GuiScreen} 而不是 {@code GuiContainer}：这里没有任何槽位，
 * 只是在编辑手里那个物品的 NBT。用 GuiContainer 反而要凭空造一个 Container。
 *
 * <p>
 * 改动会<b>同时写本地物品和发给服务端</b>：本地写是为了界面立刻反映出来
 * （不然要等一个来回），发服务端是因为物品的权威副本在那边 ——
 * 只写本地的话，把迅步丢出去、放箱子里，倍率就丢了。
 */
public class GuiSwiftStep extends GuiScreen {

    private static final int GUI_WIDTH = 200;
    private static final int GUI_HEIGHT = 306;

    // 飞行那一组
    private static final int BTN_FLIGHT_DOWN = 0;
    private static final int BTN_FLIGHT_UP = 1;
    private static final int FLIGHT_PRESET_BASE = 100;

    // 移动那一组
    private static final int BTN_WALK_DOWN = 2;
    private static final int BTN_WALK_UP = 3;
    private static final int WALK_PRESET_BASE = 200;

    // 照明那一组
    private static final int BTN_LIGHT_DOWN = 4;
    private static final int BTN_LIGHT_UP = 5;
    private static final int LIGHT_PRESET_BASE = 300;

    private static final int BTN_DONE = 9;

    /** 微调步长。 */
    private static final float STEP = 0.25F;

    /**
     * 预设倍率。
     *
     * <p>
     * 最高档取 16 而不是 20：<b>专用服务器的飞行硬上限是 18</b>
     * （推导见 {@link ItemSwiftStep#serverSafeFlyMultiplier()}），
     * 超过之后每 tick 都会被服务端拉回原地，等于完全没加速。
     * 摆一个按下去就废掉的 20x 档位只会误导人。
     */
    private static final float[] PRESETS = { 1.0F, 2.0F, 3.0F, 5.0F, 10.0F, 16.0F };

    /**
     * 照明预设：关 / 暗 / 中 / 火把 / 最亮。
     *
     * <p>
     * 摆 14 是因为「火把」是玩家心里的参照物（原版火把就是 14），
     * 15 留给「比火把还亮」。
     */
    private static final int[] LIGHT_PRESETS = { 0, 4, 8, ItemSwiftStep.TORCH_LIGHT, ItemSwiftStep.MAX_LIGHT };

    private final ItemStack charm;

    public GuiSwiftStep(ItemStack charm) {
        this.charm = charm;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        int left = (width - GUI_WIDTH) / 2;
        int top = (height - GUI_HEIGHT) / 2;

        addPresetRow(left, top + 52, FLIGHT_PRESET_BASE);
        buttonList.add(new GuiSmallButton(BTN_FLIGHT_DOWN, left + 8, top + 74, 90, 18, "-0.25"));
        buttonList.add(new GuiSmallButton(BTN_FLIGHT_UP, left + 102, top + 74, 90, 18, "+0.25"));

        addPresetRow(left, top + 130, WALK_PRESET_BASE);
        buttonList.add(new GuiSmallButton(BTN_WALK_DOWN, left + 8, top + 152, 90, 18, "-0.25"));
        buttonList.add(new GuiSmallButton(BTN_WALK_UP, left + 102, top + 152, 90, 18, "+0.25"));

        addLightPresetRow(left, top + 208);
        buttonList.add(new GuiSmallButton(BTN_LIGHT_DOWN, left + 8, top + 230, 90, 18, "-1"));
        buttonList.add(new GuiSmallButton(BTN_LIGHT_UP, left + 102, top + 230, 90, 18, "+1"));

        buttonList.add(new GuiSmallButton(BTN_DONE, left + 52, top + 254, 96, 20, tr("gui.done")));

        updatePresetStates();
    }

    private void addPresetRow(int left, int y, int baseId) {
        // 6 个预设 × 29 + 5 个 2px 间隙 = 184，正好塞进 200 宽（左右各留 8）
        int presetWidth = 29;
        int gap = 2;
        int total = PRESETS.length * presetWidth + (PRESETS.length - 1) * gap;
        int startX = left + (GUI_WIDTH - total) / 2;
        for (int i = 0; i < PRESETS.length; i++) {
            buttonList.add(
                new GuiSmallButton(
                    baseId + i,
                    startX + i * (presetWidth + gap),
                    y,
                    presetWidth,
                    18,
                    formatPreset(PRESETS[i])));
        }
    }

    /** 照明那一行：5 个档位，标签是「关 / 4 / 8 / 火把 / 15」。 */
    private void addLightPresetRow(int left, int y) {
        int presetWidth = 29;
        int gap = 2;
        int total = LIGHT_PRESETS.length * presetWidth + (LIGHT_PRESETS.length - 1) * gap;
        int startX = left + (GUI_WIDTH - total) / 2;
        for (int i = 0; i < LIGHT_PRESETS.length; i++) {
            buttonList.add(
                new GuiSmallButton(
                    LIGHT_PRESET_BASE + i,
                    startX + i * (presetWidth + gap),
                    y,
                    presetWidth,
                    18,
                    formatLightPreset(LIGHT_PRESETS[i])));
        }
    }

    private static String formatLightPreset(int level) {
        if (level <= 0) return tr("item.futa_gtnh.swift_step.light.off");
        if (level == ItemSwiftStep.TORCH_LIGHT) return tr("item.futa_gtnh.swift_step.light.torch_short");
        return Integer.toString(level);
    }

    /** 超出上限的预设按钮置灰，而不是按了没反应 —— 让玩家看得出边界在哪。 */
    private void updatePresetStates() {
        float max = ItemSwiftStep.maxMultiplier();
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            if (button.id < FLIGHT_PRESET_BASE) continue;
            int index = button.id % 100;
            if (index < 0 || index >= PRESETS.length) continue;
            button.enabled = PRESETS[index] <= max;
        }
    }

    private static String formatPreset(float value) {
        if (value == Math.round(value)) {
            return ((int) value) + "x";
        }
        return String.format(Locale.ROOT, "%.1fx", value);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    private static String fixed(float value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        super.drawScreen(mouseX, mouseY, partialTicks);

        int left = (width - GUI_WIDTH) / 2;
        int top = (height - GUI_HEIGHT) / 2;

        drawCenteredString(
            fontRendererObj,
            EnumChatFormatting.GOLD + tr("item.futa_gtnh.swift_step.name"),
            width / 2,
            top + 8,
            0xFFFFFF);

        drawGroup(
            top + 26,
            "futa_gtnh.swift_step.gui.flight",
            ItemSwiftStep.getFlightMultiplier(charm),
            ItemSwiftStep.VANILLA_FLY_SPEED);
        drawGroup(
            top + 104,
            "futa_gtnh.swift_step.gui.walk",
            ItemSwiftStep.getWalkMultiplier(charm),
            ItemSwiftStep.VANILLA_WALK_SPEED);
        drawLightGroup(top + 182);

        // 三组的视觉分隔
        int left2 = left + 8;
        drawRect(left2, top + 96, left + GUI_WIDTH - 8, top + 97, 0xFF808080);
        drawRect(left2, top + 174, left + GUI_WIDTH - 8, top + 175, 0xFF808080);

        // 被服务器安全上限压住时说清楚：玩家调了 20 倍却只跑出 18 倍，
        // 不解释的话他只会觉得这东西坏了
        int rangeY = top + GUI_HEIGHT - 4;
        if (SwiftStepClientHandler.isClampedForServer()) {
            drawCenteredString(
                fontRendererObj,
                EnumChatFormatting.RED + StatCollector.translateToLocalFormatted(
                    "futa_gtnh.swift_step.gui.server_clamp",
                    fixed(ItemSwiftStep.serverSafeFlyMultiplier(), 0)),
                width / 2,
                rangeY - 12,
                0xFFFFFF);
        }

        drawCenteredString(
            fontRendererObj,
            EnumChatFormatting.DARK_GRAY + StatCollector
                .translateToLocalFormatted("futa_gtnh.swift_step.gui.range", fixed(ItemSwiftStep.maxMultiplier(), 2)),
            width / 2,
            rangeY,
            0xFFFFFF);
    }

    /** 画「标题 + 当前倍率 + 换算出来的实际值」。 */
    private void drawGroup(int labelY, String titleKey, float multiplier, float vanillaBase) {
        drawCenteredString(fontRendererObj, EnumChatFormatting.YELLOW + tr(titleKey), width / 2, labelY, 0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            EnumChatFormatting.AQUA + fixed(multiplier, 2)
                + "x"
                + EnumChatFormatting.GRAY
                + "  ("
                + fixed(multiplier * vanillaBase, 3)
                + " / "
                + fixed(vanillaBase, 3)
                + ")",
            width / 2,
            labelY + 12,
            0xFFFFFF);
    }

    /**
     * 照明那一组。
     *
     * <p>
     * 顺带说清楚它的性质：这是<b>客户端自己放的一个隐形光源</b>，
     * 所以只影响照亮，不影响服务端的刷怪判定 —— 不写明白的话，
     * 玩家戴着它发现洞里还是刷怪，只会觉得「坏了」。
     */
    private void drawLightGroup(int labelY) {
        drawCenteredString(
            fontRendererObj,
            EnumChatFormatting.YELLOW + tr("futa_gtnh.swift_step.gui.light"),
            width / 2,
            labelY,
            0xFFFFFF);
        drawCenteredString(
            fontRendererObj,
            EnumChatFormatting.AQUA + ItemSwiftStep.describeLightLocalized(
                charm) + EnumChatFormatting.GRAY + "  (" + tr("futa_gtnh.swift_step.gui.light.note") + ")",
            width / 2,
            labelY + 12,
            0xFFFFFF);
    }

    // ==================================================================
    // 交互
    // ==================================================================

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_DONE) {
            mc.displayGuiScreen(null);
            return;
        }

        if (button.id == BTN_FLIGHT_DOWN) {
            applyFlight(ItemSwiftStep.getFlightMultiplier(charm) - STEP);
        } else if (button.id == BTN_FLIGHT_UP) {
            applyFlight(ItemSwiftStep.getFlightMultiplier(charm) + STEP);
        } else if (button.id == BTN_WALK_DOWN) {
            applyWalk(ItemSwiftStep.getWalkMultiplier(charm) - STEP);
        } else if (button.id == BTN_WALK_UP) {
            applyWalk(ItemSwiftStep.getWalkMultiplier(charm) + STEP);
        } else if (button.id == BTN_LIGHT_DOWN) {
            applyLight(ItemSwiftStep.getLightLevel(charm) - 1);
        } else if (button.id == BTN_LIGHT_UP) {
            applyLight(ItemSwiftStep.getLightLevel(charm) + 1);
        } else if (button.id >= FLIGHT_PRESET_BASE && button.id < FLIGHT_PRESET_BASE + 100) {
            applyFlight(PRESETS[button.id - FLIGHT_PRESET_BASE]);
        } else if (button.id >= WALK_PRESET_BASE && button.id < WALK_PRESET_BASE + 100) {
            applyWalk(PRESETS[button.id - WALK_PRESET_BASE]);
        } else if (button.id >= LIGHT_PRESET_BASE && button.id < LIGHT_PRESET_BASE + 100) {
            int index = button.id - LIGHT_PRESET_BASE;
            if (index < LIGHT_PRESETS.length) applyLight(LIGHT_PRESETS[index]);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // 回车也当「完成」。
        //
        // 这里刻意没有 try/catch IOException：1.7.10 的 GuiScreen#keyTyped
        // **不声明**任何 checked 异常，包一层 catch 会直接编译不过
        // （「在相应的 try 语句主体中不能抛出异常错误」）。
        if (keyCode == Keyboard.KEY_RETURN) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void applyFlight(float value) {
        float clamped = ItemSwiftStep.clampMultiplier(value);
        ItemSwiftStep.setFlightMultiplier(charm, clamped);
        // 只发变化的那一项，另一项传 -1 表示「别动它」
        NetworkHandler.INSTANCE.sendToServer(new PacketSetSwiftStep(clamped, PacketSetSwiftStep.UNCHANGED));
        updatePresetStates();
    }

    private void applyWalk(float value) {
        float clamped = ItemSwiftStep.clampMultiplier(value);
        ItemSwiftStep.setWalkMultiplier(charm, clamped);
        NetworkHandler.INSTANCE.sendToServer(new PacketSetSwiftStep(PacketSetSwiftStep.UNCHANGED, clamped));
        updatePresetStates();
    }

    /**
     * 改照明亮度。
     *
     * <p>
     * 光线放在客户端的世界里（见 {@code SwiftStepLight}），所以本地写下去就立刻生效，
     * 发给服务端只是为了让物品的权威副本也记住这个值 —— 不然把迅步丢出去再捡回来，
     * 亮度就丢了。
     */
    private void applyLight(int value) {
        int clamped = ItemSwiftStep.clampLight(value);
        ItemSwiftStep.setLightLevel(charm, clamped);
        NetworkHandler.INSTANCE
            .sendToServer(new PacketSetSwiftStep(PacketSetSwiftStep.UNCHANGED, PacketSetSwiftStep.UNCHANGED, clamped));
        updatePresetStates();
    }
}
