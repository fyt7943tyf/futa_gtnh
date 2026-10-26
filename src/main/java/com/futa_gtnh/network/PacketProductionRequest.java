package com.futa_gtnh.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetHandler;

import com.futa_gtnh.inventory.ContainerProductionTerminal;
import com.futa_gtnh.supply.SupplyTracker;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Window/session-bound, bounded, on-demand device throughput requests. */
public final class PacketProductionRequest implements IMessage {

    private static final Map<UUID, Rate> LAST = new LinkedHashMap<>();
    public int window, sequence, minutes = 10, linePage, sourcePage, resourcePage;
    public long session;
    public String lineId = "", sourceId = "";
    public NBTTagCompound mutation;
    private boolean valid = true;

    public PacketProductionRequest() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        if (buf.readableBytes() > 8192 || buf.readableBytes() < 29) {
            valid = false;
            return;
        }
        try {
            window = buf.readInt();
            session = buf.readLong();
            sequence = buf.readInt();
            minutes = buf.readUnsignedByte();
            linePage = buf.readInt();
            sourcePage = buf.readInt();
            resourcePage = buf.readInt();
            lineId = ByteBufUtils.readUTF8String(buf);
            sourceId = ByteBufUtils.readUTF8String(buf);
            mutation = ByteBufUtils.readTag(buf);
            valid = (minutes == 1 || minutes == 10 || minutes == 60) && session != 0
                && sequence > 0
                && lineId.length() <= 96
                && sourceId.length() <= 160
                && linePage >= 0
                && sourcePage >= 0
                && resourcePage >= 0
                && (mutation == null || mutation.toString()
                    .length() <= 4096)
                && !buf.isReadable();
        } catch (RuntimeException malformed) {
            valid = false;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(window)
            .writeLong(session)
            .writeInt(sequence)
            .writeByte(minutes)
            .writeInt(linePage)
            .writeInt(sourcePage)
            .writeInt(resourcePage);
        ByteBufUtils.writeUTF8String(buf, lineId);
        ByteBufUtils.writeUTF8String(buf, sourceId);
        ByteBufUtils.writeTag(buf, mutation);
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

    private static final class Rate {

        final INetHandler connection;
        long queryAt, mutationAt, session;
        int sequence;

        Rate(INetHandler connection) {
            this.connection = connection;
        }
    }

    public static class Handler implements IMessageHandler<PacketProductionRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketProductionRequest message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (player == null || !message.valid) return null;
            Rate rate;
            synchronized (LAST) {
                rate = LAST.get(player.getUniqueID());
                if (rate == null || rate.connection != player.playerNetServerHandler) {
                    if (LAST.size() >= 256) LAST.remove(
                        LAST.keySet()
                            .iterator()
                            .next());
                    rate = new Rate(player.playerNetServerHandler);
                    LAST.put(player.getUniqueID(), rate);
                }
                long now = System.nanoTime();
                long previous = message.mutation == null ? rate.queryAt : rate.mutationAt;
                long interval = message.mutation == null ? 750_000_000L : 250_000_000L;
                if (previous != 0 && now - previous < interval) return null;
                if (message.mutation == null) rate.queryAt = now;
                else rate.mutationAt = now;
            }
            Rate state = rate;
            ServerActions.enqueue(() -> {
                ContainerProductionTerminal terminal = player.openContainer instanceof ContainerProductionTerminal target
                    && target.windowId == message.window ? target : null;
                if (!(PacketSupplyRequest.authorized(player, message.window) || terminal != null)
                    || !player.openContainer.canInteractWith(player)
                    || state.connection != player.playerNetServerHandler
                    || !SupplyClients.supports(player.getUniqueID(), player.playerNetServerHandler)) return;
                if (terminal != null && !terminal.accepts(message.session, message.mutation == null)) return;
                synchronized (LAST) {
                    if (state.session == message.session && message.sequence <= state.sequence) return;
                    state.session = message.session;
                    state.sequence = message.sequence;
                }
                NBTTagCompound mutation = terminal == null ? message.mutation : terminal.constrain(message.mutation);
                NBTTagCompound result = null;
                if (message.mutation != null) {
                    if (mutation == null) {
                        result = new NBTTagCompound();
                        result.setString("message", "此窗口仅支持绑定、解绑和新建产线");
                    } else result = SupplyTracker.productionMutation(player, mutation);
                }
                NBTTagCompound data = terminal != null && terminal.bindingMode
                    ? SupplyTracker.productionBindingSnapshot(terminal.target, message.minutes, message.linePage)
                    : SupplyTracker.productionSnapshot(
                        message.lineId,
                        message.sourceId,
                        message.minutes,
                        message.linePage,
                        message.sourcePage,
                        message.resourcePage);
                data.setBoolean(
                    "canEdit",
                    data.getBoolean("writable") && player.canCommandSenderUseCommand(4, "futalines"));
                if (result != null) data.setTag("result", result);
                NetworkHandler.INSTANCE.sendTo(
                    new PacketProductionSnapshot(message.window, message.session, message.sequence, data),
                    player);
            });
            return null;
        }
    }
}
