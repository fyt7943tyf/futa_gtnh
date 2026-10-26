package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketSupplyRequest;
import com.futa_gtnh.network.PacketSupplySnapshot;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.supply.SupplySource;
import com.futa_gtnh.supply.SupplyTracker;

/** On-demand resource/inlet inspection. No server ledger is streamed while this screen is closed. */
public final class GuiSupplyMonitor extends FutaGuiScreen {

    private final ContainerSharedTerminal container;
    private final List<StorageViewEntry> shown = new ArrayList<>();
    private final List<StorageViewEntry> watched = new ArrayList<>();
    private FutaSearchField search, editor;
    private NBTTagCompound data = new NBTTagCompound();
    private NBTTagCompound selectedSource;
    private Object target;
    private String targetName = "", query = "", error;
    private int left, top, panelWidth, panelHeight, listWidth, rows, mode, itemPage, sourcePage, sourceScroll,
        watchPage;
    private int minutes = 10, ticks, lastRevision = -1, requested, applied, editMode;
    private long lastRequest;
    private long lastTeleport;
    private PacketSupplySnapshot handledNavigation;
    private boolean listDirty = true;
    private boolean queryPending;

    public GuiSupplyMonitor(ContainerSharedTerminal container) {
        this.container = container;
        mode = 3;
    }

    public static void open(ContainerSharedTerminal container) {
        container.withClientPageSwitch(
            () -> Minecraft.getMinecraft()
                .displayGuiScreen(new GuiSupplyMonitor(container)));
    }

    @Override
    public void initGui() {
        panelWidth = Math.max(300, Math.min(450, width - 10));
        panelHeight = Math.max(210, Math.min(292, height - 10));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listWidth = Math.min(144, panelWidth / 3);
        rows = Math.max(1, (panelHeight - 96) / 20);
        search = new FutaSearchField(fontRendererObj, left + 8, top + 44, listWidth - 16, 14);
        search.setText(query, false);
        search.setChangeListener(text -> {
            query = text;
            itemPage = 0;
            listDirty = true;
        });
        buttonList.clear();
        int tabWidth = (listWidth - 22) / 4;
        button(0, 8, 24, tabWidth, tr("items"));
        button(1, 10 + tabWidth, 24, tabWidth, tr("fluids"));
        button(2, 12 + tabWidth * 2, 24, tabWidth, tr("watched"));
        button(12, 14 + tabWidth * 3, 24, tabWidth, tr("history"));
        button(3, 8, panelHeight - 26, 18, "<");
        button(4, listWidth - 26, panelHeight - 26, 18, ">");
        button(5, panelWidth - 46, 24, 36, "10m");
        button(6, listWidth + 8, panelHeight - 26, 16, "<");
        button(7, listWidth + 26, panelHeight - 26, 16, ">");
        button(8, listWidth + 8, panelHeight - 48, 64, tr("warning"));
        button(9, listWidth + 44, panelHeight - 26, 34, tr("rename"));
        button(10, listWidth + 80, panelHeight - 26, 34, tr("coords"));
        button(11, panelWidth - 48, panelHeight - 26, 38, tr("done"));
        button(13, listWidth + 8, panelHeight - 70, 64, tr("highlight"));
        button(14, listWidth + 74, panelHeight - 70, 64, tr("teleport"));
        button(16, panelWidth - 100, 5, 90, StatCollector.translateToLocal("futa_gtnh.me.crafting.button"));
        button(15, panelWidth - 168, 5, 64, StatCollector.translateToLocal("futa_gtnh.production.button"));
        Keyboard.enableRepeatEvents(true);
        listDirty = true;
        request(PacketSupplyRequest.QUERY, "", "", -1);
        updateButtons();
    }

    private void button(int id, int x, int y, int size, String text) {
        buttonList.add(new GuiSmallButton(id, left + x, top + y, size, 16, text));
    }

    @Override
    public void updateScreen() {
        if (mc.thePlayer == null || mc.thePlayer.openContainer != container) {
            mc.displayGuiScreen(null);
            return;
        }
        search.updateCursorCounter();
        if (editor != null) editor.updateCursorCounter();
        ticks++;
        PacketSupplySnapshot response = ClientSupplyState.response();
        PacketSupplySnapshot navigation = ClientSupplyState.navigation();
        if (navigation != null && navigation != handledNavigation
            && navigation.window == container.windowId
            && navigation.sequence == requested) {
            handledNavigation = navigation;
            error = tr(
                navigation.data.getCompoundTag("navigate")
                    .getString("message"));
        }
        if (response != null && response.window == container.windowId
            && response.sequence == requested
            && response.sequence != applied) {
            applied = response.sequence;
            apply(response.data);
        }
        if (ticks % 40 == 0 && lastRevision != ClientStorageCache.getRevision()) listDirty = true;
        if (listDirty) refreshList();
        if (queryPending && System.currentTimeMillis() - lastRequest >= 1100)
            request(PacketSupplyRequest.QUERY, "", "", -1);
        if (editMode == 0 && System.currentTimeMillis() - lastRequest >= 5000)
            request(PacketSupplyRequest.QUERY, "", "", -1);
        updateButtons();
    }

    private void apply(NBTTagCompound snapshot) {
        data = snapshot;
        sourcePage = data.getInteger("sourcePage");
        watchPage = data.getInteger("watchPage");
        watched.clear();
        NBTTagList list = data.getTagList("watches", 10);
        for (int i = 0; i < Math.min(SupplyTracker.PAGE_SIZE, list.tagCount()); i++) {
            Object key = SupplyTracker.readKey(list.getCompoundTagAt(i));
            StorageViewEntry entry = key instanceof ItemKey ? StorageViewEntry.ofItem((ItemKey) key, 0)
                : key instanceof FluidKey ? StorageViewEntry.ofFluid((FluidKey) key, 0) : null;
            if (entry != null) watched.add(entry);
        }
        if (selectedSource != null && editMode != 2) {
            String id = selectedSource.getString("id");
            selectedSource = null;
            NBTTagList sources = data.getTagList("sources", 10);
            for (int i = 0; i < sources.tagCount(); i++) if (id.equals(
                sources.getCompoundTagAt(i)
                    .getString("id")))
                selectedSource = sources.getCompoundTagAt(i);
        }
        error = data.getBoolean("okay") ? null : tr("rejected");
        if (!data.getBoolean("writable")) error = tr("settingsError");
        if (mode >= 2) listDirty = true;
    }

    private void refreshList() {
        listDirty = false;
        lastRevision = ClientStorageCache.getRevision();
        List<StorageViewEntry> source = mode >= 2 ? watched
            : mode == 1 ? ClientStorageCache.fluids() : ClientStorageCache.items();
        StorageSearch.filter(source, StorageSearch.compile(query), shown);
        StorageSort.sort(shown, StorageSort.BY_NAME);
        itemPage = Math.min(itemPage, Math.max(0, (shown.size() - 1) / rows));
    }

    private void updateButtons() {
        for (Object value : buttonList) {
            GuiButton b = (GuiButton) value;
            b.enabled = editMode == 0;
            if (b.id < 3) b.enabled &= mode != b.id;
            else if (b.id == 12) b.enabled &= mode != 3;
            else if (b.id == 3) b.enabled &= mode >= 2 ? itemPage > 0 || watchPage > 0 : itemPage > 0;
            else if (b.id == 4) b.enabled &= (itemPage + 1) * rows < shown.size()
                || mode >= 2 && watchPage + 1 < data.getInteger("watchPages");
            else if (b.id == 5) b.displayString = minutes + "m";
            else if (b.id == 6) b.enabled &= sourcePage > 0;
            else if (b.id == 7) b.enabled &= sourcePage + 1 < data.getInteger("sourcePages");
            else if (b.id == 8)
                b.enabled &= target != null && data.getBoolean("writable") && data.getBoolean("enabled");
            else if (b.id == 9) b.enabled &= selectedSource != null && data.getBoolean("writable");
            else if (b.id == 10)
                b.enabled &= selectedSource != null && selectedSource.getInteger("kind") != SupplySource.UNKNOWN
                    && selectedSource.getInteger("kind") != SupplySource.PLAYER;
            else if (b.id == 13 || b.id == 14) {
                SupplySource source = selectedSource == null ? null : SupplySource.read(selectedSource);
                b.enabled &= source != null && source.hasPosition();
                if (b.id == 13) b.displayString = tr(SupplyHighlight.selected(source) ? "clearHighlight" : "highlight");
                else b.enabled &= data.getBoolean("teleportEnabled")
                    && System.currentTimeMillis() - lastTeleport >= 5000;
            }
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled || editMode != 0) return;
        if (button.id < 3 || button.id == 12) {
            mode = button.id == 12 ? 3 : button.id;
            itemPage = 0;
            watchPage = 0;
            query = "";
            search.setText("", false);
            watched.clear();
            listDirty = true;
            request(PacketSupplyRequest.QUERY, "", "", -1);
            return;
        }
        switch (button.id) {
            case 16:
                GuiPublicMeCrafting.open(container);
                return;
            case 15:
                GuiProductionLines.open(container);
                return;
            case 3:
            case 4:
                int direction = button.id == 3 ? -1 : 1;
                if (mode >= 2) {
                    if (direction > 0 && (itemPage + 1) * rows < shown.size() || direction < 0 && itemPage > 0) {
                        itemPage += direction;
                        listDirty = true;
                    } else {
                        watchPage = Math.max(0, watchPage + direction);
                        itemPage = 0;
                        request(PacketSupplyRequest.QUERY, "", "", -1);
                    }
                } else {
                    itemPage = Math.max(0, itemPage + direction);
                    listDirty = true;
                }
                break;
            case 5:
                minutes = minutes == 1 ? 10 : minutes == 10 ? 60 : 1;
                sourcePage = 0;
                sourceScroll = 0;
                request(PacketSupplyRequest.QUERY, "", "", -1);
                break;
            case 6:
            case 7:
                sourcePage = Math.max(0, sourcePage + (button.id == 6 ? -1 : 1));
                sourceScroll = 0;
                request(PacketSupplyRequest.QUERY, "", "", -1);
                break;
            case 8:
                openEditor(1);
                break;
            case 9:
                openEditor(2);
                break;
            case 10:
                setClipboardString(location(selectedSource));
                error = tr("copied");
                break;
            case 11:
                close();
                break;
            case 13:
                SupplySource source = SupplySource.read(selectedSource);
                if (SupplyHighlight.selected(source)) {
                    SupplyHighlight.clear();
                    error = tr("highlightCleared");
                } else {
                    SupplyHighlight.show(source);
                    error = tr("highlighted");
                }
                break;
            case 14:
                lastTeleport = System.currentTimeMillis();
                error = tr("travelling");
                request(PacketSupplyRequest.TELEPORT, selectedSource.getString("id"), "", -1);
                break;
            default:
                break;
        }
    }

    private void request(int operation, String source, String name, long threshold) {
        if (mc.thePlayer == null || mc.thePlayer.openContainer != container) return;
        if (!ClientSupplyState.supported()) {
            lastRequest = System.currentTimeMillis();
            error = tr("unsupported");
            return;
        }
        if (operation == PacketSupplyRequest.QUERY && System.currentTimeMillis() - lastRequest < 1100) {
            queryPending = true;
            requested = ClientSupplyState.nextSequence(); // Ignore a stale response for the previous selection.
            return;
        }
        queryPending = false;
        PacketSupplyRequest packet = new PacketSupplyRequest();
        packet.window = container.windowId;
        packet.sequence = ClientSupplyState.nextSequence();
        packet.operation = operation;
        packet.minutes = minutes;
        packet.sourcePage = sourcePage;
        packet.watchPage = watchPage;
        packet.listMode = mode == 3 ? 2 : mode == 2 ? 1 : 0;
        packet.key = target == null ? null : SupplyTracker.writeKey(target);
        packet.source = source;
        packet.name = name;
        packet.threshold = threshold;
        requested = packet.sequence;
        lastRequest = System.currentTimeMillis();
        NetworkHandler.INSTANCE.sendToServer(packet);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        com.futa_gtnh.client.ui.FutaUiTheme.panel(left, top, panelWidth, panelHeight);
        text(tr("title"), 8, 7, panelWidth - 184, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        search.drawTextBox();
        RenderHelper.enableGUIStandardItemLighting();
        for (int row = 0; row < rows; row++) {
            int i = itemPage * rows + row;
            if (i >= shown.size()) break;
            StorageViewEntry entry = shown.get(i);
            int y = top + 64 + row * 20;
            Object key = key(entry);
            drawRect(left + 6, y, left + listWidth - 5, y + 19, key.equals(target) ? 0xFF365D42 : 0xFF343434);
            itemRender.renderItemAndEffectIntoGUI(
                fontRendererObj,
                mc.getTextureManager(),
                entry.getDisplay(),
                left + 8,
                y + 1);
            fontRendererObj.drawString(
                fontRendererObj.trimStringToWidth(entry.getDisplayName(), listWidth - 34),
                left + 27,
                y + 5,
                0xFFFFFF);
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        int x = listWidth + 8, available = panelWidth - x - 10;
        text(target == null ? tr("choose") : targetName, x, 26, available - 40, 0xFFFFFF);
        if (target != null) {
            text(tr("stock", data.getLong("stock")), x, 44, available, 0xFFFFFF);
            double rate = data.getDouble("perMinute");
            String trend = data.getBoolean("hasTrend")
                ? tr("trend", number(rate), rate < 0 ? number(data.getLong("stock") / -rate) : "--")
                : tr("sampling");
            text(trend, x, 56, available, rate < 0 ? 0xFFCC77 : 0xAAAAAA);
            text(tr("totals", data.getLong("outgoing"), data.getLong("incoming")), x, 68, available, 0xAAAAAA);
            text(data.getLong("dropped") > 0 ? tr("capped") : tr("sources"), x, 85, available, 0xDDDDDD);
            NBTTagList sources = data.getTagList("sources", 10);
            sourceScroll = Math.max(0, Math.min(sourceScroll, sources.tagCount() - visibleSourceRows()));
            for (int row = 0; row < visibleSourceRows() && row + sourceScroll < sources.tagCount(); row++) {
                NBTTagCompound source = sources.getCompoundTagAt(row + sourceScroll);
                int y = top + 99 + row * 17;
                boolean selected = selectedSource != null && source.getString("id")
                    .equals(selectedSource.getString("id"));
                drawRect(left + x - 2, y - 1, left + panelWidth - 9, y + 15, selected ? 0xFF365D42 : 0xFF343434);
                long net = Math.max(0L, source.getLong("out") - source.getLong("in"));
                String amount = Long.toString(net);
                fontRendererObj.drawString(
                    amount,
                    left + panelWidth - 12 - fontRendererObj.getStringWidth(amount),
                    y + 3,
                    0xFFFFFF);
                text(
                    sourceName(source),
                    x,
                    y - top + 3,
                    available - fontRendererObj.getStringWidth(amount) - 7,
                    0xFFFFFF);
            }
        }
        long threshold = data.getLong("threshold");
        text(
            threshold < 0 ? tr("unwatched") : tr("threshold", threshold),
            x + 68,
            panelHeight - 44,
            available - 68,
            0xAAAAAA);
        text(
            mode >= 2 ? (watchPage + 1) + "/" + Math.max(1, data.getInteger("watchPages"))
                : (itemPage + 1) + "/" + Math.max(1, (shown.size() + rows - 1) / rows),
            29,
            panelHeight - 22,
            listWidth - 58,
            0xAAAAAA);
        if (!data.getBoolean("enabled")) text(tr("disabled"), x, 99, available, 0xFF7777);
        if (error != null) text(error, 8, panelHeight - 9, panelWidth - 16, 0xFFAA55);
        if (editMode != 0) drawEditor();
        else if (mode >= 2 && mouseX >= left + 8
            && mouseX < left + listWidth - 8
            && mouseY >= top + 44
            && mouseY < top + 58)
            drawHoveringText(java.util.Collections.singletonList(tr("pageSearch")), mouseX, mouseY, fontRendererObj);
        else if (mouseY >= top + panelHeight - 70 && mouseY < top + panelHeight - 54
            && mouseX >= left + listWidth + 8
            && mouseX < left + listWidth + 138)
            drawHoveringText(
                java.util.Collections
                    .singletonList(tr(mouseX < left + listWidth + 74 ? "highlightTip" : "teleportTip")),
                mouseX,
                mouseY,
                fontRendererObj);
        else drawSourceTooltip(mouseX, mouseY);
    }

    private int visibleSourceRows() {
        return Math.max(1, Math.min(8, (panelHeight - 166) / 17));
    }

    private static Object key(StorageViewEntry entry) {
        return entry.isFluid() ? entry.getFluidKey() : entry.getItemKey();
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private void text(String value, int x, int y, int max, int color) {
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(value, Math.max(0, max)), left + x, top + y, color);
    }

    private static String tr(String key, Object... args) {
        return StatCollector.translateToLocalFormatted("futa_gtnh.supply." + key, args);
    }

    private String sourceName(NBTTagCompound tag) {
        String name = tag.getString("name");
        if (!name.isEmpty()) return name;
        int kind = tag.getInteger("kind");
        return tr("kind." + kind) + (kind == SupplySource.PLAYER ? " " + tag.getString("player") : "");
    }

    private static String location(NBTTagCompound tag) {
        return "D" + tag.getInteger("dim")
            + " "
            + tag.getInteger("x")
            + ", "
            + tag.getInteger("y")
            + ", "
            + tag.getInteger("z")
            + " / "
            + ForgeDirection.getOrientation(tag.getInteger("side"))
                .name();
    }

    private void drawSourceTooltip(int mx, int my) {
        int row = (my - top - 99) / 17;
        NBTTagList sources = data.getTagList("sources", 10);
        if (mx < left + listWidth + 6 || mx >= left + panelWidth - 8
            || my < top + 99
            || row >= visibleSourceRows()
            || row + sourceScroll >= sources.tagCount()) return;
        NBTTagCompound source = sources.getCompoundTagAt(row + sourceScroll);
        SupplySource identity = SupplySource.read(source);
        List<String> tooltip = new ArrayList<>();
        tooltip.add(sourceName(source));
        if (identity.kind == SupplySource.AE_BUS || identity.kind == SupplySource.IO
            || identity.kind == SupplySource.AE_MACHINE) tooltip.add(location(source));
        tooltip.add(tr("rowTotals", source.getLong("out"), source.getLong("in")));
        if (identity.kind == SupplySource.AE_BUS || identity.kind == SupplySource.IO) {
            net.minecraftforge.common.util.ForgeDirection side = net.minecraftforge.common.util.ForgeDirection
                .getOrientation(identity.side);
            tooltip.add(tr("peer", identity.x + side.offsetX, identity.y + side.offsetY, identity.z + side.offsetZ));
        }
        if (identity.kind == SupplySource.AE_MACHINE) tooltip.add(tr("unresolved"));
        tooltip.add(tr("ledgerOnly"));
        drawHoveringText(tooltip, mx, my, fontRendererObj);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        if (editMode != 0) {
            editor.mouseClicked(mx, my, button);
            editorClick(mx, my, button);
            return;
        }
        search.mouseClicked(mx, my, button);
        super.mouseClicked(mx, my, button);
        if (button != 0) return;
        int row = (my - top - 64) / 20;
        if (mx >= left + 6 && mx < left + listWidth - 5 && my >= top + 64 && row < rows) {
            int i = itemPage * rows + row;
            if (i < shown.size()) {
                StorageViewEntry entry = shown.get(i);
                target = key(entry);
                targetName = entry.getDisplayName();
                selectedSource = null;
                sourcePage = 0;
                sourceScroll = 0;
                data = new NBTTagCompound();
                request(PacketSupplyRequest.QUERY, "", "", -1);
            }
        }
        row = (my - top - 99) / 17;
        NBTTagList sources = data.getTagList("sources", 10);
        if (mx >= left + listWidth + 6 && mx < left + panelWidth - 8
            && my >= top + 99
            && row < visibleSourceRows()
            && row + sourceScroll < sources.tagCount()) selectedSource = sources.getCompoundTagAt(row + sourceScroll);
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0 || editMode != 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        if (mx >= left + listWidth) sourceScroll = Math.max(0, sourceScroll + (wheel < 0 ? 1 : -1));
        else {
            itemPage = Math.max(0, itemPage + (wheel < 0 ? 1 : -1));
            listDirty = true;
        }
    }

    private void openEditor(int mode) {
        editMode = mode;
        editor = new FutaSearchField(
            fontRendererObj,
            left + (panelWidth - 204) / 2,
            top + (panelHeight - 32) / 2,
            204,
            16);
        editor.setMaxStringLength(mode == 1 ? 19 : 64);
        editor.setText(
            mode == 1 ? (data.getLong("threshold") < 0 ? "" : Long.toString(data.getLong("threshold")))
                : selectedSource.getString("name"),
            false);
        editor.setFocused(true);
        error = null;
    }

    private void drawEditor() {
        drawRect(left, top, left + panelWidth, top + panelHeight, 0xBB000000);
        int x = left + (panelWidth - 228) / 2, y = top + (panelHeight - 100) / 2;
        com.futa_gtnh.client.ui.FutaUiTheme.panel(x, y, 228, 100);
        fontRendererObj.drawString(tr(editMode == 1 ? "editWarning" : "editName"), x + 10, y + 10, 0xFFFFFF);
        editor.drawTextBox();
        fontRendererObj.drawString(fontRendererObj.trimStringToWidth(tr("blankClears"), 208), x + 10, y + 54, 0xAAAAAA);
        drawRect(x + 12, y + 72, x + 108, y + 90, 0xFF3A573A);
        drawRect(x + 120, y + 72, x + 216, y + 90, 0xFF4A4A4A);
        fontRendererObj.drawString(tr("save"), x + 20, y + 77, 0xFFFFFF);
        fontRendererObj.drawString(tr("cancel"), x + 128, y + 77, 0xFFFFFF);
        if (error != null)
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(error, 208), x + 10, y + 63, 0xFF7777);
    }

    private void editorClick(int mx, int my, int button) {
        if (button != 0) return;
        int x = left + (panelWidth - 228) / 2, y = top + (panelHeight - 100) / 2;
        if (my >= y + 72 && my < y + 90) {
            if (mx >= x + 12 && mx < x + 108) saveEditor();
            else if (mx >= x + 120 && mx < x + 216) editMode = 0;
        }
    }

    private void saveEditor() {
        try {
            if (editMode == 1) {
                String value = editor.getText()
                    .trim();
                if (!value.isEmpty() && !value.matches("[0-9]+")) throw new NumberFormatException();
                request(PacketSupplyRequest.WATCH, "", "", value.isEmpty() ? -1 : Long.parseLong(value));
            } else request(PacketSupplyRequest.RENAME, selectedSource.getString("id"), editor.getText(), -1);
            editMode = 0;
        } catch (NumberFormatException invalid) {
            error = tr("invalid");
        }
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            if (editMode != 0) editMode = 0;
            else close();
            return;
        }
        if (editMode != 0) {
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) saveEditor();
            else editor.textboxKeyTyped(character, key);
        } else search.textboxKeyTyped(character, key);
    }

    private void close() {
        mc.displayGuiScreen(
            mc.thePlayer != null && mc.thePlayer.openContainer == container ? GuiSharedTerminal.create(container)
                : null);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
