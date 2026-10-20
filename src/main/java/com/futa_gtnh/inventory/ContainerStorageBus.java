package com.futa_gtnh.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.storagebus.TileEntityStorageBus;

/**
 * 存储总线配置界面的容器。
 *
 * <p>
 * 和 IO 节点一样，这个界面只改配置、不搬玩家背包，所以没有自己的功能槽。
 * 但玩家 36 格仍要按原版顺序挂在屏幕外：1.7.10 的服务端在右键打开界面后
 * 会拿「当前手持物品在容器里的槽位」，零槽容器会在那里 NPE 崩溃。
 *
 * <p>
 * 8 格交互距离和 {@code PacketStorageBusConfig} 的距离校验保持一致。
 */
public class ContainerStorageBus extends Container {

    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;
    private static final int HIDDEN = -3000;

    private final TileEntityStorageBus bus;
    private final int side;

    public ContainerStorageBus(TileEntityStorageBus bus, EntityPlayer player, int side) {
        this.bus = bus;
        this.side = side;

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlotToContainer(new Slot(player.inventory, column + row * 9 + 9, HIDDEN, HIDDEN));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlotToContainer(new Slot(player.inventory, column, HIDDEN, HIDDEN));
        }
    }

    public TileEntityStorageBus getBus() {
        return bus;
    }

    public int getSide() {
        return side;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (bus == null || bus.isInvalid()) return false;
        return player.getDistanceSq(bus.xCoord + 0.5, bus.yCoord + 0.5, bus.zCoord + 0.5) <= MAX_DISTANCE_SQ;
    }

    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotIndex) {
        return null;
    }
}
