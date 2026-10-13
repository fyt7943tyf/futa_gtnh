package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.futa_gtnh.client.widget.FutaSearchField;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageAction;

/** 全服库存上限管理；只编辑设置，不改存量，手动存入仍允许超限。 */
public class GuiStorageLimits extends FutaGuiScreen {

    private static final int GUI_WIDTH = 300;
    private static final int GUI_HEIGHT = 224;
    private static final int COLS = 8;
    private static final int ROWS = 6;
    private static final int CELL = 18;
    private static final int PAGE = COLS * ROWS;
    private static final int BTN_ITEMS = 0;
    private static final int BTN_FLUIDS = 1;
    private static final int BTN_VIEW = 2;
    private static final int BTN_PREV = 3;
    private static final int BTN_NEXT = 4;
    private static final int BTN_SAVE = 5;
    private static final int BTN_CLEAR = 6;
    private static final int BTN_DONE = 7;

    private final ContainerSharedTerminal container;
    private final List<StorageViewEntry> shown = new ArrayList<>();
    private FutaSearchField search;
    private FutaSearchField amountField;
    private StorageViewEntry target;
    private boolean fluid;
    private boolean limitedOnly;
    private int page;
    private int left;
    private int top;
    private int revision = -1;
    private String query = "";
    private Object searchConfiguration;
    private String error;

    public GuiStorageLimits(ContainerSharedTerminal container) {
        this.container = container;
    }

    public static void open(ContainerSharedTerminal container) {
        if (container != null) Minecraft.getMinecraft()
            .displayGuiScreen(new GuiStorageLimits(container));
    }

    @Override
    public void initGui() {
        left = (width - GUI_WIDTH) / 2;
        top = (height - GUI_HEIGHT) / 2;
        String number = amountField == null ? "" : amountField.getText();
        search = new FutaSearchField(fontRendererObj, left + 8, top + 48, 144, 16);
        search.setText(query, false);
        search.setChangeListener(text -> {
            query = text;
            page = 0;
            revision = -1;
        });
        amountField = new FutaSearchField(fontRendererObj, left + 166, top + 130, 124, 16);
        amountField.setMaxStringLength(19);
        amountField.setText(number, false);
        amountField.setChangeListener(text -> error = null);
        buttonList.clear();
        buttonList.add(new GuiSmallButton(BTN_ITEMS, left + 8, top + 24, 42, 16, tr("tab.items")));
        buttonList.add(new GuiSmallButton(BTN_FLUIDS, left + 52, top + 24, 42, 16, tr("tab.fluids")));
        buttonList.add(new GuiSmallButton(BTN_VIEW, left + 96, top + 24, 56, 16, ""));
        buttonList.add(new GuiSmallButton(BTN_PREV, left + 8, top + 194, 18, 16, "<"));
        buttonList.add(new GuiSmallButton(BTN_NEXT, left + 134, top + 194, 18, 16, ">"));
        buttonList.add(new GuiSmallButton(BTN_SAVE, left + 166, top + 153, 124, 16, tr("limits.save")));
        buttonList.add(new GuiSmallButton(BTN_CLEAR, left + 166, top + 174, 124, 16, tr("limits.clear")));
        buttonList.add(new GuiSmallButton(BTN_DONE, left + 166, top + 194, 124, 16, tr("terminal.io.done")));
        Keyboard.enableRepeatEvents(true);
        refresh();
    }

    private void refresh() {
        Object configuration = NeiSearchBridge.configurationToken();
        int now = ClientStorageCache.getRevision();
        if (revision != now || searchConfiguration != configuration) {
            revision = now;
            searchConfiguration = configuration;
            StorageSearch
                .filter(ClientStorageCache.limitEntries(fluid, limitedOnly), StorageSearch.compile(query), shown);
            StorageSort.sort(shown, StorageSort.BY_NAME);
            page = Math.min(page, pageCount() - 1);
        }
        for (Object object : buttonList) {
            GuiButton button = (GuiButton) object;
            switch (button.id) {
                case BTN_ITEMS:
                    button.enabled = fluid;
                    break;
                case BTN_FLUIDS:
                    button.enabled = !fluid;
                    break;
                case BTN_VIEW:
                    button.displayString = tr(limitedOnly ? "limits.view.limited" : "limits.view.all");
                    break;
                case BTN_PREV:
                    button.enabled = page > 0;
                    break;
                case BTN_NEXT:
                    button.enabled = page + 1 < pageCount();
                    break;
                case BTN_SAVE:
                case BTN_CLEAR:
                    button.enabled = target != null && ClientStorageCache.isReady();
                    break;
                default:
                    break;
            }
        }
        amountField.setEnabled(target != null && ClientStorageCache.isReady());
    }

    private int pageCount() {
        return Math.max(1, (shown.size() + PAGE - 1) / PAGE);
    }

    static Long limitOf(StorageViewEntry entry) {
        return entry.isFluid() ? ClientStorageCache.getFluidLimit(entry.getFluidKey())
            : ClientStorageCache.getItemLimit(entry.getItemKey());
    }

    static long amountOf(StorageViewEntry entry) {
        StorageViewEntry current = entry.isFluid() ? ClientStorageCache.fluidEntry(entry.getFluidKey())
            : ClientStorageCache.itemEntry(entry.getItemKey());
        return current == null ? 0L : current.getAmount();
    }

    @Override
    public void updateScreen() {
        search.updateCursorCounter();
        amountField.updateCursorCounter();
        if (mc.thePlayer == null || mc.thePlayer.openContainer != container) {
            mc.displayGuiScreen(null);
            return;
        }
        refresh();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        com.futa_gtnh.client.ui.FutaUiTheme.panel(left, top, GUI_WIDTH, GUI_HEIGHT);
        fontRendererObj.drawStringWithShadow(tr("limits.title"), left + 8, top + 6, 0xFFFFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        drawField(search, left + 8, top + 48, 144);
        drawField(amountField, left + 166, top + 130, 124);
        RenderHelper.enableGUIStandardItemLighting();
        for (int cell = 0; cell < PAGE; cell++) {
            int x = left + 8 + cell % COLS * CELL;
            int y = top + 76 + cell / COLS * CELL;
            int index = page * PAGE + cell;
            StorageViewEntry entry = index < shown.size() ? shown.get(index) : null;
            drawRect(x, y, x + CELL - 1, y + CELL - 1, entry != null && sameTarget(entry) ? 0xFF376A37 : 0xFF353535);
            if (entry == null) continue;
            itemRender
                .renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), entry.getDisplay(), x + 1, y + 1);
            Long limit = limitOf(entry);
            if (limit != null) {
                drawRect(
                    x + CELL - 4,
                    y + CELL - 4,
                    x + CELL - 1,
                    y + CELL - 1,
                    amountOf(entry) >= limit ? 0xFFFF5555 : 0xFF55FF55);
            }
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glColor4f(1F, 1F, 1F, 1F);
        fontRendererObj.drawString((page + 1) + "/" + pageCount(), left + 62, top + 198, 0xC0C0C0);
        if (!ClientStorageCache.isReady()) {
            fontRendererObj.drawString(tr("terminal.io.syncing"), left + 8, top + 66, 0xFFFF55);
        } else if (shown.isEmpty()) {
            fontRendererObj.drawString(tr("terminal.io.empty"), left + 8, top + 66, 0x909090);
        }
        drawEditor();
        fontRendererObj
            .drawString(fontRendererObj.trimStringToWidth(tr("limits.manual"), 284), left + 8, top + 214, 0xA0A0A0);
        StorageViewEntry hovered = entryAt(mouseX, mouseY);
        if (hovered != null) {
            List<String> tooltip = new ArrayList<>(
                hovered.getDisplay()
                    .getTooltip(mc.thePlayer, mc.gameSettings.advancedItemTooltips));
            tooltip.add(tr("limits.stock") + ": " + amountOf(hovered) + (fluid ? " mB / L" : ""));
            Long limit = limitOf(hovered);
            tooltip.add(tr("limits.maximum") + ": " + (limit == null ? tr("limits.unlimited") : limit));
            drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
        }
    }

    private void drawField(FutaSearchField field, int x, int y, int fieldWidth) {
        drawRect(x - 1, y - 1, x + fieldWidth + 1, y + 17, 0xFFA0A0A0);
        drawRect(x, y, x + fieldWidth, y + 16, 0xFF000000);
        field.drawTextBox();
    }

    private void drawEditor() {
        if (target == null) {
            fontRendererObj.drawSplitString(tr("limits.choose"), left + 166, top + 48, 124, 0xC0C0C0);
            return;
        }
        fontRendererObj.drawSplitString(
            fontRendererObj.trimStringToWidth(target.getDisplayName(), 124),
            left + 166,
            top + 48,
            124,
            0xFFFFFF);
        long amount = amountOf(target);
        Long limit = limitOf(target);
        fontRendererObj.drawString(tr("limits.stock") + (fluid ? " (mB / L)" : ""), left + 166, top + 68, 0xC0C0C0);
        fontRendererObj.drawString(Long.toString(amount), left + 166, top + 80, 0xFFFFFF);
        String status = limit == null ? "limits.unlimited"
            : amount > limit ? "limits.overfull" : amount == limit ? "limits.full" : "limits.accepting";
        fontRendererObj.drawString(
            fontRendererObj.trimStringToWidth(tr(status), 124),
            left + 166,
            top + 96,
            limit != null && amount >= limit ? 0xFF5555 : 0x55FF55);
        fontRendererObj.drawString(tr("limits.maximum"), left + 166, top + 116, 0xC0C0C0);
        if (error != null)
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(error, 144), left + 8, top + 66, 0xFF5555);
    }

    private boolean sameTarget(StorageViewEntry entry) {
        return target != null && (fluid ? entry.getFluidKey()
            .equals(target.getFluidKey())
            : entry.getItemKey()
                .equals(target.getItemKey()));
    }

    private StorageViewEntry entryAt(int x, int y) {
        int dx = x - left - 8;
        int dy = y - top - 76;
        if (dx < 0 || dx >= COLS * CELL || dy < 0 || dy >= ROWS * CELL) return null;
        int index = page * PAGE + dy / CELL * COLS + dx / CELL;
        return index < shown.size() ? shown.get(index) : null;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        search.mouseClicked(mouseX, mouseY, mouseButton);
        amountField.mouseClicked(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0 || !ClientStorageCache.isReady()) return;
        StorageViewEntry entry = entryAt(mouseX, mouseY);
        if (entry == null) return;
        target = entry;
        error = null;
        Long limit = limitOf(entry);
        amountField.setText(limit == null ? "" : Long.toString(limit), false);
        amountField.setFocused(true);
        search.setFocused(false);
        refresh();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case BTN_ITEMS:
            case BTN_FLUIDS:
                fluid = button.id == BTN_FLUIDS;
                target = null;
                error = null;
                amountField.setText("", false);
                page = 0;
                revision = -1;
                break;
            case BTN_VIEW:
                limitedOnly = !limitedOnly;
                page = 0;
                revision = -1;
                break;
            case BTN_PREV:
                page = Math.max(0, page - 1);
                break;
            case BTN_NEXT:
                page = Math.min(pageCount() - 1, page + 1);
                break;
            case BTN_SAVE:
                save();
                break;
            case BTN_CLEAR:
                send(-1L);
                amountField.setText("", false);
                break;
            case BTN_DONE:
                close();
                return;
            default:
                break;
        }
        refresh();
    }

    static long parseLimit(String text) {
        String value = text.trim();
        if (value.isEmpty()) return -1L;
        if (!value.matches("[0-9]+")) throw new NumberFormatException();
        return Long.parseLong(value);
    }

    private void save() {
        try {
            send(parseLimit(amountField.getText()));
            error = null;
        } catch (NumberFormatException invalid) {
            error = tr("limits.invalid");
        }
    }

    private void send(long limit) {
        if (target == null || !ClientStorageCache.isReady()
            || mc.thePlayer == null
            || mc.thePlayer.openContainer != container) return;
        NetworkHandler.INSTANCE.sendToServer(
            target.isFluid()
                ? PacketStorageAction.fluid(PacketStorageAction.SET_FLUID_LIMIT, target.getFluidKey(), limit)
                : PacketStorageAction.item(PacketStorageAction.SET_ITEM_LIMIT, target.getItemKey(), limit));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            close();
            return;
        }
        if (search.isFocused()) {
            search.textboxKeyTyped(typedChar, keyCode);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            save();
            return;
        }
        amountField.textboxKeyTyped(typedChar, keyCode);
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

    private static String tr(String key) {
        return StatCollector.translateToLocal("futa_gtnh.gui." + key);
    }
}
