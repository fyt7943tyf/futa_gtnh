package com.futa_gtnh.network;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.aewireless.TileAeSignalTransmitter;
import com.futa_gtnh.inventory.ContainerAeSignalTransmitter;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 「AE 无线发信端」界面的改名请求（C2S）。
 *
 * <p>
 * 服务端校验：界面打开者必须是发信端所有者（或同队），名字截断到上限。
 * 改名不影响接收端的链接（链接键是坐标，不是名字）。
 */
public class PacketAeTransmitterConfig implements IMessage {

    private int dim, x, y, z;
    private String name;

    public PacketAeTransmitterConfig() {}

    public PacketAeTransmitterConfig(int dim, int x, int y, int z, String name) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.name = name;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        name = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim)
            .writeInt(x)
            .writeInt(y)
            .writeInt(z);
        ByteBufUtils.writeUTF8String(buf, trimTo(name, 96));
    }

    private static String trimTo(String value, int maxBytes) {
        return Utf8Text.limit(value, maxBytes);
    }

    public static class Handler implements IMessageHandler<PacketAeTransmitterConfig, IMessage> {

        @Override
        public IMessage onMessage(PacketAeTransmitterConfig message, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().playerEntity;
            ServerActions.enqueue(() -> apply(message, player));
            return null;
        }

        private void apply(PacketAeTransmitterConfig message, EntityPlayerMP player) {
            if (!(player.openContainer instanceof ContainerAeSignalTransmitter container)
                || !container.matches(message.dim, message.x, message.y, message.z)
                || !container.canInteractWith(player)) return;
            World world = MinecraftServer.getServer()
                .worldServerForDimension(message.dim);
            if (world == null) return;
            TileEntity tile = world.getTileEntity(message.x, message.y, message.z);
            if (!(tile instanceof TileAeSignalTransmitter transmitter)) return;
            if (!transmitter.canManage(player)) {
                FutaGtnhMod.LOG.info("玩家 {} 尝试改名不属于自己队伍的发信端，已拒绝", player.getCommandSenderName());
                return;
            }
            String name = message.name == null ? "" : message.name.trim();
            transmitter.setSignalName(name);
        }
    }
}
