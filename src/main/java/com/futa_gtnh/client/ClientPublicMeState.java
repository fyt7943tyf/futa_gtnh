package com.futa_gtnh.client;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.network.INetHandler;

import com.futa_gtnh.network.PacketPublicMeSnapshot;

/** A crafting preview belongs to both the connection and the exact screen that requested it. */
public final class ClientPublicMeState {

    private static final AtomicLong GENERATIONS = new AtomicLong(System.nanoTime());
    private static volatile INetHandler supportedConnection, connection;
    private static volatile long session;
    private static volatile PacketPublicMeSnapshot response;

    private ClientPublicMeState() {}

    public static void ready(INetHandler source, boolean supported) {
        supportedConnection = supported ? source : null;
    }

    public static boolean supported(INetHandler source) {
        return source != null && source == supportedConnection;
    }

    public static synchronized long begin(INetHandler source) {
        connection = source;
        response = null;
        long next = GENERATIONS.incrementAndGet();
        if (next == 0) next = GENERATIONS.incrementAndGet();
        session = next;
        return next;
    }

    public static synchronized void end(long expected) {
        if (session != expected) return;
        connection = null;
        response = null;
        session = 0;
    }

    public static synchronized void receive(INetHandler source, PacketPublicMeSnapshot packet) {
        if (packet == null || packet.data == null
            || source == null
            || source != connection
            || session == 0
            || packet.session != session) return;
        PacketPublicMeSnapshot previous = response;
        if (previous == null || packet.sequence > previous.sequence) response = packet;
    }

    public static PacketPublicMeSnapshot response() {
        return response;
    }
}
