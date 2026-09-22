package com.futa_gtnh.shared;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

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
    public static final int FORMAT_VERSION = 1;

    private final LinkedHashMap<ItemKey, Long> items = new LinkedHashMap<>();
    private final LinkedHashMap<FluidKey, Long> fluids = new LinkedHashMap<>();

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
     * 存入物品。
     *
     * @param stack  物品来源，只取它的「种类」，{@code stackSize} 被忽略
     * @param amount 要存的数量
     * @return 实际存进去的数量；因为饱和上限而没存进去的部分需要调用方自行处理
     */
    public synchronized long insertItem(net.minecraft.item.ItemStack stack, long amount) {
        return insertItem(ItemKey.of(stack), amount);
    }

    public synchronized long insertItem(ItemKey key, long amount) {
        if (key == null || amount <= 0L) return 0L;

        long have = getItemAmount(key);
        long space = MAX_AMOUNT - have;
        if (space <= 0L) return 0L;

        long accepted = Math.min(amount, space);
        items.put(key, have + accepted);
        // 用饱和加法：合计值本身溢出变负的话，/futashared info 会打印出负数，
        // 之后每一次取出还会让这个负数继续漂移
        itemTotal = saturatingAdd(itemTotal, accepted);
        markChanged();
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
        if (key == null || amount <= 0L) return 0L;

        long have = getFluidAmount(key);
        long space = MAX_AMOUNT - have;
        if (space <= 0L) return 0L;

        long accepted = Math.min(amount, space);
        fluids.put(key, have + accepted);
        fluidTotal = saturatingAdd(fluidTotal, accepted);
        markChanged();
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
        return taken;
    }

    // ==================================================================
    // 只读视图 / 统计
    // ==================================================================

    /** @return 物品条目的独立副本，可以安全地在锁外遍历 */
    public synchronized List<Map.Entry<ItemKey, Long>> snapshotItems() {
        return new ArrayList<>(items.entrySet());
    }

    public synchronized List<Map.Entry<FluidKey, Long>> snapshotFluids() {
        return new ArrayList<>(fluids.entrySet());
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
