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
                    && probeClass("tconstruct.tools.logic.PartBuilderLogic")
                    // 合成站自动补料靠 SlotCraftingStation 认「玩家拿走了产物」这个动作
                    // （见 mixins/MixinSlotCraftingStation），它必须真的存在
                    && probeClass("tconstruct.tools.inventory.SlotCraftingStation");
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
     * 启动时（postInit）调一次：探测匠魂并把「已启用」那行日志写掉。
     *
     * <p>
     * 为什么不等到第一个 tick：探测会把匠魂那几个类<b>真正加载进来</b>
     * （混入是在类加载那一刻生效的），早一点做，日志里就能立刻看到
     * 「匠魂工作站自动补料已启用」和各个 mixin 的应用结果 ——
     * 排查「功能好像没生效」时，这两条是最省事的线索。
     */
    public static void prewarm() {
        if (!Config.tinkersAutoFill) return;
        if (!isAvailable()) return;
        announceOnce();
    }

    private static void announceOnce() {
        if (announced) return;
        // 只报一次：这条日志是「功能到底有没有起来」的唯一线索，
        // 免得玩家看到界面上没反应却不知道是探测失败还是规则没命中
        announced = true;
        com.futa_gtnh.FutaGtnhMod.LOG.info("匠魂工作站自动补料已启用（开着工作站界面时才会补料）");
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
        announceOnce();
        try {
            WorkstationAutoFill.tick();
        } catch (Throwable t) {
            // 匠魂版本对不上之类的意外：整个功能静默失效，绝不影响主流程
            WorkstationAutoFill.reportFailure(t);
        }
    }

    /**
     * 玩家点了容器里的一格（由 {@code mixins/MixinContainer} 在
     * {@code Container.slotClick} 入口转发过来）。
     *
     * <p>
     * 自动补料只从「这一格少了几个」判断不出是<b>合成吃掉的</b>还是<b>玩家拿走的</b>，
     * 于是会把玩家刚拿回来的材料又补回去（九宫格里的东西「拿不出来」）。
     * 这里把「谁动的」这一唯一可靠的信号传给它。
     *
     * <p>
     * 这是每次点击都会走到的路径，所以先做两个 boolean 判断（没开自动补料 / 没装匠魂 /
     * 没有工作站界面开着时立刻返回），不产生任何分配。
     */
    public static void notePlayerClick(net.minecraft.inventory.Container container, int slotId, int mode) {
        if (!Config.tinkersAutoFill || !isAvailable()) return;
        try {
            KeptLayoutFill.notePlayerClick(container, slotId, mode);
        } catch (Throwable t) {
            // 同理：这只是个提示信号，出错也不能影响玩家正常的点击
            WorkstationAutoFill.reportFailure(t);
        }
    }

    /** 玩家一次性清空了整份库存（「倒空合成栏」按钮）：整份都算玩家动过。 */
    public static void notePlayerTouchedAll(net.minecraft.inventory.Container container) {
        if (!Config.tinkersAutoFill || !isAvailable()) return;
        try {
            KeptLayoutFill.noteTouchedAll(container);
        } catch (Throwable t) {
            WorkstationAutoFill.reportFailure(t);
        }
    }

    /**
     * 玩家拿走了合成产物（由 {@code mixins/MixinSlotCraftingStation} 转发）。
     *
     * <p>
     * 这是合成站「回填」唯一认可的前提：只有真拿走过产物，才说明合成栏是被合成消耗掉的。
     */
    public static void noteCraftResultTaken(net.minecraft.inventory.Container container) {
        if (!Config.tinkersAutoFill || !isAvailable()) return;
        try {
            KeptLayoutFill.noteCraft(container);
        } catch (Throwable t) {
            WorkstationAutoFill.reportFailure(t);
        }
    }
}
