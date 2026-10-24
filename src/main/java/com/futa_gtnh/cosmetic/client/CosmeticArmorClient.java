package com.futa_gtnh.cosmetic.client;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.INetHandler;

import com.futa_gtnh.cosmetic.CosmeticArmorIdentity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent.ClientConnectedToServerEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent.ClientDisconnectionFromServerEvent;

/** Keeps the server's own-player identity separate from the launcher's account identity. */
public final class CosmeticArmorClient {

    private static final CosmeticArmorIdentity IDENTITY = new CosmeticArmorIdentity();

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new CosmeticArmorClient());
    }

    @SubscribeEvent
    public void connected(ClientConnectedToServerEvent event) {
        IDENTITY.connected(event.manager);
    }

    @SubscribeEvent
    public void disconnected(ClientDisconnectionFromServerEvent event) {
        IDENTITY.disconnected(event.manager);
    }

    public static void receiveIdentity(INetHandler source, UUID serverId) {
        if (!(source instanceof NetHandlerPlayClient)) return;
        // EntityPlayerSP uses this same Session GameProfile, even before thePlayer exists at login.
        UUID localId = EntityPlayer.func_146094_a(
            Minecraft.getMinecraft()
                .getSession()
                .func_148256_e());
        IDENTITY.identify(((NetHandlerPlayClient) source).getNetworkManager(), localId, serverId);
    }

    public static UUID resolve(UUID requested) {
        return IDENTITY.resolve(requested);
    }
}
