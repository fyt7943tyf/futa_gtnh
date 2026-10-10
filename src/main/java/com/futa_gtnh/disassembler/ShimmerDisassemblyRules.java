/*
 * Adapted from GT-Not-Leisure's DisassemblerHelper, LGPL-3.0.
 * Upstream: ABKQPO/GT-Not-Leisure @ c9614667efbbcbc5b9eed89c76807caeea4b1e5e.
 * Changes: optional-mod guards/reflection and copies of forward ingredients.
 * See META-INF/licenses/gtnl/NOTICE.txt and the accompanying source archive.
 */
package com.futa_gtnh.disassembler;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.FutaGtnhMod;

import appeng.api.AEApi;
import appeng.api.util.AEColor;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.Mods;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.items.MetaGeneratedTool;
import gregtech.api.objects.GTItemStack;
import gregtech.api.objects.ItemData;
import gregtech.api.recipe.RecipeMaps;
import gregtech.api.util.GTModHandler;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.api.util.GTUtility;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/** Material rules and automatic-input exclusions from Shimmer, shared by all generated routes. */
public final class ShimmerDisassemblyRules {

    public static final ObjectArrayList<GTItemStack> inputBlacklist = new ObjectArrayList<>();

    private ShimmerDisassemblyRules() {}

    public static void initializeBlacklist() {
        inputBlacklist.clear();
        for (ItemList item : new ItemList[] { ItemList.Casing_Coil_Superconductor, ItemList.Circuit_Parts_Vacuum_Tube,
            ItemList.Schematic, ItemList.ZPM, ItemList.Transformer_MV_LV, ItemList.Transformer_HV_MV,
            ItemList.Transformer_EV_HV, ItemList.Transformer_IV_EV }) {
            if (item.hasBeenSet()) blacklist(item.get(1));
        }
        blacklist(Materials.Graphene.getDust(1));
        if (Mods.TecTech.isModLoaded())
            blacklist(optionalEnumStack("tectech.thing.CustomItemList", "hatch_CreativeMaintenance"));
        if (Mods.Railcraft.isModLoaded()) {
            for (int damage : new int[] { 0, 736, 816 })
                blacklist(GTModHandler.getModItem(Mods.Railcraft.ID, "track", 1, damage));
            blacklist(GTModHandler.getModItem(Mods.Railcraft.ID, "machine.alpha", 1, 14));
        }
        if (Mods.IndustrialCraft2.isModLoaded()) {
            blacklist(ic2.api.item.IC2Items.getItem("mixedMetalIngot"));
            for (int damage = 3; damage <= 6; damage++)
                blacklist(GTModHandler.getModItem(Mods.IndustrialCraft2.ID, "blockElectric", 1, damage));
        }
        if (Mods.AppliedEnergistics2.isModLoaded()) {
            var parts = AEApi.instance()
                .definitions()
                .parts();
            blacklist(
                parts.craftingTerminal()
                    .maybeStack(1)
                    .orNull());
            blacklist(
                parts.cableDense()
                    .stack(AEColor.Transparent, 1));
        }
        if (Mods.GoodGenerator.isModLoaded())
            blacklist(GTModHandler.getModItem(Mods.GoodGenerator.ID, "radiationProtectionPlate", 1, 0));
    }

    private static void blacklist(ItemStack stack) {
        if (GTUtility.isStackValid(stack)) inputBlacklist.add(new GTItemStack(stack));
    }

    private static ItemStack optionalEnumStack(String className, String fieldName) {
        try {
            Object entry = Class.forName(className)
                .getField(fieldName)
                .get(null);
            for (Method method : entry.getClass()
                .getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (!method.getName()
                    .equals("get") || params.length == 0 || (params[0] != long.class && params[0] != int.class))
                    continue;
                Object amount = params[0] == int.class ? (Object) Integer.valueOf(1) : Long.valueOf(1);
                if (params.length == 1) return (ItemStack) method.invoke(entry, amount);
                if (params.length == 2 && params[1].isArray())
                    return (ItemStack) method.invoke(entry, amount, Array.newInstance(params[1].getComponentType(), 0));
            }
            throw new NoSuchMethodException(className + ".get");
        } catch (ReflectiveOperationException exception) {
            FutaGtnhMod.LOG.warn("Cannot resolve optional Shimmer item {}.{}", className, fieldName, exception);
            return null;
        }
    }

    public static ObjectList<ItemStack> handleRecipeTransformation(ItemStack[] outputs,
        ObjectOpenHashSet<ItemStack[]> outputsInOtherRecipes) {
        ItemStack[] retOutputs = new ItemStack[outputs.length];

        for (int idx = 0; idx < outputs.length; idx++) {
            ItemStack itemInSlotIdx = outputs[idx] == null ? null : outputs[idx].copy();
            ItemData itemDataInSlotIdx = GTOreDictUnificator.getItemData(itemInSlotIdx);

            if (itemDataInSlotIdx == null || itemDataInSlotIdx.mMaterial == null
                || itemDataInSlotIdx.mMaterial.mMaterial == null
                || itemDataInSlotIdx.mPrefix == null) {
                retOutputs[idx] = itemInSlotIdx;
                continue;
            }

            Materials thisMaterial = itemDataInSlotIdx.mMaterial.mMaterial;

            if (outputsInOtherRecipes != null) {
                for (ItemStack[] otherOutputs : outputsInOtherRecipes) {
                    if (idx >= otherOutputs.length) continue;

                    ItemData dataAgainst = GTOreDictUnificator.getItemData(otherOutputs[idx]);
                    if (dataAgainst != null && dataAgainst.mMaterial != null
                        && dataAgainst.mMaterial.mMaterial != null
                        && dataAgainst.mPrefix == itemDataInSlotIdx.mPrefix) {

                        // 1. replace cheaper
                        Materials cheaper = replaceCheaperOrNull(thisMaterial, dataAgainst.mMaterial.mMaterial);
                        if (cheaper != null) {
                            retOutputs[idx] = GTOreDictUnificator.get(
                                OrePrefixes.getPrefix(itemDataInSlotIdx.mPrefix.getName()),
                                cheaper,
                                itemInSlotIdx.stackSize);
                            continue;
                        }

                        // 2. replace "Any" material
                        Materials nonAny = replaceAnyOrNull(thisMaterial);
                        if (nonAny != null) {
                            retOutputs[idx] = GTOreDictUnificator.get(
                                OrePrefixes.getPrefix(itemDataInSlotIdx.mPrefix.getName()),
                                nonAny,
                                itemInSlotIdx.stackSize);
                        }
                    }
                }
            }

            // 3. unprocessed fallback
            Materials unprocessed = getUnprocessedMaterials(thisMaterial);
            if (unprocessed != null) {
                retOutputs[idx] = GTOreDictUnificator.get(
                    OrePrefixes.getPrefix(itemDataInSlotIdx.mPrefix.getName()),
                    unprocessed,
                    itemInSlotIdx.stackSize);
            }

            // 4. replace circuit
            if (itemDataInSlotIdx.mPrefix == OrePrefixes.circuit) {
                ItemStack circuit = Mods.NewHorizonsCoreMod.isModLoaded() ? getCheapestCircuitOrNull(thisMaterial)
                    : null;
                if (circuit != null) {
                    circuit.stackSize = itemInSlotIdx.stackSize;
                    retOutputs[idx] = circuit;
                }
            }
        }

        for (int idx = 0; idx < outputs.length; idx++) {
            ItemStack original = outputs[idx] == null ? null : outputs[idx].copy();
            ItemStack current = retOutputs[idx];

            if (current == null) {
                retOutputs[idx] = original;
                current = original;
            }

            if (GTUtility.areStacksEqual(current, original)) {
                current.stackSize = Math.min(current.stackSize, original.stackSize);
            }

            for (Object2ObjectMap.Entry<ItemStack, ItemStack> entry : getAlwaysReplace().object2ObjectEntrySet()) {
                if (GTUtility.areStacksEqual(current, entry.getKey(), true)) {
                    retOutputs[idx] = entry.getValue()
                        .copy();
                    break;
                }
            }

            retOutputs[idx] = handleUnification(retOutputs[idx]);
            retOutputs[idx] = handleWildcard(retOutputs[idx]);
            retOutputs[idx] = handleContainerItem(retOutputs[idx]);
        }

        return Arrays.stream(retOutputs)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(ObjectArrayList::new));
    }

    public static Materials replaceCheaperOrNull(Materials first, Materials second) {
        if (first == second) return null;

        if (first == Materials.Aluminium && second == Materials.Iron) return second;
        if (first == Materials.Steel && second == Materials.Iron) return second;
        if (first == Materials.CastIron && second == Materials.Iron) return second;
        if (first == Materials.Aluminium && second == Materials.CastIron) return Materials.Iron;
        if (first == Materials.Aluminium && second == Materials.Steel) return second;

        if (first == Materials.Polytetrafluoroethylene && second == Materials.Polyethylene) return second;
        if (first == Materials.Polybenzimidazole && second == Materials.Polyethylene) return second;
        if (first == Materials.Polystyrene && second == Materials.Polyethylene) return second;
        if (first == Materials.RubberSilicone && second == Materials.Polyethylene) return second;

        if ((first == Materials.NetherQuartz || first == Materials.CertusQuartz) && second == Materials.Quartzite)
            return second;

        if (first == Materials.Polyethylene && second == Materials.Wood) return second;
        if (first == Materials.Diamond && second == Materials.Glass) return second;

        return null;
    }

    public static Materials replaceAnyOrNull(Materials first) {
        List<Materials> list = first.mOreReRegistrations;

        if (list != null) {
            for (Materials reg : list) {
                if (reg == Materials.AnyIron) return Materials.Iron;
                if (reg == Materials.AnyCopper) return Materials.Copper;
                if (reg == Materials.AnyRubber) return Materials.Rubber;
                if (reg == Materials.AnyBronze) return Materials.Bronze;
                if (reg == Materials.AnySyntheticRubber) return Materials.Rubber;
            }
        }

        return null;
    }

    public static Materials getUnprocessedMaterials(Materials first) {
        if (first == Materials.SteelMagnetic) return Materials.Steel;
        if (first == Materials.IronMagnetic) return Materials.Iron;
        if (first == Materials.NeodymiumMagnetic) return Materials.Neodymium;
        if (first == Materials.SamariumMagnetic) return Materials.Samarium;
        if (first == Materials.AnnealedCopper) return Materials.Copper;
        return null;
    }

    public static ItemStack getCheapestCircuitOrNull(Materials material) {
        if (material == Materials.ULV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitULV");
        if (material == Materials.LV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitLV");
        if (material == Materials.MV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitMV");
        if (material == Materials.HV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitHV");
        if (material == Materials.EV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitEV");
        if (material == Materials.IV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitIV");
        if (material == Materials.LuV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitLuV");
        if (material == Materials.ZPM) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitZPM");
        if (material == Materials.UV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitUV");
        if (material == Materials.UHV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitUHV");
        if (material == Materials.UEV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitUEV");
        if (material == Materials.UIV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitUIV");
        if (material == Materials.UMV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitUMV");
        if (material == Materials.UXV) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitUXV");
        if (material == Materials.MAX) return optionalEnumStack("com.dreammaster.item.NHItemList", "CircuitMAX");
        return null;
    }

    public static Object2ObjectMap<String, ItemStack> getOreDictReplace() {
        Object2ObjectMap<String, ItemStack> map = new Object2ObjectArrayMap<>();
        map.put("plankWood", new ItemStack(Blocks.planks));
        map.put("stoneCobble", new ItemStack(Blocks.cobblestone));
        map.put("gemDiamond", new ItemStack(Items.diamond));
        map.put("logWood", new ItemStack(Blocks.log));
        map.put("stickWood", new ItemStack(Items.stick));
        map.put("treeSapling", new ItemStack(Blocks.sapling));
        return map;
    }

    public static Object2ObjectMap<ItemStack, ItemStack> getAlwaysReplace() {
        Object2ObjectMap<ItemStack, ItemStack> map = new Object2ObjectLinkedOpenHashMap<>();
        map.put(
            new ItemStack(Blocks.trapped_chest, 1, OreDictionary.WILDCARD_VALUE),
            new ItemStack(Blocks.chest, 1, OreDictionary.WILDCARD_VALUE));
        return map;
    }

    public static ItemStack handleUnification(ItemStack stack) {
        if (stack != null) {
            for (int oreId : OreDictionary.getOreIDs(stack)) {
                String oreName = OreDictionary.getOreName(oreId);
                Object2ObjectMap<String, ItemStack> oreDictReplace = getOreDictReplace();
                if (oreDictReplace.containsKey(oreName)) {
                    ItemStack result = oreDictReplace.get(oreName)
                        .copy();
                    result.stackSize = stack.stackSize;
                    return result;
                }
            }
        }
        return GTOreDictUnificator.get(stack);
    }

    public static ItemStack handleWildcard(ItemStack stack) {
        if (stack != null && stack.getItemDamage() == OreDictionary.WILDCARD_VALUE
            && !stack.getItem()
                .isDamageable()) {
            stack.setItemDamage(0);
        }
        return stack;
    }

    public static ItemStack handleContainerItem(ItemStack stack) {
        if (stack != null && stack.getItem()
            .hasContainerItem(stack)) {
            return null;
        }
        return stack;
    }

    public static boolean shouldDisassemble(ItemStack[] mInputsOrOutputs) {
        return mInputsOrOutputs.length == 1 && shouldDisassembleItemStack(mInputsOrOutputs[0]);
    }

    /**
     * Check if the input item is valid for disassembling.
     */
    public static boolean shouldDisassembleItemStack(ItemStack stack) {
        if (stack == null) return false;

        if (stack.getItem() instanceof MetaGeneratedTool) return false;
        if (isCircuit(stack)) return false;
        if (isOre(stack)) return false;
        if (hasUnpackerRecipe(stack)) return false;

        for (GTItemStack blacklisted : inputBlacklist) {
            if (GTUtility.areStacksEqual(blacklisted.toStack(), stack, true)) {
                return false;
            }
        }

        return true;
    }

    public static boolean isCircuit(ItemStack stack) {
        ItemData data = GTOreDictUnificator.getAssociation(stack);
        return data != null && data.mPrefix == OrePrefixes.circuit;
    }

    public static boolean hasUnpackerRecipe(ItemStack stack) {
        return RecipeMaps.unpackagerRecipes.findRecipeQuery()
            .items(stack)
            .find() != null;
    }

    public static boolean isOre(ItemStack stack) {
        ItemData data = GTOreDictUnificator.getAssociation(stack);
        return data != null && (data.mPrefix == OrePrefixes.ore || data.mPrefix == OrePrefixes.crushed
            || data.mPrefix == OrePrefixes.crushedCentrifuged
            || data.mPrefix == OrePrefixes.crushedPurified);
    }

}
