package com.futa_gtnh.client;

import java.util.Locale;

/**
 * NotEnoughCharacters（NEChar）拼音检索桥。
 *
 * <p>
 * 玩家装了 NEChar（modid {@code nechar}）时，名字匹配直接交给它的 PinIn 上下文：
 * 拼音全拼/首字母、模糊音（zh→z、ang→an……）、GTNH 生僻字（钅卢这类私用区字）、
 * 电压名特搜（zpm/max/luv…）全是现成的，而且<b>跟随玩家自己的 NEChar 配置</b> ——
 * 不用重复造轮子。没装 NEChar 时退回本模组自带的 pinyin.txt 后缀匹配
 * （见 {@link Pinyin} / {@code StorageViewEntry#getSearchName}）。
 *
 * <p>
 * <b>隔离规则：所有出现 NEChar 类型的代码只能存在于 {@link Hook} 这一个嵌套类里。</b>
 * 嵌套类是懒加载的 —— 不调用 {@link #matches} 就永远不会触发它的类加载，
 * 所以 NEChar 缺席时不会炸 {@code NoClassDefFoundError}。外层入口全部
 * {@code try/catch} 兜底，NEChar 装了但版本对不上时自动整体退回自研路径。
 */
public final class NecharBridge {

    private static boolean available;

    private NecharBridge() {}

    /**
     * NEChar 是否在场且可用。第一次调用时探测，之后缓存。
     *
     * <p>
     * 除了 modid 检查还额外按类名探了一次： FML 的 modid 列表在极早期
     * （构建搜索索引太早的话）可能还没填好，双保险。
     */
    public static boolean isAvailable() {
        return available;
    }

    /** 在客户端生命周期的合适时机（postInit 之后）调用一次，探测并缓存结果。 */
    public static void init() {
        if (available) return;
        try {
            available = cpw.mods.fml.common.Loader.isModLoaded("nechar")
                && probeClass("net.moecraft.nechar.NecharUtils")
                && probeClass("me.towdium.pinin.PinIn");
        } catch (Throwable t) {
            available = false;
        }
    }

    private static boolean probeClass(String name) {
        try {
            Class.forName(name, false, NecharBridge.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 名字匹配（等价于「source 里能不能找到 query」）。
     *
     * <p>
     * NEChar 不在场时恒返回 {@code false}，调用方应当继续走自研的后缀匹配 ——
     * 这样两条路径天然互斥，不会出现「两边都判了一遍」的浪费。
     *
     * <p>
     * 两边都先转小写再交给它：汉字没有大小写，英文统一小写就是大小写不敏感，
     * 不依赖 PinIn 内部对大小写的处理方式。
     */
    public static boolean matches(String sourceText, String searchText) {
        if (!available || sourceText == null || searchText == null || searchText.isEmpty()) return false;
        try {
            return Hook.contain(sourceText.toLowerCase(Locale.ROOT), searchText.toLowerCase(Locale.ROOT));
        } catch (Throwable t) {
            // NEChar 装了但行为不符合预期（版本差异等）—— 关掉桥接，之后全走自研
            available = false;
            return false;
        }
    }

    /** 唯一允许引用 NEChar 类型的类；NEChar 缺席时它永远不会被加载。 */
    private static final class Hook {

        static boolean contain(String sourceText, String searchText) {
            // 第三个参数 = 电压名特搜（zpm/max/ulv…uxv），对 GTNH 的机器名很有用；
            // NEChar 自己注明这条路径「可能很慢」，但它只在普通匹配失败后才走
            return net.moecraft.nechar.NecharUtils.contain(sourceText, searchText, true);
        }
    }
}
