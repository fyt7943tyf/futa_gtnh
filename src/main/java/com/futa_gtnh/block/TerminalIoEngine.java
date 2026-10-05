package com.futa_gtnh.block;

import java.util.List;
import java.util.Map;

import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidHandler;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 共享终端方块的<b>主动搬运</b>：按配置往相邻的容器抽东西 / 送东西。
 *
 * <p>
 * 方块本身仍然是「直通共享存储」的（放进去的东西立刻进仓库，抽走的东西直接扣仓库），
 * 这里只是替玩家做那件本来要 GT 覆盖板来做的事：<b>每个面配一个方向</b>，
 * 然后每几 tick 自己动一次。
 *
 * <p>
 * <b>守恒是硬要求。</b>两个方向都可能「搬一半就搬不动了」（目标满了、
 * 源被别的机器抽走了），所以每一处都按同一个套路写：
 * <ol>
 * <li>先问清楚「能搬多少」（模拟，不改任何状态）；</li>
 * <li>真搬；</li>
 * <li><b>搬不进去的部分原样退回去</b>，绝不允许出现「扣了但没送到」或者
 * 「送到了但没扣」—— 前者是丢东西，后者是刷东西。</li>
 * </ol>
 *
 * <p>
 * 相邻方块只认 Forge 的 {@link IInventory} / {@link IFluidHandler}：
 * 箱子、桶、GT 的大多数机器和储罐都实现了这两个接口。
 * 只认这两个接口是有意的 —— 想接 GT 管道的话，让管道<b>来抽</b>这个终端就行
 * （那本来就通，见 {@link TileEntitySharedTerminal}），不需要我们反向去推。
 */
final class TerminalIoEngine {

    private TerminalIoEngine() {}

    /**
     * 六个面里，哪几个面挨着能搬东西的方块。
     *
     * <p>
     * 位 {@code i} 为 1 表示 {@code ForgeDirection.getOrientation(i)} 那一面有目标。
     * 这个结果只用来给配置界面画那圈半透明的小方块 —— 让玩家一眼看出哪几面真的接了东西；
     * 真正的搬运每次还是现场去问容器，不依赖这个位掩码。
     *
     * @param fluid true = 看流体（{@link IFluidHandler}），false = 看物品（{@link IInventory}）
     */
    static int targetMask(World world, int x, int y, int z, boolean fluid) {
        if (world == null) return 0;

        int mask = 0;
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity tile = world.getTileEntity(x + face.offsetX, y + face.offsetY, z + face.offsetZ);
            if (tile == null) continue;
            if (fluid ? tile instanceof IFluidHandler : tile instanceof IInventory) {
                mask |= 1 << face.ordinal();
            }
        }
        return mask;
    }

    /** 把六个面各走一遍。调用方保证这是服务端、而且配置里至少有一个面需要动。 */
    static void tick(World world, int x, int y, int z, TerminalIoConfig config) {
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            TerminalIoConfig.Mode itemMode = config.getMode(face, false);
            TerminalIoConfig.Mode fluidMode = config.getMode(face, true);
            if (itemMode == TerminalIoConfig.Mode.OFF && fluidMode == TerminalIoConfig.Mode.OFF) continue;

            TileEntity neighbour = world.getTileEntity(x + face.offsetX, y + face.offsetY, z + face.offsetZ);
            if (neighbour == null || neighbour instanceof TileEntitySharedTerminal) continue;
            // IO 节点不是容器，贴着也不该被当成搬运目标
            if (neighbour instanceof TileEntityIoNode) continue;

            // 邻居看我们的方向：Forge 的接口要的是「从哪一面来的」
            ForgeDirection side = face.getOpposite();

            if (itemMode != TerminalIoConfig.Mode.OFF && neighbour instanceof IInventory) {
                IInventory inventory = (IInventory) neighbour;
                if (itemMode.pulls()) pullItems(inventory, side, config);
                if (itemMode.pushes()) pushItems(inventory, side, config, face);
            }

            if (fluidMode != TerminalIoConfig.Mode.OFF && neighbour instanceof IFluidHandler) {
                IFluidHandler handler = (IFluidHandler) neighbour;
                if (fluidMode.pulls()) pullFluid(handler, side, config);
                if (fluidMode.pushes()) pushFluid(handler, side, config, face);
            }
        }
    }

    // ==================================================================
    // 物品
    // ==================================================================

    /** 从相邻容器里把东西抽进共享存储。 */
    private static void pullItems(IInventory inventory, ForgeDirection side, TerminalIoConfig config) {
        int budget = Math.max(1, config.getItemsPerOperation());
        SharedStorage storage = SharedStorageManager.getStorage();

        for (int slot = 0; slot < inventory.getSizeInventory() && budget > 0; slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) continue;
            if (!canExtract(inventory, slot, stack, side)) continue;

            ItemKey key = ItemKey.of(stack);
            if (key == null) continue;

            int want = Math.min(budget, stack.stackSize);
            long stored = storage.insertItem(stack, want);
            if (stored <= 0L) continue;

            int remaining = stack.stackSize - (int) stored;
            // 扣源那一格：数量为 0 时放 null。用副本写回去，
            // 免得把调用方还拿着的那个 ItemStack 对象改坏
            inventory.setInventorySlotContents(slot, remaining <= 0 ? null : withSize(stack, remaining));
            inventory.markDirty();

            // 有些容器（别的模组写的）会「收下这个调用但什么都不改」。
            // 那样我们就等于凭空多了一份东西，所以回头验一下：
            // 源那一格如果还是原来的数量，就把刚存进去的吐回去。
            if (!sourceShrank(inventory, slot, stack, (int) stored)) {
                storage.extractItem(key, stored);
                continue;
            }

            SharedStorageManager.broadcastItemChange(key);
            budget -= (int) stored;
        }
    }

    /** @return 源那一格是不是真的少了 {@code moved} 个 */
    private static boolean sourceShrank(IInventory inventory, int slot, ItemStack before, int moved) {
        ItemStack current = inventory.getStackInSlot(slot);
        if (current == null || current.getItem() == null) return true;
        return current.stackSize <= before.stackSize - moved;
    }

    /**
     * 把共享存储里符合条件的东西送进相邻容器。
     *
     * <p>
     * <b>同一个条目在一轮里可以搬好几叠。</b>档位说的是「这一轮总共搬多少个」，
     * 而一次提取最多一叠（原版堆叠上限 64）—— 不循环的话，「1024 个/轮」这种档位
     * 在只筛了一种物品时会被卡在 64 个，档位就成了摆设。
     * 循环的终止条件是目标收不下了或者额度用完，两件事都是当场问出来的。
     */
    private static void pushItems(IInventory inventory, ForgeDirection side, TerminalIoConfig config,
        ForgeDirection face) {
        if (config.getOutputFilter(face)
            .isEmpty(false)) return;
        int budget = Math.max(1, config.getItemsPerOperation());
        SharedStorage storage = SharedStorageManager.getStorage();

        List<Map.Entry<ItemKey, Long>> snapshot = storage.snapshotItems();
        for (Map.Entry<ItemKey, Long> entry : snapshot) {
            if (budget <= 0) return;

            ItemKey key = entry.getKey();
            long available = entry.getValue() == null ? 0L
                : entry.getValue()
                    .longValue();
            if (available <= 0L) continue;

            ItemStack prototype = key.prototype();
            if (prototype == null || prototype.getItem() == null) continue;
            if (!config.matchesOutput(face, prototype)) continue;

            int maxSize = Math.max(1, prototype.getMaxStackSize());

            while (budget > 0 && available > 0L) {
                int want = (int) Math.min(Math.min((long) budget, (long) maxSize), available);
                // 目标装不下就别去扣仓库：先算清楚有多少空位（额度内够用就停）
                want = Math.min(want, freeSpaceFor(inventory, side, prototype, budget));
                if (want <= 0) break;

                long taken = storage.extractItem(key, want);
                if (taken <= 0L) break;

                int accepted = insertInto(inventory, side, key.prototype(taken));
                if (accepted < taken) {
                    // 目标中途不收（比如别的机器同时塞满了）：剩下的原样放回仓库。
                    // 宁可退回去，也不能凭空多出来。
                    // 这是回滚不是存款 —— 用不受上限的入口，否则上限一挡，东西就没了
                    storage.insertItemManual(key, taken - accepted);
                }
                inventory.markDirty();
                SharedStorageManager.broadcastItemChange(key);

                budget -= accepted;
                available -= taken;
                if (accepted < taken) break;
            }
        }
    }

    /**
     * @return 这个容器还能再装下多少个 {@code prototype}（按堆叠合并规则算）
     *
     *         <p>
     *         {@code limit} 是「再算也没意义了」的上限：调用方这一轮最多只要这么多，
     *         没有必要为了一个精确数字把几百格的机器库存全扫一遍。
     */
    private static int freeSpaceFor(IInventory inventory, ForgeDirection side, ItemStack prototype, int limit) {
        int space = 0;
        int maxSize = Math.max(1, prototype.getMaxStackSize());
        int stopAt = Math.max(1, Math.min(limit, maxSize * 512));

        for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
            ItemStack existing = inventory.getStackInSlot(slot);
            if (existing == null || existing.getItem() == null || existing.stackSize <= 0) {
                if (!canInsert(inventory, slot, prototype, side)) continue;
                space += maxSize;
            } else if (sameItem(existing, prototype) && canInsert(inventory, slot, prototype, side)) {
                space += Math.max(0, Math.min(maxSize, existing.getMaxStackSize()) - existing.stackSize);
            }
            if (space >= stopAt) break;
        }
        return space;
    }

    /**
     * 把 {@code stack} 塞进容器，尽量合并到已有的同种堆里。
     *
     * <p>
     * 原版的 {@code IInventory} 没有「帮我加进去」这样的方法（那是
     * {@code InventoryPlayer} 自己的），所以这里手写一遍，并且<b>只填能填的部分</b>：
     * 返回值就是真正放进去的数量，调用方按这个数字去扣共享存储。
     *
     * @return 实际放进去的数量
     */
    private static int insertInto(IInventory inventory, ForgeDirection side, ItemStack stack) {
        int remaining = stack == null ? 0 : stack.stackSize;
        if (remaining <= 0) return 0;

        int maxSize = Math.max(1, stack.getMaxStackSize());
        // 两趟：先并入已有的同类堆，再找空格。和原版 addItemStackToInventory 一个顺序
        for (int pass = 0; pass < 2 && remaining > 0; pass++) {
            for (int slot = 0; slot < inventory.getSizeInventory() && remaining > 0; slot++) {
                if (!canInsert(inventory, slot, stack, side)) continue;
                ItemStack existing = inventory.getStackInSlot(slot);

                if (pass == 0) {
                    if (existing == null || !sameItem(existing, stack)) continue;
                    int room = Math.min(maxSize, existing.getMaxStackSize()) - existing.stackSize;
                    if (room <= 0) continue;
                    int moved = Math.min(room, remaining);
                    existing.stackSize += moved;
                    inventory.setInventorySlotContents(slot, existing);
                    remaining -= moved;
                } else {
                    if (existing != null && existing.getItem() != null && existing.stackSize > 0) continue;
                    if (!canInsert(inventory, slot, stack, side)) continue;
                    int moved = Math.min(maxSize, remaining);
                    inventory.setInventorySlotContents(slot, withSize(stack, moved));
                    remaining -= moved;
                }
            }
        }
        return stack.stackSize - remaining;
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage()
            && ItemStack.areItemStackTagsEqual(a, b);
    }

    private static ItemStack withSize(ItemStack stack, int size) {
        ItemStack copy = stack.copy();
        copy.stackSize = size;
        return copy;
    }

    private static boolean canInsert(IInventory inventory, int slot, ItemStack stack, ForgeDirection side) {
        if (inventory instanceof ISidedInventory) {
            return isAccessible((ISidedInventory) inventory, slot, side)
                && ((ISidedInventory) inventory).canInsertItem(slot, stack, side.ordinal());
        }
        return inventory.isItemValidForSlot(slot, stack);
    }

    private static boolean canExtract(IInventory inventory, int slot, ItemStack stack, ForgeDirection side) {
        if (inventory instanceof ISidedInventory) {
            return isAccessible((ISidedInventory) inventory, slot, side)
                && ((ISidedInventory) inventory).canExtractItem(slot, stack, side.ordinal());
        }
        return true;
    }

    private static boolean isAccessible(ISidedInventory inventory, int slot, ForgeDirection side) {
        // ★ GT 的机器 / 输入总线 / 输入仓跳过这道闸门。
        //
        // GT 的 getAccessibleSlotsFromSide 对绝大多数机器常年返回空数组（细节见
        // TileEntityIoNode.isItemTarget 的说明），拿它当闸门的结果是「旁边摆着 GT 机器，
        // 既搬不动、也不显示连接」。GT 的面访问实际由 canInsertItem / canExtractItem 控制，
        // 那两个方法在调用处照样会走 —— 它们才是权威，这个槽位表不是。
        if (inventory instanceof gregtech.api.interfaces.tileentity.IGregTechTileEntity) return true;

        for (int accessible : inventory.getAccessibleSlotsFromSide(side.ordinal())) {
            if (accessible == slot) return true;
        }
        return false;
    }

    // ==================================================================
    // 流体
    // ==================================================================

    /** 从相邻容器里把流体抽进共享存储。 */
    private static void pullFluid(IFluidHandler handler, ForgeDirection side, TerminalIoConfig config) {
        int budget = Math.max(1, config.getFluidPerOperation());

        FluidStack preview = handler.drain(side, budget, false);
        if (preview == null || preview.getFluid() == null || preview.amount <= 0) return;

        FluidStack drained = handler.drain(side, preview.amount, true);
        if (drained == null || drained.getFluid() == null || drained.amount <= 0) return;

        SharedStorage storage = SharedStorageManager.getStorage();
        long stored = storage.insertFluid(drained, drained.amount);
        if (stored < drained.amount) {
            // 存储顶到上限了（理论上不可能）：没存进去的那部分原样灌回去
            int back = (int) (drained.amount - stored);
            handler.fill(side, new FluidStack(drained.getFluid(), back), true);
            if (stored <= 0L) return;
        }

        FluidKey key = FluidKey.of(drained);
        if (key != null) SharedStorageManager.broadcastFluidChange(key);
    }

    /**
     * 把共享存储里符合条件的流体送进相邻容器。
     *
     * <p>
     * 和物品那边同理，<b>同一个条目在一轮里可以灌好几次</b>：
     * 档位说的是「这一轮总共搬多少」，而一次 {@code fill} 只灌得进目标当时收得下的量
     * （机器内部的小缓冲罐往往一次只收一点点）。不循环的话，
     * 「2000 万 mB/轮」在只筛了一种流体时会被卡在一次 fill 的量上。
     * 每轮循环至少推进 1 mB，所以额度用完一定停得下来。
     */
    private static void pushFluid(IFluidHandler handler, ForgeDirection side, TerminalIoConfig config,
        ForgeDirection face) {
        if (config.getOutputFilter(face)
            .isEmpty(true)) return;
        int budget = Math.max(1, config.getFluidPerOperation());
        SharedStorage storage = SharedStorageManager.getStorage();

        List<Map.Entry<FluidKey, Long>> snapshot = storage.snapshotFluids();
        for (Map.Entry<FluidKey, Long> entry : snapshot) {
            FluidKey key = entry.getKey();
            long available = entry.getValue() == null ? 0L
                : entry.getValue()
                    .longValue();
            if (available <= 0L) continue;

            FluidStack prototype = key.prototype();
            if (prototype == null || prototype.getFluid() == null) continue;
            if (!config.matchesOutput(face, prototype)) continue;

            while (budget > 0 && available > 0L) {
                int want = (int) Math.min((long) budget, available);
                // 先模拟：目标收多少我们就扣多少，不做「先扣再退」的无用功
                int accepted = handler.fill(side, key.prototype(want), false);
                if (accepted <= 0) break;

                long taken = storage.extractFluid(key, accepted);
                if (taken <= 0L) break;

                int filled = handler.fill(side, key.prototype(taken), true);
                if (filled < taken) {
                    // 目标中途不收了：没灌进去的原样放回仓库。
                    // 同样是回滚，不受上限约束
                    storage.insertFluidManual(key, taken - filled);
                }
                if (filled > 0) SharedStorageManager.broadcastFluidChange(key);

                budget -= filled;
                available -= taken;
                if (filled <= 0 || filled < taken) break;
            }

            if (budget <= 0) return;
        }
    }
}
