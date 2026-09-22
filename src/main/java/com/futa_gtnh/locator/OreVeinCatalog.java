package com.futa_gtnh.locator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;

import gregtech.api.interfaces.IOreMaterial;
import gregtech.common.WorldgenGTOreLayer;

/**
 * GT 矿脉类型的目录。
 *
 * <p>
 * 「矿脉」（vein / ore mix）和「矿石方块」是两回事：一条矿脉由<b>四种材料</b>组成
 * （主 / 次 / 中 / 散，见 {@link WorldgenGTOreLayer#mPrimary} 那四个字段），
 * 铺成一个横跨好几个区块的椭球。所以按方块搜是很别扭的 ——
 * 你想找的是「铁矿脉」，而不是「某个石种下的那块铁矿石」。
 *
 * <p>
 * 这个类负责把 GT 的矿脉列表包装成能直接塞进界面和网络包的东西：一份稳定的键
 * （用 GT 自己的 {@code getName()}）、一个给玩家看的名字、一个图标。
 *
 * <p>
 * <b>显示名是自己拼的，不是 GT 给的。</b> GT 那边的名字是 {@code ore.mix.iron}
 * 这样的语言键，而且它只存在 {@code OreMixBuilder} 上 —— {@code WorldgenGTOreLayer}
 * 构造完就把它丢了（{@code getName()} 返回的是内部名）。
 * GT 的 NEI 插件是拿材料名现拼的，这里照做。
 *
 * <p>
 * 整个类都直接引用 GT 的类型，调用方一律用 {@code try/catch} 包住：
 * 换到一个没有这套 API 的 GT 版本时，界面上「矿脉」页签空着就行，不该崩。
 */
public final class OreVeinCatalog {

    private OreVeinCatalog() {}

    /** 界面/网络包里用的一个矿脉条目。 */
    public static final class Entry {

        private final WorldgenGTOreLayer layer;
        private final String key;
        private final String title;
        private final String materials;
        private final String search;

        /**
         * 界面图标。{@link #getIcon()} 偷懒算一次就存下来。
         *
         * <p>
         * <b>必须缓存。</b>每次算它都要向 GT 借一个 {@code OreInfo} 再合成一个
         * {@code ItemStack}，而界面是<b>每帧、每个可见格子</b>都要图标的 ——
         * 63 个格子 × 每秒 60 帧，不缓存就是每秒四千个垃圾对象。
         */
        private ItemStack icon;
        private boolean iconResolved;

        /** 生成高度区间，同样按维度缓存一次。 */
        private String rangeDimension;
        private int[] range;

        Entry(WorldgenGTOreLayer layer) {
            this.layer = layer;
            this.key = layer.getName();
            this.title = buildTitle(layer);
            this.materials = buildMaterials(layer);
            // 搜索文本把标题和材料都算上：搜「镍」应该能搜到「镍矿脉」，
            // 但也应该能搜到「铂矿脉」（它的次要材料里有镍）
            this.search = (title + ' ' + materials).toLowerCase(Locale.ROOT);
        }

        /** 服务端用它来认回是哪条矿脉。 */
        public String getKey() {
            return key;
        }

        /** 给玩家看的名字，例如「铁矿脉」。 */
        public String getTitle() {
            return title;
        }

        /** 四个材料的列表，例如「铁 / 镍 / 锡 / 锌」。 */
        public String getMaterials() {
            return materials;
        }

        String getSearchText() {
            return search;
        }

        WorldgenGTOreLayer getLayer() {
            return layer;
        }

        /** @return 主材料的矿石物品；拿不到时返回 null（结果会被记住，不会反复重试） */
        ItemStack getIcon() {
            if (!iconResolved) {
                iconResolved = true;
                icon = GtOreSupport.oreIcon(layer.mPrimary);
            }
            return icon;
        }

        /**
         * @return 这个维度下的生成高度区间；拿不到时返回 null
         */
        int[] getRange(String dimensionName) {
            if (dimensionName == null) return null;
            if (range == null || !dimensionName.equals(rangeDimension)) {
                rangeDimension = dimensionName;
                try {
                    range = new int[] { layer.getMinY(dimensionName), layer.getMaxY(dimensionName) };
                } catch (Throwable t) {
                    range = null;
                }
            }
            return range;
        }
    }

    private static List<Entry> entries;
    private static Map<String, Entry> byKey;
    private static boolean failed;
    /** 上一次建表的时间，用来给「建出来是空的」加冷却。 */
    private static long lastBuildMillis;

    /** 空表重试的冷却时间（毫秒）。 */
    private static final long EMPTY_RETRY_COOLDOWN = 2000L;

    // ==================================================================
    // 目录
    // ==================================================================

    /** @return GT 的矿脉列表能不能用（界面据此决定要不要显示「矿脉」页签） */
    public static boolean isAvailable() {
        return !all().isEmpty();
    }

    /**
     * @return 全部矿脉条目，按显示名排序；拿不到 GT 数据时返回空列表
     */
    public static List<Entry> all() {
        ensureBuilt();
        return entries;
    }

    /** @return 找不到同名矿脉时返回 null */
    public static Entry byKey(String key) {
        ensureBuilt();
        return byKey.get(key);
    }

    /**
     * 保证表建好了。
     *
     * <p>
     * <b>建出来是空表的话会允许重试。</b>GT 是把自己的矿脉列表在 postload 阶段填进
     * {@code sList} 的，如果本模组哪个钩子跑在它前面，读到的就是一张空表 ——
     * 把空结果缓存一整个进程的话，界面上「矿脉」页签会永远是空的，而且查不出原因。
     * 所以空表只锁 2 秒，之后再来一次。加冷却是因为这个方法在界面里是每帧调的。
     */
    private static void ensureBuilt() {
        if (!failed && entries != null && !entries.isEmpty()) return;

        long now = System.currentTimeMillis();
        if (entries != null && now - lastBuildMillis < EMPTY_RETRY_COOLDOWN) return;

        lastBuildMillis = now;
        build();
    }

    private static void build() {
        List<Entry> list = new ArrayList<>();
        Map<String, Entry> map = new HashMap<>();

        if (!failed) {
            try {
                List<WorldgenGTOreLayer> layers = WorldgenGTOreLayer.sList;
                if (layers != null) {
                    for (WorldgenGTOreLayer layer : layers) {
                        // noOresInVein 是 GT 用来占位的哨兵层（表示「这个区块没有矿脉」），
                        // 它不该出现在玩家的选择列表里
                        if (layer == null || layer == gregtech.common.GTWorldgenerator.noOresInVein) continue;
                        if (layer.mPrimary == null) continue;

                        Entry entry = new Entry(layer);
                        if (entry.getKey() == null || map.containsKey(entry.getKey())) continue;
                        list.add(entry);
                        map.put(entry.getKey(), entry);
                    }
                }
                FutaGtnhMod.LOG.info("寻物魔杖：读到 {} 种 GT 矿脉", list.size());
            } catch (Throwable t) {
                failed = true;
                FutaGtnhMod.LOG.warn("拿不到 GT 的矿脉列表，寻物魔杖将只提供方块搜索", t);
                list.clear();
                map.clear();
            }
        }

        Collections.sort(list, new Comparator<Entry>() {

            @Override
            public int compare(Entry a, Entry b) {
                return a.title.compareTo(b.title);
            }
        });

        entries = list;
        byKey = map;
    }

    /**
     * 只保留能在该维度生成的矿脉。
     *
     * <p>
     * 不过滤的话，在下界里会看到一堆主世界专属的矿脉，选了一个只会得到
     * 「找不到」—— 那是纯粹的噪音。判断失败时<b>不过滤</b>：
     * 宁可多列几条，也不要因为拿不到维度名就把整个列表清空。
     */
    public static List<Entry> forWorld(World world) {
        List<Entry> all = all();
        if (world == null || all.isEmpty()) return all;

        String dimensionName;
        try {
            dimensionName = galacticgreg.api.enums.DimensionDef.getDimensionName(world);
        } catch (Throwable t) {
            return all;
        }
        if (dimensionName == null) return all;

        List<Entry> filtered = new ArrayList<>(all.size());
        for (Entry entry : all) {
            try {
                if (entry.layer.canGenerateIn(dimensionName)) filtered.add(entry);
            } catch (Throwable t) {
                filtered.add(entry); // 单条判断失败就当它能生成
            }
        }
        return filtered;
    }

    // ==================================================================
    // 显示
    // ==================================================================

    /**
     * @return 主材料的矿石物品，用来当图标；拿不到时返回 null
     */
    public static ItemStack iconOf(Entry entry) {
        return entry == null ? null : entry.getIcon();
    }

    /** @return 这条矿脉是不是根本不适合在给定维度里找 */
    public static boolean canGenerateIn(Entry entry, World world) {
        if (entry == null || world == null) return true;
        try {
            String dimensionName = galacticgreg.api.enums.DimensionDef.getDimensionName(world);
            return dimensionName == null || entry.layer.canGenerateIn(dimensionName);
        } catch (Throwable t) {
            return true;
        }
    }

    /** @return 矿脉的生成高度范围，没有可用数据时返回 null */
    public static int[] heightRange(Entry entry, World world) {
        if (entry == null) return null;
        try {
            return entry.getRange(galacticgreg.api.enums.DimensionDef.getDimensionName(world));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String buildTitle(WorldgenGTOreLayer layer) {
        String material = localName(layer.mPrimary);
        return StatCollector.translateToLocalFormatted("futa_gtnh.locator.vein.name", material);
    }

    /**
     * 四个材料的列表。用「 / 」分隔，和 GT 的 NEI 插件一个风格。
     *
     * <p>
     * 重复的材料会去掉：GT 里有些矿脉的次要材料和主材料是同一个
     * （单材料矿脉就是这么写的），列两遍没有意义。
     */
    private static String buildMaterials(WorldgenGTOreLayer layer) {
        StringBuilder builder = new StringBuilder();
        appendMaterial(builder, layer.mPrimary);
        appendMaterial(builder, layer.mSecondary);
        appendMaterial(builder, layer.mBetween);
        appendMaterial(builder, layer.mSporadic);
        return builder.toString();
    }

    private static void appendMaterial(StringBuilder builder, IOreMaterial material) {
        if (material == null) return;
        String name = localName(material);
        if (name.isEmpty()) return;
        // 去重：只在已经写进去的片段里找，避免「铁 / 铁」
        if (builder.length() > 0) {
            String[] parts = builder.toString()
                .split(" / ");
            for (String part : parts) {
                if (part.equals(name)) return;
            }
            builder.append(" / ");
        }
        builder.append(name);
    }

    private static String localName(IOreMaterial material) {
        if (material == null) return "";
        try {
            String name = material.getLocalizedName();
            return name == null ? material.getInternalName() : name;
        } catch (Throwable t) {
            return "";
        }
    }
}
