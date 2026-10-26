package com.futa_gtnh.mixins;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.me.GtswnQuantumCompat;
import com.futa_gtnh.me.PublicMeNetwork;

import appeng.api.networking.IGridNode;
import appeng.me.helpers.AENetworkProxy;

/** Public quantum branches share the same six native channel paths as ordinary public access points. */
@Pseudo
@Mixin(targets = "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode", remap = false)
public abstract class MixinGtswnQuantumNode {

    @Shadow
    private int anchorDim;
    @Shadow
    private int anchorX;
    @Shadow
    private int anchorY;
    @Shadow
    private int anchorZ;

    @Shadow
    public abstract AENetworkProxy getProxy();

    @Shadow
    private void destroyBridgeConnection() {
        throw new AssertionError();
    }

    @Unique
    private int futa$publicPort = -1;

    @Unique
    private boolean futa$controllerConflict;

    @Unique
    private boolean futa$isPublicAnchor() {
        return PublicMeNetwork.isControllerLocation(anchorDim, anchorX, anchorY, anchorZ);
    }

    @Inject(method = "maintainConnection", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void futa$isolatePrivateController(CallbackInfo ci) {
        futa$controllerConflict = futa$isPublicAnchor()
            && GtswnQuantumCompat.hasForeignController(getProxy().getNode());
        if (futa$controllerConflict) {
            destroyBridgeConnection();
            ci.cancel();
        }
    }

    @Inject(method = "getOfflineReasonKey", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void futa$explainControllerConflict(CallbackInfoReturnable<String> cir) {
        if (futa$controllerConflict) cir.setReturnValue("futa_gtnh.quantum.controllerConflict");
    }

    @Redirect(
        method = "tryConnect",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/me/helpers/AENetworkProxy;getNode()Lappeng/api/networking/IGridNode;",
            ordinal = 0),
        require = 1,
        remap = false)
    private IGridNode futa$connectThroughPublicPort(AENetworkProxy anchorProxy) {
        if (!futa$isPublicAnchor()) return anchorProxy.getNode();
        int selected = GtswnQuantumCompat.selectPort(futa$publicPort);
        if (selected != futa$publicPort) {
            futa$publicPort = selected;
            ((TileEntity) (Object) this).markDirty();
        }
        return GtswnQuantumCompat.portNode(futa$publicPort);
    }

    @Inject(method = "setAnchor", at = @At("HEAD"), require = 1, remap = false)
    private void futa$resetPort(int dim, int x, int y, int z, CallbackInfo ci) {
        if (dim != anchorDim || x != anchorX || y != anchorY || z != anchorZ) futa$publicPort = -1;
    }

    @Inject(method = { "readFromNBT", "func_145839_a" }, at = @At("RETURN"), require = 1, remap = false)
    private void futa$readPort(NBTTagCompound tag, CallbackInfo ci) {
        futa$publicPort = tag.hasKey(GtswnQuantumCompat.PORT_NBT) ? tag.getInteger(GtswnQuantumCompat.PORT_NBT) : -1;
        if (futa$publicPort < 0 || futa$publicPort >= PublicMeNetwork.PORT_COUNT) futa$publicPort = -1;
    }

    @Inject(method = { "writeToNBT", "func_145841_b" }, at = @At("RETURN"), require = 1, remap = false)
    private void futa$writePort(NBTTagCompound tag, CallbackInfo ci) {
        if (futa$publicPort >= 0) tag.setInteger(GtswnQuantumCompat.PORT_NBT, futa$publicPort);
        else tag.removeTag(GtswnQuantumCompat.PORT_NBT);
    }

    @Inject(method = "checkNetworkOverload", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void futa$useNativePublicChannelLimit(CallbackInfo ci) {
        // Native controller-port paths enforce capacity. A player's branch must never explode shared infrastructure.
        if (futa$isPublicAnchor()) ci.cancel();
    }
}
