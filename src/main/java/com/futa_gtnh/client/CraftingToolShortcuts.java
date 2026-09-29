package com.futa_gtnh.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.shared.ItemKey;

import gregtech.api.enums.ToolDictNames;

/**
 * 仓库面板上那排「一键取 GT 工具」按钮的数据来源。
 *
 * <p>
 * 每一种工具都用 <b>GT 自己注册的 {@code craftingTool...} 矿辞</b>认，而不是写死某个
 * 物品 —— GT 的工具分很多材质档（青铜、钢、钨钢、各种合金），矿辞是唯一稳定的身份：
 * 仓库里不管躺着哪一档，都能被同一条矿辞找出来。
 *
 * <p>
 * 名字直接引用 GT 的 {@link ToolDictNames} 枚举，<b>不写字符串</b>：枚举常量的名字就是
 * 注册进矿辞表的那个名字，写错了编译期就报错。写字符串的代价是踩过一次的坑 ——
 * 「硬锤」在 GT 里叫 {@code craftingToolHardHammer}、不叫 {@code craftingToolHammer}，
 * 「软锤」叫 {@code craftingToolSoftMallet}、不叫 {@code craftingToolSoftHammer}；
 * 名字错一个字，按钮就是永远灰着、也不报错。
 *
 * <p>
 * <b>为什么按钮只在客户端算。</b>点一下 = 「把仓库里这一种工具取 1 个到背包」，
 * 走的还是既有的 {@code WITHDRAW_ITEM} 包（服务端本来就会校验这个键真的在仓库里），
 * 所以这里只负责三件事：挑一个用于显示的图标、找出仓库里实际存着的那把、判断有没有。
 * 一个包、一个动作都没多开。
 *
 * <p>
 * 结果按 {@link ClientStorageCache#getRevision()} 缓存：库存没变时一帧一次全表扫描
 * 太浪费（仓库条目可以上千条），变了才重算。
 */
public final class CraftingToolShortcuts {

    /**
     * 支持的工具栏位，顺序就是按钮在界面上的顺序。
     *
     * <p>
     * 扳手排第一是因为 GTNH 里它用得最多（拆装机器、扳手右键一切）；其余按常用度排。
     */
    public static final ToolDictNames[] TOOLS = { ToolDictNames.craftingToolWrench,
        ToolDictNames.craftingToolHardHammer, ToolDictNames.craftingToolSoftMallet, ToolDictNames.craftingToolFile,
        ToolDictNames.craftingToolScrewdriver, ToolDictNames.craftingToolSaw, ToolDictNames.craftingToolWireCutter,
        ToolDictNames.craftingToolCrowbar };

    /** 一个工具栏位当前的样子：图标 + 仓库里实际存着的键（没有就是 null）。 */
    public static final class Entry {

        /** 矿辞名（= {@link ToolDictNames} 常量的名字，也就是注册进矿辞表的那个）。 */
        public final String ore;
        /** 用来画按钮的图标；这个矿辞一个物品都没有时是 null。 */
        public final ItemStack icon;
        /** 仓库里实际存着的那个变体；为 null 表示「仓库没有，取不出来」。 */
        public final ItemKey stored;

        Entry(String ore, ItemStack icon, ItemKey stored) {
            this.ore = ore;
            this.icon = icon;
            this.stored = stored;
        }

        public boolean isAvailable() {
            return stored != null;
        }
    }

    private static int cachedRevision = Integer.MIN_VALUE;
    private static List<Entry> cached = new ArrayList<>();

    private CraftingToolShortcuts() {}

    /** 当前库存下的工具栏位（长度固定 = {@link #TOOLS} 的长度，顺序相同）。 */
    public static List<Entry> entries() {
        int revision = ClientStorageCache.getRevision();
        if (revision == cachedRevision) return cached;

        Map<String, ItemKey> storedByOre = new HashMap<>();
        if (ClientStorageCache.isReady()) {
            for (StorageViewEntry entry : ClientStorageCache.items()) {
                if (entry == null || entry.getAmount() <= 0L || entry.getItemKey() == null) continue;
                indexStored(entry.getItemKey(), storedByOre);
            }
        }

        List<Entry> fresh = new ArrayList<>(TOOLS.length);
        for (ToolDictNames tool : TOOLS) {
            String ore = tool.name();
            fresh.add(new Entry(ore, iconFor(ore), storedByOre.get(ore)));
        }

        cached = fresh;
        cachedRevision = revision;
        return cached;
    }

    /**
     * 把这个条目登记到它命中的每一条 {@code craftingTool...} 矿辞上。
     *
     * <p>
     * 矿辞查询对个别模组物品会抛（它们在 {@code getOreIDs} 里做懒加载），
     * 所以整段包起来 —— 一个物品查不了不该让整排按钮消失。
     */
    private static void indexStored(ItemKey key, Map<String, ItemKey> out) {
        try {
            for (int id : OreDictionary.getOreIDs(key.prototype())) {
                String name = OreDictionary.getOreName(id);
                if (name == null || !name.startsWith("craftingTool")) continue;
                // 同一矿辞有多个变体（青铜锤 / 钢锤 / 钨钢锤）时取列表里第一个：
                // 仓库列表本身排过序，所以同一次库存下结果稳定，不会每帧换一把
                if (!out.containsKey(name)) out.put(name, key);
            }
        } catch (Throwable ignored) {
            // 这个物品的矿辞查不了：跳过它，其余照常
        }
    }

    /** 拿一个代表栈当图标（GT 工具图标就是这个物品的图标）。 */
    private static ItemStack iconFor(String ore) {
        try {
            List<ItemStack> ores = OreDictionary.getOres(ore);
            for (ItemStack stack : ores) {
                if (stack != null && stack.getItem() != null) return stack;
            }
        } catch (Throwable ignored) {
            // 矿辞查不了：这个位置就没有图标可画
        }
        return null;
    }

    /** 按钮悬停时显示的名字：直接用那个图标自己的显示名（跟着语言走，不用再写一份翻译）。 */
    public static String displayName(Entry entry) {
        if (entry == null || entry.icon == null) return "";
        try {
            return entry.icon.getDisplayName();
        } catch (Throwable t) {
            return entry.ore;
        }
    }
}
