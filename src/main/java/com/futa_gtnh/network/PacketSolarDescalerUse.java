package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.futa_gtnh.Config;
import com.futa_gtnh.item.ItemSolarDescaler;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.tileentities.boilers.MTEBoilerSolar;
import io.netty.buffer.ByteBuf;

/**
 * 客户端 -&gt; 服务端：太阳能除钙剂请求重置某台锅炉的钙化进度。
 *
 * <p>
 * <b>为什么需要这个包（1.7.10 的 onItemUseFirst 陷阱）：</b>客户端的
 * {@code PlayerControllerMP.onPlayerRightClick} 里，{@code onItemUseFirst}
 * 返回 true 就<b>直接返回、不再发送 C08 点击包</b>（字节码可证：C08 的创建
 * 在 onItemUseFirst 的分支之后）。也就是说客户端消费掉这次点击后，服务端
 * 永远不会看到它 —— 服务端的动作必须自己带包过去。
 *
 * <p>
 * 服务端校验三件事后才动手：手里确实是除钙剂、玩家离方块足够近、目标
 * 真的是太阳能锅炉 —— 客户端报上来的坐标只是「线索」，不是「指令」。
 */
public class PacketSolarDescalerUse implements IMessage {

    private int x;
    private int y;
    private int z;

    public PacketSolarDescalerUse() {}

    public PacketSolarDescalerUse(int x, int y, int z) {
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

    public static class Handler implements IMessageHandler<PacketSolarDescalerUse, IMessage> {

        @Override
        public IMessage onMessage(PacketSolarDescalerUse message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            if (player == null || !Config.enableSolarDescaler) return null;

            // 手里必须是除钙剂 —— 不信任客户端
            ItemStack held = player.getCurrentEquippedItem();
            if (!(held != null && held.getItem() instanceof ItemSolarDescaler)) return null;

            World world = player.worldObj;
            TileEntity tile = world.getTileEntity(message.x, message.y, message.z);
            if (!(tile instanceof IGregTechTileEntity)) return null;

            Object metaTileEntity = ((IGregTechTileEntity) tile).getMetaTileEntity();
            if (!(metaTileEntity instanceof MTEBoilerSolar)) return null;

            // 距离校验：和容器可交互距离同一个标准 —— 1.7.10 的
            // Container.canInteractWith 用的就是「距离平方 <= 64」，也就是 8 格
            if (player.getDistanceSq(message.x + 0.5D, message.y + 0.5D, message.z + 0.5D) > 64.0D) return null;

            ((ItemSolarDescaler) held.getItem()).descaleServer(player, world, (MTEBoilerSolar) metaTileEntity);
            return null;
        }
    }
}
