package com.futa_gtnh.exchange;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

import gregtech.api.util.GTUtility;

/**
 * 共享存储 &lt;-&gt; 玩家个人背包的双向交换。
 *
 * <p>
 * 这个类只负责「搬运」，不碰网络也不碰 GUI。所有方法都是<b>服务端权威</b>的：
 * 数量、能不能放得下、物品是否合法，全在这里判定，客户端说什么都不算数。
 *
 * <p>
 * 贯穿全类的一条原则是<b>数量守恒</b>：先把「能放多少」算清楚，再从另一边扣，
 * 而且任何一步失败都要把已经扣掉的东西还回去。宁可这次操作什么都别做，
 * 也绝不能出现「背包里没拿到、存储里却少了」这种凭空蒸发的情况。
 */
public final class InventoryExchange {

    private InventoryExchange() {}

    public static final int INV_SIZE = 36;
    public static final int HOTBAR_SIZE = 9;
    /** 护甲槽数量 */
    public static final int ARMOR_SIZE = 4;
    /**
     * 玩家身上「可被单独存入」的槽位总数 = 主背包(36) + 护甲(4)。
     *
     * <p>
     * 沿用 {@link InventoryPlayer} 自己的编号约定：{@code getStackInSlot(i)} 在
     * {@code i >= 36} 时会自动落到 {@code armorInventory[i - 36]}，
     * 所以把 0..39 交给它就行，不用自己判断该访问哪个数组。
     * 那 4 格是 36=靴子、37=护腿、38=胸甲、39=头盔。
     */
    public static final int PLAYER_SLOT_COUNT = INV_SIZE + ARMOR_SIZE;

    /** 批量存入的范围 */
    public static final int SCOPE_ALL = 0;
    public static final int SCOPE_HOTBAR = 1;
    public static final int SCOPE_MAIN = 2;

    // ==================================================================
    // 存入：个人背包 -> 共享存储
    // ==================================================================

    /**
     * 存入背包某一格。
     *
     * @param requested 想存的数量；{@code <= 0} 表示整叠存入
     * @return 实际存入的数量
     */
    public static long depositSlot(EntityPlayer player, int slot, long requested, SharedStorage storage,
        DeltaRecorder recorder) {
        InventoryPlayer inv = player.inventory;
        if (slot < 0 || slot >= PLAYER_SLOT_COUNT) return 0;
        return depositFrom(player, inv, slot, requested, storage, recorder);
    }

    /**
     * 从任意物品栏的某一格里取东西存进共享存储。
     *
     * <p>
     * 泛化到 {@link IInventory} 是为了同时覆盖三种来源：玩家主背包、护甲槽
     * （两者都走 {@link InventoryPlayer}，索引 0..39），以及终端界面里那个
     * <b>属于容器而不属于玩家</b>的 3×3 合成栏（{@code InventoryCrafting}）。
     *
     * @param requested 想存的数量；{@code <= 0} 表示整叠存入
     * @return 实际存入的数量
     */
    public static long depositFrom(EntityPlayer player, IInventory inventory, int index, long requested,
        SharedStorage storage, DeltaRecorder recorder) {
        if (inventory == null || index < 0 || index >= inventory.getSizeInventory()) return 0;

        ItemStack stack = inventory.getStackInSlot(index);
        if (stack == null || stack.getItem() == null) return 0;

        // GT 的流体显示物品：按种类存成流体，而不是当成一个物品。
        // 这样「取出流体 -> 显示物品 -> 再存回去」能闭环。
        if (Config.displayItemBecomesFluid) {
            FluidStack displayed = GTUtility.getFluidFromDisplayStack(stack);
            if (displayed != null && displayed.getFluid() != null && displayed.amount > 0) {
                return depositFluidStack(inventory, index, displayed, requested, storage, recorder);
            }
        }

        ItemKey key = ItemKey.of(stack);
        if (key == null) return 0;

        long available = stack.stackSize;
        long want = requested <= 0L ? available : Math.min(requested, available);
        if (want <= 0L) return 0;

        long stored = storage.insertItem(key, want);
        if (stored <= 0L) return 0;

        stack.stackSize -= (int) stored;
        if (stack.stackSize <= 0) {
            inventory.setInventorySlotContents(index, null);
        }
        // 对 InventoryCrafting 来说 markDirty 是「重算产物」，部分取出后也必须调，
        // 否则合成栏里少了东西、产物格却还显示原来的结果
        inventory.markDirty();
        recorder.item(key);
        return stored;
    }

    /**
     * 把一个装着流体的容器整叠倒进共享存储（容器本身留在背包里）。
     *
     * <p>
     * <b>注意整叠的情况。</b>GT 的流体显示物品并没有覆写 {@code getItemStackLimit}，
     * 所以它的上限是原版的 64；而两个同流体的显示物品 NBT 完全一样，
     * 原版 {@code InventoryPlayer.storeItemStack} 会把它们合并成 {@code stackSize = 2}。
     * 也就是说这一格里可能有 N 份流体，每份 {@code fluid.amount} 毫巴。
     *
     * <p>
     * 如果只按「一份」的数量入账却把整格清空，差额就凭空蒸发了。
     * 所以这里按 {@code fluid.amount * stackSize} 结算，而且
     * <b>要么整叠收下、要么完全不动</b> —— 显示物品的数量信息整体存在 NBT 里，
     * 没法像普通物品那样「扣一半」，做部分扣除只会让数量和信息对不上。
     */
    private static long depositFluidStack(IInventory inventory, int index, FluidStack fluid, long requested,
        SharedStorage storage, DeltaRecorder recorder) {
        ItemStack stack = inventory.getStackInSlot(index);
        if (stack == null) return 0L;

        FluidKey key = FluidKey.of(fluid);
        if (key == null) return 0L;

        long available = (long) fluid.amount * (long) stack.stackSize;
        long want = requested <= 0L ? available : Math.min(requested, available);
        if (want <= 0L) return 0L;

        // 显示物品不可拆分，请求量不足一整叠时直接不做，而不是默默丢掉一部分
        if (want < available) return 0L;

        long stored = storage.insertFluid(key, available);
        if (stored < available) {
            // 存量到顶了：把已经收下的退回去，这一格保持原样
            storage.extractFluid(key, stored);
            return 0L;
        }

        inventory.setInventorySlotContents(index, null);
        inventory.markDirty();
        recorder.fluid(key);
        return stored;
    }

    /**
     * 批量存入。
     *
     * <p>
     * 会<b>跳过玩家当前手持的那一格</b>。这条规则是有意为之：一键倒空背包
     * 是很容易手滑按到的操作，而「工具留在手上」是最不容易出事的默认值。
     * 真想把正拿着的东西也存进去，单独 Shift 点那一格即可。
     *
     * @param scope {@link #SCOPE_ALL} / {@link #SCOPE_HOTBAR} / {@link #SCOPE_MAIN}
     * @return 实际存入的物品总个数
     */
    public static long depositAll(EntityPlayer player, int scope, SharedStorage storage, DeltaRecorder recorder) {
        int from;
        int to;
        switch (scope) {
            case SCOPE_HOTBAR:
                from = 0;
                to = HOTBAR_SIZE;
                break;
            case SCOPE_MAIN:
                from = HOTBAR_SIZE;
                to = INV_SIZE;
                break;
            default:
                from = 0;
                to = INV_SIZE;
                break;
        }

        int held = player.inventory.currentItem;

        long total = 0L;
        for (int i = from; i < to; i++) {
            if (i == held) continue;
            total += depositSlot(player, i, 0L, storage, recorder);
        }
        return total;
    }

    /**
     * 存入鼠标光标上拿着的那一叠。
     *
     * @param requested 想存的数量；{@code <= 0} 表示整叠存入
     */
    public static long depositCursor(EntityPlayer player, long requested, SharedStorage storage,
        DeltaRecorder recorder) {
        InventoryPlayer inv = player.inventory;
        ItemStack cursor = inv.getItemStack();
        if (cursor == null || cursor.getItem() == null) return 0;

        if (Config.displayItemBecomesFluid) {
            FluidStack displayed = GTUtility.getFluidFromDisplayStack(cursor);
            FluidKey displayKey = displayed == null ? null : FluidKey.of(displayed);
            if (displayKey != null && displayed.amount > 0) {
                // 整叠结算，理由和 depositFluidStack 一样：显示物品的数量整体存在 NBT 里，
                // 光标上这一叠有几份就要按几份入账，而且不能做部分扣除
                long total = (long) displayed.amount * (long) cursor.stackSize;
                long stored = storage.insertFluid(displayKey, total);
                if (stored < total) {
                    storage.extractFluid(displayKey, stored);
                    return 0;
                }
                inv.setItemStack(null);
                recorder.fluid(displayKey);
                return stored;
            }
        }

        long want = requested <= 0L ? cursor.stackSize : Math.min(requested, cursor.stackSize);
        if (want <= 0L) return 0;

        ItemKey key = ItemKey.of(cursor);
        if (key == null) return 0;

        long stored = storage.insertItem(key, want);
        if (stored <= 0L) return 0;

        cursor.stackSize -= (int) stored;
        if (cursor.stackSize <= 0) {
            inv.setItemStack(null);
        }
        recorder.item(key);
        return stored;
    }

    /**
     * 从玩家背包里凑出指定条目并存进共享存储。
     *
     * <p>
     * 数量按背包里<b>实际有的</b>封顶，所以外部模组调用时无论传多大的数量都没关系，
     * 不可能凭空造出物品。用于「别的模组绕过点击流程直接往虚拟槽位里塞东西」那条路。
     *
     * @param amount 想存的数量；{@code <= 0} 表示有多少存多少
     * @return 实际存入的数量
     */
    public static long depositMatching(EntityPlayer player, ItemKey key, long amount, SharedStorage storage,
        DeltaRecorder recorder) {
        if (key == null) return 0L;

        long remaining = amount <= 0L ? Long.MAX_VALUE : amount;
        long total = 0L;

        for (int i = 0; i < INV_SIZE && remaining > 0L; i++) {
            ItemStack slot = player.inventory.mainInventory[i];
            if (slot == null || slot.getItem() == null) continue;
            if (!key.equals(ItemKey.of(slot))) continue;

            long got = depositSlot(player, i, Math.min(remaining, slot.stackSize), storage, recorder);
            total += got;
            remaining -= got;
        }
        return total;
    }

    /**
     * 从共享存储取一个物品堆到光标上，语义和原版容器左键点击一致。
     *
     * <p>
     * 光标不为空时必须是同一种物品；数量由光标自身的堆叠上限封顶，不能因为客户端
     * 传了一个大数字就把光标撑爆。
     */
    public static long withdrawToCursor(EntityPlayer player, ItemKey key, long requested, SharedStorage storage,
        DeltaRecorder recorder) {
        if (player == null || key == null) return 0L;

        InventoryPlayer inv = player.inventory;
        ItemStack cursor = inv.getItemStack();
        if (cursor != null && !key.equals(ItemKey.of(cursor))) return 0L;

        int limit = maxStackSize(cursor == null ? key.prototype() : cursor);
        long space = limit - (cursor == null ? 0L : cursor.stackSize);
        if (space <= 0L) return 0L;

        long want = requested <= 0L ? space : Math.min(requested, space);
        long taken = storage.extractItem(key, want);
        if (taken <= 0L) return 0L;

        if (cursor == null) {
            inv.setItemStack(key.prototype((int) taken));
        } else {
            cursor.stackSize += (int) taken;
        }
        inv.markDirty();
        updateHeldItem(player);
        recorder.item(key);
        return taken;
    }

    /**
     * 原版「双击收集」的服务端实现。
     *
     * <p>
     * 先收集玩家背包和当前容器的合成栏，再用共享存储补足光标。这样双击不会在客户端
     * 直接修改虚拟槽位，也不会因为虚拟槽位返回的是临时 {@link ItemStack} 而刷出物品。
     */
    public static long collectToCursor(EntityPlayer player, IInventory extraInventory, ItemKey key, long requested,
        SharedStorage storage, DeltaRecorder recorder) {
        if (player == null || key == null) return 0L;

        InventoryPlayer inv = player.inventory;
        ItemStack cursor = inv.getItemStack();
        if (cursor != null && !key.equals(ItemKey.of(cursor))) return 0L;

        int limit = maxStackSize(cursor == null ? key.prototype() : cursor);
        long space = limit - (cursor == null ? 0L : cursor.stackSize);
        if (space <= 0L) return 0L;

        long want = requested <= 0L ? space : Math.min(requested, space);
        long moved = 0L;

        for (int i = 0; i < INV_SIZE && moved < want; i++) {
            ItemStack source = inv.mainInventory[i];
            long got = collectFromStack(inv, source, key, want - moved);
            if (got > 0L) {
                if (source.stackSize <= 0) inv.mainInventory[i] = null;
                moved += got;
            }
        }

        for (int i = 0; i < ARMOR_SIZE && moved < want; i++) {
            ItemStack source = inv.armorInventory[i];
            long got = collectFromStack(inv, source, key, want - moved);
            if (got > 0L) {
                if (source.stackSize <= 0) inv.armorInventory[i] = null;
                moved += got;
            }
        }

        if (extraInventory != null) {
            for (int i = 0; i < extraInventory.getSizeInventory() && moved < want; i++) {
                ItemStack source = extraInventory.getStackInSlot(i);
                long got = collectFromStack(inv, source, key, want - moved);
                if (got > 0L) {
                    if (source.stackSize <= 0) extraInventory.setInventorySlotContents(i, null);
                    extraInventory.markDirty();
                    moved += got;
                }
            }
        }

        long fromStorage = 0L;
        if (moved < want) {
            fromStorage = storage.extractItem(key, want - moved);
            if (fromStorage > 0L) {
                cursor = inv.getItemStack();
                if (cursor == null) inv.setItemStack(key.prototype((int) fromStorage));
                else cursor.stackSize += (int) fromStorage;
                moved += fromStorage;
                recorder.item(key);
            }
        }

        if (moved > 0L) {
            inv.markDirty();
            updateHeldItem(player);
        }
        return moved;
    }

    /** 把一个真实槽里的同种物品并入光标，返回实际移动数。 */
    private static long collectFromStack(InventoryPlayer inv, ItemStack source, ItemKey key, long requested) {
        if (source == null || source.getItem() == null || requested <= 0L) return 0L;
        if (!key.equals(ItemKey.of(source))) return 0L;

        ItemStack cursor = inv.getItemStack();
        int limit = maxStackSize(cursor == null ? key.prototype() : cursor);
        long space = limit - (cursor == null ? 0L : cursor.stackSize);
        if (space <= 0L) return 0L;

        int moved = (int) Math.min(Math.min(space, requested), source.stackSize);
        if (moved <= 0) return 0L;

        if (cursor == null) {
            inv.setItemStack(source.copy());
            inv.getItemStack().stackSize = moved;
        } else {
            cursor.stackSize += moved;
        }
        source.stackSize -= moved;
        return moved;
    }

    /**
     * 数字键快捷交换：把共享存储里的一个堆放入快捷栏指定格，原来的堆存回共享存储。
     * 虚拟槽位不能交给原版 mode=2 直接交换，因此整个动作在服务端原子完成。
     */
    public static long swapHotbarItem(EntityPlayer player, int hotbarSlot, ItemKey key, long requested,
        SharedStorage storage, DeltaRecorder recorder) {
        if (player == null || key == null || hotbarSlot < 0 || hotbarSlot >= HOTBAR_SIZE) return 0L;

        ItemStack prototype = key.prototype();
        int limit = maxStackSize(prototype);
        long want = requested <= 0L ? limit : Math.min(requested, limit);
        if (want <= 0L) return 0L;

        InventoryPlayer inv = player.inventory;
        ItemStack old = inv.mainInventory[hotbarSlot];
        ItemKey oldKey = old == null ? null : ItemKey.of(old);
        long oldStored = 0L;

        if (old != null) {
            if (oldKey == null) return 0L;
            oldStored = storage.insertItem(oldKey, old.stackSize);
            if (oldStored < old.stackSize) {
                if (oldStored > 0L) storage.extractItem(oldKey, oldStored);
                return 0L;
            }
        }

        long taken = storage.extractItem(key, want);
        if (taken <= 0L) {
            if (oldStored > 0L) storage.extractItem(oldKey, oldStored);
            return 0L;
        }

        inv.mainInventory[hotbarSlot] = key.prototype((int) taken);
        inv.markDirty();
        recorder.item(key);
        if (oldKey != null) recorder.item(oldKey);
        return taken;
    }

    /** 从共享存储取出物品并丢到世界，供共享虚拟槽位的 Q / Ctrl+Q 使用。 */
    public static long dropItem(EntityPlayer player, ItemKey key, long requested, SharedStorage storage,
        DeltaRecorder recorder) {
        if (player == null || key == null) return 0L;

        long want = requested <= 0L ? storage.getItemAmount(key) : requested;
        if (want <= 0L) return 0L;

        long taken = storage.extractItem(key, want);
        if (taken <= 0L) return 0L;

        int stackLimit = maxStackSize(key.prototype());
        long remaining = taken;
        while (remaining > 0L) {
            int size = (int) Math.min(remaining, stackLimit);
            ItemStack dropped = key.prototype(size);
            if (dropped == null) {
                storage.insertItem(key, remaining);
                return 0L;
            }
            player.dropPlayerItemWithRandomChoice(dropped, true);
            remaining -= size;
        }

        recorder.item(key);
        return taken;
    }

    /** 丢弃共享存储中的全部物品；背包空间不参与这个动作。 */
    public static long dropAllItems(EntityPlayer player, SharedStorage storage, DeltaRecorder recorder) {
        long total = 0L;
        for (java.util.Map.Entry<ItemKey, Long> entry : storage.snapshotItems()) {
            total += dropItem(player, entry.getKey(), 0L, storage, recorder);
        }
        return total;
    }

    // ==================================================================
    // 取出：共享存储 -> 个人背包
    // ==================================================================

    /**
     * 取物品到背包。
     *
     * <p>
     * 分两阶段：先<b>算</b>清楚能塞进哪些格子、各塞多少，再从存储里扣，
     * 最后才真正写进背包。反过来做的话，背包满了就会把东西从存储里扣掉却无处安放。
     *
     * @param requested 想取的数量；{@code <= 0} 表示「尽量塞满背包」
     * @return 实际取出的数量
     */
    public static long withdrawItem(EntityPlayer player, ItemKey key, long requested, SharedStorage storage,
        DeltaRecorder recorder) {
        return withdrawItem(player, key, requested, false, storage, recorder);
    }

    /**
     * 取物品到玩家的空槽位；不会往已有同类堆叠里合并。
     *
     * <p>
     * 这是 Inventory Bogo Sorter 的 Ctrl+右键语义。共享存储没有有限的虚拟槽位，
     * 因此这里的「空槽」指玩家背包里的空槽。
     */
    public static long withdrawItemToEmptySlot(EntityPlayer player, ItemKey key, long requested, SharedStorage storage,
        DeltaRecorder recorder) {
        return withdrawItem(player, key, requested, true, storage, recorder);
    }

    /**
     * 把共享存储中的物品尽量全部转移到玩家背包。
     *
     * <p>
     * 逐个键处理，而不是先把存储表清空；玩家背包装满后，剩余条目仍留在共享存储里。
     */
    public static long withdrawAllItems(EntityPlayer player, SharedStorage storage, DeltaRecorder recorder) {
        long total = 0L;
        for (java.util.Map.Entry<ItemKey, Long> entry : storage.snapshotItems()) {
            total += withdrawItem(player, entry.getKey(), 0L, storage, recorder);
        }
        return total;
    }

    private static long withdrawItem(EntityPlayer player, ItemKey key, long requested, boolean emptyOnly,
        SharedStorage storage, DeltaRecorder recorder) {
        if (key == null) return 0L;

        InventoryPlayer inv = player.inventory;
        long available = storage.getItemAmount(key);
        if (available <= 0L) {
            // 客户端点了一个服务端存储里<b>不存在</b>的键。
            //
            // 这条以前是静默 return 0，结果就是玩家看到「点了没反应」，
            // 而且一点线索都没有 —— 服务端和客户端的条目对不上时（模组版本
            // 不一致、键序列化对不上……）就属于这一类，值得留下证据。
            //
            // 把键本身打出来：它带着物品、元数据和 NBT 的特征，足够定位是哪一个。
            FutaGtnhMod.LOG
                .warn("共享存储：玩家 {} 请求取出一个存里没有的条目 {}（数量 {}），已忽略", player.getCommandSenderName(), key, requested);
            return 0L;
        }

        long want = requested <= 0L ? available : Math.min(requested, available);
        if (want <= 0L) return 0L;

        // --- 阶段一：规划 ---
        int[] plan = new int[INV_SIZE];
        long planned = 0L;

        if (!emptyOnly) {
            // 先往背包里已有的同类堆叠上补，避免把半叠的补成一整叠却占掉空格
            for (int i = 0; i < INV_SIZE && planned < want; i++) {
                ItemStack existing = inv.mainInventory[i];
                if (existing == null || existing.getItem() == null) continue;
                if (!key.equals(ItemKey.of(existing))) continue;

                int space = maxStackSize(existing) - existing.stackSize;
                if (space <= 0) continue;

                int give = (int) Math.min(space, want - planned);
                plan[i] = give;
                planned += give;
            }
        }

        // 再用空格子。原型栈只造一次 —— 这里每格都调一次 getMaxStackSize()，
        // 没必要为每个空格子都重新解一份 NBT
        int emptySlotCapacity = maxStackSize(key.prototype());
        for (int i = 0; i < INV_SIZE && planned < want; i++) {
            if (inv.mainInventory[i] != null) continue;

            int give = (int) Math.min(emptySlotCapacity, want - planned);
            plan[i] = give;
            planned += give;
        }

        if (planned <= 0L) {
            // 存储里有，但背包一个格子都放不下。
            //
            // 这也是以前静默失败的一条：玩家点了半天不知道为什么没反应。
            // 尤其是 maxStackSize == 1 的物品（GT 工具就是），
            // 「先补进已有的同类堆叠」那一步对它永远是 0 空间，
            // 只能靠空格子 —— 背包满了就真的一点办法都没有。
            FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.msg.no_space");
            return 0L;
        }

        // --- 阶段二：从存储扣。规划时已经确认存量够，这一步必定成功 ---
        long taken = storage.extractItem(key, planned);
        if (taken <= 0L) return 0L;

        // --- 阶段三：写进背包 ---
        long remaining = taken;
        for (int i = 0; i < INV_SIZE && remaining > 0L; i++) {
            int give = (int) Math.min(plan[i], remaining);
            if (give <= 0) continue;

            ItemStack existing = inv.mainInventory[i];
            if (existing == null) {
                inv.mainInventory[i] = key.prototype(give);
            } else {
                existing.stackSize += give;
            }
            remaining -= give;
        }

        // 阶段二扣的和阶段三放的理论上一定相等；不等说明有 bug，
        // 这时把差额补回存储，宁可玩家少拿也不能让总量对不上。
        if (remaining > 0L) {
            storage.insertItem(key, remaining);
            taken -= remaining;
        }

        inv.markDirty();
        recorder.item(key);
        return taken;
    }

    /**
     * 用共享存储里的流体灌装背包里的容器。
     *
     * <p>
     * 会优先灌「已经有同种流体但没装满」的容器，其次才是空容器。
     *
     * @param requested 想要多少毫巴；{@code <= 0} 表示尽量多灌
     * @return 实际灌进容器的毫巴数
     */
    public static long fillContainers(EntityPlayer player, FluidKey key, long requested, SharedStorage storage,
        DeltaRecorder recorder) {
        if (key == null) return 0L;

        InventoryPlayer inv = player.inventory;
        long available = storage.getFluidAmount(key);
        if (available <= 0L) return 0L;

        long want = requested <= 0L ? available : Math.min(requested, available);
        if (want <= 0L) return 0L;

        long remaining = want;
        long filled = 0L;

        for (int i = 0; i < INV_SIZE && remaining > 0L; i++) {
            // 一格里有整叠空容器时，只要流体还够就继续灌<b>同一格</b>。
            // 不循环的话，一个「16 个空桶」的格子每次点击只会灌出 1 个水桶 ——
            // Shift + 左键的「尽量灌」就名不副实了。
            while (remaining > 0L) {
                ItemStack slot = inv.mainInventory[i];
                if (slot == null || slot.getItem() == null) break;

                // 先模拟：拿一个副本去试，试失败不会污染玩家背包里的真容器
                ItemStack probe = slot.stackSize > 1 ? singleCopy(slot) : slot;
                FluidContainerHelper.FillResult result = FluidContainerHelper.fill(probe, key, remaining);
                if (result == null || result.consumed <= 0L) break;

                long taken = storage.extractFluid(key, result.consumed);
                if (taken <= 0L) break;

                ItemStack replacement = result.container;
                replacement.stackSize = 1;
                if (!replaceOne(inv, i, replacement)) {
                    // 背包腾不出地方放灌好的容器，把扣掉的流体还回去，整个停手
                    storage.insertFluid(key, taken);
                    remaining = 0L;
                    break;
                }

                filled += taken;
                remaining -= taken;
            }
        }

        if (filled > 0L) {
            inv.markDirty();
            recorder.fluid(key);
        }
        return filled;
    }

    /**
     * 取出 GT 的流体显示物品。
     *
     * <p>
     * 数量存在 NBT 的 long 里（{@code mFluidDisplayAmount}），远不止 1000 mB，
     * 所以一个显示物品就能代表一大批流体。
     *
     * <p>
     * 这里刻意<b>先扣后给、给失败就还</b>，而且会读回来核对 GT 到底记下了多少 ——
     * 万一 GT 那边截断或拒收，玩家不会平白少东西。
     *
     * @return 实际取出的毫巴数
     */
    public static long takeFluidDisplay(EntityPlayer player, FluidKey key, long requested, SharedStorage storage,
        DeltaRecorder recorder) {
        if (key == null) return 0L;

        long available = storage.getFluidAmount(key);
        if (available <= 0L) return 0L;

        long want = requested <= 0L ? available : Math.min(requested, available);
        // 单个显示物品的数量上限受 FluidStack.amount 是 int 的限制
        want = Math.min(want, Integer.MAX_VALUE);
        if (want <= 0L) return 0L;

        long taken = storage.extractFluid(key, want);
        if (taken <= 0L) return 0L;

        ItemStack display = GTUtility.getFluidDisplayStack(key.prototype(taken), true);
        FluidStack readBack = display == null ? null : GTUtility.getFluidFromDisplayStack(display);
        if (readBack == null || readBack.amount <= 0) {
            // GT 没能把数量记进去（例如版本差异导致布尔量含义不同），整体回滚
            storage.insertFluid(key, taken);
            return 0L;
        }

        if (readBack.amount < taken) {
            // GT 只记下了一部分，把差额还回存储，只按它真正记下的量算
            storage.insertFluid(key, taken - readBack.amount);
            taken = readBack.amount;
            display = GTUtility.getFluidDisplayStack(key.prototype(taken), true);
            if (display == null) {
                storage.insertFluid(key, taken);
                return 0L;
            }
        }

        if (!player.inventory.addItemStackToInventory(display)) {
            storage.insertFluid(key, taken);
            return 0L;
        }

        recorder.fluid(key);
        return taken;
    }

    /**
     * 把鼠标光标上那个容器里的流体倒进共享存储。
     *
     * <p>
     * 光标上的物品<b>不属于 {@code mainInventory}</b>（它是
     * {@code InventoryPlayer.itemStack} 这个独立字段），所以
     * {@link #drainContainers} 那套背包遍历碰不到它，必须单独处理。
     *
     * <p>
     * 数量守恒和别处一样：先把流体收下，收不下就整体不做；
     * 只有确认收下了才把光标换成倒空后的容器。
     *
     * @return 实际存入的毫巴数
     */
    public static long drainCursorContainer(EntityPlayer player, SharedStorage storage, DeltaRecorder recorder) {
        InventoryPlayer inv = player.inventory;
        ItemStack cursor = inv.getItemStack();
        if (cursor == null || cursor.getItem() == null) return 0L;

        FluidContainerHelper.DrainResult result = FluidContainerHelper.drain(cursor);
        if (result == null || result.fluid == null || result.fluid.amount <= 0) return 0L;

        FluidKey key = FluidKey.of(result.fluid);
        if (key == null) return 0L;

        // 必须按整叠结算。FluidContainerHelper.drain 内部把探测用的副本压成 stackSize = 1，
        // 再把结果叠数恢复成原叠数 —— 也就是说 result.fluid.amount 只是「一个容器」的量。
        // 光标上要是有 16 个牛奶桶，只按一份入账却把整叠换成 16 个空桶，就等于抹掉 15 桶牛奶。
        long total = (long) result.fluid.amount * (long) cursor.stackSize;

        long stored = storage.insertFluid(key, total);
        if (stored < total) {
            // 存量到顶了：把已经收下的退回去，光标保持原样
            storage.extractFluid(key, stored);
            return 0L;
        }

        // 空容器留在光标上，叠数保持不变（16 个空桶倒完还是 16 个空桶）
        ItemStack emptied = result.container;
        emptied.stackSize = cursor.stackSize;
        inv.setItemStack(emptied);

        recorder.fluid(key);
        return stored;
    }

    /**
     * 把背包里所有装着流体的容器都倒进共享存储（容器本身留在背包里）。
     *
     * <p>
     * 按<b>整格</b>处理：一格里有 16 个装满的桶，就一次倒出 16 份、整格换成 16 个空桶。
     * 早先的写法借 {@code replaceOne} 一次只换一个，导致「倒空容器」对整叠容器
     * 每点一次只倒一格里的一个 —— 不是丢东西，但按钮名不副实。
     *
     * @return 实际存入的毫巴数
     */
    public static long drainContainers(EntityPlayer player, SharedStorage storage, DeltaRecorder recorder) {
        InventoryPlayer inv = player.inventory;
        long total = 0L;

        for (int i = 0; i < INV_SIZE; i++) {
            ItemStack slot = inv.mainInventory[i];
            if (slot == null || slot.getItem() == null) continue;

            FluidContainerHelper.DrainResult result = FluidContainerHelper.drain(slot);
            if (result == null || result.fluid == null || result.fluid.amount <= 0) continue;

            FluidKey key = FluidKey.of(result.fluid);
            if (key == null) continue;

            // 同 drainCursorContainer：result.fluid.amount 只是「一个容器」的量
            long amount = (long) result.fluid.amount * (long) slot.stackSize;

            long stored = storage.insertFluid(key, amount);
            if (stored < amount) {
                // 存储到顶了（理论上不可能），把没存进去的留下，别凭空吃掉
                storage.extractFluid(key, stored);
                break;
            }

            // 整格替换：叠数不变，只是从「装满的」变成「倒空的」
            ItemStack emptied = result.container;
            emptied.stackSize = slot.stackSize;
            inv.mainInventory[i] = emptied;

            total += stored;
            recorder.fluid(key);
        }

        if (total > 0L) {
            inv.markDirty();
        }
        return total;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /**
     * 把背包第 {@code slot} 格的<b>一个</b>物品替换成 {@code replacement}。
     *
     * <p>
     * 整叠的情况要拆：先减一个，再把新物品塞进背包；塞不进去就把数量加回来。
     * 这样「16 个空桶灌成 16 个水桶」这种操作不会因为背包满而丢桶。
     *
     * @return 是否替换成功
     */
    private static boolean replaceOne(InventoryPlayer inv, int slot, ItemStack replacement) {
        ItemStack current = inv.mainInventory[slot];
        if (current == null) return false;

        replacement.stackSize = 1;

        if (current.stackSize <= 1) {
            inv.mainInventory[slot] = replacement;
            return true;
        }

        current.stackSize--;
        if (!inv.addItemStackToInventory(replacement)) {
            current.stackSize++;
            return false;
        }
        return true;
    }

    private static ItemStack singleCopy(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        return copy;
    }

    /** {@code updateHeldItem} 是服务端玩家实体的方法，客户端/通用类型不能直接调用。 */
    private static void updateHeldItem(EntityPlayer player) {
        if (player instanceof EntityPlayerMP) {
            ((EntityPlayerMP) player).updateHeldItem();
        }
    }

    /**
     * 玩家背包单格能放多少。
     *
     * <p>
     * 用原版 {@code getMaxStackSize()}：GT5U 并没有提供「查询物品最大堆叠」的公共 API
     * （只有 {@code ItemStackSizeCalculator} 这个接口，而且 GT 自己没有实现类），
     * 所以按原版语义走。返回值异常时退回 64，避免出现 0 或负数导致死循环。
     */
    private static int maxStackSize(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return 64;
        int limit = stack.getMaxStackSize();
        return limit <= 0 ? 64 : limit;
    }
}
