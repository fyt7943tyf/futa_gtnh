package com.futa_gtnh.client;

import java.util.function.Predicate;

import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;

/**
 * 共享存储搜索与 NEI 搜索条的可选联动桥。
 *
 * <p>
 * NEI 是可选联动：模组本体的类里<b>不能出现任何 {@code codechicken.nei} 引用</b>
 * （没装 NEI 时加载就是 {@code NoClassDefFoundError}），搜索与同步的调用点
 * 必须随时可加载。因此用只依赖本模组和 Minecraft 类型的接口隔离 NEI：
 *
 * <ul>
 * <li>界面侧通过桥编译搜索规则、转义拖入名称或同步文字，不引用 NEI 类型；</li>
 * <li>真正碰 {@code LayoutManager.searchField} 的实现在 {@code client.nei} 包里，
 * 由 {@code NeiIntegration.register()} 在「确认装了 NEI」之后安装进来；</li>
 * <li>没装 NEI 时桥是空的，同步是空操作，过滤使用本地回退。</li>
 * </ul>
 */
public final class NeiSearchBridge {

    /** NEI 在场时由 {@code client.nei} 包提供的实现；方法只允许在客户端线程调用。 */
    public interface Impl {

        /** NEI 的搜索条此刻是否存在（第一张 GUI 打开之前可能还没建出来）。 */
        boolean searchFieldExists();

        /** 把共享背包的搜索词推给 NEI（实现内部自行做相等判断，避免重复触发过滤）。 */
        void pushSearchText(String text);

        /** 编译 NEI 搜索规则；返回 null 时使用本地回退，不依赖 NEI 面板是否显示。 */
        default Predicate<StorageViewEntry> compileFilter(String text) {
            return null;
        }

        /** 配置或提供器变更后身份改变，用于刷新已有查询的结果。 */
        default Object configurationToken() {
            return this;
        }

        /** 拖入名称按当前搜索语法转义；无 NEI 时保留普通名称。 */
        default String escapedSearchText(ItemStack stack) {
            return EnumChatFormatting.getTextWithoutFormattingCodes(stack.getDisplayName());
        }
    }

    private static volatile Impl impl;

    /** 由 {@code client.nei} 包在注册时调用；重复调用以后装的为准。 */
    public static void install(Impl bridge) {
        impl = bridge;
    }

    /** @return NEI 联动是否已注册（≈「装了 NEI」）。 */
    public static boolean isInstalled() {
        return impl != null;
    }

    public static boolean searchFieldExists() {
        Impl bridge = impl;
        return bridge != null && bridge.searchFieldExists();
    }

    public static void pushSearchText(String text) {
        Impl bridge = impl;
        if (bridge != null) bridge.pushSearchText(text);
    }

    public static Predicate<StorageViewEntry> compileFilter(String text) {
        Impl bridge = impl;
        return bridge == null ? null : bridge.compileFilter(text);
    }

    public static Object configurationToken() {
        Impl bridge = impl;
        return bridge == null ? null : bridge.configurationToken();
    }

    public static String escapedSearchText(ItemStack stack) {
        Impl bridge = impl;
        return bridge == null ? EnumChatFormatting.getTextWithoutFormattingCodes(stack.getDisplayName())
            : bridge.escapedSearchText(stack);
    }

    private NeiSearchBridge() {}
}
