package com.futa_gtnh.client.nei;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.futa_gtnh.disassembler.DisassemblerRegistration;
import com.futa_gtnh.disassembler.DisassemblyCatalog;
import com.futa_gtnh.disassembler.DisassemblyRecipe;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

import codechicken.nei.PositionedStack;
import codechicken.nei.api.API;
import codechicken.nei.recipe.TemplateRecipeHandler;
import gregtech.api.util.GTUtility;

/** Each NEI entry is one numbered page of the same complete batch, never a separate machine operation. */
public final class DisassemblerRecipeHandler extends TemplateRecipeHandler {

    public static final String ID = "futa_gtnh.disassembly";

    public static void register() {
        DisassemblerRecipeHandler handler = new DisassemblerRecipeHandler();
        API.registerRecipeHandler(handler);
        API.registerUsageHandler(handler);
        API.addRecipeCatalyst(DisassemblerRegistration.stack(), handler);
    }

    @Override
    public String getRecipeName() {
        return StatCollector.translateToLocal("futa_gtnh.disassembler.name");
    }

    @Override
    public String getGuiTexture() {
        return "";
    }

    @Override
    public String getOverlayIdentifier() {
        return ID;
    }

    @Override
    public int getRecipeHeight(int recipe) {
        return 140;
    }

    @Override
    public int recipiesPerPage() {
        return 1;
    }

    @Override
    public void drawBackground(int recipe) {
        Gui.drawRect(0, 0, 166, 140, 0xffc6c6c6);
        Page page = (Page) arecipes.get(recipe);
        slot(page.getIngredient());
        for (PositionedStack output : page.outputs) slot(output);
    }

    private void slot(PositionedStack stack) {
        Gui.drawRect(stack.relx - 1, stack.rely - 1, stack.relx + 17, stack.rely + 17, 0xff888888);
    }

    @Override
    public void drawExtras(int recipe) {
        Page page = (Page) arecipes.get(recipe);
        Minecraft.getMinecraft().fontRenderer.drawString("32 EU/t · LV 1A · 2s", 4, 118, 0x404040);
        Minecraft.getMinecraft().fontRenderer.drawString(
            StatCollector.translateToLocalFormatted("futa_gtnh.disassembler.nei_page", page.page + 1, page.pages),
            4,
            130,
            0x404040);
    }

    @Override
    public void loadCraftingRecipes(String id, Object... results) {
        if (ID.equals(id)) {
            for (DisassemblyRecipe recipe : DisassemblyCatalog.all()) add(recipe);
        } else super.loadCraftingRecipes(id, results);
    }

    @Override
    public void loadCraftingRecipes(ItemStack result) {
        ItemKey key = ItemKey.of(result);
        FluidKey fluidKey = FluidKey.of(GTUtility.getFluidFromDisplayStack(result));
        for (DisassemblyRecipe recipe : DisassemblyCatalog.all()) {
            boolean matches = recipe.itemOutputs()
                .stream()
                .anyMatch(stack -> key.equals(ItemKey.of(stack)))
                || recipe.fluidOutputs()
                    .stream()
                    .anyMatch(
                        fluid -> FluidKey.of(fluid)
                            .equals(fluidKey));
            if (matches) add(recipe);
        }
    }

    @Override
    public void loadUsageRecipes(ItemStack ingredient) {
        if (ItemKey.of(ingredient)
            .equals(ItemKey.of(DisassemblerRegistration.stack()))) {
            for (DisassemblyRecipe recipe : DisassemblyCatalog.all()) add(recipe);
        } else {
            DisassemblyRecipe recipe = DisassemblyCatalog.find(ingredient);
            if (recipe != null) add(recipe);
        }
    }

    private void add(DisassemblyRecipe recipe) {
        List<ItemStack> items = recipe.itemOutputs();
        List<ItemStack> fluids = new ArrayList<>();
        recipe.fluidOutputs()
            .forEach(fluid -> fluids.add(GTUtility.getFluidDisplayStack(fluid, true)));
        int pages = Math.max(1, Math.max((items.size() + 27) / 28, (fluids.size() + 6) / 7));
        for (int page = 0; page < pages; page++) arecipes.add(new Page(recipe, items, fluids, page, pages));
    }

    private final class Page extends CachedRecipe {

        final PositionedStack input;
        final List<PositionedStack> outputs = new ArrayList<>();
        final int page, pages;

        Page(DisassemblyRecipe recipe, List<ItemStack> items, List<ItemStack> fluids, int page, int pages) {
            this.page = page;
            this.pages = pages;
            input = new PositionedStack(recipe.input.prototype(recipe.inputCount), 3, 24, false);
            for (int offset = 0; offset < 28 && page * 28 + offset < items.size(); offset++) outputs.add(
                new PositionedStack(items.get(page * 28 + offset), 31 + offset % 7 * 18, 6 + offset / 7 * 18, false));
            for (int offset = 0; offset < 7 && page * 7 + offset < fluids.size(); offset++)
                outputs.add(new PositionedStack(fluids.get(page * 7 + offset), 31 + offset * 18, 92, false));
        }

        @Override
        public PositionedStack getIngredient() {
            return input;
        }

        @Override
        public List<PositionedStack> getIngredients() {
            return Collections.singletonList(input);
        }

        @Override
        public PositionedStack getResult() {
            return outputs.isEmpty() ? null : outputs.get(0);
        }

        @Override
        public List<PositionedStack> getOtherStacks() {
            return outputs.size() <= 1 ? Collections.emptyList() : outputs.subList(1, outputs.size());
        }
    }
}
