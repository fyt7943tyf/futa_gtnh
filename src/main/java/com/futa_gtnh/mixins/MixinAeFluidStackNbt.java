package com.futa_gtnh.mixins;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAETagCompound;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;

/** Completes AE 1082's otherwise unused fluid-tag field so migration cannot merge distinct tagged fluids. */
@Mixin(value = AEFluidStack.class, remap = false)
public abstract class MixinAeFluidStackNbt {

    @Shadow
    @Final
    private Fluid fluid;

    @Shadow
    private IAETagCompound tagCompound;

    @Shadow
    @Final
    @Mutable
    private int myHash;

    @Inject(method = "<init>(Lnet/minecraftforge/fluids/FluidStack;)V", at = @At("RETURN"), require = 1, remap = false)
    private void futa$preserveFluidTag(FluidStack source, CallbackInfo ci) {
        futa$setFluidTag(source.tag);
    }

    @Inject(method = "setTagCompound", at = @At("RETURN"), require = 1, remap = false)
    private void futa$updateFluidTag(NBTTagCompound tag, CallbackInfo ci) {
        futa$setFluidTag(tag);
    }

    @Inject(method = "writeToNBT", at = @At("RETURN"), require = 1, remap = false)
    private void futa$writeForgeFluidTag(NBTTagCompound encoded, CallbackInfo ci) {
        // Forge FluidStack reads uppercase Tag; AE's dormant writer used lowercase tag.
        encoded.removeTag("tag");
        if (tagCompound == null) encoded.removeTag("Tag");
        else encoded.setTag("Tag", tagCompound.getNBTTagCompoundCopy());
    }

    @Redirect(
        method = { "loadFluidStackFromNBT", "loadFluidStackFromPacket" },
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/fluids/FluidStack;loadFluidStackFromNBT(Lnet/minecraft/nbt/NBTTagCompound;)Lnet/minecraftforge/fluids/FluidStack;"),
        require = 2,
        remap = false)
    private static FluidStack futa$readForgeFluidTag(NBTTagCompound encoded) {
        FluidStack result = FluidStack.loadFluidStackFromNBT(encoded);
        // Packet decoding builds AE's lowercase intermediate compound; also accept legacy AE disk data.
        if (result != null && result.tag == null && encoded.hasKey("tag", 10)) {
            result.tag = (NBTTagCompound) encoded.getCompoundTag("tag")
                .copy();
        }
        return result;
    }

    @Inject(
        method = "isSameType(Lappeng/api/storage/data/IAEFluidStack;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 1,
        remap = false)
    private void futa$compareFluidIdentity(IAEFluidStack other, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(other != null && fluid == other.getFluid() && tagCompound == other.getTagCompound());
    }

    @Unique
    private void futa$setFluidTag(NBTTagCompound tag) {
        tagCompound = null;
        if (tag != null && !tag.hasNoTags()) {
            // Use AE's existing weak NBT interner: native AEFluidStack.equals compares tag references.
            // The bucket is only an interner key; it never enters the world or changes the fluid's tag.
            ItemStack identity = new ItemStack(Items.bucket);
            identity.setTagCompound((NBTTagCompound) tag.copy());
            tagCompound = AEItemStack.create(identity)
                .getTagCompound();
        }
        myHash = fluid.getName()
            .hashCode() ^ (tagCompound == null ? 0 : System.identityHashCode(tagCompound));
    }
}
