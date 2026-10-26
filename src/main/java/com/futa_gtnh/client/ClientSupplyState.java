package com.futa_gtnh.client;

import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;

import com.futa_gtnh.network.PacketSupplySnapshot;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.supply.SupplyTracker;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/** A single immutable response mailbox; GUI reads it on the client thread. */
public final class ClientSupplyState {

    private static volatile PacketSupplySnapshot response;
    private static volatile PacketSupplySnapshot navigation;
    private static PacketSupplySnapshot handledNavigation;
    private static int sequence;
    private static volatile net.minecraft.network.INetHandler connection;
    private static volatile boolean supported;
    private static final ArrayBlockingQueue<NBTTagCompound> ALERTS = new ArrayBlockingQueue<>(16);

    private ClientSupplyState() {}

    public static void receive(net.minecraft.network.INetHandler source, PacketSupplySnapshot packet) {
        if (source == null || source != connection) return;
        if (packet.data.getBoolean("ready")) {
            supported = true;
            ClientProductionState.ready(source, packet.data.getBoolean("productionReady"));
            ClientPublicMeState.ready(source, packet.data.getBoolean("publicMeReady"));
            return;
        }
        if (packet.data.hasKey("navigate", 10)) navigation = packet;
        else if (packet.data.hasKey("alert", 10)) ALERTS.offer(packet.data.getCompoundTag("alert"));
        else response = packet;
    }

    public static PacketSupplySnapshot response() {
        return response;
    }

    public static PacketSupplySnapshot navigation() {
        return navigation;
    }

    public static boolean supported() {
        return supported;
    }

    public static int nextSequence() {
        return ++sequence;
    }

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new ClientSupplyState());
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        if (mc.getNetHandler() != null && mc.getNetHandler() != connection) {
            connection = mc.getNetHandler();
            supported = false;
            ClientProductionState.ready(null, false);
            ClientPublicMeState.ready(null, false);
            response = null;
            navigation = null;
            handledNavigation = null;
            SupplyHighlight.clear();
            ALERTS.clear();
            com.futa_gtnh.network.NetworkHandler.INSTANCE.sendToServer(
                new com.futa_gtnh.network.PacketStorageAction(
                    com.futa_gtnh.network.PacketStorageAction.SUPPLY_CLIENT_READY));
        }
        PacketSupplySnapshot arrival = navigation;
        if (arrival != null && arrival != handledNavigation) {
            handledNavigation = arrival;
            NBTTagCompound result = arrival.data.getCompoundTag("navigate");
            if (result.hasKey("target", 10))
                SupplyHighlight.show(com.futa_gtnh.supply.SupplySource.read(result.getCompoundTag("target")));
            mc.thePlayer
                .addChatMessage(new ChatComponentTranslation("futa_gtnh.supply." + result.getString("message")));
        }
        if (ALERTS.isEmpty()) return;
        for (int i = 0; i < 4; i++) {
            NBTTagCompound alert = ALERTS.poll();
            if (alert == null) break;
            Object key = SupplyTracker.readKey(alert);
            if (key == null) continue;
            String name = key instanceof ItemKey ? ((ItemKey) key).prototype()
                .getDisplayName()
                : ((FluidKey) key).prototype()
                    .getLocalizedName();
            mc.thePlayer.addChatMessage(
                new ChatComponentTranslation(
                    "futa_gtnh.supply.alert",
                    name,
                    alert.getLong("stock"),
                    alert.getLong("threshold")));
        }
    }

    @SubscribeEvent
    public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ClientProductionState.ready(null, false);
        ClientPublicMeState.ready(null, false);
        ALERTS.clear();
        response = null;
        navigation = null;
        handledNavigation = null;
        SupplyHighlight.clear();
        supported = false;
        connection = null;
    }
}
