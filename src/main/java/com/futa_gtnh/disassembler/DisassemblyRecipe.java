package com.futa_gtnh.disassembler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/** One exact, non-recursive reverse production batch. Quantities are never rounded up. */
public final class DisassemblyRecipe {

    public static final int EU_PER_TICK = 32;
    public static final int DURATION = 40;
    public final ItemKey input;
    public final int inputCount;
    public final String source;
    private final Map<ItemKey, Long> items;
    private final Map<FluidKey, Long> fluids;

    public DisassemblyRecipe(ItemStack output, List<ItemStack> ingredients, List<FluidStack> liquids, String source) {
        if (output == null || output.stackSize <= 0 || ItemKey.of(output) == null || output.getItemDamage() == 32767)
            throw new IllegalArgumentException("Invalid output");
        Map<ItemKey, Long> itemCounts = new LinkedHashMap<>();
        Map<FluidKey, Long> fluidCounts = new LinkedHashMap<>();
        for (ItemStack stack : ingredients) {
            if (stack == null || stack.stackSize <= 0) continue;
            if (stack.getItem() == null || stack.getItemDamage() == 32767
                || stack.getItem()
                    .hasContainerItem(stack))
                throw new IllegalArgumentException("Wildcard or container ingredient");
            itemCounts.merge(ItemKey.of(stack), (long) stack.stackSize, Math::addExact);
        }
        for (FluidStack fluid : liquids) {
            if (fluid != null && fluid.amount > 0 && FluidKey.of(fluid) == null)
                throw new IllegalArgumentException("Invalid fluid");
            if (fluid != null && fluid.amount > 0)
                fluidCounts.merge(FluidKey.of(fluid), (long) fluid.amount, Math::addExact);
        }
        if (itemCounts.isEmpty() && fluidCounts.isEmpty()) throw new IllegalArgumentException("No consumed materials");
        long divisor = output.stackSize;
        for (long amount : itemCounts.values()) divisor = gcd(divisor, amount);
        for (long amount : fluidCounts.values()) divisor = gcd(divisor, amount);
        final long scale = divisor;
        itemCounts.replaceAll((key, amount) -> amount / scale);
        fluidCounts.replaceAll((key, amount) -> amount / scale);
        input = ItemKey.of(output);
        inputCount = (int) (output.stackSize / divisor);
        if (inputCount > Math.min(64, output.getMaxStackSize()))
            throw new IllegalArgumentException("Batch exceeds input slot");
        for (long amount : fluidCounts.values())
            if (amount > Integer.MAX_VALUE) throw new IllegalArgumentException("Fluid batch exceeds int capacity");
        items = Collections.unmodifiableMap(itemCounts);
        fluids = Collections.unmodifiableMap(fluidCounts);
        this.source = source;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    public boolean sameMaterials(DisassemblyRecipe other) {
        return inputCount == other.inputCount && items.equals(other.items) && fluids.equals(other.fluids);
    }

    public List<ItemStack> itemOutputs() {
        List<ItemStack> result = new ArrayList<>();
        items.forEach((key, count) -> {
            int limit = Math.max(
                1,
                Math.min(
                    64,
                    key.prototype()
                        .getMaxStackSize()));
            for (long remaining = count; remaining > 0; remaining -= limit)
                result.add(key.prototype(Math.min(limit, remaining)));
        });
        return result;
    }

    public List<FluidStack> fluidOutputs() {
        List<FluidStack> result = new ArrayList<>();
        fluids.forEach((key, count) -> result.add(key.prototype(count.intValue())));
        return result;
    }

    public int requiredItemSlots() {
        long slots = 0;
        for (Map.Entry<ItemKey, Long> entry : items.entrySet()) {
            int limit = Math.max(
                1,
                Math.min(
                    64,
                    entry.getKey()
                        .prototype()
                        .getMaxStackSize()));
            slots = Math.addExact(slots, (entry.getValue() + limit - 1) / limit);
        }
        return Math.toIntExact(slots);
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setTag(
            "input",
            input.prototype(inputCount)
                .writeToNBT(new NBTTagCompound()));
        NBTTagList itemList = new NBTTagList();
        for (ItemStack stack : itemOutputs()) itemList.appendTag(stack.writeToNBT(new NBTTagCompound()));
        tag.setTag("items", itemList);
        NBTTagList fluidList = new NBTTagList();
        for (FluidStack fluid : fluidOutputs()) fluidList.appendTag(fluid.writeToNBT(new NBTTagCompound()));
        tag.setTag("fluids", fluidList);
        return tag;
    }

    public static DisassemblyRecipe readFromNbt(NBTTagCompound tag) {
        List<ItemStack> items = new ArrayList<>();
        List<FluidStack> fluids = new ArrayList<>();
        NBTTagList itemTags = tag.getTagList("items", 10);
        for (int i = 0; i < itemTags.tagCount(); i++) {
            ItemStack stack = ItemStack.loadItemStackFromNBT(itemTags.getCompoundTagAt(i));
            if (stack == null) throw new IllegalArgumentException("Missing saved material");
            items.add(stack);
        }
        NBTTagList fluidTags = tag.getTagList("fluids", 10);
        for (int i = 0; i < fluidTags.tagCount(); i++) {
            FluidStack stack = FluidStack.loadFluidStackFromNBT(fluidTags.getCompoundTagAt(i));
            if (stack == null) throw new IllegalArgumentException("Missing saved fluid");
            fluids.add(stack);
        }
        return new DisassemblyRecipe(
            ItemStack.loadItemStackFromNBT(tag.getCompoundTag("input")),
            items,
            fluids,
            "saved task");
    }
}
