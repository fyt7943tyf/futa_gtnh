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
     * <b>专用服务器上限按水平与竖直合成后的位移计算</b>。竖直速度最高为 10 倍，
     * 再用服务端每 tick 的 10 格位移阈值计算水平速度余量。超过安全倍率后，玩家会被
     * 判定为 {@code moved too quickly} 并拉回原地。推导（全部来自原版源码）：
     *
     * <pre>
     *   飞行每 tick 水平加速 = flySpeed = 倍率 × 0.05
     *   飞行水平阻力         = 0.91        （EntityLivingBase.moveEntityWithHeading）
     *   终端速度             = flySpeed / (1 - 0.91) = 倍率 × 0.5556 格/tick
     *   竖直终端位移         = min(倍率, 10) × 0.15 / (1 - 0.6) 格/tick
     *   服务端判定           = 三轴位移平方和 &gt; 100，即 合位移 &gt; 10 格/tick
     *                          （NetHandlerPlayServer.processPlayer）
     *   ⇒ 竖直倍率封顶后，再由剩余位移预算算水平安全倍率
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
     * 默认 16 低于竖直速度封顶后计算出的多人安全倍率，并留有余量。
     * 就算这里设得更高，客户端在多人服务器上也会自动压到安全值并在界面上说明，
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

    /** 生物群系搜索半径（方块）；使用专用的二维扫描，不受普通寻物半径影响。 */
    public static int locatorBiomeSearchRadius = 8192;

    /** 生物群系搜索每 tick 扫描的方块位置预算。 */
    public static int locatorBiomeSamplesPerTick = 250000;

    /** 生物群系搜索的最长运行时间；范围更大时可提高预算或延长超时。 */
    public static int locatorBiomeScanTimeoutTicks = 1800;

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

    /**
     * 追踪的方块被<b>自己挖掉</b>之后，戴着魔杖时是否自动传送到下一处。
     *
     * <p>
     * 触发条件刻意收得很紧，四条同时成立才算数：
     * <ol>
     * <li>魔杖<b>戴在饰品栏里</b>（拿在手上挖矿是常态，戴着才是「我正在用它扫矿」）；</li>
     * <li>挖掉的正好是<b>当前追踪的那一格</b>（挖别的方块不算）；</li>
     * <li>是<b>玩家自己</b>挖的 —— 别人挖了、爆炸炸了只会让光束换个目标，不会把人挪走；</li>
     * <li>这个开关开着。</li>
     * </ol>
     *
     * <p>
     * 传送本身和手动点「传送」走同一段代码：一样的找安全落点、一样可以就地开两格
     * （见 {@link #locatorTeleportCarve}），一样<b>绝不碰矿石</b>。
     * 失败时不会消耗「这次结果只能用一次传送」的额度，结果和坐标都留着，
     * 玩家可以自己再点一次。
     */
    public static boolean locatorAutoAdvance = true;

    /**
     * 自动追下一处的最小距离（格）。新目标比这更近就只把追踪切过去、不传送。
     *
     * <p>
     * 挖矿时「下一个」常常就在隔壁一两格 —— 为这个把人挪一下既没意义又晃眼
     * （而且可能顺手在原地开洞）。0 表示不设阈值，每次都传。
     */
    public static int locatorAutoAdvanceMinDistance = 4;

    // ------------------------------------------------------------------
    // 小游戏助手（lootgames 联动）
    // ------------------------------------------------------------------

    /** 是否注册小游戏助手。没装 LootGames 时这项无效（注册了也搜不到任何地牢）。 */
    public static boolean enableMinigameHelper = true;

    /**
     * lootgames 小游戏「无限重试」后，第几次失败自动按满奖励结算（4 个战利品箱）。
     *
     * <p>
     * 这个值<b>会直接覆写 LootGames 自己的 attempt_count 配置</b>（扫雷/光之游戏
     * 在 preInit 阶段写它的公共字段，数独在运行时用 mixin 拦截），所以
     * {@code config/lootgames/*.cfg} 里的 attempt_count 在装了本模组后不再生效。
     * 失败惩罚（爆炸/刷怪/岩浆）会被 mixin 全部取消 —— 失败只是重开当前关卡。
     */
    public static int lootgamesFullRewardRetries = 10;

    /**
     * 小游戏助手「搜索附近」的半径（方块，以发起搜索的玩家为圆心）。
     *
     * <p>
     * 候选点是按种子推算的（不加载区块、零开销），真正花时间的是逐个候选加载
     * 区块去确认地牢是否真的生成了 —— 半径越大候选越多、确认越久（按距离从近到远，
     * 期间界面有进度）。上限 3000 时一次搜索最多几百个候选，十几秒跑完。
     */
    public static int lootassistSearchRadius = 1000;

    // ------------------------------------------------------------------
    // 自选抽奖机（EnhancedLootBags + VendingMachine 联动）
    // ------------------------------------------------------------------

    /**
     * 是否注册自选抽奖机方块。它整个玩法都建立在 Enhanced LootBags 的开袋算法上，
     * 没装 ELB 时即使开着这项也不会注册（界面上那个袋子槽没有东西可放）。
     */
    public static boolean enableLootMachine = true;

    /**
     * 重置 roll 次数时扣哪个钱包里的技术员代币。
     *
     * <p>
     * {@code true} = 团队钱包（默认，和 VendingMachine 的团队模式一致，全队共享）；
     * {@code false} = 个人钱包。判定和扣款都在服务端。
     */
    public static boolean lootMachineTeamWallet = true;

    // ------------------------------------------------------------------
    // 太阳能除钙剂
    // ------------------------------------------------------------------

    /** 是否注册太阳能除钙剂（右键蒸汽太阳能锅炉重置钙化进度的永久工具）。 */
    public static boolean enableSolarDescaler = true;

    public static boolean enableDisassembler = true;
    public static int disassemblerMetaTileId = 32700;

    // ------------------------------------------------------------------
    // 界面（客户端行为）
    // ------------------------------------------------------------------

    /**
     * 共享终端界面里 NEI 物品面板的可见性。
     */
    public enum TerminalNeiPanel {
        /** 显示（默认）。与界面重叠的 NEI 格子会被遮罩，不会误触界面自己的控件。 */
        SHOW,
        /**
         * 收起。注意 NEI 在「搜索条跟随面板」布局下（{@code NEI} 选项里的
         * 搜索条位置设置）会连搜索条一起收掉 —— 这是 NEI 自己的行为。
         */
        HIDE
    }

    /**
     * 共享终端搜索框与 NEI 的联动方式（对齐 AE2 终端的搜索模式）。
     */
    public enum TerminalSearchMode {

        /** 手动：点搜索框开始输入（默认）。 */
        MANUAL,
        /** 自动聚焦：打开界面时搜索框直接进输入状态。 */
        AUTO,
        /** NEI 同步：输入实时推送到 NEI 搜索条，NEI 物品面板随之过滤。 */
        NEI_SYNC,
        /** NEI 同步 + 自动聚焦。 */
        NEI_SYNC_AUTO;

        /** 是否向 NEI 推送搜索词。 */
        public boolean neiSync() {
            return this == NEI_SYNC || this == NEI_SYNC_AUTO;
        }

        /** 打开界面时是否自动聚焦搜索框。 */
        public boolean autoFocus() {
            return this == AUTO || this == NEI_SYNC_AUTO;
        }
    }

    /** NEI 物品面板在共享终端界面里的可见性。客户端偏好。 */
    public static TerminalNeiPanel terminalNeiPanel = TerminalNeiPanel.SHOW;

    /** 搜索框与 NEI 的联动方式。客户端偏好。 */
    public static TerminalSearchMode terminalSearchMode = TerminalSearchMode.MANUAL;

    // ------------------------------------------------------------------
    // Roguelike 地牢地图（客户端）
    // ------------------------------------------------------------------

    /** 是否启用 Roguelike Dungeons 地图快捷键和悬浮小地图。 */
    public static boolean enableRoguelikeMap = true;

    /** 每次扫描当前楼层时向四周读取的方块半径。只读取已经加载的区块。 */
    public static int roguelikeMapScanRadius = 72;

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

    // ------------------------------------------------------------------
    // 网页合成向导（客户端）
    //
    // 这些全部只在客户端生效：配方数据来自 NEI，而 NEI 只存在于客户端。
    // 专用服务端读它们没有任何用处（也不会去读）。
    // ------------------------------------------------------------------

    /**
     * 是否启用手机网页版的配方查询与合成指导。
     *
     * <p>
     * 关掉之后不会开任何端口、也不会去建配方索引，按键和命令都会直接说「已关闭」。
     */
    public static boolean webRecipeEnable = true;

    /** 客户端启动后是否自动开始监听（不用每次进游戏手动开）。 */
    public static boolean webRecipeAutoStart = true;

    /**
     * 是否在启动后自动建立全量配方索引。
     *
     * <p>
     * 建一次要跑一会儿（几百个配方处理器、十几万条配方），但结果会缓存到
     * {@code config/futa_gtnh/web_recipes.dat}，下次开游戏是秒开的。
     * 关掉它就变成「手机第一次打开页面时再建」。
     */
    public static boolean webRecipePrebuild = true;

    /** 网页监听端口。 */
    public static int webRecipePort = 8765;

    /**
     * 网页绑定地址。
     *
     * <p>
     * 默认 {@code 0.0.0.0}（局域网里的手机才连得上）。改成 {@code 127.0.0.1}
     * 就只有这台电脑自己能访问 —— 不想让同网段的人扫到端口时这么设。
     */
    public static String webRecipeBindAddress = "0.0.0.0";

    /**
     * 访问口令。留空表示不校验。
     *
     * <p>
     * 设了之后必须用带 {@code ?k=口令} 的完整地址打开（按键时聊天栏里给的就是它），
     * 之后浏览器会记住这个 Cookie。
     */
    public static String webRecipeToken = "";

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

        // NEI 面板可见性：新键 terminalNeiPanel；老键 hideNeiPanelInTerminalGui
        // 只用于一次性的「升级迁移」（true → HIDE），读完就从配置文件里摘掉。
        // 只在老键存在、且玩家还没写过新键时才尊重老键 —— 否则玩家改过的新值会被覆盖。
        //
        // 合并 PR #4 时补上的第三个老键：main 这支在 1.9 前后把老键改成正向的
        // showNeiPanelInTerminalGui（false → 收起），有人配置文件里存的是它，
        // 所以一并迁移，同样读完就摘掉。
        {
            boolean legacyKeyPresent = configuration.getCategory(Configuration.CATEGORY_GENERAL)
                .containsKey("hideNeiPanelInTerminalGui");
            boolean legacyHide = legacyKeyPresent && configuration.getCategory(Configuration.CATEGORY_GENERAL)
                .get("hideNeiPanelInTerminalGui")
                .getBoolean(true);
            boolean legacyShowKeyPresent = configuration.getCategory(Configuration.CATEGORY_GENERAL)
                .containsKey("showNeiPanelInTerminalGui");
            boolean legacyShow = legacyShowKeyPresent && configuration.getCategory(Configuration.CATEGORY_GENERAL)
                .get("showNeiPanelInTerminalGui")
                .getBoolean(true);
            boolean newKeyPresent = configuration.getCategory(Configuration.CATEGORY_GENERAL)
                .containsKey("terminalNeiPanel");

            terminalNeiPanel = parseEnum(
                configuration.get(
                    Configuration.CATEGORY_GENERAL,
                    "terminalNeiPanel",
                    terminalNeiPanel.name(),
                    "共享终端界面里 NEI 物品面板的可见性：SHOW=显示（与界面重叠的格子会被遮罩，不会误触界面自己的控件）；HIDE=收起（注意「搜索条跟随面板」布局下搜索条会一起收掉）。客户端行为，各端读各自的配置。")
                    .getString(),
                TerminalNeiPanel.class,
                terminalNeiPanel);
            if (legacyHide && !newKeyPresent) {
                terminalNeiPanel = TerminalNeiPanel.HIDE;
            }
            if (!legacyShow && !newKeyPresent) {
                terminalNeiPanel = TerminalNeiPanel.HIDE;
            }
            if (legacyKeyPresent) {
                configuration.getCategory(Configuration.CATEGORY_GENERAL)
                    .remove("hideNeiPanelInTerminalGui");
            }
            if (legacyShowKeyPresent) {
                configuration.getCategory(Configuration.CATEGORY_GENERAL)
                    .remove("showNeiPanelInTerminalGui");
            }
        }

        terminalSearchMode = parseEnum(
            configuration
                .get(
                    Configuration.CATEGORY_GENERAL,
                    "terminalSearchMode",
                    terminalSearchMode.name(),
                    "共享终端搜索框与 NEI 的联动方式（对齐 AE2 终端的搜索模式）：" + "MANUAL=手动聚焦；AUTO=打开界面自动聚焦；"
                        + "NEI_SYNC=输入实时同步到 NEI 搜索条；NEI_SYNC_AUTO=同步+自动聚焦。"
                        + "未装 NEI 时 NEI_SYNC* 按 AUTO 对待。客户端行为，界面里的循环按钮会改写这一项。")
                .getString(),
            TerminalSearchMode.class,
            terminalSearchMode);

        enableRoguelikeMap = configuration.getBoolean(
            "enableRoguelikeMap",
            Configuration.CATEGORY_GENERAL,
            enableRoguelikeMap,
            "是否启用 Roguelike Dungeons 地图快捷键和悬浮小地图。客户端行为，各端读取自己的配置。");

        roguelikeMapScanRadius = configuration.getInt(
            "roguelikeMapScanRadius",
            Configuration.CATEGORY_GENERAL,
            roguelikeMapScanRadius,
            24,
            128,
            "地牢地图每次扫描当前楼层的半径。只读取已经加载的区块；半径越大，地图建立越完整但扫描开销越高。");

        enableRecipe = configuration
            .getBoolean("enableRecipe", Configuration.CATEGORY_GENERAL, enableRecipe, "是否注册共享终端的合成配方。");

        webRecipeEnable = configuration.getBoolean(
            "webRecipeEnable",
            Configuration.CATEGORY_GENERAL,
            webRecipeEnable,
            "是否启用手机网页版的配方查询与合成指导（客户端内嵌一个只读的 HTTP 服务，数据来自 NEI）。");

        webRecipeAutoStart = configuration.getBoolean(
            "webRecipeAutoStart",
            Configuration.CATEGORY_GENERAL,
            webRecipeAutoStart,
            "客户端启动后是否自动开始监听。关掉后可以用 P 键或 /futaweb start 手动开。");

        webRecipePrebuild = configuration.getBoolean(
            "webRecipePrebuild",
            Configuration.CATEGORY_GENERAL,
            webRecipePrebuild,
            "启动后是否自动建立全量配方索引（首次要跑一会儿，之后走 config/futa_gtnh/web_recipes.dat 缓存秒开）。");

        webRecipePort = configuration.getInt(
            "webRecipePort",
            Configuration.CATEGORY_GENERAL,
            webRecipePort,
            1024,
            65535,
            "网页监听端口。手机访问的地址是 http://电脑IP:这个端口/ 。");

        webRecipeBindAddress = configuration.getString(
            "webRecipeBindAddress",
            Configuration.CATEGORY_GENERAL,
            webRecipeBindAddress,
            "网页绑定地址。0.0.0.0=局域网可访问（手机要连这个）；127.0.0.1=只有本机能访问。");

        webRecipeToken = configuration.getString(
            "webRecipeToken",
            Configuration.CATEGORY_GENERAL,
            webRecipeToken,
            "访问口令。留空=不校验；填了就必须用带 ?k=口令 的完整地址打开（按键时聊天栏给的就是完整地址）。");

        enableLootMachine = configuration.getBoolean(
            "enableLootMachine",
            Configuration.CATEGORY_GENERAL,
            enableLootMachine,
            "是否注册自选抽奖机方块（需要装了 Enhanced LootBags；放入战利品袋+时运附魔书，模拟开袋最多 roll 三次，可花技术员代币重置次数）。");

        lootMachineTeamWallet = configuration.getBoolean(
            "lootMachineTeamWallet",
            Configuration.CATEGORY_GENERAL,
            lootMachineTeamWallet,
            "抽奖机重置 roll 次数时，扣技术员代币的哪个钱包：true=团队钱包（与 VendingMachine 团队模式一致），false=个人钱包。服务端行为。");

        enableSolarDescaler = configuration.getBoolean(
            "enableSolarDescaler",
            Configuration.CATEGORY_GENERAL,
            enableSolarDescaler,
            "是否注册太阳能除钙剂（右键蒸汽太阳能锅炉，重置其钙化进度；永久工具不消耗）。");

        enableDisassembler = configuration.getBoolean(
            "enableDisassembler",
            Configuration.CATEGORY_GENERAL,
            enableDisassembler,
            "注册 LV 单步拆解机（固定 32 EU/t、1A、2 秒）。客户端和服务端需一致。");
        disassemblerMetaTileId = configuration.getInt(
            "disassemblerMetaTileId",
            Configuration.CATEGORY_GENERAL,
            disassemblerMetaTileId,
            1,
            32766,
            "拆解机的稳定 GT MTE ID；冲突时修改，已有世界不可随意更换。客户端和服务端需一致。");

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
            "迅步的速度倍率上限（原版速度的倍数）。专用服务器按水平与竖直合位移计算安全上限，超过后会被判定 moved too quickly 并拉回原地；单人存档不受此限制。");

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

        locatorBiomeSearchRadius = configuration.getInt(
            "locatorBiomeSearchRadius",
            Configuration.CATEGORY_GENERAL,
            locatorBiomeSearchRadius,
            512,
            8192,
            "寻物魔杖的生物群系搜索半径（方块）。此搜索只查询二维生物群系数据，不加载搜索范围内的区块。");

        locatorBiomeSamplesPerTick = configuration.getInt(
            "locatorBiomeSamplesPerTick",
            Configuration.CATEGORY_GENERAL,
            locatorBiomeSamplesPerTick,
            10000,
            2000000,
            "生物群系搜索每 tick 检查多少个位置。调大结果更快，但会占用更多服务器 tick 时间。");

        locatorBiomeScanTimeoutTicks = configuration.getInt(
            "locatorBiomeScanTimeoutTicks",
            Configuration.CATEGORY_GENERAL,
            locatorBiomeScanTimeoutTicks,
            100,
            72000,
            "一次生物群系搜索的最长时间（tick）；范围很大或目标很远时可提高。");

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

        locatorAutoAdvance = configuration.getBoolean(
            "locatorAutoAdvance",
            Configuration.CATEGORY_GENERAL,
            locatorAutoAdvance,
            "追踪的方块被自己挖掉后，戴着魔杖时是否自动传送到下一处（同样的目标、从当前位置重搜）。" + "只在魔杖戴在饰品栏、且挖掉的正是当前追踪那一格时触发；别人挖掉或爆炸炸掉只会让光束换目标，不会传送。"
                + "传送规则和手动传送完全一致（找安全落点、允许就地开两格、绝不碰矿石）。");

        locatorAutoAdvanceMinDistance = configuration.getInt(
            "locatorAutoAdvanceMinDistance",
            Configuration.CATEGORY_GENERAL,
            locatorAutoAdvanceMinDistance,
            0,
            256,
            "自动追下一处的最小距离（格）：新目标比这更近就只把追踪切过去、不传送。" + "挖矿时下一个常常就在隔壁一两格，为这个挪一下既没意义又晃眼。0 = 每次都传。");

        enableMinigameHelper = configuration.getBoolean(
            "enableMinigameHelper",
            Configuration.CATEGORY_GENERAL,
            enableMinigameHelper,
            "是否注册小游戏助手（右键打开全服共享的 lootgames 地牢列表，可搜索附近并标记已完成）。需要装了 LootGames。");

        lootgamesFullRewardRetries = configuration.getInt(
            "lootgamesFullRewardRetries",
            Configuration.CATEGORY_GENERAL,
            lootgamesFullRewardRetries,
            1,
            1000,
            "lootgames 小游戏第几次失败自动按满奖励结算（4 个战利品箱）。失败次数未到之前无限重试，且失败不再有爆炸/刷怪/岩浆惩罚。会覆写 LootGames 自己的 attempt_count 配置。");

        lootassistSearchRadius = configuration.getInt(
            "lootassistSearchRadius",
            Configuration.CATEGORY_GENERAL,
            lootassistSearchRadius,
            128,
            3000,
            "小游戏助手「搜索附近」的半径（方块，以玩家为圆心）。候选点按种子推算零开销，确认地牢是否生成需要逐个加载区块，半径越大确认越久。");

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

    /**
     * 运行时回写共享终端的搜索联动模式（点界面里的循环按钮时调用）。
     * 与 {@link #saveClientGuiSort} 同一套路：重读配置、只改一项、存盘。
     */
    public static void saveTerminalSearchMode(TerminalSearchMode mode) {
        terminalSearchMode = mode;
        if (configFileRef == null) return;
        try {
            Configuration configuration = new Configuration(configFileRef);
            configuration.get(Configuration.CATEGORY_GENERAL, "terminalSearchMode", mode.name())
                .set(mode.name());
            configuration.save();
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("保存共享终端搜索联动模式失败（不影响本次使用）", t);
        }
    }

    /** 按枚举名解析配置字符串，认不出（改名/手改坏值）时退回默认。 */
    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type, E fallback) {
        if (value != null) {
            for (E constant : type.getEnumConstants()) {
                if (constant.name()
                    .equals(value)) return constant;
            }
        }
        return fallback;
    }
}
