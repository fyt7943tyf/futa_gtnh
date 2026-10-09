package com.futa_gtnh.api;

import com.futa_gtnh.stats.IoFlowStats;
import com.futa_gtnh.stats.IoFlowStatsSnapshots;

/**
 * Versioned, read-only access to per-node IO-flow statistics (IO nodes and shared terminals
 * that opted in via their in-game configuration).
 *
 * <p>
 * Call on the server thread after scheduling through the consumer's own server task queue
 * (the AE2-Web adapter already does this). All returned objects are detached and immutable;
 * history rings are cloned on read. This API has no dependency on AE2-Web.
 *
 * <p>
 * Amounts: items are counted in pieces, fluids in mB. History samples cover
 * {@link IoFlowStats#SAMPLE_INTERVAL_TICKS}/20 seconds each; a node keeps at most
 * {@link IoFlowStats#SAMPLE_COUNT} of them.
 */
public final class IoFlowStatsApi {

    public static final int API_VERSION = 1;

    private IoFlowStatsApi() {}

    /** Safe to call from any thread, including before the server starts. */
    public static int getApiVersion() {
        return API_VERSION;
    }

    /** Returns lifecycle information without copying statistics. */
    public static IoFlowStatsStatus getStatus() {
        return IoFlowStatsSnapshots.status();
    }

    /** Returns every statistics node (online and offline, including disabled-but-known ones). */
    public static IoNodeFlowListSnapshot listNodes() {
        return IoFlowStatsSnapshots.listSnapshot();
    }

    /** @return full detail of one node, or null when the key is unknown */
    public static IoNodeFlowDetail getNodeDetail(String key) {
        if (key == null || key.isEmpty()) return null;
        return IoFlowStatsSnapshots.detail(key);
    }
}
