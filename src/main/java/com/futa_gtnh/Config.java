package com.futa_gtnh;

import java.io.File;

import net.minecraftforge.common.config.Configuration;

/**
 * 模组配置文件（{@code config/futa_gtnh.cfg}）。
 *
 * <p>
 * 所有配置都是<b>服务端权威</b>的：服务端读这里的值，客户端不会拿它做任何决定
 * （客户端那侧只用于界面表现）。所以改完配置重启服务端即可，玩家不用动。
 */
public class Config {

    public static boolean enableDebugLogging = false;

    // ------------------------------------------------------------------
    // 访问控制
    // ------------------------------------------------------------------

    /**
     * 是否允许用按键（默认 B）随时随地打开共享背包。
     *
     * <p>
     * 关掉之后就只能靠放置「共享终端」方块来访问。想给服务器增加一点
     * 「要跑一趟仓库」的仪式感，或者不想让共享存储变成随身空间时关掉它。
     */
    public static boolean allowRemoteAccess = true;

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    /** 自动保存间隔（秒）。只在内容有改动时才会真的写盘。 */
    public static int autosaveIntervalSeconds = 300;

    // ------------------------------------------------------------------
    // 交互手感
    // ------------------------------------------------------------------

    /** Shift + 左键点击物品条目时取多少个。 */
    public static int shiftClickWithdrawAmount = 64;

    /** 点击流体条目时默认操作多少毫巴（1 L = 1 mB）。 */
    public static int fluidClickAmount = 1000;

    /**
     * 把 GT 的「流体显示物品」存进共享存储时，是否自动转成流体。
     *
     * <p>
     * 打开时，「取出流体 → 显示物品 → 再存回去」可以闭环；
     * 关掉时显示物品就只是个普通物品，会被当成物品存起来。
     */
    public static boolean displayItemBecomesFluid = true;

    // ------------------------------------------------------------------
    // 迅步（Baubles 饰品）
    // ------------------------------------------------------------------

    /** 是否注册迅步。需要装了 Baubles，没装的话这项无效。 */
    public static boolean enableSwiftStep = true;

    /**
     * 迅步能把飞行速度 / 移动速度提到原版的多少倍。
     *
     * <p>
     * 上限<b>由服务端说了算</b>：客户端界面按自己配置里的值画按钮，
     * 但发上来的倍率会在服务端再夹一次。所以把这个值调小之后，
     * 就算客户端还显示着 20x，实际写进物品的也会被压到上限。
     *
     * <p>
     * <b>专用服务器的硬上限是 18.0，超过就是一个加速都拿不到</b> —— 不是「快一点但
     * 有点风险」，而是每 tick 都被服务端判定 {@code moved too quickly} 并拉回原地。
     * 推导（全部来自原版源码）：
     *
     * <pre>
     *   飞行每 tick 水平加速 = flySpeed = 倍率 × 0.05
     *   飞行水平阻力         = 0.91        （EntityLivingBase.moveEntityWithHeading）
     *   终端速度             = flySpeed / (1 - 0.91) = 倍率 × 0.5556 格/tick
     *   服务端判定           = 位移平方和 &gt; 100，即 位移 &gt; 10 格/tick
     *                          （NetHandlerPlayServer.processPlayer）
     *   ⇒ 倍率 ≤ 10 / 0.5556 = 18.0
     * </pre>
     *
     * 校验：代倍率 1 进去得 0.556 格/tick = 11.1 格/秒，正好是创造模式飞行的体感。
     *
     * <p>
     * <b>单人存档不受这条限制</b>：那个判定带一个
     * {@code !isSinglePlayer() || !getServerOwner().equals(playerName)} 的豁免条件，
     * 自己开档时整条检查会被跳过（所以「单人里好好的、一连服务器就失效」）。
     * 想在单人里开更快就把这个值调大。
     *
     * <p>
     * 默认 16 是 18 再留一点余量（斜着飞加上垂直分量时位移的平方和会更大）。
     * 就算这里调得比 18 高，客户端在多人服务器上也会自动压到 18 并在界面上说明，
     * 不会让玩家对着一个「按了没反应」的速度发呆。
     */
    public static double swiftStepMaxMultiplier = 16.0D;

    // ------------------------------------------------------------------
    // 寻物魔杖
    // ------------------------------------------------------------------

    /** 是否注册寻物魔杖。 */
    public static boolean enableLocatorWand = true;

    /**
     * 寻物魔杖一次搜索的半径（方块，以玩家为中心的正方体半边长）。
     *
     * <p>
     * 扫描量按半径的<b>三次方</b>增长：半径 64 是约 170 万个方块位置，
     * 128 就是 1350 万个。默认 128 配合下面的每 tick 预算大约是几百毫秒到一两秒，
     * 玩家能接受；再往上加会让服务器在一次搜索里卡好几秒。
     *
     * <p>
     * 这个值<b>只在服务端生效</b>，客户端配置里的数字不影响判定。
     */
    public static int locatorSearchRadius = 128;

    /**
     * 寻物扫描每 tick 最多检查多少个方块位置。
     *
     * <p>
     * 这是「搜索要多久」和「服务器卡不卡」之间唯一的旋钮：
     * 调大 = 结果出得快但每 tick 占用更多；调小 = 平滑但可能要等好几秒。
     * 建议值 20000 ~ 200000。上限 2000000，再高单 tick 就会明显掉刻。
     */
    public static int locatorBlocksPerTick = 60000;

    /**
     * 一次搜索最多允许跑多少 tick，超时就放弃。
     *
     * <p>
     * 兜底用：万一有人把半径调到很大、或者世界加载卡住，没有这个上限的话
     * 一个搜索任务会一直挂在服务端 tick 里。默认 600 tick（30 秒）。
     */
    public static int locatorScanTimeoutTicks = 600;

    // ------------------------------------------------------------------
    // 界面（客户端行为）
    // ------------------------------------------------------------------

    /**
     * 打开共享终端界面时是否暂时收起 NEI 的物品面板。
     *
     * <p>
     * 终端界面比原版容器宽（232px），NEI 的物品面板会和右侧的合成栏叠在一起。
     * 这是<b>客户端表现</b>：服务器改这一项不影响玩家自己客户端的表现，
     * 各端读自己配置文件里的值。
     */
    public static boolean hideNeiPanelInTerminalGui = true;

    // ------------------------------------------------------------------
    // 匠魂工作站自动补料（服务端行为）
    // ------------------------------------------------------------------

    /**
     * 开着匠魂工作站界面时，缺的部件 / 材料自动从共享存储补（见 {@code com.futa_gtnh.tinkers} 包）。
     *
     * <p>
     * 判定和取料都在服务端，所以各端读的是<b>服务端</b>那份配置。只在玩家开着那个工作站的
     * 界面时才会补，关掉就停 —— 免得变成一个无人看管的自动吞料机。
     */
    public static boolean tinkersAutoFill = true;

    // ------------------------------------------------------------------
    // 合成
    // ------------------------------------------------------------------

    /** 是否注册共享终端的合成配方。 */
    public static boolean enableRecipe = true;

    public static void synchronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);

        enableDebugLogging = configuration
            .getBoolean("enableDebugLogging", Configuration.CATEGORY_GENERAL, enableDebugLogging, "打开后会输出更多调试日志");

        allowRemoteAccess = configuration.getBoolean(
            "allowRemoteAccess",
            Configuration.CATEGORY_GENERAL,
            allowRemoteAccess,
            "是否允许用按键随时随地打开共享背包。关掉后只能用「共享终端」方块访问。");

        autosaveIntervalSeconds = configuration.getInt(
            "autosaveIntervalSeconds",
            Configuration.CATEGORY_GENERAL,
            autosaveIntervalSeconds,
            10,
            86400,
            "自动保存间隔（秒）。内容没有改动时不会写盘。");

        shiftClickWithdrawAmount = configuration.getInt(
            "shiftClickWithdrawAmount",
            Configuration.CATEGORY_GENERAL,
            shiftClickWithdrawAmount,
            1,
            1000000000,
            "在界面里 Shift + 左键点击一个物品条目时取出多少个。");

        fluidClickAmount = configuration.getInt(
            "fluidClickAmount",
            Configuration.CATEGORY_GENERAL,
            fluidClickAmount,
            1,
            Integer.MAX_VALUE,
            "在界面里点击一个流体条目时默认操作多少毫巴（1 L = 1 mB）。");

        displayItemBecomesFluid = configuration.getBoolean(
            "displayItemBecomesFluid",
            Configuration.CATEGORY_GENERAL,
            displayItemBecomesFluid,
            "把 GT 的流体显示物品存进共享存储时，自动把它转成流体。");

        hideNeiPanelInTerminalGui = configuration.getBoolean(
            "hideNeiPanelInTerminalGui",
            Configuration.CATEGORY_GENERAL,
            hideNeiPanelInTerminalGui,
            "打开共享终端界面时暂时收起 NEI 的物品面板（终端界面较宽，面板会叠在合成栏上）。客户端行为，各端读各自的配置。");

        enableRecipe = configuration
            .getBoolean("enableRecipe", Configuration.CATEGORY_GENERAL, enableRecipe, "是否注册共享终端的合成配方。");

        tinkersAutoFill = configuration.getBoolean(
            "tinkersAutoFill",
            Configuration.CATEGORY_GENERAL,
            tinkersAutoFill,
            "开着匠魂工作站界面时，自动从共享存储补上缺的部件/材料（工匠工作站、锻造台、部件加工台、合成站、冶炼炉）。" + "只在界面开着时生效；工作站里什么都没放时不猜、不补。服务端行为。");

        enableSwiftStep = configuration.getBoolean(
            "enableSwiftStep",
            Configuration.CATEGORY_GENERAL,
            enableSwiftStep,
            "是否注册迅步饰品（需要装了 Baubles；没装的话这一项无效）。");

        swiftStepMaxMultiplier = configuration.getFloat(
            "swiftStepMaxMultiplier",
            Configuration.CATEGORY_GENERAL,
            (float) swiftStepMaxMultiplier,
            1.0F,
            100.0F,
            "迅步的速度倍率上限（原版速度的倍数）。专用服务器的飞行硬上限是 18.0：超过之后每 tick 都会被服务端判定 moved too quickly 并拉回原地，等于完全没加速。单人存档不受此限制。");

        enableLocatorWand = configuration.getBoolean(
            "enableLocatorWand",
            Configuration.CATEGORY_GENERAL,
            enableLocatorWand,
            "是否注册寻物魔杖（右键选方块找最近的一个，可传送过去）。");

        locatorSearchRadius = configuration.getInt(
            "locatorSearchRadius",
            Configuration.CATEGORY_GENERAL,
            locatorSearchRadius,
            16,
            512,
            "寻物魔杖的搜索半径（方块）。扫描量按半径三次方增长，256 以上会明显变慢。");

        locatorBlocksPerTick = configuration.getInt(
            "locatorBlocksPerTick",
            Configuration.CATEGORY_GENERAL,
            locatorBlocksPerTick,
            1000,
            2000000,
            "寻物扫描每 tick 检查多少个方块位置。调大出结果快、调小更平滑。");

        locatorScanTimeoutTicks = configuration.getInt(
            "locatorScanTimeoutTicks",
            Configuration.CATEGORY_GENERAL,
            locatorScanTimeoutTicks,
            20,
            72000,
            "一次寻物扫描最多跑多少 tick，超时放弃（兜底，防止任务一直挂在服务端 tick 里）。");

        // 上限调到超过服务器安全值时提醒一句。
        //
        // 这个值不是"偏好"，是物理约束：超过去之后飞行速度不是"快一点但有点风险"，
        // 而是每 tick 都被服务端拉回原地，玩家会觉得"这东西坏了"却查不出原因。
        float safeFly = com.futa_gtnh.item.ItemSwiftStep.serverSafeFlyMultiplier();
        if (swiftStepMaxMultiplier > safeFly) {
            FutaGtnhMod.LOG.warn(
                "迅步：swiftStepMaxMultiplier={} 超过了专用服务器的飞行安全上限 {}。"
                    + "超过之后服务端会每 tick 判定 moved too quickly 并把玩家拉回原地，等于完全没加速"
                    + "（单人存档不受此限制）。客户端在多人服务器上会自动压到安全值。",
                swiftStepMaxMultiplier,
                safeFly);
        }

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
