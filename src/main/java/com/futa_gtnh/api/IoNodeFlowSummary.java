package com.futa_gtnh.api;

/**
 * One statistics node (IO node or shared terminal) as shown in listings.
 *
 * <p>
 * {@code latest*} is the most recently completed sample window, {@code avg*} is the mean over the
 * whole filled history, {@code total*} accumulates since statistics were first enabled.
 */
public final class IoNodeFlowSummary {

    /** "dim:x:y:z" — stable as long as the block stays where it is. */
    private final String key;
    private final int dim;
    private final int x;
    private final int y;
    private final int z;
    /** "io_node" or "shared_terminal". */
    private final String nodeType;
    /** Configured display name; empty means "unnamed" (the web falls back to coordinates). */
    private final String name;
    /** "IN", "OUT" or "BOTH". */
    private final String mode;
    private final boolean enabled;
    private final boolean online;
    private final int sampleCount;
    private final long latestItemIn;
    private final long latestItemOut;
    private final long latestFluidIn;
    private final long latestFluidOut;
    private final double avgItemIn;
    private final double avgItemOut;
    private final double avgFluidIn;
    private final double avgFluidOut;
    private final long totalItemIn;
    private final long totalItemOut;
    private final long totalFluidIn;
    private final long totalFluidOut;
    private final int resourceCount;

    // 参数确实多，但都是逐字段平铺的只读数据；拆成 Builder 只会让调用点更啰嗦
    public IoNodeFlowSummary(String key, int dim, int x, int y, int z, String nodeType, String name, String mode,
        boolean enabled, boolean online, int sampleCount, long latestItemIn, long latestItemOut, long latestFluidIn,
        long latestFluidOut, double avgItemIn, double avgItemOut, double avgFluidIn, double avgFluidOut,
        long totalItemIn, long totalItemOut, long totalFluidIn, long totalFluidOut, int resourceCount) {
        this.key = key;
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.nodeType = nodeType;
        this.name = name;
        this.mode = mode;
        this.enabled = enabled;
        this.online = online;
        this.sampleCount = sampleCount;
        this.latestItemIn = latestItemIn;
        this.latestItemOut = latestItemOut;
        this.latestFluidIn = latestFluidIn;
        this.latestFluidOut = latestFluidOut;
        this.avgItemIn = avgItemIn;
        this.avgItemOut = avgItemOut;
        this.avgFluidIn = avgFluidIn;
        this.avgFluidOut = avgFluidOut;
        this.totalItemIn = totalItemIn;
        this.totalItemOut = totalItemOut;
        this.totalFluidIn = totalFluidIn;
        this.totalFluidOut = totalFluidOut;
        this.resourceCount = resourceCount;
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

    public String getNodeType() {
        return nodeType;
    }

    public String getName() {
        return name;
    }

    public String getMode() {
        return mode;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isOnline() {
        return online;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public long getLatestItemIn() {
        return latestItemIn;
    }

    public long getLatestItemOut() {
        return latestItemOut;
    }

    public long getLatestFluidIn() {
        return latestFluidIn;
    }

    public long getLatestFluidOut() {
        return latestFluidOut;
    }

    public double getAvgItemIn() {
        return avgItemIn;
    }

    public double getAvgItemOut() {
        return avgItemOut;
    }

    public double getAvgFluidIn() {
        return avgFluidIn;
    }

    public double getAvgFluidOut() {
        return avgFluidOut;
    }

    public long getTotalItemIn() {
        return totalItemIn;
    }

    public long getTotalItemOut() {
        return totalItemOut;
    }

    public long getTotalFluidIn() {
        return totalFluidIn;
    }

    public long getTotalFluidOut() {
        return totalFluidOut;
    }

    public int getResourceCount() {
        return resourceCount;
    }
}
