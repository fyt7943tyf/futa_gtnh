package com.futa_gtnh.client;

import java.util.List;

import com.futa_gtnh.Config;

/**
 * 想跟 NEI 打交道的界面实现的<b>描述接口</b>（futa 自己的，不依赖任何 NEI 类）。
 *
 * <p>
 * NEI 的接口（{@code INEIGuiHandler}）不能直接挂在界面类上 —— 界面类无条件下
 * 都要能加载，而 {@code codechicken.nei} 只在装了 NEI 时才存在于 classpath。
 * 所以方向反过来：界面只声明「我想要什么」，真正的 NEI handler
 * （{@code client.nei.FutaNeiGuiHandler}，单例、只在 NEI 在场时注册）认得这个
 * 接口，把声明翻译成 NEI 的调用。任何界面实现了本接口，就自动获得完整的
 * NEI 联动，不需要再去 NEI 那边逐个注册。
 */
public interface NeiAwareGui {

    /**
     * 打开这个界面时要不要收起 NEI 物品面板。
     *
     * <p>
     * 默认按全局配置 {@link Config#terminalNeiPanel}（默认 SHOW，即不收起）。
     * 注意：收起面板后，NEI 在「搜索条跟随面板」布局下会把搜索条一起收掉，
     * 这是 NEI 自己的行为（{@code LayoutManager.updateWidgetVisiblities}），
     * 接口层管不到。
     */
    default boolean hideNeiItemPanel() {
        return Config.terminalNeiPanel == Config.TerminalNeiPanel.HIDE;
    }

    /**
     * 要遮住 NEI 物品面板格子的区域，<b>GUI 本地坐标</b> {@code {x, y, w, h}}。
     *
     * <p>
     * ⚠️ <b>想清楚再用。</b>NEI 对这个返回值的处理不是「点击穿透」，而是把这些格子
     * <b>从面板里摘掉</b>：{@code codechicken.nei.ItemsGrid} 里拿到 true 会把该格
     * 记进 {@code invalidSlotMap}，而那张表在绘制循环开头就被 {@code continue} 掉 ——
     * 于是面板在这个位置会真的<b>空掉/看不见</b>。
     *
     * <p>
     * 共享终端踩过这个坑：面板挂在右边时，侧栏那条遮罩把面板上对应的格子整片摘掉，
     * 表现就是「右侧不显示 NEI 物品栏了」。而按界面的实际排布，遮罩压住的多半正是
     * 面板本身（终端侧栏和装备/合成格都是要点的槽位，没法只遮一半），
     * 所以共享终端<b>最终没有用这个机制</b> —— 面板能看见比「格子点不点到」重要得多。
     * {@code GuiButton} 不用登记：NEI 会自动按 buttonList 遮。
     *
     * <p>
     * 返回 null/空 = 没有要遮的区域。
     */
    default List<int[]> neiMaskedAreas() {
        return null;
    }
}
