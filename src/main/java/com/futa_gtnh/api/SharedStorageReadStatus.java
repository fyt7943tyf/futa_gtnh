package com.futa_gtnh.api;

import java.util.Objects;

/** Immutable warehouse lifecycle information; NOT_READY is different from a ready, empty warehouse. */
public final class SharedStorageReadStatus {

    public enum State {
        READY,
        NOT_READY
    }

    private final State state;
    private final String generation;
    private final int revision;

    public SharedStorageReadStatus(State state, String generation, int revision) {
        this.state = Objects.requireNonNull(state, "state");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.revision = revision;
    }

    public State getState() {
        return state;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    /** Changes on start, storage replacement/reload and stop; it is not a persisted world identifier. */
    public String getGeneration() {
        return generation;
    }

    /** Valid only when ready. Compare for equality; it may wrap and is not unique across generations. */
    public int getRevision() {
        return revision;
    }
}
