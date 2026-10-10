package com.futa_gtnh.disassembler;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.shared.ItemKey;

import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.enums.ToolDictNames;
import gregtech.api.items.MetaGeneratedTool;
import gregtech.api.objects.ItemData;
import gregtech.api.util.GTOreDictUnificator;

/** Refunds use declared GT representatives, never the order of a recipe's alternatives. */
public final class DisassemblyIngredients {

    private DisassemblyIngredients() {}

    /** GTNL's reverse-crafting rule: tools take part in matching but are never material refunds. */
    static ItemStack craftingTool(Object ingredient) {
        if (ingredient instanceof ItemStack) {
            ItemStack stack = (ItemStack) ingredient;
            return isCraftingTool(stack) ? stack.copy() : null;
        }
        if (!(ingredient instanceof List<?>)) return null;
        List<?> options = (List<?>) ingredient;
        if (options.isEmpty()) return null;
        for (Object option : options)
            if (!(option instanceof ItemStack) || !isCraftingTool((ItemStack) option)) return null;
        return ((ItemStack) options.get(0)).copy();
    }

    private static boolean isCraftingTool(ItemStack stack) {
        if (ItemKey.of(stack) == null) return false;
        if (stack.getItem() instanceof MetaGeneratedTool) return true;
        // A dictionary label alone must not turn an ordinary consumed material into a free catalyst.
        if (!stack.getItem()
            .hasContainerItem(stack)) return false;
        for (int id : OreDictionary.getOreIDs(stack))
            if (ToolDictNames.contains(OreDictionary.getOreName(id))) return true;
        return false;
    }

    public static ItemStack resolve(Object ingredient) {
        if (ingredient == null) return null;
        if (ingredient instanceof ItemStack) return normalize((ItemStack) ingredient);
        if (!(ingredient instanceof List<?>)) throw new IllegalArgumentException("Unknown ingredient");
        List<?> options = (List<?>) ingredient;
        ItemStack first = null;
        boolean identical = true;
        Set<Integer> commonOres = null;
        for (Object option : options) {
            if (!(option instanceof ItemStack)) throw new IllegalArgumentException("Unknown alternative");
            ItemStack stack = (ItemStack) option;
            if (ItemKey.of(stack) == null) throw new IllegalArgumentException("Invalid alternative");
            if (first == null) first = stack;
            else {
                if (first.stackSize != stack.stackSize)
                    throw new IllegalArgumentException("Conflicting alternative quantities");
                identical &= ItemKey.of(first)
                    .equals(ItemKey.of(stack));
            }
            Set<Integer> ores = new LinkedHashSet<>();
            for (int id : OreDictionary.getOreIDs(stack)) ores.add(id);
            if (commonOres == null) commonOres = ores;
            else commonOres.retainAll(ores);
        }
        if (first == null) throw new IllegalArgumentException("Empty ore alternatives");
        if (identical) return normalize(first);
        for (Object option : options) {
            if (!plain((ItemStack) option)) throw new IllegalArgumentException(
                "Stateful, wildcard or container alternatives: " + describe(options));
        }
        ItemStack result = null;
        for (int id : commonOres) {
            ItemStack standard = representative(OreDictionary.getOreName(id));
            if (standard == null || !contains(options, standard)) continue;
            if (result != null && !ItemKey.of(result)
                .equals(ItemKey.of(standard)))
                throw new IllegalArgumentException("Ambiguous GT ore representatives: " + describe(options));
            result = standard;
        }
        if (result == null) throw new IllegalArgumentException("No shared GT ore representative: " + describe(options));
        result.stackSize = first.stackSize;
        return result;
    }

    /** Normalize fixed material inputs too, so equivalent crafting/assembler routes can agree. */
    public static ItemStack normalize(ItemStack stack) {
        if (stack == null) return null;
        if (plain(stack)) {
            ItemData data = GTOreDictUnificator.getAssociation(stack);
            if (data != null && data.hasValidPrefixMaterialData()) {
                ItemStack standard = representative(data.toString());
                if (standard != null && sameOre(stack, standard, data.toString())) {
                    standard.stackSize = stack.stackSize;
                    return standard;
                }
            }
        }
        return stack.copy();
    }

    private static ItemStack representative(String ore) {
        OrePrefixes prefix = OrePrefixes.getOrePrefix(ore);
        if (prefix == null) return null;
        Materials material = OrePrefixes.getMaterial(ore, prefix);
        if (material == null || material == Materials._NULL) return null;
        if (prefix != OrePrefixes.circuit && (!prefix.isMaterialBased() || !prefix.isUnifiable())) return null;
        ItemStack standard;
        if (prefix == OrePrefixes.circuit) {
            ItemList circuit = circuit(material);
            standard = circuit != null && circuit.hasBeenSet() ? circuit.get(1)
                : GTOreDictUnificator.getName2StackMap()
                    .get(ore);
        } else standard = GTOreDictUnificator.getName2StackMap()
            .get(ore);
        return standard != null && plain(standard) ? standard.copy() : null;
    }

    private static ItemList circuit(Materials material) {
        if (material == Materials.ULV) return ItemList.Circuit_Primitive;
        if (material == Materials.LV) return ItemList.Circuit_Basic;
        if (material == Materials.MV) return ItemList.Circuit_Good;
        if (material == Materials.HV) return ItemList.Circuit_Advanced;
        if (material == Materials.EV) return ItemList.Circuit_Data;
        if (material == Materials.IV) return ItemList.Circuit_Elite;
        if (material == Materials.LuV) return ItemList.Circuit_Master;
        return null;
    }

    private static boolean plain(ItemStack stack) {
        return ItemKey.of(stack) != null && stack.stackSize > 0
            && stack.getItemDamage() != OreDictionary.WILDCARD_VALUE
            && !ItemKey.of(stack)
                .hasNbt()
            && !stack.getItem()
                .hasContainerItem(stack);
    }

    private static boolean contains(List<?> options, ItemStack standard) {
        for (Object option : options) if (ItemKey.of((ItemStack) option)
            .equals(ItemKey.of(standard))) return true;
        return false;
    }

    private static boolean sameOre(ItemStack a, ItemStack b, String ore) {
        int id = OreDictionary.getOreID(ore);
        boolean foundA = false;
        boolean foundB = false;
        for (int candidate : OreDictionary.getOreIDs(a)) foundA |= candidate == id;
        for (int candidate : OreDictionary.getOreIDs(b)) foundB |= candidate == id;
        return foundA && foundB;
    }

    private static String describe(List<?> options) {
        Set<String> items = new LinkedHashSet<>();
        for (Object option : options) items.add(
            ItemKey.of((ItemStack) option)
                .toString());
        return items.toString();
    }
}
