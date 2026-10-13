package com.futa_gtnh.network;

import java.util.concurrent.ArrayBlockingQueue;

/** Small bounded queue for world mutations received from a Netty thread. */
public final class ServerActions {

    private static final ArrayBlockingQueue<Runnable> PENDING = new ArrayBlockingQueue<>(256);

    private ServerActions() {}

    public static void enqueue(Runnable action) {
        PENDING.offer(action);
    }

    public static void tick() {
        for (int i = 0; i < 256; i++) {
            Runnable action = PENDING.poll();
            if (action == null) break;
            action.run();
        }
    }

    public static void clear() {
        PENDING.clear();
    }
}
