package com.futa_gtnh.common;

/** Per-wearer feedback; contains no game object references. */
public final class SwiftStepAuraStats {

    public int crops;
    public int crosses;
    public int botania;
    public int updates;
    public int blocked;
    public boolean limited;

    void targetsReset() {
        crops = 0;
        crosses = 0;
        botania = 0;
    }

    void windowReset() {
        updates = 0;
        blocked = 0;
        limited = false;
    }
}
