package com.futa_gtnh.block;

import java.util.ArrayList;
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
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * 「共享终端方块往哪个面主动搬东西」的配置：方向 + 筛选。
 *
 * <p>
 * 每个面、每种东西（物品 / 流体）各自有一个方向：
 * <ul>
 * <li>{@link Mode#OFF}：什么都不做；</li>
 * <li>{@link Mode#PULL}：把<b>相邻容器</b>里的东西搬进共享存储（主动抽取）；</li>
 * <li>{@link Mode#PUSH}：把<b>共享存储</b>里的东西搬到相邻容器（主动输出）。</li>
 * </ul>
 * 物品和流体分开配，是因为「同一个面抽物品、同时往同一个面输出流体」是很常见的组合
 * （GT 的覆盖板也是这么分的）。
 *
 * <p>
 * <b>筛选是一张白名单，两个方向都管</b>：列表为空表示什么都搬；一旦选了东西，
 * 就只搬符合条件的那几种。之所以抽取也管，是因为「别把垃圾吸进来」和
 * 「只输出这几种」是同一个诉求的两面 —— 只对输出生效的话，玩家还得再找一个开关
 * 去挡抽取，反而多了个概念。
 *
 * <p>
 * 筛选条件有三种，<b>任意一种命中就算通过</b>：
 * <ol>
 * <li>具体的物品 / 流体条目（可以选多个）；</li>
 * <li>{@link Preset 矿辞预设}（原矿、粉碎的矿、洗净的矿、含杂的矿、热力离心后的矿）；</li>
 * <li>自己敲的矿辞前缀（例如 {@code ingot}、{@code plate}）。</li>
 * </ol>
 *
 * <p>
 * <b>矿辞是「按最具体的前缀归类」的。</b>这一点必须说清楚，因为 GT 的前缀天生重叠：
 * {@code crushedIron}（粉碎的矿）是 {@code crushedPurifiedIron}（洗净的矿）的<b>前缀</b>。
 * 如果直接 {@code startsWith} 判断，勾了「粉碎的矿」就会把洗净的、离心的也一起放进来。
 * 所以这里先在所有已知前缀（5 个预设的前缀 + 自己敲的）里找出<b>最长</b>的那个匹配，
 * 再问「那一个是不是被勾上了」—— {@code crushedPurifiedIron} 归到
 * {@code crushedPurified}，于是勾「粉碎的矿」不会误伤它。
 */
public final class TerminalIoConfig {

    /** 一个面、一种东西的搬运方向。 */
    public enum Mode {

        OFF("futa_gtnh.gui.terminal.io.mode.off"),
        PULL("futa_gtnh.gui.terminal.io.mode.pull"),
        PUSH("futa_gtnh.gui.terminal.io.mode.push");

        private final String langKey;

        Mode(String langKey) {
            this.langKey = langKey;
        }

        public String getLangKey() {
            return langKey;
        }

        /** 循环切换：关 → 抽 → 输 → 关。界面上的按钮点一下就往下走一格。 */
        public Mode next() {
            Mode[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /**
     * 默认的矿辞预设。
     *
     * <p>
     * 前缀都对着 GT 的 {@code OrePrefixes} 抄的（连大小写）：GT 的矿辞名就是
     * 「前缀 + 材料名」，所以 {@code crushed} + {@code Iron} = {@code crushedIron}。
     * 「原矿」给了两个前缀：GTNH 2.7 之后矿石掉的是<b>粗矿</b>（{@code rawOreIron}），
     * 而老版本和部分模组用的还是<b>矿石方块</b>（{@code oreIron}）——
     * 两个都认，免得玩家在两种世界里得到一个「勾了没反应」的预设。
     */
    public enum Preset {

        RAW_ORE("futa_gtnh.gui.terminal.io.preset.raw", "rawOre", "ore"),
        CRUSHED("futa_gtnh.gui.terminal.io.preset.crushed", "crushed"),
        PURIFIED("futa_gtnh.gui.terminal.io.preset.purified", "crushedPurified"),
        IMPURE("futa_gtnh.gui.terminal.io.preset.impure", "dustImpure"),
        CENTRIFUGED("futa_gtnh.gui.terminal.io.preset.centrifuged", "crushedCentrifuged");

        private final String langKey;
        private final String[] prefixes;

        Preset(String langKey, String... prefixes) {
            this.langKey = langKey;
            this.prefixes = prefixes;
        }

        public String getLangKey() {
            return langKey;
        }

        public String[] getPrefixes() {
            return prefixes;
        }
    }

    /** 面的数量，等于 {@code ForgeDirection} 的取值个数。 */
    public static final int FACES = 6;

    private final Mode[] itemModes = new Mode[FACES];
    private final Mode[] fluidModes = new Mode[FACES];

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

    public TerminalIoConfig() {
        for (int i = 0; i < FACES; i++) {
            itemModes[i] = Mode.OFF;
            fluidModes[i] = Mode.OFF;
        }
    }

    // ==================================================================
    // 方向
    // ==================================================================

    public Mode getMode(ForgeDirection face, boolean fluid) {
        int index = index(face);
        return index < 0 ? Mode.OFF : (fluid ? fluidModes[index] : itemModes[index]);
    }

    public void setMode(ForgeDirection face, boolean fluid, Mode mode) {
        int index = index(face);
        if (index < 0 || mode == null) return;
        if (fluid) {
            fluidModes[index] = mode;
        } else {
            itemModes[index] = mode;
        }
    }

    /** @return 有没有任何一个面配了动作。没有的话整个搬运逻辑直接跳过。 */
    public boolean hasAnyMode() {
        for (int i = 0; i < FACES; i++) {
            if (itemModes[i] != Mode.OFF || fluidModes[i] != Mode.OFF) return true;
        }
        return false;
    }

    private static int index(ForgeDirection face) {
        if (face == null) return -1;
        int ordinal = face.ordinal();
        return ordinal >= 0 && ordinal < FACES ? ordinal : -1;
    }

    // ==================================================================
    // 筛选
    // ==================================================================

    public boolean isFilterEmpty() {
        return items.isEmpty() && fluids.isEmpty() && presets.isEmpty() && customPrefixes.isEmpty();
    }

    public Set<ItemKey> getItems() {
        return items;
    }

    public Set<FluidKey> getFluids() {
        return fluids;
    }

    public Set<Preset> getPresets() {
        return presets;
    }

    public List<String> getCustomPrefixes() {
        return customPrefixes;
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

    public void clearFilter() {
        items.clear();
        fluids.clear();
        presets.clear();
        customPrefixes.clear();
        touch();
    }

    private void touch() {
        revision++;
    }

    /** @return 这个物品能不能搬（过滤条件为空 = 什么都行） */
    public boolean matches(ItemStack stack) {
        if (isFilterEmpty()) return true;
        if (stack == null || stack.getItem() == null) return false;

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
        if (isFilterEmpty()) return true;
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

    // ==================================================================
    // 存档 / 网络
    // ==================================================================

    public NBTTagCompound writeToNbt() {
        NBTTagCompound tag = new NBTTagCompound();

        byte[] itemBytes = new byte[FACES];
        byte[] fluidBytes = new byte[FACES];
        for (int i = 0; i < FACES; i++) {
            itemBytes[i] = (byte) itemModes[i].ordinal();
            fluidBytes[i] = (byte) fluidModes[i].ordinal();
        }
        tag.setByteArray("itemModes", itemBytes);
        tag.setByteArray("fluidModes", fluidBytes);

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
        if (tag == null) return;

        clearFilter();
        for (int i = 0; i < FACES; i++) {
            itemModes[i] = Mode.OFF;
            fluidModes[i] = Mode.OFF;
        }

        byte[] itemBytes = tag.getByteArray("itemModes");
        byte[] fluidBytes = tag.getByteArray("fluidModes");
        Mode[] values = Mode.values();
        for (int i = 0; i < FACES; i++) {
            if (itemBytes.length > i && itemBytes[i] >= 0 && itemBytes[i] < values.length) {
                itemModes[i] = values[itemBytes[i]];
            }
            if (fluidBytes.length > i && fluidBytes[i] >= 0 && fluidBytes[i] < values.length) {
                fluidModes[i] = values[fluidBytes[i]];
            }
        }

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
