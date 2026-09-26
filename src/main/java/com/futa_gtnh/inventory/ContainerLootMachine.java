package com.futa_gtnh.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.block.TileEntityLootMachine;
import com.futa_gtnh.lootbag.EnhancedLootBagsCompat;
import com.futa_gtnh.lootbag.TokenWallet;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketLootMachineAction;
import com.futa_gtnh.network.PacketLootMachineResult;

/**
 * 自选抽奖机的容器。布局常量与 {@code client/GuiLootMachine} 共享，
 * 两端跑同一个构造函数，槽位下标一致（0=袋子, 1=书, 2..=玩家背包）。
 *
 * <p>
 * 容器本身不改机器状态：所有动作（roll / 领取 / 重置）都由
 * {@code PacketLootMachineAction} 显式发到服务端，在这里校验后转给
 * {@link TileEntityLootMachine}，成功与否都回一份全量同步包
 * （{@link PacketLootMachineResult}）—— 界面上那片「模拟出货」不是真槽位，
 * 只能靠这个包画。
 */
public class ContainerLootMachine extends Container {

    // ---- 与 GUI 共享的布局常量（像素坐标）----
    // 注意：这些常量是「贴图上 18x18 槽位格」的左上角；真正创建 Slot 时要 +1
    // （原版约定：物品 16x16 画在槽位原点上，才会在格内居中）。
    // 和 ContainerSharedTerminal 的 GRID_X + 1 + col * 18 同一套写法。
    // GUI_HEIGHT 必须不超过 256：原版 drawTexturedModalRect 按 1/256 归一化 UV，
    // GUI 贴图画布是 256x256，面板超出会被截掉（见 GenTextures 的说明）。
    public static final int GUI_WIDTH = 176;
    public static final int GUI_HEIGHT = 256;

    public static final int BAG_SLOT_X = 8;
    public static final int BAG_SLOT_Y = 20;
    public static final int BOOK_SLOT_X = 28;
    public static final int BOOK_SLOT_Y = 20;

    public static final int PLAYER_INV_X = 8;
    public static final int PLAYER_INV_Y = 176;
    public static final int HOTBAR_Y = 234;

    private final TileEntityLootMachine machine;

    public ContainerLootMachine(InventoryPlayer playerInventory, TileEntityLootMachine machine) {
        this.machine = machine;

        addSlotToContainer(new Slot(machine, TileEntityLootMachine.SLOT_BAG, BAG_SLOT_X + 1, BAG_SLOT_Y + 1) {

            @Override
            public boolean isItemValid(ItemStack stack) {
                return EnhancedLootBagsCompat.isLootBag(stack);
            }
        });

        addSlotToContainer(new Slot(machine, TileEntityLootMachine.SLOT_BOOK, BOOK_SLOT_X + 1, BOOK_SLOT_Y + 1) {

            @Override
            public boolean isItemValid(ItemStack stack) {
                return EnhancedLootBagsCompat.isFortuneBook(stack);
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlotToContainer(
                    new Slot(
                        playerInventory,
                        9 + row * 9 + column,
                        PLAYER_INV_X + 1 + column * 18,
                        PLAYER_INV_Y + 1 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlotToContainer(new Slot(playerInventory, column, PLAYER_INV_X + 1 + column * 18, HOTBAR_Y + 1));
        }
    }

    public TileEntityLootMachine getMachine() {
        return machine;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return machine != null && machine.isUseableByPlayer(player);
    }

    /**
     * 服务端动作入口（包处理器调用）。不做任何「信任客户端」的推断：
     * 能不能 roll、要不要扣代币、轮次下标有没有越界，全由
     * {@link TileEntityLootMachine} 自己判。
     */
    public void handleAction(EntityPlayerMP player, byte action, byte roundIndex) {
        if (player == null || !canInteractWith(player)) return;

        switch (action) {
            case PacketLootMachineAction.ACTION_ROLL:
                machine.roll(player);
                break;
            case PacketLootMachineAction.ACTION_CLAIM:
                machine.claim(player, roundIndex);
                break;
            case PacketLootMachineAction.ACTION_RESET:
                machine.resetRolls(player);
                break;
            default:
                return;
        }
        syncTo(player);
    }

    /** 把机器状态全量推给客户端（开界面 / 每次动作后）。 */
    public void syncTo(EntityPlayerMP player) {
        int balance = TokenWallet.isAvailable() ? TokenWallet.getBalance(player) : -1;
        NetworkHandler.INSTANCE
            .sendTo(new PacketLootMachineResult(machine, balance, TokenWallet.isAvailable()), player);
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotIndex) {
        ItemStack transferred = null;
        Slot slot = (Slot) inventorySlots.get(slotIndex);
        if (slot == null || !slot.getHasStack()) return transferred;

        ItemStack stack = slot.getStack();
        transferred = stack.copy();

        if (slotIndex == TileEntityLootMachine.SLOT_BAG || slotIndex == TileEntityLootMachine.SLOT_BOOK) {
            // 机器槽 -> 玩家背包
            if (!mergeItemStack(stack, 2, 38, true)) return null;
        } else if (EnhancedLootBagsCompat.isLootBag(stack)) {
            if (!mergeItemStack(stack, 0, 1, false)) return null;
        } else if (EnhancedLootBagsCompat.isFortuneBook(stack)) {
            if (!mergeItemStack(stack, 1, 2, false)) return null;
        } else if (slotIndex >= 29) {
            // 快捷栏(29..37) -> 主背包(2..28)
            if (!mergeItemStack(stack, 2, 29, false)) return null;
        } else {
            // 主背包(2..28) -> 快捷栏(29..37)
            if (!mergeItemStack(stack, 29, 38, false)) return null;
        }

        if (stack.stackSize <= 0) {
            slot.putStack(null);
        } else {
            slot.onSlotChanged();
        }
        return transferred;
    }
}
