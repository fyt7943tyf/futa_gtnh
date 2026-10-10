package com.futa_gtnh.disassembler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.ItemKey;

import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTRecipe;

/** Snapshot of final crafting, assembler and real assembly-line recipes. Unsafe routes block a product. */
public final class DisassemblyCatalog {

    private static Map<ItemKey, DisassemblyRecipe> recipes = Collections.emptyMap();
    public static int itemSlots = 36;
    public static int fluidSlots = 8;
    public static int tankCapacity = 64000;

    private DisassemblyCatalog() {}

    public static DisassemblyRecipe find(ItemStack input) {
        return recipes.get(ItemKey.of(input));
    }

    public static List<DisassemblyRecipe> all() {
        return new ArrayList<>(recipes.values());
    }

    public static void build(File reportFile) {
        Builder builder = new Builder();
        for (Object object : CraftingManager.getInstance()
            .getRecipeList()) {
            if (!(object instanceof IRecipe)) continue;
            IRecipe recipe = (IRecipe) object;
            ItemStack output = recipe.getRecipeOutput();
            if (output == null) continue;
            String source = "crafting:" + recipe.getClass()
                .getName();
            try {
                builder.add(DisassemblyCrafting.reverse(recipe));
            } catch (IllegalArgumentException | ArithmeticException exception) {
                builder.block(output, source, exception.getMessage());
            }
        }
        for (GTRecipe recipe : RecipeMaps.assemblerRecipes.getAllRecipes()) {
            if (!recipe.mEnabled || recipe.mFakeRecipe) continue;
            if (recipe.mOutputs.length != 1) {
                for (ItemStack output : recipe.mOutputs)
                    if (output != null) builder.block(output, "assembler", "Multiple production outputs");
                continue;
            }
            if (recipe.mOutputs[0] == null) continue;
            ItemStack output = recipe.mOutputs[0];
            try {
                if (recipe.getClass() != GTRecipe.class || recipe.mFluidOutputs.length > 0
                    || recipe.getOutputChance(0) != 10000)
                    throw new IllegalArgumentException("Custom, secondary or chance output");
                for (int i = 0; i < recipe.mInputs.length; i++)
                    if (recipe.getInputChance(i) != 10000) throw new IllegalArgumentException("Chance input");
                for (int i = 0; i < recipe.mFluidInputs.length; i++) {
                    if (recipe.getFluidInputChance(i) != 10000)
                        throw new IllegalArgumentException("Chance fluid input");
                    if (recipe.mAltFluidInputs != null && i < recipe.mAltFluidInputs.length
                        && recipe.mAltFluidInputs[i] != null) {
                        for (FluidStack alternative : recipe.mAltFluidInputs[i])
                            if (alternative != null && !alternative.isFluidEqual(recipe.mFluidInputs[i]))
                                throw new IllegalArgumentException("Alternative fluids");
                    }
                }
                builder.add(
                    new DisassemblyRecipe(
                        output,
                        normalized(recipe.mInputs),
                        Arrays.asList(recipe.mFluidInputs),
                        "assembler"));
            } catch (IllegalArgumentException | ArithmeticException exception) {
                builder.block(output, "assembler", exception.getMessage());
            }
        }
        for (GTRecipe.RecipeAssemblyLine recipe : GTRecipe.RecipeAssemblyLine.sAssemblylineRecipes) {
            if (recipe.mOutput == null) continue;
            try {
                List<ItemStack> items = new ArrayList<>();
                for (int i = 0; i < recipe.mInputs.length; i++) {
                    ItemStack input = recipe.mInputs[i];
                    if (recipe.mOreDictAlt != null && i < recipe.mOreDictAlt.length
                        && recipe.mOreDictAlt[i] != null
                        && recipe.mOreDictAlt[i].length > 0) {
                        input = DisassemblyIngredients.resolve(Arrays.asList(recipe.mOreDictAlt[i]));
                    }
                    items.add(DisassemblyIngredients.normalize(input));
                }
                builder.add(
                    new DisassemblyRecipe(recipe.mOutput, items, Arrays.asList(recipe.mFluidInputs), "assembly line"));
            } catch (IllegalArgumentException | ArithmeticException exception) {
                builder.block(recipe.mOutput, "assembly line", exception.getMessage());
            }
        }
        recipes = Collections.unmodifiableMap(builder.finish());
        itemSlots = 36;
        fluidSlots = 8;
        tankCapacity = 64000;
        for (DisassemblyRecipe recipe : recipes.values()) {
            itemSlots = Math.max(itemSlots, recipe.requiredItemSlots());
            List<FluidStack> fluids = recipe.fluidOutputs();
            fluidSlots = Math.max(fluidSlots, fluids.size());
            for (FluidStack fluid : fluids) tankCapacity = Math.max(tankCapacity, fluid.amount);
        }
        String summary = "LV disassembler: candidates=" + builder.candidates
            + ", accepted="
            + recipes.size()
            + ", blocked products="
            + builder.blocked.size()
            + ", output slots="
            + itemSlots
            + ", tanks="
            + fluidSlots
            + ", capacity="
            + tankCapacity
            + " mB; 32 EU/t, 40 ticks, one step";
        FutaGtnhMod.LOG.info(summary);
        builder.report.add(0, summary);
        Map<String, Integer> acceptedSources = new LinkedHashMap<>();
        for (DisassemblyRecipe recipe : recipes.values()) acceptedSources.merge(recipe.source, 1, Integer::sum);
        builder.report.add(1, "Parsed candidates by source: " + builder.candidateSources);
        builder.report.add(2, "Accepted representative routes by source: " + acceptedSources);
        builder.report.add(3, "Skipped routes by source: " + builder.skippedSources);
        for (DisassemblyRecipe recipe : recipes.values()) builder.report.add(
            "ACCEPT " + recipe.input
                + " x"
                + recipe.inputCount
                + " <- "
                + recipe.source
                + "; items="
                + recipe.itemOutputs()
                    .stream()
                    .map(stack -> ItemKey.of(stack) + " x" + stack.stackSize)
                    .collect(java.util.stream.Collectors.joining(", ")));
        try {
            Files.write(reportFile.toPath(), builder.report, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            FutaGtnhMod.LOG.warn("Cannot write disassembler report", exception);
        }
    }

    private static List<ItemStack> normalized(ItemStack[] inputs) {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack input : inputs) result.add(DisassemblyIngredients.normalize(input));
        return result;
    }

    public static final class Builder {

        private final Map<ItemKey, DisassemblyRecipe> candidatesByItem = new LinkedHashMap<>();
        private final Set<ItemKey> blocked = new LinkedHashSet<>();
        private final List<String> report = new ArrayList<>();
        private final Map<String, Integer> candidateSources = new LinkedHashMap<>();
        private final Map<String, Integer> skippedSources = new LinkedHashMap<>();
        private int candidates;

        public void add(DisassemblyRecipe recipe) {
            candidates++;
            candidateSources.merge(recipe.source, 1, Integer::sum);
            DisassemblyRecipe previous = candidatesByItem.putIfAbsent(recipe.input, recipe);
            if (previous != null && !previous.sameMaterials(recipe))
                block(recipe.input.prototype(), recipe.source, "Conflicting production routes");
        }

        public void block(ItemStack output, String source, String reason) {
            ItemKey key = ItemKey.of(output);
            if (key != null) {
                skippedSources.merge(source, 1, Integer::sum);
                blocked.add(key);
                report.add("SKIP " + key + " <- " + source + ": " + reason);
            }
        }

        public Map<ItemKey, DisassemblyRecipe> finish() {
            Map<ItemKey, DisassemblyRecipe> result = new LinkedHashMap<>(candidatesByItem);
            for (ItemKey key : blocked) result.remove(key);
            return result;
        }
    }
}
