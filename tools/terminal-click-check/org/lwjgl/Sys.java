package org.lwjgl;

public final class Sys {

    public static long now = 10000L;

    public static long getTime() {
        return now;
    }

    public static long getTimerResolution() {
        return 1000L;
    }

    public static void initialize() {}
}
