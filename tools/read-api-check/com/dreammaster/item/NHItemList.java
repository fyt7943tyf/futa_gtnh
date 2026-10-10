package com.dreammaster.item;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

/** Test-only optional bridge fixture: NHCore 2.9.71 exposes get(int), not get(long). */
public enum NHItemList {
    CircuitULV, CircuitLV, CircuitMV, CircuitHV, CircuitEV, CircuitIV, CircuitLuV,
    CircuitZPM, CircuitUV, CircuitUHV, CircuitUEV, CircuitUIV, CircuitUMV, CircuitUXV, CircuitMAX;
    public ItemStack get(int amount) { return new ItemStack(Items.redstone, amount, ordinal()); }
}
