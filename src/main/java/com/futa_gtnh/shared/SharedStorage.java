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
 * 调进来，因此所有会改状态的方法都加了 {@code synchronized}；
 * 需要遍历时请用 {@link #snapshotItems()} / {@link #snapshotFluids()}，
 * 它们返回独立副本，调用方在锁外遍历不会踩到 {@code ConcurrentModificationException}。
 */
public class SharedStorage {

    /** 单个条目的数量上限。再往上加会饱和到这个值，多出来的部分原样退回。 */
    public static final long MAX_AMOUNT = Long.MAX_VALUE;

    /** 存档格式版本。将来改结构时靠它做迁移。 */
    public static final int FORMAT_VERSION = 2;

    private final LinkedHashMap<ItemKey, Long> items = new LinkedHashMap<>();
    private final LinkedHashMap<FluidKey, Long> fluids = new LinkedHashMap<>();

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
        return amount == null ? 0L : amount;
    }

    public synchronized long getItemAmount(net.minecraft.item.ItemStack stack) {
        return getItemAmount(ItemKey.of(stack));
    }

    public synchronized boolean hasItem(ItemKey key) {
        return key != null && items.containsKey(key);
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
    public synchronized long insertItem(net.minecraft.item.ItemStack stack, long amount) {
        return insertItem(ItemKey.of(stack), amount);
    }

    public synchronized long insertItem(ItemKey key, long amount) {
        return insertItemInternal(key, amount, true);
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
    public synchronized long insertItemManual(net.minecraft.item.ItemStack stack, long amount) {
        return insertItemManual(ItemKey.of(stack), amount);
    }

    public synchronized long insertItemManual(ItemKey key, long amount) {
        return insertItemInternal(key, amount, false);
    }

    private long insertItemInternal(ItemKey key, long amount, boolean obeyLimit) {
        if (key == null || amount <= 0L) return 0L;

        long have = getItemAmount(key);
        // 手动存放时上限不参与，但全局上限仍然要守：long 加爆了会让合计值变负
        long ceiling = obeyLimit ? limitOf(itemLimits, key) : MAX_AMOUNT;
        long space = ceiling - have;
        if (space <= 0L) return 0L;

        long accepted = Math.min(amount, space);
        items.put(key, have + accepted);
        // 用饱和加法：合计值本身溢出变负的话，/futashared info 会打印出负数，
        // 之后每一次取出还会让这个负数继续漂移
        itemTotal = saturatingAdd(itemTotal, accepted);
        markChanged();
        if (accepted > 0L) notifyItemChanged(key);
        return accepted;
    }

    /**
     * 取出物品。
     *
     * @return 实际取出的数量，可能少于请求量（存量不足）。条目取空后会从表里删掉。
     */
    public synchronized long extractItem(ItemKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;

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
        long have = getItemAmount(key);
        items.put(key, saturatingAdd(have, amount));
        itemTotal = saturatingAdd(itemTotal, amount);
    }

    private void mergeFluid(FluidKey key, long amount) {
        long have = getFluidAmount(key);
        fluids.put(key, saturatingAdd(have, amount));
        fluidTotal = saturatingAdd(fluidTotal, amount);
    }

    // ==================================================================
    // 流体（单位：毫巴 mB，和 GT 的 L 是 1:1）
    // ==================================================================

    public synchronized long getFluidAmount(FluidKey key) {
        if (key == null) return 0L;
        Long amount = fluids.get(key);
        return amount == null ? 0L : amount;
    }

    public synchronized long getFluidAmount(net.minecraftforge.fluids.FluidStack stack) {
        return getFluidAmount(FluidKey.of(stack));
    }

    public synchronized boolean hasFluid(FluidKey key) {
        return key != null && fluids.containsKey(key);
    }

    public synchronized long insertFluid(net.minecraftforge.fluids.FluidStack stack, long amount) {
        return insertFluid(FluidKey.of(stack), amount);
    }

    public synchronized long insertFluid(FluidKey key, long amount) {
        return insertFluidInternal(key, amount, true);
    }

    /** 玩家手动倒入流体，允许超出上限（同 {@link #insertItemManual}）。 */
    public synchronized long insertFluidManual(FluidKey key, long amount) {
        return insertFluidInternal(key, amount, false);
    }

    private long insertFluidInternal(FluidKey key, long amount, boolean obeyLimit) {
        if (key == null || amount <= 0L) return 0L;

        long have = getFluidAmount(key);
        long ceiling = obeyLimit ? limitOf(fluidLimits, key) : MAX_AMOUNT;
        long space = ceiling - have;
        if (space <= 0L) return 0L;

        long accepted = Math.min(amount, space);
        fluids.put(key, have + accepted);
        fluidTotal = saturatingAdd(fluidTotal, accepted);
        markChanged();
        if (accepted > 0L) notifyFluidChanged(key);
        return accepted;
    }

    public synchronized long extractFluid(FluidKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;

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

    /** @return 物品条目的独立副本，可以安全地在锁外遍历 */
    public synchronized List<Map.Entry<ItemKey, Long>> snapshotItems() {
        List<Map.Entry<ItemKey, Long>> result = new ArrayList<>(items.size());
        for (Map.Entry<ItemKey, Long> entry : items.entrySet()) {
            result.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        }
        return result;
    }

    public synchronized List<Map.Entry<FluidKey, Long>> snapshotFluids() {
        List<Map.Entry<FluidKey, Long>> result = new ArrayList<>(fluids.size());
        for (Map.Entry<FluidKey, Long> entry : fluids.entrySet()) {
            result.add(new AbstractMap.SimpleImmutableEntry<>(entry));
        }
        return result;
    }

    /** Captures revision, items and fluids atomically; called by the server-thread read API. */
    synchronized SharedStorageSnapshot snapshotForReadApi(String generation) {
        List<SharedStorageSnapshot.ItemEntry> itemRows = new ArrayList<>(items.size());
        for (Map.Entry<ItemKey, Long> entry : items.entrySet()) {
            itemRows.add(
                new SharedStorageSnapshot.ItemEntry(
                    entry.getKey()
                        .prototype(),
                    entry.getValue()));
        }
        List<SharedStorageSnapshot.FluidEntry> fluidRows = new ArrayList<>(fluids.size());
        for (Map.Entry<FluidKey, Long> entry : fluids.entrySet()) {
            fluidRows.add(
                new SharedStorageSnapshot.FluidEntry(
                    entry.getKey()
                        .prototype(),
                    entry.getValue()));
        }
        SharedStorageReadStatus status = new SharedStorageReadStatus(
            SharedStorageReadStatus.State.READY,
            generation,
            revision);
        return new SharedStorageSnapshot(status, itemRows, fluidRows);
    }

    /** @return 只读视图，仅限已持有本对象锁的场景使用（例如序列化时） */
    public synchronized Map<ItemKey, Long> itemView() {
        return Collections.unmodifiableMap(items);
    }

    public synchronized Map<FluidKey, Long> fluidView() {
        return Collections.unmodifiableMap(fluids);
    }

    public synchronized int itemTypeCount() {
        return items.size();
    }

    public synchronized int fluidTypeCount() {
        return fluids.size();
    }

    public synchronized long getItemTotal() {
        return itemTotal;
    }

    public synchronized long getFluidTotal() {
        return fluidTotal;
    }

    public synchronized int getRevision() {
        return revision;
    }

    /** 清空全部内容，<b>包括隔离区</b> —— 这是 /futashared clear 的语义，管理员要的就是彻底清掉。 */
    public synchronized void clear() {
        if (items.isEmpty() && fluids.isEmpty() && unreadableItems.isEmpty() && unreadableFluids.isEmpty()) {
            return;
        }
        items.clear();
        fluids.clear();
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
        return Math.max(0L, limitOf(itemLimits, key) - getItemAmount(key));
    }

    public synchronized long fluidSpaceLeft(FluidKey key) {
        if (key == null) return 0L;
        return Math.max(0L, limitOf(fluidLimits, key) - getFluidAmount(key));
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
        root.setInteger("version", FORMAT_VERSION);

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
