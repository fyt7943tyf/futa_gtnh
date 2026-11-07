package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.miner.ItemMinerDeployer;
import com.futa_gtnh.miner.MinerDeployerOps;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 矿机一站式部署的客户端请求（C2S）。
 *
 * <p>
 * 用独立包代替原版 C08：客户端在 {@code onItemUseFirst} 里返回 true 后原生点击包
 * 不再发送（同时挡住控制器的 GUI），服务端动作只能自己带包过去 ——
 * 和 {@code PacketCardboardBoxUse} 同一套管线。服务端收到后重新校验（目标控制器 /
 * 距离 / 手持物品），客户端的判断不作数。
 *
 * <p>
 * {@code preview} = 潜行右键：只报材料清单，不动任何方块。
 */
public class PacketMinerDeployUse implements IMessage {

    private static final double MAX_DISTANCE_SQ = 8.0 * 8.0;

    private int x;
    private int y;
    private int z;
    private boolean preview;

    public PacketMinerDeployUse() {}

    public PacketMinerDeployUse(int x, int y, int z, boolean preview) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.preview = preview;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        preview = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(x);
        buf.writeInt(y);
        buf.writeInt(z);
        buf.writeBoolean(preview);
    }

    public static class Handler implements IMessageHandler<PacketMinerDeployUse, IMessage> {

        @Override
        public IMessage onMessage(PacketMinerDeployUse message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler() != null ? ctx.getServerHandler().playerEntity : null;
            if (player == null) return null;

            try {
                if (player.getDistanceSq(message.x + 0.5, message.y + 0.5, message.z + 0.5) > MAX_DISTANCE_SQ) {
                    return null;
                }
                ItemStack held = player.getHeldItem();
                if (held == null || !(held.getItem() instanceof ItemMinerDeployer)) return null;
                World world = player.worldObj;
                if (world == null || world.isRemote) return null;
                TileEntity te = world.getTileEntity(message.x, message.y, message.z);
                if (te == null) return null;
                MinerDeployerOps.run(player, te, message.preview);
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("矿机部署器：处理玩家 {} 的请求失败", player.getCommandSenderName(), t);
            }
            return null;
        }
    }
}
