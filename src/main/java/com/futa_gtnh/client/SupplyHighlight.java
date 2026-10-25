package com.futa_gtnh.client;

import com.futa_gtnh.supply.SupplySource;

/** One client-only waypoint. No world/tile references, scans or network polling. */
public final class SupplyHighlight {

    private static SupplySource target;
    private static long expires;
    private static String label = "";

    private SupplyHighlight() {}

    public static void show(SupplySource source) {
        if (source == null || !source.hasPosition()) return;
        target = source;
        expires = System.nanoTime() + 300_000_000_000L;
        label = "D" + source.dim + "  " + source.x + ", " + source.y + ", " + source.z;
    }

    public static SupplySource target() {
        if (target != null && System.nanoTime() - expires >= 0) clear();
        return target;
    }

    public static boolean selected(SupplySource source) {
        return source != null && source.equals(target());
    }

    public static String label() {
        return label;
    }

    public static void clear() {
        target = null;
        label = "";
    }
}
