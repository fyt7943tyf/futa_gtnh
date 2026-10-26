package com.futa_gtnh.network;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.INetHandler;

import com.futa_gtnh.ae2.Ae2Compat;

import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

/** Explicit plan/confirm/cancel actions for the native public ME crafting service. */
public final class PacketPublicMeRequest implements IMessage {

    public static final int QUERY = 0, PLAN = 1, CONFIRM = 2, CANCEL = 3;
    private static final Map<UUID, Rate> LAST = new LinkedHashMap<>();
    public int window, sequence, operation, catalogPage, planPage;
    public long session, amount = 1;
    public String search = "", planId = "";
    public NBTTagCompound key;
    private boolean valid = true;

    public PacketPublicMeRequest() {}

    @Override
    public void fromBytes(ByteBuf buf) {
        if (buf.readableBytes() > 8192 || buf.readableBytes() < 33) {
            valid = false;
            return;
        }
        try {
            window = buf.readInt();
            session = buf.readLong();
            sequence = buf.readInt();
            operation = buf.readUnsignedByte();
            amount = buf.readLong();
            catalogPage = buf.readInt();
            planPage = buf.readInt();
            search = ByteBufUtils.readUTF8String(buf);
            planId = ByteBufUtils.readUTF8String(buf);
            key = ByteBufUtils.readTag(buf);
            valid = operation <= CANCEL && session != 0
                && sequence > 0
                && catalogPage >= 0
                && planPage >= 0
                && search.length() <= 64
                && planId.length() <= 64
                && (key == null || key.toString()
                    .length() <= 2048)
                && !buf.isReadable();
            if (operation == PLAN) valid &= key != null && amount > 0 && amount <= 1_000_000_000L;
            if (operation == CONFIRM || operation == CANCEL) valid &= !planId.isEmpty();
        } catch (RuntimeException malformed) {
            valid = false;
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(window)
            .writeLong(session)
            .writeInt(sequence)
            .writeByte(operation)
            .writeLong(amount)
            .writeInt(catalogPage)
            .writeInt(planPage);
        ByteBufUtils.writeUTF8String(buf, search);
        ByteBufUtils.writeUTF8String(buf, planId);
        ByteBufUtils.writeTag(buf, key);
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
        long queried, changed, session;
        int sequence;

        Rate(INetHandler connection) {
            this.connection = connection;
        }
    }

    public static class Handler implements IMessageHandler<PacketPublicMeRequest, IMessage> {

        @Override
        public IMessage onMessage(PacketPublicMeRequest message, MessageContext context) {
            EntityPlayerMP player = context.getServerHandler().playerEntity;
            if (player == null || !message.valid || !Ae2Compat.isAvailable()) return null;
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
                long previous = message.operation == QUERY ? rate.queried : rate.changed;
                if (previous != 0 && now - previous < 750_000_000L) return null;
                if (message.operation == QUERY) rate.queried = now;
                else rate.changed = now;
            }
            Rate state = rate;
            ServerActions.enqueue(() -> {
                if (!PacketSupplyRequest.authorized(player, message.window)
                    || !player.openContainer.canInteractWith(player)
                    || state.connection != player.playerNetServerHandler
                    || !SupplyClients.supports(player.getUniqueID(), player.playerNetServerHandler)) return;
                synchronized (LAST) {
                    if (state.session == message.session && message.sequence <= state.sequence) return;
                    state.session = message.session;
                    state.sequence = message.sequence;
                }
                NBTTagCompound response;
                try {
                    response = com.futa_gtnh.me.PublicMeCraftingView.handle(
                        player,
                        message.operation,
                        message.key,
                        message.amount,
                        message.planId,
                        message.search,
                        message.catalogPage,
                        message.planPage);
                } catch (RuntimeException failure) {
                    com.futa_gtnh.FutaGtnhMod.LOG.warn("公共 ME 合成界面操作失败，玩家 {}", player.getUniqueID(), failure);
                    response = new NBTTagCompound();
                    response.setString("notice", "AE 无法完成本次操作，请刷新任务状态并查看服务端日志");
                }
                NetworkHandler.INSTANCE.sendTo(
                    new PacketPublicMeSnapshot(message.window, message.session, message.sequence, response),
                    player);
            });
            return null;
        }
    }
}
