package com.futa_gtnh.ae2;

import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

import appeng.api.storage.ISaveProvider;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;
import appeng.util.item.AEItemStackType;

/** 物品通道的共享背包元件 handler。通道差异以外的语义见基类注释。 */
final class SharedItemCellInventory extends AbstractSharedCellInventory<IAEItemStack, ItemKey> {

    SharedItemCellInventory(ItemStack cellItem, ISaveProvider host) {
        super(cellItem, host);
    }

    @Override
    protected IAEStackType<IAEItemStack> aeStackType() {
        return AEItemStackType.ITEM_STACK_TYPE;
    }

    @Override
    protected ItemKey keyOf(IAEItemStack stack) {
        if (stack == null) return null;
        return ItemKey.of(stack.getItemStack());
    }

    @Override
    protected long storedAmount(ItemKey key) {
        return SharedStorageManager.getStorage()
            .getItemAmount(key);
    }

    @Override
    protected long spaceLeft(ItemKey key) {
        SharedStorage storage = SharedStorageManager.getStorage();
        if (!Config.ae2CellObeyLimits) {
            // 手动路径不设上限：剩余容量 = 全局上限 - 现有（现有 ≥ 0，不会溢出）
            return Math.max(0L, SharedStorage.MAX_AMOUNT - storage.getItemAmount(key));
        }
        return storage.itemSpaceLeft(key);
    }

    @Override
    protected long insertStorage(ItemKey key, long amount) {
        SharedStorage storage = SharedStorageManager.getStorage();
        return Config.ae2CellObeyLimits ? storage.insertItem(key, amount) : storage.insertItemManual(key, amount);
    }

    @Override
    protected long extractStorage(ItemKey key, long amount) {
        return SharedStorageManager.getStorage()
            .extractItem(key, amount);
    }

    @Override
    protected void broadcastChange(ItemKey key) {
        SharedStorageManager.broadcastItemChange(key);
    }

    @Override
    public IItemList<IAEItemStack> getAvailableItems(IItemList<IAEItemStack> out, int iteration) {
        if (AeCellBridge.serveContent(this)) AeCellBridge.addItemMirror(out);
        return out;
    }
}
