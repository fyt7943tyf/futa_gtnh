/*
 * Adapted from GTNL ReversedRecipeRegistry and GT recipe constructor mixins, LGPL-3.0.
 * Upstream: ABKQPO/GT-Not-Leisure @ c9614667efbbcbc5b9eed89c76807caeea4b1e5e.
 * Changes: copied inputs, compact records, machine catalog consumer, no debug stack traces.
 * See META-INF/licenses/gtnl/NOTICE.txt.
 */
package com.futa_gtnh.disassembler;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.minecraft.item.ItemStack;

import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.common.blocks.ItemMachines;

/** Captures raw GT machine recipes before ore alternatives/shape/NBT flags are resolved. */
public final class ShimmerCraftingRegistry {

    private static final List<Entry> RECIPES = new ArrayList<>();

    private ShimmerCraftingRegistry() {}

    public static void capture(ItemStack output, Object[] raw, boolean shaped) {
        if (output == null || !(output.getItem() instanceof ItemMachines)) return;
        RECIPES.add(new Entry(output, raw, shaped));
    }

    public static List<Entry> snapshot() {
        return new ArrayList<>(RECIPES);
    }

    public static final class Entry {

        public final ItemStack output;
        private final Object[] raw;
        private final boolean shaped;

        public Entry(ItemStack output, Object[] raw, boolean shaped) {
            this.output = output.copy();
            this.shaped = shaped;
            List<Object> filtered = new ArrayList<>();
            for (Object value : raw) {
                if (value instanceof String && ((String) value).startsWith("craftingTool")) continue;
                filtered.add(value instanceof ItemStack ? ((ItemStack) value).copy() : value);
            }
            this.raw = filtered.toArray();
        }

        public Optional<GTRecipe> reverse() {
            return shaped ? GTUtility.reverseShapedRecipe(output.copy(), raw)
                : GTUtility.reverseShapelessRecipe(output.copy(), raw);
        }
    }
}
