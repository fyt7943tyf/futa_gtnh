package com.futa_gtnh.mixins.lootgames;

import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import ru.timeconqueror.lootgames.api.minigame.LootGame;
import ru.timeconqueror.lootgames.minigame.sudoku.GameSudoku;

/**
 * 数独的「失败结局 → 满奖励结算」转换。
 *
 * <p>
 * 数独<b>没有覆写</b> {@code triggerGameLose}（扫雷/光之游戏都有），失败结局直接走到
 * 基类 {@link LootGame#triggerGameLose}，所以拦截只能做在基类上。基类是所有游戏的
 * 父类，因此必须用 {@code instanceof GameSudoku} 守卫 —— 其他游戏（包括第三方通过
 * API 注册的）从这里经过时原样放行，行为不变。扫雷/光之游戏覆写里的
 * {@code super.triggerGameLose()} 也会路过这里，但它们的耗尽路径在各自的覆写里
 * 已经被拦掉，永远走不到 super。
 *
 * <p>
 * 触发条件回顾（{@code GameSudoku.handleEndGameCheck}）：答错时
 * {@code currentLevel > 1} 走 win（按已达到关卡折算部分奖励，由
 * {@link MixinGameSudoku} 抬成满奖励）；{@code currentLevel == 1} 走 lose ——
 * 就是这里，第 1 关失败耗尽本该触发失败音效+结束，改成直接给满奖励。
 */
@Mixin(value = LootGame.class)
public abstract class MixinLootGame {

    @Shadow(remap = false)
    protected abstract void triggerGameWin();

    @Shadow(remap = false)
    public abstract void sendToNearby(IChatComponent component);

    @Inject(method = "triggerGameLose", at = @At("HEAD"), cancellable = true, remap = false)
    private void futa$sudokuLoseBecomesFullReward(CallbackInfo ci) {
        if (!((Object) this instanceof GameSudoku)) return;

        GameSudoku game = (GameSudoku) (Object) this;
        // currentLevel 抬到 5 之后，GameSudoku.triggerGameWin 会按 currentLevel-1 = 4
        // 生成全部 4 个战利品箱（virtual dispatch 调到子类的覆写）
        game.currentLevel = 5;
        sendToNearby(new ChatComponentTranslation("futa_gtnh.msg.lootgames.full_reward"));
        triggerGameWin();
        ci.cancel();
    }
}
