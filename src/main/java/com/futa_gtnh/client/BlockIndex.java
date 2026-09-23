package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 客户端缓存的一份「所有方块」清单，供寻物魔杖的界面搜索。
 *
 * <p>
 * GTNH 里方块条目有几万条（光 GT 的某个元数据方块就能贡献几千个变体），
 * 一次性枚举会卡住好几秒。所以这里做成<b>分帧构建</b>：每 tick 只处理固定预算，
 * 界面显示「正在建立索引」的进度，构建完之前也能先看着。
 *
 * <p>
 * 只建一次，之后整个客户端会话都复用。方块注册表在游戏启动后就固定了，
 * 不存在过期问题。
 *
 * <p>
 * 每一项都预先算好一份小写的搜索文本（显示名 + 注册名）。搜索是每敲一个键
 * 就要扫全表的，现算的话几万次 {@code getDisplayName()} 会明显卡顿 ——
 * 这和共享背包那边的搜索是同一个取舍。
 */
public final class BlockIndex {

    private BlockIndex() {}

    private static final List<ItemStack> ALL = new ArrayList<>();
    private static final List<String> SEARCH = new ArrayList<>();
    /**
     * 与 {@link #SEARCH} 一一对应的<b>原始显示名</b>。
     *
     * <p>
     * SEARCH 里混着注册名和拼音后缀，不适合直接交给 NotEnoughCharacters 的
     * 拼音匹配（它要的是纯名字）；所以单独存一份。没装 NEChar 时这份列表
     * 不会被用到，白占一点内存换来两条路径共用一套索引。
     */
    private static final List<String> NAMES = new ArrayList<>();
    /** 去重用：同一个 (物品, 元数据) 只收一次。 */
    private static final Set<Long> SEEN = new HashSet<>();

    private static Iterator<?> pendingBlocks;
    private static int totalBlocks;
    private static int scannedBlocks;
    private static boolean building;
    private static boolean ready;

    /** 每 tick 最多索引多少个方块条目。 */
    private static final int ITEM_BUDGET_PER_TICK = 1200;
    /** 每 tick 最多问多少个方块要它的子类型。 */
    private static final int BLOCK_BUDGET_PER_TICK = 40;

    // ==================================================================
    // 分帧构建
    // ==================================================================

    /** 第一次需要时才开始建。重复调用无副作用。 */
    public static void ensureStarted() {
        if (building || ready) return;

        building = true;
        scannedBlocks = 0;
        pendingBlocks = Block.blockRegistry.iterator();
        totalBlocks = countBlocks();

        FutaGtnhMod.LOG.info("寻物魔杖：开始建立方块索引，共 {} 个注册方块", totalBlocks);
    }

    private static int countBlocks() {
        int count = 0;
        for (Object ignored : Block.blockRegistry) {
            count++;
        }
        return Math.max(1, count);
    }

    /** 每 tick 调一次推进构建。界面在 {@code updateScreen} 里调。 */
    public static void tick() {
        if (!building || ready || pendingBlocks == null) return;

        int itemsLeft = ITEM_BUDGET_PER_TICK;
        int blocksLeft = BLOCK_BUDGET_PER_TICK;

        while (itemsLeft > 0 && blocksLeft > 0 && pendingBlocks.hasNext()) {
            Object next = pendingBlocks.next();
            scannedBlocks++;
            blocksLeft--;

            if (!(next instanceof Block)) continue;
            Block block = (Block) next;
            if (block == Blocks.air || block.getMaterial() == net.minecraft.block.material.Material.air) continue;

            Item item = Item.getItemFromBlock(block);
            if (item == null) continue;

            List<ItemStack> produced = new ArrayList<>();
            try {
                // 传方块自己的创造标签页：不少模组的 getSubBlocks 会
                // if (tab == this.getCreativeTabToDisplayOn()) 才填内容，
                // 传 null 或者别的东西会让它们一个变体都不返回
                block.getSubBlocks(item, block.getCreativeTabToDisplayOn(), produced);
            } catch (Throwable t) {
                // 个别模组的 getSubBlocks 会抛异常，不能让它带崩整次索引
                continue;
            }

            for (ItemStack stack : produced) {
                if (stack == null || stack.getItem() == null) continue;

                long key = ((long) Item.getIdFromItem(stack.getItem()) << 32) | (stack.getItemDamage() & 0xFFFFFFFFL);
                if (!SEEN.add(key)) continue;

                ALL.add(stack);
                SEARCH.add(buildSearchText(stack));
                NAMES.add(displayNameOf(stack));
                itemsLeft--;
            }
        }

        if (!pendingBlocks.hasNext()) {
            ready = true;
            building = false;
            pendingBlocks = null;
            FutaGtnhMod.LOG.info("寻物魔杖：方块索引完成，共 {} 个条目", ALL.size());
        }
    }

    private static String buildSearchText(ItemStack stack) {
        StringBuilder builder = new StringBuilder();
        String displayName = null;
        try {
            displayName = stack.getDisplayName();
            builder.append(displayName.toLowerCase(Locale.ROOT));
        } catch (Throwable ignored) {
            // 个别物品取显示名会抛异常，跳过名字但保留注册名
        }
        Object registryName = Item.itemRegistry.getNameForObject(stack.getItem());
        if (registryName != null) {
            builder.append(' ')
                .append(
                    registryName.toString()
                        .toLowerCase(Locale.ROOT));
        }
        // 拼音（全拼 + 首字母）。这是自研回退路径用的；装了 NotEnoughCharacters
        // 时名字匹配走 NEChar 的 PinIn（见 #matchesAt），后缀其实用不到，
        // 但留着无害 —— 两条路径随时可能按装载情况切换。
        builder.append(Pinyin.searchSuffix(displayName));
        return builder.toString();
    }

    private static String displayNameOf(ItemStack stack) {
        try {
            String name = stack.getDisplayName();
            return name == null ? "" : name;
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean isReady() {
        return ready;
    }

    public static boolean isBuilding() {
        return building;
    }

    public static float getProgress() {
        if (ready) return 1.0F;
        return Math.min(1.0F, (float) scannedBlocks / (float) totalBlocks);
    }

    public static int size() {
        return ALL.size();
    }

    // ==================================================================
    // 搜索
    // ==================================================================

    /**
     * 按查询串过滤。
     *
     * <p>
     * 语法刻意做得和共享背包那边一致：空格分隔多个词是「与」，
     * 词首的 {@code @} 表示按模组过滤（实现上就是去掉 @ 之后当普通子串匹配，
     * 因为注册名里本来就带着模组前缀）。
     *
     * @param out 输出列表，会被清空
     */
    public static void filter(String query, List<ItemStack> out) {
        out.clear();
        if (query == null || query.trim()
            .isEmpty()) {
            out.addAll(ALL);
            return;
        }

        String[] words = query.trim()
            .toLowerCase(Locale.ROOT)
            .split("\\s+");

        for (int i = 0; i < ALL.size(); i++) {
            boolean matches = true;
            for (String word : words) {
                String needle = word.startsWith("@") ? word.substring(1) : word;
                if (needle.isEmpty()) continue;
                if (!matchesAt(i, needle)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                out.add(ALL.get(i));
            }
        }
    }

    /**
     * 第 {@code i} 个条目是否包含 {@code needle}。
     *
     * <p>
     * 自研路径：预计算的搜索串（小写显示名 + 注册名 + 拼音后缀）子串匹配。
     * 装了 NotEnoughCharacters 时再补一刀 NEChar 的拼音/模糊音匹配 ——
     * 它要的是纯显示名，所以查 {@link #NAMES} 那一份。
     */
    private static boolean matchesAt(int i, String needle) {
        if (SEARCH.get(i)
            .contains(needle)) {
            return true;
        }
        return NecharBridge.matches(NAMES.get(i), needle);
    }
}
