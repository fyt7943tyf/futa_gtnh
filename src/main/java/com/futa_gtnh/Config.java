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

    /**
     * 传送找不到现成落脚点时，是否允许<b>就地开一小块地方</b>（清掉玩家身上那两格）。
     *
     * <p>
     * 为什么需要它：目标矿脉十有八九整个埋在实心石头里，周围几十格都没有一处天然
     * 能站人的地方（这正是「挖矿」的常态）。不允许开洞的话，传送在矿洞里基本用不了。
     *
     * <p>
     * 开洞是<b>有节制的</b>：只在目标附近 ±4 格内找，只清玩家身体那两格，
     * 而且脚下必须本来就是实心的（不会凭空放方块）、不碰<b>目标方块本身</b>
     * （免得把你来找的那块矿挖掉）、不碰挖不动的方块、不碰带方块实体的方块
     * （免得把箱子/机器删成一个幽灵方块）、四周有液体就不开（免得灌进来）。
     *
     * <p>
     * 关掉它就退回旧行为：找不到现成位置就只提示一声、不传送。
     */
    public static boolean locatorTeleportCarve = true;

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
    // 俯瞰建筑（RTS 模式）
    // ------------------------------------------------------------------

    /**
     * 是否启用俯瞰建筑模式（G 键开关的 RTS 视角 + 远程搭建/互动）。
     *
     * <p>
     * 这是<b>服务端权威</b>的开关：客户端的 G 键发来的开启请求在这里被拒绝时，
     * 会收到一个拒绝回包并自动退出俯瞰界面。
     */
    public static boolean rtsEnable = true;

    /**
     * 俯瞰模式的操作半径（方块，以玩家为中心的正方形半边长）。
     *
     * <p>
     * 双重含义：客户端把<b>相机</b>钳在这个范围内（表现层），服务端把所有
     * 远程动作的<b>目标</b>校验在这个范围内（安全层）。两端各读各自的配置，
     * 不一致时以服务端的拒绝为准 —— 客户端最多是「多显示了一点、点了没反应」。
     *
     * <p>
     * 默认 128 = 8 个区块。注意客户端的视距（渲染距离）要大于半径 ÷ 16，
     * 否则相机移到边界附近时远处的方块还没被渲染出来。
     */
    public static int rtsMaxActionRadius = 128;

    /** 相机允许低到玩家脚下多少格（防穿到基岩层以下看着难受）。 */
    public static int rtsHeightMinOffset = -35;

    /** 相机允许高到玩家头顶多少格。 */
    public static int rtsHeightMaxOffset = 110;

    /** 键盘平移速度（格/tick）。WASD 用，60 格/秒的默认值比跑得快、比飞得慢。 */
    public static float rtsCameraPanSpeed = 1.5F;

    /**
     * 光标拾取距离（格）。点不到比这更远的东西。
     *
     * <p>
     * 这个值是纯客户端的（服务端校验的是玩家半径），拾取射线每帧做一次
     * 128 格的方块 raytrace，和原版准星的开销同级。
     */
    public static int rtsPickRange = 128;

    /**
     * 远程互动的虚拟眼距（格）。服务端把玩家临时放到「命中点往回退这么多」
     * 的位置上再跑原版交互 —— GT 机器等的距离校验普遍认 4~8 格。
     */
    public static double rtsInteractReach = 4.0D;

    /**
     * 每 tick 每玩家最多几次单块操作（点击互动/放置/破坏共享这个额度）。
     *
     * <p>
     * 防改客户端灌包用。批量操作不占这个额度，由批量引擎自己的每 tick 预算
     * （{@code rtsBuildBatchBlocksPerTick}）限速。
     */
    public static int rtsOpsPerTickPerPlayer = 8;

    /**
     * 生存模式下远程破坏是否要求工具挖掘等级够。
     *
     * <p>
     * 原版行为是「挖掉了但没有掉落物」—— 俯瞰模式一次拖动扫过一整面墙，
     * 用这个行为等于毁墙不掉东西，太伤。默认改成拒绝并提示。
     */
    public static boolean rtsRequireCorrectToolForBreak = true;

    /**
     * 批量形状每条边的最大跨度（格）。上限 32 时包围盒最多 32×32×32，
     * 与 {@code rtsMaxSelectionVolume} 双重限制。
     */
    public static int rtsMaxShapeDimension = 32;

    /** 批量形状生成后的最大格数（实心立方体 32³ 正好 32768）。 */
    public static int rtsMaxSelectionVolume = 32768;

    /**
     * 批量引擎每 tick 每玩家最多执行多少格。这是「任务多快跑完」和
     * 「服务器卡不卡」之间唯一的旋钮。
     */
    public static int rtsBuildBatchBlocksPerTick = 64;

    /**
     * 批量建造的材料是否允许从共享背包补（先玩家背包、不足再共享背包）。
     * 关掉就只用背包，缺料任务会中止。
     */
    public static boolean rtsUseSharedStorage = true;

    /** 撤销栈每栈最多几条记录（一次批量任务 = 一条）。 */
    public static int rtsHistoryMaxEntries = 3;

    /** 撤销记录的保留时长（秒），过期在入栈/出栈时惰性清理。 */
    public static int rtsHistoryRetentionSeconds = 600;

    /** 键盘升降速度（格/tick）。空格 / 左 Shift 用。 */
    public static float rtsCameraVerticalSpeed = 1.2F;

    /** Q/E 旋转速度（度/tick）。 */
    public static float rtsCameraRotateSpeed = 4.0F;

    /** 滚轮推拉速度（格/每格滚轮）。 */
    public static float rtsCameraZoomSpeed = 3.0F;

    /** 鼠标拖拽旋转灵敏度（度/像素）。中键拖拽用。 */
    public static float rtsMouseRotateSensitivity = 0.35F;

    /**
     * 鼠标拖拽平移灵敏度（格/像素，按相机高度再缩放）。右键拖拽用。
     *
     * <p>
     * 拉得越高一格像素代表的世界距离越大（缩放系数 0.5 ~ 6.0，见
     * {@code RtsCameraController}），这是地图软件的通用手感。
     */
    public static float rtsMousePanSensitivity = 0.05F;

    /**
     * 共享终端界面的排序方式（0=名称 1=数量 2=模组），<b>默认按数量</b>。
     *
     * <p>
     * 这是<b>客户端偏好</b>：点界面上的排序按钮时会写回配置文件（见
     * {@link #saveClientGuiSort}），下次打开界面/重启游戏都记住。
     * 存的是整数而不是引用客户端的枚举，保证服务端加载这个类时安全。
     */
    public static int guiSortMode = 1;

    // ------------------------------------------------------------------
    // 合成
    // ------------------------------------------------------------------

    /** 是否注册共享终端的合成配方。 */
    public static boolean enableRecipe = true;

    /** 配置文件位置，在 {@link #synchronizeConfiguration} 里记下，供运行时回写用。 */
    private static File configFileRef;

    public static void synchronizeConfiguration(File configFile) {
        configFileRef = configFile;
        Configuration configuration = new Configuration(configFile);

        enableDebugLogging = configuration
            .getBoolean("enableDebugLogging", Configuration.CATEGORY_GENERAL, enableDebugLogging, "打开后会输出更多调试日志");

        guiSortMode = configuration.getInt(
            "guiSortMode",
            Configuration.CATEGORY_GENERAL,
            guiSortMode,
            0,
            2,
            "共享终端界面的排序方式（0=名称 1=数量 2=模组）。客户端偏好：界面里点排序按钮会自动改写这一项。");

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

        locatorTeleportCarve = configuration.getBoolean(
            "locatorTeleportCarve",
            Configuration.CATEGORY_GENERAL,
            locatorTeleportCarve,
            "传送找不到现成落脚点时，是否允许就地清掉玩家身体那两格。" + "只清「没用的方块」（石头/泥土/沙子这类），并且永不碰矿石和木头/玻璃/金属/机器；"
                + "不碰目标方块本身，也不掉落物品。"
                + "矿脉大多整个埋在石头里，关掉它传送在矿洞里基本用不了。");

        rtsEnable = configuration.getBoolean(
            "rtsEnable",
            Configuration.CATEGORY_GENERAL,
            rtsEnable,
            "是否启用俯瞰建筑模式（G 键开关的 RTS 视角 + 远程搭建/互动）。服务端权威：关掉后客户端的开启请求会被拒绝并自动退出。");

        rtsMaxActionRadius = configuration.getInt(
            "rtsMaxActionRadius",
            Configuration.CATEGORY_GENERAL,
            rtsMaxActionRadius,
            16,
            512,
            "俯瞰模式的操作半径（方块，玩家周围正方形的半边长）。客户端用它钳制相机，服务端用它校验所有远程动作的目标。默认 128 = 8 区块，客户端视距需大于半径÷16。");

        rtsHeightMinOffset = configuration.getInt(
            "rtsHeightMinOffset",
            Configuration.CATEGORY_GENERAL,
            rtsHeightMinOffset,
            -256,
            0,
            "相机允许低到玩家脚下多少格。");

        rtsHeightMaxOffset = configuration
            .getInt("rtsHeightMaxOffset", Configuration.CATEGORY_GENERAL, rtsHeightMaxOffset, 1, 256, "相机允许高到玩家头顶多少格。");

        rtsCameraPanSpeed = configuration.getFloat(
            "rtsCameraPanSpeed",
            Configuration.CATEGORY_GENERAL,
            rtsCameraPanSpeed,
            0.1F,
            10.0F,
            "俯瞰相机键盘平移速度（格/tick），WASD 用。");

        rtsPickRange = configuration.getInt(
            "rtsPickRange",
            Configuration.CATEGORY_GENERAL,
            rtsPickRange,
            16,
            512,
            "俯瞰光标的拾取距离（格）。纯客户端表现，服务端校验的是操作半径。");

        rtsInteractReach = configuration.getFloat(
            "rtsInteractReach",
            Configuration.CATEGORY_GENERAL,
            (float) rtsInteractReach,
            2.0F,
            8.0F,
            "远程互动的虚拟眼距（格）：服务端把玩家临时放到命中点往回退这个距离的位置再跑原版交互。");

        rtsOpsPerTickPerPlayer = configuration.getInt(
            "rtsOpsPerTickPerPlayer",
            Configuration.CATEGORY_GENERAL,
            rtsOpsPerTickPerPlayer,
            1,
            64,
            "俯瞰模式下每 tick 每玩家最多几次单块操作（防改客户端灌包；批量操作另有限速）。");

        rtsRequireCorrectToolForBreak = configuration.getBoolean(
            "rtsRequireCorrectToolForBreak",
            Configuration.CATEGORY_GENERAL,
            rtsRequireCorrectToolForBreak,
            "生存模式下远程破坏是否要求工具挖掘等级够。原版行为是挖掉了但没有掉落物；开着的话会直接拒绝并提示。");

        rtsMaxShapeDimension = configuration.getInt(
            "rtsMaxShapeDimension",
            Configuration.CATEGORY_GENERAL,
            rtsMaxShapeDimension,
            1,
            64,
            "批量形状每条边的最大跨度（格）。");

        rtsMaxSelectionVolume = configuration.getInt(
            "rtsMaxSelectionVolume",
            Configuration.CATEGORY_GENERAL,
            rtsMaxSelectionVolume,
            1,
            1000000,
            "批量形状生成后的最大格数。");

        rtsBuildBatchBlocksPerTick = configuration.getInt(
            "rtsBuildBatchBlocksPerTick",
            Configuration.CATEGORY_GENERAL,
            rtsBuildBatchBlocksPerTick,
            1,
            1024,
            "批量引擎每 tick 每玩家最多执行多少格（任务速度 vs 服务器负载的唯一旋钮）。");

        rtsUseSharedStorage = configuration.getBoolean(
            "rtsUseSharedStorage",
            Configuration.CATEGORY_GENERAL,
            rtsUseSharedStorage,
            "批量建造的材料是否允许从共享背包补（先玩家背包、不足再共享背包）。关掉就只用背包，缺料任务会中止。");

        rtsHistoryMaxEntries = configuration.getInt(
            "rtsHistoryMaxEntries",
            Configuration.CATEGORY_GENERAL,
            rtsHistoryMaxEntries,
            1,
            20,
            "撤销栈每栈最多几条记录（一次批量任务 = 一条）。");

        rtsHistoryRetentionSeconds = configuration.getInt(
            "rtsHistoryRetentionSeconds",
            Configuration.CATEGORY_GENERAL,
            rtsHistoryRetentionSeconds,
            60,
            86400,
            "撤销记录的保留时长（秒）。");

        rtsCameraVerticalSpeed = configuration.getFloat(
            "rtsCameraVerticalSpeed",
            Configuration.CATEGORY_GENERAL,
            rtsCameraVerticalSpeed,
            0.1F,
            10.0F,
            "俯瞰相机升降速度（格/tick），空格/左Shift 用。");

        rtsCameraRotateSpeed = configuration.getFloat(
            "rtsCameraRotateSpeed",
            Configuration.CATEGORY_GENERAL,
            rtsCameraRotateSpeed,
            0.5F,
            45.0F,
            "俯瞰相机 Q/E 旋转速度（度/tick）。");

        rtsCameraZoomSpeed = configuration.getFloat(
            "rtsCameraZoomSpeed",
            Configuration.CATEGORY_GENERAL,
            rtsCameraZoomSpeed,
            0.5F,
            20.0F,
            "俯瞰相机滚轮推拉速度（格/每格滚轮）。");

        rtsMouseRotateSensitivity = configuration.getFloat(
            "rtsMouseRotateSensitivity",
            Configuration.CATEGORY_GENERAL,
            rtsMouseRotateSensitivity,
            0.05F,
            3.0F,
            "俯瞰相机中键拖拽旋转灵敏度（度/像素）。");

        rtsMousePanSensitivity = configuration.getFloat(
            "rtsMousePanSensitivity",
            Configuration.CATEGORY_GENERAL,
            rtsMousePanSensitivity,
            0.005F,
            0.5F,
            "俯瞰相机右键拖拽平移灵敏度（格/像素，按相机高度自动缩放）。");

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

    /**
     * 运行时回写共享终端的排序偏好（点界面排序按钮时调用）。
     *
     * <p>
     * 这是本工程唯一一处「cfg 在 preInit 之外被写」的地方：重新读一遍配置文件、
     * 只改 guiSortMode 一项再存盘，其余配置项保持原样。写盘在主线程、只点按钮
     * 才发生，一次几毫秒，无感。
     */
    public static void saveClientGuiSort(int mode) {
        guiSortMode = mode;
        if (configFileRef == null) return;
        try {
            Configuration configuration = new Configuration(configFileRef);
            configuration.get(Configuration.CATEGORY_GENERAL, "guiSortMode", guiSortMode)
                .set(Integer.toString(mode));
            configuration.save();
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("保存共享终端排序偏好失败（不影响本次使用）", t);
        }
    }
}
