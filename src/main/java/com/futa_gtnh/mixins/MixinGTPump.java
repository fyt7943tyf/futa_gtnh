package com.futa_gtnh.mixins;

import net.minecraft.block.Block;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.futa_gtnh.common.PumpFluidUpdates;

import gregtech.common.tileentities.machines.basic.MTEPump;

/** GT removes vanilla fluids with flag 2, leaving adjacent stationary flows asleep. */
@Mixin(value = MTEPump.class, remap = false)
public abstract class MixinGTPump {

    @Redirect(
        method = "consumeFluid(III)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;setBlock(IIILnet/minecraft/block/Block;II)Z",
            remap = true),
        require = 1,
        remap = false)
    private boolean futa$refreshAdjacentFlows(World world, int x, int y, int z, Block block, int metadata, int flags) {
        return PumpFluidUpdates.setPumpBlock(world, x, y, z, block, metadata, flags);
    }
}
