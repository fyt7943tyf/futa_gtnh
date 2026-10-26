package com.futa_gtnh.ae2;

import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

import appeng.api.storage.ISaveProvider;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.util.item.AEFluidStackType;

/** 流体通道的共享背包元件 handler（单位 mB，与共享背包一致）。语义见基类注释。 */
final class SharedFluidCellInventory extends AbstractSharedCellInventory<IAEFluidStack, FluidKey> {

    SharedFluidCellInventory(ItemStack cellItem, ISaveProvider host) {
        super(cellItem, host);
    }

    @Override
    protected IAEStackType<IAEFluidStack> aeStackType() {
        return AEFluidStackType.FLUID_STACK_TYPE;
    }

    @Override
    protected FluidKey keyOf(IAEFluidStack stack) {
        if (stack == null) return null;
        return FluidKey.of(stack.getFluidStack());
    }

    @Override
    protected long storedAmount(FluidKey key) {
        return SharedStorageManager.getStorage()
            .getFluidAmount(key);
    }

    @Override
    protected long spaceLeft(FluidKey key) {
        SharedStorage storage = SharedStorageManager.getStorage();
        if (!Config.ae2CellObeyLimits) {
            return storage.fluidManualSpaceLeft(key);
        }
        return storage.fluidSpaceLeft(key);
    }

    @Override
    protected long insertStorage(FluidKey key, long amount) {
        SharedStorage storage = SharedStorageManager.getStorage();
        return Config.ae2CellObeyLimits ? storage.insertFluid(key, amount) : storage.insertFluidManual(key, amount);
    }

    @Override
    protected long extractStorage(FluidKey key, long amount) {
        return SharedStorageManager.getStorage()
            .extractFluid(key, amount);
    }

    @Override
    protected void broadcastChange(FluidKey key) {
        SharedStorageManager.broadcastFluidChange(key);
    }

    @Override
    protected void noteOwnWrite(FluidKey key) {
        AeCellBridge.noteOwnFluidWrite(key);
    }

    @Override
    public IItemList<IAEFluidStack> getAvailableItems(IItemList<IAEFluidStack> out, int iteration) {
        if (!AeCellBridge.serveContent(this)) return out;
        if (com.futa_gtnh.me.PublicMeNetwork.isRunning()) {
            appeng.api.storage.IMEMonitor<IAEFluidStack> monitor = com.futa_gtnh.me.PublicMeNetwork
                .monitor(aeStackType());
            return monitor == null ? out : monitor.getAvailableItems(out, iteration);
        }
        AeCellBridge.addFluidMirror(out);
        return out;
    }
}
