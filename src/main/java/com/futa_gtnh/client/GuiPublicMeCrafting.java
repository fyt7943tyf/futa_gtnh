package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketPublicMeRequest;
import com.futa_gtnh.network.PacketPublicMeSnapshot;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.supply.SupplyTracker;

/** Preview and explicitly confirm native AE jobs without introducing another crafting executor. */
public final class GuiPublicMeCrafting extends FutaGuiScreen {

    private final ContainerSharedTerminal container;
    private NBTTagCompound data = new NBTTagCompound(), target;
    private FutaSearchField search, amount;
    private int left, top, panelWidth, panelHeight, listWidth, catalogPage, planPage, catalogScroll, planScroll;
    private int sequence, requested, applied;
    private long session, lastRequest, commandAt;
    private boolean pending, queryPending;
    private String searchText = "", amountText = "1", notice = "";

    public GuiPublicMeCrafting(ContainerSharedTerminal container) {
        this.container = container;
    }

    public static void open(ContainerSharedTerminal container) {
        container.withClientPageSwitch(
            () -> Minecraft.getMinecraft()
                .displayGuiScreen(new GuiPublicMeCrafting(container)));
    }

    @Override
    public void initGui() {
        panelWidth = Math.max(300, Math.min(500, width - 10));
        panelHeight = Math.max(210, Math.min(300, height - 10));
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        listWidth = Math.max(112, Math.min(170, panelWidth / 3));
        if (session == 0) session = ClientPublicMeState.begin(mc.getNetHandler());
        search = new FutaSearchField(fontRendererObj, left + 9, top + 28, listWidth - 18, 14);
        search.setText(searchText, false);
        search.setChangeListener(value -> {
            searchText = value;
            catalogPage = catalogScroll = 0;
            query();
        });
        amount = new FutaSearchField(fontRendererObj, left + 9, top + panelHeight - 60, listWidth - 18, 14);
        amount.setMaxStringLength(10);
        amount.setText(amountText, false);
        amount.setChangeListener(value -> amountText = value);
        buttonList.clear();
        button(0, 8, panelHeight - 92, 18, "<");
        button(1, listWidth - 26, panelHeight - 92, 18, ">");
        button(2, 8, panelHeight - 29, listWidth - 16, tr("plan"));
        int x = listWidth + 7, available = panelWidth - x - 8;
        button(3, x, panelHeight - 50, 18, "<");
        button(4, panelWidth - 26, panelHeight - 50, 18, ">");
        int third = (available - 4) / 3;
        button(5, x, panelHeight - 29, third, tr("confirm"));
        button(6, x + third + 2, panelHeight - 29, third, tr("cancel"));
        button(7, x + third * 2 + 4, panelHeight - 29, third, tr("back"));
        Keyboard.enableRepeatEvents(true);
        query();
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
        amount.updateCursorCounter();
        PacketPublicMeSnapshot response = ClientPublicMeState.response();
        if (response != null && response.window == container.windowId
            && response.session == session
            && response.sequence == requested
            && applied != requested) {
            applied = requested;
            data = response.data;
            catalogPage = data.getInteger("catalogPage");
            planPage = job().getInteger("rowPage");
            pending = false;
            notice = data.getString("notice");
        }
        long now = System.currentTimeMillis();
        if (pending && now - commandAt > 7000) {
            pending = false;
            notice = tr("timeout");
            queryPending = true;
        }
        int state = job().getInteger("state");
        long interval = state == 1 || state == 4 ? 1200 : 4000;
        if (!pending && (queryPending && now - lastRequest >= 850 || now - lastRequest >= interval)) query();
        updateButtons();
    }

    private NBTTagCompound job() {
        return data.getCompoundTag("job");
    }

    private long quantity() {
        try {
            long value = Long.parseLong(amountText.trim());
            return value > 0 && value <= 1_000_000_000L ? value : 0;
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private boolean matchesPreview() {
        Object chosen = SupplyTracker.readKey(target);
        return chosen != null && chosen.equals(SupplyTracker.readKey(job().getCompoundTag("output")))
            && quantity() == job().getLong("amount");
    }

    private void updateButtons() {
        int state = job().getInteger("state");
        boolean actionable = !pending && System.currentTimeMillis() - commandAt >= 850;
        for (Object value : buttonList) {
            GuiButton button = (GuiButton) value;
            button.enabled = !pending;
            if (button.id == 0) button.enabled &= catalogPage > 0;
            else if (button.id == 1) button.enabled &= catalogPage + 1 < data.getInteger("catalogPages");
            else if (button.id == 2) button.enabled &= actionable && data.getBoolean("canCraft")
                && target != null
                && quantity() > 0
                && state != 4;
            else if (button.id == 3) button.enabled &= planPage > 0;
            else if (button.id == 4) button.enabled &= planPage + 1 < job().getInteger("rowPages");
            else if (button.id == 5) button.enabled &= actionable && data.getBoolean("canCraft")
                && state == 2
                && !job().getString("planId")
                    .isEmpty()
                && matchesPreview();
            else if (button.id == 6) button.enabled &= actionable && state >= 1
                && state <= 4
                && !job().getString("planId")
                    .isEmpty();
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (!button.enabled) return;
        switch (button.id) {
            case 0:
            case 1:
                catalogPage = Math.max(0, catalogPage + (button.id == 0 ? -1 : 1));
                catalogScroll = 0;
                query();
                break;
            case 2:
                command(PacketPublicMeRequest.PLAN);
                break;
            case 3:
            case 4:
                planPage = Math.max(0, planPage + (button.id == 3 ? -1 : 1));
                planScroll = 0;
                query();
                break;
            case 5:
                command(PacketPublicMeRequest.CONFIRM);
                break;
            case 6:
                command(PacketPublicMeRequest.CANCEL);
                break;
            case 7:
                close();
                break;
            default:
                break;
        }
    }

    private void query() {
        if (pending) {
            queryPending = true;
            return;
        }
        requested = ++sequence;
        if (System.currentTimeMillis() - lastRequest < 850) queryPending = true;
        else send(PacketPublicMeRequest.QUERY);
    }

    private void command(int operation) {
        if (pending || System.currentTimeMillis() - commandAt < 850) return;
        if (operation == PacketPublicMeRequest.PLAN && (target == null || quantity() == 0)) {
            notice = tr("invalidAmount");
            return;
        }
        requested = ++sequence;
        pending = true;
        commandAt = System.currentTimeMillis();
        send(operation);
    }

    private void send(int operation) {
        lastRequest = System.currentTimeMillis();
        if (!ClientPublicMeState.supported(mc.getNetHandler())) {
            pending = false;
            notice = tr("unsupported");
            return;
        }
        queryPending = false;
        PacketPublicMeRequest packet = new PacketPublicMeRequest();
        packet.window = container.windowId;
        packet.session = session;
        packet.sequence = requested;
        packet.operation = operation;
        packet.search = searchText;
        packet.catalogPage = catalogPage;
        packet.planPage = planPage;
        packet.key = target;
        packet.amount = quantity();
        packet.planId = job().getString("planId");
        NetworkHandler.INSTANCE.sendToServer(packet);
    }

    @Override
    public void drawScreen(int mx, int my, float partialTicks) {
        drawDefaultBackground();
        com.futa_gtnh.client.ui.FutaUiTheme.panel(left, top, panelWidth, panelHeight);
        text(tr("title"), 8, 8, panelWidth - 16, 0xFFFFFF);
        super.drawScreen(mx, my, partialTicks);
        drawRect(left + 7, top + 25, left + listWidth - 7, top + 44, 0xFF222222);
        search.drawTextBox();
        if (searchText.isEmpty() && !search.isFocused()) text(tr("search"), 10, 29, listWidth - 20, 0x777777);
        NBTTagList catalog = data.getTagList("catalog", 10);
        catalogScroll = clampScroll(catalogScroll, catalog.tagCount(), catalogRows());
        Object selected = SupplyTracker.readKey(target);
        for (int row = 0; row < catalogRows() && row + catalogScroll < catalog.tagCount(); row++) {
            NBTTagCompound entry = catalog.getCompoundTagAt(row + catalogScroll);
            int y = 50 + row * 20;
            boolean chosen = selected != null && selected.equals(SupplyTracker.readKey(entry));
            drawRect(left + 6, top + y, left + listWidth - 5, top + y + 19, chosen ? 0xFF365D42 : 0xFF343434);
            text(name(entry), 9, y + 5, listWidth - 18, 0xFFFFFF);
        }
        text(
            (catalogPage + 1) + "/" + Math.max(1, data.getInteger("catalogPages")),
            29,
            panelHeight - 88,
            listWidth - 58,
            0xAAAAAA);
        text(
            tr("amount", target != null && target.getByte("kind") == 2 ? "mB" : tr("items")),
            9,
            panelHeight - 73,
            listWidth - 18,
            0xAAAAAA);
        drawRect(left + 7, top + panelHeight - 63, left + listWidth - 7, top + panelHeight - 44, 0xFF222222);
        amount.drawTextBox();
        int x = listWidth + 7, available = panelWidth - x - 8;
        NBTTagCompound job = job();
        text(
            job.hasKey("output", 10) ? name(job.getCompoundTag("output")) + " × " + job.getLong("amount")
                : tr("choose"),
            x,
            27,
            available,
            0xFFFFFF);
        text(
            tr("state." + job.getInteger("state")),
            x,
            40,
            available,
            job.getInteger("state") == 3 ? 0xFFAA66 : 0xCCDDCC);
        text(tr("cpu", job.getLong("bytes")), x, 53, available, 0xAAAAAA);
        text(tr("columns"), x, 68, available, 0xDDCC88);
        NBTTagList rows = job.getTagList("rows", 10);
        planScroll = clampScroll(planScroll, rows.tagCount(), planRows());
        for (int row = 0; row < planRows() && row + planScroll < rows.tagCount(); row++) {
            NBTTagCompound entry = rows.getCompoundTagAt(row + planScroll);
            int y = 82 + row * 24;
            drawRect(left + x - 2, top + y, left + panelWidth - 7, top + y + 23, 0xFF343434);
            text(name(entry), x + 2, y + 2, available - 4, 0xFFFFFF);
            text(
                entry.getLong("stored") + " / " + entry.getLong("craft") + " / " + entry.getLong("missing"),
                x + 2,
                y + 13,
                available - 4,
                entry.getLong("missing") > 0 ? 0xFFAA66 : 0xBBBBBB);
        }
        text(
            (planPage + 1) + "/" + Math.max(1, job.getInteger("rowPages")),
            x + 23,
            panelHeight - 46,
            available - 46,
            0xAAAAAA);
        String footer = !notice.isEmpty() ? notice : job.getString("message");
        if (footer.isEmpty()) footer = tr("hint");
        text(footer, 8, panelHeight - 10, panelWidth - 16, 0xDDAA66);
        tooltip(mx, my, footer);
    }

    private int catalogRows() {
        return Math.max(1, (panelHeight - 147) / 20);
    }

    private int planRows() {
        return Math.max(1, (panelHeight - 140) / 24);
    }

    private static int clampScroll(int value, int size, int rows) {
        return Math.max(0, Math.min(value, size - rows));
    }

    private void tooltip(int mx, int my, String footer) {
        List<String> lines = new ArrayList<>();
        if (my >= top + panelHeight - 12 && my < top + panelHeight && mx >= left && mx < left + panelWidth) {
            lines.add(footer);
            lines.add(tr("hint"));
            if (data.getBoolean("catalogCapped") || job().getInteger("hiddenRows") > 0) lines.add(tr("capped"));
        }
        int row = (my - top - 50) / 20;
        NBTTagList list = data.getTagList("catalog", 10);
        if (mx >= left + 6 && mx < left + listWidth - 5
            && my >= top + 50
            && row < catalogRows()
            && row + catalogScroll < list.tagCount()) lines.add(name(list.getCompoundTagAt(row + catalogScroll)));
        row = (my - top - 82) / 24;
        list = job().getTagList("rows", 10);
        if (mx >= left + listWidth + 5 && mx < left + panelWidth - 7
            && my >= top + 82
            && row < planRows()
            && row + planScroll < list.tagCount()) {
            NBTTagCompound entry = list.getCompoundTagAt(row + planScroll);
            lines.add(name(entry));
            lines.add(
                tr(
                    "row",
                    entry.getLong("stored"),
                    entry.getLong("craft"),
                    entry.getLong("missing"),
                    entry.getByte("kind") == 2 ? "mB" : tr("items")));
        }
        if (!lines.isEmpty()) drawHoveringText(lines, mx, my, fontRendererObj);
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) {
        search.mouseClicked(mx, my, button);
        amount.mouseClicked(mx, my, button);
        super.mouseClicked(mx, my, button);
        if (button != 0 || pending) return;
        int row = (my - top - 50) / 20;
        NBTTagList catalog = data.getTagList("catalog", 10);
        if (mx >= left + 6 && mx < left + listWidth - 5
            && my >= top + 50
            && row < catalogRows()
            && row + catalogScroll < catalog.tagCount()) {
            target = catalog.getCompoundTagAt(row + catalogScroll);
            notice = tr("selected");
            updateButtons();
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        int mx = Mouse.getEventX() * width / mc.displayWidth;
        if (mx < left + listWidth) catalogScroll += wheel < 0 ? 1 : -1;
        else planScroll += wheel < 0 ? 1 : -1;
    }

    @Override
    protected void keyTyped(char character, int key) {
        if (key == Keyboard.KEY_ESCAPE) {
            close();
            return;
        }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            if (amount.isFocused()) command(PacketPublicMeRequest.PLAN);
            return;
        }
        search.textboxKeyTyped(character, key);
        amount.textboxKeyTyped(character, key);
    }

    private void close() {
        mc.displayGuiScreen(
            mc.thePlayer != null && mc.thePlayer.openContainer == container ? GuiSharedTerminal.create(container)
                : null);
    }

    @Override
    public void onGuiClosed() {
        ClientPublicMeState.end(session);
        session = 0;
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void text(String value, int x, int y, int max, int color) {
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(value, Math.max(0, max)), left + x, top + y, color);
    }

    private static String name(NBTTagCompound entry) {
        Object key = SupplyTracker.readKey(entry);
        return key instanceof ItemKey ? ((ItemKey) key).prototype()
            .getDisplayName()
            : key instanceof FluidKey ? ((FluidKey) key).prototype()
                .getLocalizedName() : tr("unknown");
    }

    private static String tr(String key, Object... args) {
        return StatCollector.translateToLocalFormatted("futa_gtnh.meCraft." + key, args);
    }
}
