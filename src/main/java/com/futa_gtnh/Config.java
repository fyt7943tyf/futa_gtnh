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
     * 默认 20 差不多就是<b>专用服务器的物理上限</b>了：飞行终端速度约等于
     * {@code flySpeed × 9.1} 格/tick，20 倍正好是 9.1 格/tick，而
     * {@code NetHandlerPlayServer} 在单轴超过 10 格/tick 时会判定
     * 「moved too quickly」并把你拉回原地。再往上就会开始被拉回
     * （单人 / 局域网主机不受这条检查限制，所以自己开档感觉不出来）。
     */
    public static double swiftStepMaxMultiplier = 20.0D;

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

        enableRecipe = configuration
            .getBoolean("enableRecipe", Configuration.CATEGORY_GENERAL, enableRecipe, "是否注册共享终端的合成配方。");

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
            "迅步的速度倍率上限（原版速度的倍数）。实际上限由服务端决定；超过约 20 倍时专用服务器会因「moved too quickly」把人拉回。");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
