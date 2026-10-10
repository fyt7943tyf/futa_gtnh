package com.futa_gtnh.ae2;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageListener;
import com.futa_gtnh.shared.SharedStorageManager;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEFluidStack;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.util.item.AEFluidStackType;
import appeng.util.item.AEItemStackType;

/**
 * AE2 共享背包元件的「大脑」：镜像缓存、活跃网络注册表、增量推送与同网络去重。
 * 全部静态、全局一份（共享背包本身就是一个全局单例，镜像自然也是）。
 *
 * <p>
 * <b>性能模型</b>（这是整个功能的性能答案，改这里之前先读完）：
 * <ul>
 * <li>AE 网络<b>不轮询</b>元件。{@code injectItems}/{@code extractItems} 是
 * O(1) 直查共享存储；单类型查询 {@code getAvailableItem} 被覆写成 O(1)，
 * 不走默认实现的全表扫描。</li>
 * <li>全表枚举 {@code getAvailableItems} 只发生在终端全量同步 / 元件插拔差分 /
 * 合成任务开单快照这些低频场景，且走 {@link #itemMirror}/{@link #fluidMirror}
 * —— {@code AEItemStack} 只在条目<b>首次出现</b>时构造一次，之后仅改数量；
 * revision 没变时连快照都不拍。</li>
 * <li>外部路径（终端界面、GT 管道、拾取自动入库……）改动共享背包时，
 * {@link SharedStorageListener} 只把<b>条目键</b>塞进并发集合（存储锁内零 AE 调用），
 * 由 {@link #onServerTickEnd} 每 tick 末批量对活跃网络
 * {@code postAlterationOfStoredItems}。没有元件在场时监听器第一步就返回，
 * 全链路开销是「一次 volatile 读」。</li>
 * <li>本元件自己的写入触发的监听回调用 {@link ThreadLocal} 抑制掉 ——
 * AE 的 NetworkMonitor 已经为这次操作发过差量，再推就是重复。</li>
 * </ul>
 *
 * <p>
 * <b>同网络去重</b>：所有元件包装的是<b>同一个</b>共享存储单例，同一网络装两个
 * 的话聚合列表会把每个条目算两遍（显示翻倍、合成快照翻倍）。规则：
 * 每个网络只有最早注册的元件（primary）提供内容，其余惰性（拒收、不吐、列表为空）；
 * 跨网络的多个元件各自正常服务 —— 和「多个共享终端方块」同一语义。
 *
 * <p>
 * <b>注册表的生命周期</b>（这是和 AE2 内部时序对齐的部分，改动前先看类尾的时序注释）：
 * <ul>
 * <li>注册是<b>懒</b>的：元件 handler 第一次被真实地<b>查询内容</b>时才登记。
 * 刻意不做构造期注册 —— {@code TileChest.canInsertItem} 会顺手为「正在被漏斗
 * 塞进来的元件」创建 handler，构造期注册会让这种幽灵条目占住 primary 位。</li>
 * <li>淘汰信号：{@code Platform.postChanges} 在元件被拔出/插入时会以
 * {@code host == null} 新建 handler 问一遍内容。同一 tick 内「被 null-host 查过
 * 但没有发生过注册」= 这个元件已经离开它的驱动器 → 淘汰。
 * （插入路径里驱动器先 updateState → cellUpdate 差分必然先对它做一次内容查询，
 * 也就是先注册，所以插入不会被误杀 —— 见类尾时序注释。）</li>
 * <li>被淘汰的是 primary 时，向该网络补发一次 {@link MENetworkCellArrayUpdate}，
 * 让剩下的惰性元件接管并触发全量重同步 —— 一次元件拔出接受一次全量同步，
 * 是罕见操作，可接受。</li>
 * </ul>
 *
 * <p>
 * <b>锁序约定</b>：只允许「桥锁 → 存储锁」（镜像刷新在桥锁内拍快照），
 * 绝不允许反向 —— {@link SharedStorage} 的监听回调在<b>存储锁内</b>发生，
 * 所以监听路径只碰并发集合和 volatile，不取桥锁。两边都守住就没有 ABBA。
 */
final class AeCellBridge {

    private AeCellBridge() {}

    /**
     * 向 AE 报告的数量上限。AE2 创意元件用 2^52-1 表示「近乎无限」；
     * 共享背包真实上限是 long 最大值，但全网数量聚合是 long 加法，
     * 直接如实上报会在极端存量下把聚合值加爆。提取不受此限（按请求实返）。
     */
    static final long REPORT_CAP = (1L << 52) - 1;

    // ==================================================================
    // 监听器侧（存储锁内调用，只做并发集合操作，不取桥锁）
    // ==================================================================

    private static volatile boolean installed;
    /** 有元件在场吗。监听路径的快速闸门；注册时置 true，flush 发现全空时置 false。 */
    private static volatile boolean anyActiveCells;
    private static volatile boolean storageReplaced;

    // volatile 非 final：flush 时整体换成新集合。监听线程每次回调单次读字段后在集合上操作，
    // 交换期间新增的条目落在旧集合里也会被完整发完，最多晚一 tick，不会丢。
    private static volatile Set<ItemKey> pendingItems = ConcurrentHashMap.newKeySet();
    private static volatile Set<FluidKey> pendingFluids = ConcurrentHashMap.newKeySet();

    /** 本元件自己写共享存储期间为 true：那次写入的差量 NetworkMonitor 自己会发。 */
    private static final ThreadLocal<Boolean> SUPPRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final SharedStorageListener LISTENER = new SharedStorageListener() {

        @Override
        public void onItemChanged(ItemKey key) {
            if (!installed || SUPPRESS.get()
                .booleanValue() || !anyActiveCells || key == null) return;
            pendingItems.add(key);
        }

        @Override
        public void onFluidChanged(FluidKey key) {
            if (!installed || SUPPRESS.get()
                .booleanValue() || !anyActiveCells || key == null) return;
            pendingFluids.add(key);
        }

        @Override
        public void onStorageReplaced() {
            if (!installed || !anyActiveCells) return;
            storageReplaced = true;
        }
    };

    // ==================================================================
    // 活跃注册表（全部在桥锁内）
    // ==================================================================

    private static final class CellEntry {

        final ItemStack stack;
        final AbstractSharedCellInventory<?, ?> handler;
        final IGrid grid;
        /** 本 tick 内发生过 null-host 差量查询（Platform.postChanges 的拔/插信号）。 */
        boolean nullHostQuery;
        /** 本 tick 内发生过（重新）注册 = 插入侧的「在场证明」。 */
        boolean registeredThisTick;

        CellEntry(ItemStack stack, AbstractSharedCellInventory<?, ?> handler, IGrid grid) {
            this.stack = stack;
            this.handler = handler;
            this.grid = grid;
        }
    }

    /**
     * 全局一张表：identityHashCode(元件 ItemStack 实例) → 条目。
     * 驱动器缓存期间对同一元件槽位返回同一个 ItemStack 实例，所以身份键稳定；
     * 插入顺序即 primary 选举顺序（LinkedHashMap 的 put 覆盖已有键不改变位置）。
     */
    private static final LinkedHashMap<Integer, CellEntry> cells = new LinkedHashMap<>();

    // ==================================================================
    // 镜像缓存（全部在桥锁内）
    // ==================================================================

    private static LinkedHashMap<ItemKey, IAEItemStack> itemMirror;
    private static LinkedHashMap<FluidKey, IAEFluidStack> fluidMirror;
    private static int itemRevisionWatermark = -1;
    private static int fluidRevisionWatermark = -1;

    // ==================================================================
    // 安装 / 重置
    // ==================================================================

    static synchronized void install() {
        if (installed) return;
        installed = true;
        // 挂到当前实例上；换存档/reload 时 SharedStorageManager 会把监听器过继到新实例
        SharedStorageManager.getStorage()
            .addListener(LISTENER);
    }

    /** 服务端停止时清空全部运行时状态（注册表、镜像、待推送集合）。 */
    static synchronized void reset() {
        cells.clear();
        pendingItems.clear();
        pendingFluids.clear();
        itemMirror = null;
        fluidMirror = null;
        itemRevisionWatermark = -1;
        fluidRevisionWatermark = -1;
        storageReplaced = false;
        anyActiveCells = false;
    }

    // ==================================================================
    // 服务决策（handler 每次内容操作都问一句「该不该由我服务」）
    // ==================================================================

    /**
     * 这个 handler 现在应该对外提供共享背包的内容吗？
     *
     * <p>
     * 判定链：注册表里找它的元件身份 → 没登记过且带宿主（真实驱动器里的元件）
     * 就地懒注册并放行；没登记过且没有宿主（{@code Platform.postChanges} 的
     * 差量查询）保守放行 —— 差量查询必须给出「这颗元件当时提供的内容」，
     * 拿不准时宁可报全量，后续 forceUpdate 会收敛；已登记则看它是不是
     * 自己网络里的 primary。
     */
    static synchronized boolean serveContent(AbstractSharedCellInventory<?, ?> handler) {
        if (!installed) return true;
        int identity = System.identityHashCode(handler.cellItem);
        CellEntry entry = cells.get(identity);
        if (entry == null) {
            if (handler.host == null) return true;
            cells.put(identity, new CellEntry(handler.cellItem, handler, handler.grid()));
            cells.get(identity).registeredThisTick = true;
            anyActiveCells = true;
            entry = cells.get(identity);
        }
        return isPrimary(entry);
    }

    /**
     * {@code SharedCellHandler.getCellInventory} 在 host == null 时（差量查询路径）
     * 调用：给对应元件身份盖「本 tick 被 null-host 查过」的章，供 flush 淘汰。
     */
    static synchronized void markNullHostQuery(ItemStack cellItem) {
        if (cellItem == null || !installed) return;
        CellEntry entry = cells.get(System.identityHashCode(cellItem));
        if (entry != null) entry.nullHostQuery = true;
    }

    /** 同一网络里最早登记的条目是 primary；没有同网络竞争者时自己就是。 */
    private static boolean isPrimary(CellEntry entry) {
        if (entry == null) return true;
        for (CellEntry other : cells.values()) {
            if (other.grid == entry.grid) return other == entry;
        }
        return true;
    }

    /** 在不触发监听器的情况下执行一次共享存储写入（元件自己的 MODULATE 路径）。 */
    static <T> T suppressed(Supplier<T> storageWrite) {
        SUPPRESS.set(Boolean.TRUE);
        try {
            return storageWrite.get();
        } finally {
            SUPPRESS.set(Boolean.FALSE);
        }
    }

    // ==================================================================
    // 镜像
    // ==================================================================

    /** 把共享背包全部物品条目（带数量）加进调用方给的列表。列表会自行 copy，安全。 */
    static synchronized void addItemMirror(IItemList<IAEItemStack> out) {
        refreshItemMirror();
        if (itemMirror != null) {
            for (IAEItemStack stack : itemMirror.values()) {
                if (stack.getStackSize() > 0) out.add(stack);
            }
        }
    }

    static synchronized void addFluidMirror(IItemList<IAEFluidStack> out) {
        refreshFluidMirror();
        if (fluidMirror != null) {
            for (IAEFluidStack stack : fluidMirror.values()) {
                if (stack.getStackSize() > 0) out.add(stack);
            }
        }
    }

    private static void refreshItemMirror() {
        SharedStorage storage = SharedStorageManager.getStorage();
        int revision = storage.getRevision();
        if (itemMirror != null && itemRevisionWatermark == revision) return;

        LinkedHashMap<ItemKey, IAEItemStack> fresh = new LinkedHashMap<>();
        for (Map.Entry<ItemKey, Long> entry : storage.snapshotItems()) {
            long amount = Math.min(entry.getValue(), REPORT_CAP);
            IAEItemStack cached = itemMirror == null ? null : itemMirror.get(entry.getKey());
            if (cached != null) {
                cached.setStackSize(amount);
                fresh.put(entry.getKey(), cached);
                continue;
            }
            IAEItemStack created = AEApi.instance()
                .storage()
                .createItemStack(
                    entry.getKey()
                        .prototype());
            if (created == null) continue;
            created.setStackSize(amount);
            fresh.put(entry.getKey(), created);
        }
        itemMirror = fresh;
        itemRevisionWatermark = revision;
    }

    private static void refreshFluidMirror() {
        SharedStorage storage = SharedStorageManager.getStorage();
        int revision = storage.getRevision();
        if (fluidMirror != null && fluidRevisionWatermark == revision) return;

        LinkedHashMap<FluidKey, IAEFluidStack> fresh = new LinkedHashMap<>();
        for (Map.Entry<FluidKey, Long> entry : storage.snapshotFluids()) {
            long amount = Math.min(entry.getValue(), REPORT_CAP);
            IAEFluidStack cached = fluidMirror == null ? null : fluidMirror.get(entry.getKey());
            if (cached != null) {
                cached.setStackSize(amount);
                fresh.put(entry.getKey(), cached);
                continue;
            }
            IAEFluidStack created = AEApi.instance()
                .storage()
                .createFluidStack(
                    entry.getKey()
                        .prototype());
            if (created == null) continue;
            created.setStackSize(amount);
            fresh.put(entry.getKey(), created);
        }
        fluidMirror = fresh;
        fluidRevisionWatermark = revision;
    }

    // ==================================================================
    // 每 tick 末冲洗（注册表淘汰 + 增量推送）
    // ==================================================================

    static synchronized void onServerTickEnd() {
        if (!installed || MinecraftServer.getServer() == null) return;

        List<IGrid> gridsNeedingResync = evictStaleCells();

        if (!anyActiveCells) {
            pendingItems.clear();
            pendingFluids.clear();
            storageReplaced = false;
            return;
        }

        boolean replaced = storageReplaced;
        storageReplaced = false;

        if (replaced) {
            // 存储被结构性替换（换存档 / reload / 清空）：镜像作废 + 每个活跃网络全量重同步
            itemMirror = null;
            fluidMirror = null;
            itemRevisionWatermark = -1;
            fluidRevisionWatermark = -1;
            pendingItems.clear();
            pendingFluids.clear();
            for (IGrid grid : collectLiveGrids()) {
                postCellArrayUpdate(grid);
            }
            return;
        }

        if (gridsNeedingResync != null) {
            for (IGrid grid : gridsNeedingResync) {
                postCellArrayUpdate(grid);
            }
        }

        if (pendingItems.isEmpty() && pendingFluids.isEmpty()) return;

        Set<ItemKey> items = pendingItems;
        Set<FluidKey> fluids = pendingFluids;
        pendingItems = ConcurrentHashMap.newKeySet();
        pendingFluids = ConcurrentHashMap.newKeySet();

        for (IGrid grid : collectLiveGrids()) {
            try {
                IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
                MachineSource source = machineSourceOf(grid);
                if (source == null) continue;
                if (!items.isEmpty()) {
                    storageGrid.postAlterationOfStoredItems(AEItemStackType.ITEM_STACK_TYPE, itemDeltas(items), source);
                }
                if (!fluids.isEmpty()) {
                    storageGrid
                        .postAlterationOfStoredItems(AEFluidStackType.FLUID_STACK_TYPE, fluidDeltas(fluids), source);
                }
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("AE2 共享背包元件：向网络推送增量失败（本 tick 跳过）", t);
            }
        }
    }

    /**
     * 淘汰「本 tick 被 null-host 差量查询过、且本 tick 没发生过注册」的条目
     * （= 元件已离开驱动器），并把因此失去 primary 的网络记下来待重同步。
     */
    private static List<IGrid> evictStaleCells() {
        List<IGrid> resync = null;
        Iterator<CellEntry> iterator = cells.values()
            .iterator();
        while (iterator.hasNext()) {
            CellEntry entry = iterator.next();
            if (entry.nullHostQuery && !entry.registeredThisTick) {
                iterator.remove();
                if (isPrimaryOfGrid(entry)) {
                    if (resync == null) resync = new ArrayList<>();
                    resync.add(entry.grid);
                }
                continue;
            }
            entry.nullHostQuery = false;
            entry.registeredThisTick = false;
        }
        if (cells.isEmpty()) anyActiveCells = false;
        return resync;
    }

    private static boolean isPrimaryOfGrid(CellEntry entry) {
        for (CellEntry other : cells.values()) {
            if (other.grid == entry.grid) return other == entry;
        }
        return true;
    }

    /** 当前仍有登记条目的网络（去重）。 */
    private static List<IGrid> collectLiveGrids() {
        List<IGrid> result = new ArrayList<>(4);
        for (CellEntry entry : cells.values()) {
            if (!result.contains(entry.grid)) result.add(entry.grid);
        }
        return result;
    }

    /** primary 元件宿主（驱动器/箱子，都是 IActionHost）包装成 AE 的动作来源。 */
    private static MachineSource machineSourceOf(IGrid grid) {
        for (CellEntry entry : cells.values()) {
            if (entry.grid == grid && entry.handler.host instanceof IActionHost) {
                return new MachineSource((IActionHost) entry.handler.host);
            }
        }
        return null;
    }

    private static void postCellArrayUpdate(IGrid grid) {
        try {
            grid.postEvent(new MENetworkCellArrayUpdate());
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("AE2 共享背包元件：触发网络重同步失败", t);
        }
    }

    /** 把一批变更键做成「当前全量」的 AE 物品栈（postAlteration 是绝对量语义，0 = 条目消失）。 */
    private static List<IAEStack<?>> itemDeltas(Set<ItemKey> keys) {
        SharedStorage storage = SharedStorageManager.getStorage();
        List<IAEStack<?>> result = new ArrayList<>(keys.size());
        for (ItemKey key : keys) {
            IAEItemStack stack = AEApi.instance()
                .storage()
                .createItemStack(key.prototype());
            if (stack == null) continue;
            stack.setStackSize(Math.min(storage.getItemAmount(key), REPORT_CAP));
            result.add(stack);
        }
        return result;
    }

    private static List<IAEStack<?>> fluidDeltas(Set<FluidKey> keys) {
        SharedStorage storage = SharedStorageManager.getStorage();
        List<IAEStack<?>> result = new ArrayList<>(keys.size());
        for (FluidKey key : keys) {
            IAEFluidStack stack = AEApi.instance()
                .storage()
                .createFluidStack(key.prototype());
            if (stack == null) continue;
            stack.setStackSize(Math.min(storage.getFluidAmount(key), REPORT_CAP));
            result.add(stack);
        }
        return result;
    }

    /*
     * ============================ 时序注释 ============================
     * 下面是「淘汰规则为什么不会误杀刚插入的元件」的依据，全部来自 AE2 源码：
     * 元件插入驱动器（TileDrive.onChangeInventory）：
     * 1. isCached = false → updateState()：对新元件以 host=this 调
     * getCellInventory —— 只创建 handler，不注册（懒注册）。
     * 2. postEvent(MENetworkCellArrayUpdate) → GridStorageCache.cellUpdate：
     * 对新增 handler 做 getAvailableItems 差分 —— 这是第一次内容查询，
     * serveContent 就地注册（registeredThisTick = true）。
     * 3. Platform.postChanges(removed=null, added=元件)：以 host=null 新建
     * handler 问内容 → markNullHostQuery。
     * → 本 tick 结束时：nullHostQuery=true 但 registeredThisTick=true → 保留。
     * 元件拔出：
     * 1. updateState()：该槽位已空，不创建 handler。
     * 2. cellUpdate：对「移除的旧 handler」做 getAvailableItems —— 旧实例按
     * 身份借注册表里的既有条目回答（借道不注册）。
     * 3. Platform.postChanges(removed=元件)：host=null 查询 → 盖章。
     * → 本 tick 结束：nullHostQuery=true、registeredThisTick=false → 淘汰；
     * 若它是 primary → 对该网络补 MENetworkCellArrayUpdate，剩下的元件接管。
     * 已知的极小残余：同一 tick 内「插入又拔出」同一元件会让条目活过一轮
     * （同 tick 注册+盖章按插入处理）。这需要两次库存变更挤进同一个 tick，
     * 漏斗搬运（每 8 tick 一次）做不到，只有直接改驱动器库存的代码路径可能触发。
     */
}
