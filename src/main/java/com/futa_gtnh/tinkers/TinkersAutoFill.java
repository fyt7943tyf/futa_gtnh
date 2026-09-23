package com.futa_gtnh.tinkers;

import com.futa_gtnh.Config;

import cpw.mods.fml.common.Loader;

/**
 * 匠魂（Tinkers' Construct）工作站「自动用共享存储的材料」的入口。
 *
 * <p>
 * <b>这个类里不许出现任何 tconstruct.* 的类型</b>：没装匠魂时加载那些类会
 * {@code NoClassDefFoundError}。所有真正碰匠魂的代码都在
 * {@link WorkstationAutoFill} 里，只有本类在确认匠魂在场之后才会去引用它
 * （JVM 惰性解析符号引用，所以缺席时那个类根本不会被加载）。
 *
 * <p>
 * 玩法（细节见 {@link WorkstationAutoFill} 与各 filler 的注释）：
 * <ul>
 * <li><b>工匠工作站 / 锻造台</b>：槽里已经有部件、能唯一确定你要造什么工具时，
 * 缺的部件自动从共享存储补进来 —— 而匠魂自己在 {@code setInventorySlotContents}
 * 里就会尝试 {@code buildTool}，所以补进去的下一刻工具就造好了；</li>
 * <li><b>部件加工台</b>：放上图纸后，材料自动补（同样由匠魂自己触发合成）；</li>
 * <li><b>合成站 / 冶炼炉</b>：记住你摆好的样子，被消耗掉的部分自动补回原数量
 * （不会凭空往里塞新材料）。</li>
 * </ul>
 *
 * <p>
 * 只在你<b>开着那个工作站的界面</b>时才会补，关掉就停 —— 免得变成一个无人看管的
 * 自动吞料机。
 */
public final class TinkersAutoFill {

    private static boolean probed;
    private static boolean available;
    private static boolean announced;

    private TinkersAutoFill() {}

    /** 匠魂是否在场且可用。第一次调用时探测一次，之后缓存。 */
    public static boolean isAvailable() {
        if (!probed) {
            probed = true;
            try {
                available = Loader.isModLoaded("TConstruct") && probeClass("tconstruct.tools.logic.ToolStationLogic")
                    && probeClass("tconstruct.tools.logic.PartBuilderLogic");
            } catch (Throwable t) {
                available = false;
            }
        }
        return available;
    }

    private static boolean probeClass(String name) {
        try {
            Class.forName(name, false, TinkersAutoFill.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 服务端每 tick 调一次（由 {@code ModEventHandler} 转发）。
     *
     * <p>
     * {@link WorkstationAutoFill} 里全是匠魂的类型，所以必须等探测通过再引用它。
     */
    public static void onServerTick() {
        if (!Config.tinkersAutoFill) return;
        if (!isAvailable()) return;
        if (!announced) {
            // 只报一次：这条日志是「功能到底有没有起来」的唯一线索，
            // 免得玩家看到界面上没反应却不知道是探测失败还是规则没命中
            announced = true;
            com.futa_gtnh.FutaGtnhMod.LOG.info("匠魂工作站自动补料已启用（开着工作站界面时才会补料）");
        }
        try {
            WorkstationAutoFill.tick();
        } catch (Throwable t) {
            // 匠魂版本对不上之类的意外：整个功能静默失效，绝不影响主流程
            WorkstationAutoFill.reportFailure(t);
        }
    }
}
