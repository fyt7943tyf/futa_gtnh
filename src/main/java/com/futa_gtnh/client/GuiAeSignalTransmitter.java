package com.futa_gtnh.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.futa_gtnh.aewireless.TileAeSignalTransmitter;
import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.inventory.ContainerAeSignalTransmitter;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketAeTransmitterConfig;

/**
 * 「AE 无线发信端」界面：一个名字输入框 + 应用按钮 + 状态区。
 *
 * <p>
 * 名字只是给人看的标签（接收端列表里显示它），链接键始终是坐标 ——
 * 随时改名都不会断开已连上的接收端。
 */
public class GuiAeSignalTransmitter extends FutaGuiContainer {

    private FutaSearchField nameField;
    private boolean nameEdited;
    private boolean initialNameReceived;
    private GuiButton applyButton;

    private final int dim, x, y, z;

    public GuiAeSignalTransmitter(ContainerAeSignalTransmitter container, EntityPlayer player) {
        super(container);
        this.dim = container.dim();
        this.x = container.x();
        this.y = container.y();
        this.z = container.z();
        xSize = 176;
        ySize = 120;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        com.futa_gtnh.network.PacketAeTransmitterSync initial = snapshot();
        String current = initial == null ? "" : initial.getName();
        initialNameReceived = initial != null;
        nameField = new FutaSearchField(fontRendererObj, guiLeft + 16, guiTop + 30, xSize - 32, 16);
        nameField.setMaxStringLength(TileAeSignalTransmitter.MAX_NAME_LENGTH);
        nameField.setFocused(true);
        nameField.setText(current, false);
        nameField.setChangeListener(text -> nameEdited = true);
        applyButton = new GuiButton(
            0,
            guiLeft + 16,
            guiTop + 56,
            80,
            20,
            StatCollector.translateToLocal("futa_gtnh.aewireless.apply"));
        buttonList.add(applyButton);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) {
            NetworkHandler.INSTANCE.sendToServer(new PacketAeTransmitterConfig(dim, x, y, z, nameField.getText()));
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        if (nameField != null && nameField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN) {
                if (applyButton.enabled) actionPerformed(applyButton);
                return;
            }
            nameField.textboxKeyTyped(typedChar, keyCode);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (nameField != null) nameField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        drawDefaultBackground();
        com.futa_gtnh.client.ui.FutaUiTheme.panel(guiLeft, guiTop, xSize, ySize);
        if (nameField != null) nameField.drawTextBox();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj
            .drawString(StatCollector.translateToLocal("futa_gtnh.aewireless.transmitter.title"), 16, 12, 0xE0E0E0);
        fontRendererObj.drawString(StatCollector.translateToLocal("futa_gtnh.aewireless.name"), 16, 24, 0xD0D0D0);
        PacketAeTransmitterSnapshotText text = snapshotText();
        fontRendererObj.drawString(text.state, 16, 84, text.color);
        String list = truncate(text.list, 144);
        if (!list.isEmpty()) {
            fontRendererObj
                .drawString(StatCollector.translateToLocal("futa_gtnh.aewireless.visible"), 16, 96, 0xD0D0D0);
            fontRendererObj.drawString(list, 16, 106, 0xD0D0D0);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        com.futa_gtnh.network.PacketAeTransmitterSync incoming = snapshot();
        if (!initialNameReceived && incoming != null && nameField != null) {
            if (!nameEdited) nameField.setText(incoming.getName(), false);
            initialNameReceived = true;
        }
        if (nameField != null) nameField.updateCursorCounter();
        if (applyButton != null) applyButton.enabled = incoming != null;
    }

    private com.futa_gtnh.network.PacketAeTransmitterSync snapshot() {
        com.futa_gtnh.network.PacketAeTransmitterSync value = ClientAeTransmitterState.snapshot();
        return value != null && value.getDim() == dim && value.getX() == x && value.getY() == y && value.getZ() == z
            ? value
            : null;
    }

    private static String truncate(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (fontRendererStaticWidth(text) <= maxWidth) return text;
        return net.minecraft.client.Minecraft.getMinecraft().fontRenderer
            .trimStringToWidth(text, Math.max(0, maxWidth - fontRendererStaticWidth("…"))) + "…";
    }

    private static int fontRendererStaticWidth(String text) {
        return net.minecraft.client.Minecraft.getMinecraft().fontRenderer.getStringWidth(text);
    }

    private static final class PacketAeTransmitterSnapshotText {

        final String state;
        final int color;
        final String list;

        PacketAeTransmitterSnapshotText(String state, int color, String list) {
            this.state = state;
            this.color = color;
            this.list = list;
        }
    }

    private PacketAeTransmitterSnapshotText snapshotText() {
        com.futa_gtnh.network.PacketAeTransmitterSync snapshot = snapshot();
        if (snapshot == null) {
            return new PacketAeTransmitterSnapshotText(
                StatCollector.translateToLocal("futa_gtnh.aewireless.state.offline"),
                0xC04040,
                "");
        }
        String state = StatCollector.translateToLocal(ClientAeTransmitterState.stateKey(snapshot));
        int color = switch (snapshot.getState()) {
            case ONLINE -> 0x40A040;
            case CONFLICT -> 0xC08020;
            case OFFLINE -> 0xC04040;
        };
        return new PacketAeTransmitterSnapshotText(state, color, snapshot.getVisibleList());
    }
}
