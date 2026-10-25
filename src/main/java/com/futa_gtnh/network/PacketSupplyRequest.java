package com.futa_gtnh.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.supply.SupplyTracker;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** On-demand, rate-limited telemetry/settings request, bound to the player's current terminal window. */
public final class PacketSupplyRequest implements IMessage {

    public static final int QUERY = 0, RENAME = 1, WATCH = 2, TELEPORT = 3;
    private static final Map<UUID, long[]> LAST = new HashMap<>();
    public int window, sequence, operation, minutes = 10, sourcePage, watchPage, listMode;
    public NBTTagCompound key;
    public String source = "", name = "";
    public long threshold = -1;
    private boolean valid = true;

    public PacketSupplyRequest() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        if (buf.readableBytes() > 8192 || buf.readableBytes() < 31) {
            valid = false;
            return;
        }
        window = buf.readInt();
        sequence = buf.readInt();
        operation = buf.readUnsignedByte();
        minutes = buf.readUnsignedByte();
        listMode = buf.readUnsignedByte();
        sourcePage = buf.readInt();
        watchPage = buf.readInt();
        threshold = buf.readLong();
        source = ByteBufUtils.readUTF8String(buf);
        name = ByteBufUtils.readUTF8String(buf);
        key = ByteBufUtils.readTag(buf);
        valid = operation <= TELEPORT && listMode <= 2
            && source.length() <= 160
            && name.length() <= 96
            && (key == null || key.toString()
                .length() <= 4096);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(window)
            .writeInt(sequence)
            .writeByte(operation)
            .writeByte(minutes)
            .writeByte(listMode)
            .writeInt(sourcePage)
            .writeInt(watchPage)
            .writeLong(threshold);
        ByteBufUtils.writeUTF8String(buf, source);
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeTag(buf, key);
    }

    public static boolean authorized(EntityPlayerMP player, int window) {
        return player != null && player.openContainer instanceof ContainerSharedTerminal
            && player.openContainer.windowId == window;
    }

    public static void forget(UUID player) {
        synchronized (LAST) {
            LAST.remove(player);
        }
    }

    public static void clear() {
        synchronized (LAST) {
            LAST.clear();
        }
    }

    public static class Handler implements IMessageHandler<PacketSupplyRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketSupplyRequest message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (player == null || !message.valid) return null;
            long now = System.nanoTime();
            synchronized (LAST) {
                long[] last = LAST.get(player.getUniqueID());
                int slot = message.operation == QUERY ? 0 : message.operation == TELEPORT ? 2 : 1;
                long interval = slot == 0 ? 1_000_000_000L : slot == 2 ? 5_000_000_000L : 250_000_000L;
                if (last != null && last[slot] != 0 && now - last[slot] < interval) return null;
                if (LAST.size() >= 256) LAST.clear();
                if (last == null) {
                    last = new long[3];
                    LAST.put(player.getUniqueID(), last);
                }
                last[slot] = now;
            }
            ServerActions.enqueue(() -> {
                if (!authorized(player, message.window)
                    || !SupplyClients.supports(player.getUniqueID(), player.playerNetServerHandler)) return;
                Object key = SupplyTracker.readKey(message.key);
                if (message.operation == TELEPORT) {
                    NBTTagCompound result = new NBTTagCompound();
                    result.setTag(
                        "navigate",
                        com.futa_gtnh.supply.SupplyNavigation.navigate(player, key, message.source));
                    NetworkHandler.INSTANCE
                        .sendTo(new PacketSupplySnapshot(message.window, message.sequence, result), player);
                    return;
                }
                boolean okay = true;
                if (message.operation == RENAME) okay = SupplyTracker.rename(message.source, message.name);
                else if (message.operation == WATCH)
                    okay = message.threshold >= -1 && SupplyTracker.watch(key, message.threshold);
                NBTTagCompound response = SupplyTracker
                    .snapshot(key, message.minutes, message.sourcePage, message.watchPage, message.listMode);
                response.setBoolean("okay", okay);
                NetworkHandler.INSTANCE
                    .sendTo(new PacketSupplySnapshot(message.window, message.sequence, response), player);
            });
            return null;
        }
    }
}
