package com.futa_gtnh.client;

import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.futa_gtnh.item.ItemSwiftStep;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketSetSwiftStep;
import com.futa_gtnh.network.PacketSetSwiftStepMagnet;

/** 迅步设置界面，按移动、生长和恢复分为三个页面。 */
public class GuiSwiftStep extends GuiScreen {

    private static final int GUI_WIDTH = 200;
    private static final int GUI_HEIGHT = 344;

    private static final int PAGE_MOVEMENT = 0;
    private static final int PAGE_GROWTH = 1;
    private static final int PAGE_RECOVERY = 2;

    private static final int BTN_FLIGHT_DOWN = 0;
    private static final int BTN_FLIGHT_UP = 1;
    private static final int FLIGHT_PRESET_BASE = 100;
    private static final int BTN_WALK_DOWN = 2;
    private static final int BTN_WALK_UP = 3;
    private static final int WALK_PRESET_BASE = 200;
    private static final int BTN_LIGHT_DOWN = 4;
    private static final int BTN_LIGHT_UP = 5;
    private static final int LIGHT_PRESET_BASE = 300;

    private static final int BTN_CROP_AURA_TOGGLE = 6;
    private static final int BTN_GROWTH_RADIUS_DOWN = 7;
    private static final int BTN_GROWTH_RADIUS_UP = 8;
    private static final int BTN_ANIMAL_AURA_TOGGLE = 9;
    private static final int BTN_GROWTH_SPEED_DOWN = 10;
    private static final int BTN_GROWTH_SPEED_UP = 11;
    private static final int BTN_DONE = 12;

    private static final int BTN_TAB_MOVEMENT = 13;
    private static final int BTN_TAB_GROWTH = 14;
    private static final int BTN_TAB_RECOVERY = 15;
    private static final int BTN_HEALTH_RECOVERY_TOGGLE = 16;
    private static final int BTN_FOOD_RECOVERY_TOGGLE = 17;
    private static final int BTN_RECOVERY_SPEED_DOWN = 18;
    private static final int BTN_RECOVERY_SPEED_UP = 19;
    private static final int BTN_GROWTH_SPEED_DOWN_10 = 20;
    private static final int BTN_GROWTH_SPEED_UP_10 = 21;
    private static final int BTN_ITEM_MAGNET_TOGGLE = 22;
    private static final int BTN_ITEM_MAGNET_RADIUS_DOWN_1 = 23;
    private static final int BTN_ITEM_MAGNET_RADIUS_UP_1 = 24;
    private static final int BTN_ITEM_MAGNET_RADIUS_DOWN_10 = 25;
    private static final int BTN_ITEM_MAGNET_RADIUS_UP_10 = 26;
    private static final int BTN_ITEM_MAGNET_RADIUS_DOWN_100 = 27;
    private static final int BTN_ITEM_MAGNET_RADIUS_UP_100 = 28;

    private static final float STEP = 0.25F;
    private static final float[] PRESETS = { 1.0F, 2.0F, 3.0F, 5.0F, 10.0F, 16.0F };
    private static final int[] LIGHT_PRESETS = { 0, 4, 8, ItemSwiftStep.TORCH_LIGHT, ItemSwiftStep.MAX_LIGHT };

    private final ItemStack charm;
    private int page = PAGE_MOVEMENT;

    public GuiSwiftStep(ItemStack charm) {
        this.charm = charm;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        int left = (width - GUI_WIDTH) / 2;
        int top = (height - GUI_HEIGHT) / 2;

        addTabs(left, top);
        if (page == PAGE_MOVEMENT) {
            addMovementControls(left, top);
        } else if (page == PAGE_GROWTH) {
            addGrowthControls(left, top);
        } else {
            addRecoveryControls(left, top);
        }
        buttonList.add(new GuiSmallButton(BTN_DONE, left + 52, top + 282, 96, 20, tr("gui.done")));

        updatePresetStates();
        updateGrowthAuraToggleButtons();
        updateGrowthAuraRadiusButtons();
        updateGrowthAuraSpeedButtons();
        updateRecoveryToggleButtons();
        updateRecoverySpeedButtons();
        updateItemMagnetButtons();
    }

    private void addTabs(int left, int top) {
        buttonList.add(
            new GuiSmallButton(
                BTN_TAB_MOVEMENT,
                left + 8,
                top + 26,
                58,
                18,
                tr("futa_gtnh.swift_step.gui.tab.movement")));
        buttonList.add(
            new GuiSmallButton(BTN_TAB_GROWTH, left + 71, top + 26, 58, 18, tr("futa_gtnh.swift_step.gui.tab.growth")));
        buttonList.add(
            new GuiSmallButton(
                BTN_TAB_RECOVERY,
                left + 134,
                top + 26,
                58,
                18,
                tr("futa_gtnh.swift_step.gui.tab.recovery")));
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            if (button.id == BTN_TAB_MOVEMENT + page) button.enabled = false;
        }
    }

    private void addMovementControls(int left, int top) {
        addPresetRow(left, top + 78, FLIGHT_PRESET_BASE);
        buttonList.add(new GuiSmallButton(BTN_FLIGHT_DOWN, left + 8, top + 99, 90, 18, "-0.25"));
        buttonList.add(new GuiSmallButton(BTN_FLIGHT_UP, left + 102, top + 99, 90, 18, "+0.25"));

        addPresetRow(left, top + 153, WALK_PRESET_BASE);
        buttonList.add(new GuiSmallButton(BTN_WALK_DOWN, left + 8, top + 174, 90, 18, "-0.25"));
        buttonList.add(new GuiSmallButton(BTN_WALK_UP, left + 102, top + 174, 90, 18, "+0.25"));

        addLightPresetRow(left, top + 228);
        buttonList.add(new GuiSmallButton(BTN_LIGHT_DOWN, left + 8, top + 249, 90, 18, "-1"));
        buttonList.add(new GuiSmallButton(BTN_LIGHT_UP, left + 102, top + 249, 90, 18, "+1"));
    }

    private void addGrowthControls(int left, int top) {
        buttonList.add(new GuiSmallButton(BTN_CROP_AURA_TOGGLE, left + 8, top + 104, 90, 18, cropAuraToggleText()));
        buttonList
            .add(new GuiSmallButton(BTN_ANIMAL_AURA_TOGGLE, left + 102, top + 104, 90, 18, animalAuraToggleText()));
        buttonList.add(
            new GuiSmallButton(
                BTN_GROWTH_RADIUS_DOWN,
                left + 8,
                top + 128,
                90,
                18,
                tr("futa_gtnh.swift_step.gui.growth_aura.radius_down")));
        buttonList.add(
            new GuiSmallButton(
                BTN_GROWTH_RADIUS_UP,
                left + 102,
                top + 128,
                90,
                18,
                tr("futa_gtnh.swift_step.gui.growth_aura.radius_up")));
        buttonList.add(new GuiSmallButton(BTN_GROWTH_SPEED_DOWN_10, left + 8, top + 152, 42, 18, "-10"));
        buttonList.add(new GuiSmallButton(BTN_GROWTH_SPEED_DOWN, left + 54, top + 152, 42, 18, "-1"));
        buttonList.add(new GuiSmallButton(BTN_GROWTH_SPEED_UP, left + 104, top + 152, 42, 18, "+1"));
        buttonList.add(new GuiSmallButton(BTN_GROWTH_SPEED_UP_10, left + 150, top + 152, 42, 18, "+10"));

        buttonList
            .add(new GuiSmallButton(BTN_ITEM_MAGNET_TOGGLE, left + 8, top + 211, 184, 18, itemMagnetToggleText()));
        buttonList.add(new GuiSmallButton(BTN_ITEM_MAGNET_RADIUS_DOWN_100, left + 8, top + 235, 42, 18, "-100"));
        buttonList.add(new GuiSmallButton(BTN_ITEM_MAGNET_RADIUS_DOWN_10, left + 54, top + 235, 42, 18, "-10"));
        buttonList.add(new GuiSmallButton(BTN_ITEM_MAGNET_RADIUS_UP_10, left + 104, top + 235, 42, 18, "+10"));
        buttonList.add(new GuiSmallButton(BTN_ITEM_MAGNET_RADIUS_UP_100, left + 150, top + 235, 42, 18, "+100"));
        buttonList.add(new GuiSmallButton(BTN_ITEM_MAGNET_RADIUS_DOWN_1, left + 8, top + 259, 90, 18, "-1"));
        buttonList.add(new GuiSmallButton(BTN_ITEM_MAGNET_RADIUS_UP_1, left + 102, top + 259, 90, 18, "+1"));
    }

    private void addRecoveryControls(int left, int top) {
        buttonList.add(
            new GuiSmallButton(BTN_HEALTH_RECOVERY_TOGGLE, left + 8, top + 94, 184, 18, healthRecoveryToggleText()));
        buttonList
            .add(new GuiSmallButton(BTN_FOOD_RECOVERY_TOGGLE, left + 8, top + 160, 184, 18, foodRecoveryToggleText()));
        buttonList.add(
            new GuiSmallButton(
                BTN_RECOVERY_SPEED_DOWN,
                left + 8,
                top + 232,
                90,
                18,
                tr("futa_gtnh.swift_step.gui.recovery.speed_down")));
        buttonList.add(
            new GuiSmallButton(
                BTN_RECOVERY_SPEED_UP,
                left + 102,
                top + 232,
                90,
                18,
                tr("futa_gtnh.swift_step.gui.recovery.speed_up")));
    }

    private void addPresetRow(int left, int y, int baseId) {
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

    private void updatePresetStates() {
        float max = ItemSwiftStep.maxMultiplier();
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            boolean flight = button.id >= FLIGHT_PRESET_BASE && button.id < FLIGHT_PRESET_BASE + PRESETS.length;
            boolean walk = button.id >= WALK_PRESET_BASE && button.id < WALK_PRESET_BASE + PRESETS.length;
            if (flight || walk) {
                int index = button.id % 100;
                button.enabled = PRESETS[index] <= max;
            }
        }
    }

    private static String formatPreset(float value) {
        return value == Math.round(value) ? ((int) value) + "x" : String.format(Locale.ROOT, "%.1fx", value);
    }

    private static String fixed(float value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static String tr(String key) {
        return StatCollector.translateToLocal(key);
    }

    private static String tr(String key, Object... values) {
        return StatCollector.translateToLocalFormatted(key, values);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        float scale = getGuiScale();
        int logicalMouseX = unscaleMouse(mouseX, width / 2.0F, scale);
        int logicalMouseY = unscaleMouse(mouseY, height / 2.0F, scale);

        GL11.glPushMatrix();
        GL11.glTranslatef(width / 2.0F, height / 2.0F, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        GL11.glTranslatef(-width / 2.0F, -height / 2.0F, 0.0F);

        int left = (width - GUI_WIDTH) / 2;
        int top = (height - GUI_HEIGHT) / 2;
        drawCenteredFittedString(
            EnumChatFormatting.GOLD + tr("item.futa_gtnh.swift_step.name"),
            width / 2,
            top + 8,
            0xFFFFFF);
        drawRect(left + 4, top + 48, left + GUI_WIDTH - 4, top + 49, 0xFF808080);

        if (page == PAGE_MOVEMENT) {
            drawMovementPage(top, left);
        } else if (page == PAGE_GROWTH) {
            drawGrowthPage(top);
        } else {
            drawRecoveryPage(top);
        }

        super.drawScreen(logicalMouseX, logicalMouseY, partialTicks);
        GL11.glPopMatrix();
    }

    private void drawMovementPage(int top, int left) {
        drawGroup(
            top + 53,
            "futa_gtnh.swift_step.gui.flight",
            ItemSwiftStep.getFlightMultiplier(charm),
            ItemSwiftStep.VANILLA_FLY_SPEED);
        drawRect(left + 8, top + 123, left + GUI_WIDTH - 8, top + 124, 0xFF808080);
        drawGroup(
            top + 128,
            "futa_gtnh.swift_step.gui.walk",
            ItemSwiftStep.getWalkMultiplier(charm),
            ItemSwiftStep.VANILLA_WALK_SPEED);
        drawRect(left + 8, top + 198, left + GUI_WIDTH - 8, top + 199, 0xFF808080);
        drawLightGroup(top + 203);
        drawRect(left + 8, top + 273, left + GUI_WIDTH - 8, top + 274, 0xFF808080);

        if (SwiftStepClientHandler.isClampedForServer()) {
            drawCenteredFittedString(
                EnumChatFormatting.RED
                    + tr("futa_gtnh.swift_step.gui.server_clamp", fixed(ItemSwiftStep.serverSafeFlyMultiplier(), 0)),
                width / 2,
                top + 306,
                0xFFFFFF);
        }
        drawCenteredFittedString(
            EnumChatFormatting.DARK_GRAY
                + tr("futa_gtnh.swift_step.gui.range", fixed(ItemSwiftStep.maxMultiplier(), 2)),
            width / 2,
            top + 326,
            0xFFFFFF);
    }

    private void drawGrowthPage(int top) {
        boolean enabled = ItemSwiftStep.isGrowthAuraEnabled(charm) || ItemSwiftStep.isAnimalAuraEnabled(charm);
        drawCenteredFittedString(
            EnumChatFormatting.YELLOW + tr("futa_gtnh.swift_step.gui.growth_aura"),
            width / 2,
            top + 66,
            0xFFFFFF);
        drawCenteredFittedString(
            (enabled ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY) + tr(
                "futa_gtnh.swift_step.gui.growth_aura.status",
                ItemSwiftStep.getGrowthAuraRadius(charm),
                ItemSwiftStep.getGrowthAuraSpeed(charm),
                ItemSwiftStep.getGrowthAuraSpeed(charm)),
            width / 2,
            top + 80,
            0xFFFFFF);
        boolean magnetEnabled = ItemSwiftStep.isItemMagnetEnabled(charm);
        drawCenteredFittedString(
            EnumChatFormatting.YELLOW + tr("futa_gtnh.swift_step.gui.item_magnet"),
            width / 2,
            top + 183,
            0xFFFFFF);
        drawCenteredFittedString(
            (magnetEnabled ? EnumChatFormatting.GREEN : EnumChatFormatting.GRAY)
                + tr("futa_gtnh.swift_step.gui.item_magnet.status", ItemSwiftStep.getItemMagnetRadius(charm)),
            width / 2,
            top + 197,
            0xFFFFFF);
    }

    private void drawRecoveryPage(int top) {
        int interval = ItemSwiftStep.getRecoveryIntervalTicks(charm);
        float seconds = interval / 20.0F;

        drawCenteredFittedString(
            EnumChatFormatting.YELLOW + tr("futa_gtnh.swift_step.gui.recovery.health"),
            width / 2,
            top + 62,
            0xFFFFFF);
        drawCenteredFittedString(
            EnumChatFormatting.GRAY + tr("futa_gtnh.swift_step.gui.recovery.health_status", fixed(seconds, 1)),
            width / 2,
            top + 77,
            0xFFFFFF);
        drawCenteredFittedString(
            EnumChatFormatting.YELLOW + tr("futa_gtnh.swift_step.gui.recovery.food"),
            width / 2,
            top + 128,
            0xFFFFFF);
        drawCenteredFittedString(
            EnumChatFormatting.GRAY + tr("futa_gtnh.swift_step.gui.recovery.food_status", fixed(seconds, 1)),
            width / 2,
            top + 143,
            0xFFFFFF);
        drawCenteredFittedString(
            EnumChatFormatting.YELLOW + tr("futa_gtnh.swift_step.gui.recovery.speed"),
            width / 2,
            top + 193,
            0xFFFFFF);
        drawCenteredFittedString(
            EnumChatFormatting.GRAY
                + tr("futa_gtnh.swift_step.gui.recovery.speed_status", ItemSwiftStep.getRecoverySpeed(charm)),
            width / 2,
            top + 208,
            0xFFFFFF);
    }

    private void drawGroup(int labelY, String titleKey, float multiplier, float vanillaBase) {
        drawCenteredFittedString(EnumChatFormatting.YELLOW + tr(titleKey), width / 2, labelY, 0xFFFFFF);
        drawCenteredFittedString(
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

    private void drawLightGroup(int labelY) {
        drawCenteredFittedString(
            EnumChatFormatting.YELLOW + tr("futa_gtnh.swift_step.gui.light"),
            width / 2,
            labelY,
            0xFFFFFF);
        drawCenteredFittedString(
            EnumChatFormatting.AQUA + ItemSwiftStep.describeLightLocalized(
                charm) + EnumChatFormatting.GRAY + "  (" + tr("futa_gtnh.swift_step.gui.light.note") + ")",
            width / 2,
            labelY + 12,
            0xFFFFFF);
    }

    private void drawCenteredFittedString(String text, int centerX, int y, int color) {
        int textWidth = fontRendererObj.getStringWidth(text);
        float textScale = textWidth <= GUI_WIDTH - 16 ? 1.0F : (GUI_WIDTH - 16.0F) / textWidth;
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX, y, 0.0F);
        GL11.glScalef(textScale, textScale, 1.0F);
        GL11.glTranslatef(-centerX, -y, 0.0F);
        drawCenteredString(fontRendererObj, text, centerX, y, color);
        GL11.glPopMatrix();
    }

    private String cropAuraToggleText() {
        return tr(
            ItemSwiftStep.isGrowthAuraEnabled(charm) ? "futa_gtnh.swift_step.gui.growth_aura.crop_on"
                : "futa_gtnh.swift_step.gui.growth_aura.crop_off");
    }

    private String animalAuraToggleText() {
        return tr(
            ItemSwiftStep.isAnimalAuraEnabled(charm) ? "futa_gtnh.swift_step.gui.growth_aura.animal_on"
                : "futa_gtnh.swift_step.gui.growth_aura.animal_off");
    }

    private String itemMagnetToggleText() {
        return tr(
            ItemSwiftStep.isItemMagnetEnabled(charm) ? "futa_gtnh.swift_step.gui.item_magnet.on"
                : "futa_gtnh.swift_step.gui.item_magnet.off");
    }

    private String healthRecoveryToggleText() {
        return tr(
            "futa_gtnh.swift_step.gui.recovery.health_"
                + (ItemSwiftStep.isHealthRecoveryEnabled(charm) ? "on" : "off"));
    }

    private String foodRecoveryToggleText() {
        return tr(
            "futa_gtnh.swift_step.gui.recovery.food_" + (ItemSwiftStep.isFoodRecoveryEnabled(charm) ? "on" : "off"));
    }

    private void updateGrowthAuraToggleButtons() {
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            if (button.id == BTN_CROP_AURA_TOGGLE) button.displayString = cropAuraToggleText();
            if (button.id == BTN_ANIMAL_AURA_TOGGLE) button.displayString = animalAuraToggleText();
        }
    }

    private void updateGrowthAuraRadiusButtons() {
        int radius = ItemSwiftStep.getGrowthAuraRadius(charm);
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            if (button.id == BTN_GROWTH_RADIUS_DOWN) button.enabled = radius > ItemSwiftStep.MIN_GROWTH_AURA_RADIUS;
            if (button.id == BTN_GROWTH_RADIUS_UP) button.enabled = radius < ItemSwiftStep.MAX_GROWTH_AURA_RADIUS;
        }
    }

    private void updateGrowthAuraSpeedButtons() {
        int speed = ItemSwiftStep.getGrowthAuraSpeed(charm);
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            if (button.id == BTN_GROWTH_SPEED_DOWN || button.id == BTN_GROWTH_SPEED_DOWN_10) {
                button.enabled = speed > ItemSwiftStep.MIN_GROWTH_AURA_SPEED;
            }
            if (button.id == BTN_GROWTH_SPEED_UP || button.id == BTN_GROWTH_SPEED_UP_10) {
                button.enabled = speed < ItemSwiftStep.MAX_GROWTH_AURA_SPEED;
            }
        }
    }

    private void updateRecoveryToggleButtons() {
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            if (button.id == BTN_HEALTH_RECOVERY_TOGGLE) button.displayString = healthRecoveryToggleText();
            if (button.id == BTN_FOOD_RECOVERY_TOGGLE) button.displayString = foodRecoveryToggleText();
        }
    }

    private void updateRecoverySpeedButtons() {
        int speed = ItemSwiftStep.getRecoverySpeed(charm);
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            if (button.id == BTN_RECOVERY_SPEED_DOWN) button.enabled = speed > ItemSwiftStep.MIN_RECOVERY_SPEED;
            if (button.id == BTN_RECOVERY_SPEED_UP) button.enabled = speed < ItemSwiftStep.MAX_RECOVERY_SPEED;
        }
    }

    private void updateItemMagnetButtons() {
        int radius = ItemSwiftStep.getItemMagnetRadius(charm);
        for (Object object : buttonList) {
            if (!(object instanceof GuiButton)) continue;
            GuiButton button = (GuiButton) object;
            if (button.id == BTN_ITEM_MAGNET_TOGGLE) button.displayString = itemMagnetToggleText();
            if (button.id == BTN_ITEM_MAGNET_RADIUS_DOWN_1) {
                button.enabled = radius > ItemSwiftStep.MIN_ITEM_MAGNET_RADIUS;
            }
            if (button.id == BTN_ITEM_MAGNET_RADIUS_DOWN_10) {
                button.enabled = radius - 10 >= ItemSwiftStep.MIN_ITEM_MAGNET_RADIUS;
            }
            if (button.id == BTN_ITEM_MAGNET_RADIUS_DOWN_100) {
                button.enabled = radius - 100 >= ItemSwiftStep.MIN_ITEM_MAGNET_RADIUS;
            }
            if (button.id == BTN_ITEM_MAGNET_RADIUS_UP_1) {
                button.enabled = radius < ItemSwiftStep.MAX_ITEM_MAGNET_RADIUS;
            }
            if (button.id == BTN_ITEM_MAGNET_RADIUS_UP_10) {
                button.enabled = radius + 10 <= ItemSwiftStep.MAX_ITEM_MAGNET_RADIUS;
            }
            if (button.id == BTN_ITEM_MAGNET_RADIUS_UP_100) {
                button.enabled = radius + 100 <= ItemSwiftStep.MAX_ITEM_MAGNET_RADIUS;
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_DONE) {
            mc.displayGuiScreen(null);
            return;
        }
        if (button.id >= BTN_TAB_MOVEMENT && button.id <= BTN_TAB_RECOVERY) {
            page = button.id - BTN_TAB_MOVEMENT;
            initGui();
            return;
        }

        if (button.id == BTN_FLIGHT_DOWN) applyFlight(ItemSwiftStep.getFlightMultiplier(charm) - STEP);
        else if (button.id == BTN_FLIGHT_UP) applyFlight(ItemSwiftStep.getFlightMultiplier(charm) + STEP);
        else if (button.id == BTN_WALK_DOWN) applyWalk(ItemSwiftStep.getWalkMultiplier(charm) - STEP);
        else if (button.id == BTN_WALK_UP) applyWalk(ItemSwiftStep.getWalkMultiplier(charm) + STEP);
        else if (button.id == BTN_LIGHT_DOWN) applyLight(ItemSwiftStep.getLightLevel(charm) - 1);
        else if (button.id == BTN_LIGHT_UP) applyLight(ItemSwiftStep.getLightLevel(charm) + 1);
        else if (button.id == BTN_CROP_AURA_TOGGLE) {
            applyGrowthAuraEnabled(!ItemSwiftStep.isGrowthAuraEnabled(charm));
            updateGrowthAuraToggleButtons();
        } else if (button.id == BTN_ANIMAL_AURA_TOGGLE) {
            applyAnimalAuraEnabled(!ItemSwiftStep.isAnimalAuraEnabled(charm));
            updateGrowthAuraToggleButtons();
        } else if (button.id == BTN_GROWTH_RADIUS_DOWN)
            applyGrowthAuraRadius(ItemSwiftStep.getGrowthAuraRadius(charm) - 1);
        else if (button.id == BTN_GROWTH_RADIUS_UP) applyGrowthAuraRadius(ItemSwiftStep.getGrowthAuraRadius(charm) + 1);
        else if (button.id == BTN_GROWTH_SPEED_DOWN) applyGrowthAuraSpeed(ItemSwiftStep.getGrowthAuraSpeed(charm) - 1);
        else if (button.id == BTN_GROWTH_SPEED_UP) applyGrowthAuraSpeed(ItemSwiftStep.getGrowthAuraSpeed(charm) + 1);
        else if (button.id == BTN_GROWTH_SPEED_DOWN_10)
            applyGrowthAuraSpeed(ItemSwiftStep.getGrowthAuraSpeed(charm) - 10);
        else if (button.id == BTN_GROWTH_SPEED_UP_10)
            applyGrowthAuraSpeed(ItemSwiftStep.getGrowthAuraSpeed(charm) + 10);
        else if (button.id == BTN_ITEM_MAGNET_TOGGLE) {
            applyItemMagnetEnabled(!ItemSwiftStep.isItemMagnetEnabled(charm));
            updateItemMagnetButtons();
        } else if (button.id == BTN_ITEM_MAGNET_RADIUS_DOWN_1)
            applyItemMagnetRadius(ItemSwiftStep.getItemMagnetRadius(charm) - 1);
        else if (button.id == BTN_ITEM_MAGNET_RADIUS_UP_1)
            applyItemMagnetRadius(ItemSwiftStep.getItemMagnetRadius(charm) + 1);
        else if (button.id == BTN_ITEM_MAGNET_RADIUS_DOWN_10)
            applyItemMagnetRadius(ItemSwiftStep.getItemMagnetRadius(charm) - 10);
        else if (button.id == BTN_ITEM_MAGNET_RADIUS_UP_10)
            applyItemMagnetRadius(ItemSwiftStep.getItemMagnetRadius(charm) + 10);
        else if (button.id == BTN_ITEM_MAGNET_RADIUS_DOWN_100)
            applyItemMagnetRadius(ItemSwiftStep.getItemMagnetRadius(charm) - 100);
        else if (button.id == BTN_ITEM_MAGNET_RADIUS_UP_100)
            applyItemMagnetRadius(ItemSwiftStep.getItemMagnetRadius(charm) + 100);
        else if (button.id == BTN_HEALTH_RECOVERY_TOGGLE) {
            applyHealthRecoveryEnabled(!ItemSwiftStep.isHealthRecoveryEnabled(charm));
            updateRecoveryToggleButtons();
        } else if (button.id == BTN_FOOD_RECOVERY_TOGGLE) {
            applyFoodRecoveryEnabled(!ItemSwiftStep.isFoodRecoveryEnabled(charm));
            updateRecoveryToggleButtons();
        } else if (button.id == BTN_RECOVERY_SPEED_DOWN) applyRecoverySpeed(ItemSwiftStep.getRecoverySpeed(charm) - 1);
        else if (button.id == BTN_RECOVERY_SPEED_UP) applyRecoverySpeed(ItemSwiftStep.getRecoverySpeed(charm) + 1);
        else if (button.id >= FLIGHT_PRESET_BASE && button.id < FLIGHT_PRESET_BASE + PRESETS.length)
            applyFlight(PRESETS[button.id - FLIGHT_PRESET_BASE]);
        else if (button.id >= WALK_PRESET_BASE && button.id < WALK_PRESET_BASE + PRESETS.length)
            applyWalk(PRESETS[button.id - WALK_PRESET_BASE]);
        else if (button.id >= LIGHT_PRESET_BASE && button.id < LIGHT_PRESET_BASE + LIGHT_PRESETS.length)
            applyLight(LIGHT_PRESETS[button.id - LIGHT_PRESET_BASE]);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        float scale = getGuiScale();
        super.mouseClicked(
            unscaleMouse(mouseX, width / 2.0F, scale),
            unscaleMouse(mouseY, height / 2.0F, scale),
            mouseButton);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_RETURN) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private float getGuiScale() {
        float availableWidth = Math.max(1.0F, width - 8.0F);
        float availableHeight = Math.max(1.0F, height - 8.0F);
        return Math.min(1.0F, Math.min(availableWidth / GUI_WIDTH, availableHeight / GUI_HEIGHT));
    }

    private static int unscaleMouse(int mouse, float center, float scale) {
        return Math.round(center + (mouse - center) / scale);
    }

    private void applyFlight(float value) {
        float clamped = ItemSwiftStep.clampMultiplier(value);
        ItemSwiftStep.setFlightMultiplier(charm, clamped);
        NetworkHandler.INSTANCE.sendToServer(new PacketSetSwiftStep(clamped, PacketSetSwiftStep.UNCHANGED));
        updatePresetStates();
    }

    private void applyWalk(float value) {
        float clamped = ItemSwiftStep.clampMultiplier(value);
        ItemSwiftStep.setWalkMultiplier(charm, clamped);
        NetworkHandler.INSTANCE.sendToServer(new PacketSetSwiftStep(PacketSetSwiftStep.UNCHANGED, clamped));
        updatePresetStates();
    }

    private void applyLight(int value) {
        int clamped = ItemSwiftStep.clampLight(value);
        ItemSwiftStep.setLightLevel(charm, clamped);
        NetworkHandler.INSTANCE
            .sendToServer(new PacketSetSwiftStep(PacketSetSwiftStep.UNCHANGED, PacketSetSwiftStep.UNCHANGED, clamped));
    }

    private void applyGrowthAuraEnabled(boolean enabled) {
        ItemSwiftStep.setGrowthAuraEnabled(charm, enabled);
        NetworkHandler.INSTANCE.sendToServer(
            new PacketSetSwiftStep(
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.LIGHT_UNCHANGED,
                enabled ? 1 : 0,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED));
    }

    private void applyGrowthAuraRadius(int radius) {
        int clamped = ItemSwiftStep.clampGrowthAuraRadius(radius);
        ItemSwiftStep.setGrowthAuraRadius(charm, clamped);
        NetworkHandler.INSTANCE.sendToServer(
            new PacketSetSwiftStep(
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.LIGHT_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                clamped));
        updateGrowthAuraRadiusButtons();
    }

    private void applyGrowthAuraSpeed(int speed) {
        int clamped = ItemSwiftStep.clampGrowthAuraSpeed(speed);
        ItemSwiftStep.setGrowthAuraSpeed(charm, clamped);
        NetworkHandler.INSTANCE.sendToServer(
            new PacketSetSwiftStep(
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.LIGHT_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                clamped));
        updateGrowthAuraSpeedButtons();
    }

    private void applyAnimalAuraEnabled(boolean enabled) {
        ItemSwiftStep.setAnimalAuraEnabled(charm, enabled);
        NetworkHandler.INSTANCE.sendToServer(
            new PacketSetSwiftStep(
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.LIGHT_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                enabled ? 1 : 0));
    }

    private void applyHealthRecoveryEnabled(boolean enabled) {
        ItemSwiftStep.setHealthRecoveryEnabled(charm, enabled);
        NetworkHandler.INSTANCE.sendToServer(
            new PacketSetSwiftStep(
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.LIGHT_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                enabled ? 1 : 0,
                PacketSetSwiftStep.RECOVERY_UNCHANGED,
                PacketSetSwiftStep.RECOVERY_UNCHANGED));
    }

    private void applyFoodRecoveryEnabled(boolean enabled) {
        ItemSwiftStep.setFoodRecoveryEnabled(charm, enabled);
        NetworkHandler.INSTANCE.sendToServer(
            new PacketSetSwiftStep(
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.LIGHT_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.RECOVERY_UNCHANGED,
                enabled ? 1 : 0,
                PacketSetSwiftStep.RECOVERY_UNCHANGED));
    }

    private void applyRecoverySpeed(int speed) {
        int clamped = ItemSwiftStep.clampRecoverySpeed(speed);
        ItemSwiftStep.setRecoverySpeed(charm, clamped);
        NetworkHandler.INSTANCE.sendToServer(
            new PacketSetSwiftStep(
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.UNCHANGED,
                PacketSetSwiftStep.LIGHT_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.GROWTH_AURA_UNCHANGED,
                PacketSetSwiftStep.RECOVERY_UNCHANGED,
                PacketSetSwiftStep.RECOVERY_UNCHANGED,
                clamped));
        updateRecoverySpeedButtons();
    }

    private void applyItemMagnetEnabled(boolean enabled) {
        ItemSwiftStep.setItemMagnetEnabled(charm, enabled);
        sendItemMagnetSettings();
    }

    private void applyItemMagnetRadius(int radius) {
        ItemSwiftStep.setItemMagnetRadius(charm, radius);
        sendItemMagnetSettings();
        updateItemMagnetButtons();
    }

    private void sendItemMagnetSettings() {
        NetworkHandler.INSTANCE.sendToServer(
            new PacketSetSwiftStepMagnet(
                ItemSwiftStep.isItemMagnetEnabled(charm),
                ItemSwiftStep.getItemMagnetRadius(charm)));
    }
}
