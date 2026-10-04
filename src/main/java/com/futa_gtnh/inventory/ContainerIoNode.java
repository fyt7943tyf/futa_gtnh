package com.futa_gtnh.inventory;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.block.TileEntityIoNode;

/**
 * IO 节点配置界面的容器。
 *
 * <p>
 * 这个界面只改配置（模式 / 筛选 / 节奏），不搬玩家背包里的东西，所以<b>没有任何属于自己的槽</b>。
 * 存在的唯一原因是 1.7.10 的 {@code player.openGui} 要求服务端返回非 null 的容器，
 * 否则客户端不会开界面 —— 返回这个空壳，界面本身（{@code client/GuiIoNode}）自绘。
 *
 * <p>
 * <b>玩家背包槽必须挂进来，但要放到屏幕外。</b>1.7.10 的
 * {@code NetHandlerPlayServer.processPlayerBlockPlacement} 会调
 * {@code getSlotFromInventory(玩家背包, 当前手持)} 拿槽位、拿不到（容器没这个槽）时
 * 返回 null，紧接着就 {@code slot.slotNumber} —— 零槽容器会在这里把服务端 NPE 崩掉
 * （右键后物品校验一发 S2FPacketSetSlot 就炸）。所以玩家 36 格按原版顺序注册，
 * 坐标全放 (-3000,-3000)：服务端找得到槽，界面又看不见点不着（EnderIO 的
 * {@code ExternalConnectionContainer.setInoutSlotsVisible} 同一个手法）。
 *
 * <p>
 * {@link #canInteractWith} 同时承担「走远了自动关界面」：玩家离方块超过 8 格界面就消失，
 * 和配置上行包（{@code PacketIoNodeConfig}）的距离校验是同一把尺子。
 */
public class ContainerIoNode extends Container {

    /** 交互距离上限（方块中心到玩家），和 PacketIoNodeConfig 的校验一致。 */
    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    /** 屏幕外坐标：槽注册了但不渲染、点不到。 */
    private static final int HIDDEN = -3000;

    private final TileEntityIoNode node;

    public ContainerIoNode(TileEntityIoNode node, EntityPlayer player) {
        this.node = node;

        // 玩家背包 3 行 + 快捷栏 1 行，槽位下标和原版容器一致
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlotToContainer(new Slot(player.inventory, column + row * 9 + 9, HIDDEN, HIDDEN));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlotToContainer(new Slot(player.inventory, column, HIDDEN, HIDDEN));
        }
    }

    public TileEntityIoNode getNode() {
        return node;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (node == null || node.isInvalid()) return false;
        return player.getDistanceSq(node.xCoord + 0.5, node.yCoord + 0.5, node.zCoord + 0.5) <= MAX_DISTANCE_SQ;
    }

    /** 界面没有可交互的槽，shift 点物品一律不搬家。 */
    @Override
    public ItemStack transferStackInSlot(EntityPlayer player, int slotIndex) {
        return null;
    }
}
