package com.futa_gtnh.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ICrafting;
import net.minecraft.inventory.Slot;

import com.futa_gtnh.aewireless.TileAeSignalTransmitter;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketAeTransmitterSync;

/**
 * 「AE 无线发信端」的容器：没有槽位，只负责两件事 ——
 * 服务端周期性把状态快照推给每个看着界面的人，客户端承接快照。
 */
public class ContainerAeSignalTransmitter extends Container {

    private final TileAeSignalTransmitter tile;
    private final int dim, x, y, z;
    private int lastState = Integer.MIN_VALUE;
    private int tickCounter;

    public ContainerAeSignalTransmitter(TileAeSignalTransmitter tile, EntityPlayer player) {
        this.tile = tile;
        this.dim = tile.getWorldObj().provider.dimensionId;
        this.x = tile.xCoord;
        this.y = tile.yCoord;
        this.z = tile.zCoord;
        // Right-click item reconciliation expects every hotbar slot to exist, even in a configuration GUI.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlotToContainer(new Slot(player.inventory, 9 + row * 9 + column, -3000, -3000));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlotToContainer(new Slot(player.inventory, column, -3000, -3000));
        }
    }

    public int dim() {
        return dim;
    }

    public int x() {
        return x;
    }

    public int y() {
        return y;
    }

    public int z() {
        return z;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        return player != null && !tile.isInvalid()
            && player.worldObj == tile.getWorldObj()
            && player.worldObj.getTileEntity(x, y, z) == tile
            && player.getDistanceSq(x + 0.5, y + 0.5, z + 0.5) <= 64
            && tile.canManage(player);
    }

    public boolean matches(int dimension, int posX, int posY, int posZ) {
        return dim == dimension && x == posX && y == posY && z == posZ;
    }

    /** 服务端：每 20 tick 给界面上每个玩家补一份快照（状态变化靠它刷出来）。 */
    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (tile.getWorldObj().isRemote) return;
        int state = tile.getState()
            .ordinal();
        boolean force = state != lastState;
        lastState = state;
        if (!force && tickCounter % 20 != 0) {
            tickCounter++;
            return;
        }
        tickCounter++;
        for (Object crafter : crafters) {
            if (crafter instanceof ICrafting crafting && crafting instanceof EntityPlayerMP player) {
                NetworkHandler.INSTANCE.sendTo(PacketAeTransmitterSync.of(tile, player), player);
            }
        }
    }
}
