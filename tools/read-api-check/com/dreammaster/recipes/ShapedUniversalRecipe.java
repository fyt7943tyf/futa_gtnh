package com.dreammaster.recipes;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.ShapedOreRecipe;

/** Test fixture for NHCore's audited static getInput override and its unused parent placeholder grid. */
public class ShapedUniversalRecipe extends ShapedOreRecipe {
    private final Object[] inputs;
    public ShapedUniversalRecipe(ItemStack output, Object... recipe) {
        super(output, "xxx", "xxx", "xxx", 'x', Blocks.fire);
        inputs = new ShapedOreRecipe(output, recipe).getInput();
    }
    @Override public Object[] getInput() { return inputs; }
    // The full grid used here has ordinary inputs, so reproduce NHCore's final matching via the real Forge matcher.
    @Override public boolean matches(net.minecraft.inventory.InventoryCrafting grid, net.minecraft.world.World world) {
        ShapedOreRecipe matcher = new ShapedOreRecipe(getRecipeOutput(), "abc", "def", "ghi",
            'a', inputs[0], 'b', inputs[1], 'c', inputs[2], 'd', inputs[3], 'e', inputs[4],
            'f', inputs[5], 'g', inputs[6], 'h', inputs[7], 'i', inputs[8]);
        return matcher.matches(grid, world);
    }
}
