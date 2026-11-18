package com.futa_gtnh.miner;

/**
 * Duck interface mixed into the 采矿场 controller ({@code MTEOreDrillingPlantBase}) by
 * {@link com.futa_gtnh.mixins.MixinMTEOreDrillingPlantBase}: apply a one-shot mining-area
 * multiplier chosen in the AE 采矿枢纽 cover GUI.
 *
 * <p>
 * {@code factor} is 1/2/3; the mixin converts it to a target radius of
 * {@code getRadiusInChunks() * factor} (tier 4 = 9 chunks, so ×3 = 18 → 36×36 chunk area).
 * Once applied the radius lives in GT's own NBT-persisted config — the screwdriver keeps
 * working on it, and re-deploying the machine resets to stock (apply again from the cover).
 *
 * <p>
 * When the mixin is skipped (older GT without the chunk radius system) no controller carries
 * this interface and cover calls degrade to a no-op.
 */
public interface MinerAreaEnhance {

    /** Apply a mining-area multiplier (1 = tier stock radius, 2, or 3). */
    void futa$applyRadiusFactor(int factor);
}
