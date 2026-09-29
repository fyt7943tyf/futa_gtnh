package com.futa_gtnh.client;

/**
 * 共享背包搜索框 → NEI 搜索条的推送桥。
 *
 * <p>
 * NEI 是可选联动：模组本体的类里<b>不能出现任何 {@code codechicken.nei} 引用</b>
 * （没装 NEI 时加载就是 {@code NoClassDefFoundError}），但「把搜索词推给 NEI」
 * 的调用点在界面代码里（{@code GuiSharedTerminal}），那个类无 NECI 可言、必须
 * 随时可加载。所以中间垫一层纯接口的桥：
 *
 * <ul>
 * <li>界面侧只调 {@link #pushSearchText} / {@link #isInstalled}，不认识 NEI；</li>
 * <li>真正碰 {@code LayoutManager.searchField} 的实现在 {@code client.nei} 包里，
 * 由 {@code NeiIntegration.register()} 在「确认装了 NEI」之后安装进来；</li>
 * <li>没装 NEI 时桥是空的，推送自动变成无害的空操作，搜索框行为退回单机模式。</li>
 * </ul>
 */
public final class NeiSearchBridge {

    /** NEI 在场时由 {@code client.nei} 包提供的实现；方法只允许在客户端线程调用。 */
    public interface Impl {

        /** NEI 的搜索条此刻是否存在（第一张 GUI 打开之前可能还没建出来）。 */
        boolean searchFieldExists();

        /** 把共享背包的搜索词推给 NEI（实现内部自行做相等判断，避免重复触发过滤）。 */
        void pushSearchText(String text);
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

    private NeiSearchBridge() {}
}
