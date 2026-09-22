package com.futa_gtnh.client;

import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.futa_gtnh.Config;
import com.futa_gtnh.item.ItemFlightCharm;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketSetFlightSpeed;

/**
 * 飞行护符的倍率调整界面。
 *
 * <p>
 * 普通 {@link GuiScreen} 而不是 {@code GuiContainer}：这里没有任何槽位，
 * 只是在编辑手里那个物品的 NBT。用 GuiContainer 反而要凭空造一个 Container。
 *
 * <p>
 * 改动会<b>同时写本地物品和发给服务端</b>：本地写是为了界面立刻反映出来
 * （不然要等一个来回），发服务端是因为物品的权威副本在那边 ——
 * 只写本地的话，把护符丢出去、放箱子里，倍率就丢了。
 */
public class GuiFlightCharm extends GuiScreen {

    private static final int GUI_WIDTH = 200;
    private static final int GUI_HEIGHT = 112;

    private static final int BTN_STEP_DOWN = 0;
    private static final int BTN_STEP_UP = 1;
    private static final int BTN_DONE = 2;
    private static final int BTN_PRESET_BASE = 10;

    /** 微调步长。 */
    private static final float STEP = 0.25F;

    /** 预设倍率，按钮上显示成 "1x" "2x" 这样。 */
    private static final float[] PRESETS = { 1.0F, 2.0F, 3.0F, 5.0F, 10.0F };

    private final ItemStack charm;

    public GuiFlightCharm(ItemStack charm) {
        this.charm = charm;
    }

    @Override
    public void initGui() {
        buttonList.clear();

        int left = (width - GUI_WIDTH) / 2;
        int top = (height - GUI_HEIGHT) / 2;

        // 预设行
        int presetCount = PRESETS.length;
        int presetWidth = 34;
        int presetGap = 2;
        int presetTotal = presetCount * presetWidth + (presetCount - 1) * presetGap;
        int presetX = left + (GUI_WIDTH - presetTotal) / 2;
        for (int i = 0; i < presetCount; i++) {
            float value = PRESETS[i];
            buttonList.add(
                new GuiButton(
                    BTN_PRESET_BASE + i,
                    presetX + i * (presetWidth + presetGap),
                    top + 58,
                    presetWidth,
                    20,
                    formatPreset(value)));
        }

        // 微调 + 完成
        buttonList.add(new GuiButton(BTN_STEP_DOWN, left + 11, top + 84, 56, 20, "-0.25"));
        buttonList.add(new GuiButton(BTN_STEP_UP, left + 71, top + 84, 56, 20, "+0.25"));
        buttonList.add(new GuiButton(BTN_DONE, left + 133, top + 84, 56, 20, tr("gui.done")));

        updatePresetStates();
    }

    /** 超出上限的预设按钮置灰，而不是按了没反应 —— 让玩家看得出边界在哪。 */
    private void updatePresetStates() {
        float max = (float) Math.max(ItemFlightCharm.MIN_MULTIPLIER, Config.flightCharmMaxMultiplier);
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            if (button.id < BTN_PRESET_BASE) continue;
            int index = button.id - BTN_PRESET_BASE;
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
            EnumChatFormatting.GOLD + tr("item.futa_gtnh.flight_charm.name"),
            width / 2,
            top + 8,
            0xFFFFFF);

        float multiplier = ItemFlightCharm.getMultiplier(charm);
        drawCenteredString(
            fontRendererObj,
            tr("futa_gtnh.flight_charm.gui.multiplier") + " "
                + EnumChatFormatting.AQUA
                + String.format(Locale.ROOT, "%.2f", multiplier)
                + "x",
            width / 2,
            top + 28,
            0xFFFFFF);

        drawCenteredString(
            fontRendererObj,
            EnumChatFormatting.GRAY + StatCollector.translateToLocalFormatted(
                "futa_gtnh.flight_charm.gui.result",
                String.format(Locale.ROOT, "%.3f", multiplier * ItemFlightCharm.VANILLA_FLY_SPEED),
                String.format(Locale.ROOT, "%.3f", ItemFlightCharm.VANILLA_FLY_SPEED)),
            width / 2,
            top + 40,
            0xFFFFFF);

        drawCenteredString(
            fontRendererObj,
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocalFormatted(
                "futa_gtnh.flight_charm.gui.range",
                String.format(Locale.ROOT, "%.2f", ItemFlightCharm.MIN_MULTIPLIER),
                String.format(Locale.ROOT, "%.2f", Config.flightCharmMaxMultiplier)),
            width / 2,
            top + GUI_HEIGHT - 14,
            0xFFFFFF);

        // 补一句说明，免得玩家以为「1x 没生效」
        drawCenteredString(
            fontRendererObj,
            EnumChatFormatting.DARK_GRAY + tr("futa_gtnh.flight_charm.gui.hint"),
            width / 2,
            top + GUI_HEIGHT - 3,
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

        float current = ItemFlightCharm.getMultiplier(charm);
        float next;
        if (button.id == BTN_STEP_DOWN) {
            next = current - STEP;
        } else if (button.id == BTN_STEP_UP) {
            next = current + STEP;
        } else if (button.id >= BTN_PRESET_BASE && button.id - BTN_PRESET_BASE < PRESETS.length) {
            next = PRESETS[button.id - BTN_PRESET_BASE];
        } else {
            return;
        }

        apply(next);
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

    /**
     * 应用一个新倍率。
     *
     * <p>
     * 夹取用的是<b>配置里那个上限</b>，而且服务端收到包之后会再夹一次 ——
     * 客户端这边的夹取只是让界面别显示越界值，真正说了算的是服务端。
     */
    private void apply(float value) {
        float clamped = ItemFlightCharm.clampMultiplier(value);

        // 本地先写，界面立刻跟上
        ItemFlightCharm.setMultiplier(charm, clamped);
        // 再把权威副本交给服务端去写
        NetworkHandler.INSTANCE.sendToServer(new PacketSetFlightSpeed(clamped));

        updatePresetStates();
    }
}
