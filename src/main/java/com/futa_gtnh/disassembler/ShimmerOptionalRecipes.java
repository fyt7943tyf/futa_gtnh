package com.futa_gtnh.disassembler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.Loader;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;

/** Optional GTNL overrides and Intergalactic source; no extra required mods or changes to their maps. */
final class ShimmerOptionalRecipes {

    private ShimmerOptionalRecipes() {}

    static RecipeMap<?> spaceAssembler() {
        return recipeMap("gtnhintergalactic", "gtnhintergalactic.recipe.IGRecipeMaps", "spaceAssemblerRecipes");
    }

    private static RecipeMap<?> recipeMap(String mod, String type, String field) {
        if (!Loader.isModLoaded(mod)) return null;
        try {
            return (RecipeMap<?>) Class.forName(type)
                .getField(field)
                .get(null);
        } catch (ReflectiveOperationException exception) {
            FutaGtnhMod.LOG.warn("Cannot read optional Shimmer recipe map {}.{}", type, field, exception);
            return null;
        }
    }

    static void loadOverrides(DisassemblyCatalog.Builder builder) {
        if (!Loader.isModLoaded("sciencenotleisure")) return;
        // GTNL has normally consumed/cleared its override map already. Its conversion map retains
        // those overrides and preserves their original matching order (including NBT and batch size).
        try {
            Map<?, ?> conversions = (Map<?, ?>) Class.forName("com.science.gtnl.common.recipe.gtnl.ShimmerRecipes")
                .getField("conversionMap")
                .get(null);
            for (Object group : conversions.values()) for (Object entry : (Iterable<?>) group) {
                ItemStack input = (ItemStack) entry.getClass()
                    .getMethod("input")
                    .invoke(entry);
                List<ItemStack> items = new ArrayList<>();
                List<FluidStack> fluids = new ArrayList<>();
                for (Object value : (Iterable<?>) entry.getClass()
                    .getMethod("outputs")
                    .invoke(entry)) {
                    ItemStack stack = (ItemStack) value;
                    if (stack.getItem()
                        .getClass()
                        .getName()
                        .equals("com.glodblock.github.common.item.ItemFluidPacket")) {
                        FluidStack fluid = (FluidStack) stack.getItem()
                            .getClass()
                            .getMethod("getFluidStack", ItemStack.class)
                            .invoke(null, stack);
                        if (fluid != null) {
                            fluid = fluid.copy();
                            fluid.amount = Math.multiplyExact(fluid.amount, stack.stackSize);
                            fluids.add(fluid);
                        }
                    } else items.add(stack);
                }
                builder.register(input, items, fluids, "GTNL Shimmer");
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            FutaGtnhMod.LOG.warn("Cannot import GTNL Shimmer conversions", exception);
        }
        RecipeMap<?> overrides = recipeMap(
            "sciencenotleisure",
            "com.science.gtnl.common.material.GTNLRecipeMaps",
            "HardOverrideRecipes");
        if (overrides == null) return;
        for (GTRecipe recipe : overrides.getAllRecipes()) {
            if (recipe == null || recipe.mOutputs == null || recipe.mOutputs.length == 0) continue;
            builder.register(
                recipe.mOutputs[0],
                recipe.mInputs == null ? new ArrayList<>() : java.util.Arrays.asList(recipe.mInputs),
                DisassemblyCatalog.fluids(recipe.mFluidInputs),
                "GTNL hard override");
        }
    }
}
