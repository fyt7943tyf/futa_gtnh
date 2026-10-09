package com.futa_gtnh.api;

/**
 * Per-resource flow of one node. Items are counted in pieces, fluids in mB.
 *
 * <p>
 * {@code historyIn}/{@code historyOut} run oldest → newest and share the node's sample
 * alignment (index {@code historyIn.length - 1} is the newest completed sample).
 */
public final class IoResourceFlow {

    /** "item" or "fluid". */
    private final String resourceType;
    private final String registryName;
    private final String metadata;
    private final String displayName;
    private final boolean hasNbt;
    /** true = 「其他」聚合桶（超出跟踪上限的资源合并行，没有图标和注册名）。 */
    private final boolean aggregate;
    private final long latestIn;
    private final long latestOut;
    private final double avgIn;
    private final double avgOut;
    private final long totalIn;
    private final long totalOut;
    private final long[] historyIn;
    private final long[] historyOut;

    public IoResourceFlow(String resourceType, String registryName, String metadata, String displayName, boolean hasNbt,
        boolean aggregate, long latestIn, long latestOut, double avgIn, double avgOut, long totalIn, long totalOut,
        long[] historyIn, long[] historyOut) {
        this.resourceType = resourceType;
        this.registryName = registryName;
        this.metadata = metadata;
        this.displayName = displayName;
        this.hasNbt = hasNbt;
        this.aggregate = aggregate;
        this.latestIn = latestIn;
        this.latestOut = latestOut;
        this.avgIn = avgIn;
        this.avgOut = avgOut;
        this.totalIn = totalIn;
        this.totalOut = totalOut;
        this.historyIn = historyIn.clone();
        this.historyOut = historyOut.clone();
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getRegistryName() {
        return registryName;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isHasNbt() {
        return hasNbt;
    }

    public boolean isAggregate() {
        return aggregate;
    }

    public long getLatestIn() {
        return latestIn;
    }

    public long getLatestOut() {
        return latestOut;
    }

    public double getAvgIn() {
        return avgIn;
    }

    public double getAvgOut() {
        return avgOut;
    }

    public long getTotalIn() {
        return totalIn;
    }

    public long getTotalOut() {
        return totalOut;
    }

    public long[] getHistoryIn() {
        return historyIn.clone();
    }

    public long[] getHistoryOut() {
        return historyOut.clone();
    }
}
