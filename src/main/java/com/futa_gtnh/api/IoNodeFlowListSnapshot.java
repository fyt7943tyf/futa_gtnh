package com.futa_gtnh.api;

import java.util.Collections;
import java.util.List;

/** Detached node list captured on the server thread. Amounts: items are counted in pieces, fluids in mB. */
public final class IoNodeFlowListSnapshot {

    private final IoFlowStatsStatus status;
    private final List<IoNodeFlowSummary> nodes;
    private final int intervalSeconds;
    private final int sampleCapacity;
    private final long observedAtMs;

    public IoNodeFlowListSnapshot(IoFlowStatsStatus status, List<IoNodeFlowSummary> nodes, int intervalSeconds,
        int sampleCapacity, long observedAtMs) {
        this.status = status;
        this.nodes = Collections.unmodifiableList(nodes);
        this.intervalSeconds = intervalSeconds;
        this.sampleCapacity = sampleCapacity;
        this.observedAtMs = observedAtMs;
    }

    public IoFlowStatsStatus getStatus() {
        return status;
    }

    public List<IoNodeFlowSummary> getNodes() {
        return nodes;
    }

    /** Seconds covered by one history sample. */
    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    /** Ring capacity, i.e. the maximum number of samples a detail can carry. */
    public int getSampleCapacity() {
        return sampleCapacity;
    }

    public long getObservedAtMs() {
        return observedAtMs;
    }
}
