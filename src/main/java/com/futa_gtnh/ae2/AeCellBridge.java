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

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageListener;
import com.futa_gtnh.shared.SharedStorageManager;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.events.MENetworkCellArrayUpdate;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
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
 * {@code postAlterationOfStoredItems}。变更键<b>不因注册表暂时为空而被丢弃</b>
 * —— 元件回来后的第一批推送会把积压补发出去。没有 AE2 时监听器不安装，
 * 全链路开销是「一次 volatile 读」。</li>
 * <li>本元件自己的写入触发的监听回调用 {@link ThreadLocal} 抑制掉 ——
 * AE 的 NetworkMonitor 已经为这次操作发过差量，再推就是重复。</li>
 * <li>同一批变更除了发通知，还会<b>投递实物给在等的合成 CPU</b>
 * （{@link #feedWaitingCraftingCpus}）。这不是可选项：AE 的合成 CPU 只在
 * 「产物从网络物品库存注入」时才认账，光发通知会卡合成。只有真的在等这件东西的
 * 网络才走这一步，其余网络的开销是「一次 {@code isRequesting()} 判空」。</li>
 * </ul>
 *
 * <p>
 * <b>同网络去重</b>：所有元件包装的是<b>同一个</b>共享存储单例，同一网络装两个
 * 的话聚合列表会把每个条目算两遍（显示翻倍、合成快照翻倍）。规则：
 * 每个网络的每种存储通道各由最早注册的元件（primary）提供内容，
 * 同通道的其余元件惰性（拒收、不吐、列表为空）；物品和流体元件互不竞争。
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
 * 宿主方块实体失效（区块卸载 / 被破坏，这两种<b>不会</b>产生库存变更事件）
 * 是第二个淘汰信号。每个 tick 末还会把幸存条目的网格引用从宿主节点<b>重新解析</b>
 * —— 网络拆分 / 合并后老引用指向死网格，继续往死网格推增量会让真实网络的
 * 终端冻结在旧状态。
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

    // Queue publication and listener additions share a short lock, never held during storage/AE calls.
    // Without it a worker can add to the previous set after the tick has already drained that set.
    private static final Object PENDING_LOCK = new Object();
    private static Set<ItemKey> pendingItems = ConcurrentHashMap.newKeySet();
    private static Set<FluidKey> pendingFluids = ConcurrentHashMap.newKeySet();

    static final class PendingChanges {

        final Set<ItemKey> items;
        final Set<FluidKey> fluids;

        PendingChanges(Set<ItemKey> items, Set<FluidKey> fluids) {
            this.items = items;
            this.fluids = fluids;
        }
    }

    static PendingChanges drainPendingChanges() {
        synchronized (PENDING_LOCK) {
            PendingChanges result = new PendingChanges(pendingItems, pendingFluids);
            pendingItems = ConcurrentHashMap.newKeySet();
            pendingFluids = ConcurrentHashMap.newKeySet();
            return result;
        }
    }

    /** 本元件自己写共享存储期间为 true：那次写入的差量 NetworkMonitor 自己会发。 */
    private static final ThreadLocal<Boolean> SUPPRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final SharedStorageListener LISTENER = new SharedStorageListener() {

        @Override
        public void onItemChanged(ItemKey key) {
            // 注意这里<b>不检查</b> anyActiveCells：注册表暂时为空（元件都在换网格 /
            // 刚被错误淘汰 / 尚未注册）期间发生的变更也必须排队。键在集合里有幂等性，
            // 最多攒「每种条目一个」，等元件回来后第一批推送就能把它们补发给 AE。
            // 以前这里会直接丢掉 —— 丢掉的变更没有任何补救路径，AE 终端从此冻结在
            // 旧状态（幽灵材料 / 产物失踪），直到玩家手动对 AE 做一次操作。
            if (!installed || SUPPRESS.get()
                .booleanValue() || key == null) return;
            synchronized (PENDING_LOCK) {
                pendingItems.add(key);
            }
        }

        @Override
        public void onFluidChanged(FluidKey key) {
            if (!installed || SUPPRESS.get()
                .booleanValue() || key == null) return;
            synchronized (PENDING_LOCK) {
                pendingFluids.add(key);
            }
        }

        @Override
        public void onStorageReplaced() {
            if (!installed) return;
            storageReplaced = true;
        }
    };

    // ==================================================================
    // 活跃注册表（全部在桥锁内）
    // ==================================================================

    private static final class CellEntry {

        final ItemStack stack;
        /** 现在正代表这颗元件回答查询的 handler（驱动器重建 handler 时会换实例）。 */
        AbstractSharedCellInventory<?, ?> handler;
        /** 最近一次解析出的网格；每 tick 末从宿主节点重新解析（见 flushGrids）。 */
        IGrid grid;
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
    private static SharedStorage itemMirrorStorage;
    private static SharedStorage fluidMirrorStorage;

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

    /** 服务端停止时清空全部运行时状态（注册表、镜像、待推送集合、差量基准）。 */
    static synchronized void reset() {
        cells.clear();
        pendingItems.clear();
        pendingFluids.clear();
        itemMirror = null;
        fluidMirror = null;
        itemMirrorStorage = null;
        fluidMirrorStorage = null;
        itemRevisionWatermark = -1;
        fluidRevisionWatermark = -1;
        lastPostedItems.clear();
        lastPostedFluids.clear();
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
        if (com.futa_gtnh.me.PublicMeNetwork.isRunning() && handler.currentGrid() != null
            && handler.currentGrid() == com.futa_gtnh.me.PublicMeNetwork.grid()) return false;
        if (!installed) return true;
        int identity = System.identityHashCode(handler.cellItem);
        CellEntry entry = cells.get(identity);
        if (entry == null) {
            if (handler.host == null) return true;
            cells.put(identity, new CellEntry(handler.cellItem, handler, handler.currentGrid()));
            cells.get(identity).registeredThisTick = true;
            anyActiveCells = true;
            entry = cells.get(identity);
        } else if (handler.host != null && entry.handler != handler) {
            // 同一元件身份（驱动器缓存的 ItemStack 实例）换了 handler 实例：
            // 要么驱动器 updateState 重建了 handler，要么新元件撞上了被淘汰元件
            // 留下的 identityHashCode。以「现在来服务的这个」为准 —— 旧 handler
            // 挂着死网格继续当家的话，这颗元件可能被判成非 primary 而拒绝服务。
            entry.handler = handler;
            entry.grid = handler.currentGrid();
            entry.registeredThisTick = true;
            anyActiveCells = true;
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

    /** 同一网络、同一存储通道里最早登记的条目是 primary。 */
    private static boolean isPrimary(CellEntry entry) {
        if (entry == null) return true;
        for (CellEntry other : cells.values()) {
            // 两种通道分别由 final 的 SharedItem/SharedFluidCellInventory 实现。
            // 按 handler 类型分组，也避免选主时触发 AE stack type 注册表的静态初始化。
            if (other.grid == entry.grid && other.handler.getClass() == entry.handler.getClass()) {
                return other == entry;
            }
        }
        return true;
    }

    /** 在不触发监听器的情况下执行一次共享存储写入（元件自己的 MODULATE 路径）。 */
    static <T> T suppressed(Supplier<T> storageWrite) {
        boolean previous = SUPPRESS.get();
        SUPPRESS.set(Boolean.TRUE);
        try {
            return storageWrite.get();
        } finally {
            SUPPRESS.set(previous);
        }
    }

    /**
     * 元件自己的写入落账后同步差量基准（见 {@code lastPostedItems} 的说明）。
     * AE 网络已经通过 {@code injectItems/extractItems} 的返回值知道了这笔写入，
     * 这里只是把「上次推送量」对齐到写入后的实际数量，让后续外部变更的差量保持精确。
     */
    static synchronized void noteOwnItemWrite(ItemKey key) {
        if (!installed || key == null) return;
        lastPostedItems.put(
            key,
            Long.valueOf(
                Math.min(
                    SharedStorageManager.getStorage()
                        .getItemAmount(key),
                    REPORT_CAP)));
    }

    /** 流体侧的 {@link #noteOwnItemWrite(ItemKey)}。 */
    static synchronized void noteOwnFluidWrite(FluidKey key) {
        if (!installed || key == null) return;
        lastPostedFluids.put(
            key,
            Long.valueOf(
                Math.min(
                    SharedStorageManager.getStorage()
                        .getFluidAmount(key),
                    REPORT_CAP)));
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
        int revision = storage.getItemRevision();
        if (itemMirror != null && itemMirrorStorage == storage && itemRevisionWatermark == revision) return;

        LinkedHashMap<ItemKey, IAEItemStack> fresh = new LinkedHashMap<>();
        for (Map.Entry<ItemKey, Long> entry : storage.snapshotItemsCached()) {
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
        itemMirrorStorage = storage;
        itemRevisionWatermark = revision;
    }

    private static void refreshFluidMirror() {
        SharedStorage storage = SharedStorageManager.getStorage();
        int revision = storage.getFluidRevision();
        if (fluidMirror != null && fluidMirrorStorage == storage && fluidRevisionWatermark == revision) return;

        LinkedHashMap<FluidKey, IAEFluidStack> fresh = new LinkedHashMap<>();
        for (Map.Entry<FluidKey, Long> entry : storage.snapshotFluidsCached()) {
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
        fluidMirrorStorage = storage;
        fluidRevisionWatermark = revision;
    }

    // ==================================================================
    // 每 tick 末冲洗（注册表保鲜 + 合成投递 + 增量推送）
    // ==================================================================

    /**
     * 上一次推送给 AE 的各条目数量。{@code postAlterationOfStoredItems} 在 AE2 里是
     * <b>差量</b>语义（{@code postChange(true, ...)} 原样把数量当增量发给监听者），
     * 以前直接把「当前全量」当增量发 —— 终端恰好会重新读绝对值所以没炸，但对任何
     * 按差量累加的监听者都是污染。现在记住上一笔发到多少，只发真正的变化。
     */
    private static final java.util.HashMap<ItemKey, Long> lastPostedItems = new java.util.HashMap<>();
    private static final java.util.HashMap<FluidKey, Long> lastPostedFluids = new java.util.HashMap<>();

    static synchronized void onServerTickEnd() {
        if (!installed || MinecraftServer.getServer() == null) return;

        List<IGrid> gridsNeedingResync = evictStaleCells();

        if (!anyActiveCells) {
            // 注册表暂时为空：变更<b>留在队列里</b>（不丢），等元件回来后第一批
            // 推送就会补发。storageReplaced 也一样留着 —— 元件回来时的全量重同步
            // 仍然要以它为准作废镜像。
            return;
        }

        boolean replaced = storageReplaced;
        storageReplaced = false;

        if (replaced) {
            // 存储被结构性替换（换存档 / reload / 清空）：镜像作废 + 每个活跃网络全量重同步
            itemMirror = null;
            fluidMirror = null;
            itemMirrorStorage = null;
            fluidMirrorStorage = null;
            itemRevisionWatermark = -1;
            fluidRevisionWatermark = -1;
            lastPostedItems.clear();
            lastPostedFluids.clear();
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

        PendingChanges changes = drainPendingChanges();
        Set<ItemKey> items = changes.items;
        Set<FluidKey> fluids = changes.fluids;
        if (items.isEmpty() && fluids.isEmpty()) return;

        List<IGrid> liveGrids = collectLiveGrids();
        if (liveGrids.isEmpty()) {
            // 一个可用网络都没有（条目都在换网格）：把键塞回队列，别丢 ——
            // 下一 tick 网格解析出来了照常补发。
            requeueChanges(items, fluids);
            return;
        }

        // ★ 顺序有意义：先向等待的 CPU 投递实物，再广播结算后的共享库存。
        // 中间产物进入 CPU；最终产物被确认后退回共享库。
        liveGrids.removeIf(grid -> grid == com.futa_gtnh.me.PublicMeNetwork.grid());
        feedWaitingCraftingCpus(liveGrids, items, fluids);

        // 差量只算一次：基准（lastPosted）在计算时就会前移，
        // 每个网络各自算一遍的话，第二个网络起拿到的永远是空集。
        List<IAEStack<?>> itemDeltaList = items.isEmpty() ? java.util.Collections.<IAEStack<?>>emptyList()
            : itemDeltas(items);
        List<IAEStack<?>> fluidDeltaList = fluids.isEmpty() ? java.util.Collections.<IAEStack<?>>emptyList()
            : fluidDeltas(fluids);
        if (itemDeltaList.isEmpty() && fluidDeltaList.isEmpty()) return;

        for (IGrid grid : liveGrids) {
            if (grid == com.futa_gtnh.me.PublicMeNetwork.grid()) continue;
            try {
                IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
                MachineSource source = machineSourceOf(grid);
                if (source == null) {
                    // 拿不到动作来源（宿主不是 IActionHost / 网格刚重组完）：
                    // 退回一次全量重同步。静默跳过的话这批变更就没了 ——
                    // 那正是「AE 冻结在旧状态」的来源之一。
                    postCellArrayUpdate(grid);
                    continue;
                }
                if (!itemDeltaList.isEmpty()) {
                    storageGrid.postAlterationOfStoredItems(AEItemStackType.ITEM_STACK_TYPE, itemDeltaList, source);
                }
                if (!fluidDeltaList.isEmpty()) {
                    storageGrid.postAlterationOfStoredItems(AEFluidStackType.FLUID_STACK_TYPE, fluidDeltaList, source);
                }
            } catch (Throwable t) {
                FutaGtnhMod.LOG.warn("AE2 共享背包元件：向网络推送增量失败，改为触发该网络全量重同步", t);
                postCellArrayUpdate(grid);
            }
        }
    }

    /** flush 中途发现没有可用的网络时，把刚取出的键塞回待推送集合（合并，不覆盖后来新增的）。 */
    private static void requeueChanges(Set<ItemKey> items, Set<FluidKey> fluids) {
        synchronized (PENDING_LOCK) {
            pendingItems.addAll(items);
            pendingFluids.addAll(fluids);
        }
    }

    // ==================================================================
    // 合成投递（把「直接落进共享背包的那部分」喂给在等的合成 CPU）
    // ==================================================================

    /**
     * External warehouse writes bypass AE's injection path. Reserve real stock, offer it only to the
     * crafting cache, and restore the returned remainder (including acknowledged final outputs).
     * No absolute-stock watermark is needed: every successful transfer settles its actual quantities.
     */
    @SuppressWarnings("unchecked")
    private static void feedWaitingCraftingCpus(List<IGrid> grids, Set<ItemKey> items, Set<FluidKey> fluids) {
        if (!Config.ae2CellFeedCraftingCpu || (items.isEmpty() && fluids.isEmpty())) return;
        SharedStorage storage = SharedStorageManager.getStorage();
        for (IGrid grid : grids) {
            try {
                ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
                MachineSource source = machineSourceOf(grid);
                if (!(craftingGrid instanceof IMEInventory<?>) || source == null) continue;
                for (ItemKey key : items) {
                    try {
                        IAEItemStack stack = AEApi.instance()
                            .storage()
                            .createItemStack(key.prototype());
                        if (stack == null) continue;
                        stack.setStackSize(Math.min(storage.getItemAmount(key), REPORT_CAP));
                        if (stack.getStackSize() <= 0L || !craftingGrid.isRequesting(stack)) continue;
                        suppressed(() -> {
                            CraftingDelivery
                                .item(storage, key, stack, (IMEInventory<IAEItemStack>) craftingGrid, source);
                            return null;
                        });
                    } catch (Throwable error) {
                        FutaGtnhMod.LOG.error(
                            "AE2 crafting delivery failed; uncertain stock is retained in /futashared escrow",
                            error);
                    } finally {
                        SharedStorageManager.broadcastItemChange(key);
                    }
                }
                for (FluidKey key : fluids) {
                    try {
                        IAEFluidStack stack = AEApi.instance()
                            .storage()
                            .createFluidStack(key.prototype());
                        if (stack == null) continue;
                        stack.setStackSize(Math.min(storage.getFluidAmount(key), REPORT_CAP));
                        if (stack.getStackSize() <= 0L || !craftingGrid.isRequesting(stack)) continue;
                        suppressed(() -> {
                            CraftingDelivery
                                .fluid(storage, key, stack, (IMEInventory<IAEFluidStack>) craftingGrid, source);
                            return null;
                        });
                    } catch (Throwable error) {
                        FutaGtnhMod.LOG.error(
                            "AE2 fluid crafting delivery failed; uncertain stock is retained in /futashared escrow",
                            error);
                    } finally {
                        SharedStorageManager.broadcastFluidChange(key);
                    }
                }
            } catch (Throwable error) {
                FutaGtnhMod.LOG.warn("AE2 crafting cache is unavailable; delivery skipped for this network", error);
            }
        }
    }

    /**
     * 淘汰已经离开驱动器的元件条目，并把幸存条目的网格引用刷新到「现在」。
     *
     * <p>
     * 淘汰信号有两个，满足其一即淘汰（都受 {@code registeredThisTick} 保护，
     * 刚注册的条目本 tick 不杀）：
     * <ol>
     * <li>原有信号：本 tick 被 null-host 差量查询过且没有发生过注册
     * （= Platform.postChanges 报告的「拔出 / 插入又拔出」）；</li>
     * <li>新增信号：宿主方块实体已失效（区块卸载 / 方块被破坏走的是这条 ——
     * 这两种情况<b>不会</b>触发驱动器的库存变更事件，原有信号收不到，
     * 条目会带着死网格一直留在表里）。</li>
     * </ol>
     *
     * <p>
     * 网格刷新在淘汰之前做：AE 网络拆分 / 合并后，驱动器节点会被迁移到新的
     * {@code IGrid}，老引用指向的网格虽然对象还在、却已经没有任何节点在 tick 它 ——
     * 继续往死网格推增量，真实网络的监视器就永远收不到通知（AE 终端冻结在旧状态，
     * 也就是「材料还在、产物没有、从 AE 塞一次就好」的那种不一致）。
     */
    private static List<IGrid> evictStaleCells() {
        for (CellEntry entry : cells.values()) {
            IGrid fresh = entry.handler.currentGrid();
            if (fresh != entry.grid) entry.grid = fresh;
        }

        List<IGrid> resync = null;
        Iterator<CellEntry> iterator = cells.values()
            .iterator();
        while (iterator.hasNext()) {
            CellEntry entry = iterator.next();
            if (!entry.registeredThisTick && isHostGone(entry)) {
                // 必须在移除前判定；移除后表里只剩次要元件，会漏掉接管所需的重同步。
                boolean wasPrimary = isPrimary(entry);
                iterator.remove();
                if (wasPrimary && entry.grid != null) {
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

    /** 既有淘汰信号（null-host 差量查询且本 tick 无注册），或宿主已经失效。 */
    private static boolean isHostGone(CellEntry entry) {
        if (entry.nullHostQuery && !entry.registeredThisTick) return true;
        Object host = entry.handler.host;
        if (host instanceof net.minecraft.tileentity.TileEntity tileEntity) return tileEntity.isInvalid();
        return false;
    }

    /** 当前仍有登记条目的网络（去重；网格解析不出的条目跳过）。 */
    private static List<IGrid> collectLiveGrids() {
        List<IGrid> result = new ArrayList<>(4);
        for (CellEntry entry : cells.values()) {
            if (entry.grid != null && !result.contains(entry.grid)) result.add(entry.grid);
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

    /**
     * 把一批变更键做成真正的<b>差量</b>（当前量 − 上次推送量；0 = 条目消失时为负数）。
     * {@code postAlterationOfStoredItems} 的消费方按差量理解这些数字，
     * 没变化的条目直接跳过。构造失败（物品已注销）的条目保持旧基准，下个 tick 重试。
     */
    private static List<IAEStack<?>> itemDeltas(Set<ItemKey> keys) {
        SharedStorage storage = SharedStorageManager.getStorage();
        List<IAEStack<?>> result = new ArrayList<>(keys.size());
        for (ItemKey key : keys) {
            long current = Math.min(storage.getItemAmount(key), REPORT_CAP);
            long previous = lastPostedItems.containsKey(key) ? lastPostedItems.get(key)
                .longValue() : 0L;
            if (current == previous) continue;
            IAEItemStack stack = AEApi.instance()
                .storage()
                .createItemStack(key.prototype());
            if (stack == null) continue;
            stack.setStackSize(current - previous);
            result.add(stack);
            lastPostedItems.put(key, Long.valueOf(current));
        }
        return result;
    }

    private static List<IAEStack<?>> fluidDeltas(Set<FluidKey> keys) {
        SharedStorage storage = SharedStorageManager.getStorage();
        List<IAEStack<?>> result = new ArrayList<>(keys.size());
        for (FluidKey key : keys) {
            long current = Math.min(storage.getFluidAmount(key), REPORT_CAP);
            long previous = lastPostedFluids.containsKey(key) ? lastPostedFluids.get(key)
                .longValue() : 0L;
            if (current == previous) continue;
            IAEFluidStack stack = AEApi.instance()
                .storage()
                .createFluidStack(key.prototype());
            if (stack == null) continue;
            stack.setStackSize(current - previous);
            result.add(stack);
            lastPostedFluids.put(key, Long.valueOf(current));
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
