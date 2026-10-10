package com.dreammaster.recipes;

import java.util.ArrayList;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.ShapelessOreRecipe;

/** Test fixture for NHCore's separate ingredient list, rather than the parent's empty input. */
public class ShapelessUniversalRecipe extends ShapelessOreRecipe {
    private final ShapelessOreRecipe delegate;
    public ShapelessUniversalRecipe(ItemStack output, Object... recipe) {
        super(output);
        delegate = new ShapelessOreRecipe(output, recipe);
    }
    @Override public ArrayList<Object> getInput() { return delegate.getInput(); }
    @Override public boolean matches(net.minecraft.inventory.InventoryCrafting grid, net.minecraft.world.World world) {
        return delegate.matches(grid, world);
    }
}
