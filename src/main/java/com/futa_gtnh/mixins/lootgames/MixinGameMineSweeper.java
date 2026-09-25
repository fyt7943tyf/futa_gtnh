package com.futa_gtnh.mixins.lootgames;

import net.minecraft.util.ChatComponentTranslation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.timeconqueror.lootgames.api.minigame.LootGame;
import ru.timeconqueror.lootgames.minigame.minesweeper.GameMineSweeper;

/**
 * 扫雷的「无限重试 + 第 N 次失败满奖励结算」。
 *
 * <p>
 * 原版结算逻辑（{@code GameMineSweeper}）依赖两条不变量，正好都能被精确识别：
 *
 * <ul>
 * <li><b>真通关</b>必然是 {@code onLevelSuccessfullyFinished} 在 {@code currentLevel == 4}
 * 时先自增到 5 再调 {@code triggerGameWin()} —— 所以 {@code triggerGameWin} 进来时
 * {@code currentLevel == 5} 的一定是真通关，原样放行。</li>
 * <li><b>重试耗尽</b>（{@code StageDetonating.onTick}）走的是同一个小游戏结算但
 * {@code currentLevel} 还停在 2..4（按已达到关卡折算 1..3 个箱子的「部分奖励」），
 * 或 {@code currentLevel == 1} 时直接 {@code triggerGameLose()}（中心爆炸）。</li>
 * </ul>
 *
 * <p>
 * 于是：win 进来时进度在 2..4 → 抬到 5（原逻辑随后按 {@code currentLevel-1 = 4}
 * 生成全部 4 个战利品箱）；lose 进来 → 抬到 5 转 win 并取消（爆炸因此永远不会发生）。
 * 重试上限本身不用改逻辑：{@code LootgamesCompat.applyServerTweaks()} 在 preInit
 * 把 {@code LGConfigs.MINESWEEPER.attemptCount} 覆写成配置值（默认 10），
 * 原版的「重试 / 结算」分支判断照常工作，只是次数变了。
 */
@Mixin(value = GameMineSweeper.class)
public abstract class MixinGameMineSweeper {

    @Shadow(remap = false)
    private int currentLevel;

    @Shadow(remap = false)
    protected abstract void triggerGameWin();

    @Inject(method = "triggerGameWin", at = @At("HEAD"), remap = false)
    private void futa$fullRewardOnExhaustionWin(CallbackInfo ci) {
        if (currentLevel >= 2 && currentLevel <= 4) {
            currentLevel = 5;
            futa$announceFullReward();
        }
    }

    @Inject(method = "triggerGameLose", at = @At("HEAD"), cancellable = true, remap = false)
    private void futa$loseBecomesFullReward(CallbackInfo ci) {
        currentLevel = 5;
        futa$announceFullReward();
        triggerGameWin();
        ci.cancel();
    }

    /**
     * 广播满奖励结算提示。{@code sendToNearby} 声明在基类 {@link LootGame} 上、
     * 不在目标类里，不能 {@code @Shadow}，走显式转型。
     */
    @SuppressWarnings("rawtypes")
    private void futa$announceFullReward() {
        ((LootGame) (Object) this).sendToNearby(new ChatComponentTranslation("futa_gtnh.msg.lootgames.full_reward"));
    }
}
