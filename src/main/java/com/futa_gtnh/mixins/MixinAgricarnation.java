package com.futa_gtnh.mixins;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.common.SwiftStepGrowthAura;

import vazkii.botania.api.subtile.SubTileFunctional;
import vazkii.botania.common.block.subtile.functional.SubTileAgricarnation;

/** Agricarnation retains its random position, range, redstone and mana checks for crop-stick plants. */
@Pseudo
@Mixin(value = SubTileAgricarnation.class, remap = false)
public abstract class MixinAgricarnation extends SubTileFunctional {

    @Inject(method = "isPlant", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void futa$recognizeCropStick(int x, int y, int z, CallbackInfoReturnable<Boolean> result) {
        World world = supertile.getWorldObj();
        if (!world.isRemote && world.blockExists(x, y, z)
            && SwiftStepGrowthAura.canBoostCrop(world.getTileEntity(x, y, z))) result.setReturnValue(true);
    }

    @Redirect(
        method = "onUpdate",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;scheduleBlockUpdate(IIILnet/minecraft/block/Block;I)V",
            remap = true),
        require = 1,
        remap = false)
    private void futa$growCropStick(World world, int x, int y, int z, Block block, int delay) {
        TileEntity tile = world.blockExists(x, y, z) ? world.getTileEntity(x, y, z) : null;
        if (!SwiftStepGrowthAura.boostCrop(tile)) world.scheduleBlockUpdate(x, y, z, block, delay);
    }
}
