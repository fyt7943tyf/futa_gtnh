package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.inventory.Container;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketProductionRequest;
import com.futa_gtnh.network.PacketProductionSnapshot;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.supply.SupplyTracker;

/** Production grouping and actual device transfer rates; keeps the original server container open. */
public final class GuiProductionLines extends FutaGuiScreen {

    private final GuiSharedTerminal parent;
    private final Container container;
    private NBTTagCompound data = new NBTTagCompound();
    private String lineId = "", lineName = "", sourceId = "", status = "";
    private String pendingAction = "", pendingName = "";
    private int left, top, panelWidth, panelHeight, listWidth;
    private int linePage, sourcePage, resourcePage, lineScroll, sourceScroll, resourceScroll;
    private int minutes = 10, sequence, requested, applied, editMode;
    private long session, lastRequest, mutationAt;
    private boolean allSources = true, resources, queryPending, mutationPending;
    private FutaSearchField editor;

    public GuiProductionLines(GuiSharedTerminal parent, Container container) {
        this.parent = parent;
        this.container = container;
    }

    public static void open(GuiSharedTerminal parent, ContainerSharedTerminal container) {
        container.withClientPageSwitch(
            () -> Minecraft.getMinecraft()
                .displayGuiScreen(new GuiProductionLines(parent, container)));
    }

    public static void open(ContainerSharedTerminal container) {
        open(null, container);
    }

    @Override
    public void initGui() {
        panelWidth = Math.max(300, Math.min(520, width - 10));
        panelHeight = Math.max(210, Math.min(310, height - 10));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listWidth = Math.min(150, Math.max(96, panelWidth / 3));
        if (session == 0) session = ClientProductionState.begin(mc.getNetHandler());
        buttonList.clear();
        button(0, 8, 27, listWidth - 16, tr("all"));
        int third = (listWidth - 20) / 3;
        button(1, 8, panelHeight - 29, third, tr("create"));
        button(2, 10 + third, panelHeight - 29, third, tr("rename"));
        button(3, 12 + third * 2, panelHeight - 29, third, tr("delete"));
        button(4, 8, panelHeight - 50, 18, "<");
        button(5, listWidth - 26, panelHeight - 50, 18, ">");
        int x = listWidth + 6, available = panelWidth - x - 8;
        button(6, x, 27, 52, tr("devices"));
        button(7, x + 54, 27, 52, tr("resources"));
        button(8, panelWidth - 46, 27, 38, minutes + "m");
        int quarter = (available - 6) / 4;
        button(9, x, panelHeight - 29, quarter, tr("allIo"));
        button(10, x + quarter + 2, panelHeight - 29, quarter, tr("bind"));
        button(11, x + quarter * 2 + 4, panelHeight - 29, quarter, tr("unbind"));
        button(12, x + quarter * 3 + 6, panelHeight - 29, quarter, tr("back"));
        button(13, x, panelHeight - 50, 18, "<");
        button(14, panelWidth - 26, panelHeight - 50, 18, ">");
        Keyboard.enableRepeatEvents(true);
        if (editMode != 0) openEditor(editMode);
        query();
        updateButtons();
    }

    private void button(int id, int x, int y, int size, String label) {
        buttonList.add(new GuiSmallButton(id, left + x, top + y, size, 16, label));
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
            && response.sequence != applied) {
            applied = response.sequence;
            data = response.data;
            linePage = data.getInteger("linePage");
            sourcePage = data.getInteger("sourcePage");
            resourcePage = data.getInteger("resourcePage");
            mutationPending = false;
            if (data.hasKey("result", 10)) {
                NBTTagCompound result = data.getCompoundTag("result");
                status = result.getString("message");
                if (result.getBoolean("ok")) {
                    String next = result.getString("lineId");
                    if (!next.isEmpty()) lineId = next;
                    if ("create".equals(pendingAction)) {
                        lineName = pendingName;
                        linePage = Math.max(0, data.getInteger("linePages") - 1);
                        allSources = false;
                        sourcePage = resourcePage = sourceScroll = resourceScroll = 0;
                    } else if ("rename".equals(pendingAction)) lineName = pendingName;
                    else if ("delete".equals(pendingAction) || !data.getBoolean("lineFound")) {
                        lineId = "";
                        lineName = "";
                        allSources = true;
                    }
                    sourceId = "";
                    queryPending = true;
                }
            }
            NBTTagList lines = data.getTagList("lines", 10);
            for (int i = 0; i < lines.tagCount(); i++) {
                NBTTagCompound line = lines.getCompoundTagAt(i);
                if (lineId.equals(line.getString("id"))) lineName = line.getString("name");
            }
        }
        long now = System.currentTimeMillis();
        if (mutationPending && now - mutationAt > 7000) {
            mutationPending = false;
            status = tr("timeout");
            queryPending = true;
        }
        if (!mutationPending && editMode == 0
            && (queryPending && now - lastRequest >= 850 || now - lastRequest >= 5000)) query();
        updateButtons();
    }

    private void updateButtons() {
        for (Object value : buttonList) {
            GuiButton button = (GuiButton) value;
            button.enabled = editMode == 0 && !mutationPending;
            switch (button.id) {
                case 1:
                    button.enabled &= data.getBoolean("canEdit");
                    break;
                case 2:
                case 3:
                    button.enabled &= data.getBoolean("canEdit") && !lineId.isEmpty();
                    break;
                case 4:
                    button.enabled &= linePage > 0;
                    break;
                case 5:
                    button.enabled &= linePage + 1 < data.getInteger("linePages");
                    break;
                case 6:
                    button.enabled &= resources;
                    break;
                case 7:
                    button.enabled &= !resources;
                    break;
                case 8:
                    button.displayString = minutes + "m";
                    break;
                case 9:
                    button.displayString = tr(allSources ? "lineIo" : "allIo");
                    button.enabled &= !lineId.isEmpty();
                    break;
                case 10:
                    button.enabled &= data.getBoolean("canEdit") && !lineId.isEmpty() && !sourceId.isEmpty();
                    break;
                case 11:
                    button.enabled &= data.getBoolean("canEdit") && !sourceId.isEmpty();
                    break;
                case 13:
                    button.enabled &= (resources ? resourcePage : sourcePage) > 0;
                    break;
                case 14:
                    button.enabled &= (resources ? resourcePage : sourcePage) + 1
                        < data.getInteger(resources ? "resourcePages" : "sourcePages");
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        switch (button.id) {
            case 0:
                lineId = "";
                lineName = "";
                sourceId = "";
                allSources = true;
                sourcePage = resourcePage = sourceScroll = resourceScroll = 0;
                query();
                break;
            case 1:
            case 2:
            case 3:
                openEditor(button.id);
                break;
            case 4:
            case 5:
                linePage = Math.max(0, linePage + (button.id == 4 ? -1 : 1));
                lineScroll = 0;
                query();
                break;
            case 6:
            case 7:
                resources = button.id == 7;
                break;
            case 8:
                minutes = minutes == 1 ? 10 : minutes == 10 ? 60 : 1;
                query();
                break;
            case 9:
                allSources = !allSources;
                sourceId = "";
                sourcePage = resourcePage = sourceScroll = resourceScroll = 0;
                query();
                break;
            case 10:
                mutate("bind", "");
                break;
            case 11:
                mutate("unbind", "");
                break;
            case 12:
                close();
                break;
            case 13:
            case 14:
                int delta = button.id == 13 ? -1 : 1;
                if (resources) resourcePage = Math.max(0, resourcePage + delta);
                else sourcePage = Math.max(0, sourcePage + delta);
                sourceScroll = resourceScroll = 0;
                query();
                break;
            default:
                break;
        }
    }

    private void query() {
        if (container instanceof com.futa_gtnh.inventory.ContainerProductionTerminal && container.windowId == 0) {
            queryPending = true;
            return;
        }
        if (mutationPending) {
            queryPending = true;
            return;
        }
        requested = ++sequence;
        if (System.currentTimeMillis() - lastRequest < 850) {
            queryPending = true;
            return;
        }
        send(null);
    }

    private void mutate(String action, String name) {
        if (!data.getBoolean("canEdit") || mutationPending) return;
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", action);
        request.setString("lineId", lineId);
        request.setString("sourceId", sourceId);
        request.setString("name", name);
        pendingAction = action;
        pendingName = name;
        requested = ++sequence;
        mutationPending = true;
        mutationAt = System.currentTimeMillis();
        send(request);
    }

    private void send(NBTTagCompound mutation) {
        lastRequest = System.currentTimeMillis();
        if (!ClientProductionState.supported(mc.getNetHandler())) {
            status = tr("unsupported");
            mutationPending = false;
            return;
        }
        queryPending = false;
        PacketProductionRequest packet = new PacketProductionRequest();
        packet.window = container.windowId;
        packet.session = session;
        packet.sequence = requested;
        packet.minutes = minutes;
        packet.lineId = allSources ? "" : lineId;
        packet.sourceId = sourceId;
        packet.linePage = linePage;
        packet.sourcePage = sourcePage;
        packet.resourcePage = resourcePage;
        packet.mutation = mutation;
        NetworkHandler.INSTANCE.sendToServer(packet);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        com.futa_gtnh.client.ui.FutaUiTheme.panel(left, top, panelWidth, panelHeight);
        text(tr("title") + (lineId.isEmpty() ? "" : " · " + lineName), 8, 8, panelWidth - 16, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        NBTTagList lines = data.getTagList("lines", 10);
        lineScroll = clampScroll(lineScroll, lines.tagCount(), lineRows());
        for (int row = 0; row < lineRows() && row + lineScroll < lines.tagCount(); row++) {
            NBTTagCompound line = lines.getCompoundTagAt(row + lineScroll);
            int y = 49 + row * 20;
            drawRect(
                left + 6,
                top + y,
                left + listWidth - 5,
                top + y + 19,
                lineId.equals(line.getString("id")) ? 0xFF365D42 : 0xFF343434);
            text(line.getString("name"), 9, y + 3, listWidth - 20, 0xFFFFFF);
            text(tr("deviceCount", line.getInteger("sourceCount")), 9, y + 11, listWidth - 20, 0xAAAAAA);
        }
        text(
            (linePage + 1) + "/" + Math.max(1, data.getInteger("linePages")),
            29,
            panelHeight - 46,
            listWidth - 58,
            0xAAAAAA);
        int x = listWidth + 6, available = panelWidth - x - 8;
        NBTTagCompound total = data.getCompoundTag("totals");
        text(
            tr("itemRate", number(total, "itemOutPerMinute"), number(total, "itemInPerMinute")),
            x,
            49,
            available,
            0xFFFFFF);
        text(
            tr("fluidRate", number(total, "fluidOutPerMinute"), number(total, "fluidInPerMinute")),
            x,
            61,
            available,
            0xFFFFFF);
        text(
            data.getBoolean("hasRate")
                ? tr("observed", String.format(Locale.ROOT, "%.1f", data.getLong("observedMillis") / 1000d))
                : tr("warming"),
            x,
            74,
            available,
            0xAAAAAA);
        text(sourceId.isEmpty() ? tr("groupTotals") : tr("deviceTotals"), x, 86, available, 0xDDCC88);
        NBTTagList list = data.getTagList(resources ? "resources" : "sources", 10);
        if (resources) resourceScroll = clampScroll(resourceScroll, list.tagCount(), detailRows());
        else sourceScroll = clampScroll(sourceScroll, list.tagCount(), detailRows());
        int offset = resources ? resourceScroll : sourceScroll;
        for (int row = 0; row < detailRows() && row + offset < list.tagCount(); row++) {
            NBTTagCompound entry = list.getCompoundTagAt(row + offset);
            int y = 99 + row * 24;
            drawRect(
                left + x - 2,
                top + y,
                left + panelWidth - 7,
                top + y + 23,
                !resources && sourceId.equals(entry.getString("id")) ? 0xFF365D42 : 0xFF343434);
            if (resources) {
                text(resourceName(entry), x + 2, y + 2, available - 4, 0xFFFFFF);
                text(
                    tr("resourceRate", number(entry, "outPerMinute"), number(entry, "inPerMinute"), unit(entry)),
                    x + 2,
                    y + 13,
                    available - 4,
                    0xBBBBBB);
            } else {
                text(sourceName(entry), x + 2, y + 2, available - 4, 0xFFFFFF);
                text(
                    tr(
                        "compactRate",
                        number(entry, "itemOutPerMinute"),
                        number(entry, "itemInPerMinute"),
                        number(entry, "fluidOutPerMinute"),
                        number(entry, "fluidInPerMinute")),
                    x + 2,
                    y + 13,
                    available - 4,
                    0xBBBBBB);
            }
        }
        int page = resources ? resourcePage : sourcePage;
        text(
            (page + 1) + "/" + Math.max(1, data.getInteger(resources ? "resourcePages" : "sourcePages")),
            x + 23,
            panelHeight - 46,
            available - 46,
            0xAAAAAA);
        String footer = !status.isEmpty() ? status
            : !data.getBoolean("enabled") ? tr("disabled")
                : data.getLong("dropped") > 0 ? tr("capped") : data.getBoolean("canEdit") ? tr("hint") : tr("readOnly");
        text(footer, 8, panelHeight - 10, panelWidth - 16, 0xDDAA66);
        if (editMode != 0) drawEditor();
        else tooltip(mouseX, mouseY);
    }

    private int lineRows() {
        return Math.max(1, (panelHeight - 107) / 20);
    }

    private int detailRows() {
        return Math.max(1, (panelHeight - 156) / 24);
    }

    private static int clampScroll(int value, int size, int rows) {
        return Math.max(0, Math.min(value, size - rows));
    }

    private static String number(NBTTagCompound data, String key) {
        return String.format(Locale.ROOT, "%.1f", data.getDouble(key));
    }

    private static String unit(NBTTagCompound entry) {
        return "mB".equals(entry.getString("unit")) ? "mB" : tr("itemUnit");
    }

    private static String resourceName(NBTTagCompound entry) {
        Object key = SupplyTracker.readKey(entry);
        return key instanceof ItemKey ? ((ItemKey) key).prototype()
            .getDisplayName()
            : key instanceof FluidKey ? ((FluidKey) key).prototype()
                .getLocalizedName() : tr("unknown");
    }

    private static String sourceName(NBTTagCompound entry) {
        String name = entry.getString("name");
        return name.isEmpty()
            ? StatCollector.translateToLocal("futa_gtnh.supply.kind." + entry.getInteger("kind")) + " "
                + entry.getInteger("x")
                + ","
                + entry.getInteger("y")
                + ","
                + entry.getInteger("z")
            : name;
    }

    private void text(String value, int x, int y, int max, int color) {
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(value, Math.max(0, max)), left + x, top + y, color);
    }

    private void tooltip(int mx, int my) {
        List<String> lines = new ArrayList<>();
        int row = (my - top - 49) / 20;
        NBTTagList groups = data.getTagList("lines", 10);
        if (mx >= left + 6 && mx < left + listWidth - 5
            && my >= top + 49
            && row < lineRows()
            && row + lineScroll < groups.tagCount()) {
            NBTTagCompound group = groups.getCompoundTagAt(row + lineScroll);
            lines.add(group.getString("name"));
            lines.add(group.getString("id"));
            lines.add(tr("deviceCount", group.getInteger("sourceCount")));
        }
        row = (my - top - 99) / 24;
        NBTTagList list = data.getTagList(resources ? "resources" : "sources", 10);
        int offset = resources ? resourceScroll : sourceScroll;
        if (mx >= left + listWidth + 4 && mx < left + panelWidth - 6
            && my >= top + 99
            && row < detailRows()
            && row + offset < list.tagCount()) {
            NBTTagCompound entry = list.getCompoundTagAt(row + offset);
            if (resources) {
                lines.add(resourceName(entry));
                lines.add(tr("resourceRate", number(entry, "outPerMinute"), number(entry, "inPerMinute"), unit(entry)));
                lines.add(tr("resourceAmounts", entry.getLong("out"), entry.getLong("in"), unit(entry)));
            } else {
                lines.add(sourceName(entry));
                lines.add(entry.getString("id"));
                lines.add(
                    tr(
                        "member",
                        entry.getString("lineName")
                            .isEmpty() ? tr("unbound") : entry.getString("lineName")));
                lines.add(tr("itemRate", number(entry, "itemOutPerMinute"), number(entry, "itemInPerMinute")));
                lines.add(tr("fluidRate", number(entry, "fluidOutPerMinute"), number(entry, "fluidInPerMinute")));
                lines.add(tr(entry.getBoolean("active") ? "recorded" : "inactive"));
            }
            lines.add(tr("ledgerOnly"));
        }
        if (!lines.isEmpty()) drawHoveringText(lines, mx, my, fontRendererObj);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        if (editMode != 0) {
            if (editor != null) editor.mouseClicked(mx, my, button);
            if (button == 0) {
                int x = left + (panelWidth - 228) / 2, y = top + (panelHeight - 100) / 2;
                if (my >= y + 72 && my < y + 90) {
                    if (mx >= x + 12 && mx < x + 108) saveEditor();
                    else if (mx >= x + 120 && mx < x + 216) editMode = 0;
                }
            }
            return;
        }
        super.mouseClicked(mx, my, button);
        if (button != 0 || mutationPending) return;
        int row = (my - top - 49) / 20;
        NBTTagList list = data.getTagList("lines", 10);
        if (mx >= left + 6 && mx < left + listWidth - 5
            && my >= top + 49
            && row < lineRows()
            && row + lineScroll < list.tagCount()) {
            NBTTagCompound line = list.getCompoundTagAt(row + lineScroll);
            lineId = line.getString("id");
            lineName = line.getString("name");
            allSources = false;
            sourceId = "";
            sourcePage = resourcePage = sourceScroll = resourceScroll = 0;
            status = "";
            query();
        }
        row = (my - top - 99) / 24;
        list = data.getTagList("sources", 10);
        if (!resources && mx >= left + listWidth + 4
            && mx < left + panelWidth - 6
            && my >= top + 99
            && row < detailRows()
            && row + sourceScroll < list.tagCount()) {
            String next = list.getCompoundTagAt(row + sourceScroll)
                .getString("id");
            sourceId = sourceId.equals(next) ? "" : next;
            resourcePage = resourceScroll = 0;
            query();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || editMode != 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        int delta = wheel < 0 ? 1 : -1;
        if (mx < left + listWidth) lineScroll += delta;
        else if (resources) resourceScroll += delta;
        else sourceScroll += delta;
    }

    private void openEditor(int mode) {
        editMode = mode;
        status = "";
        if (mode == 3) {
            editor = null;
            return;
        }
        editor = new FutaSearchField(
            fontRendererObj,
            left + (panelWidth - 204) / 2,
            top + (panelHeight - 32) / 2,
            204,
            16);
        editor.setMaxStringLength(64);
        editor.setText(mode == 2 ? lineName : "", false);
        editor.setFocused(true);
    }

    private void drawEditor() {
        drawRect(left, top, left + panelWidth, top + panelHeight, 0xBB000000);
        int x = left + (panelWidth - 228) / 2, y = top + (panelHeight - 100) / 2;
        com.futa_gtnh.client.ui.FutaUiTheme.panel(x, y, 228, 100);
        fontRendererObj.drawString(
            tr(editMode == 1 ? "createName" : editMode == 2 ? "renameName" : "deleteConfirm"),
            x + 10,
            y + 10,
            0xFFFFFF);
        if (editor != null) editor.drawTextBox();
        else fontRendererObj.drawString(fontRendererObj.trimStringToWidth(lineName, 208), x + 10, y + 35, 0xFFCC88);
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(tr(editMode == 3 ? "deleteHint" : "nameHint"), 208),
            x + 10,
            y + 55,
            0xAAAAAA);
        drawRect(x + 12, y + 72, x + 108, y + 90, 0xFF3A573A);
        drawRect(x + 120, y + 72, x + 216, y + 90, 0xFF4A4A4A);
        fontRendererObj.drawString(tr(editMode == 3 ? "delete" : "save"), x + 20, y + 77, 0xFFFFFF);
        fontRendererObj.drawString(tr("cancel"), x + 128, y + 77, 0xFFFFFF);
    }

    private void saveEditor() {
        String name = editor == null ? ""
            : editor.getText()
                .trim();
        if (editMode != 3 && name.isEmpty()) return;
        mutate(editMode == 1 ? "create" : editMode == 2 ? "rename" : "delete", name);
        editMode = 0;
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (editMode != 0) editMode = 0;
            else close();
        } else if (editMode != 0) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) saveEditor();
            else if (editor != null) editor.textboxKeyTyped(character, key);
        }
    }

    private void close() {
        if (!(container instanceof ContainerSharedTerminal shared)) {
            if (mc.thePlayer != null) mc.thePlayer.closeScreen();
            else mc.displayGuiScreen(null);
            return;
        }
        mc.displayGuiScreen(
            mc.thePlayer != null && mc.thePlayer.openContainer == container
                ? parent == null ? GuiSharedTerminal.create(shared) : parent
                : null);
    }

    @Override
    public void onGuiClosed() {
        ClientProductionState.end(session);
        session = 0;
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String tr(String key, Object... args) {
        return StatCollector.translateToLocalFormatted("futa_gtnh.production." + key, args);
    }
}
