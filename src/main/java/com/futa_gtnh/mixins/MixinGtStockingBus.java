package com.futa_gtnh.mixins;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.futa_gtnh.ae2.StockingBusGuard;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import appeng.util.Platform;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.common.tileentities.machines.MTEHatchInputBusME;

/**
 * Stocking input bus: stop a short real pull from voiding the recipe inputs.
 *
 * <p>
 * Works on 5.09.54.175 through 5.09.55.x; the members used here are the same in all of them.
 */
@Mixin(value = MTEHatchInputBusME.class, remap = false)
public abstract class MixinGtStockingBus {

    @Shadow
    protected boolean processingRecipe;

    @Shadow
    @Final
    protected MTEHatchInputBusME.Slot[] slots;

    @Shadow
    public abstract AENetworkProxy getProxy();

    @Shadow
    protected abstract BaseActionSource getRequestSource();

    /** Items already pulled in the current {@code endRecipeProcessing} call. */
    @Unique
    private List<IAEItemStack> futa$pulled;

    @Unique
    private boolean futa$shortPull;

    /** Items of one kind that the grid can pay for this recipe check; valid when {@link #futa$capReady}. */
    @Unique
    private long futa$affordable;

    @Unique
    private boolean futa$capReady;

    @Unique
    private long futa$lastLogMs;

    @Inject(method = "startRecipeProcessing", at = @At("HEAD"), require = 1, remap = false)
    private void futa$resetCap(CallbackInfo ci) {
        futa$capReady = false;
    }

    /** The simulation ignores AE power; never promise the machine more than the grid can actually pay out. */
    @Inject(method = "updateInformationSlot", at = @At("RETURN"), require = 1, remap = false)
    private void futa$capSimulatedAmount(int index, CallbackInfo ci) {
        if (!processingRecipe || !StockingBusGuard.enabled()) return;
        if (index < 0 || index >= slots.length) return;
        MTEHatchInputBusME.Slot slot = slots[index];
        if (slot == null || slot.extracted == null || slot.extractedAmount <= 0) return;

        if (!futa$capReady) {
            try {
                IEnergyGrid energy = getProxy().getEnergy();
                futa$affordable = StockingBusGuard.affordableItems(
                    energy,
                    slot.createAEStack(1)
                        .getAmountPerUnit());
                futa$capReady = true;
            } catch (GridAccessException e) {
                return;
            }
        }

        if (slot.extractedAmount <= futa$affordable) return;
        if (futa$affordable <= 0) {
            slot.resetExtracted();
        } else {
            slot.extractedAmount = (int) futa$affordable;
            slot.extracted.stackSize = (int) futa$affordable;
        }
    }

    @Inject(method = "endRecipeProcessing", at = @At("HEAD"), require = 1, remap = false)
    private void futa$beginPull(MTEMultiBlockBase controller, CallbackInfoReturnable<CheckRecipeResult> cir) {
        futa$pulled = null;
        futa$shortPull = false;
    }

    @Redirect(
        method = "endRecipeProcessing",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/util/Platform;poweredExtraction(Lappeng/api/networking/energy/IEnergySource;Lappeng/api/storage/IMEInventory;Lappeng/api/storage/data/IAEStack;Lappeng/api/networking/security/BaseActionSource;)Lappeng/api/storage/data/IAEStack;"),
        require = 1,
        remap = false)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private IAEStack futa$trackPull(IEnergySource energy, IMEInventory cell, IAEStack request, BaseActionSource src) {
        IAEStack result = Platform.poweredExtraction(energy, cell, request, src);
        if (result instanceof IAEItemStack pulled) {
            if (futa$pulled == null) futa$pulled = new ArrayList<>();
            futa$pulled.add(pulled);
        }
        if (result == null || result.getStackSize() != request.getStackSize()) {
            futa$shortPull = true;
            futa$lastLogMs = StockingBusGuard.logShortPull(futa$lastLogMs, futa$where(), energy, request, result);
        }
        return result;
    }

    /** The machine is about to shut down; hand back what was pulled so the recipe's inputs are not lost. */
    @Inject(method = "endRecipeProcessing", at = @At("RETURN"), require = 1, remap = false)
    private void futa$refundPulled(MTEMultiBlockBase controller, CallbackInfoReturnable<CheckRecipeResult> cir) {
        List<IAEItemStack> pulled = futa$pulled;
        boolean shortPull = futa$shortPull;
        futa$pulled = null;
        futa$shortPull = false;
        if (!shortPull || pulled == null || pulled.isEmpty() || !StockingBusGuard.enabled()) return;

        long items = 0;
        for (IAEItemStack stack : pulled) items += stack.getStackSize();

        if (!StockingBusGuard.inRecipeCheck()) {
            StockingBusGuard.logRefund(futa$where(), pulled.size(), items, 0, false);
            return;
        }

        long leftover = 0;
        try {
            IMEMonitor<IAEItemStack> network = getProxy().getStorage()
                .getItemInventory();
            BaseActionSource source = getRequestSource();
            for (IAEItemStack stack : pulled) {
                IAEItemStack rest = network.injectItems(stack, Actionable.MODULATE, source);
                if (rest != null) leftover += rest.getStackSize();
            }
        } catch (GridAccessException e) {
            leftover = items;
        }
        StockingBusGuard.logRefund(futa$where(), pulled.size(), items, leftover, true);
    }

    @Unique
    private String futa$where() {
        IGregTechTileEntity tile = ((MTEHatchInputBusME) (Object) this).getBaseMetaTileEntity();
        if (tile == null) return "unknown";
        return "dim " + tile.getWorld().provider.dimensionId
            + " ("
            + tile.getXCoord()
            + ", "
            + tile.getYCoord()
            + ", "
            + tile.getZCoord()
            + ")";
    }
}
