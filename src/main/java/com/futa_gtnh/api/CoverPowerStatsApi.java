package com.futa_gtnh.api;

import java.util.Collections;
import java.util.Map;

import com.futa_gtnh.stats.CoverFlowTracker;

/**
 * Versioned, read-only access to top-down wireless-cover power statistics.
 *
 * <p>
 * Call on the server thread after scheduling through the consumer's own server task queue.
 * Rows are detached snapshots of the in-memory tracker; there is no persisted history here
 * (account-level history belongs to the consumer's own power monitoring).
 *
 * <p>
 * Semantics: {@code eutIn} is EU/t the cover injected into its host machine (consumer cover),
 * {@code eutOut} is EU/t the cover drained out of its host machine (dynamo cover). Both are
 * measured at the actual GT API call sites inside the covers, so the "wireless feed bypasses
 * GT accounting" gap does not apply.
 */
public final class CoverPowerStatsApi {

    public static final int API_VERSION = 1;

    private CoverPowerStatsApi() {}

    /** Safe to call from any thread. False when GTSWN's node registry is unreachable. */
    public static boolean isAvailable() {
        return CoverFlowTracker.isAvailable();
    }

    /** @return every known wireless-cover host, online and offline alike */
    public static Map<String, CoverPowerNode> listCoverNodes() {
        Map<String, CoverFlowTracker.CoverRecord> records = CoverFlowTracker.records();
        if (records.isEmpty()) return Collections.emptyMap();
        Map<String, CoverPowerNode> rows = new java.util.LinkedHashMap<>(records.size());
        for (Map.Entry<String, CoverFlowTracker.CoverRecord> entry : records.entrySet()) {
            rows.put(entry.getKey(), new CoverPowerNode(entry.getValue()));
        }
        return rows;
    }
}
