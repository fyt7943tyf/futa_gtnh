package com.futa_gtnh.shared;

import java.util.Map;
import appeng.api.config.Actionable;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IItemList;
import appeng.me.storage.MEInventoryHandler;
import appeng.util.item.AEItemStack;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStackType;
import appeng.util.item.AEFluidStackType;

/** v1.33.10 ItemPool/FluidPool bodies verbatim; an isolated always-attached provider, no live server globals. */
final class LegacyPoolProviders13310 {
    private static final long REPORT_CAP=(1L<<52)-1;
    private static final class Context { boolean manual; }
    private static final ThreadLocal<Context> CONTEXT=ThreadLocal.withInitial(Context::new);
    private final LegacySharedStorage13310 pool;
    private int providerWrites;
    LegacyPoolProviders13310(LegacySharedStorage13310 pool){this.pool=pool;}
    private boolean attached(){return true;}
    IMEInventoryHandler<IAEItemStack> items(){return new MEInventoryHandler<>(new ItemPool(),AEItemStackType.ITEM_STACK_TYPE);}
    IMEInventoryHandler<IAEFluidStack> fluids(){return new MEInventoryHandler<>(new FluidPool(),AEFluidStackType.FLUID_STACK_TYPE);}
    private final class ItemPool implements IMEInventory<IAEItemStack> {

        @Override
        public IAEItemStack injectItems(IAEItemStack input, Actionable mode, BaseActionSource source) {
            if (input == null || !attached() || input.getStackSize() <= 0) return input;
            ItemKey key = ItemKey.of(input.getItemStack());
            long amount = Math.min(input.getStackSize(), pool.poolItemSpace(key, CONTEXT.get().manual));
            if (mode == Actionable.MODULATE) {
                providerWrites++;
                try {
                    amount = pool.poolInsertItem(key, amount, CONTEXT.get().manual);
                } finally {
                    providerWrites--;
                }
            }
            if (amount == input.getStackSize()) return null;
            IAEItemStack rest = input.copy();
            rest.setStackSize(input.getStackSize() - amount);
            return rest;
        }

        @Override
        public IAEItemStack extractItems(IAEItemStack input, Actionable mode, BaseActionSource source) {
            if (input == null || !attached() || input.getStackSize() <= 0) return null;
            ItemKey key = ItemKey.of(input.getItemStack());
            long amount = Math.min(input.getStackSize(), pool.getItemAmountPool(key));
            if (mode == Actionable.MODULATE) {
                providerWrites++;
                try {
                    amount = pool.poolExtractItem(key, amount);
                } finally {
                    providerWrites--;
                }
            }
            if (amount <= 0) return null;
            IAEItemStack result = input.copy();
            result.setStackSize(amount);
            return result;
        }

        @Override
        public IAEItemStack getAvailableItem(IAEItemStack request, int iteration) {
            if (request == null || !attached()) return null;
            ItemKey key = ItemKey.of(request.getItemStack());
            long amount = Math.min(REPORT_CAP, pool.getItemAmountPool(key));
            if (amount <= 0) return null;
            IAEItemStack result = request.copy();
            result.setStackSize(amount);
            return result;
        }

        @Override
        public IItemList<IAEItemStack> getAvailableItems(IItemList<IAEItemStack> out, int iteration) {
            if (attached()) for (Map.Entry<ItemKey, Long> row : pool.snapshotPoolItems()) {
                IAEItemStack value = AEItemStack.create(
                    row.getKey()
                        .prototype());
                value.setStackSize(Math.min(REPORT_CAP, row.getValue()));
                out.add(value);
            }
            return out;
        }
    }

    private final class FluidPool implements IMEInventory<IAEFluidStack> {

        @Override
        public IAEFluidStack injectItems(IAEFluidStack input, Actionable mode, BaseActionSource source) {
            if (input == null || !attached() || input.getStackSize() <= 0) return input;
            FluidKey key = FluidKey.of(input.getFluidStack());
            long amount = Math.min(input.getStackSize(), pool.poolFluidSpace(key, CONTEXT.get().manual));
            if (mode == Actionable.MODULATE) {
                providerWrites++;
                try {
                    amount = pool.poolInsertFluid(key, amount, CONTEXT.get().manual);
                } finally {
                    providerWrites--;
                }
            }
            if (amount == input.getStackSize()) return null;
            IAEFluidStack rest = input.copy();
            rest.setStackSize(input.getStackSize() - amount);
            return rest;
        }

        @Override
        public IAEFluidStack extractItems(IAEFluidStack input, Actionable mode, BaseActionSource source) {
            if (input == null || !attached() || input.getStackSize() <= 0) return null;
            FluidKey key = FluidKey.of(input.getFluidStack());
            long amount = Math.min(input.getStackSize(), pool.getFluidAmountPool(key));
            if (mode == Actionable.MODULATE) {
                providerWrites++;
                try {
                    amount = pool.poolExtractFluid(key, amount);
                } finally {
                    providerWrites--;
                }
            }
            if (amount <= 0) return null;
            IAEFluidStack result = input.copy();
            result.setStackSize(amount);
            return result;
        }

        @Override
        public IAEFluidStack getAvailableItem(IAEFluidStack request, int iteration) {
            if (request == null || !attached()) return null;
            FluidKey key = FluidKey.of(request.getFluidStack());
            long amount = Math.min(REPORT_CAP, pool.getFluidAmountPool(key));
            if (amount <= 0) return null;
            IAEFluidStack result = request.copy();
            result.setStackSize(amount);
            return result;
        }

        @Override
        public IItemList<IAEFluidStack> getAvailableItems(IItemList<IAEFluidStack> out, int iteration) {
            if (attached()) for (Map.Entry<FluidKey, Long> row : pool.snapshotPoolFluids()) {
                IAEFluidStack value = AEFluidStack.create(
                    row.getKey()
                        .prototype());
                value.setStackSize(Math.min(REPORT_CAP, row.getValue()));
                out.add(value);
            }
            return out;
        }
    }
}
