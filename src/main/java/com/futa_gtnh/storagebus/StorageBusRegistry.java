package com.futa_gtnh.storagebus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.tileentity.TileEntity;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 全服存储总线的注册表与聚合库存。
 *
 * <p>
 * 它把「已加载的存储总线」聚合成共享存储的一部分：物品按可读/全部两张表分别累计，
 * 写入时按优先级从高到低尝试，抽取时按优先级从低到高尝试。共享存储池本身按
 * 优先级 0 处理（见 {@code SharedStorage} 的读写路径）。
 *
 * <p>
 * <b>为什么要有两张表</b>：只写总线的库存不应出现在共享终端里，也不应被抽出；
 * 但它确实占着实物，算「存量上限」时必须计入。所以可读表供界面/AE/抽取使用，
 * 全部表供上限计算使用。
 *
 * <p>
 * <b>变更广播是攒到 tick 末的</b>：一次 AE 合成可能连续触发几十次总线读写，
 * 每个都发一个增量包会放大网络开销。这里只把变化的键塞进并发集合，
 * 由 {@link #onServerTickEnd()} 统一交给 {@link SharedStorageManager} 广播。
 * 集合操作不碰存储锁，也就不会和 AE 的桥锁形成反向等待。
 */
public final class StorageBusRegistry {

    private StorageBusRegistry() {}

    private static final Object LOCK = new Object();

    private static final List<StorageBusLink> LINKS = new ArrayList<>();
    /** Immutable accounting snapshots, separate from each link's live mirror. */
    private static final Map<StorageBusLink, Contribution> CONTRIBUTIONS = new IdentityHashMap<>();
    /** Multiple faces on the same tile expose one physical inventory, not separate copies. */
    private static final Map<TileEntity, TargetStock> TARGETS = new IdentityHashMap<>();

    private static final class Contribution {

        final TileEntity target;
        final boolean readable;
        final Map<ItemKey, Long> items;
        final Map<FluidKey, Long> fluids;

        Contribution(StorageBusLink link) {
            target = link.targetTile();
            readable = link.isReadable();
            items = toItemMap(link.itemSnapshot());
            fluids = toFluidMap(link.fluidSnapshot());
        }
    }

    private static final class TargetStock {

        final Map<StorageBusLink, Contribution> links = new LinkedHashMap<>();
        Map<ItemKey, Long> items = new LinkedHashMap<>();
        Map<FluidKey, Long> fluids = new LinkedHashMap<>();
        Map<ItemKey, Long> readableItems = new LinkedHashMap<>();
        Map<FluidKey, Long> readableFluids = new LinkedHashMap<>();
    }

    /** 只包含「可读」总线贡献的聚合表，供终端 / AE / 抽取使用。 */
    private static final Map<ItemKey, Long> READABLE_ITEMS = new LinkedHashMap<>();
    private static final Map<FluidKey, Long> READABLE_FLUIDS = new LinkedHashMap<>();

    /** 包含全部总线贡献的聚合表，供存量上限计算使用。 */
    private static final Map<ItemKey, Long> ALL_ITEMS = new LinkedHashMap<>();
    private static final Map<FluidKey, Long> ALL_FLUIDS = new LinkedHashMap<>();

    private static final Set<ItemKey> PENDING_ITEMS = ConcurrentHashMap.newKeySet();
    private static final Set<FluidKey> PENDING_FLUIDS = ConcurrentHashMap.newKeySet();

    /**
     * 聚合库存的版本号。任何总线贡献变化都加一，共享存储把它并进自己的
     * {@code getRevision()}，终端/AE 的全量快照缓存就会立刻失效。
     * 只是读，不需要把整个注册表锁住。
     */
    private static volatile int revision;
    private static volatile int itemRevision;
    private static volatile int fluidRevision;

    /**
     * 有没有已登记的总线。没有时共享存储的所有读写都只做一次 volatile 读，
     * 不拿注册表锁 —— 绝大多数存档根本没有总线，不能为它拖慢 AE 的单类型查询热路径。
     */
    private static volatile boolean enabled;

    public static int getRevision() {
        return revision;
    }

    public static int getItemRevision() {
        return itemRevision;
    }

    public static int getFluidRevision() {
        return fluidRevision;
    }

    /**
     * 当前线程能不能碰世界里的容器。
     *
     * <p>
     * 共享存储本身允许 GT 的机器从工作线程写进来；总线要访问世界里的方块实体，
     * 只能在服务端主线程做。工作线程的调用退回「只用共享存储池」，
     * 不去碰 World / IInventory —— 宁可少看见一点总线库存，也不能在别的线程动世界。
     * 没有服务端实例时（无头检查 / 单测）视为可以，方便测试。
     */
    public static boolean canTouchWorld() {
        return SharedStorageManager.isServerThread();
    }

    private static void bumpRevision() {
        revision++;
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** 方块实体第一次 tick 时登记。重复登记无副作用。 */
    public static void register(StorageBusLink link) {
        if (link == null) return;

        synchronized (LOCK) {
            if (LINKS.contains(link)) return;
        }

        // 扫描必须在注册表锁外做：目标容器是别的模组写的，可能很慢，
        // 也可能反过来触发一次共享存储查询。
        link.refresh();
        Contribution fresh = new Contribution(link);

        synchronized (LOCK) {
            if (LINKS.contains(link)) return;
            LINKS.add(link);
            enabled = true;
            replaceContribution(link, fresh);
        }
    }

    /** 方块实体失效 / 区块卸载时注销。 */
    public static void unregister(StorageBusLink link) {
        if (link == null) return;

        synchronized (LOCK) {
            if (!LINKS.remove(link)) return;
            replaceContribution(link, null);
            enabled = !LINKS.isEmpty();
        }
    }

    /** 目标面 / 访问模式 / 筛选改变后重建这一条总线的贡献。 */
    public static void refresh(StorageBusLink link) {
        if (link == null) return;

        boolean registered;
        synchronized (LOCK) {
            registered = LINKS.contains(link);
        }
        if (!registered) {
            // 还没登记（比如刚放下的方块），直接补登记
            register(link);
            return;
        }

        link.refresh();
        Contribution fresh = new Contribution(link);

        synchronized (LOCK) {
            if (!LINKS.contains(link)) return;
            replaceContribution(link, fresh);
        }
    }

    /** 打开终端前刷新所有已加载总线，避免把最多一个刷新周期的旧数字发出去。 */
    public static void refreshAll() {
        if (!enabled) return;
        List<StorageBusLink> links;
        synchronized (LOCK) {
            links = new ArrayList<>(LINKS);
        }
        for (StorageBusLink link : links) {
            try {
                refresh(link);
            } catch (Throwable ignored) {
                // 单个容器异常只影响它自己这一轮
            }
        }
    }

    /** @return {@code tile} 是不是某条总线的目标容器 */
    public static boolean isTargetTile(net.minecraft.tileentity.TileEntity tile) {
        if (tile == null || !enabled) return false;
        List<StorageBusLink> links;
        synchronized (LOCK) {
            links = new ArrayList<>(LINKS);
        }
        for (StorageBusLink link : links) {
            if (link.targetTile() == tile) return true;
        }
        return false;
    }

    /** O(1) target lookup and only its own faces; telemetry must not scan every registered bus per transfer. */
    public static boolean accountsFor(TileEntity tile, Object key) {
        if (tile == null || !enabled) return false;
        synchronized (LOCK) {
            TargetStock target = TARGETS.get(tile);
            if (target == null) return false;
            for (StorageBusLink link : target.links.keySet()) {
                if (key instanceof ItemKey && link.getConfig()
                    .allowsItem((ItemKey) key)) return true;
                if (key instanceof FluidKey && link.getConfig()
                    .allowsFluid((FluidKey) key)) return true;
            }
            return false;
        }
    }

    /**
     * 目标容器里的 {@code key} 是不是已经被某条总线计入聚合库存（含只写总线）。
     * 主动 IO 从「挂在总线上的容器」抽东西进池子时，只有确实计入过的那部分
     * 才可以用不受上限的入口：总量本来就在账上，只是从总线挪到池子。
     * 被筛选挡在外面、根本没计入的条目，搬进池子仍然要守上限。
     */
    public static boolean targetCountsItem(net.minecraft.tileentity.TileEntity tile, ItemKey key) {
        if (tile == null || key == null || !enabled) return false;
        List<StorageBusLink> links;
        synchronized (LOCK) {
            links = new ArrayList<>(LINKS);
        }
        for (StorageBusLink link : links) {
            if (link.targetTile() == tile && link.cachedItemAmount(key) > 0L) return true;
        }
        return false;
    }

    public static boolean targetCountsFluid(net.minecraft.tileentity.TileEntity tile, FluidKey key) {
        if (tile == null || key == null || !enabled) return false;
        List<StorageBusLink> links;
        synchronized (LOCK) {
            links = new ArrayList<>(LINKS);
        }
        for (StorageBusLink link : links) {
            if (link.targetTile() == tile && link.cachedFluidAmount(key) > 0L) return true;
        }
        return false;
    }

    /**
     * 只刷新「目标正好是 {@code tile}」的总线。
     *
     * <p>
     * 主动 IO 把东西从某个容器抽进池子 / 推进容器后，如果那个容器本身挂着总线，
     * 它的镜像需要立刻跟上：否则「池子 + 总线」会短暂重算一遍，存量上限也可能
     * 因为旧镜像而误判。只扫命中的那几条，比全量刷新便宜。
     */
    public static void refreshTargeting(net.minecraft.tileentity.TileEntity tile) {
        if (tile == null || !enabled) return;
        List<StorageBusLink> links;
        synchronized (LOCK) {
            links = new ArrayList<>(LINKS);
        }
        for (StorageBusLink link : links) {
            try {
                if (link.targetTile() == tile) refresh(link);
            } catch (Throwable ignored) {
                // 单个容器异常只影响它自己这一轮
            }
        }
    }

    /** 停服 / 换存档时清空。总线的方块实体会在新区块加载时重新登记。 */
    public static void clear() {
        synchronized (LOCK) {
            LINKS.clear();
            CONTRIBUTIONS.clear();
            TARGETS.clear();
            READABLE_ITEMS.clear();
            READABLE_FLUIDS.clear();
            ALL_ITEMS.clear();
            ALL_FLUIDS.clear();
            PENDING_ITEMS.clear();
            PENDING_FLUIDS.clear();
            enabled = false;
            itemRevision++;
            fluidRevision++;
            bumpRevision();
        }
    }

    // ==================================================================
    // 读取聚合
    // ==================================================================

    public static long getReadableItemAmount(ItemKey key) {
        if (key == null || !enabled) return 0L;
        synchronized (LOCK) {
            Long amount = READABLE_ITEMS.get(key);
            return amount == null ? 0L : amount.longValue();
        }
    }

    public static long getReadableFluidAmount(FluidKey key) {
        if (key == null || !enabled) return 0L;
        synchronized (LOCK) {
            Long amount = READABLE_FLUIDS.get(key);
            return amount == null ? 0L : amount.longValue();
        }
    }

    public static long getAllItemAmount(ItemKey key) {
        if (key == null || !enabled) return 0L;
        synchronized (LOCK) {
            Long amount = ALL_ITEMS.get(key);
            return amount == null ? 0L : amount.longValue();
        }
    }

    public static long getAllFluidAmount(FluidKey key) {
        if (key == null || !enabled) return 0L;
        synchronized (LOCK) {
            Long amount = ALL_FLUIDS.get(key);
            return amount == null ? 0L : amount.longValue();
        }
    }

    /** @return 可读物品条目的独立副本，供终端全量快照合并 */
    public static Map<ItemKey, Long> snapshotReadableItems() {
        if (!enabled) return Collections.emptyMap();
        synchronized (LOCK) {
            return new LinkedHashMap<>(READABLE_ITEMS);
        }
    }

    public static Map<FluidKey, Long> snapshotReadableFluids() {
        if (!enabled) return Collections.emptyMap();
        synchronized (LOCK) {
            return new LinkedHashMap<>(READABLE_FLUIDS);
        }
    }

    /** @return 所有已登记总线的可读物品总量（/futashared info 用） */
    public static long readableItemTotal() {
        if (!enabled) return 0L;
        synchronized (LOCK) {
            return sum(READABLE_ITEMS.values());
        }
    }

    public static long readableFluidTotal() {
        if (!enabled) return 0L;
        synchronized (LOCK) {
            return sum(READABLE_FLUIDS.values());
        }
    }

    public static boolean hasAnyLink() {
        return enabled;
    }

    // ==================================================================
    // 写入 / 抽取
    // ==================================================================

    /**
     * 按优先级从高到低尝试写入。
     *
     * @param atOrAbovePool true = 只写优先级 ≥ 0 的总线（共享存储池之前），
     *                      false = 只写优先级 &lt; 0 的总线（池子之后）
     * @return 总线实际接受的总量
     */
    public static long insertItem(ItemKey key, long amount, boolean atOrAbovePool) {
        if (key == null || amount <= 0L || !enabled) return 0L;
        List<StorageBusLink> links = writableLinks(atOrAbovePool);
        long remaining = amount;
        for (StorageBusLink link : links) {
            if (remaining <= 0L) break;
            try {
                long accepted = link.insertItem(key, remaining);
                if (accepted <= 0L) continue;
                remaining -= accepted;
                refreshTargeting(link.targetTile());
            } catch (Throwable ignored) {
                // 单个容器异常：继续尝试下一条总线 / 最后落进共享存储池
            }
        }
        return amount - remaining;
    }

    public static long insertFluid(FluidKey key, long amount, boolean atOrAbovePool) {
        if (key == null || amount <= 0L || !enabled) return 0L;
        List<StorageBusLink> links = writableLinks(atOrAbovePool);
        long remaining = amount;
        for (StorageBusLink link : links) {
            if (remaining <= 0L) break;
            try {
                long accepted = link.insertFluid(key, remaining);
                if (accepted <= 0L) continue;
                remaining -= accepted;
                refreshTargeting(link.targetTile());
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return amount - remaining;
    }

    /**
     * 按优先级从低到高尝试抽取。
     *
     * @param belowPool true = 只抽优先级 &lt; 0 的总线（共享存储池之前），
     *                  false = 只抽优先级 ≥ 0 的总线（共享存储池之后）
     * @return 总线实际给出的总量
     */
    public static long extractItem(ItemKey key, long amount, boolean belowPool) {
        return extractItem(key, amount, belowPool, null);
    }

    /**
     * @param excluded 不从这个容器里抽（主动输出时排除目标自己，避免把目标刚收到的东西又抽回来）
     */
    public static long extractItem(ItemKey key, long amount, boolean belowPool, TileEntity excluded) {
        if (key == null || amount <= 0L || !enabled) return 0L;
        List<StorageBusLink> links = readableLinks(belowPool);
        long remaining = amount;
        for (StorageBusLink link : links) {
            if (remaining <= 0L) break;
            if (excluded != null && link.targetTile() == excluded) continue;
            try {
                long taken = link.extractItem(key, remaining);
                if (taken <= 0L) continue;
                remaining -= taken;
                refreshTargeting(link.targetTile());
            } catch (Throwable ignored) {
                // 单个容器异常：继续尝试下一条总线 / 最后从共享存储池取
            }
        }
        return amount - remaining;
    }

    public static long extractFluid(FluidKey key, long amount, boolean belowPool) {
        return extractFluid(key, amount, belowPool, null);
    }

    public static long extractFluid(FluidKey key, long amount, boolean belowPool, TileEntity excluded) {
        if (key == null || amount <= 0L || !enabled) return 0L;
        List<StorageBusLink> links = readableLinks(belowPool);
        long remaining = amount;
        for (StorageBusLink link : links) {
            if (remaining <= 0L) break;
            if (excluded != null && link.targetTile() == excluded) continue;
            try {
                long taken = link.extractFluid(key, remaining);
                if (taken <= 0L) continue;
                remaining -= taken;
                refreshTargeting(link.targetTile());
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return amount - remaining;
    }

    // ==================================================================
    // 变更广播（tick 末统一冲洗）
    // ==================================================================

    /** 每 tick 末由 {@code ModEventHandler} 调用：把这一 tick 的总线变化交给广播中枢。 */
    public static void onServerTickEnd() {
        if (PENDING_ITEMS.isEmpty() && PENDING_FLUIDS.isEmpty()) return;
        if (!SharedStorageManager.isLoaded()) {
            PENDING_ITEMS.clear();
            PENDING_FLUIDS.clear();
            return;
        }

        Set<ItemKey> items = drainItems();
        Set<FluidKey> fluids = drainFluids();
        if (items.isEmpty() && fluids.isEmpty()) return;
        try {
            SharedStorageManager.onExternalStorageChanged(items, fluids);
        } catch (Throwable ignored) {
            // 广播失败不能影响服务端 tick；下一轮全量快照会兜底
        }
    }

    private static Set<ItemKey> drainItems() {
        Set<ItemKey> result = new LinkedHashSet<>(PENDING_ITEMS);
        PENDING_ITEMS.removeAll(result);
        return result;
    }

    private static Set<FluidKey> drainFluids() {
        Set<FluidKey> result = new LinkedHashSet<>(PENDING_FLUIDS);
        PENDING_FLUIDS.removeAll(result);
        return result;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private static List<StorageBusLink> writableLinks(boolean atOrAbovePool) {
        List<StorageBusLink> links;
        synchronized (LOCK) {
            links = new ArrayList<>(LINKS);
        }
        links.removeIf(link -> {
            if (!link.isWritable()) return true;
            int priority = link.getConfig()
                .getPriority();
            return atOrAbovePool ? priority < 0 : priority >= 0;
        });
        // 优先级高的先填；同优先级保持登记顺序，行为稳定
        links.sort(
            Comparator.comparingInt(
                (StorageBusLink link) -> -link.getConfig()
                    .getPriority()));
        return links;
    }

    private static List<StorageBusLink> readableLinks(boolean belowPool) {
        List<StorageBusLink> links;
        synchronized (LOCK) {
            links = new ArrayList<>(LINKS);
        }
        links.removeIf(link -> {
            if (!link.isReadable()) return true;
            int priority = link.getConfig()
                .getPriority();
            return belowPool ? priority >= 0 : priority < 0;
        });
        // 抽取从低优先级开始；同优先级保持登记顺序
        links.sort(
            Comparator.comparingInt(
                link -> link.getConfig()
                    .getPriority()));
        return links;
    }

    /** Called under LOCK; no world/container callbacks are made while updating the ledger. */
    private static void replaceContribution(StorageBusLink link, Contribution fresh) {
        Contribution old = CONTRIBUTIONS.get(link);
        if (old != null && fresh != null
            && old.target == fresh.target
            && old.readable == fresh.readable
            && old.items.equals(fresh.items)
            && old.fluids.equals(fresh.fluids)) return;
        if (fresh == null) CONTRIBUTIONS.remove(link);
        else CONTRIBUTIONS.put(link, fresh);
        TileEntity oldTarget = old == null ? null : old.target;
        TileEntity newTarget = fresh == null ? null : fresh.target;
        if (oldTarget != null && oldTarget != newTarget) TARGETS.get(oldTarget).links.remove(link);
        if (newTarget != null) TARGETS.computeIfAbsent(newTarget, ignored -> new TargetStock()).links.put(link, fresh);
        if (oldTarget != null) rebuildTarget(oldTarget);
        if (newTarget != null && newTarget != oldTarget) rebuildTarget(newTarget);
    }

    private static void rebuildTarget(TileEntity target) {
        TargetStock stock = TARGETS.get(target);
        Map<ItemKey, Long> items = new LinkedHashMap<>();
        Map<FluidKey, Long> fluids = new LinkedHashMap<>();
        Map<ItemKey, Long> readableItems = new LinkedHashMap<>();
        Map<FluidKey, Long> readableFluids = new LinkedHashMap<>();
        for (Contribution contribution : stock.links.values()) {
            mergeMaximum(items, contribution.items);
            mergeMaximum(fluids, contribution.fluids);
            if (contribution.readable) {
                mergeMaximum(readableItems, contribution.items);
                mergeMaximum(readableFluids, contribution.fluids);
            }
        }
        boolean changed = updateAggregate(ALL_ITEMS, stock.items, items, null);
        changed |= updateAggregate(ALL_FLUIDS, stock.fluids, fluids, null);
        boolean itemsChanged = updateAggregate(READABLE_ITEMS, stock.readableItems, readableItems, PENDING_ITEMS);
        boolean fluidsChanged = updateAggregate(READABLE_FLUIDS, stock.readableFluids, readableFluids, PENDING_FLUIDS);
        if (itemsChanged) itemRevision++;
        if (fluidsChanged) fluidRevision++;
        changed |= itemsChanged || fluidsChanged;
        stock.items = items;
        stock.fluids = fluids;
        stock.readableItems = readableItems;
        stock.readableFluids = readableFluids;
        if (stock.links.isEmpty()) TARGETS.remove(target);
        if (changed) bumpRevision();
    }

    private static <K> void mergeMaximum(Map<K, Long> target, Map<K, Long> source) {
        // Filters may expose different keys, but aliases on one tile still refer to the same stock.
        for (Map.Entry<K, Long> entry : source.entrySet()) target.merge(entry.getKey(), entry.getValue(), Math::max);
    }

    private static <K> boolean updateAggregate(Map<K, Long> aggregate, Map<K, Long> before, Map<K, Long> after,
        Set<K> pending) {
        Set<K> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        boolean changed = false;
        for (K key : keys) {
            long delta = after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
            if (delta == 0L) continue;
            long next = saturatingAdd(aggregate.getOrDefault(key, 0L), delta);
            if (next <= 0L) aggregate.remove(key);
            else aggregate.put(key, next);
            if (pending != null) pending.add(key);
            changed = true;
        }
        return changed;
    }

    private static Map<ItemKey, Long> toItemMap(List<Map.Entry<ItemKey, Long>> entries) {
        Map<ItemKey, Long> result = new LinkedHashMap<>();
        for (Map.Entry<ItemKey, Long> entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static Map<FluidKey, Long> toFluidMap(List<Map.Entry<FluidKey, Long>> entries) {
        Map<FluidKey, Long> result = new LinkedHashMap<>();
        for (Map.Entry<FluidKey, Long> entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static long sum(java.util.Collection<Long> values) {
        long total = 0L;
        for (Long value : values) {
            if (value == null) continue;
            total = saturatingAdd(total, value.longValue());
        }
        return total;
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        return ((a ^ sum) & (b ^ sum)) < 0L ? Long.MAX_VALUE : sum;
    }

    /** 仅调试/检查用：当前登记条数。 */
    public static int size() {
        synchronized (LOCK) {
            return LINKS.size();
        }
    }

    /** 仅检查用：清掉待广播集合而不动注册表。 */
    static void clearPendingForTests() {
        PENDING_ITEMS.clear();
        PENDING_FLUIDS.clear();
    }

    /** 只读的已登记总线列表（检查用）。 */
    public static List<StorageBusLink> links() {
        synchronized (LOCK) {
            return Collections.unmodifiableList(new ArrayList<>(LINKS));
        }
    }
}
