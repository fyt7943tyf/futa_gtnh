package com.futa_gtnh.client;

import java.util.Locale;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidRegistry;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

import gregtech.api.util.GTUtility;

/**
 * 客户端视图里的一条共享存储条目。
 *
 * <p>
 * 只存在于客户端。它把「网络传过来的键 + 数量」加工成 GUI 需要的东西：
 * 一个用来画图标的 {@link ItemStack}、以及预转成小写的搜索文本。
 *
 * <p>
 * 搜索文本在构造时就算好并缓存，是因为搜索框每敲一个字符都会拿全部条目重新匹配一遍，
 * 几千个条目 × 每次按键都去调 {@code getDisplayName()} / 查矿辞，卡顿会非常明显。
 * 矿辞和 tooltip 属于「只有用到才贵」的那类，做成懒加载。
 */
public final class StorageViewEntry {

    private final ItemKey itemKey;
    private final FluidKey fluidKey;

    private long amount;
    private ItemStack display;

    private final String modId;
    private final String displayName;
    private final String searchName;
    private final String registryName;

    private String oreDictText;
    private boolean oreDictResolved;

    private String tooltipText;
    private boolean tooltipResolved;

    private StorageViewEntry(ItemKey itemKey, FluidKey fluidKey, long amount, ItemStack display, String modId,
        String displayName, String registryName) {
        this.itemKey = itemKey;
        this.fluidKey = fluidKey;
        this.amount = amount;
        this.display = display;
        this.modId = modId == null ? "" : modId;
        this.displayName = displayName == null ? "" : displayName;
        this.registryName = registryName == null ? "" : registryName;
        this.searchName = this.displayName.toLowerCase(Locale.ROOT);
    }

    public static StorageViewEntry ofItem(ItemKey key, long amount) {
        ItemStack prototype = key.prototype();
        String registry = String.valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(key.getItem()));
        return new StorageViewEntry(key, null, amount, prototype, modIdOf(registry), safeName(prototype), registry);
    }

    /**
     * @return 流体条目；GT 造不出显示物品时返回 null（调用方应当跳过这一条）
     */
    public static StorageViewEntry ofFluid(FluidKey key, long amount) {
        // 显示物品带上真实数量：GT 的 tooltip 会把它显示成「1000 L 水」这类信息，
        // 鼠标悬停时能直接看到，不额外花什么代价
        ItemStack display = GTUtility.getFluidDisplayStack(key.prototype(Math.min(amount, Integer.MAX_VALUE)), true);
        if (display == null || display.getItem() == null) {
            return null;
        }
        String registry = String.valueOf(FluidRegistry.getFluidName(key.getFluid()));
        String name = safeFluidName(key);
        return new StorageViewEntry(null, key, amount, display, modIdOf(registry), name, registry);
    }

    private static String safeName(ItemStack stack) {
        try {
            String name = stack.getDisplayName();
            return name == null ? "" : name;
        } catch (Throwable t) {
            // 个别模组的物品在拿显示名时会抛异常（少见但确实存在），
            // 不能让一条坏数据把整个界面的构建搞崩
            return "";
        }
    }

    private static String safeFluidName(FluidKey key) {
        try {
            String name = key.getFluid()
                .getLocalizedName(key.prototype());
            if (name == null || name.isEmpty()) {
                name = FluidRegistry.getFluidName(key.getFluid());
            }
            return name == null ? "" : name;
        } catch (Throwable t) {
            return String.valueOf(FluidRegistry.getFluidName(key.getFluid()));
        }
    }

    /** {@code "gregtech:gt.metaitem.01"} -> {@code "gregtech"} */
    private static String modIdOf(String registryName) {
        if (registryName == null) return "";
        int colon = registryName.indexOf(':');
        return colon <= 0 ? registryName : registryName.substring(0, colon);
    }

    // ------------------------------------------------------------------

    public boolean isFluid() {
        return fluidKey != null;
    }

    public ItemKey getItemKey() {
        return itemKey;
    }

    public FluidKey getFluidKey() {
        return fluidKey;
    }

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public ItemStack getDisplay() {
        return display;
    }

    public String getModId() {
        return modId;
    }

    /** 模组的人类可读名字，用于排序和 {@code @mod} 搜索。 */
    public String getModName() {
        try {
            cpw.mods.fml.common.ModContainer container = cpw.mods.fml.common.Loader.instance()
                .getIndexedModList()
                .get(modId);
            if (container != null) {
                return container.getName();
            }
        } catch (Throwable ignored) {
            // Loader 在极早期可能还没就绪，退回 modId 即可
        }
        return modId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRegistryName() {
        return registryName;
    }

    /** 普通文本搜索用的目标串（已是小写）。 */
    public String getSearchName() {
        return searchName;
    }

    /**
     * 矿辞名字串（小写、空格分隔），懒加载。
     *
     * <p>
     * {@code OreDictionary.getOreIDs} 对每个物品都要构造一次 NBT 键去查表，
     * 几千个条目一次性算完会明显卡一下，所以只在玩家真的用了 {@code *} 前缀时才做，
     * 做完缓存住。
     */
    public String getOreDictText() {
        if (oreDictResolved) return oreDictText == null ? "" : oreDictText;
        oreDictResolved = true;

        if (itemKey == null) {
            oreDictText = "";
            return oreDictText;
        }

        try {
            int[] ids = net.minecraftforge.oredict.OreDictionary.getOreIDs(itemKey.prototype());
            if (ids == null || ids.length == 0) {
                oreDictText = "";
            } else {
                StringBuilder builder = new StringBuilder();
                for (int id : ids) {
                    String name = net.minecraftforge.oredict.OreDictionary.getOreName(id);
                    if (name == null || "Unknown".equals(name)) continue;
                    if (builder.length() > 0) builder.append(' ');
                    builder.append(name.toLowerCase(Locale.ROOT));
                }
                oreDictText = builder.toString();
            }
        } catch (Throwable t) {
            oreDictText = "";
        }
        return oreDictText;
    }

    /** tooltip 拼接串（小写），懒加载并缓存；用于 {@code #} 前缀搜索。 */
    public String getTooltipText() {
        if (tooltipResolved) return tooltipText == null ? "" : tooltipText;
        tooltipResolved = true;

        try {
            java.util.List<String> lines = display
                .getTooltip(net.minecraft.client.Minecraft.getMinecraft().thePlayer, false);
            if (lines == null || lines.isEmpty()) {
                tooltipText = "";
                return tooltipText;
            }
            StringBuilder builder = new StringBuilder();
            for (String line : lines) {
                if (line == null) continue;
                if (builder.length() > 0) builder.append(' ');
                builder.append(net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(line));
            }
            tooltipText = builder.toString()
                .toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            tooltipText = "";
        }
        return tooltipText;
    }

    /** 供调试和日志用。 */
    @Override
    public String toString() {
        return (isFluid() ? "Fluid(" + registryName + ")" : "Item(" + registryName + ")") + " x " + amount;
    }
}
