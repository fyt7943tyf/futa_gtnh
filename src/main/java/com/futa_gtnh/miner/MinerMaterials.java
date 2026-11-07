package com.futa_gtnh.miner;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.reactor.ReactorAeTerminal;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 矿机部署的三级取料：玩家背包 → 共享背包 → 随身 ME 终端网络。
 *
 * <p>
 * 顺序与口径照抄反应堆 {@code ShellBuilder}：不预扣不预占，摆一个扣一个，池子是
 * 局部状态，中途断了账也是平的；没摆出去的余料统一退回共享背包。部署的材料全是
 * 无 NBT 的全新件（外壳/框架方块、仓体物品），所以全走精确 {@link ItemKey}。
 */
public final class MinerMaterials {

    private MinerMaterials() {}

    /**
     * 按需求表逐项取料，实物记进 pool。
     *
     * @return 总缺口数（取不到的件数）
     */
    public static int satisfy(EntityPlayerMP player, Map<ItemKey, Integer> demands, Map<ItemKey, Integer> pool) {
        SharedStorage storage = SharedStorageManager.getStorage();
        // 随身 ME 终端找一次：没带终端/超范围时是 null，第三级安静跳过
        ReactorAeTerminal.TerminalHandle ae = ReactorAeTerminal.find(player);

        int shortage = 0;
        for (Map.Entry<ItemKey, Integer> demand : demands.entrySet()) {
            ItemKey key = demand.getKey();
            int need = demand.getValue();
            int taken = takeFromPlayer(player, pool, key, need);
            if (taken < need) taken += takeFromStorage(storage, pool, key, need - taken);
            if (taken < need && ae != null) taken += takeFromAe(ae, player, pool, key, need - taken);
            if (taken < need) shortage += need - taken;
        }
        return shortage;
    }

    public static int poolCount(Map<ItemKey, Integer> pool) {
        int total = 0;
        for (Integer count : pool.values()) {
            if (count != null) total += count;
        }
        return total;
    }

    /** 优先扣「首选键」的账，再扣池里其它变体（同物品不同来源的容错）。 */
    public static void consumeOne(Map<ItemKey, Integer> pool, ItemKey preferred) {
        if (preferred != null && pool.getOrDefault(preferred, 0) > 0) {
            pool.merge(preferred, -1, Integer::sum);
            return;
        }
        for (Map.Entry<ItemKey, Integer> entry : pool.entrySet()) {
            if (entry.getValue() != null && entry.getValue() > 0) {
                entry.setValue(entry.getValue() - 1);
                return;
            }
        }
    }

    /** 没摆出去的余料退回共享背包（与部署回收同一套路）。 */
    public static void refund(Map<ItemKey, Integer> pool) {
        SharedStorage storage = SharedStorageManager.getStorage();
        for (Map.Entry<ItemKey, Integer> entry : new LinkedHashMap<>(pool).entrySet()) {
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (count <= 0) continue;
            storage.insertItemManual(entry.getKey(), count);
            SharedStorageManager.broadcastItemChange(entry.getKey());
        }
        pool.clear();
    }

    private static int takeFromPlayer(EntityPlayerMP player, Map<ItemKey, Integer> pool, ItemKey key, int amount) {
        if (amount <= 0) return 0;
        ItemStack[] main = player.inventory.mainInventory;
        int taken = 0;
        for (int i = 0; i < main.length && taken < amount; i++) {
            ItemStack slot = main[i];
            if (slot == null || !key.equals(ItemKey.of(slot))) continue;
            int take = Math.min(amount - taken, slot.stackSize);
            slot.stackSize -= take;
            if (slot.stackSize <= 0) main[i] = null;
            pool.merge(key, take, Integer::sum);
            taken += take;
        }
        if (taken > 0) player.inventory.markDirty();
        return taken;
    }

    private static int takeFromStorage(SharedStorage storage, Map<ItemKey, Integer> pool, ItemKey key, int amount) {
        if (amount <= 0) return 0;
        long have = storage.getItemAmount(key);
        if (have <= 0) return 0;
        long extracted = storage.extractItem(key, Math.min(have, amount));
        if (extracted <= 0) return 0;
        pool.merge(key, (int) extracted, Integer::sum);
        SharedStorageManager.broadcastItemChange(key);
        return (int) extracted;
    }

    private static int takeFromAe(ReactorAeTerminal.TerminalHandle ae, EntityPlayerMP player,
        Map<ItemKey, Integer> pool, ItemKey key, int amount) {
        if (amount <= 0) return 0;
        int extracted = ReactorAeTerminal.extractExact(ae, player, key, amount);
        if (extracted <= 0) return 0;
        pool.merge(key, extracted, Integer::sum);
        return extracted;
    }
}
