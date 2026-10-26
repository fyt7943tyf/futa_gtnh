package com.futa_gtnh.client;

import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.network.INetHandler;

import com.futa_gtnh.network.PacketProductionSnapshot;

/** Connection and screen generation checks keep late packets out of a newly opened terminal. */
public final class ClientProductionState {

    private static final AtomicLong GENERATIONS = new AtomicLong(System.nanoTime());
    private static volatile INetHandler supportedConnection, connection;
    private static volatile long session;
    private static volatile PacketProductionSnapshot response;

    private ClientProductionState() {}

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

    public static synchronized void receive(INetHandler source, PacketProductionSnapshot packet) {
        if (packet == null || packet.data == null
            || source == null
            || source != connection
            || session == 0
            || packet.session != session) return;
        PacketProductionSnapshot previous = response;
        if (previous == null || packet.sequence > previous.sequence) response = packet;
    }

    public static PacketProductionSnapshot response() {
        return response;
    }
}
