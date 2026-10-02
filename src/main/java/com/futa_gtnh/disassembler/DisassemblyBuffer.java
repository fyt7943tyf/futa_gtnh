package com.futa_gtnh.disassembler;

import java.util.Arrays;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTank;

import com.futa_gtnh.shared.ItemKey;

/** Real inventory: input 0, escrow 1, outputs 2 onwards. Escrow drops only the original unfinished input. */
public final class DisassemblyBuffer {

    public ItemStack[] inventory;
    public FluidTank[] tanks;
    public int tankCapacity;
    public int progress;
    private DisassemblyRecipe pending;

    public DisassemblyBuffer(int itemSlots, int fluidSlots, int capacity) {
        inventory = new ItemStack[Math.max(36, itemSlots) + 2];
        tankCapacity = Math.max(64000, capacity);
        tanks = new FluidTank[Math.max(8, fluidSlots)];
        for (int i = 0; i < tanks.length; i++) tanks[i] = new FluidTank(tankCapacity);
    }

    public boolean hasTask() {
        return pending != null;
    }

    public boolean start(DisassemblyRecipe recipe) {
        ItemStack input = inventory[0];
        if (pending != null || inventory[1] != null
            || input == null
            || recipe == null
            || input.stackSize < recipe.inputCount
            || !recipe.input.equals(ItemKey.of(input))
            || simulate(recipe) == null) return false;
        inventory[1] = input.splitStack(recipe.inputCount);
        if (input.stackSize == 0) inventory[0] = null;
        pending = recipe;
        progress = 0;
        return true;
    }

    /** The caller pays 32 EU for each successful processing tick, including the final one. */
    public boolean advance(boolean powered) {
        if (pending == null || !powered) return false;
        Snapshot complete = simulate(pending);
        if (complete == null) return false;
        if (++progress >= DisassemblyRecipe.DURATION) {
            for (int i = 2; i < inventory.length; i++) inventory[i] = complete.items[i];
            for (int i = 0; i < tanks.length; i++) tanks[i].setFluid(complete.fluids[i]);
            inventory[1] = null;
            pending = null;
            progress = 0;
        }
        return true;
    }

    public boolean canAdvance() {
        return pending != null && simulate(pending) != null;
    }

    private Snapshot simulate(DisassemblyRecipe recipe) {
        ItemStack[] items = Arrays.stream(inventory)
            .map(stack -> stack == null ? null : stack.copy())
            .toArray(ItemStack[]::new);
        FluidStack[] fluids = new FluidStack[tanks.length];
        for (int i = 0; i < tanks.length; i++) fluids[i] = tanks[i].getFluid() == null ? null
            : tanks[i].getFluid()
                .copy();
        for (ItemStack output : recipe.itemOutputs()) {
            int remaining = output.stackSize;
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int i = 2; i < items.length && remaining > 0; i++) {
                    ItemStack current = items[i];
                    if (pass == 0 ? current == null || !ItemKey.of(current)
                        .equals(ItemKey.of(output)) : current != null) continue;
                    int room = Math.min(64, output.getMaxStackSize()) - (current == null ? 0 : current.stackSize);
                    int count = Math.min(remaining, Math.max(0, room));
                    if (count > 0) {
                        if (current == null) items[i] = ItemKey.of(output)
                            .prototype(count);
                        else current.stackSize += count;
                        remaining -= count;
                    }
                }
            }
            if (remaining != 0) return null;
        }
        for (FluidStack output : recipe.fluidOutputs()) {
            int remaining = output.amount;
            for (int pass = 0; pass < 2 && remaining > 0; pass++) {
                for (int i = 0; i < fluids.length && remaining > 0; i++) {
                    FluidStack current = fluids[i];
                    if (pass == 0 ? current == null || !current.isFluidEqual(output) : current != null) continue;
                    int count = Math.min(remaining, Math.max(0, tankCapacity - (current == null ? 0 : current.amount)));
                    if (count > 0) {
                        if (current == null) {
                            fluids[i] = output.copy();
                            fluids[i].amount = count;
                        } else current.amount += count;
                        remaining -= count;
                    }
                }
            }
            if (remaining != 0) return null;
        }
        return new Snapshot(items, fluids);
    }

    public void writeFluids(NBTTagCompound tag) {
        tag.setInteger("tankCount", tanks.length);
        tag.setInteger("tankCapacity", tankCapacity);
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < tanks.length; i++) {
            if (tanks[i].getFluid() == null) continue;
            NBTTagCompound fluid = tanks[i].getFluid()
                .writeToNBT(new NBTTagCompound());
            fluid.setInteger("slot", i);
            list.appendTag(fluid);
        }
        tag.setTag("tanks", list);
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("inventorySize", inventory.length);
        NBTTagList list = new NBTTagList();
        for (int i = 0; i < inventory.length; i++) {
            if (inventory[i] == null) continue;
            NBTTagCompound stack = inventory[i].writeToNBT(new NBTTagCompound());
            stack.setInteger("slot", i);
            list.appendTag(stack);
        }
        tag.setTag("inventory", list);
        writeFluids(tag);
        if (pending != null) tag.setTag("task", pending.writeToNbt());
        tag.setInteger("progress", progress);
        return tag;
    }

    public void readFromNbt(NBTTagCompound tag) {
        inventory = new ItemStack[Math.max(inventory.length, tag.getInteger("inventorySize"))];
        NBTTagList list = tag.getTagList("inventory", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound stack = list.getCompoundTagAt(i);
            int slot = stack.getInteger("slot");
            if (slot < 0) throw new IllegalArgumentException("Negative saved slot");
            if (slot >= inventory.length) inventory = Arrays.copyOf(inventory, slot + 1);
            inventory[slot] = ItemStack.loadItemStackFromNBT(stack);
        }
        tankCapacity = Math.max(tankCapacity, tag.getInteger("tankCapacity"));
        int count = Math.max(tanks.length, tag.getInteger("tankCount"));
        NBTTagList fluidTags = tag.getTagList("tanks", 10);
        for (int i = 0; i < fluidTags.tagCount(); i++) count = Math.max(
            count,
            fluidTags.getCompoundTagAt(i)
                .getInteger("slot") + 1);
        tanks = new FluidTank[count];
        for (int i = 0; i < count; i++) tanks[i] = new FluidTank(tankCapacity);
        for (int i = 0; i < fluidTags.tagCount(); i++) {
            NBTTagCompound fluid = fluidTags.getCompoundTagAt(i);
            FluidStack stack = FluidStack.loadFluidStackFromNBT(fluid);
            if (stack != null) tanks[fluid.getInteger("slot")].setFluid(stack);
        }
        pending = tag.hasKey("task") ? DisassemblyRecipe.readFromNbt(tag.getCompoundTag("task")) : null;
        if (pending != null && (inventory[1] == null || inventory[1].stackSize != pending.inputCount
            || !pending.input.equals(ItemKey.of(inventory[1]))))
            throw new IllegalArgumentException("Saved task does not match escrow");
        progress = pending == null ? 0
            : Math.max(0, Math.min(DisassemblyRecipe.DURATION - 1, tag.getInteger("progress")));
    }

    private static final class Snapshot {

        final ItemStack[] items;
        final FluidStack[] fluids;

        Snapshot(ItemStack[] items, FluidStack[] fluids) {
            this.items = items;
            this.fluids = fluids;
        }
    }
}
