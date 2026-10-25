package com.futa_gtnh.network;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.INetHandler;

/** Explicit opt-in per connection: old clients must never receive an unknown telemetry discriminator. */
public final class SupplyClients {

    private static final Map<UUID, WeakReference<INetHandler>> CLIENTS = new ConcurrentHashMap<>();

    private SupplyClients() {}

    public static void register(UUID player, INetHandler handler) {
        if (player != null && handler != null) CLIENTS.put(player, new WeakReference<>(handler));
    }

    public static boolean supports(UUID player, INetHandler handler) {
        WeakReference<INetHandler> known = player == null ? null : CLIENTS.get(player);
        return handler != null && known != null && known.get() == handler;
    }

    public static void forget(UUID player, INetHandler handler) {
        if (player == null) return;
        WeakReference<INetHandler> known = CLIENTS.get(player);
        if (known != null && known.get() == handler) CLIENTS.remove(player, known);
    }

    public static void clear() {
        CLIENTS.clear();
    }
}
