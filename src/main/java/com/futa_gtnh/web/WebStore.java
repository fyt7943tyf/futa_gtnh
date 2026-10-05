package com.futa_gtnh.web;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.client.ClientStorageCache;
import com.futa_gtnh.client.NecharBridge;
import com.futa_gtnh.client.Pinyin;
import com.futa_gtnh.client.StorageViewEntry;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/**
 * 网页用的物品目录：id ↔ 物品、显示名、模组、搜索文本、以及「我现在有多少」。
 *
 * <p>
 * <b>目录来源是 NEI 的物品清单</b>（{@code ItemList.items}），不是创造模式物品栏 ——
 * GTNH 里大量物品根本不在任何创造标签页里（GT 的隐藏元物品、各种中间产物），
 * 而玩家在 NEI 里搜得到它们，所以在网页上也必须搜得到。NEI 自己那份清单
 * 还包含 NBT 变体（附魔书、GT 电池），同样是我们想要的。
 *
 * <p>
 * <b>身份是「物品 + 元数据」</b>，刻意不含 NBT。理由和 NEI 判定
 * {@code areStacksSameTypeCrafting} 一样：配方树要连得起来。
 * 「电动马达（带 NBT 的 GT 元物品）」和配方里写的那个马达，在玩家眼里是同一个东西，
 * 要是把 NBT 算进身份，树会在每一个 GT 元物品上断掉。代价是极少数
 * 「靠 NBT 区分」的输出（附魔书之类）会合并成一个节点，这个取舍是划算的。
 *
 * <p>
 * <b>扫描在客户端线程上分帧做</b>（每 tick 一个预算），和 {@code client/ItemIndex}
 * 同一个套路：几万个物品一次性算拼音搜索串会明显卡一下，摊到几十 tick 里就没感觉了。
 */
public final class WebStore {

    private WebStore() {}

    /** 每 tick 处理多少个物品条目。 */
    private static final int BUILD_BUDGET_PER_TICK = 4000;

    /** 目录上限，纯粹是防御性的（正常 GTNH 在一万到几万之间）。 */
    private static final int MAX_ENTRIES = 400000;

    /** 两次「请服务端重发共享背包快照」之间的最小间隔（毫秒）。 */
    private static final long SNAPSHOT_INTERVAL_MILLIS = 5000L;

    private static final Object LOCK = new Object();

    /**
     * 拼音表的专用锁。
     *
     * <p>
     * {@code Pinyin} 的字典是「第一次用到才读」的，而这里有两个线程会碰它：
     * 客户端线程在分帧建目录，索引线程在给配方里的新物品补登记。
     * 两边都从 {@link #registerLocked} 走，所以在这里串起来就够了 ——
     * 不去改 Pinyin 自己的懒加载，那样会牵动别的调用方。
     */
    private static final Object PINYIN_LOCK = new Object();

    private static final List<ItemStack> STACKS = new ArrayList<>();
    private static final List<String> NAMES = new ArrayList<>();
    private static final List<String> MODS = new ArrayList<>();
    private static final List<String> SEARCH = new ArrayList<>();
    /** 每个物品的稳定键（{@code registryName@meta}），和 STACKS 一一对应。 */
    private static final List<String> KEYS = new ArrayList<>();
    /** 每个物品是不是「非消耗品」，见 {@link #isCatalyst(int)}。 */
    private static final List<Boolean> CATALYST = new ArrayList<>();
    private static final Map<String, Integer> BY_KEY = new HashMap<>();

    private static volatile Map<Long, Long> stock = Collections.emptyMap();
    private static volatile boolean catalogReady;
    private static volatile boolean catalogFailed;

    private static List<ItemStack> pending;
    private static int cursor;

    // ==================================================================
    // 目录构建（客户端线程，分帧）
    // ==================================================================

    /** 目录是否可用。没就绪时搜索一律返回空，界面会显示进度。 */
    public static boolean isReady() {
        return catalogReady;
    }

    public static float progress() {
        if (catalogReady) return 1.0F;
        int total = pending == null ? 0 : pending.size();
        return total <= 0 ? 0.0F : Math.min(1.0F, (float) cursor / (float) total);
    }

    /** 目录来源的待处理总量（诊断用）。 */
    public static int sourceSize() {
        List<ItemStack> local = pending;
        return local == null ? 0 : local.size();
    }

    /**
     * 在客户端 tick 里调用。
     *
     * <p>
     * 目录的数据源是 {@code client/ItemIndex}（模组原有的那份「所有物品」清单，
     * 它扫的是物品注册表 + 创造模式标签页）。这里<b>特意不用 NEI 的
     * {@code ItemList.items}</b>：那份清单是 NEI 按需加载的，实测在标题界面
     * 待着不动会一直停在「没加载完」，用它等于把本功能钉死在「必须先开一次
     * NEI 物品面板」。而配方里出现的物品本来就会由索引那边补登记，
     * 所以缺的只是「还没被任何配方引用到」的那些，代价可以忽略。
     */
    public static void tick() {
        if (catalogReady || catalogFailed) return;

        if (pending == null) {
            com.futa_gtnh.client.ItemIndex.ensureStarted();
            com.futa_gtnh.client.ItemIndex.tick();
            if (com.futa_gtnh.client.ItemIndex.isBuilding()) return;

            int size = com.futa_gtnh.client.ItemIndex.size();
            if (size <= 0) return;

            List<ItemStack> all = new ArrayList<>(size);
            com.futa_gtnh.client.ItemIndex.filter("", all);
            if (all.isEmpty()) return;

            pending = all;
            cursor = 0;
            FutaGtnhMod.LOG.info("网页配方：开始建立物品目录，共 {} 个条目", all.size());
        }

        int end = Math.min(pending.size(), cursor + BUILD_BUDGET_PER_TICK);
        synchronized (LOCK) {
            if (STACKS.size() < MAX_ENTRIES) {
                for (int i = cursor; i < end; i++) {
                    registerLocked(pending.get(i));
                }
            }
        }
        cursor = end;

        if (cursor >= pending.size()) {
            catalogReady = true;
            pending = null;
            FutaGtnhMod.LOG.info("网页配方：物品目录完成，共 {} 个条目", size());
        }
    }

    /**
     * 登记一个物品（幂等）。
     *
     * <p>
     * 配方里出现的物品不一定在 NEI 的物品清单里（机器产出的中间产物、只有配方的虚拟物品），
     * 所以索引那边随时可能补登记，这里必须能处理「目录还在建」的情况。
     */
    public static int idOf(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return -1;
        String key = keyOf(stack);
        synchronized (LOCK) {
            Integer existing = BY_KEY.get(key);
            if (existing != null) return existing;
            return registerLocked(stack);
        }
    }

    private static int registerLocked(ItemStack stack) {
        String key = keyOf(stack);
        Integer existing = BY_KEY.get(key);
        if (existing != null) return existing;

        int id = STACKS.size();
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        STACKS.add(copy);
        String name = displayName(copy);
        NAMES.add(name);
        MODS.add(modIdOf(copy));
        SEARCH.add(buildSearchText(copy, name));
        CATALYST.add(looksLikeCatalyst(key, name));
        KEYS.add(key);
        BY_KEY.put(key, id);
        return id;
    }

    /**
     * 这个物品像不像「非消耗品」（可反复使用的那种）。
     *
     * <p>
     * 为什么要在规划里区别对待：GT 里一大类东西是<b>当工具用</b>的 —— 可编程电路、
     * 各种模具、常驻工具、透镜 —— 它们在配方里出现，但做完还在你手上。规划要是把它们
     * 当普通材料算，就会得到「需要 576 个可编程电路」「做 512 次螺丝刀」这种荒唐结果，
     * 玩家还得自己一件件去标「当作原始材料」。
     *
     * <p>
     * 判据尽量保守，只认确定的那几类：
     * <ul>
     * <li>GT 的可编程电路（{@code gt.integrated_circuit}）—— 机器里那个是配置槽，不消耗；</li>
     * <li>GT 的常驻工具（{@code gt.metatool.01}）—— 有耐久、能修，不是一次性的；</li>
     * <li>名字里带 Mold / 模具 的（GT 的模具是反复用的）；</li>
     * <li>名字里带 Lens / 透镜 的。</li>
     * </ul>
     * <b>一次性工具必须排除</b>：它们的名字里带「一次性」，是设计上就要消耗的
     * （所以一份计划里出现「4570 个一次性锻造锤」是对的，不是 bug）。
     *
     * <p>
     * 认错了也没关系：界面上每一行都能改成「还是按消耗算」，反过来也能手动标成非消耗品。
     */
    private static boolean looksLikeCatalyst(String key, String name) {
        if (name.contains("一次性")) return false;
        if (key.startsWith("gregtech:gt.integrated_circuit")) return true;
        if (key.startsWith("gregtech:gt.metatool.01")) return true;
        if (name.contains("Mold") || name.contains("模具")) return true;
        if (name.contains("Lens") || name.contains("透镜")) return true;
        return false;
    }

    /**
     * 这个物品是不是「非消耗品」。
     *
     * <p>
     * 规划在索引线程上跑，所以这里读的是建目录时就定好的布尔表 ——
     * 不去现场问物品要名字（那要碰语言表和物品对象，不能在别的线程做）。
     */
    public static boolean isCatalyst(int id) {
        synchronized (LOCK) {
            return id >= 0 && id < CATALYST.size() && CATALYST.get(id);
        }
    }

    // ==================================================================
    // 读取
    // ==================================================================

    public static ItemStack stackOf(int id) {
        synchronized (LOCK) {
            return id < 0 || id >= STACKS.size() ? null : STACKS.get(id);
        }
    }

    public static String nameOf(int id) {
        synchronized (LOCK) {
            return id < 0 || id >= NAMES.size() ? "" : NAMES.get(id);
        }
    }

    /** 物品的稳定键（{@code registryName@meta}），编号换了它也不变。 */
    public static String keyOfId(int id) {
        synchronized (LOCK) {
            return id < 0 || id >= KEYS.size() ? "" : KEYS.get(id);
        }
    }

    /**
     * 用稳定键换当前会话的物品编号；没有这个物品时返回 -1。
     *
     * <p>
     * 缓存和界面存档里存的都是键（编号每个会话会重排），用的时候才换回编号。
     */
    public static int idOfKey(String key) {
        if (key == null || key.isEmpty()) return -1;
        synchronized (LOCK) {
            Integer existing = BY_KEY.get(key);
            return existing == null ? -1 : existing;
        }
    }

    public static String modOf(int id) {
        synchronized (LOCK) {
            return id < 0 || id >= MODS.size() ? "" : MODS.get(id);
        }
    }

    /** 模组的人类可读名字（{@code @gtnh} 这种搜索命中时显示用）。 */
    public static String modNameOf(String modId) {
        if (modId == null || modId.isEmpty()) return "";
        try {
            ModContainer container = Loader.instance()
                .getIndexedModList()
                .get(modId);
            if (container != null) return container.getName();
        } catch (Throwable ignored) {
            // Loader 没就绪时退回 modId 就够了
        }
        return modId;
    }

    public static int size() {
        synchronized (LOCK) {
            return STACKS.size();
        }
    }

    /** 库存快照里有多少个条目（诊断用：共享背包快照有没有拉下来看这个）。 */
    public static int stockSize() {
        return stock.size();
    }

    /** 库存里有多少这个物品（共享背包 + 玩家背包）。 */
    public static long stockOf(int id) {
        ItemStack stack = stackOf(id);
        if (stack == null) return 0L;
        Long value = stock.get(stockKey(stack));
        return value == null ? 0L : value;
    }

    /** 库存里有多少这个物品（按栈本身查，配方里的任意候选都能问）。 */
    public static long stockOf(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return 0L;
        Long value = stock.get(stockKey(stack));
        return value == null ? 0L : value;
    }

    // ==================================================================
    // 库存快照（客户端线程）
    // ==================================================================

    /**
     * 重新拍一份「我现在有多少东西」的快照。
     *
     * <p>
     * 两个来源：共享背包的客户端缓存（服务端增量同步过来的）和玩家自己的背包。
     * 都不含容器（箱子/背包）里的东西 —— 那些要玩家自己走过去开，算进来反而会误导。
     */
    public static void refreshStock() {
        Map<Long, Long> map = new HashMap<>(8192);

        try {
            if (ClientStorageCache.isReady()) {
                for (StorageViewEntry entry : ClientStorageCache.items()) {
                    ItemStack display = entry.getDisplay();
                    if (display == null || display.getItem() == null) continue;
                    add(map, stockKey(display), entry.getAmount());
                }
            }
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：读取共享背包快照失败（本次按空处理）", t);
        }

        try {
            EntityPlayer player = Minecraft.getMinecraft().thePlayer;
            if (player != null && player.inventory != null) {
                ItemStack[] main = player.inventory.mainInventory;
                for (int i = 0; i < main.length; i++) {
                    ItemStack stack = main[i];
                    if (stack == null || stack.getItem() == null) continue;
                    add(map, stockKey(stack), stack.stackSize);
                }
            }
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：读取玩家背包快照失败（本次按空处理）", t);
        }

        stock = map;
    }

    private static void add(Map<Long, Long> map, long key, long amount) {
        if (amount <= 0L) return;
        Long old = map.get(key);
        map.put(key, old == null ? amount : old + amount);
    }

    // ==================================================================
    // 共享背包快照的按需拉取
    //
    // 全量快照本来只在打开共享终端界面时下发，之后的增量也只发给开着界面的人。
    // 手机网页要在「没开界面」的情况下算还缺多少，所以这里主动要一份 ——
    // 只在网页真的问到库存时拉，并且限流（见 PacketStorageSnapshotRequest 的注释）。
    // ==================================================================

    /** HTTP 线程置位，客户端 tick 里消费。 */
    private static volatile boolean wantSnapshot;
    private static volatile long lastSnapshotRequest;

    /** 由 HTTP 层调用：觉得库存数据可能太旧了。 */
    public static void requestFreshStock() {
        if (System.currentTimeMillis() - lastSnapshotRequest > SNAPSHOT_INTERVAL_MILLIS) {
            wantSnapshot = true;
        }
    }

    /** 在客户端 tick 里调用（必须主线程：发的是网络包）。 */
    public static void tickStockRequest() {
        if (!wantSnapshot) return;
        wantSnapshot = false;
        if (System.currentTimeMillis() - lastSnapshotRequest <= SNAPSHOT_INTERVAL_MILLIS) return;
        lastSnapshotRequest = System.currentTimeMillis();

        if (Minecraft.getMinecraft().thePlayer == null) return;
        try {
            com.futa_gtnh.network.NetworkHandler.INSTANCE
                .sendToServer(new com.futa_gtnh.network.PacketStorageSnapshotRequest());
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("网页配方：请求共享背包快照失败（本次按已有数据算）", t);
        }
    }

    // ==================================================================
    // 搜索
    // ==================================================================

    /**
     * 搜索物品 id。
     *
     * <p>
     * 语法和 NEI/共享终端保持一致的部分：空格分隔是「与」，竖线是「或」，
     * {@code @模组} 按模组匹配。物品名走「小写名 + 拼音后缀」的子串匹配，
     * 装了 NotEnoughCharacters 时额外交给它做模糊音 —— 手机虽然能打中文，
     * 但用拼音缩写找东西的习惯是改不掉的。
     *
     * @param out 命中的 id 会追加进来（调用方负责清空）
     * @return 命中总数（可能远大于 {@code out} 里的条数）
     */
    public static int search(String query, int limit, int offset, List<Integer> out) {
        if (!catalogReady) return 0;

        String[] words = query == null ? new String[0]
            : query.trim()
                .toLowerCase(Locale.ROOT)
                .split("\\s+");
        boolean empty = words.length == 0 || (words.length == 1 && words[0].isEmpty());

        int total = 0;
        synchronized (LOCK) {
            for (int id = 0; id < STACKS.size(); id++) {
                if (!empty && !matches(id, words)) continue;
                if (total >= offset && out.size() < limit) out.add(id);
                total++;
            }
        }
        return total;
    }

    private static boolean matches(int id, String[] words) {
        for (int w = 0; w < words.length; w++) {
            if (!matchesWord(id, words[w])) return false;
        }
        return true;
    }

    private static boolean matchesWord(int id, String word) {
        String[] alternatives = word.split("\\|");
        for (int a = 0; a < alternatives.length; a++) {
            String term = alternatives[a];
            if (term.isEmpty()) continue;

            if (term.charAt(0) == '@') {
                String needle = term.substring(1);
                if (needle.isEmpty()) continue;
                if (MODS.get(id)
                    .toLowerCase(Locale.ROOT)
                    .contains(needle)
                    || modNameOf(MODS.get(id)).toLowerCase(Locale.ROOT)
                        .contains(needle)) {
                    return true;
                }
                continue;
            }

            if (SEARCH.get(id)
                .contains(term)) return true;
            if (NecharBridge.isAvailable() && NecharBridge.matches(NAMES.get(id), term)) return true;
        }
        return false;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 物品身份键：{@code 注册名@metadata}（不含 NBT，理由见类注释）。 */
    public static String keyOf(ItemStack stack) {
        Object registered = Item.itemRegistry.getNameForObject(stack.getItem());
        String base = registered != null ? registered.toString() : "id" + Item.getIdFromItem(stack.getItem());
        return base + '@' + stack.getItemDamage();
    }

    private static long stockKey(ItemStack stack) {
        return ((long) Item.getIdFromItem(stack.getItem()) << 32) | (stack.getItemDamage() & 0xFFFFFFFFL);
    }

    private static String displayName(ItemStack stack) {
        try {
            String name = stack.getDisplayName();
            return name == null ? "" : name;
        } catch (Throwable t) {
            // 个别模组的物品拿显示名会抛异常，一条坏数据不能把整个目录带崩
            return "";
        }
    }

    private static String buildSearchText(ItemStack stack, String displayName) {
        StringBuilder builder = new StringBuilder();
        builder.append(displayName.toLowerCase(Locale.ROOT));
        Object registered = Item.itemRegistry.getNameForObject(stack.getItem());
        if (registered != null) {
            builder.append(' ')
                .append(
                    registered.toString()
                        .toLowerCase(Locale.ROOT));
        }
        synchronized (PINYIN_LOCK) {
            builder.append(Pinyin.searchSuffix(displayName));
        }
        return builder.toString();
    }

    private static String modIdOf(ItemStack stack) {
        Object registered = Item.itemRegistry.getNameForObject(stack.getItem());
        if (registered == null) return "";
        String name = registered.toString();
        int colon = name.indexOf(':');
        return colon <= 0 ? name : name.substring(0, colon);
    }
}
