package com.futa_gtnh.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.block.TerminalIoConfig.Preset;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

import appeng.util.item.AEItemStack;
import appeng.util.prioitylist.OreFilteredList;

/** 一个面的输出白名单；物品和流体独立，空白名单禁止输出，输入不使用它。 */
public final class TerminalOutputFilter {

    private final Set<ItemKey> items = new HashSet<>();
    private final Set<FluidKey> fluids = new HashSet<>();
    private final Set<Preset> presets = new HashSet<>();
    /** 原样保存 AE2 矿典过滤卡表达式，大小写和空格都属于输入内容。 */
    private String oreFilter = "";

    /** 筛选条件变一次加一；匹配结果的小缓存靠它失效。 */
    private int revision = 1;

    /** 每份表达式使用 AE2 自己的物品匹配缓存。 */
    private final List<OreFilteredList> oreMatchers = new ArrayList<>();
    private int oreMatcherRevision = -1;

    int getRevision() {
        return revision;
    }

    public boolean isEmpty() {
        return isEmpty(false) && isEmpty(true);
    }

    public Set<ItemKey> getItems() {
        return Collections.unmodifiableSet(items);
    }

    public Set<FluidKey> getFluids() {
        return Collections.unmodifiableSet(fluids);
    }

    public Set<Preset> getPresets() {
        return Collections.unmodifiableSet(presets);
    }

    public String getOreFilter() {
        return oreFilter;
    }

    public boolean containsItem(ItemKey key) {
        return key != null && items.contains(key);
    }

    public boolean containsFluid(FluidKey key) {
        return key != null && fluids.contains(key);
    }

    public void toggleItem(ItemKey key) {
        if (key == null) return;
        if (!items.remove(key)) items.add(key);
        touch();
    }

    public void toggleFluid(FluidKey key) {
        if (key == null) return;
        if (!fluids.remove(key)) fluids.add(key);
        touch();
    }

    public void togglePreset(Preset preset) {
        if (preset == null) return;
        if (!presets.remove(preset)) presets.add(preset);
        touch();
    }

    /** 不拆词、不改大小写；解析和错误处理全部交给 AE2。 */
    public void setOreFilter(String text) {
        oreFilter = text == null ? "" : text;
        touch();
    }

    /** 只清空当前类型；另一类输出白名单不受影响。 */
    public void clear(boolean fluid) {
        if (fluid) {
            fluids.clear();
        } else {
            items.clear();
            presets.clear();
            oreFilter = "";
        }
        touch();
    }

    public boolean isEmpty(boolean fluid) {
        return fluid ? fluids.isEmpty()
            : items.isEmpty() && presets.isEmpty()
                && oreFilter.trim()
                    .isEmpty();
    }

    public void clear() {
        items.clear();
        fluids.clear();
        presets.clear();
        oreFilter = "";
        touch();
    }

    private void touch() {
        revision++;
    }

    /** @return 这个物品能不能搬（白名单为空 = 禁止输出） */
    public boolean matches(ItemStack stack) {
        if (stack == null || stack.getItem() == null || isEmpty(false)) return false;

        ItemKey key = ItemKey.of(stack);
        if (key == null) return false;
        if (items.contains(key)) return true;

        return matchesOreDict(stack);
    }

    /** @return 这种流体能不能搬 */
    public boolean matches(FluidStack stack) {
        if (stack == null || stack.getFluid() == null) return false;

        FluidKey key = FluidKey.of(stack);
        // 流体没有矿辞，只能按条目选
        return key != null && fluids.contains(key);
    }

    private boolean matchesOreDict(ItemStack stack) {
        if (oreMatcherRevision != revision) {
            oreMatchers.clear();
            if (!oreFilter.trim()
                .isEmpty()) oreMatchers.add(new OreFilteredList(oreFilter));
            for (Preset preset : Preset.values()) {
                if (presets.contains(preset)) oreMatchers.add(new OreFilteredList(preset.getExpression()));
            }
            oreMatcherRevision = revision;
        }
        if (oreMatchers.isEmpty()) return false;
        AEItemStack aeStack = AEItemStack.create(stack);
        for (OreFilteredList matcher : oreMatchers) {
            if (matcher.isListed(aeStack)) return true;
        }
        return false;
    }

    /**
     * 仅用于读取旧存档：把旧的忽略大小写、最长前缀归类转换成等价 AE2 正则。
     * 已启用前缀排除所有更长的已知前缀，再将各分支取并集；以后只保存新表达式。
     */
    private void migrateLegacyPrefixes(NBTTagList prefixList) {
        Set<String> known = new java.util.TreeSet<>();
        Set<String> enabled = new java.util.TreeSet<>();
        for (Preset preset : Preset.values()) {
            for (String prefix : preset.getPrefixes()) {
                String lower = prefix.toLowerCase(Locale.ROOT);
                known.add(lower);
                if (presets.contains(preset)) enabled.add(lower);
            }
        }
        for (int i = 0; i < prefixList.tagCount(); i++) {
            String prefix = prefixList.getStringTagAt(i)
                .toLowerCase(Locale.ROOT);
            if (prefix.isEmpty()) continue;
            known.add(prefix);
            enabled.add(prefix);
        }
        StringBuilder expression = new StringBuilder();
        for (String prefix : enabled) {
            if (expression.length() > 0) expression.append('|');
            expression.append(Pattern.quote(prefix));
            StringBuilder excluded = new StringBuilder();
            for (String longer : known) {
                if (longer.length() <= prefix.length() || !longer.startsWith(prefix)) continue;
                if (excluded.length() > 0) excluded.append('|');
                excluded.append(Pattern.quote(longer.substring(prefix.length())));
            }
            if (excluded.length() > 0) expression.append("(?!")
                .append(excluded)
                .append(')');
        }
        oreFilter = expression.length() == 0 ? "" : "(?i)^(?:" + expression + ")";
        presets.clear();
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList itemList = new NBTTagList();
        for (ItemKey key : items) {
            itemList.appendTag(key.writeToNbt());
        }
        tag.setTag("items", itemList);

        NBTTagList fluidList = new NBTTagList();
        for (FluidKey key : fluids) {
            fluidList.appendTag(key.writeToNbt());
        }
        tag.setTag("fluids", fluidList);

        NBTTagList presetList = new NBTTagList();
        for (Preset preset : presets) {
            presetList.appendTag(new NBTTagString(preset.name()));
        }
        tag.setTag("presets", presetList);

        tag.setString("oreFilter", oreFilter);

        return tag;
    }

    public void readFromNbt(NBTTagCompound tag) {
        clear();
        if (tag == null) return;
        NBTTagList itemList = tag.getTagList("items", 10);
        for (int i = 0; i < itemList.tagCount(); i++) {
            ItemKey key = ItemKey.readFromNbt(itemList.getCompoundTagAt(i));
            if (key != null) items.add(key);
        }

        NBTTagList fluidList = tag.getTagList("fluids", 10);
        for (int i = 0; i < fluidList.tagCount(); i++) {
            FluidKey key = FluidKey.readFromNbt(fluidList.getCompoundTagAt(i));
            if (key != null) fluids.add(key);
        }

        NBTTagList presetList = tag.getTagList("presets", 8);
        for (int i = 0; i < presetList.tagCount(); i++) {
            String name = presetList.getStringTagAt(i);
            for (Preset preset : Preset.values()) {
                if (preset.name()
                    .equals(name)) {
                    presets.add(preset);
                    break;
                }
            }
        }

        if (tag.hasKey("oreFilter", 8)) {
            oreFilter = tag.getString("oreFilter");
        } else {
            migrateLegacyPrefixes(tag.getTagList("prefixes", 8));
        }

        touch();
    }
}
