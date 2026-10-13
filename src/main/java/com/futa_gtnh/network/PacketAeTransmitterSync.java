package com.futa_gtnh.network;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.futa_gtnh.aewireless.AeSignalRegistry;
import com.futa_gtnh.aewireless.TileAeSignalTransmitter;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/**
 * 「AE 无线发信端」界面的状态快照（S2C）：名字 + 三态 + 可见候选列表（供参考）。
 * 打开界面时发一份，之后 {@code ContainerAeSignalTransmitter} 每 20 tick 补发一次。
 */
public class PacketAeTransmitterSync implements IMessage {

    private int dim, x, y, z;
    private String name;
    private int stateOrdinal;
    /** 候选列表（当前玩家可见的全体发信端），用于界面上「还有哪些发信端」的提示。 */
    private String visibleList;

    public PacketAeTransmitterSync() {}

    public static PacketAeTransmitterSync of(TileAeSignalTransmitter tile, EntityPlayerMP viewer) {
        PacketAeTransmitterSync packet = new PacketAeTransmitterSync();
        packet.dim = tile.getWorldObj().provider.dimensionId;
        packet.x = tile.xCoord;
        packet.y = tile.yCoord;
        packet.z = tile.zCoord;
        packet.name = tile.getSignalName();
        packet.stateOrdinal = tile.getState()
            .ordinal();
        UUID owner = viewer.getUniqueID();
        StringBuilder sb = new StringBuilder();
        for (AeSignalRegistry.Entry entry : AeSignalRegistry.listVisible(owner)) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.name)
                .append('(')
                .append(entry.dim)
                .append(':')
                .append(entry.x)
                .append(',')
                .append(entry.y)
                .append(',')
                .append(entry.z)
                .append(')');
        }
        packet.visibleList = sb.toString();
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        dim = buf.readInt();
        x = buf.readInt();
        y = buf.readInt();
        z = buf.readInt();
        stateOrdinal = buf.readByte();
        name = readUtf(buf);
        visibleList = readUtf(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(dim)
            .writeInt(x)
            .writeInt(y)
            .writeInt(z)
            .writeByte(stateOrdinal);
        writeUtf(buf, name);
        writeUtf(buf, visibleList);
    }

    private static void writeUtf(ByteBuf buf, String value) {
        ByteBufUtils.writeUTF8String(buf, trimTo(value, 2048));
    }

    private static String readUtf(ByteBuf buf) {
        return ByteBufUtils.readUTF8String(buf);
    }

    private static String trimTo(String value, int maxBytes) {
        if (value == null) return "";
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return value;
        return new String(bytes, 0, maxBytes, java.nio.charset.StandardCharsets.UTF_8);
    }

    public int getDim() {
        return dim;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public String getName() {
        return name == null ? "" : name;
    }

    public TileAeSignalTransmitter.State getState() {
        return TileAeSignalTransmitter.State.VALUES[Math
            .floorMod(stateOrdinal, TileAeSignalTransmitter.State.VALUES.length)];
    }

    public String getVisibleList() {
        return visibleList == null ? "" : visibleList;
    }

    public static class Handler implements IMessageHandler<PacketAeTransmitterSync, IMessage> {

        @Override
        public IMessage onMessage(PacketAeTransmitterSync message, MessageContext ctx) {
            com.futa_gtnh.client.ClientAeTransmitterState.apply(message);
            return null;
        }
    }

    /** 服务端：解析发信端并给玩家推一份快照（打开界面时用）。 */
    public static void send(EntityPlayerMP player, World world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileAeSignalTransmitter transmitter) {
            com.futa_gtnh.network.NetworkHandler.INSTANCE.sendTo(of(transmitter, player), player);
        }
    }
}
