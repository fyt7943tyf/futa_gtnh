package com.futa_gtnh.client;

import java.util.Locale;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.inventory.ContainerProductionTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketProductionRequest;
import com.futa_gtnh.network.PacketProductionSnapshot;
import com.futa_gtnh.supply.SupplySource;

/** Select a production line for the physical IO pinned by the server's open container. */
public final class GuiProductionBinding extends FutaGuiScreen {

    private final ContainerProductionTerminal container;
    private NBTTagCompound data = new NBTTagCompound(), queuedMutation;
    private int left, top, panelWidth, panelHeight, rows, page, scroll, sequence, requested, applied;
    private long session, lastQuery, lastMutation, pendingAt;
    private boolean pending, queryPending, editing;
    private String status = "", pendingAction = "";
    private FutaSearchField editor;

    public GuiProductionBinding(ContainerProductionTerminal container) {
        this.container = container;
    }

    @Override
    public void initGui() {
        panelWidth = Math.max(280, Math.min(400, width - 10));
        panelHeight = Math.max(210, Math.min(292, height - 10));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        rows = Math.max(1, (panelHeight - 154) / 22);
        if (session == 0) session = ClientProductionState.begin(mc.getNetHandler());
        buttonList.clear();
        int third = (panelWidth - 20) / 3;
        button(1, 8, panelHeight - 28, third, tr("create"));
        button(2, 10 + third, panelHeight - 28, third, tr("unbind"));
        button(0, 12 + third * 2, panelHeight - 28, third, tr("close"));
        button(3, 8, panelHeight - 66, 18, "<");
        button(4, panelWidth - 26, panelHeight - 66, 18, ">");
        button(5, 8, panelHeight - 28, (panelWidth - 18) / 2, tr("save"));
        button(6, panelWidth / 2 + 1, panelHeight - 28, (panelWidth - 18) / 2, tr("cancel"));
        if (editing) openEditor();
        Keyboard.enableRepeatEvents(true);
        request(null);
        updateButtons();
    }

    private void button(int id, int x, int y, int buttonWidth, String label) {
        buttonList.add(new GuiSmallButton(id, left + x, top + y, buttonWidth, 16, label));
    }

    private int maxScroll() {
        return Math.max(
            0,
            data.getTagList("lines", 10)
                .tagCount() - rows);
    }

    @Override
    public void updateScreen() {
        if (mc.thePlayer == null || mc.thePlayer.openContainer != container) {
            mc.displayGuiScreen(null);
            return;
        }
        if (editor != null) editor.updateCursorCounter();
        PacketProductionSnapshot response = ClientProductionState.response();
        if (response != null && response.window == container.windowId
            && response.session == session
            && response.sequence == requested
            && applied != response.sequence) {
            applied = response.sequence;
            apply(response.data);
        }
        long now = System.currentTimeMillis();
        if (queuedMutation != null && now - lastMutation >= 300) {
            NBTTagCompound mutation = queuedMutation;
            queuedMutation = null;
            request(mutation);
        }
        if (pending && now - pendingAt > 5000) {
            pending = false;
            queuedMutation = null;
            status = tr("timeout");
            queryPending = true;
        }
        if (!pending && (queryPending && now - lastQuery >= 850 || now - lastQuery >= 5000)) request(null);
        updateButtons();
    }

    private void apply(NBTTagCompound snapshot) {
        data = snapshot;
        page = data.getInteger("linePage");
        scroll = Math.min(scroll, maxScroll());
        pending = false;
        if (data.hasKey("result", 10)) {
            NBTTagCompound result = data.getCompoundTag("result");
            status = result.getString("message");
            if (result.getBoolean("ok") && "create".equals(pendingAction)) {
                page = Math.max(0, data.getInteger("linePages") - 1);
                scroll = 0;
                queryPending = true;
            }
        }
    }

    private void request(NBTTagCompound mutation) {
        if (container.windowId == 0) {
            queryPending = true;
            return;
        }
        if (!ClientProductionState.terminalSupported(mc.getNetHandler())) {
            status = tr("unsupported");
            pending = false;
            queryPending = false;
            lastQuery = System.currentTimeMillis();
            return;
        }
        long now = System.currentTimeMillis();
        if (mutation == null) {
            if (pending || now - lastQuery < 850) {
                queryPending = true;
                return;
            }
            lastQuery = now;
            queryPending = false;
        } else {
            pending = true;
            pendingAt = now;
            if (now - lastMutation < 300) {
                queuedMutation = mutation;
                return;
            }
            lastMutation = now;
        }
        requested = ++sequence;
        PacketProductionRequest packet = new PacketProductionRequest();
        packet.window = container.windowId;
        packet.session = session;
        packet.sequence = requested;
        packet.linePage = page;
        packet.mutation = mutation;
        NetworkHandler.INSTANCE.sendToServer(packet);
    }

    private void mutate(String action, String lineId, String name) {
        if (!data.getBoolean("canEdit") || pending) return;
        NBTTagCompound mutation = new NBTTagCompound();
        mutation.setString("action", action);
        mutation.setString("lineId", lineId);
        mutation.setString("name", name);
        pendingAction = action;
        request(mutation);
        updateButtons();
    }

    private void updateButtons() {
        for (Object entry : buttonList) {
            GuiButton button = (GuiButton) entry;
            button.visible = editing ? button.id == 5 || button.id == 6 : button.id < 5;
            button.enabled = !pending;
            if (button.id == 0 || button.id == 6) button.enabled = true;
            if (button.id == 1 || button.id == 2 || button.id == 5) button.enabled &= data.getBoolean("canEdit");
            if (button.id == 2) button.enabled &= !data.getCompoundTag("currentBinding")
                .getString("lineId")
                .isEmpty()
                && !data.getCompoundTag("currentBinding")
                    .getBoolean("autoBound");
            if (button.id == 3) button.enabled &= page > 0;
            if (button.id == 4) button.enabled &= page + 1 < data.getInteger("linePages");
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        switch (button.id) {
            case 0:
                close();
                break;
            case 1:
                editing = true;
                editor = null;
                openEditor();
                break;
            case 2:
                mutate("unbind", "", "");
                break;
            case 3:
            case 4:
                page = Math.max(0, page + (button.id == 3 ? -1 : 1));
                scroll = 0;
                request(null);
                break;
            case 5:
                String name = editor.getText()
                    .trim();
                if (name.isEmpty()) {
                    status = tr("emptyName");
                    break;
                }
                editing = false;
                mutate("create", "", name);
                break;
            case 6:
                editing = false;
                break;
            default:
                break;
        }
        updateButtons();
    }

    private void openEditor() {
        String draft = editor == null ? "" : editor.getText();
        editor = new FutaSearchField(fontRendererObj, left + 12, top + panelHeight / 2, panelWidth - 24, 16);
        editor.setMaxStringLength(48);
        editor.setText(draft, false);
        editor.setFocused(true);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        com.futa_gtnh.client.ui.FutaUiTheme.panel(left, top, panelWidth, panelHeight);
        text(tr("title"), 8, 8, panelWidth - 16, 0xFFFFFF);
        String name = data.getString("targetName");
        if (container.target != null) name = container.target.name;
        text(name.isEmpty() ? tr("waiting") : name, 8, 28, panelWidth - 16, 0xFFFFFF);
        if (data.hasKey("bindingTarget", 10)) {
            SupplySource source = SupplySource.read(data.getCompoundTag("bindingTarget"));
            String face = source.side < 0 ? tr("whole")
                : StatCollector.translateToLocal(
                    "futa_gtnh.gui.terminal.io.dir." + ForgeDirection.getOrientation(source.side)
                        .name()
                        .toLowerCase(Locale.ROOT));
            text(tr("position", source.dim, source.x, source.y, source.z, face), 8, 40, panelWidth - 16, 0xAAAAAA);
        }
        String currentId = data.getCompoundTag("currentBinding")
            .getString("lineId");
        String currentName = data.getCompoundTag("currentBinding")
            .getString("lineName");
        text(tr("current", currentName.isEmpty() ? tr("unbound") : currentName), 8, 54, panelWidth - 16, 0xAADD99);
        text(
            tr(
                data.getBoolean("canEdit") ? data.getCompoundTag("currentBinding")
                    .getBoolean("autoBound") ? "hintAuto" : "hint" : "readOnly"),
            8,
            68,
            panelWidth - 16,
            0xAAAAAA);
        NBTTagList lines = data.getTagList("lines", 10);
        scroll = Math.min(scroll, maxScroll());
        if (lines.tagCount() == 0) text(tr("noLines"), 10, 92, panelWidth - 20, 0xCCCCCC);
        for (int row = 0; row < rows && row + scroll < lines.tagCount(); row++) {
            NBTTagCompound line = lines.getCompoundTagAt(row + scroll);
            boolean current = currentId.equals(line.getString("id"));
            int y = 84 + row * 22;
            drawRect(left + 8, top + y, left + panelWidth - 8, top + y + 20, current ? 0xFF365D42 : 0xFF343434);
            text(line.getString("name"), 12, y + 5, panelWidth - 84, 0xFFFFFF);
            if (current) text(tr("selected"), panelWidth - 64, y + 5, 52, 0xAADD99);
        }
        text(
            (page + 1) + "/" + Math.max(1, data.getInteger("linePages")),
            32,
            panelHeight - 62,
            panelWidth - 64,
            0xAAAAAA);
        text(pending ? tr("saving") : status, 8, panelHeight - 44, panelWidth - 16, 0xFFCC77);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (editing) {
            drawRect(left + 6, top + 78, left + panelWidth - 6, top + panelHeight - 48, 0xFF30343B);
            text(tr("createName"), 12, panelHeight / 2 - 17, panelWidth - 24, 0xFFFFFF);
            editor.drawTextBox();
        }
    }

    private void text(String value, int x, int y, int available, int color) {
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(value, Math.max(0, available)), left + x, top + y, color);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        if (editing) {
            editor.mouseClicked(x, y, button);
            return;
        }
        if (button != 0 || pending || x < left + 8 || x >= left + panelWidth - 8 || y < top + 84) return;
        int row = (y - top - 84) / 22;
        NBTTagList lines = data.getTagList("lines", 10);
        if (row < rows && row + scroll < lines.tagCount()) mutate(
            "bind",
            lines.getCompoundTagAt(row + scroll)
                .getString("id"),
            "");
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == 1) {
            if (editing) {
                editing = false;
                updateButtons();
            } else close();
        } else if (editing && (key == 28 || key == 156)) {
            for (Object entry : buttonList) if (((GuiButton) entry).id == 5) actionPerformed((GuiButton) entry);
        } else if (editing) editor.textboxKeyTyped(character, key);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        if (editing || pending) return;
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) scroll = Math.max(0, Math.min(maxScroll(), scroll + (wheel < 0 ? 1 : -1)));
    }

    private void close() {
        if (mc.thePlayer != null) mc.thePlayer.closeScreen();
        else mc.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        ClientProductionState.end(session);
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return StatCollector.translateToLocalFormatted("futa_gtnh.productionTerminal." + key, arguments);
    }
}
