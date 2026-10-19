package com.futa_gtnh.storagebus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    /**
     * 每条总线「上一次计入可读表时」的访问模式。
     *
     * <p>
     * 不能现场读 {@code link.isReadable()}：配置改变后它已经是新值了，而聚合表里
     * 还记着旧贡献。刷新/注销必须按这份记录扣旧账，否则只读↔只写切换会把数量
     * 留在可读表里（或者反复扣成 0）。
     */
    private static final Map<StorageBusLink, Boolean> READABLE_FLAGS = new java.util.IdentityHashMap<>();

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

    /**
     * 有没有已登记的总线。没有时共享存储的所有读写都只做一次 volatile 读，
     * 不拿注册表锁 —— 绝大多数存档根本没有总线，不能为它拖慢 AE 的单类型查询热路径。
     */
    private static volatile boolean enabled;

    public static int getRevision() {
        return revision;
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
        List<Map.Entry<ItemKey, Long>> items = link.itemSnapshot();
        List<Map.Entry<FluidKey, Long>> fluids = link.fluidSnapshot();
        boolean readable = link.isReadable();

        synchronized (LOCK) {
            if (LINKS.contains(link)) return;
            LINKS.add(link);
            READABLE_FLAGS.put(link, Boolean.valueOf(readable));
            addContribution(items, fluids, readable);
            enabled = true;
            bumpRevision();
        }

        if (readable) {
            queue(items, fluids);
        }
    }

    /** 方块实体失效 / 区块卸载时注销。 */
    public static void unregister(StorageBusLink link) {
        if (link == null) return;

        List<Map.Entry<ItemKey, Long>> items = link.itemSnapshot();
        List<Map.Entry<FluidKey, Long>> fluids = link.fluidSnapshot();
        boolean readable = false;

        synchronized (LOCK) {
            if (!LINKS.remove(link)) return;
            Boolean flag = READABLE_FLAGS.remove(link);
            readable = flag != null && flag.booleanValue();
            removeContribution(items, fluids, readable);
            enabled = !LINKS.isEmpty();
            bumpRevision();
        }

        if (readable) {
            queue(items, fluids);
        }
    }

    /** 目标面 / 访问模式 / 筛选改变后重建这一条总线的贡献。 */
    public static void refresh(StorageBusLink link) {
        if (link == null) return;

        List<Map.Entry<ItemKey, Long>> oldItems;
        List<Map.Entry<FluidKey, Long>> oldFluids;
        boolean oldReadable;
        boolean registered;
        synchronized (LOCK) {
            registered = LINKS.contains(link);
            if (registered) {
                oldItems = link.itemSnapshot();
                oldFluids = link.fluidSnapshot();
                Boolean flag = READABLE_FLAGS.get(link);
                oldReadable = flag != null && flag.booleanValue();
            } else {
                oldItems = null;
                oldFluids = null;
                oldReadable = false;
            }
        }
        if (!registered) {
            // 还没登记（比如刚放下的方块），直接补登记
            register(link);
            return;
        }

        link.refresh();

        List<Map.Entry<ItemKey, Long>> newItems = link.itemSnapshot();
        List<Map.Entry<FluidKey, Long>> newFluids = link.fluidSnapshot();
        boolean newReadable = link.isReadable();

        // 可读性变了的话，整份旧/新键都要让客户端和 AE 重新对账；否则只发真正变了的
        Set<ItemKey> changedItems = oldReadable != newReadable ? unionItemKeys(oldItems, newItems)
            : diffItemKeys(oldItems, newItems);
        Set<FluidKey> changedFluids = oldReadable != newReadable ? unionFluidKeys(oldFluids, newFluids)
            : diffFluidKeys(oldFluids, newFluids);
        if (changedItems.isEmpty() && changedFluids.isEmpty()) {
            // 数量没变但访问模式可能变了：把标记更新掉，否则下一次写/抽会按旧的可读性记账
            if (oldReadable != newReadable) {
                synchronized (LOCK) {
                    if (LINKS.contains(link)) READABLE_FLAGS.put(link, Boolean.valueOf(newReadable));
                }
            }
            return;
        }

        synchronized (LOCK) {
            if (!LINKS.contains(link)) return;
            removeContribution(oldItems, oldFluids, oldReadable);
            addContribution(newItems, newFluids, newReadable);
            READABLE_FLAGS.put(link, Boolean.valueOf(newReadable));
            bumpRevision();
        }

        // 只有曾经/现在可读的键才需要客户端与 AE 对账；纯只写总线的内部变化不出现在库存里
        if (oldReadable || newReadable) {
            queueItems(changedItems);
            queueFluids(changedFluids);
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
            READABLE_FLAGS.clear();
            READABLE_ITEMS.clear();
            READABLE_FLUIDS.clear();
            ALL_ITEMS.clear();
            ALL_FLUIDS.clear();
            PENDING_ITEMS.clear();
            PENDING_FLUIDS.clear();
            enabled = false;
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
                noteItemChange(link, key, accepted);
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
                noteFluidChange(link, key, accepted);
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
        if (key == null || amount <= 0L || !enabled) return 0L;
        List<StorageBusLink> links = readableLinks(belowPool);
        long remaining = amount;
        for (StorageBusLink link : links) {
            if (remaining <= 0L) break;
            try {
                long taken = link.extractItem(key, remaining);
                if (taken <= 0L) continue;
                remaining -= taken;
                noteItemChange(link, key, -taken);
            } catch (Throwable ignored) {
                // 单个容器异常：继续尝试下一条总线 / 最后从共享存储池取
            }
        }
        return amount - remaining;
    }

    public static long extractFluid(FluidKey key, long amount, boolean belowPool) {
        if (key == null || amount <= 0L || !enabled) return 0L;
        List<StorageBusLink> links = readableLinks(belowPool);
        long remaining = amount;
        for (StorageBusLink link : links) {
            if (remaining <= 0L) break;
            try {
                long taken = link.extractFluid(key, remaining);
                if (taken <= 0L) continue;
                remaining -= taken;
                noteFluidChange(link, key, -taken);
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

    private static void noteItemChange(StorageBusLink link, ItemKey key, long delta) {
        boolean readable;
        synchronized (LOCK) {
            if (!LINKS.contains(link)) return;
            add(ALL_ITEMS, key, delta);
            Boolean flag = READABLE_FLAGS.get(link);
            readable = flag != null && flag.booleanValue();
            if (readable) add(READABLE_ITEMS, key, delta);
            bumpRevision();
        }
        if (readable) PENDING_ITEMS.add(key);
    }

    private static void noteFluidChange(StorageBusLink link, FluidKey key, long delta) {
        boolean readable;
        synchronized (LOCK) {
            if (!LINKS.contains(link)) return;
            add(ALL_FLUIDS, key, delta);
            Boolean flag = READABLE_FLAGS.get(link);
            readable = flag != null && flag.booleanValue();
            if (readable) add(READABLE_FLUIDS, key, delta);
            bumpRevision();
        }
        if (readable) PENDING_FLUIDS.add(key);
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

    private static void addContribution(List<Map.Entry<ItemKey, Long>> items, List<Map.Entry<FluidKey, Long>> fluids,
        boolean readable) {
        for (Map.Entry<ItemKey, Long> entry : items) {
            add(ALL_ITEMS, entry.getKey(), entry.getValue());
            if (readable) add(READABLE_ITEMS, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<FluidKey, Long> entry : fluids) {
            add(ALL_FLUIDS, entry.getKey(), entry.getValue());
            if (readable) add(READABLE_FLUIDS, entry.getKey(), entry.getValue());
        }
    }

    private static void removeContribution(List<Map.Entry<ItemKey, Long>> items, List<Map.Entry<FluidKey, Long>> fluids,
        boolean readable) {
        for (Map.Entry<ItemKey, Long> entry : items) {
            subtract(ALL_ITEMS, entry.getKey(), entry.getValue());
            if (readable) subtract(READABLE_ITEMS, entry.getKey(), entry.getValue());
        }
        for (Map.Entry<FluidKey, Long> entry : fluids) {
            subtract(ALL_FLUIDS, entry.getKey(), entry.getValue());
            if (readable) subtract(READABLE_FLUIDS, entry.getKey(), entry.getValue());
        }
    }

    private static void queue(List<Map.Entry<ItemKey, Long>> items, List<Map.Entry<FluidKey, Long>> fluids) {
        for (Map.Entry<ItemKey, Long> entry : items) PENDING_ITEMS.add(entry.getKey());
        for (Map.Entry<FluidKey, Long> entry : fluids) PENDING_FLUIDS.add(entry.getKey());
    }

    private static void queueItems(Set<ItemKey> keys) {
        if (keys != null && !keys.isEmpty()) PENDING_ITEMS.addAll(keys);
    }

    private static void queueFluids(Set<FluidKey> keys) {
        if (keys != null && !keys.isEmpty()) PENDING_FLUIDS.addAll(keys);
    }

    private static void add(Map<ItemKey, Long> map, ItemKey key, long amount) {
        if (key == null || amount == 0L) return;
        Long old = map.get(key);
        long next = saturatingAdd(old == null ? 0L : old.longValue(), amount);
        if (next <= 0L) {
            map.remove(key);
        } else {
            map.put(key, Long.valueOf(next));
        }
    }

    private static void add(Map<FluidKey, Long> map, FluidKey key, long amount) {
        if (key == null || amount == 0L) return;
        Long old = map.get(key);
        long next = saturatingAdd(old == null ? 0L : old.longValue(), amount);
        if (next <= 0L) {
            map.remove(key);
        } else {
            map.put(key, Long.valueOf(next));
        }
    }

    private static void subtract(Map<ItemKey, Long> map, ItemKey key, long amount) {
        if (key == null || amount <= 0L) return;
        Long old = map.get(key);
        long next = Math.max(0L, (old == null ? 0L : old.longValue()) - amount);
        if (next <= 0L) {
            map.remove(key);
        } else {
            map.put(key, Long.valueOf(next));
        }
    }

    private static void subtract(Map<FluidKey, Long> map, FluidKey key, long amount) {
        if (key == null || amount <= 0L) return;
        Long old = map.get(key);
        long next = Math.max(0L, (old == null ? 0L : old.longValue()) - amount);
        if (next <= 0L) {
            map.remove(key);
        } else {
            map.put(key, Long.valueOf(next));
        }
    }

    private static Set<ItemKey> unionItemKeys(List<Map.Entry<ItemKey, Long>> a, List<Map.Entry<ItemKey, Long>> b) {
        Set<ItemKey> result = new LinkedHashSet<>();
        for (Map.Entry<ItemKey, Long> entry : a) result.add(entry.getKey());
        for (Map.Entry<ItemKey, Long> entry : b) result.add(entry.getKey());
        return result;
    }

    private static Set<FluidKey> unionFluidKeys(List<Map.Entry<FluidKey, Long>> a, List<Map.Entry<FluidKey, Long>> b) {
        Set<FluidKey> result = new LinkedHashSet<>();
        for (Map.Entry<FluidKey, Long> entry : a) result.add(entry.getKey());
        for (Map.Entry<FluidKey, Long> entry : b) result.add(entry.getKey());
        return result;
    }

    private static Set<ItemKey> diffItemKeys(List<Map.Entry<ItemKey, Long>> before,
        List<Map.Entry<ItemKey, Long>> after) {
        Map<ItemKey, Long> old = toItemMap(before);
        Map<ItemKey, Long> fresh = toItemMap(after);
        Set<ItemKey> changed = new LinkedHashSet<>();
        for (Map.Entry<ItemKey, Long> entry : old.entrySet()) {
            if (!java.util.Objects.equals(entry.getValue(), fresh.get(entry.getKey()))) changed.add(entry.getKey());
        }
        for (Map.Entry<ItemKey, Long> entry : fresh.entrySet()) {
            if (!java.util.Objects.equals(entry.getValue(), old.get(entry.getKey()))) changed.add(entry.getKey());
        }
        return changed;
    }

    private static Set<FluidKey> diffFluidKeys(List<Map.Entry<FluidKey, Long>> before,
        List<Map.Entry<FluidKey, Long>> after) {
        Map<FluidKey, Long> old = toFluidMap(before);
        Map<FluidKey, Long> fresh = toFluidMap(after);
        Set<FluidKey> changed = new LinkedHashSet<>();
        for (Map.Entry<FluidKey, Long> entry : old.entrySet()) {
            if (!java.util.Objects.equals(entry.getValue(), fresh.get(entry.getKey()))) changed.add(entry.getKey());
        }
        for (Map.Entry<FluidKey, Long> entry : fresh.entrySet()) {
            if (!java.util.Objects.equals(entry.getValue(), old.get(entry.getKey()))) changed.add(entry.getKey());
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
