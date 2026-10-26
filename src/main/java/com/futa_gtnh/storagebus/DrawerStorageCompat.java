package com.futa_gtnh.storagebus;

import java.util.LinkedHashMap;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.shared.ItemKey;

/** Storage Drawers exposes input/output windows through IInventory, not its physical stock. */
public final class DrawerStorageCompat {

    private static final Class<?> DRAWER_GROUP = findDrawerGroup();

    private DrawerStorageCompat() {}

    private static Class<?> findDrawerGroup() {
        try {
            return Class.forName(
                "com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup",
                false,
                DrawerStorageCompat.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    public static boolean supports(IInventory inventory) {
        return DRAWER_GROUP != null && DRAWER_GROUP.isInstance(inventory);
    }

    /** Null means an ordinary inventory; an empty map means a supported, empty drawer group. */
    public static LinkedHashMap<ItemKey, Long> snapshot(IInventory inventory) {
        return supports(inventory) ? Hook.snapshot(inventory) : null;
    }

    /** Negative means unsupported. Counts must be read after the drawer has synced its virtual stacks. */
    static long itemAmount(IInventory inventory, ItemKey key) {
        return supports(inventory) ? Hook.itemAmount(inventory, key) : -1L;
    }

    /** Negative means unsupported; this path deliberately never synchronizes virtual drawer slots. */
    static long simulateItems(IInventory inventory, ForgeDirection side, ItemKey key, long amount, boolean insert) {
        return supports(inventory) ? Hook.simulateItems(inventory, side, key, amount, insert) : -1L;
    }

    public static long insertItems(IInventory inventory, ForgeDirection side, ItemKey key, long amount) {
        return ExternalInventoryOps.insertItems(inventory, side, key, amount);
    }

    public static long extractItems(IInventory inventory, ForgeDirection side, ItemKey key, long amount) {
        return ExternalInventoryOps.extractItems(inventory, side, key, amount);
    }

    /** All optional API references stay in this lazily loaded class. */
    private static final class Hook {

        static long simulateItems(IInventory inventory, ForgeDirection side, ItemKey key, long amount, boolean insert) {
            com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup group = (com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup) inventory;
            com.jaquadro.minecraft.storagedrawers.api.inventory.IDrawerInventory slots = group.getDrawerInventory();
            if (slots == null) return -1L;
            ItemStack prototype = key.prototype();
            long remaining = amount;
            for (int index = 0; index < group.getDrawerCount() && remaining > 0; index++) {
                if (!group.isDrawerEnabled(index)) continue;
                com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer drawer = group.getDrawer(index);
                if (drawer == null) continue;
                ItemKey stored = ItemKey.of(drawer.getStoredItemPrototype());
                int slot = slots.getInventorySlot(
                    index,
                    insert ? com.jaquadro.minecraft.storagedrawers.api.inventory.SlotType.INPUT
                        : com.jaquadro.minecraft.storagedrawers.api.inventory.SlotType.OUTPUT);
                if (slot < 0) continue;
                long available;
                if (insert) {
                    if (stored != null && !key.equals(stored) || !drawer.canItemBeStored(prototype)
                        || !ExternalInventoryOps.canInsert(inventory, slot, prototype, side)) continue;
                    available = drawer.isEmpty() ? drawer.getMaxCapacity(prototype) : drawer.getRemainingCapacity();
                } else {
                    if (!key.equals(stored) || !drawer.canItemBeExtracted(prototype)
                        || !ExternalInventoryOps.canExtract(inventory, slot, prototype, side)) continue;
                    available = drawer.getStoredItemCount();
                }
                remaining -= Math.min(remaining, Math.max(0L, available));
            }
            return amount - remaining;
        }

        private static com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup group(IInventory inventory) {
            com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup group = (com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup) inventory;
            // Flush in-place pipe edits before reading physical counts, just like getStackInSlot does.
            if (group.getDrawerInventory() != null) group.getDrawerInventory()
                .markDirty();
            else {
                // Controllers expose drawers from several groups and have no single drawer inventory.
                for (int slot = 0; slot < group.getDrawerCount(); slot++) {
                    com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer drawer = group.getDrawer(slot);
                    if (drawer instanceof com.jaquadro.minecraft.storagedrawers.api.inventory.IInventoryAdapter) {
                        ((com.jaquadro.minecraft.storagedrawers.api.inventory.IInventoryAdapter) drawer)
                            .syncInventory();
                    }
                }
            }
            return group;
        }

        static LinkedHashMap<ItemKey, Long> snapshot(IInventory inventory) {
            com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup group = group(inventory);
            LinkedHashMap<ItemKey, Long> result = new LinkedHashMap<>();
            for (int slot = 0; slot < group.getDrawerCount(); slot++) {
                if (!group.isDrawerEnabled(slot)) continue;
                com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer drawer = group.getDrawer(slot);
                if (drawer == null) continue;
                ItemKey key = ItemKey.of(drawer.getStoredItemPrototype());
                int amount = drawer.getStoredItemCount();
                if (key != null && amount > 0) result.merge(key, (long) amount, Long::sum);
            }
            return result;
        }

        static long itemAmount(IInventory inventory, ItemKey key) {
            com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup group = group(inventory);
            long total = 0L;
            for (int slot = 0; slot < group.getDrawerCount(); slot++) {
                if (!group.isDrawerEnabled(slot)) continue;
                com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer drawer = group.getDrawer(slot);
                if (drawer == null) continue;
                ItemStack prototype = drawer.getStoredItemPrototype();
                if (key.equals(ItemKey.of(prototype))) total += Math.max(0, drawer.getStoredItemCount());
            }
            return total;
        }
    }
}
