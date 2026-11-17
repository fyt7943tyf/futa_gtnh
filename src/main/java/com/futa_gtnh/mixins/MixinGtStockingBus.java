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
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.GridAccessException;
import appeng.me.helpers.AENetworkProxy;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.recipe.check.CheckRecipeResult;
import gregtech.common.tileentities.machines.MTEHatchInputBusME;

/**
 * Stocking input bus: stop a short real pull from voiding the recipe inputs.
 *
 * <p>
 * Works on 5.09.54.175 through 5.09.55.x; the members used here are the same in all of them.
 *
 * <p>
 * Deliberately does not redirect {@code Platform.poweredExtraction}: ProgrammableHatches redirects that very call and
 * Mixin allows only one redirect per call site (the second one crashes class loading). The network is observed through
 * the {@code getItemInventory()} call instead, the hooks are {@code require = 0} so a layout change disables them
 * rather
 * than the game, and the high priority makes this mixin the one that yields if another mod claims the same site.
 */
@Mixin(value = MTEHatchInputBusME.class, priority = 2000, remap = false)
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

    /** Items taken from the network by the current {@code endRecipeProcessing} call. */
    @Unique
    private List<IAEItemStack> futa$pulled;

    /** Items the bus meant to take from the network in the current {@code endRecipeProcessing} call. */
    @Unique
    private long futa$planned;

    /** Items of one kind that the grid can pay for this recipe check; valid when {@link #futa$capReady}. */
    @Unique
    private long futa$affordable;

    @Unique
    private boolean futa$capReady;

    /** Another mod (ProgrammableHatches) replaces where this bus extracts from; leave those alone. */
    @Unique
    private boolean futa$skip;

    @Unique
    private long futa$lastLogMs;

    @Inject(method = "startRecipeProcessing", at = @At("HEAD"), require = 0, remap = false)
    private void futa$resetCap(CallbackInfo ci) {
        futa$capReady = false;
        futa$skip = StockingBusGuard.externallyOverridden(this);
    }

    /** The simulation ignores AE power; never promise the machine more than the grid can actually pay out. */
    @Inject(method = "updateInformationSlot", at = @At("RETURN"), require = 0, remap = false)
    private void futa$capSimulatedAmount(int index, CallbackInfo ci) {
        if (!processingRecipe || futa$skip || !StockingBusGuard.enabled()) return;
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

    @Inject(method = "endRecipeProcessing", at = @At("HEAD"), require = 0, remap = false)
    private void futa$beginPull(MTEMultiBlockBase controller, CallbackInfoReturnable<CheckRecipeResult> cir) {
        futa$pulled = null;
        futa$planned = 0;
        if (futa$skip) return;
        for (MTEHatchInputBusME.Slot slot : slots) {
            if (slot == null || slot.extracted == null || slot.extractedAmount == 0) continue;
            int toExtract = slot.extractedAmount - slot.extracted.stackSize;
            if (toExtract > 0) futa$planned += toExtract;
        }
    }

    /** Hand the pull code a view of the network that remembers what it takes out. */
    @Redirect(
        method = "endRecipeProcessing",
        at = @At(
            value = "INVOKE",
            target = "Lappeng/api/networking/storage/IStorageGrid;getItemInventory()Lappeng/api/storage/IMEMonitor;"),
        require = 0,
        remap = false)
    private IMEMonitor<IAEItemStack> futa$recordPulls(IStorageGrid grid) {
        IMEMonitor<IAEItemStack> network = grid.getItemInventory();
        if (futa$skip || !StockingBusGuard.enabled()) return network;
        futa$pulled = new ArrayList<>();
        return StockingBusGuard.recordingMonitor(network, futa$pulled);
    }

    /** The machine is about to shut down; hand back what was pulled so the recipe's inputs are not lost. */
    @Inject(method = "endRecipeProcessing", at = @At("RETURN"), require = 0, remap = false)
    private void futa$refundPulled(MTEMultiBlockBase controller, CallbackInfoReturnable<CheckRecipeResult> cir) {
        List<IAEItemStack> pulled = futa$pulled;
        long planned = futa$planned;
        futa$pulled = null;
        futa$planned = 0;
        CheckRecipeResult result = cir.getReturnValue();
        if (pulled == null || result == null || result.wasSuccessful()) return;

        long items = 0;
        for (IAEItemStack stack : pulled) items += stack.getStackSize();

        IEnergyGrid energy = null;
        try {
            energy = getProxy().getEnergy();
        } catch (GridAccessException ignored) {
            // the log line just omits the power figure
        }
        long previousLogMs = futa$lastLogMs;
        futa$lastLogMs = StockingBusGuard.logShortPull(futa$lastLogMs, futa$where(), energy, planned, items);
        boolean diagnose = futa$lastLogMs != previousLogMs;

        boolean refund = StockingBusGuard.enabled() && !pulled.isEmpty();
        if (refund && !StockingBusGuard.inRecipeCheck()) {
            StockingBusGuard.logRefund(futa$where(), pulled.size(), items, 0, false);
            refund = false;
        }
        if (!refund && !diagnose) return;

        IMEMonitor<IAEItemStack> network;
        try {
            network = getProxy().getStorage()
                .getItemInventory();
        } catch (GridAccessException e) {
            if (refund) StockingBusGuard.logRefund(futa$where(), pulled.size(), items, items, true);
            return;
        }
        BaseActionSource source = getRequestSource();

        if (refund) {
            long leftover = 0;
            for (IAEItemStack stack : pulled) {
                IAEItemStack rest = network.injectItems(stack, Actionable.MODULATE, source);
                if (rest != null) leftover += rest.getStackSize();
            }
            StockingBusGuard.logRefund(futa$where(), pulled.size(), items, leftover, true);
        }

        // After the refund the network is back in the state the check started from, so these numbers are comparable.
        if (diagnose) {
            String where = futa$where();
            for (MTEHatchInputBusME.Slot slot : slots) {
                if (slot == null || slot.extracted == null || slot.extractedAmount == 0) continue;
                long toExtract = slot.extractedAmount - slot.extracted.stackSize;
                if (toExtract <= 0) continue;
                long got = 0;
                IAEItemStack kind = slot.createAEStack(1);
                for (IAEItemStack stack : pulled) if (stack.isSameType(kind)) got += stack.getStackSize();
                StockingBusGuard
                    .logSlot(where, slot.config, slot.extractedAmount, toExtract, got, network, kind, source);
            }
        }
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
