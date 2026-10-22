package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.futa_gtnh.Config;
import com.futa_gtnh.item.ItemReactorLayoutTool;
import com.futa_gtnh.reactor.Ic2ReactorCompat;
import com.futa_gtnh.reactor.ReactorLayoutManager;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：反应堆布局工具请求记录目标反应堆的摆放配置。
 *
 * <p>
 * 为什么需要这个包（1.7.10 的 onItemUseFirst 陷阱）：客户端的
 * {@code onItemUseFirst} 返回 true 后原生 C08 点击包不再发送（ItemSolarDescaler /
 * ItemReactorLayoutTool 的类注释都有考证），服务端动作必须自己带包过去。
 *
 * <p>
 * 服务端校验后才动手：功能开关、手里确实是布局工具、玩家处于潜行状态
 * （Shift+右键才是记录）、玩家离方块足够近、目标真的能解析出反应堆 ——
 * 客户端报上来的坐标只是「线索」，读网格以服务端看到的那台为准。
 */
public class PacketReactorToolUse implements IMessage {

    private int x;
    private int y;
    private int z;

    public PacketReactorToolUse() {}

    public PacketReactorToolUse(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
    }

    public static class Handler implements IMessageHandler<PacketReactorToolUse, IMessage> {

        @Override
        public IMessage onMessage(PacketReactorToolUse message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null || !Config.enableReactorTool) return null;

            // 手里必须是布局工具 —— 不信任客户端
            ItemStack held = player.getCurrentEquippedItem();
            if (!(held != null && held.getItem() instanceof ItemReactorLayoutTool)) return null;

            // Shift+右键才是记录；普通右键是纯客户端的开界面，服务端不认识它
            if (!player.isSneaking()) return null;

            World world = player.worldObj;
            TileEntity tile = world.getTileEntity(message.x, message.y, message.z);
            if (tile == null) return null;

            // 距离校验：与容器可交互距离同一个标准（距离平方 ≤ 64，即 8 格）
            if (player.getDistanceSq(message.x + 0.5D, message.y + 0.5D, message.z + 0.5D) > 64.0D) return null;

            Ic2ReactorCompat.ReactorRef ref = Ic2ReactorCompat.resolveReactor(world, message.x, message.y, message.z);
            if (ref == null) return null;

            ReactorLayoutManager.recordLayout(player, ref);
            return null;
        }
    }
}
