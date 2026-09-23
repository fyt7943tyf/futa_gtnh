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
 * 迅步的倍率调整界面：飞行速度和移动速度各一组控件。
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
    private static final int GUI_HEIGHT = 204;

    // 飞行那一组
    private static final int BTN_FLIGHT_DOWN = 0;
    private static final int BTN_FLIGHT_UP = 1;
    private static final int FLIGHT_PRESET_BASE = 100;

    // 移动那一组
    private static final int BTN_WALK_DOWN = 2;
    private static final int BTN_WALK_UP = 3;
    private static final int WALK_PRESET_BASE = 200;

    private static final int BTN_DONE = 9;

    /** 微调步长。 */
    private static final float STEP = 0.25F;

    /**
     * 预设倍率。
     *
     * <p>
     * 上限放到 20 是因为玩家反馈「5 倍不够快」。但要知道 20 倍差不多就是
     * <b>专用服务器的物理上限</b>了：飞行终端速度约等于
     * {@code flySpeed × 9.1} 格/tick，20 倍正好卡在 9.1 格/tick，
     * 而 {@code NetHandlerPlayServer} 在单轴超过 10 格/tick 时会判定
     * 「moved too quickly」并把你拉回原地。再往上就得自己承担被拉回的风险。
     */
    private static final float[] PRESETS = { 1.0F, 2.0F, 3.0F, 5.0F, 10.0F, 20.0F };

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

        buttonList.add(new GuiSmallButton(BTN_DONE, left + 52, top + 176, 96, 20, tr("gui.done")));

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

        // 两组的视觉分隔
        int left2 = left + 8;
        drawRect(left2, top + 96, left + GUI_WIDTH - 8, top + 97, 0xFF808080);

        drawCenteredString(
            fontRendererObj,
            EnumChatFormatting.DARK_GRAY + StatCollector
                .translateToLocalFormatted("futa_gtnh.swift_step.gui.range", fixed(ItemSwiftStep.maxMultiplier(), 2)),
            width / 2,
            top + GUI_HEIGHT - 4,
            0xFFFFFF);
    }

    /** 画一组「标题 + 当前倍率 + 换算出来的实际值」。 */
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
        } else if (button.id >= FLIGHT_PRESET_BASE && button.id < FLIGHT_PRESET_BASE + 100) {
            applyFlight(PRESETS[button.id - FLIGHT_PRESET_BASE]);
        } else if (button.id >= WALK_PRESET_BASE && button.id < WALK_PRESET_BASE + 100) {
            applyWalk(PRESETS[button.id - WALK_PRESET_BASE]);
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
}
