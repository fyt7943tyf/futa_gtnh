package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.FutaGtnhMod;

/** 客户端缓存的一份「所有物品」清单，供寻物魔杖查找箱子内容。 */
public final class ItemIndex {

    private ItemIndex() {}

    private static final List<ItemStack> ALL = new ArrayList<>();
    private static final List<String> SEARCH = new ArrayList<>();
    private static final List<String> NAMES = new ArrayList<>();
    private static final Set<Long> SEEN = new HashSet<>();

    private static Iterator<?> pendingItems;
    private static Iterator<?> pendingStacks;
    private static int totalItems;
    private static int scannedItems;
    private static boolean building;
    private static boolean ready;

    private static final int ITEM_BUDGET_PER_TICK = 1200;
    private static final int REGISTRY_BUDGET_PER_TICK = 80;

    public static void ensureStarted() {
        if (building || ready) return;

        building = true;
        scannedItems = 0;
        pendingItems = Item.itemRegistry.iterator();
        pendingStacks = null;
        totalItems = countItems();
        FutaGtnhMod.LOG.info("寻物魔杖：开始建立物品索引，共 {} 个注册物品", totalItems);
    }

    private static int countItems() {
        int count = 0;
        for (Object ignored : Item.itemRegistry) {
            count++;
        }
        return Math.max(1, count);
    }

    public static void tick() {
        if (!building || ready || pendingItems == null) return;

        int itemsLeft = ITEM_BUDGET_PER_TICK;
        int registryLeft = REGISTRY_BUDGET_PER_TICK;
        while (itemsLeft > 0) {
            if (pendingStacks == null || !pendingStacks.hasNext()) {
                pendingStacks = null;
                if (registryLeft <= 0 || !pendingItems.hasNext()) break;

                Object next = pendingItems.next();
                scannedItems++;
                registryLeft--;
                if (!(next instanceof Item)) continue;

                Item item = (Item) next;
                List<ItemStack> produced = new ArrayList<>();
                try {
                    net.minecraft.creativetab.CreativeTabs tab = item.getCreativeTab();
                    item.getSubItems(item, tab, produced);
                    if (produced.isEmpty() && tab != null) item.getSubItems(item, null, produced);
                } catch (Throwable t) {
                    // 个别模组物品的子类型枚举会抛异常，跳过它但继续建索引。
                    continue;
                }
                pendingStacks = produced.iterator();
                continue;
            }

            Object nextStack = pendingStacks.next();
            if (!(nextStack instanceof ItemStack)) continue;
            ItemStack stack = (ItemStack) nextStack;
            if (stack == null || stack.getItem() == null) continue;
            long key = ((long) Item.getIdFromItem(stack.getItem()) << 32) | (stack.getItemDamage() & 0xFFFFFFFFL);
            if (!SEEN.add(key)) continue;

            ALL.add(stack.copy());
            String name = displayNameOf(stack);
            SEARCH.add(buildSearchText(stack, name));
            NAMES.add(name);
            itemsLeft--;
        }

        if (!pendingItems.hasNext() && (pendingStacks == null || !pendingStacks.hasNext())) {
            ready = true;
            building = false;
            pendingItems = null;
            pendingStacks = null;
            FutaGtnhMod.LOG.info("寻物魔杖：物品索引完成，共 {} 个条目", ALL.size());
        }
    }

    private static String buildSearchText(ItemStack stack, String displayName) {
        StringBuilder builder = new StringBuilder();
        if (displayName != null) builder.append(displayName.toLowerCase(Locale.ROOT));
        Object registryName = Item.itemRegistry.getNameForObject(stack.getItem());
        if (registryName != null) builder.append(' ')
            .append(
                registryName.toString()
                    .toLowerCase(Locale.ROOT));
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

    public static boolean isBuilding() {
        return building;
    }

    public static float getProgress() {
        if (ready) return 1.0F;
        return Math.min(1.0F, (float) scannedItems / (float) totalItems);
    }

    public static int size() {
        return ALL.size();
    }

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
                if (!SEARCH.get(i)
                    .contains(needle) && !NecharBridge.matches(NAMES.get(i), needle)) {
                    matches = false;
                    break;
                }
            }
            if (matches) out.add(ALL.get(i));
        }
    }
}
