package com.futa_gtnh.disassembler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import com.futa_gtnh.shared.ItemKey;

import cpw.mods.fml.relauncher.ReflectionHelper;
import gregtech.api.util.GTShapedRecipe;
import gregtech.api.util.GTShapelessRecipe;

/** Reverse the final grid, including GTNH's static universal recipes, without refunding crafting tools. */
final class DisassemblyCrafting {

    private static final Field ORE_WIDTH = ReflectionHelper.findField(ShapedOreRecipe.class, "width");

    private DisassemblyCrafting() {}

    static DisassemblyRecipe reverse(IRecipe recipe) {
        List<?> inputs;
        int width = 3;
        Class<?> type = recipe.getClass();
        String name = type.getName();
        if (type == ShapedRecipes.class) {
            inputs = Arrays.asList(((ShapedRecipes) recipe).recipeItems);
            width = ((ShapedRecipes) recipe).recipeWidth;
        } else if (type == ShapelessRecipes.class) inputs = ((ShapelessRecipes) recipe).recipeItems;
        else if (type == ShapedOreRecipe.class || type == GTShapedRecipe.class
            || name.equals("com.dreammaster.recipes.ShapedUniversalRecipe")) {
                if (recipe instanceof GTShapedRecipe && ((GTShapedRecipe) recipe).mKeepingNBT)
                    throw new IllegalArgumentException("Dynamic NBT");
                inputs = Arrays.asList(((ShapedOreRecipe) recipe).getInput());
                try {
                    width = ORE_WIDTH.getInt(recipe);
                } catch (IllegalAccessException exception) {
                    throw new IllegalArgumentException("Cannot read crafting grid width", exception);
                }
            } else if (type == ShapelessOreRecipe.class || type == GTShapelessRecipe.class
                || name.equals("com.dreammaster.recipes.ShapelessUniversalRecipe")) {
                    if (recipe instanceof GTShapelessRecipe
                        && (((GTShapelessRecipe) recipe).mKeepingNBT || ((GTShapelessRecipe) recipe).overwriteNBT))
                        throw new IllegalArgumentException("Dynamic NBT");
                    inputs = ((ShapelessOreRecipe) recipe).getInput();
                } else throw new IllegalArgumentException("Unsupported dynamic/custom recipe");
        if (width < 1 || width > 3 || inputs.size() > width * 3)
            throw new IllegalArgumentException("Non-standard crafting grid");
        InventoryCrafting grid = new InventoryCrafting(new Container() {

            @Override
            public boolean canInteractWith(EntityPlayer player) {
                return false;
            }
        }, 3, 3);
        List<ItemStack> items = new ArrayList<>();
        for (int slot = 0; slot < inputs.size(); slot++) {
            Object ingredient = inputs.get(slot);
            ItemStack tool = DisassemblyIngredients.craftingTool(ingredient);
            ItemStack material = tool == null ? DisassemblyIngredients.resolve(ingredient) : null;
            // Match the declared fixed input, even when its refund is a different unified GT item.
            ItemStack stack = tool != null ? tool
                : ingredient instanceof ItemStack ? ((ItemStack) ingredient).copy()
                    : material == null ? null : material.copy();
            if (stack != null) {
                stack.stackSize = 1;
                if (material != null) {
                    material.stackSize = 1;
                    items.add(material);
                }
            }
            grid.setInventorySlotContents(slot / width * 3 + slot % width, stack);
        }
        if (!recipe.matches(grid, null)) throw new IllegalArgumentException("Resolved crafting grid does not match");
        ItemStack output = recipe.getRecipeOutput();
        ItemStack actual = recipe.getCraftingResult(grid);
        if (actual == null || actual.stackSize != output.stackSize
            || !ItemKey.of(output)
                .equals(ItemKey.of(actual)))
            throw new IllegalArgumentException("Crafting result changes NBT or count");
        return new DisassemblyRecipe(output, items, Collections.emptyList(), "crafting:" + name);
    }
}
