package com.futa_gtnh.ae2;

import java.lang.reflect.Method;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;

/**
 * Runtime compatibility helpers for Neo ECO AE Extension ({@code neoecoae}) pattern upload hooks.
 *
 * <p>
 * Keeps all optional-mod interaction behind reflection and GT types so {@code futa_gtnh} compiles and loads cleanly
 * without {@code neoecoae} or {@code programmablehatches} on the classpath.
 */
public final class NeoEcoAeUploadCompat {

    private static final String PH_UPLOAD_CLASS = "cn.dancingsnow.neoecoae.compat.proghatches.upload.ProgrammableHatchUpload";
    private static final String PH_SLAVE_CLASS = "reobf.proghatches.gt.metatileentity.DualInputHatchSlave";

    private static volatile boolean lookedUpUnwrap;
    private static Method phUnwrapMethod;

    private NeoEcoAeUploadCompat() {}

    /**
     * Unwrap any item stored inside a ProgrammableHatches {@code ItemProgrammingCircuit} (including molds, dies and
     * lenses), returning a stack of size 1 for recipe verification without treating non-circuits as 0-24 GT circuits.
     */
    public static ItemStack unwrapProgrammingItem(ItemStack stack) {
        if (stack == null) return null;
        Method unwrap = resolvePhUnwrap();
        if (unwrap == null) return null;
        try {
            Object result = unwrap.invoke(null, stack);
            if (!(result instanceof ItemStack)) return null;
            ItemStack copy = ((ItemStack) result).copy();
            copy.stackSize = 1;
            return copy;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Checks {@link GTRecipe.GTRecipe_WithAlt} ore-dict alternatives ({@code mOreDictAlt} and {@code mOreDictIds}) for
     * the slot matching {@code recipeInput}, allowing non-representative lenses and other ore-dict catalysts to match.
     */
    public static boolean matchesRecipeAltInput(GTRecipe recipe, ItemStack recipeInput, ItemStack patternItem) {
        if (!(recipe instanceof GTRecipe.GTRecipe_WithAlt) || recipe.mInputs == null
            || recipeInput == null
            || patternItem == null) {
            return false;
        }
        GTRecipe.GTRecipe_WithAlt withAlt = (GTRecipe.GTRecipe_WithAlt) recipe;
        int slotIndex = findInputSlotIndex(recipe.mInputs, recipeInput);
        if (slotIndex < 0) return false;

        if (withAlt.mOreDictAlt != null && slotIndex < withAlt.mOreDictAlt.length) {
            ItemStack[] alts = withAlt.mOreDictAlt[slotIndex];
            if (alts != null) {
                for (ItemStack alt : alts) {
                    if (alt != null && (GTUtility.areStacksEqual(alt, patternItem, true)
                        || GTUtility.areUnificationsEqual(alt, patternItem, true))) {
                        return true;
                    }
                }
            }
        }

        if (withAlt.mOreDictIds != null && slotIndex < withAlt.mOreDictIds.length) {
            int expectedOreId = withAlt.mOreDictIds[slotIndex];
            if (expectedOreId >= 0) {
                for (int id : OreDictionary.getOreIDs(patternItem)) {
                    if (id == expectedOreId) return true;
                }
            }
        }

        return false;
    }

    /**
     * Recognizes ProgrammableHatches slave dual-input hatches so pattern upload routes treat them as programmable.
     */
    public static boolean isPhSlaveProgrammingInput(Object target) {
        if (target == null) return false;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            if (PH_SLAVE_CLASS.equals(type.getName())) return true;
        }
        return false;
    }

    private static int findInputSlotIndex(ItemStack[] inputs, ItemStack recipeInput) {
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i] == recipeInput) return i;
        }
        for (int i = 0; i < inputs.length; i++) {
            if (inputs[i] != null && GTUtility.areStacksEqual(inputs[i], recipeInput, true)) return i;
        }
        return -1;
    }

    private static Method resolvePhUnwrap() {
        if (lookedUpUnwrap) return phUnwrapMethod;
        synchronized (NeoEcoAeUploadCompat.class) {
            if (lookedUpUnwrap) return phUnwrapMethod;
            try {
                Class<?> uploadClass = Class
                    .forName(PH_UPLOAD_CLASS, false, NeoEcoAeUploadCompat.class.getClassLoader());
                phUnwrapMethod = uploadClass.getMethod("unwrap", ItemStack.class);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                phUnwrapMethod = null;
            }
            lookedUpUnwrap = true;
            return phUnwrapMethod;
        }
    }
}
