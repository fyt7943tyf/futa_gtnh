/*
 * Shimmer route ordering/generation adapted from GT-Not-Leisure, LGPL-3.0.
 * Upstream: ABKQPO/GT-Not-Leisure @ c9614667efbbcbc5b9eed89c76807caeea4b1e5e.
 * Changes: machine batches, native fluid outputs, optional mod bridges and reporting.
 * See META-INF/licenses/gtnl/NOTICE.txt.
 */
package com.futa_gtnh.disassembler;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.ItemKey;

import gregtech.api.objects.GTItemStack;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/** Shimmer conversions, executed one original production batch at a time by the LV machine. */
public final class DisassemblyCatalog {

    private static Map<Item, List<DisassemblyRecipe>> recipes = Collections.emptyMap();
    private static volatile boolean ready;

    public static boolean isReady() {
        return ready;
    }

    public static int itemSlots = 36;
    public static int fluidSlots = 8;
    public static int tankCapacity = 64000;
    public static int maxBatchSize = 64;

    private DisassemblyCatalog() {}

    public static DisassemblyRecipe find(ItemStack input) {
        if (input == null) return null;
        for (DisassemblyRecipe recipe : recipes.getOrDefault(input.getItem(), Collections.emptyList())) {
            if (recipe.matches(input, false, true)) return recipe;
        }
        return null;
    }

    /** Oversized batches collect legal input stacks privately instead of overflowing GUI packets. */
    public static DisassemblyRecipe collectable(ItemStack input) {
        if (input == null) return null;
        for (DisassemblyRecipe recipe : recipes.getOrDefault(input.getItem(), Collections.emptyList()))
            if (recipe.inputCount > Math.min(64, input.getMaxStackSize()) && recipe.matches(input, false, false))
                return recipe;
        return null;
    }

    /** NEI queries usually carry one item; show every applicable batch regardless of query quantity. */
    public static List<DisassemblyRecipe> usages(ItemStack input) {
        if (input == null) return Collections.emptyList();
        return recipes.getOrDefault(input.getItem(), Collections.emptyList())
            .stream()
            .filter(recipe -> recipe.matches(input, false, false))
            .collect(Collectors.toList());
    }

    public static List<DisassemblyRecipe> all() {
        return recipes.values()
            .stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    public static void build(File reportFile) {
        ShimmerDisassemblyRules.initializeBlacklist();
        Builder builder = new Builder();
        ShimmerOptionalRecipes.loadOverrides(builder);
        loadAssemblers(builder, RecipeMaps.assemblerRecipes.getAllRecipes());
        loadRawRecipes(builder, RecipeMaps.assemblylineVisualRecipes.getAllRecipes(), "assembly line visual");
        RecipeMap<?> space = ShimmerOptionalRecipes.spaceAssembler();
        if (space != null) loadRawRecipes(builder, space.getAllRecipes(), "space assembler");
        for (ShimmerCraftingRegistry.Entry entry : ShimmerCraftingRegistry.snapshot()) {
            if (!ShimmerDisassemblyRules.shouldDisassembleItemStack(entry.output) || builder.contains(entry.output))
                continue;
            try {
                entry.reverse()
                    .ifPresent(
                        reverse -> builder.register(
                            entry.output,
                            ShimmerDisassemblyRules.handleRecipeTransformation(reverse.mOutputs, null),
                            Collections.emptyList(),
                            "GT crafting"));
            } catch (RuntimeException exception) {
                builder.skip(entry.output, "GT crafting", exception.toString());
            }
        }
        publish(builder.finish());
        String summary = "LV disassembler (Shimmer): accepted=" + all().size()
            + ", captured GT crafting="
            + ShimmerCraftingRegistry.snapshot()
                .size()
            + ", output slots="
            + itemSlots
            + ", tanks="
            + fluidSlots
            + ", capacity="
            + tankCapacity
            + " mB, largest batch="
            + maxBatchSize
            + "; 32 EU/t, 40 ticks, one step";
        FutaGtnhMod.LOG.info(summary);
        builder.report.add(0, summary);
        Map<String, Integer> sources = new LinkedHashMap<>();
        for (DisassemblyRecipe recipe : all()) {
            sources.merge(recipe.source, 1, Integer::sum);
            builder.report.add(
                "ACCEPT " + recipe.input
                    + " x"
                    + recipe.inputCount
                    + " <- "
                    + recipe.source
                    + "; items="
                    + recipe.itemOutputs()
                        .stream()
                        .map(stack -> ItemKey.of(stack) + " x" + stack.stackSize)
                        .collect(Collectors.joining(", "))
                    + "; fluids="
                    + recipe.fluidOutputs()
                        .stream()
                        .map(
                            fluid -> fluid.getFluid()
                                .getName() + " x"
                                + fluid.amount)
                        .collect(Collectors.joining(", ")));
        }
        builder.report.add(1, "Accepted routes by source: " + sources);
        builder.report.add(2, "Failed routes=" + builder.failures + "; failures do not invalidate other conversions");
        try {
            Files.write(reportFile.toPath(), builder.report, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            FutaGtnhMod.LOG.warn("Cannot write disassembler report", exception);
        }
    }

    static void publish(List<DisassemblyRecipe> entries) {
        Map<Item, List<DisassemblyRecipe>> byItem = new LinkedHashMap<>();
        itemSlots = 36;
        fluidSlots = 8;
        tankCapacity = 64000;
        maxBatchSize = 64;
        for (DisassemblyRecipe recipe : entries) {
            byItem.computeIfAbsent(recipe.input.getItem(), item -> new ArrayList<>())
                .add(recipe);
            itemSlots = Math.max(itemSlots, recipe.requiredItemSlots());
            fluidSlots = Math.max(
                fluidSlots,
                recipe.fluidOutputs()
                    .size());
            for (FluidStack fluid : recipe.fluidOutputs()) tankCapacity = Math.max(tankCapacity, fluid.amount);
            maxBatchSize = Math.max(maxBatchSize, recipe.inputCount);
        }
        byItem.replaceAll((item, routes) -> Collections.unmodifiableList(routes));
        recipes = Collections.unmodifiableMap(byItem);
        ready = true;
    }

    static void loadAssemblers(Builder builder, Iterable<GTRecipe> originals) {
        Object2ObjectArrayMap<GTItemStack, ObjectArrayList<GTRecipe>> groups = new Object2ObjectArrayMap<>();
        for (GTRecipe recipe : originals) {
            if (recipe.mOutputs == null || recipe.mInputs == null
                || !ShimmerDisassemblyRules.shouldDisassemble(recipe.mOutputs)) continue;
            if (builder.contains(recipe.mOutputs[0])) continue;
            groups.computeIfAbsent(new GTItemStack(recipe.mOutputs[0]), key -> new ObjectArrayList<>())
                .add(recipe);
        }
        for (ObjectArrayList<GTRecipe> routes : groups.values()) {
            GTRecipe first = routes.get(0);
            try {
                ObjectOpenHashSet<ItemStack[]> alternatives = new ObjectOpenHashSet<>();
                for (GTRecipe route : routes) alternatives.add(route.mInputs);
                builder.register(
                    first.mOutputs[0],
                    ShimmerDisassemblyRules.handleRecipeTransformation(first.mInputs, alternatives),
                    fluids(first.mFluidInputs),
                    "assembler");
            } catch (RuntimeException exception) {
                builder.skip(first.mOutputs[0], "assembler", exception.toString());
            }
        }
    }

    static void loadRawRecipes(Builder builder, Iterable<GTRecipe> originals, String source) {
        for (GTRecipe recipe : originals) {
            if (recipe == null || recipe.mOutputs == null
                || recipe.mOutputs.length == 0
                || builder.contains(recipe.mOutputs[0])) continue;
            builder.register(
                recipe.mOutputs[0],
                recipe.mInputs == null ? Collections.emptyList() : Arrays.asList(recipe.mInputs),
                fluids(recipe.mFluidInputs),
                source);
        }
    }

    static List<FluidStack> fluids(FluidStack[] fluids) {
        return fluids == null ? Collections.emptyList() : Arrays.asList(fluids);
    }

    public static final class Builder {

        private final List<DisassemblyRecipe> entries = new ArrayList<>();
        private final Map<Item, List<DisassemblyRecipe>> byItem = new LinkedHashMap<>();
        private final List<String> report = new ArrayList<>();
        private int failures;

        public void add(DisassemblyRecipe recipe) {
            entries.add(recipe);
            byItem.computeIfAbsent(recipe.input.getItem(), item -> new ArrayList<>())
                .add(recipe);
        }

        public boolean contains(ItemStack input) {
            if (input == null) return false;
            for (DisassemblyRecipe entry : byItem.getOrDefault(input.getItem(), Collections.emptyList()))
                if (entry.matches(input, true, true)) return true;
            return false;
        }

        public void register(ItemStack input, List<ItemStack> items, List<FluidStack> fluids, String source) {
            if (!GTUtility.isStackValid(input) || input.stackSize <= 0) return;
            try {
                add(new DisassemblyRecipe(input, items, fluids, source));
            } catch (IllegalArgumentException | ArithmeticException exception) {
                skip(input, source, exception.toString());
            }
        }

        public void skip(ItemStack input, String source, String reason) {
            failures++;
            report.add("SKIP " + ItemKey.of(input) + " <- " + source + ": " + reason);
        }

        public List<DisassemblyRecipe> finish() {
            return new ArrayList<>(entries);
        }
    }
}
