package com.futa_gtnh.mixins;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.me.PublicMeNetwork;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;

/**
 * GTSWN 2.x "quantum incorporation" bridges an existing machine straight to the bound controller's own grid node.
 * The public controller is deliberately isolated behind its six core ports (32 channels each); a direct attachment
 * would add connections outside that budget, so incorporation onto the public controller is refused.
 */
@Pseudo
@Mixin(targets = "com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry", remap = false)
public abstract class MixinGtswnQuantumIncorporation {

    /** Tag keys written by the quantum terminal (ItemNetworkQuantumTerminal.NBT_ANCHOR_*). */
    private static final String FUTA$DIM = "QT_AnchorDim";
    private static final String FUTA$X = "QT_AnchorX";
    private static final String FUTA$Y = "QT_AnchorY";
    private static final String FUTA$Z = "QT_AnchorZ";

    @Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void futa$refusePublicController(World world, int x, int y, int z, NBTTagCompound terminal,
        EntityPlayer owner, CallbackInfoReturnable<Boolean> cir) {
        if (terminal == null || !PublicMeNetwork.isControllerLocation(
            terminal.getInteger(FUTA$DIM),
            terminal.getInteger(FUTA$X),
            terminal.getInteger(FUTA$Y),
            terminal.getInteger(FUTA$Z))) return;
        if (owner != null)
            owner.addChatMessage(new ChatComponentTranslation("futa_gtnh.quantum.incorporationUnsupported"));
        cir.setReturnValue(false);
    }

    /** Safety net for registrations that reach the public controller another way (e.g. anchor migration). */
    @Redirect(
        method = "maintain",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/IGridHost;getGridNode(Lnet/minecraftforge/common/util/ForgeDirection;)Lappeng/api/networking/IGridNode;"),
        require = 1,
        remap = false)
    private IGridNode futa$keepPublicControllerIsolated(IGridHost controller, ForgeDirection direction) {
        return PublicMeNetwork.isManagedController(controller) ? null : controller.getGridNode(direction);
    }
}
