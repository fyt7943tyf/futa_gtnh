package com.futa_gtnh.mixins.lootgames;

import net.minecraft.util.ChatComponentTranslation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.timeconqueror.lootgames.api.minigame.LootGame;
import ru.timeconqueror.lootgames.minigame.gol.GameOfLight;

/**
 * 光之游戏的「无限重试 + 第 N 次失败满奖励结算」，思路同
 * {@link MixinGameMineSweeper}：真通关时 {@code maxReachedStage} 必为 4
 * （{@code onSuccessSequence} 在最后一关先置 4 再 win），只有重试耗尽的
 * 「按最好成绩折算」路径会带着 1..3 进来 —— 抬到 4 即满奖励。
 *
 * <p>
 * {@code triggerGameLose} 是失败惩罚的入口（按配置随机爆炸 / 刷僵尸 / 倒岩浆），
 * 只会在「耗尽且一关都没过」时触发 —— 整个拦掉转 win，惩罚代码原样保留但
 * 正常游玩永远不会再执行。
 *
 * <p>
 * 重试上限由 {@code LootgamesCompat.applyServerTweaks()} 覆写
 * {@code LGConfigs.GOL.attemptCount} 实现，原版 {@code failGame} 的分支不动。
 */
@Mixin(value = GameOfLight.class)
public abstract class MixinGameOfLight {

    @Shadow(remap = false)
    private int maxReachedStage;

    @Shadow(remap = false)
    protected abstract void triggerGameWin();

    @Inject(method = "triggerGameWin", at = @At("HEAD"), remap = false)
    private void futa$fullRewardOnExhaustionWin(CallbackInfo ci) {
        if (maxReachedStage >= 1 && maxReachedStage <= 3) {
            maxReachedStage = 4;
            futa$announceFullReward();
        }
    }

    @Inject(method = "triggerGameLose", at = @At("HEAD"), cancellable = true, remap = false)
    private void futa$loseBecomesFullReward(CallbackInfo ci) {
        maxReachedStage = 4;
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
