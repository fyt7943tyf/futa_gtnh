package com.futa_gtnh.tinkers;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

/**
 * 匠魂自动补料用到的几个共用小工具：从「玩家背包 + 共享存储」凑料，以及
 * 在共享存储里挑一个符合条件的物品。
 *
 * <p>
 * <b>在存储里挑候选是要花钱的</b>：得把每个条目的键重建成物品栈才能问匠魂
 * 「这个能不能用」。所以：
 * <ul>
 * <li>先用 {@link KeyFilter} 做一步廉价过滤（只看 item/meta，不建栈）；</li>
 * <li>结果按「存储版本号 + 查询特征」缓存，存储没变就不再扫（同一次合成里会被问很多遍）；</li>
 * <li>取料失败（正好被别的玩家拿光了）时把缓存丢掉，下次重新挑。</li>
 * </ul>
 */
final class FillUtil {

    /** 廉价过滤：只看键，不建物品栈。 */
    interface KeyFilter {

        boolean accept(ItemKey key);
    }

    /** 评分：返回负数表示「这个不能用」，越大越优先；同分时取共享存储里存量多的。 */
    interface Scorer {

        int score(ItemStack prototype);
    }

    private static final class Cached {

        final int revision;
        final ItemKey key;

        Cached(int revision, ItemKey key) {
            this.revision = revision;
            this.key = key;
        }
    }

    private static final Map<Object, Cached> CACHE = new HashMap<>();

    private FillUtil() {}

    static ItemStack at(IInventory inv, int slot) {
        if (inv == null || slot < 0 || slot >= inv.getSizeInventory()) return null;
        return inv.getStackInSlot(slot);
    }

    /**
     * 在共享存储里挑一个物品键。
     *
     * @param cacheKey 查询特征（例如「部件加工台 + 这张图纸」）；同一个特征在存储没变时直接复用上次结果
     * @param refresh  true 表示强制重新挑（上次挑的那个已经取不出来了）
     */
    static ItemKey pick(SharedStorage storage, Object cacheKey, KeyFilter filter, Scorer scorer, boolean refresh) {
        int revision = storage.getRevision();
        if (!refresh && cacheKey != null) {
            Cached cached = CACHE.get(cacheKey);
            if (cached != null && cached.revision == revision) return cached.key;
        }

        ItemKey best = null;
        int bestScore = -1;
        long bestCount = -1L;

        for (Map.Entry<ItemKey, Long> entry : storage.snapshotItems()) {
            Long amount = entry.getValue();
            if (amount == null || amount <= 0L) continue;

            ItemKey key = entry.getKey();
            if (key == null) continue;
            if (filter != null && !filter.accept(key)) continue;

            ItemStack prototype;
            try {
                prototype = key.prototype();
            } catch (Throwable t) {
                continue;
            }
            if (prototype == null) continue;

            int score = scorer.score(prototype);
            if (score < 0) continue;

            if (score > bestScore || (score == bestScore && amount > bestCount)) {
                best = key;
                bestScore = score;
                bestCount = amount;
            }
        }

        if (cacheKey != null) {
            if (best == null) CACHE.remove(cacheKey);
            else CACHE.put(cacheKey, new Cached(revision, best));
        }
        return best;
    }

    /** 上一次挑的取不出来时调用：让下次重新挑。 */
    static void invalidate(Object cacheKey) {
        if (cacheKey != null) CACHE.remove(cacheKey);
    }

    /**
     * 凑出 {@code amount} 个 {@code key} 的东西：<b>玩家背包优先，其次共享存储</b>。
     *
     * <p>
     * 顺序和 {@code CraftFiller} 一致：背包里的先清掉更符合「先用自己的」直觉，
     * 也少一次全服增量广播。取的量走 {@code SharedStorage.extractItem}（守恒），
     * 拿到的物品栈由 {@link ItemKey#prototype} 从键重建 —— 和存进去的那一份逐字节一致。
     *
     * @return 拿到的东西（可能少于 amount），一个都没拿到时返回 null
     */
    static ItemStack take(EntityPlayerMP player, SharedStorage storage, DeltaRecorder recorder, ItemKey key,
        int amount) {
        if (key == null || amount <= 0) return null;

        int fromInventory = takeFromInventory(player, key, amount);
        long fromStorage = 0L;
        if (fromInventory < amount) {
            fromStorage = storage.extractItem(key, amount - fromInventory);
            if (fromStorage > 0L) recorder.item(key);
        }

        long total = fromInventory + fromStorage;
        if (total <= 0L) return null;
        return key.prototype((int) Math.min(total, Integer.MAX_VALUE));
    }

    private static int takeFromInventory(EntityPlayerMP player, ItemKey key, int amount) {
        ItemStack[] main = player.inventory.mainInventory;
        int taken = 0;

        for (int i = 0; i < main.length && taken < amount; i++) {
            ItemStack slot = main[i];
            if (slot == null || slot.getItem() == null) continue;
            if (!key.equals(ItemKey.of(slot))) continue;

            int take = Math.min(amount - taken, slot.stackSize);
            slot.stackSize -= take;
            if (slot.stackSize <= 0) main[i] = null;
            taken += take;
        }

        if (taken > 0) player.inventory.markDirty();
        return taken;
    }
}
