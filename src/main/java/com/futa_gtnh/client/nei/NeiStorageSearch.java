package com.futa_gtnh.client.nei;

import java.util.function.Predicate;

import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.NeiSearchBridge;
import com.futa_gtnh.client.StorageViewEntry;

import codechicken.nei.LayoutManager;
import codechicken.nei.SearchField;
import codechicken.nei.api.ItemFilter;

/** Only loaded when NEI is installed; searches stored stacks without changing the NEI panel query. */
public final class NeiStorageSearch implements NeiSearchBridge.Impl {

    @Override
    public boolean searchFieldExists() {
        return LayoutManager.searchField != null;
    }

    @Override
    public void pushSearchText(String text) {
        SearchField field = LayoutManager.searchField;
        if (field != null && !field.text()
            .equals(text)) field.setText(text);
    }

    @Override
    public Predicate<StorageViewEntry> compileFilter(String text) {
        final ItemFilter filter;
        try {
            // The shared parser includes NEChar and other mod providers, English names, prefixes,
            // space/pattern modes and the player's ALWAYS/PREFIX/NEVER settings.
            filter = SearchField.getFilter(text);
        } catch (RuntimeException exception) {
            FutaGtnhMod.LOG.warn("Cannot parse shared storage NEI search {}", text, exception);
            return entry -> false;
        }
        return new Predicate<StorageViewEntry>() {

            private boolean reported;

            @Override
            public boolean test(StorageViewEntry entry) {
                try {
                    // GT fluid display stacks retain their actual fluid identity for NEI providers.
                    return filter.matches(entry.getDisplay());
                } catch (RuntimeException exception) {
                    if (!reported) {
                        reported = true;
                        FutaGtnhMod.LOG.warn("Cannot match shared storage NEI search {}", text, exception);
                    }
                    return false;
                }
            }
        };
    }

    @Override
    public Object configurationToken() {
        // NEI replaces this cached list after clearCache(), provider registration or language changes.
        return SearchField.searchParser.getProviders();
    }

    @Override
    public String escapedSearchText(ItemStack stack) {
        return SearchField.getEscapedSearchText(stack);
    }
}
