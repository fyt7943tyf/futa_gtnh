package com.futa_gtnh.api;

import java.util.Objects;

/** Immutable IO-flow statistics lifecycle information; NOT_READY is different from ready-and-empty. */
public final class IoFlowStatsStatus {

    public enum State {
        READY,
        NOT_READY
    }

    private final State state;
    private final String generation;

    public IoFlowStatsStatus(State state, String generation) {
        this.state = Objects.requireNonNull(state, "state");
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    public State getState() {
        return state;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    /** Changes on server start/stop; it is not a persisted world identifier. */
    public String getGeneration() {
        return generation;
    }
}
