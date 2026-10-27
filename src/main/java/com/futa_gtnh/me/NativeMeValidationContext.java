package com.futa_gtnh.me;

import java.util.Arrays;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import net.minecraft.item.Item;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidRegistry;

import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ByteOpenHashMap;

/** One synchronous load only: registry existence memoization and scratch buffers, never stock or validation results. */
final class NativeMeValidationContext {

    private static final int CACHE_LIMIT = 64;
    final NBTTagCompound[] rows = new NBTTagCompound[63];
    final long[] amounts = new long[63];
    private final IntPredicate items;
    private final Predicate<String> fluids;
    private Int2ByteOpenHashMap itemCache = new Int2ByteOpenHashMap(4);
    private Object2ByteOpenHashMap<String> fluidCache = new Object2ByteOpenHashMap<>(4);
    private boolean itemSeen;
    private int lastItem;
    private boolean lastItemExists;
    private String lastFluid;
    private boolean lastFluidExists;

    NativeMeValidationContext() {
        this(id -> Item.getItemById(id) != null, name -> FluidRegistry.getFluid(name) != null);
    }

    NativeMeValidationContext(IntPredicate items, Predicate<String> fluids) {
        this.items = items;
        this.fluids = fluids;
    }

    void resetRows() {
        Arrays.fill(rows, null);
        Arrays.fill(amounts, 0L);
    }

    boolean itemExists(int id) {
        if (itemSeen && id == lastItem) return lastItemExists;
        byte cached = itemCache == null ? 0 : itemCache.get(id);
        boolean exists = cached == 0 ? items.test(id) : cached == 1;
        if (cached == 0 && itemCache != null) {
            if (itemCache.size() < CACHE_LIMIT) itemCache.put(id, (byte) (exists ? 1 : 2));
            else itemCache = null; // Distinct-heavy inventories keep only the last-value fast path.
        }
        itemSeen = true;
        lastItem = id;
        lastItemExists = exists;
        return exists;
    }

    boolean fluidExists(String name) {
        if (name != null && name.equals(lastFluid)) return lastFluidExists;
        byte cached = fluidCache == null ? 0 : fluidCache.getByte(name);
        boolean exists = cached == 0 ? fluids.test(name) : cached == 1;
        if (cached == 0 && fluidCache != null) {
            if (fluidCache.size() < CACHE_LIMIT) fluidCache.put(name, (byte) (exists ? 1 : 2));
            else fluidCache = null;
        }
        lastFluid = name;
        lastFluidExists = exists;
        return exists;
    }
}
