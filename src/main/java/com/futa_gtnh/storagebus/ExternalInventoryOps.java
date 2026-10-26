package com.futa_gtnh.storagebus;

import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import com.futa_gtnh.block.TileEntityIoNode;
import com.futa_gtnh.block.TileEntitySharedTerminal;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * 存储总线对「相邻容器」的底层读写。
 *
 * <p>
 * 这一层和 {@code TerminalIoEngine} 的主动搬运遵循同一套守恒规则：只认 Forge 的
 * {@link IInventory} / {@link IFluidHandler}，每次写入都按容器实际接受的量计数，
 * 每次抽取都以容器实际减少的量计数。区别在于它是<b>按需</b>调用的（共享终端取东西、
 * AE 网络注入、IO 节点搬运），不是每 tick 定时扫描。
 * 抽屉的 IInventory 槽位是会自动补充的虚拟窗口，数量核对必须改用其物理存量。
 *
 * <p>
 * GT 的机器 / 输入总线 / 输入仓对 {@code getAccessibleSlotsFromSide} 常年返回空数组，
 * 真正的面访问由 {@code canInsertItem} / {@code canExtractItem} 控制，所以这里和
 * {@code TileEntityIoNode} 一样对 GT 侧跳过槽位表闸门。
 */
final class ExternalInventoryOps {

    /** Bound live-world work even for very large drawers/AE requests; callers may retry the remainder. */
    private static final int MAX_DRAWER_PASSES = 512;

    private ExternalInventoryOps() {}

    // ==================================================================
    // 目标判定
    // ==================================================================

    static boolean isItemTarget(TileEntity tile, ForgeDirection side) {
        if (!(tile instanceof IInventory)) return false;
        if (isStoragePart(tile)) return false;

        IInventory inventory = (IInventory) tile;
        if (inventory instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity) {
            try {
                return inventory.getSizeInventory() > 0;
            } catch (Throwable t) {
                return false;
            }
        }
        if (inventory instanceof ISidedInventory) {
            try {
                return ((ISidedInventory) inventory).getAccessibleSlotsFromSide(side.ordinal()).length > 0;
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    static boolean isFluidTarget(TileEntity tile, ForgeDirection side) {
        if (!(tile instanceof IFluidHandler) || isStoragePart(tile)) return false;
        try {
            FluidTankInfo[] tanks = ((IFluidHandler) tile).getTankInfo(side);
            return tanks != null && tanks.length > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 共享终端 / IO 节点 / 另一条存储总线永远不能当目标：那会绕成递归。 */
    private static boolean isStoragePart(TileEntity tile) {
        return tile instanceof TileEntitySharedTerminal || tile instanceof TileEntityIoNode
            || tile instanceof TileEntityStorageBus;
    }

    // ==================================================================
    // 物品
    // ==================================================================

    /** Pure preflight: never writes a slot, decrements a stack or calls markDirty. */
    static long simulateInsertItems(IInventory inventory, ForgeDirection side, ItemKey key, long amount) {
        if (inventory == null || key == null || amount <= 0L) return 0L;
        long drawerAmount = DrawerStorageCompat.simulateItems(inventory, side, key, amount, true);
        if (drawerAmount >= 0L) return drawerAmount;
        long remaining = amount;
        try {
            ItemStack prototype = key.prototype();
            int inventoryLimit = inventory.getInventoryStackLimit();
            int limit = Math
                .max(1, Math.min(Math.max(1, prototype.getMaxStackSize()), inventoryLimit > 0 ? inventoryLimit : 64));
            for (int slot = 0; slot < inventory.getSizeInventory() && remaining > 0; slot++) {
                ItemStack existing = inventory.getStackInSlot(slot);
                if (existing != null && existing.stackSize > 0 && !key.equals(ItemKey.of(existing))) continue;
                if (!canInsert(inventory, slot, prototype, side)) continue;
                long room = Math.max(0L, limit - (existing == null ? 0L : Math.max(0, existing.stackSize)));
                remaining -= Math.min(remaining, room);
            }
        } catch (RuntimeException error) {
            return 0L;
        }
        return amount - remaining;
    }

    /** Pure preflight; all permissions and item identities are checked against the live target. */
    static long simulateExtractItems(IInventory inventory, ForgeDirection side, ItemKey key, long amount) {
        if (inventory == null || key == null || amount <= 0L) return 0L;
        long drawerAmount = DrawerStorageCompat.simulateItems(inventory, side, key, amount, false);
        if (drawerAmount >= 0L) return drawerAmount;
        long remaining = amount;
        try {
            for (int slot = 0; slot < inventory.getSizeInventory() && remaining > 0; slot++) {
                ItemStack existing = inventory.getStackInSlot(slot);
                if (existing == null || existing.stackSize <= 0 || !key.equals(ItemKey.of(existing))) continue;
                if (canExtract(inventory, slot, existing, side)) remaining -= Math.min(remaining, existing.stackSize);
            }
        } catch (RuntimeException error) {
            return 0L;
        }
        return amount - remaining;
    }

    /**
     * 把 {@code amount} 个 {@code key} 塞进容器。
     *
     * @return 容器实际接受的数量
     */
    static long insertItems(IInventory inventory, ForgeDirection side, ItemKey key, long amount) {
        if (inventory == null || key == null || amount <= 0L) return 0L;
        boolean drawers = DrawerStorageCompat.supports(inventory);
        long remaining = amount;
        int passes = drawers ? MAX_DRAWER_PASSES : 1;
        do {
            long moved = insertItemsPass(inventory, side, key, remaining);
            remaining -= moved;
            // Drawer input windows are replenished after each write even while real capacity remains.
            if (!drawers || moved <= 0L) break;
        } while (remaining > 0L && --passes > 0);
        return amount - remaining;
    }

    private static long insertItemsPass(IInventory inventory, ForgeDirection side, ItemKey key, long amount) {

        int inventoryLimit = 64;
        try {
            inventoryLimit = inventory.getInventoryStackLimit();
        } catch (Throwable ignored) {
            // 有些容器没认真实现，退回原版默认值
        }
        int maxStack = (int) Math.max(
            1L,
            Math.min(
                (long) Math.max(
                    1,
                    key.prototype()
                        .getMaxStackSize()),
                inventoryLimit > 0 ? inventoryLimit : 64L));
        if (maxStack <= 0) return 0L;

        long remaining = amount;
        // 两趟：先并入已有的同类堆，再找空格 —— 和原版 addItemStackToInventory 一个顺序
        for (int pass = 0; pass < 2 && remaining > 0L; pass++) {
            int size;
            try {
                size = inventory.getSizeInventory();
            } catch (Throwable t) {
                return 0L;
            }
            for (int slot = 0; slot < size && remaining > 0L; slot++) {
                ItemStack existing;
                try {
                    existing = inventory.getStackInSlot(slot);
                } catch (Throwable t) {
                    continue;
                }

                if (pass == 0) {
                    if (existing == null || existing.getItem() == null || !key.equals(ItemKey.of(existing))) continue;
                    if (!canInsert(inventory, slot, key.prototype(), side)) continue;
                    int room = maxStack - existing.stackSize;
                    if (room <= 0) continue;
                    int moved = (int) Math.min((long) room, remaining);
                    int beforeSize = existing.stackSize;
                    long physicalBefore = DrawerStorageCompat.itemAmount(inventory, key);
                    ItemStack updated = existing.copy();
                    updated.stackSize += moved;
                    if (!writeSlot(inventory, slot, updated)) {
                        // 写调用抛异常时也可能已经写进去了：按实际变化记账
                        int actual = increaseInSlot(inventory, slot, beforeSize, physicalBefore, key, moved);
                        remaining -= actual;
                        if (actual <= 0) {
                            if (remaining < amount) markDirty(inventory);
                            return amount - remaining;
                        }
                        continue;
                    }
                    // 有些容器会收下调用但不改内容；按容器的实际变化记账，绝不凭空算收下
                    int actual = increaseInSlot(inventory, slot, beforeSize, physicalBefore, key, moved);
                    if (actual <= 0) {
                        if (remaining < amount) markDirty(inventory);
                        return amount - remaining;
                    }
                    remaining -= actual;
                } else {
                    if (existing != null && existing.getItem() != null && existing.stackSize > 0) continue;
                    ItemStack prototype = key.prototype();
                    if (!canInsert(inventory, slot, prototype, side)) continue;
                    int moved = (int) Math.min((long) maxStack, remaining);
                    long physicalBefore = DrawerStorageCompat.itemAmount(inventory, key);
                    if (!writeSlot(inventory, slot, key.prototype(moved))) {
                        int actual = increaseInSlot(inventory, slot, 0, physicalBefore, key, moved);
                        remaining -= actual;
                        if (actual <= 0) {
                            if (remaining < amount) markDirty(inventory);
                            return amount - remaining;
                        }
                        continue;
                    }
                    int actual = increaseInSlot(inventory, slot, 0, physicalBefore, key, moved);
                    if (actual <= 0) {
                        if (remaining < amount) markDirty(inventory);
                        return amount - remaining;
                    }
                    remaining -= actual;
                }
            }
        }

        long accepted = amount - remaining;
        if (accepted > 0L) markDirty(inventory);
        return accepted;
    }

    /**
     * 从容器里取出 {@code amount} 个 {@code key}。
     *
     * @return 实际取出的数量
     */
    static long extractItems(IInventory inventory, ForgeDirection side, ItemKey key, long amount) {
        if (inventory == null || key == null || amount <= 0L) return 0L;
        boolean drawers = DrawerStorageCompat.supports(inventory);
        long remaining = amount;
        int passes = drawers ? MAX_DRAWER_PASSES : 1;
        do {
            long moved = extractItemsPass(inventory, side, key, remaining);
            remaining -= moved;
            // A drawer may return another full output stack without changing the window's stackSize.
            if (!drawers || moved <= 0L) break;
        } while (remaining > 0L && --passes > 0);
        return amount - remaining;
    }

    private static long extractItemsPass(IInventory inventory, ForgeDirection side, ItemKey key, long amount) {

        long remaining = amount;
        int size;
        try {
            size = inventory.getSizeInventory();
        } catch (Throwable t) {
            return 0L;
        }

        for (int slot = 0; slot < size && remaining > 0L; slot++) {
            ItemStack existing;
            try {
                existing = inventory.getStackInSlot(slot);
            } catch (Throwable t) {
                continue;
            }
            if (existing == null || existing.getItem() == null || existing.stackSize <= 0) continue;
            if (!key.equals(ItemKey.of(existing))) continue;
            if (!canExtract(inventory, slot, existing, side)) continue;

            int sourceBefore = existing.stackSize;
            long physicalBefore = DrawerStorageCompat.itemAmount(inventory, key);
            int want = (int) Math.min((long) sourceBefore, remaining);
            ItemStack removed;
            try {
                removed = inventory.decrStackSize(slot, want);
            } catch (Throwable t) {
                continue;
            }
            if (removed == null || removed.getItem() == null || removed.stackSize <= 0) continue;

            if (!key.equals(ItemKey.of(removed))) {
                // 容器没有按契约返回所请求的那一种物品。只有源格真的减少了才把它塞回去，
                // 否则会把「返回了一个副本但源格没动」也补一遍，变成刷物品。
                int afterWrong = amountInSlotRaw(inventory, slot);
                int removedFromSlot = physicalBefore >= 0L
                    ? (int) Math.min(
                        Integer.MAX_VALUE,
                        Math.max(0L, physicalBefore - DrawerStorageCompat.itemAmount(inventory, key)))
                    : Math.max(0, sourceBefore - afterWrong);
                if (removedFromSlot > 0) {
                    ItemStack back = removed.copy();
                    back.stackSize = Math.min(removedFromSlot, removed.stackSize);
                    restore(inventory, slot, back);
                }
                continue;
            }

            // 再核对源格真的少了多少：有的容器会返回一个栈却不动槽位。
            // 普通容器按源格减少量记账；抽屉按物理存量减少量记账，窗口可能仍显示 64。
            int afterSize = amountInSlotRaw(inventory, slot);
            long sourceDelta = physicalBefore >= 0L
                ? Math.max(0L, physicalBefore - DrawerStorageCompat.itemAmount(inventory, key))
                : Math.max(0, sourceBefore - afterSize);
            int taken = (int) Math.min(Math.min(removed.stackSize, sourceDelta), want);
            if (taken <= 0) continue;
            remaining -= taken;
            markDirty(inventory);
        }
        return amount - remaining;
    }

    private static boolean writeSlot(IInventory inventory, int slot, ItemStack stack) {
        try {
            inventory.setInventorySlotContents(slot, stack);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** @return 写入后这一格相对 {@code beforeSize} 实际增加了多少个目标物品（0..expected） */
    private static int increaseInSlot(IInventory inventory, int slot, int beforeSize, long physicalBefore, ItemKey key,
        int expected) {
        if (physicalBefore >= 0L) {
            long after = DrawerStorageCompat.itemAmount(inventory, key);
            return (int) Math.max(0L, Math.min(expected, after - physicalBefore));
        }
        try {
            ItemStack after = inventory.getStackInSlot(slot);
            if (after == null || after.getItem() == null || !key.equals(ItemKey.of(after))) return 0;
            int increase = after.stackSize - beforeSize;
            return Math.max(0, Math.min(expected, increase));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 只读数量；读失败时返回一个很大的值，让调用方的「源格减少了多少」算成 0（走保守回滚）。 */
    private static int amountInSlotRaw(IInventory inventory, int slot) {
        try {
            ItemStack after = inventory.getStackInSlot(slot);
            return after == null || after.getItem() == null ? 0 : Math.max(0, after.stackSize);
        } catch (Throwable t) {
            return Integer.MAX_VALUE;
        }
    }

    /** 抽取结果不符合预期时的兜底：先并回原槽，原槽有东西就找空槽。 */
    private static void restore(IInventory inventory, int slot, ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return;
        try {
            ItemStack current = inventory.getStackInSlot(slot);
            if (current == null || current.getItem() == null || current.stackSize <= 0) {
                inventory.setInventorySlotContents(slot, stack);
                markDirty(inventory);
                return;
            }
            // 同种就并回去；不同种就放到别的空槽
            if (sameItem(current, stack)) {
                int room = Math.max(1, current.getMaxStackSize()) - current.stackSize;
                int moved = Math.min(room, stack.stackSize);
                if (moved > 0) {
                    ItemStack updated = current.copy();
                    updated.stackSize += moved;
                    inventory.setInventorySlotContents(slot, updated);
                    stack.stackSize -= moved;
                    markDirty(inventory);
                }
            }
            if (stack.stackSize > 0) {
                for (int i = 0; i < inventory.getSizeInventory() && stack.stackSize > 0; i++) {
                    ItemStack other = inventory.getStackInSlot(i);
                    if (other != null && other.getItem() != null && other.stackSize > 0) continue;
                    int moved = Math.min(Math.max(1, stack.getMaxStackSize()), stack.stackSize);
                    ItemStack put = stack.copy();
                    put.stackSize = moved;
                    inventory.setInventorySlotContents(i, put);
                    stack.stackSize -= moved;
                    markDirty(inventory);
                }
            }
        } catch (Throwable t) {
            // 无计可施时至少不要中断调用方；这一段只有在别的模组违反契约时才会走到
        }
    }

    // ==================================================================
    // 流体
    // ==================================================================

    static long simulateInsertFluid(IFluidHandler handler, ForgeDirection side, FluidKey key, long amount) {
        if (handler == null || key == null || amount <= 0L) return 0L;
        int want = (int) Math.min(amount, Integer.MAX_VALUE);
        try {
            return Math.max(0, Math.min(want, handler.fill(side, key.prototype(want), false)));
        } catch (RuntimeException error) {
            return 0L;
        }
    }

    static long simulateExtractFluid(IFluidHandler handler, ForgeDirection side, FluidKey key, long amount) {
        if (handler == null || key == null || amount <= 0L) return 0L;
        int want = (int) Math.min(amount, Integer.MAX_VALUE);
        try {
            FluidStack result = handler.drain(side, key.prototype(want), false);
            return result == null || !key.equals(FluidKey.of(result)) ? 0L : Math.max(0, Math.min(want, result.amount));
        } catch (RuntimeException error) {
            return 0L;
        }
    }

    static long insertFluid(IFluidHandler handler, ForgeDirection side, FluidKey key, long amount) {
        if (handler == null || key == null || amount <= 0L) return 0L;
        int want = (int) Math.min(amount, Integer.MAX_VALUE);
        try {
            int accepted = handler.fill(side, key.prototype(want), true);
            return Math.max(0, Math.min(accepted, want));
        } catch (Throwable t) {
            return 0L;
        }
    }

    static long extractFluid(IFluidHandler handler, ForgeDirection side, FluidKey key, long amount) {
        if (handler == null || key == null || amount <= 0L) return 0L;
        int want = (int) Math.min(amount, Integer.MAX_VALUE);
        FluidStack drained;
        try {
            drained = handler.drain(side, key.prototype(want), true);
        } catch (Throwable t) {
            return 0L;
        }
        if (drained == null || drained.getFluid() == null || drained.amount <= 0) return 0L;
        FluidKey drainedKey = FluidKey.of(drained);
        if (drainedKey == null || !drainedKey.equals(key)) {
            // 抽出来的不是要的那一种：尽量灌回去，不能把它算进这次抽取
            try {
                handler.fill(side, drained, true);
            } catch (Throwable ignored) {
                // 灌不回去时只能少算，绝不凭空记账
            }
            return 0L;
        }
        return Math.min(drained.amount, want);
    }

    // ==================================================================
    // 面访问
    // ==================================================================

    static boolean canInsert(IInventory inventory, int slot, ItemStack stack, ForgeDirection side) {
        try {
            if (inventory instanceof ISidedInventory) {
                return isAccessible((ISidedInventory) inventory, slot, side)
                    && ((ISidedInventory) inventory).canInsertItem(slot, stack, side.ordinal());
            }
            return inventory.isItemValidForSlot(slot, stack);
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean canExtract(IInventory inventory, int slot, ItemStack stack, ForgeDirection side) {
        try {
            if (inventory instanceof ISidedInventory) {
                return isAccessible((ISidedInventory) inventory, slot, side)
                    && ((ISidedInventory) inventory).canExtractItem(slot, stack, side.ordinal());
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isAccessible(ISidedInventory inventory, int slot, ForgeDirection side) {
        if (inventory instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity) return true;
        try {
            for (int accessible : inventory.getAccessibleSlotsFromSide(side.ordinal())) {
                if (accessible == slot) return true;
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage()
            && ItemStack.areItemStackTagsEqual(a, b);
    }

    private static void markDirty(IInventory inventory) {
        try {
            inventory.markDirty();
        } catch (Throwable ignored) {
            // 只是缓存失效提示，失败不影响数量守恒
        }
    }
}
