package com.futa_gtnh.lootassist;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.Loader;
import ru.timeconqueror.lootgames.LootGames;
import ru.timeconqueror.lootgames.common.config.LGConfigs;

/**
 * LootGames 联动的桥接类 —— 本工程里<b>唯一直接引用 lootgames 类型</b>的普通类之一
 * （另一处是 {@link LootgameFinder} 和 mixin 包）。
 *
 * <p>
 * 和匠魂联动（{@code com.futa_gtnh.tinkers}）同一个套路：lootgames 是可选联动，
 * 这里所有碰 {@code ru.timeconqueror.lootgames.*} 的方法都必须先过
 * {@link #isAvailable()} 这道闸，没装 LootGames 时绝不能进入这些方法体 ——
 * JVM 的惰性解析保证只要方法不执行，缺失的类就不会被加载。
 *
 * <p>
 * 对 lootgames 的「行为修改」全部由 mixin 完成（见 {@code com.futa_gtnh.mixins.lootgames}）；
 * 这个类只负责两件事：
 *
 * <ul>
 * <li><b>preInit 时覆写尝试次数配置</b>：扫雷/光之游戏的失败上限直接读
 * {@code LGConfigs} 的 public 字段，在 lootgames 加载完自己的配置之后写一次即可；
 * 数独的上限存在每局游戏序列化的快照里，覆写不了，由
 * {@code MixinGameSudoku} 的 {@code @Redirect} 在运行时替换。</li>
 * <li><b>给 mixin 提供满奖励重试次数</b>（{@link #fullRewardRetries()}）。</li>
 * </ul>
 */
public final class LootgamesCompat {

    private LootgamesCompat() {}

    /** @return LootGames 是否在场（已加载/可在类路径上找到） */
    public static boolean isAvailable() {
        return Loader.isModLoaded(LootGames.MODID);
    }

    /**
     * preInit 里调用（本模组声明了 {@code after:lootgames}，此时它的配置已经加载完）。
     *
     * <p>
     * 覆写之后，LootGames 配置文件里的 {@code attempt_count} 就被架空了：
     * 失败上限以 {@link Config#lootgamesFullRewardRetries} 为准。每次启动都会重写，
     * 所以玩家改 lootgames 配置文件也改变不了行为 —— 这是有意的，
     * 「无限重试 + 第 N 次满奖励」是本模组的整体行为，拆开配反而说不清。
     */
    public static void applyServerTweaks() {
        if (!isAvailable()) return;

        int retries = Config.lootgamesFullRewardRetries;
        LGConfigs.MINESWEEPER.attemptCount = retries;
        LGConfigs.GOL.attemptCount = retries;
        FutaGtnhMod.LOG.info("LootGames 联动：小游戏失败重试上限已覆写为 {}（第 {} 次失败自动满奖励结算，失败惩罚已由 mixin 取消）", retries, retries);
    }

    /** @return 满奖励结算的重试上限（mixin 运行时读取） */
    public static int fullRewardRetries() {
        return Config.lootgamesFullRewardRetries;
    }
}
