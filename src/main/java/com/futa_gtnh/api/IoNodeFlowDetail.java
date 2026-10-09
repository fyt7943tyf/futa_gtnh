package com.futa_gtnh.api;

import java.util.Collections;
import java.util.List;

/** Full node detail: summary fields plus sample history and per-resource breakdown. */
public final class IoNodeFlowDetail {

    private final IoFlowStatsStatus status;
    private final IoNodeFlowSummary summary;
    private final int intervalSeconds;
    private final long observedAtMs;
    private final List<IoNodeFlowSample> samples;
    private final List<IoResourceFlow> resources;

    public IoNodeFlowDetail(IoFlowStatsStatus status, IoNodeFlowSummary summary, int intervalSeconds, long observedAtMs,
        List<IoNodeFlowSample> samples, List<IoResourceFlow> resources) {
        this.status = status;
        this.summary = summary;
        this.intervalSeconds = intervalSeconds;
        this.observedAtMs = observedAtMs;
        this.samples = Collections.unmodifiableList(samples);
        this.resources = Collections.unmodifiableList(resources);
    }

    public IoFlowStatsStatus getStatus() {
        return status;
    }

    public IoNodeFlowSummary getSummary() {
        return summary;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }

    public long getObservedAtMs() {
        return observedAtMs;
    }

    /** Oldest → newest; aligns with each resource's history arrays. */
    public List<IoNodeFlowSample> getSamples() {
        return samples;
    }

    public List<IoResourceFlow> getResources() {
        return resources;
    }
}
