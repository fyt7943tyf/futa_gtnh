package com.futa_gtnh.disassembler;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

import gregtech.api.util.GTUtility;

/** One original Shimmer production batch; no recursive refunds or quantity reduction. */
public final class DisassemblyRecipe {

    public static final int EU_PER_TICK = 32;
    public static final int DURATION = 40;
    public final ItemKey input;
    public final int inputCount;
    public final String source;
    private final ItemStack pattern;
    private final List<ItemStack> items = new ArrayList<>();
    private final List<FluidStack> fluids = new ArrayList<>();

    public DisassemblyRecipe(ItemStack output, List<ItemStack> ingredients, List<FluidStack> liquids, String source) {
        if (!GTUtility.isStackValid(output) || output.stackSize <= 0)
            throw new IllegalArgumentException("Invalid output");
        pattern = output.copy();
        input = ItemKey.of(output);
        inputCount = output.stackSize;
        this.source = source;
        for (ItemStack stack : ingredients) {
            if (GTUtility.isStackValid(stack) && stack.stackSize > 0) items.add(stack.copy());
        }
        for (FluidStack fluid : liquids) {
            if (fluid != null && fluid.amount > 0) {
                if (FluidKey.of(fluid) == null) throw new IllegalArgumentException("Invalid fluid");
                fluids.add(fluid.copy());
            }
        }
        if (items.isEmpty() && fluids.isEmpty()) throw new IllegalArgumentException("No consumed materials");
    }

    public ItemStack inputStack() {
        return pattern.copy();
    }

    public boolean matches(ItemStack stack, boolean ignoreNBT, boolean checkQuantity) {
        return stack != null && (!checkQuantity || stack.stackSize >= inputCount)
            && GTUtility.areStacksEqual(stack, pattern, ignoreNBT);
    }

    public List<ItemStack> itemOutputs() {
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : items) {
            int limit = Math.max(1, Math.min(64, stack.getMaxStackSize()));
            for (long remaining = stack.stackSize; remaining > 0; remaining -= limit) {
                ItemStack copy = stack.copy();
                copy.stackSize = (int) Math.min(limit, remaining);
                result.add(copy);
            }
        }
        return result;
    }

    public List<FluidStack> fluidOutputs() {
        List<FluidStack> result = new ArrayList<>();
        for (FluidStack fluid : fluids) result.add(fluid.copy());
        return result;
    }

    public int requiredItemSlots() {
        long slots = 0;
        for (ItemStack stack : items) {
            int limit = Math.max(1, Math.min(64, stack.getMaxStackSize()));
            slots = Math.addExact(slots, ((long) stack.stackSize + limit - 1) / limit);
        }
        return Math.toIntExact(slots);
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagCompound inputTag = inputStack().writeToNBT(new NBTTagCompound());
        inputTag.setInteger("batchCount", inputCount);
        tag.setTag("input", inputTag);
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
        NBTTagCompound inputTag = tag.getCompoundTag("input");
        ItemStack input = ItemStack.loadItemStackFromNBT(inputTag);
        if (input != null && inputTag.hasKey("batchCount")) input.stackSize = inputTag.getInteger("batchCount");
        return new DisassemblyRecipe(input, items, fluids, "saved task");
    }
}
