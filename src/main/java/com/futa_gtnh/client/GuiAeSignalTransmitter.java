package com.futa_gtnh.client;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

import com.futa_gtnh.aewireless.TileAeSignalTransmitter;
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

    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/gui/book.png");

    private GuiTextField nameField;
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
        String current = ClientAeTransmitterState.name();
        nameField = new GuiTextField(fontRendererObj, guiLeft + 16, guiTop + 30, xSize - 32, 16);
        nameField.setMaxStringLength(TileAeSignalTransmitter.MAX_NAME_LENGTH);
        nameField.setFocused(true);
        nameField.setText(current);
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
        if (nameField != null && nameField.isFocused()) {
            if (keyCode == Keyboard.KEY_RETURN) {
                actionPerformed(applyButton);
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
        mc.getTextureManager()
            .bindTexture(TEXTURE);
        // 用原版成书贴图的左侧干净区域当底板（避开右侧翻页箭头）
        drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
        if (nameField != null) nameField.drawTextBox();
    }

    @Override
    protected void drawGuiContainerForegroundLayer(int mouseX, int mouseY) {
        fontRendererObj
            .drawString(StatCollector.translateToLocal("futa_gtnh.aewireless.transmitter.title"), 16, 12, 0x404040);
        fontRendererObj.drawString(StatCollector.translateToLocal("futa_gtnh.aewireless.name"), 16, 20, 0x606060);
        PacketAeTransmitterSnapshotText text = snapshotText();
        fontRendererObj.drawString(text.state, 16, 84, text.color);
        String list = truncate(text.list, 144);
        if (!list.isEmpty()) {
            fontRendererObj
                .drawString(StatCollector.translateToLocal("futa_gtnh.aewireless.visible"), 16, 96, 0x606060);
            fontRendererObj.drawString(list, 16, 106, 0x606060);
        }
    }

    private static String truncate(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (fontRendererStaticWidth(text) <= maxWidth) return text;
        StringBuilder sb = new StringBuilder();
        while (sb.length() < text.length() && fontRendererStaticWidth(sb.toString() + "…") <= maxWidth) {
            sb.append(text.charAt(sb.length()));
        }
        return sb + "…";
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
        com.futa_gtnh.network.PacketAeTransmitterSync snapshot = ClientAeTransmitterState.snapshot();
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
