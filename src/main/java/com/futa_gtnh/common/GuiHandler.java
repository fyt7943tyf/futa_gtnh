package com.futa_gtnh.common;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;

import com.futa_gtnh.block.TileEntitySharedTerminal;
import com.futa_gtnh.client.GuiSharedTerminal;
import com.futa_gtnh.exchange.AutoStore;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketAutoStoreSync;
import com.futa_gtnh.network.PacketTerminalFluid;
import com.futa_gtnh.shared.SharedStorageManager;

import cpw.mods.fml.common.network.IGuiHandler;

/**
 * GUI 的客户端/服务端工厂。
 *
 * <p>
 * 两边各自 new 一个 {@link ContainerSharedTerminal}，<b>槽位下标必须一模一样</b> ——
 * 保证这一点靠的是两边跑的是同一个构造函数、同一组布局常量，
 * 而不是靠谁记得同步改两处。
 */
public class GuiHandler implements IGuiHandler {

    public static final int GUI_SHARED_TERMINAL = 0;

    @Override
    public Object getServerGuiElement(int id, net.minecraft.entity.player.EntityPlayer player, World world, int x,
        int y, int z) {
        if (id != GUI_SHARED_TERMINAL) return null;

        TileEntitySharedTerminal terminal = resolve(world, x, y, z);

        if (player instanceof EntityPlayerMP) {
            EntityPlayerMP serverPlayer = (EntityPlayerMP) player;

            // 把「这个终端在输出哪种流体」告诉客户端。
            // 它是每个方块各自的状态，不跟着全服共用的那份快照走，所以单独发一个小包。
            NetworkHandler.INSTANCE
                .sendTo(new PacketTerminalFluid(terminal == null ? null : terminal.getOutputFluid()), serverPlayer);

            // 「拾取自动入库」是每个玩家各自的状态，同理单独下发
            NetworkHandler.INSTANCE.sendTo(new PacketAutoStoreSync(AutoStore.isEnabled(serverPlayer)), serverPlayer);

            // 拉一份全量快照给客户端。
            // 客户端要拿整份数据来做搜索/排序/翻页 —— 这些操作每按一个键、每换一页都要重算，
            // 不可能每次都问服务端。之后靠增量包保持同步。
            SharedStorageManager.sendSnapshotTo(serverPlayer);
        }

        return new ContainerSharedTerminal(player.inventory, terminal);
    }

    @Override
    public Object getClientGuiElement(int id, net.minecraft.entity.player.EntityPlayer player, World world, int x,
        int y, int z) {
        if (id != GUI_SHARED_TERMINAL) return null;
        // 这里引用了只在客户端存在的 GuiSharedTerminal。方法体在服务端永远不会执行，
        // JVM 又是惰性解析符号引用的，所以服务端加载这个类不会出问题。
        return new GuiSharedTerminal(new ContainerSharedTerminal(player.inventory, resolve(world, x, y, z)));
    }

    /**
     * 把坐标解析成方块终端。
     *
     * <p>
     * 按键远程打开时，客户端传的是玩家自己的坐标，那里当然没有终端，
     * 于是返回 null —— 界面照常能用，只是没有「终端输出流体」这个功能。
     */
    private static TileEntitySharedTerminal resolve(World world, int x, int y, int z) {
        if (world == null) return null;
        net.minecraft.tileentity.TileEntity tile = world.getTileEntity(x, y, z);
        return tile instanceof TileEntitySharedTerminal ? (TileEntitySharedTerminal) tile : null;
    }
}
