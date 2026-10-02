package com.futa_gtnh.block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.block.TerminalIoConfig.Preset;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/** 一个面的输出白名单；物品和流体独立，空白名单禁止输出，输入不使用它。 */
public final class TerminalOutputFilter {

    private final Set<ItemKey> items = new HashSet<>();
    private final Set<FluidKey> fluids = new HashSet<>();
    private final Set<Preset> presets = new HashSet<>();
    /** 自己敲的矿辞前缀，一律转小写保存。 */
    private final List<String> customPrefixes = new ArrayList<>();

    /** 筛选条件变一次加一；匹配结果的小缓存靠它失效。 */
    private int revision = 1;

    /** 物品匹配结果缓存：一次搬运要问几百次同一个键，矿辞查表不值得反复做。 */
    private final Map<ItemKey, Boolean> itemCache = new HashMap<>();
    private int itemCacheRevision = -1;

    int getRevision() {
        return revision;
    }

    public boolean isEmpty() {
        return items.isEmpty() && fluids.isEmpty() && presets.isEmpty() && customPrefixes.isEmpty();
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

    public List<String> getCustomPrefixes() {
        return Collections.unmodifiableList(customPrefixes);
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

    /**
     * 换掉自定义前缀（界面里的输入框按回车/失焦时调一次）。
     *
     * <p>
     * 允许用空格、逗号、分号分隔，全部转小写 —— 矿辞名的前缀本身就是大小写混排
     * （{@code crushedPurified}），但玩家不会记得住，匹配时也一律按小写比。
     */
    public void setCustomPrefixes(String text) {
        customPrefixes.clear();
        if (text != null) {
            for (String piece : text.split("[\\s,;]+")) {
                String trimmed = piece.trim()
                    .toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty() && !customPrefixes.contains(trimmed)) {
                    customPrefixes.add(trimmed);
                }
            }
        }
        touch();
    }

    /** 只清空当前类型；另一类输出白名单不受影响。 */
    public void clear(boolean fluid) {
        if (fluid) {
            fluids.clear();
        } else {
            items.clear();
            presets.clear();
            customPrefixes.clear();
        }
        touch();
    }

    public boolean isEmpty(boolean fluid) {
        return fluid ? fluids.isEmpty() : items.isEmpty() && presets.isEmpty() && customPrefixes.isEmpty();
    }

    public void clear() {
        items.clear();
        fluids.clear();
        presets.clear();
        customPrefixes.clear();
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

        if (itemCacheRevision != revision) {
            itemCache.clear();
            itemCacheRevision = revision;
        }
        Boolean cached = itemCache.get(key);
        if (cached != null) return cached.booleanValue();

        boolean allowed = matchesOreDict(key.prototype());
        itemCache.put(key, Boolean.valueOf(allowed));
        return allowed;
    }

    /** @return 这种流体能不能搬 */
    public boolean matches(FluidStack stack) {
        if (stack == null || stack.getFluid() == null) return false;

        FluidKey key = FluidKey.of(stack);
        // 流体没有矿辞，只能按条目选
        return key != null && fluids.contains(key);
    }

    private boolean matchesOreDict(ItemStack stack) {
        String[] names = oreNames(stack);
        for (String name : names) {
            String classified = classify(name);
            if (classified != null && isPrefixEnabled(classified)) return true;
        }
        return false;
    }

    /**
     * 找出这个名字归到哪个前缀。
     *
     * <p>
     * 返回<b>最长</b>的那个匹配 —— 这就是「按最具体的前缀归类」，
     * 也是 {@code crushedIron} 和 {@code crushedPurifiedIron} 能被分开的原因。
     */
    private String classify(String lowerName) {
        String best = null;
        for (Preset preset : Preset.values()) {
            for (String prefix : preset.getPrefixes()) {
                String lower = prefix.toLowerCase(Locale.ROOT);
                if (lowerName.startsWith(lower) && (best == null || lower.length() > best.length())) {
                    best = lower;
                }
            }
        }
        for (String prefix : customPrefixes) {
            if (lowerName.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best;
    }

    private boolean isPrefixEnabled(String lowerPrefix) {
        for (String prefix : customPrefixes) {
            if (prefix.equals(lowerPrefix)) return true;
        }
        for (Preset preset : presets) {
            for (String prefix : preset.getPrefixes()) {
                if (prefix.toLowerCase(Locale.ROOT)
                    .equals(lowerPrefix)) return true;
            }
        }
        return false;
    }

    private static String[] oreNames(ItemStack stack) {
        try {
            int[] ids = OreDictionary.getOreIDs(stack);
            if (ids == null || ids.length == 0) return EMPTY_NAMES;

            String[] names = new String[ids.length];
            for (int i = 0; i < ids.length; i++) {
                String name = OreDictionary.getOreName(ids[i]);
                names[i] = name == null ? "" : name.toLowerCase(Locale.ROOT);
            }
            return names;
        } catch (Throwable t) {
            // 矿辞是别的模组在填，条目本身可能有毛病；判断不出来就当不匹配
            return EMPTY_NAMES;
        }
    }

    private static final String[] EMPTY_NAMES = new String[0];

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

        NBTTagList prefixList = new NBTTagList();
        for (String prefix : customPrefixes) {
            prefixList.appendTag(new NBTTagString(prefix));
        }
        tag.setTag("prefixes", prefixList);

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

        NBTTagList prefixList = tag.getTagList("prefixes", 8);
        for (int i = 0; i < prefixList.tagCount(); i++) {
            String prefix = prefixList.getStringTagAt(i)
                .toLowerCase(Locale.ROOT);
            if (!prefix.isEmpty() && !customPrefixes.contains(prefix)) {
                customPrefixes.add(prefix);
            }
        }

        touch();
    }
}
