package com.futa_gtnh.shared;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.futa_gtnh.api.SharedStorageReadStatus;
import com.futa_gtnh.api.SharedStorageSnapshot;
import com.futa_gtnh.storagebus.StorageBusRegistry;

/**
 * 全服共享的无限存储池。物品和流体各一张表，<b>数量用 long 记</b>，
 * 单条目上限 {@link #MAX_AMOUNT}（约 9.22e18），实际使用中等于无限。
 *
 * <p>
 * 为什么用 long 而不是 int 存数量：{@code ItemStack.stackSize} 是 int，
 * 但「无限堆叠」要求在<b>存储层</b>摆脱 int 的限制。只有在取出到玩家背包时
 * 才需要落回 {@code ItemStack} 的 int 世界（那时按 64 一组发就行）。
 * 另外原版 {@code ItemStack.writeToNBT} 把 Count 写成 byte，超过 127 会被截断 ——
 * 所以数量绝对不能塞进 ItemStack 的 NBT 里持久化。
 *
 * <p>
 * 用 {@link LinkedHashMap} 而不是 HashMap：保留插入顺序，让 GUI 在
 * 「按名称排序」之外的默认视图下位置稳定，不会每帧跳来跳去。
 *
 * <p>
 * <b>线程模型</b>：MC 服务端的方块/实体/网络逻辑都在主线程上跑，
 * 所以正常路径没有竞争。但 GT 的某些机器、管道或第三方模组有可能从工作线程
 * 调进来，因此池子本身的修改仍然全部在 {@code synchronized} 的 {@code *Pool}
 * 方法里完成；需要遍历时请用 {@link #snapshotItems()} / {@link #snapshotFluids()}，
 * 它们返回独立副本，调用方在锁外遍历不会踩到 {@code ConcurrentModificationException}。
 *
 * <p>
 * <b>存储总线</b>的读写要在世界里的容器上做，只能在服务端主线程执行；
 * 工作线程调 {@link #insertItem}/{@link #extractItem} 时会自动退回「只用池子」
 * （见 {@code StorageBusRegistry.canTouchWorld()}）。聚合库存的读取是锁保护的
 * 缓存表，工作线程读也不会碰世界。
 */
public class SharedStorage {

    /** 单个条目的数量上限。再往上加会饱和到这个值，多出来的部分原样退回。 */
    public static final long MAX_AMOUNT = Long.MAX_VALUE;

    /** 存档格式版本。将来改结构时靠它做迁移。 */
    public static final int FORMAT_VERSION = 3;

    private final LinkedHashMap<ItemKey, Long> items = new LinkedHashMap<>();
    private final LinkedHashMap<FluidKey, Long> fluids = new LinkedHashMap<>();

    // Removed from spendable stock, but with rollback capacity retained. An uncertain AE callback
    // leaves this escrow durable and blocks automatic delivery of that key until an OP resolves it.
    private final LinkedHashMap<ItemKey, Long> craftingItems = new LinkedHashMap<>();
    private final LinkedHashMap<FluidKey, Long> craftingFluids = new LinkedHashMap<>();
    private final List<NBTTagCompound> unreadableCraftingItems = new ArrayList<>();
    private final List<NBTTagCompound> unreadableCraftingFluids = new ArrayList<>();
    private long craftingEpoch;

    /**
     * 按条目的存量上限。没进这张表 = 不限制（见 {@link #getItemLimit}）。
     *
     * <p>
     * 和存量分开存是有意的：存量会被取空（条目从 {@link #items} 里删掉），
     * 而上限是玩家的一个「设置」，东西取光了也不该自己忘掉。
     */
    private final LinkedHashMap<ItemKey, Long> itemLimits = new LinkedHashMap<>();
    private final LinkedHashMap<FluidKey, Long> fluidLimits = new LinkedHashMap<>();

    /**
     * 「隔离区」：存档里读不出来的条目，原样留着，写盘时再原封不动写回去。
     * 用途见 {@link #readFromNbt} 的说明 —— 这是防止「临时卸载一个模组就永久丢东西」的关键。
     */
    private final java.util.List<NBTTagCompound> unreadableItems = new java.util.ArrayList<>();
    private final java.util.List<NBTTagCompound> unreadableFluids = new java.util.ArrayList<>();

    /** 增量维护的合计值，避免每次开 GUI 都全表求和 */
    private long itemTotal;
    private long fluidTotal;

    /**
     * 每次内容变化自增。客户端拿它判断「要不要重新过滤/排序」，
     * 比逐个比较条目便宜得多。
     */
    private int revision;

    private boolean dirty;

    /**
     * 内容变更监听器（AE2 桥用，见 {@link SharedStorageListener}）。
     *
     * <p>
     * 普通情况下一辈子都是空的，写入路径上只多一次 {@code isEmpty()} 判断；
     * 改动只发生在 {@link #addListener}/{@link #removeListener} 里。
     */
    private final List<SharedStorageListener> listeners = new ArrayList<>();

    // ==================================================================
    // 物品
    // ==================================================================

    public synchronized long getItemAmount(ItemKey key) {
        if (key == null) return 0L;
        Long amount = items.get(key);
        long internal = amount == null ? 0L : amount;
        // 存储总线把相邻容器的库存并进共享存储的「可读视图」。
        // 只写总线不在可读表里，所以这里看不到它们。
        return saturatingAdd(internal, StorageBusRegistry.getReadableItemAmount(key));
    }

    public synchronized long getItemAmount(net.minecraft.item.ItemStack stack) {
        return getItemAmount(ItemKey.of(stack));
    }

    public synchronized boolean hasItem(ItemKey key) {
        return key != null && getItemAmount(key) > 0L;
    }

    /** 算存量上限时用的数量：包含只写总线的隐藏库存，避免「看不见的东西绕过上限」。 */
    private long itemAmountForLimit(ItemKey key) {
        Long amount = key == null ? null : items.get(key);
        long internal = amount == null ? 0L : amount;
        return saturatingAdd(internal, StorageBusRegistry.getAllItemAmount(key));
    }

    private long fluidAmountForLimit(FluidKey key) {
        Long amount = key == null ? null : fluids.get(key);
        long internal = amount == null ? 0L : amount;
        return saturatingAdd(internal, StorageBusRegistry.getAllFluidAmount(key));
    }

    /**
     * 存入物品，<b>遵守存量上限</b>。
     *
     * <p>
     * 这是<b>自动化路径</b>用的入口：管道、机器、终端主动搬运、别的模组通过
     * {@code IInventory} 塞进来 —— 到了上限就拒收，超出的部分原样退回给调用方
     * （调用方必须自己处理没存下的部分，返回值就是实际收下的量）。
     *
     * <p>
     * 玩家<b>手动</b>往里放走 {@link #insertItemManual}，那条路允许超出上限。
     *
     * @param stack  物品来源，只取它的「种类」，{@code stackSize} 被忽略
     * @param amount 要存的数量
     * @return 实际存进去的数量；因为上限而没存进去的部分需要调用方自行处理
     */
    public long insertItem(net.minecraft.item.ItemStack stack, long amount) {
        return insertItem(ItemKey.of(stack), amount);
    }

    /**
     * 存入物品：<b>先按优先级尝试存储总线，剩下的再进共享存储池</b>。
     *
     * <p>
     * 这一步会暂时拿着存储锁：总线写入必须和池子上限的扣减在同一个临界区里，
     * 否则两个并发写入可以各自看到「还有空间」而一起超限。总线 I/O 只在本模组
     * 的方块 / 管道路径上发生，量级是「一次请求几十格」，可以接受。
     */
    public synchronized long insertItem(ItemKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;

        // 上限按「池子 + 全部总线」算；只写总线的隐藏库存也计入
        long room = itemSpaceLeft(key);
        if (room <= 0L) return 0L;
        long allowed = Math.min(amount, room);

        // AE 存储总线的优先级语义：高优先级（含 0）先填，然后才是共享存储池，
        // 负优先级只在池子也收不下时接溢出。
        // 工作线程不碰世界里的容器，直接退回池子（理由见 StorageBusRegistry.canTouchWorld）。
        boolean buses = StorageBusRegistry.canTouchWorld();
        long external = buses ? StorageBusRegistry.insertItem(key, allowed, true) : 0L;
        long remaining = allowed - external;
        long internal = remaining > 0L ? insertItemPool(key, remaining, true) : 0L;
        remaining -= internal;
        if (buses && remaining > 0L) {
            external += StorageBusRegistry.insertItem(key, remaining, false);
        }
        return external + internal;
    }

    /**
     * 玩家手动存入，<b>允许超出上限</b>。
     *
     * <p>
     * 「上限」是给自动化用的闸门（防止某个物品被机器无限灌满），不是给玩家上的镣铐 ——
     * 玩家自己往里放，放多少就收多少，只是仍然受全局上限保护（避免计数溢出）。
     *
     * <p>
     * <b>能这样分的前提是路径本身就分得开</b>：手动那条只有界面上的存入槽走，
     * 而它由本模组的 {@code Slot} 直接调用，模组外的自动化碰不到；
     * 自动化走的是 {@code IInventory}/{@code IFluidHandler} 那一套，一律受上限约束。
     */
    public long insertItemManual(net.minecraft.item.ItemStack stack, long amount) {
        return insertItemManual(ItemKey.of(stack), amount);
    }

    public synchronized long insertItemManual(ItemKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        long room = itemManualSpaceLeft(key);
        if (room <= 0L) return 0L;
        long allowed = Math.min(amount, room);
        boolean buses = StorageBusRegistry.canTouchWorld();
        long external = buses ? StorageBusRegistry.insertItem(key, allowed, true) : 0L;
        long remaining = allowed - external;
        long internal = remaining > 0L ? insertItemPool(key, remaining, false) : 0L;
        remaining -= internal;
        if (buses && remaining > 0L) {
            external += StorageBusRegistry.insertItem(key, remaining, false);
        }
        return external + internal;
    }

    /**
     * 直接进共享存储池，<b>不经过存储总线</b>。
     *
     * <p>
     * 给「从相邻容器主动抽入」这条路径用：源容器本身可能就挂着一条存储总线，
     * 如果抽出来的东西又被总线写回同一个容器，就会先扣源格、再写回、再扣，
     * 既白费力气，也可能在缓存刷新前造成数量对不上。主动抽入的语义本来就是
     * 「进池子」，所以这条路直接走池子。
     */
    public long insertItemDirect(net.minecraft.item.ItemStack stack, long amount) {
        return insertItemDirect(ItemKey.of(stack), amount);
    }

    public long insertItemDirect(ItemKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        long room = itemSpaceLeft(key);
        if (room <= 0L) return 0L;
        return insertItemPool(key, Math.min(amount, room), true);
    }

    /** 直接进池子、且允许超出上限；用于主动搬运失败时的回滚。 */
    public long insertItemManualDirect(ItemKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        long room = itemManualSpaceLeft(key);
        if (room <= 0L) return 0L;
        return insertItemPool(key, Math.min(amount, room), false);
    }

    /** @return 共享存储池自己的存量，<b>不含</b>存储总线；主动输出用 */
    public synchronized long getItemAmountPool(ItemKey key) {
        if (key == null) return 0L;
        Long amount = items.get(key);
        return amount == null ? 0L : amount.longValue();
    }

    /** 直接从池子取，不经过存储总线；主动输出 / 回滚用 */
    public long extractItemDirect(ItemKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        return extractItemPool(key, amount);
    }

    public synchronized long getFluidAmountPool(FluidKey key) {
        if (key == null) return 0L;
        Long amount = fluids.get(key);
        return amount == null ? 0L : amount.longValue();
    }

    public long extractFluidDirect(FluidKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        return extractFluidPool(key, amount);
    }

    private synchronized long insertItemPool(ItemKey key, long amount, boolean obeyLimit) {
        if (key == null || amount <= 0L) return 0L;

        long have = itemAmountForLimit(key);
        // 手动存放时上限不参与，但全局上限仍然要守：long 加爆了会让合计值变负
        long ceiling = obeyLimit ? limitOf(itemLimits, key) : MAX_AMOUNT;
        long space = Math.max(0L, ceiling - have) - craftingItems.getOrDefault(key, 0L);
        if (space <= 0L) return 0L;

        long accepted = Math.min(amount, space);
        Long existing = items.get(key);
        long internal = existing == null ? 0L : existing.longValue();
        items.put(key, saturatingAdd(internal, accepted));
        // 用饱和加法：合计值本身溢出变负的话，/futashared info 会打印出负数，
        // 之后每一次取出还会让这个负数继续漂移
        itemTotal = saturatingAdd(itemTotal, accepted);
        markChanged();
        if (accepted > 0L) notifyItemChanged(key);
        return accepted;
    }

    /**
     * 取出物品：<b>低优先级总线 → 共享存储池 → 高优先级总线</b>，
     * 和 AE 存储总线的抽取顺序一致。
     *
     * @return 实际取出的数量，可能少于请求量（存量不足）。条目取空后会从表里删掉。
     */
    public synchronized long extractItem(ItemKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;

        long remaining = amount;
        boolean buses = StorageBusRegistry.canTouchWorld();
        long taken = buses ? StorageBusRegistry.extractItem(key, remaining, true) : 0L;
        remaining -= taken;
        if (remaining > 0L) {
            long internal = extractItemPool(key, remaining);
            taken += internal;
            remaining -= internal;
        }
        if (buses && remaining > 0L) {
            taken += StorageBusRegistry.extractItem(key, remaining, false);
        }
        return taken;
    }

    private synchronized long extractItemPool(ItemKey key, long amount) {
        Long stored = items.get(key);
        if (stored == null) return 0L;

        long taken = Math.min(stored, amount);
        long remaining = stored - taken;
        if (remaining <= 0L) {
            items.remove(key);
        } else {
            items.put(key, remaining);
        }
        itemTotal = Math.max(0L, itemTotal - taken);
        markChanged();
        if (taken > 0L) notifyItemChanged(key);
        return taken;
    }

    /**
     * 读存档时把一条物品并进表里。
     *
     * <p>
     * 单独抽出来是因为它和 {@link #insertItem} 的语义不同：这里信任存档里的数量，
     * 不做上限夹取，但要累加合计值（同一键在存档里出现两次时也不能只是覆盖）。
     */
    private void mergeItem(ItemKey key, long amount) {
        Long existing = items.get(key);
        long have = existing == null ? 0L : existing.longValue();
        items.put(key, saturatingAdd(have, amount));
        itemTotal = saturatingAdd(itemTotal, amount);
    }

    private void mergeFluid(FluidKey key, long amount) {
        Long existing = fluids.get(key);
        long have = existing == null ? 0L : existing.longValue();
        fluids.put(key, saturatingAdd(have, amount));
        fluidTotal = saturatingAdd(fluidTotal, amount);
    }

    // ==================================================================
    // 流体（单位：毫巴 mB，和 GT 的 L 是 1:1）
    // ==================================================================

    public synchronized long getFluidAmount(FluidKey key) {
        if (key == null) return 0L;
        Long amount = fluids.get(key);
        long internal = amount == null ? 0L : amount;
        return saturatingAdd(internal, StorageBusRegistry.getReadableFluidAmount(key));
    }

    public synchronized long getFluidAmount(net.minecraftforge.fluids.FluidStack stack) {
        return getFluidAmount(FluidKey.of(stack));
    }

    public synchronized boolean hasFluid(FluidKey key) {
        return key != null && getFluidAmount(key) > 0L;
    }

    public long insertFluid(net.minecraftforge.fluids.FluidStack stack, long amount) {
        return insertFluid(FluidKey.of(stack), amount);
    }

    public synchronized long insertFluid(FluidKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        long room = fluidSpaceLeft(key);
        if (room <= 0L) return 0L;
        long allowed = Math.min(amount, room);
        boolean buses = StorageBusRegistry.canTouchWorld();
        long external = buses ? StorageBusRegistry.insertFluid(key, allowed, true) : 0L;
        long remaining = allowed - external;
        long internal = remaining > 0L ? insertFluidPool(key, remaining, true) : 0L;
        remaining -= internal;
        if (buses && remaining > 0L) {
            external += StorageBusRegistry.insertFluid(key, remaining, false);
        }
        return external + internal;
    }

    /** 玩家手动倒入流体，允许超出上限（同 {@link #insertItemManual}）。 */
    public synchronized long insertFluidManual(FluidKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        long room = fluidManualSpaceLeft(key);
        if (room <= 0L) return 0L;
        long allowed = Math.min(amount, room);
        boolean buses = StorageBusRegistry.canTouchWorld();
        long external = buses ? StorageBusRegistry.insertFluid(key, allowed, true) : 0L;
        long remaining = allowed - external;
        long internal = remaining > 0L ? insertFluidPool(key, remaining, false) : 0L;
        remaining -= internal;
        if (buses && remaining > 0L) {
            external += StorageBusRegistry.insertFluid(key, remaining, false);
        }
        return external + internal;
    }

    /** 直接进池子，不经过存储总线（主动抽入 / 回滚用），见 {@link #insertItemDirect}。 */
    public long insertFluidDirect(net.minecraftforge.fluids.FluidStack stack, long amount) {
        return insertFluidDirect(FluidKey.of(stack), amount);
    }

    public long insertFluidDirect(FluidKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        long room = fluidSpaceLeft(key);
        if (room <= 0L) return 0L;
        return insertFluidPool(key, Math.min(amount, room), true);
    }

    public long insertFluidManualDirect(FluidKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;
        long room = fluidManualSpaceLeft(key);
        if (room <= 0L) return 0L;
        return insertFluidPool(key, Math.min(amount, room), false);
    }

    private synchronized long insertFluidPool(FluidKey key, long amount, boolean obeyLimit) {
        if (key == null || amount <= 0L) return 0L;

        long have = fluidAmountForLimit(key);
        long ceiling = obeyLimit ? limitOf(fluidLimits, key) : MAX_AMOUNT;
        long space = Math.max(0L, ceiling - have) - craftingFluids.getOrDefault(key, 0L);
        if (space <= 0L) return 0L;

        long accepted = Math.min(amount, space);
        Long existing = fluids.get(key);
        long internal = existing == null ? 0L : existing.longValue();
        fluids.put(key, saturatingAdd(internal, accepted));
        fluidTotal = saturatingAdd(fluidTotal, accepted);
        markChanged();
        if (accepted > 0L) notifyFluidChanged(key);
        return accepted;
    }

    public synchronized long extractFluid(FluidKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;

        long remaining = amount;
        boolean buses = StorageBusRegistry.canTouchWorld();
        long taken = buses ? StorageBusRegistry.extractFluid(key, remaining, true) : 0L;
        remaining -= taken;
        if (remaining > 0L) {
            long internal = extractFluidPool(key, remaining);
            taken += internal;
            remaining -= internal;
        }
        if (buses && remaining > 0L) {
            taken += StorageBusRegistry.extractFluid(key, remaining, false);
        }
        return taken;
    }

    private synchronized long extractFluidPool(FluidKey key, long amount) {
        Long stored = fluids.get(key);
        if (stored == null) return 0L;

        long taken = Math.min(stored, amount);
        long remaining = stored - taken;
        if (remaining <= 0L) {
            fluids.remove(key);
        } else {
            fluids.put(key, remaining);
        }
        fluidTotal = Math.max(0L, fluidTotal - taken);
        markChanged();
        if (taken > 0L) notifyFluidChanged(key);
        return taken;
    }

    // ==================================================================
    // 只读视图 / 统计
    // ==================================================================

    /**
     * @return 物品条目的独立副本，可以安全地在锁外遍历。
     *         包含存储总线带来的可读库存（同一个键会合并数量）。
     */
    public synchronized List<Map.Entry<ItemKey, Long>> snapshotItems() {
        LinkedHashMap<ItemKey, Long> merged = new LinkedHashMap<>(items);
        for (Map.Entry<ItemKey, Long> entry : StorageBusRegistry.snapshotReadableItems()
            .entrySet()) {
            Long existing = merged.get(entry.getKey());
            long base = existing == null ? 0L : existing.longValue();
            merged.put(entry.getKey(), Long.valueOf(saturatingAdd(base, entry.getValue())));
        }
        List<Map.Entry<ItemKey, Long>> result = new ArrayList<>(merged.size());
        for (Map.Entry<ItemKey, Long> entry : merged.entrySet()) {
            result.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        }
        return result;
    }

    public synchronized List<Map.Entry<FluidKey, Long>> snapshotFluids() {
        LinkedHashMap<FluidKey, Long> merged = new LinkedHashMap<>(fluids);
        for (Map.Entry<FluidKey, Long> entry : StorageBusRegistry.snapshotReadableFluids()
            .entrySet()) {
            Long existing = merged.get(entry.getKey());
            long base = existing == null ? 0L : existing.longValue();
            merged.put(entry.getKey(), Long.valueOf(saturatingAdd(base, entry.getValue())));
        }
        List<Map.Entry<FluidKey, Long>> result = new ArrayList<>(merged.size());
        for (Map.Entry<FluidKey, Long> entry : merged.entrySet()) {
            result.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 共享只读快照缓存（IO 节点 / 共享终端的主动搬运轮询用）
    //
    // 每轮搬运都要遍历全部条目，而全服可能有几十上百个节点在同一 tick 做这件事。
    // 条目键是表里的同一批实例、值是不可变 Long，内容只在 revision 变化时才会不同，
    // 所以按 revision 缓存一份共享列表，让所有节点复用同一次拷贝。
    // 调用方把它当只读对象：不改列表、不改条目、拿到的 key 实例不重新加工。
    // ------------------------------------------------------------------

    private List<Map.Entry<ItemKey, Long>> itemSnapshotCache;
    private List<Map.Entry<FluidKey, Long>> fluidSnapshotCache;
    private int itemSnapshotRevision = -1;
    private int fluidSnapshotRevision = -1;

    /**
     * 带 revision 缓存的物品条目快照；内容没变时多个调用方共享同一份。
     *
     * @return 只读列表；调用方不得修改列表或其中的条目
     */
    public synchronized List<Map.Entry<ItemKey, Long>> snapshotItemsCached() {
        int currentRevision = getRevision();
        if (itemSnapshotCache == null || itemSnapshotRevision != currentRevision) {
            itemSnapshotCache = snapshotItems();
            // 先记版本再构建：构建期间总线可能又变了，记旧版本只会让下一次多重建一次，
            // 记新版本则可能把旧内容永久标成最新。
            itemSnapshotRevision = currentRevision;
        }
        return itemSnapshotCache;
    }

    /** 同 {@link #snapshotItemsCached()}，流体侧。 */
    public synchronized List<Map.Entry<FluidKey, Long>> snapshotFluidsCached() {
        int currentRevision = getRevision();
        if (fluidSnapshotCache == null || fluidSnapshotRevision != currentRevision) {
            fluidSnapshotCache = snapshotFluids();
            fluidSnapshotRevision = currentRevision;
        }
        return fluidSnapshotCache;
    }

    /** Captures revision, items and fluids atomically; called by the server-thread read API. */
    synchronized SharedStorageSnapshot snapshotForReadApi(String generation) {
        // 先记版本：构建期间总线可能变化，记旧版本只会让下一次多重建一次。
        int currentRevision = getRevision();
        List<Map.Entry<ItemKey, Long>> itemEntries = snapshotItems();
        List<SharedStorageSnapshot.ItemEntry> itemRows = new ArrayList<>(itemEntries.size());
        for (Map.Entry<ItemKey, Long> entry : itemEntries) {
            itemRows.add(
                new SharedStorageSnapshot.ItemEntry(
                    entry.getKey()
                        .prototype(),
                    entry.getValue()));
        }
        List<Map.Entry<FluidKey, Long>> fluidEntries = snapshotFluids();
        List<SharedStorageSnapshot.FluidEntry> fluidRows = new ArrayList<>(fluidEntries.size());
        for (Map.Entry<FluidKey, Long> entry : fluidEntries) {
            fluidRows.add(
                new SharedStorageSnapshot.FluidEntry(
                    entry.getKey()
                        .prototype(),
                    entry.getValue()));
        }
        SharedStorageReadStatus status = new SharedStorageReadStatus(
            SharedStorageReadStatus.State.READY,
            generation,
            currentRevision);
        return new SharedStorageSnapshot(status, itemRows, fluidRows);
    }

    /** @return 只读视图，仅限已持有本对象锁的场景使用（例如序列化时）。不包含存储总线。 */
    public synchronized Map<ItemKey, Long> itemView() {
        return Collections.unmodifiableMap(items);
    }

    public synchronized Map<FluidKey, Long> fluidView() {
        return Collections.unmodifiableMap(fluids);
    }

    public synchronized int itemTypeCount() {
        int count = items.size();
        for (ItemKey key : StorageBusRegistry.snapshotReadableItems()
            .keySet()) {
            if (!items.containsKey(key)) count++;
        }
        return count;
    }

    public synchronized int fluidTypeCount() {
        int count = fluids.size();
        for (FluidKey key : StorageBusRegistry.snapshotReadableFluids()
            .keySet()) {
            if (!fluids.containsKey(key)) count++;
        }
        return count;
    }

    public synchronized long getItemTotal() {
        return saturatingAdd(itemTotal, StorageBusRegistry.readableItemTotal());
    }

    public synchronized long getFluidTotal() {
        return saturatingAdd(fluidTotal, StorageBusRegistry.readableFluidTotal());
    }

    /**
     * 内容版本号 = 池子版本 + 存储总线聚合版本。
     *
     * <p>
     * 两者都只增不减，所以相加仍然是单调的，可以用作缓存失效水位。
     * 总线版本单独放在 {@link StorageBusRegistry} 里，是为了让总线变化
     * <b>不把共享存储标记成脏</b>：外部容器由自己的方块实体落盘。
     */
    public synchronized int getRevision() {
        return revision + StorageBusRegistry.getRevision();
    }

    /**
     * 存储总线报告「可读库存变了」。
     *
     * <p>
     * 只通知监听器（AE 桥 / 终端增量广播），<b>不碰 {@link #dirty}</b>：
     * 总线里的东西住在相邻容器的方块实体里，由它们自己落盘。
     * 版本号由 {@link StorageBusRegistry} 在聚合库存变化时推进。
     */
    public synchronized void notifyExternalItemChanged(ItemKey key) {
        if (key != null) notifyItemChanged(key);
    }

    public synchronized void notifyExternalFluidChanged(FluidKey key) {
        if (key != null) notifyFluidChanged(key);
    }

    /** 清空全部内容，<b>包括隔离区</b> —— 这是 /futashared clear 的语义，管理员要的就是彻底清掉。 */
    public synchronized void clear() {
        if (items.isEmpty() && fluids.isEmpty()
            && unreadableItems.isEmpty()
            && unreadableFluids.isEmpty()
            && craftingItems.isEmpty()
            && craftingFluids.isEmpty()
            && unreadableCraftingItems.isEmpty()
            && unreadableCraftingFluids.isEmpty()) {
            return;
        }
        items.clear();
        fluids.clear();
        craftingItems.clear();
        craftingFluids.clear();
        unreadableCraftingItems.clear();
        unreadableCraftingFluids.clear();
        craftingEpoch++;
        unreadableItems.clear();
        unreadableFluids.clear();
        itemTotal = 0L;
        fluidTotal = 0L;
        markChanged();
        // 清空是结构性变化，通知监听器走全量重同步
        notifyStorageReplaced();
    }

    // ==================================================================
    // 脏标记（决定要不要写盘）
    // ==================================================================

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized void markClean() {
        dirty = false;
    }

    public synchronized void markDirty() {
        dirty = true;
    }

    private void markChanged() {
        revision++;
        dirty = true;
    }

    // ==================================================================
    // 内容变更监听（AE2 桥用）
    // ==================================================================

    public synchronized void addListener(SharedStorageListener listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public synchronized void removeListener(SharedStorageListener listener) {
        listeners.remove(listener);
    }

    /**
     * 换存档 / reload 时把监听器过继到这个新实例，并通知一次结构性替换。
     *
     * <p>
     * {@code SharedStorageManager} 在 {@code onServerStarted} 和
     * {@code reloadFromDisk} 里都是<b>整个替换</b> storage 实例，监听器要是
     * 留在旧实例上就从此失联了。由 Manager 在替换完成后调用这一个方法即可。
     */
    synchronized void adoptListenersFrom(SharedStorage previous) {
        listeners.addAll(previous.listeners);
        previous.listeners.clear();
        notifyStorageReplaced();
    }

    /** 只在确有生效变更时调用；回调在锁内，见接口注释的约束。 */
    private void notifyItemChanged(ItemKey key) {
        if (listeners.isEmpty()) return;
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i)
                .onItemChanged(key);
        }
    }

    private void notifyFluidChanged(FluidKey key) {
        if (listeners.isEmpty()) return;
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i)
                .onFluidChanged(key);
        }
    }

    private void notifyStorageReplaced() {
        if (listeners.isEmpty()) return;
        for (int i = 0; i < listeners.size(); i++) {
            listeners.get(i)
                .onStorageReplaced();
        }
    }

    // ==================================================================
    // 按物品 / 流体的存量上限
    // ==================================================================

    /**
     * 某个条目的存量上限。
     *
     * <p>
     * <b>语义是「总存量不超过 N」</b>：到了 N 就拒收，但已经存着的东西照常能取出来。
     * 上限只挡写入，永远不删数据 —— 设一个比现有存量更小的上限（比如存了 5000 再设 100），
     * 结果是「不再收，但一个都不会少」，玩家得自己取到 100 以下才会重新开始收。
     *
     * <p>
     * <b>没有限额和限额为 0 是两回事</b>：没有限额 = 不限制（{@link #MAX_AMOUNT}），
     * 限额 0 = 一件都不许再存。界面上要分得清，所以 {@link #hasItemLimit} 单独存在。
     */
    public synchronized long getItemLimit(ItemKey key) {
        return limitOf(itemLimits, key);
    }

    public synchronized long getFluidLimit(FluidKey key) {
        return limitOf(fluidLimits, key);
    }

    /** @return 这个条目是不是被显式设过上限（用于和「没设过」区分开） */
    public synchronized boolean hasItemLimit(ItemKey key) {
        return key != null && itemLimits.containsKey(key);
    }

    public synchronized boolean hasFluidLimit(FluidKey key) {
        return key != null && fluidLimits.containsKey(key);
    }

    /**
     * 设置上限。
     *
     * @param limit 允许的总存量；负数按 0 处理（0 = 不再接受新的）
     */
    public synchronized void setItemLimit(ItemKey key, long limit) {
        if (key == null) return;
        itemLimits.put(key, Math.max(0L, limit));
        markChanged();
    }

    public synchronized void setFluidLimit(FluidKey key, long limit) {
        if (key == null) return;
        fluidLimits.put(key, Math.max(0L, limit));
        markChanged();
    }

    /** 取消上限（恢复成不限制）。注意这不会动存量。 */
    public synchronized void clearItemLimit(ItemKey key) {
        if (key == null) return;
        if (itemLimits.remove(key) != null) markChanged();
    }

    public synchronized void clearFluidLimit(FluidKey key) {
        if (key == null) return;
        if (fluidLimits.remove(key) != null) markChanged();
    }

    /** @return 现有上限表的一份副本（同步给客户端用） */
    public synchronized Map<ItemKey, Long> itemLimitsView() {
        return new LinkedHashMap<>(itemLimits);
    }

    public synchronized Map<FluidKey, Long> fluidLimitsView() {
        return new LinkedHashMap<>(fluidLimits);
    }

    /**
     * 还能再收多少。
     *
     * <p>
     * 自动搬运要用它<b>在取之前</b>先算好目标能收多少，只取那么多 ——
     * 「先取出来再试着存回去，存不下就退回去」有一条失败窗口，而那条窗口一旦
     * 关不上就是丢东西。宁可少搬一点也不要有这个窗口。
     *
     * @return 剩余容量；已达上限返回 0
     */
    public synchronized long itemSpaceLeft(ItemKey key) {
        if (key == null) return 0L;
        return Math.max(
            0L,
            Math.max(0L, limitOf(itemLimits, key) - itemAmountForLimit(key)) - craftingItems.getOrDefault(key, 0L));
    }

    public synchronized long fluidSpaceLeft(FluidKey key) {
        if (key == null) return 0L;
        return Math.max(
            0L,
            Math.max(0L, limitOf(fluidLimits, key) - fluidAmountForLimit(key)) - craftingFluids.getOrDefault(key, 0L));
    }

    public synchronized long itemManualSpaceLeft(ItemKey key) {
        if (key == null) return 0L;
        return Math.max(0L, MAX_AMOUNT - itemAmountForLimit(key) - craftingItems.getOrDefault(key, 0L));
    }

    public synchronized long fluidManualSpaceLeft(FluidKey key) {
        if (key == null) return 0L;
        return Math.max(0L, MAX_AMOUNT - fluidAmountForLimit(key) - craftingFluids.getOrDefault(key, 0L));
    }

    /** A real withdrawal with a reserved rollback slot; no storage lock is held across AE callbacks. */
    public synchronized CraftingReservation reserveItemForCrafting(ItemKey key, long requested) {
        if (key == null || requested <= 0L || craftingItems.containsKey(key)) return null;
        long amount = Math.min(requested, getItemAmount(key));
        if (amount <= 0L) return null;
        // 总线可能在这一刻刚好被别的机器抽空：按实际取到的量托管，
        // 否则 finish(0) 会把没取到的部分也从账上抹掉。
        long taken = extractItem(key, amount);
        if (taken <= 0L) return null;
        craftingItems.put(key, taken);
        return new CraftingReservation(key, null, taken, craftingEpoch);
    }

    public synchronized CraftingReservation reserveFluidForCrafting(FluidKey key, long requested) {
        if (key == null || requested <= 0L || craftingFluids.containsKey(key)) return null;
        long amount = Math.min(requested, getFluidAmount(key));
        if (amount <= 0L) return null;
        long taken = extractFluid(key, amount);
        if (taken <= 0L) return null;
        craftingFluids.put(key, taken);
        return new CraftingReservation(null, key, taken, craftingEpoch);
    }

    public final class CraftingReservation {

        private final ItemKey item;
        private final FluidKey fluid;
        private final long amount;
        private final long epoch;
        private boolean finished;

        private CraftingReservation(ItemKey item, FluidKey fluid, long amount, long epoch) {
            this.item = item;
            this.fluid = fluid;
            this.amount = amount;
            this.epoch = epoch;
        }

        public long getAmount() {
            return amount;
        }

        /** Returned final outputs are legitimate even if the CPU completed the task and kept nothing. */
        public void finish(long returned) {
            synchronized (SharedStorage.this) {
                if (finished || epoch != craftingEpoch || returned < 0L || returned > amount) {
                    throw new IllegalStateException("Invalid or stale crafting reservation");
                }
                if (item != null) {
                    if (!Long.valueOf(amount)
                        .equals(craftingItems.get(item))) throw new IllegalStateException("Missing item escrow");
                    craftingItems.remove(item);
                    if (insertItemManual(item, returned) != returned)
                        throw new IllegalStateException("Item rollback capacity lost");
                } else {
                    if (!Long.valueOf(amount)
                        .equals(craftingFluids.get(fluid))) throw new IllegalStateException("Missing fluid escrow");
                    craftingFluids.remove(fluid);
                    if (insertFluidManual(fluid, returned) != returned)
                        throw new IllegalStateException("Fluid rollback capacity lost");
                }
                finished = true;
                markChanged();
            }
        }
    }

    public synchronized List<Map.Entry<ItemKey, Long>> snapshotCraftingItems() {
        List<Map.Entry<ItemKey, Long>> result = new ArrayList<>();
        for (Map.Entry<ItemKey, Long> entry : craftingItems.entrySet())
            result.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        return result;
    }

    public synchronized List<Map.Entry<FluidKey, Long>> snapshotCraftingFluids() {
        List<Map.Entry<FluidKey, Long>> result = new ArrayList<>();
        for (Map.Entry<FluidKey, Long> entry : craftingFluids.entrySet())
            result.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        return result;
    }

    /** OP-only recovery after checking the receiving CPU; never automatically refund uncertain transfers. */
    public synchronized void refundCraftingEscrow() {
        for (Map.Entry<ItemKey, Long> entry : snapshotCraftingItems()) {
            craftingItems.remove(entry.getKey());
            long returned = insertItemManual(entry.getKey(), entry.getValue());
            if (returned < entry.getValue()) craftingItems.put(entry.getKey(), entry.getValue() - returned);
        }
        for (Map.Entry<FluidKey, Long> entry : snapshotCraftingFluids()) {
            craftingFluids.remove(entry.getKey());
            long returned = insertFluidManual(entry.getKey(), entry.getValue());
            if (returned < entry.getValue()) craftingFluids.put(entry.getKey(), entry.getValue() - returned);
        }
        craftingEpoch++;
        markChanged();
    }

    private static <K> long limitOf(Map<K, Long> limits, K key) {
        Long limit = key == null ? null : limits.get(key);
        return limit == null ? MAX_AMOUNT : limit;
    }

    // ==================================================================
    // 序列化
    // ==================================================================

    public synchronized NBTTagCompound writeToNbt() {
        NBTTagCompound root = new NBTTagCompound();
        // Normal saves remain v2-compatible; only unresolved transfers need the escrow-aware v3 reader.
        boolean escrow = !craftingItems.isEmpty() || !craftingFluids.isEmpty()
            || !unreadableCraftingItems.isEmpty()
            || !unreadableCraftingFluids.isEmpty();
        root.setInteger("version", escrow ? FORMAT_VERSION : 2);

        NBTTagList itemList = new NBTTagList();
        for (Map.Entry<ItemKey, Long> entry : items.entrySet()) {
            NBTTagCompound tag = entry.getKey()
                .writeToNbt();
            tag.setLong("amount", entry.getValue());
            itemList.appendTag(tag);
        }
        // 把读不出来的条目原样写回去，见 readFromNbt 的说明
        for (NBTTagCompound quarantined : unreadableItems) {
            itemList.appendTag(quarantined.copy());
        }
        root.setTag("items", itemList);

        NBTTagList fluidList = new NBTTagList();
        for (Map.Entry<FluidKey, Long> entry : fluids.entrySet()) {
            NBTTagCompound tag = entry.getKey()
                .writeToNbt();
            tag.setLong("amount", entry.getValue());
            fluidList.appendTag(tag);
        }
        for (NBTTagCompound quarantined : unreadableFluids) {
            fluidList.appendTag(quarantined.copy());
        }
        root.setTag("fluids", fluidList);

        // 上限表（格式版本 2 新增）。旧存档没有这两个 tag，读出来就是空表 = 不限制
        NBTTagList itemLimitList = new NBTTagList();
        for (Map.Entry<ItemKey, Long> entry : itemLimits.entrySet()) {
            NBTTagCompound tag = entry.getKey()
                .writeToNbt();
            tag.setLong("limit", entry.getValue());
            itemLimitList.appendTag(tag);
        }
        root.setTag("itemLimits", itemLimitList);

        NBTTagList fluidLimitList = new NBTTagList();
        for (Map.Entry<FluidKey, Long> entry : fluidLimits.entrySet()) {
            NBTTagCompound tag = entry.getKey()
                .writeToNbt();
            tag.setLong("limit", entry.getValue());
            fluidLimitList.appendTag(tag);
        }
        root.setTag("fluidLimits", fluidLimitList);

        NBTTagList heldItems = new NBTTagList();
        for (Map.Entry<ItemKey, Long> entry : craftingItems.entrySet()) {
            NBTTagCompound tag = entry.getKey()
                .writeToNbt();
            tag.setLong("amount", entry.getValue());
            heldItems.appendTag(tag);
        }
        for (NBTTagCompound tag : unreadableCraftingItems) heldItems.appendTag(tag.copy());
        root.setTag("craftingItems", heldItems);
        NBTTagList heldFluids = new NBTTagList();
        for (Map.Entry<FluidKey, Long> entry : craftingFluids.entrySet()) {
            NBTTagCompound tag = entry.getKey()
                .writeToNbt();
            tag.setLong("amount", entry.getValue());
            heldFluids.appendTag(tag);
        }
        for (NBTTagCompound tag : unreadableCraftingFluids) heldFluids.appendTag(tag.copy());
        root.setTag("craftingFluids", heldFluids);

        return root;
    }

    /** @return 存档格式版本；缺省视为 {@link #FORMAT_VERSION} */
    public static int readFormatVersion(NBTTagCompound root) {
        if (root == null || !root.hasKey("version")) return FORMAT_VERSION;
        return root.getInteger("version");
    }

    /**
     * 从 NBT 恢复内容。<b>会先清空现有内容</b>。
     *
     * <p>
     * 解析不出来的条目（对应的模组被卸载了、流体没注册名字了……）不会直接丢掉，
     * 而是<b>原样留在 {@code unreadable*} 列表里</b>，写盘时再原封不动写回去。
     *
     * <p>
     * 这一点很关键：如果读不出来就丢，那么「临时卸载一个模组 → 启动一次服务器」
     * 就足以永久抹掉那个模组的所有物品 —— 内存里没了，第一次自动保存覆盖正式文件，
     * 第二次保存把备份也轮换成新的空文件，于是连 {@code .bak} 都救不回来了。
     * 留着原始 NBT 的话，把模组装回去，东西就还在。
     *
     * @return 本次成功读入的条目数
     */
    public synchronized int readFromNbt(NBTTagCompound root) {
        items.clear();
        fluids.clear();
        craftingItems.clear();
        craftingFluids.clear();
        unreadableCraftingItems.clear();
        unreadableCraftingFluids.clear();
        craftingEpoch++;
        itemLimits.clear();
        fluidLimits.clear();
        unreadableItems.clear();
        unreadableFluids.clear();
        itemTotal = 0L;
        fluidTotal = 0L;

        int loaded = 0;
        if (root == null) {
            markChanged();
            return 0;
        }

        NBTTagList itemList = root.getTagList("items", 10);
        for (int i = 0; i < itemList.tagCount(); i++) {
            NBTTagCompound tag = itemList.getCompoundTagAt(i);
            long amount = tag.getLong("amount");
            ItemKey key = ItemKey.readFromNbt(tag);
            if (key == null || amount <= 0L) {
                if (tag.hasKey("item")) unreadableItems.add((NBTTagCompound) tag.copy());
                continue;
            }
            mergeItem(key, amount);
            loaded++;
        }

        NBTTagList fluidList = root.getTagList("fluids", 10);
        for (int i = 0; i < fluidList.tagCount(); i++) {
            NBTTagCompound tag = fluidList.getCompoundTagAt(i);
            long amount = tag.getLong("amount");
            FluidKey key = FluidKey.readFromNbt(tag);
            if (key == null || amount <= 0L) {
                if (tag.hasKey("fluid")) unreadableFluids.add((NBTTagCompound) tag.copy());
                continue;
            }
            mergeFluid(key, amount);
            loaded++;
        }

        // 上限表（格式版本 2）。旧存档没有这两个 tag，读出来就是空表 = 不限制，
        // 所以 v1 → v2 不需要任何迁移动作。
        //
        // 注意这里<b>不夹取存量</b>：存档里存了 5000、上限写的 100，那就保持 5000 不变，
        // 只是从今往后不再收。读档时按上限删数据就是丢东西，而丢东西是绝对不能做的。
        NBTTagList itemLimitList = root.getTagList("itemLimits", 10);
        for (int i = 0; i < itemLimitList.tagCount(); i++) {
            NBTTagCompound tag = itemLimitList.getCompoundTagAt(i);
            ItemKey key = ItemKey.readFromNbt(tag);
            if (key == null || !tag.hasKey("limit")) continue;
            itemLimits.put(key, Math.max(0L, tag.getLong("limit")));
        }

        NBTTagList fluidLimitList = root.getTagList("fluidLimits", 10);
        for (int i = 0; i < fluidLimitList.tagCount(); i++) {
            NBTTagCompound tag = fluidLimitList.getCompoundTagAt(i);
            FluidKey key = FluidKey.readFromNbt(tag);
            if (key == null || !tag.hasKey("limit")) continue;
            fluidLimits.put(key, Math.max(0L, tag.getLong("limit")));
        }

        NBTTagList heldItems = root.getTagList("craftingItems", 10);
        for (int i = 0; i < heldItems.tagCount(); i++) {
            NBTTagCompound tag = heldItems.getCompoundTagAt(i);
            ItemKey key = ItemKey.readFromNbt(tag);
            long amount = tag.getLong("amount");
            if (key == null || amount <= 0L) {
                unreadableCraftingItems.add((NBTTagCompound) tag.copy());
                continue;
            }
            craftingItems.put(key, saturatingAdd(craftingItems.getOrDefault(key, 0L), amount));
        }
        NBTTagList heldFluids = root.getTagList("craftingFluids", 10);
        for (int i = 0; i < heldFluids.tagCount(); i++) {
            NBTTagCompound tag = heldFluids.getCompoundTagAt(i);
            FluidKey key = FluidKey.readFromNbt(tag);
            long amount = tag.getLong("amount");
            if (key == null || amount <= 0L) {
                unreadableCraftingFluids.add((NBTTagCompound) tag.copy());
                continue;
            }
            craftingFluids.put(key, saturatingAdd(craftingFluids.getOrDefault(key, 0L), amount));
        }

        markChanged();
        return loaded;
    }

    /** @return 因为对应模组缺失而暂时读不出来的物品条目数 */
    public synchronized int getUnreadableItemCount() {
        return unreadableItems.size();
    }

    public synchronized int getUnreadableFluidCount() {
        return unreadableFluids.size();
    }

    private static long saturatingAdd(long a, long b) {
        long sum = a + b;
        // 溢出检测：同号相加却变号，说明溢出了
        return ((a ^ sum) & (b ^ sum)) < 0L ? Long.MAX_VALUE : sum;
    }
}
