package com.futa_gtnh.common;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.world.World;

import com.futa_gtnh.Config;

/** One bounded, shared work allowance per dimension and world tick. Keys never retain a world or a tile. */
final class SwiftStepAuraBudget {

    static final int MAX_UPDATES = 4096;
    static final int MAX_SCANS = 2048;
    private static final Map<World, SwiftStepAuraBudget> WORLDS = new WeakHashMap<>();
    private final Map<Object, Integer> applied = new HashMap<>();
    private long tick = Long.MIN_VALUE;
    private long started;
    private int updates;
    private int scans;
    boolean limited;

    static SwiftStepAuraBudget get(World world) {
        SwiftStepAuraBudget budget = WORLDS.computeIfAbsent(world, ignored -> new SwiftStepAuraBudget());
        long now = world.getTotalWorldTime();
        if (budget.tick != now) {
            budget.tick = now;
            budget.started = 0;
            budget.updates = 0;
            budget.scans = 0;
            budget.limited = false;
            budget.applied.clear();
        }
        return budget;
    }

    static Long position(int x, int y, int z) {
        return ((long) x & 0x3ffffffL) << 38 | ((long) z & 0x3ffffffL) << 12 | (y & 0xfffL);
    }

    boolean scan() {
        if (scans >= MAX_SCANS) {
            limited = true;
            return false;
        }
        if (!available()) return false;
        scans++;
        return true;
    }

    boolean available() {
        if (started == 0) started = System.nanoTime();
        boolean allowed = updates < MAX_UPDATES && (Config.swiftStepAuraBudgetMillis == 0
            || System.nanoTime() - started < Config.swiftStepAuraBudgetMillis * 1_000_000L);
        if (!allowed) limited = true;
        return allowed;
    }

    boolean claim(Object key, int allowance) {
        int done = applied.getOrDefault(key, 0);
        if (done >= allowance || !available()) return false;
        applied.put(key, done + 1);
        updates++;
        return true;
    }

    int count() {
        return updates;
    }

    static void forget(World world) {
        WORLDS.remove(world);
    }

    static void clear() {
        WORLDS.clear();
    }
}
