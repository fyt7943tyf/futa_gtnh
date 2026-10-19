package com.futa_gtnh.storagebus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * 共享存储总线（Storage Bus）的配置：目标面、优先级、访问模式与筛选。
 *
 * <p>
 * 语义刻意对齐 AE2 的 ME 存储总线：
 * <ul>
 * <li><b>访问模式</b>：读写 / 只读 / 只写。只读总线会出现在共享终端的库存里、
 * 也能被取出，但共享存储不会往它里面写；只写反过来。</li>
 * <li><b>优先级</b>：写入时高优先级先填，抽取时低优先级先抽。共享存储池本身
 * 固定按 0 处理 —— 于是正优先级是「缓冲箱」，负优先级是「先掏空的箱子」。</li>
 * <li><b>筛选</b>：空白名单 = 全部允许；白名单只允许列出的条目；黑名单反过来。
 * 匹配方式有精确（物品 + 元数据 + NBT）、模糊（忽略 NBT）和矿辞（按矿物词典名
 * 相交，物品侧）。</li>
 * </ul>
 *
 * <p>
 * 筛选条目按固定槽位保存（物品 9 格、流体 9 格）。固定槽位而不是压缩列表，
 * 是为了界面上的「第 3 格」永远对应同一个槽：玩家放进去的东西不会因为删掉
 * 前面一格就整体左移。
 */
public final class StorageBusConfig {

    /** 每类筛选的槽位数。AE2 的存储总线是 9 格；这里物品和流体各 9 格。 */
    public static final int FILTER_SLOTS = 9;

    /** 优先级的硬上限。AE2 里是 int，但界面/存档没有理由接受极端值。 */
    public static final int MAX_PRIORITY = 1_000_000;

    /** 这条总线允许共享存储怎么访问它。 */
    public enum AccessMode {

        /** 可读可写（默认）。 */
        READ_WRITE,
        /** 只能从这条总线取东西；共享存储不会往里写。 */
        READ_ONLY,
        /** 只能往这条总线写东西；它的内容不会出现在共享终端的库存里。 */
        WRITE_ONLY;

        public boolean canRead() {
            return this == READ_WRITE || this == READ_ONLY;
        }

        public boolean canWrite() {
            return this == READ_WRITE || this == WRITE_ONLY;
        }

        public AccessMode next() {
            AccessMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum FilterMode {

        /** 只允许列出的条目（空白名单 = 全部允许）。 */
        WHITELIST,
        /** 拒绝列出的条目（空黑名单 = 全部允许）。 */
        BLACKLIST;

        public FilterMode next() {
            return this == WHITELIST ? BLACKLIST : WHITELIST;
        }
    }

    public enum MatchMode {

        /** 物品 + 元数据 + NBT 完全一致。 */
        EXACT,
        /** 物品 + 元数据一致，忽略 NBT。 */
        FUZZY,
        /** 按矿物词典名相交匹配（流体没有矿辞，退回精确匹配）。 */
        ORE_DICT;

        public MatchMode next() {
            MatchMode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    private ForgeDirection targetSide = ForgeDirection.UNKNOWN;
    private int priority;
    private AccessMode access = AccessMode.READ_WRITE;
    private FilterMode filterMode = FilterMode.WHITELIST;
    private MatchMode matchMode = MatchMode.EXACT;

    private final ItemKey[] itemFilters = new ItemKey[FILTER_SLOTS];
    private final FluidKey[] fluidFilters = new FluidKey[FILTER_SLOTS];

    /** 配置每变一次加一：匹配缓存和界面刷新都靠它失效。 */
    private int revision = 1;

    public int getRevision() {
        return revision;
    }

    // ==================================================================
    // 目标面 / 基本设置
    // ==================================================================

    public ForgeDirection getTargetSide() {
        return targetSide;
    }

    public void setTargetSide(ForgeDirection side) {
        ForgeDirection next = side == null ? ForgeDirection.UNKNOWN : side;
        if (next == targetSide) return;
        targetSide = next;
        touch();
    }

    public int getPriority() {
        return priority;
    }

    /** @param value 会被夹到 ±{@link #MAX_PRIORITY} */
    public void setPriority(int value) {
        int next = Math.max(-MAX_PRIORITY, Math.min(MAX_PRIORITY, value));
        if (next == priority) return;
        priority = next;
        touch();
    }

    public AccessMode getAccess() {
        return access;
    }

    public void setAccess(AccessMode mode) {
        if (mode == null || mode == access) return;
        access = mode;
        touch();
    }

    public FilterMode getFilterMode() {
        return filterMode;
    }

    public void setFilterMode(FilterMode mode) {
        if (mode == null || mode == filterMode) return;
        filterMode = mode;
        touch();
    }

    public MatchMode getMatchMode() {
        return matchMode;
    }

    public void setMatchMode(MatchMode mode) {
        if (mode == null || mode == matchMode) return;
        matchMode = mode;
        touch();
    }

    // ==================================================================
    // 筛选槽位
    // ==================================================================

    public ItemKey getItemFilter(int slot) {
        return slot >= 0 && slot < FILTER_SLOTS ? itemFilters[slot] : null;
    }

    public FluidKey getFluidFilter(int slot) {
        return slot >= 0 && slot < FILTER_SLOTS ? fluidFilters[slot] : null;
    }

    /** @param key null = 清空这个槽位 */
    public void setItemFilter(int slot, ItemKey key) {
        if (slot < 0 || slot >= FILTER_SLOTS) return;
        if (key == null ? itemFilters[slot] == null : key.equals(itemFilters[slot])) return;
        itemFilters[slot] = key;
        touch();
    }

    /** @param key null = 清空这个槽位 */
    public void setFluidFilter(int slot, FluidKey key) {
        if (slot < 0 || slot >= FILTER_SLOTS) return;
        if (key == null ? fluidFilters[slot] == null : key.equals(fluidFilters[slot])) return;
        fluidFilters[slot] = key;
        touch();
    }

    public boolean hasItemFilters() {
        for (ItemKey key : itemFilters) {
            if (key != null) return true;
        }
        return false;
    }

    public boolean hasFluidFilters() {
        for (FluidKey key : fluidFilters) {
            if (key != null) return true;
        }
        return false;
    }

    public boolean hasAnyFilter() {
        return hasItemFilters() || hasFluidFilters();
    }

    public void clearItemFilters() {
        boolean changed = false;
        for (int i = 0; i < FILTER_SLOTS; i++) {
            if (itemFilters[i] != null) {
                itemFilters[i] = null;
                changed = true;
            }
        }
        if (changed) touch();
    }

    public void clearFluidFilters() {
        boolean changed = false;
        for (int i = 0; i < FILTER_SLOTS; i++) {
            if (fluidFilters[i] != null) {
                fluidFilters[i] = null;
                changed = true;
            }
        }
        if (changed) touch();
    }

    public void clearFilters() {
        clearItemFilters();
        clearFluidFilters();
    }

    /** @return 非空物品筛选槽（按槽位顺序），只读副本 */
    public List<ItemKey> itemFilterList() {
        List<ItemKey> result = new ArrayList<>(FILTER_SLOTS);
        for (ItemKey key : itemFilters) {
            if (key != null) result.add(key);
        }
        return result;
    }

    /** @return 非空流体筛选槽（按槽位顺序），只读副本 */
    public List<FluidKey> fluidFilterList() {
        List<FluidKey> result = new ArrayList<>(FILTER_SLOTS);
        for (FluidKey key : fluidFilters) {
            if (key != null) result.add(key);
        }
        return result;
    }

    // ==================================================================
    // 匹配
    // ==================================================================

    private final Map<ItemKey, Boolean> itemMatchCache = new HashMap<>();
    private int itemMatchCacheRevision = -1;
    private final Map<FluidKey, Boolean> fluidMatchCache = new HashMap<>();
    private int fluidMatchCacheRevision = -1;

    /**
     * @return 这条总线是否允许共享存储看见/存取这个物品。
     *         空白名单或空黑名单都返回 true。
     */
    public boolean allowsItem(ItemKey key) {
        if (key == null) return false;
        if (!hasItemFilters()) return true;
        if (itemMatchCacheRevision != revision) {
            itemMatchCache.clear();
            itemMatchCacheRevision = revision;
        }
        Boolean cached = itemMatchCache.get(key);
        if (cached != null) return cached.booleanValue();

        boolean listed = matchesItemFilter(key);
        boolean allowed = filterMode == FilterMode.WHITELIST ? listed : !listed;
        itemMatchCache.put(key, Boolean.valueOf(allowed));
        return allowed;
    }

    private boolean matchesItemFilter(ItemKey key) {
        for (ItemKey filter : itemFilters) {
            if (filter == null) continue;
            if (matchesItemFilter(key, filter)) return true;
        }
        return false;
    }

    private boolean matchesItemFilter(ItemKey key, ItemKey filter) {
        if (matchMode == MatchMode.FUZZY) {
            return filter.getItem() == key.getItem() && filter.getMeta() == key.getMeta();
        }
        if (filter.equals(key)) return true;
        if (matchMode != MatchMode.ORE_DICT) return false;

        // 精确命中已经在上面返回了；矿辞模式再按「名字相交」放宽。
        Set<String> targetNames = oreNamesOf(key);
        if (targetNames.isEmpty()) return false;
        for (String name : oreNamesOf(filter)) {
            if (targetNames.contains(name)) return true;
        }
        return false;
    }

    /**
     * @return 这条总线是否允许共享存储看见/存取这种流体。
     */
    public boolean allowsFluid(FluidKey key) {
        if (key == null) return false;
        if (!hasFluidFilters()) return true;
        if (fluidMatchCacheRevision != revision) {
            fluidMatchCache.clear();
            fluidMatchCacheRevision = revision;
        }
        Boolean cached = fluidMatchCache.get(key);
        if (cached != null) return cached.booleanValue();

        boolean listed = matchesFluidFilter(key);
        boolean allowed = filterMode == FilterMode.WHITELIST ? listed : !listed;
        fluidMatchCache.put(key, Boolean.valueOf(allowed));
        return allowed;
    }

    private boolean matchesFluidFilter(FluidKey key) {
        for (FluidKey filter : fluidFilters) {
            if (filter == null) continue;
            if (matchMode == MatchMode.FUZZY) {
                if (filter.getFluid() == key.getFluid()) return true;
            } else if (filter.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /** @return 物品注册的矿辞名；查不到时返回空集合（不缓存异常结果） */
    private static Set<String> oreNamesOf(ItemKey key) {
        Set<String> result = new HashSet<>();
        try {
            ItemStack stack = key.prototype();
            for (int id : OreDictionary.getOreIDs(stack)) {
                String name = OreDictionary.getOreName(id);
                if (name != null && !name.isEmpty()) result.add(name);
            }
        } catch (Throwable ignored) {
            // 某些模组的物品在矿辞查询时会抛异常；匹配失败比带崩存储总线好
        }
        return result;
    }

    private void touch() {
        revision++;
    }

    // ==================================================================
    // 存档 / 网络
    // ==================================================================

    public NBTTagCompound writeToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setByte("target", (byte) targetSide.ordinal());
        tag.setInteger("priority", priority);
        tag.setByte("access", (byte) access.ordinal());
        tag.setByte("filterMode", (byte) filterMode.ordinal());
        tag.setByte("matchMode", (byte) matchMode.ordinal());

        NBTTagList items = new NBTTagList();
        for (ItemKey key : itemFilters) {
            items.appendTag(key == null ? new NBTTagCompound() : key.writeToNbt());
        }
        tag.setTag("items", items);

        NBTTagList fluids = new NBTTagList();
        for (FluidKey key : fluidFilters) {
            fluids.appendTag(key == null ? new NBTTagCompound() : key.writeToNbt());
        }
        tag.setTag("fluids", fluids);
        return tag;
    }

    /**
     * 从 NBT 读取整份配置。<b>所有值都会被夹回合法范围</b> —— 客户端发上来的
     * 那份也走这里，所以伪造一个越界优先级/非法枚举不会落到方块实体里。
     */
    public void readFromNbt(NBTTagCompound tag) {
        int oldRevision = revision;
        targetSide = ForgeDirection.UNKNOWN;
        priority = 0;
        access = AccessMode.READ_WRITE;
        filterMode = FilterMode.WHITELIST;
        matchMode = MatchMode.EXACT;
        for (int i = 0; i < FILTER_SLOTS; i++) {
            itemFilters[i] = null;
            fluidFilters[i] = null;
        }

        if (tag != null) {
            int target = tag.getByte("target");
            if (target >= 0 && target < ForgeDirection.VALID_DIRECTIONS.length) {
                targetSide = ForgeDirection.getOrientation(target);
            }
            priority = Math.max(-MAX_PRIORITY, Math.min(MAX_PRIORITY, tag.getInteger("priority")));

            int accessOrdinal = tag.getByte("access");
            if (accessOrdinal >= 0 && accessOrdinal < AccessMode.values().length) {
                access = AccessMode.values()[accessOrdinal];
            }
            int filterOrdinal = tag.getByte("filterMode");
            if (filterOrdinal >= 0 && filterOrdinal < FilterMode.values().length) {
                filterMode = FilterMode.values()[filterOrdinal];
            }
            int matchOrdinal = tag.getByte("matchMode");
            if (matchOrdinal >= 0 && matchOrdinal < MatchMode.values().length) {
                matchMode = MatchMode.values()[matchOrdinal];
            }

            readItemFilters(tag.getTagList("items", 10));
            readFluidFilters(tag.getTagList("fluids", 10));
        }

        // 即使读出来的内容完全一样也要让缓存失效：NBT 可能来自网络，
        // 不能假设调用方没有改过别的东西。
        revision = oldRevision + 1;
    }

    private void readItemFilters(NBTTagList list) {
        for (int i = 0; i < list.tagCount() && i < FILTER_SLOTS; i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            if (entry == null || entry.hasNoTags()) continue;
            ItemKey key = ItemKey.readFromNbt(entry);
            if (key != null) itemFilters[i] = key;
        }
    }

    private void readFluidFilters(NBTTagList list) {
        for (int i = 0; i < list.tagCount() && i < FILTER_SLOTS; i++) {
            NBTTagCompound entry = list.getCompoundTagAt(i);
            if (entry == null || entry.hasNoTags()) continue;
            FluidKey key = FluidKey.readFromNbt(entry);
            if (key != null) fluidFilters[i] = key;
        }
    }

    /**
     * 从物品栈里解析流体筛选键：支持 GT 的流体显示物品，也支持桶/单元这类
     * 装满流体的容器。返回 null 表示这个物品不是「一种流体」。
     */
    public static FluidKey fluidKeyOf(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return null;
        try {
            FluidStack displayed = gregtech.api.util.GTUtility.getFluidFromDisplayStack(stack);
            if (displayed != null && displayed.getFluid() != null) return FluidKey.of(displayed);
        } catch (Throwable ignored) {
            // GT 的显示物品解析失败就继续试普通容器
        }
        try {
            FluidStack contained = net.minecraftforge.fluids.FluidContainerRegistry.getFluidForFilledItem(stack);
            if (contained != null && contained.getFluid() != null) return FluidKey.of(contained);
        } catch (Throwable ignored) {
            // 容器不是流体容器，正常
        }
        return null;
    }
}
