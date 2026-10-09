package com.futa_gtnh.api;

import com.futa_gtnh.stats.CoverFlowTracker;

/** Detached view of one wireless-cover host machine. Immutable. */
public final class CoverPowerNode {

    /** "dim:x:y:z" of the host machine. */
    private final String key;
    private final int dim;
    private final int x;
    private final int y;
    private final int z;
    private final String hostName;
    private final String hostClass;
    /** "SINGLE_BLOCK", "MULTIBLOCK" or "UNKNOWN". */
    private final String machineKind;
    private final boolean configured;
    private final boolean loaded;
    private final boolean missing;
    /** EU/t injected into the host machine by consumer covers, measured this sample. */
    private final double eutIn;
    /** EU/t drained out of the host machine by dynamo covers, measured this sample. */
    private final double eutOut;
    /** 0 = consuming, 1 = generating, -1 = no flow this sample. */
    private final int powerType;
    private final long lastSampleMs;

    public CoverPowerNode(CoverFlowTracker.CoverRecord record) {
        key = record.key;
        dim = record.dim;
        x = record.x;
        y = record.y;
        z = record.z;
        hostName = record.hostName;
        hostClass = record.hostClass;
        machineKind = record.machineKind;
        configured = record.configured;
        loaded = record.loaded;
        missing = record.missing;
        eutIn = record.eutIn;
        eutOut = record.eutOut;
        powerType = record.powerType();
        lastSampleMs = record.lastSampleMs;
    }

    public String getKey() {
        return key;
    }

    public int getDim() {
        return dim;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public String getHostName() {
        return hostName;
    }

    public String getHostClass() {
        return hostClass;
    }

    public String getMachineKind() {
        return machineKind;
    }

    public boolean isConfigured() {
        return configured;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isMissing() {
        return missing;
    }

    public double getEutIn() {
        return eutIn;
    }

    public double getEutOut() {
        return eutOut;
    }

    public int getPowerType() {
        return powerType;
    }

    public long getLastSampleMs() {
        return lastSampleMs;
    }
}
