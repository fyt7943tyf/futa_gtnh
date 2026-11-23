package com.futa_gtnh.mixins;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.miner.CoverMinerIo;
import com.futa_gtnh.miner.MinerAreaEnhance;

import gregtech.api.objects.GTChunkManager;
import gregtech.common.tileentities.machines.multi.MTEDrillerBase;
import gregtech.common.tileentities.machines.multi.MTEOreDrillingPlantBase;
import it.unimi.dsi.fastutil.longs.LongList;

/**
 * Mining-area multiplier for the 采矿场 (see the AE 采矿枢纽 cover): {@link #futa$applyRadiusFactor(int)}
 * scales the configured chunk radius to {@code getRadiusInChunks() * factor} and reruns GT's own
 * {@code adjustChunkRadius} cleanup sequence (release the loaded chunk → drop the ore list →
 * request a fresh working chunk), so the walk continues seamlessly in the enlarged area.
 *
 * <p>
 * GT 5.09.5x keeps the effective radius in the private, NBT-persisted field {@code chunkRadiusConfig}
 * (the bounds math reads it directly, not the abstract getter). Writing it here makes the change GT's
 * own afterwards: the screwdriver's radius cycling operates on the same field, and it survives relogs.
 *
 * <p>
 * The chunk radius system is version-guarded in {@link FutaGtnhMixinPlugin} via skeleton probing, so
 * older GT skips this mixin entirely and no controller carries the duck interface. The chunk
 * bookkeeping members ({@code mCurrentChunk}/{@code mChunkLoadingEnabled}) live on the
 * {@code MTEDrillerBase} superclass, so they are reached through plain casts (compile-time verified)
 * instead of shadows of inherited members, which the annotation processor cannot resolve.
 */
@Mixin(value = MTEOreDrillingPlantBase.class, remap = false)
public abstract class MixinMTEOreDrillingPlantBase implements MinerAreaEnhance {

    @Shadow
    private int chunkRadiusConfig;

    @Shadow
    @Final
    private LongList oreBlockPositions;

    @Shadow
    protected abstract int getRadiusInChunks();

    @Shadow
    private void createInitialWorkingChunk() {}

    @Inject(method = "tryProcessOreList()Z", at = @At("HEAD"), require = 0)
    private void futa$refillDrillingFluid(CallbackInfoReturnable<Boolean> cir) {
        CoverMinerIo.refillBeforeMining((MTEOreDrillingPlantBase) (Object) this);
    }

    @Override
    @Unique
    public void futa$applyRadiusFactor(int factor) {
        if (factor < 1) factor = 1;
        if (factor > 3) factor = 3;
        int target = this.getRadiusInChunks() * factor;
        if (this.chunkRadiusConfig == target) return;
        this.chunkRadiusConfig = target;
        // Same cleanup sequence as GT's adjustChunkRadius, minus the +/- cycling.
        MTEDrillerBase self = (MTEDrillerBase) (Object) this;
        ChunkCoordIntPair current = self.getActiveChunk();
        if (current != null && self.isChunkLoadingEnabled()) {
            GTChunkManager.releaseChunk((TileEntity) self.getBaseMetaTileEntity(), current);
        }
        this.oreBlockPositions.clear();
        this.createInitialWorkingChunk();
    }
}
