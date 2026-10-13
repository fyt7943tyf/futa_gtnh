package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.block.TerminalOutputFilter;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/** 独立于搜索结果的已选白名单。两种 IO 界面共享分页、空库存显示和删除语义。 */
final class OutputWhitelistPanel extends Gui {

    static final int COLS = 10;
    static final int CELL = 18;

    private final List<StorageViewEntry> entries = new ArrayList<>();
    private TerminalOutputFilter filter;
    private boolean fluid;
    private int filterRevision = -1;
    private int storageRevision = -1;
    private int page;

    void refresh(TerminalOutputFilter next, boolean fluidTab) {
        int now = ClientStorageCache.getRevision();
        if (next == filter && fluidTab == fluid && next.getRevision() == filterRevision && now == storageRevision)
            return;
        if (next != filter || fluidTab != fluid) page = 0;
        filter = next;
        fluid = fluidTab;
        filterRevision = next.getRevision();
        storageRevision = now;
        entries.clear();
        if (fluid) {
            for (FluidKey key : filter.getFluids()) {
                StorageViewEntry entry = ClientStorageCache.fluidEntry(key);
                if (entry == null) entry = StorageViewEntry.ofFluid(key, 0L);
                if (entry != null) entries.add(entry);
            }
        } else {
            for (ItemKey key : filter.getItems()) {
                StorageViewEntry entry = ClientStorageCache.itemEntry(key);
                entries.add(entry == null ? StorageViewEntry.ofItem(key, 0L) : entry);
            }
        }
        StorageSort.sort(entries, StorageSort.BY_NAME);
        page = Math.min(page, pageCount() - 1);
    }

    int size() {
        return entries.size();
    }

    int page() {
        return page;
    }

    int pageCount() {
        return Math.max(1, (entries.size() + COLS - 1) / COLS);
    }

    void movePage(int delta) {
        page = Math.max(0, Math.min(pageCount() - 1, page + delta));
    }

    StorageViewEntry entryAt(int mouseX, int mouseY, int left, int top) {
        if (mouseX < left || mouseX >= left + COLS * CELL || mouseY < top || mouseY >= top + CELL) return null;
        int index = page * COLS + (mouseX - left) / CELL;
        return index < entries.size() ? entries.get(index) : null;
    }

    boolean removeAt(int mouseX, int mouseY, int left, int top) {
        StorageViewEntry entry = entryAt(mouseX, mouseY, left, top);
        if (entry == null) return false;
        if (entry.isFluid()) filter.removeFluid(entry.getFluidKey());
        else filter.removeItem(entry.getItemKey());
        refresh(filter, fluid);
        return true;
    }

    void draw(Minecraft mc, FontRenderer font, RenderItem renderer, int left, int top, int mouseX, int mouseY) {
        for (int col = 0; col < COLS; col++) {
            int x = left + col * CELL;
            boolean hovered = mouseX >= x && mouseX < x + CELL && mouseY >= top && mouseY < top + CELL;
            com.futa_gtnh.client.ui.FutaUiTheme.slot(x, top);
            if (hovered) drawRect(x, top, x + CELL, top + CELL, 0x40633333);
            drawRect(x, top, x + CELL, top + 1, 0xFF55AA55);
        }
        RenderHelper.enableGUIStandardItemLighting();
        for (int col = 0; col < COLS; col++) {
            int index = page * COLS + col;
            if (index >= entries.size()) break;
            renderer.renderItemAndEffectIntoGUI(
                font,
                mc.getTextureManager(),
                entries.get(index)
                    .getDisplay(),
                left + col * CELL + 1,
                top + 1);
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glColor4f(1F, 1F, 1F, 1F);
    }
}
