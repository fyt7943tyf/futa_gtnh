package com.futa_gtnh.reactor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;

import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 布局的「记录 → 预检 → 部署」全流程，以及整个功能的核心难点：
 * <b>带 NBT 元件的等值请求问题</b>。
 *
 * <p>
 * <b>问题背景：</b>IC2 的燃料棒/散热组件把损耗记在栈 NBT 里（{@code ItemGradualInt}
 * 的 {@code advDmg} 整型；全新件没有 NBT）。从运行中的反应堆记录下来的布局，
 * 燃料棒几乎必然带着「已损耗」的 NBT；而共享背包里备的料是全新件（没有 NBT）。
 * {@link ItemKey} 的相等判定是「物品 + 耐久 + NBT 全一致」，直接按档案精确请求，
 * 永远请求不到 —— 这不是 bug，是键的语义本来就要求逐字节一致。
 *
 * <p>
 * <b>解法：三级匹配</b>（按 {@code looseNbt} 开关，默认开）：
 * <ol>
 * <li>精确：{@code ItemKey(档案里的完整 NBT)}，命中则取 —— 档案里是全新件时走这条；</li>
 * <li>宽松：同「物品 + 耐久」、任意 NBT 的条目合并计数，优先取<b>最接近全新</b>的
 * 变体（无 NBT → {@code advDmg} 小的）。用过的档案配全新库存就在这条落地；</li>
 * <li>缺料：两级都凑不齐的差额进报告，缺哪格铺哪格，绝不虚发。</li>
 * </ol>
 *
 * <p>
 * 宽松匹配拿到的元件<b>保持它自己的 NBT 原样入堆</b>（从共享背包 {@code extractItem}
 * 出来的就是键的 prototype）—— 我们不做「把新件改成旧件的 NBT」这种造假，
 * 新件装进去只会更好。预检清单（{@link #buildManifest}）会标注「以不同损耗版本顶替」，
 * 玩家在部署前就能看到影响。
 *
 * <p>
 * <b>部署语义（用户选定）：先清空后全量铺设。</b>目标网格里已有的物品全部回收到
 * 共享背包（{@code insertItemManual}，绕过存量上限 —— 这是玩家手动操作），
 * 然后按布局逐格 {@code setItemAt} 铺设；目标比布局宽时多出的列也一并清空。
 * 取料顺序沿用本模组的惯例：玩家背包优先，共享背包补足
 * （见 {@code FillUtil.take} / {@code CraftFiller.gather}）。
 */
public final class ReactorDeployer {

    private ReactorDeployer() {}

    // ==================================================================
    // 记录
    // ==================================================================

    /** 从反应堆网格读出一份布局（深拷贝，不落库）。 */
    public static ReactorLayout capture(Ic2ReactorCompat.ReactorRef ref, int columns) {
        ReactorLayout layout = new ReactorLayout(columns);
        layout.fluidCooled = Ic2ReactorCompat.isFluidCooled(ref);
        for (int row = 0; row < ReactorLayout.ROWS; row++) {
            for (int column = 0; column < layout.columns; column++) {
                ItemStack stack = Ic2ReactorCompat.getItemAt(ref, column, row);
                if (stack != null && stack.getItem() != null) {
                    layout.slots[row * layout.columns + column] = stack.copy();
                }
            }
        }
        return layout;
    }

    // ==================================================================
    // 预检清单
    // ==================================================================

    /** 需求清单的一行：一种 (物品, 耐久) 的需求量与可用量。 */
    public static final class ManifestRow {

        public ItemStack display;
        public int needed;
        public int availablePlayer;
        public int availableStorage;
        /** 是否存在 NBT 完全一致的来源（false 时全部靠宽松匹配顶替）。 */
        public boolean exactAvailable;
    }

    /**
     * 生成需求清单（记录 vs 玩家背包 + 共享背包）。
     *
     * <p>
     * 可用量按「宽松口径」统计（同物品+耐久的所有 NBT 变体合并），
     * 这样玩家看到的是「这类元件总共够不够」；是否有全新件另用
     * {@link ManifestRow#exactAvailable} 标注。
     */
    public static List<ManifestRow> buildManifest(EntityPlayerMP player, ReactorLayout layout, boolean looseNbt) {
        Map<String, Requirement> groups = collectRequirements(layout);

        List<ManifestRow> rows = new ArrayList<>();
        for (Requirement req : groups.values()) {
            ManifestRow row = new ManifestRow();
            row.display = req.display();
            row.needed = req.needed;

            if (req.exactKey != null) {
                int exactPlayer = countInPlayerInventory(player, req.item, req.meta, req.exactKey.copyNbt(), true);
                int exactStorage = (int) SharedStorageManager.getStorage()
                    .getItemAmount(req.exactKey);
                if (exactPlayer + exactStorage > 0) {
                    row.exactAvailable = true;
                    row.availablePlayer += exactPlayer;
                    row.availableStorage += exactStorage;
                }
            }

            // 宽松口径补齐统计（同物品+耐久的其余 NBT 变体）
            row.availablePlayer += countInPlayerInventory(player, req.item, req.meta, null, false)
                - (req.exactKey != null ? countExactInPlayerOnly(player, req.exactKey) : 0);
            if (row.availablePlayer < 0) row.availablePlayer = 0;
            row.availableStorage += countInStorage(SharedStorageManager.getStorage(), req.item, req.meta)
                - (req.exactKey != null ? (int) SharedStorageManager.getStorage()
                    .getItemAmount(req.exactKey) : 0);
            if (row.availableStorage < 0) row.availableStorage = 0;

            rows.add(row);
        }
        return rows;
    }

    // ==================================================================
    // 部署
    // ==================================================================

    /**
     * 执行部署：清空回收 → 取料 → 铺设 → 聊天报告。
     *
     * @return null 表示流程已走完（结果见聊天栏）；非 null 是失败原因的语言键
     *         （流程未动任何东西）
     */
    public static String deploy(EntityPlayerMP player, ReactorLayout layout, Ic2ReactorCompat.ReactorRef ref,
        boolean looseNbt) {
        int targetColumns = Ic2ReactorCompat.getColumns(ref);
        if (targetColumns < layout.columns) {
            player.addChatMessage(
                new ChatComponentTranslation("futa_gtnh.reactor.msg.grid_too_small", targetColumns, layout.columns));
            return "futa_gtnh.reactor.msg.grid_too_small";
        }

        // 类型不匹配（普通 ↔ 流体）不拦：内部网格本来通用，但要说清楚
        boolean targetFluid = Ic2ReactorCompat.isFluidCooled(ref);
        if (targetFluid != layout.fluidCooled) {
            player.addChatMessage(
                new ChatComponentTranslation(
                    "futa_gtnh.reactor.msg.type_mismatch",
                    new ChatComponentTranslation(
                        layout.fluidCooled ? "futa_gtnh.reactor.type.fluid" : "futa_gtnh.reactor.type.solid"),
                    new ChatComponentTranslation(
                        targetFluid ? "futa_gtnh.reactor.type.fluid" : "futa_gtnh.reactor.type.solid")));
        }

        SharedStorage storage = SharedStorageManager.getStorage();

        // ---- 第一步：清空目标网格，旧物品全部回收到共享背包 ----
        long recycled = 0L;
        for (int row = 0; row < ReactorLayout.ROWS; row++) {
            for (int column = 0; column < targetColumns; column++) {
                ItemStack existing = Ic2ReactorCompat.getItemAt(ref, column, row);
                if (existing == null || existing.getItem() == null) continue;
                Ic2ReactorCompat.setItemAt(ref, column, row, null);
                ItemKey key = ItemKey.of(existing);
                if (key == null) continue;
                long accepted = storage.insertItemManual(key, existing.stackSize);
                recycled += accepted;
                SharedStorageManager.broadcastItemChange(key);
            }
        }

        // ---- 第二步：按布局逐格取料（档案里的栈只是模板，实际装入的是取到的实物）----
        Map<String, Requirement> groups = collectRequirements(layout);
        Map<ItemKey, Integer> pool = new LinkedHashMap<>();
        Map<ItemKey, Boolean> poolSubstituted = new LinkedHashMap<>();
        int shortageTotal = 0;
        List<String> shortageNames = new ArrayList<>();

        for (Requirement req : groups.values()) {
            int taken = take(player, storage, pool, poolSubstituted, req, looseNbt);
            if (taken < req.needed) {
                shortageTotal += req.needed - taken;
                shortageNames.add(req.displayName() + " ×" + (req.needed - taken));
            }
        }

        // ---- 第三步：铺设（缺料的格铺多少算多少，铺不上的留在报告里）----
        int placedCells = 0;
        for (int row = 0; row < ReactorLayout.ROWS; row++) {
            for (int column = 0; column < layout.columns; column++) {
                ItemStack want = layout.slots[row * layout.columns + column];
                if (want == null || want.getItem() == null) continue;

                ItemStack assembled = assemble(pool, want.stackSize);
                if (assembled == null) continue;
                if (Ic2ReactorCompat.setItemAt(ref, column, row, assembled)) {
                    placedCells++;
                }
            }
        }
        markTileDirty(ref);

        // ---- 第四步：报告 ----
        int substituted = 0;
        for (Boolean flag : poolSubstituted.values()) {
            if (Boolean.TRUE.equals(flag)) substituted++;
        }

        player.addChatMessage(new ChatComponentTranslation("futa_gtnh.reactor.msg.deploy_head", placedCells, recycled));
        if (substituted > 0) {
            player
                .addChatMessage(new ChatComponentTranslation("futa_gtnh.reactor.msg.deploy_substituted", substituted));
        }
        if (shortageTotal > 0) {
            player.addChatMessage(
                new ChatComponentTranslation(
                    "futa_gtnh.reactor.msg.deploy_missing_head",
                    shortageTotal,
                    String.join("、", shortageNames)));
        }
        return null;
    }

    // ==================================================================
    // 取料实现（三级匹配）
    // ==================================================================

    /** 一种 (物品, 耐久) 的聚合需求。 */
    private static final class Requirement {

        Item item;
        int meta;
        /** 档案里这一组的代表 NBT（首个非空）；可能为 null（全新件）。 */
        NBTTagCompound preferNbt;
        ItemKey exactKey;
        int needed;
        String displayName;

        ItemStack display() {
            ItemStack stack = new ItemStack(item, 1, meta);
            if (preferNbt != null) stack.setTagCompound((NBTTagCompound) preferNbt.copy());
            return stack;
        }

        String displayName() {
            if (displayName != null) return displayName;
            ItemStack stack = display();
            displayName = stack.getDisplayName();
            return displayName;
        }
    }

    /** 按需求出现顺序聚合布局里的非空格。 */
    private static Map<String, Requirement> collectRequirements(ReactorLayout layout) {
        Map<String, Requirement> groups = new LinkedHashMap<>();
        for (ItemStack slot : layout.slots) {
            if (slot == null || slot.getItem() == null) continue;
            String key = Item.getIdFromItem(slot.getItem()) + ":" + slot.getItemDamage();
            Requirement req = groups.get(key);
            if (req == null) {
                req = new Requirement();
                req.item = slot.getItem();
                req.meta = slot.getItemDamage();
                req.exactKey = ItemKey.of(slot);
                req.preferNbt = slot.getTagCompound() != null && !slot.getTagCompound()
                    .hasNoTags() ? (NBTTagCompound) slot.getTagCompound()
                        .copy() : null;
                groups.put(key, req);
            }
            req.needed += Math.max(1, slot.stackSize);
        }
        return groups;
    }

    /**
     * 把一种需求取齐，实物登记进 pool。
     *
     * @return 实际凑到的数量
     */
    private static int take(EntityPlayerMP player, SharedStorage storage, Map<ItemKey, Integer> pool,
        Map<ItemKey, Boolean> poolSubstituted, Requirement req, boolean looseNbt) {
        int remaining = req.needed;

        // 1) 精确：玩家背包 → 共享背包
        if (req.exactKey != null) {
            remaining -= takeFromPlayer(player, pool, poolSubstituted, req.exactKey, remaining, false);
            if (remaining > 0) {
                remaining -= takeFromStorage(storage, pool, poolSubstituted, req.exactKey, remaining, false);
            }
        }
        if (remaining <= 0 || !looseNbt) return req.needed - Math.max(0, remaining);

        // 2) 宽松：同物品+耐久，玩家背包先扫「无 NBT（全新）」再扫其余变体
        remaining -= takeFromPlayerLoose(player, pool, poolSubstituted, req, remaining);
        // 3) 宽松：共享背包，按「最接近全新」排序逐键抽
        if (remaining > 0) {
            remaining -= takeFromStorageLoose(storage, pool, poolSubstituted, req, remaining);
        }
        return req.needed - Math.max(0, remaining);
    }

    private static int takeFromPlayer(EntityPlayerMP player, Map<ItemKey, Integer> pool,
        Map<ItemKey, Boolean> poolSubstituted, ItemKey key, int amount, boolean substituted) {
        return takeFromPlayerMatching(
            player,
            pool,
            poolSubstituted,
            key.getItem(),
            key.getMeta(),
            amount,
            candidate -> candidate.equals(key),
            substituted);
    }

    private static int takeFromPlayerLoose(EntityPlayerMP player, Map<ItemKey, Integer> pool,
        Map<ItemKey, Boolean> poolSubstituted, Requirement req, int amount) {
        int taken = 0;
        // 先全新（无 NBT），再任意变体 —— 两趟扫描代替复杂的槽位排序
        taken += takeFromPlayerMatching(
            player,
            pool,
            poolSubstituted,
            req.item,
            req.meta,
            amount - taken,
            candidate -> !candidate.hasNbt(),
            true);
        if (amount - taken > 0) {
            taken += takeFromPlayerMatching(
                player,
                pool,
                poolSubstituted,
                req.item,
                req.meta,
                amount - taken,
                candidate -> true,
                true);
        }
        return taken;
    }

    private interface KeyMatcher {

        boolean matches(ItemKey key);
    }

    private static int takeFromPlayerMatching(EntityPlayerMP player, Map<ItemKey, Integer> pool,
        Map<ItemKey, Boolean> poolSubstituted, Item item, int meta, int amount, KeyMatcher matcher,
        boolean substituted) {
        if (amount <= 0) return 0;
        ItemStack[] main = player.inventory.mainInventory;
        int taken = 0;

        for (int i = 0; i < main.length && taken < amount; i++) {
            ItemStack slot = main[i];
            if (slot == null || slot.getItem() != item || slot.getItemDamage() != meta) continue;
            ItemKey key = ItemKey.of(slot);
            if (key == null || !matcher.matches(key)) continue;

            int take = Math.min(amount - taken, slot.stackSize);
            slot.stackSize -= take;
            if (slot.stackSize <= 0) main[i] = null;
            addToPool(pool, poolSubstituted, key, take, substituted);
            taken += take;
        }
        if (taken > 0) player.inventory.markDirty();
        return taken;
    }

    private static int takeFromStorage(SharedStorage storage, Map<ItemKey, Integer> pool,
        Map<ItemKey, Boolean> poolSubstituted, ItemKey key, int amount, boolean substituted) {
        long have = storage.getItemAmount(key);
        if (have <= 0) return 0;
        long extracted = storage.extractItem(key, Math.min(have, amount));
        if (extracted <= 0) return 0;
        addToPool(pool, poolSubstituted, key, (int) extracted, substituted);
        SharedStorageManager.broadcastItemChange(key);
        return (int) extracted;
    }

    private static int takeFromStorageLoose(SharedStorage storage, Map<ItemKey, Integer> pool,
        Map<ItemKey, Boolean> poolSubstituted, Requirement req, int amount) {
        // 快照后排序：无 NBT 的全新件最优先，其余按 advDmg 从小到大（损耗越少越接近档案外的新件）
        List<Map.Entry<ItemKey, Long>> candidates = new ArrayList<>();
        for (Map.Entry<ItemKey, Long> entry : storage.snapshotItems()) {
            ItemKey key = entry.getKey();
            if (key == null || key.getItem() != req.item || key.getMeta() != req.meta) continue;
            if (entry.getValue() == null || entry.getValue() <= 0L) continue;
            candidates.add(entry);
        }
        candidates.sort(Comparator.comparingInt(entry -> freshnessRank(entry.getKey())));

        int taken = 0;
        for (Map.Entry<ItemKey, Long> entry : candidates) {
            if (taken >= amount) break;
            ItemKey key = entry.getKey();
            int want = (int) Math.min(
                entry.getValue()
                    .longValue(),
                (long) (amount - taken));
            if (want <= 0) continue;
            long extracted = storage.extractItem(key, want);
            if (extracted <= 0) continue;
            boolean substituted = req.exactKey == null || !key.equals(req.exactKey);
            addToPool(pool, poolSubstituted, key, (int) extracted, substituted);
            SharedStorageManager.broadcastItemChange(key);
            taken += (int) extracted;
        }
        return taken;
    }

    /**
     * 「新鲜度」排序值：越小越新。无 NBT = 0（全新）；带 {@code advDmg}（IC2
     * {@code ItemGradualInt} 的损耗计数）按损耗排；其它 NBT 按最难看的兜底值排。
     */
    private static int freshnessRank(ItemKey key) {
        NBTTagCompound nbt = key.copyNbt();
        if (nbt == null) return 0;
        if (nbt.hasKey("advDmg")) return Math.max(1, nbt.getInteger("advDmg"));
        return 1_000_000;
    }

    private static void addToPool(Map<ItemKey, Integer> pool, Map<ItemKey, Boolean> poolSubstituted, ItemKey key,
        int count, boolean substituted) {
        if (count <= 0) return;
        pool.merge(key, count, Integer::sum);
        if (substituted) poolSubstituted.put(key, Boolean.TRUE);
        else poolSubstituted.putIfAbsent(key, Boolean.FALSE);
    }

    /** 从取料池里拼一个最多 {@code max} 件、至少 1 件的物品栈；池空了返回 null。 */
    private static ItemStack assemble(Map<ItemKey, Integer> pool, int max) {
        for (Map.Entry<ItemKey, Integer> entry : pool.entrySet()) {
            if (entry.getValue() <= 0) continue;
            int size = Math.min(Math.min(entry.getValue(), max), 64);
            ItemStack stack = entry.getKey()
                .prototype(size);
            entry.setValue(entry.getValue() - size);
            if (entry.getValue() <= 0) pool.remove(entry.getKey());
            return stack;
        }
        return null;
    }

    // ==================================================================
    // 可用量统计（预检清单用，只读不动库存）
    // ==================================================================

    private static int countInPlayerInventory(EntityPlayerMP player, Item item, int meta, NBTTagCompound exactNbt,
        boolean exactOnly) {
        int count = 0;
        ItemStack[] main = player.inventory.mainInventory;
        for (ItemStack slot : main) {
            if (slot == null || slot.getItem() != item || slot.getItemDamage() != meta) continue;
            ItemKey key = ItemKey.of(slot);
            if (key == null) continue;
            if (exactOnly) {
                NBTTagCompound nbt = key.copyNbt();
                if (exactNbt == null ? nbt != null : !equalsNbt(exactNbt, nbt)) continue;
            }
            count += slot.stackSize;
        }
        return count;
    }

    private static int countExactInPlayerOnly(EntityPlayerMP player, ItemKey key) {
        return countInPlayerInventory(player, key.getItem(), key.getMeta(), key.copyNbt(), true);
    }

    private static int countInStorage(SharedStorage storage, Item item, int meta) {
        long total = 0L;
        for (Map.Entry<ItemKey, Long> entry : storage.snapshotItems()) {
            ItemKey key = entry.getKey();
            if (key == null || key.getItem() != item || key.getMeta() != meta) continue;
            if (entry.getValue() != null) total += entry.getValue();
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    private static boolean equalsNbt(NBTTagCompound a, NBTTagCompound b) {
        if (a == null || a.hasNoTags()) return b == null || b.hasNoTags();
        return a.equals(b);
    }

    private static void markTileDirty(Ic2ReactorCompat.ReactorRef ref) {
        if (ref.reactor instanceof TileEntity) {
            try {
                ((TileEntity) ref.reactor).markDirty();
            } catch (Throwable ignored) {
                // IC2 的库存槽自己也会标脏；这里只是兜底
            }
        }
    }
}
