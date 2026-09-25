package com.futa_gtnh.mixins.lootgames;

import net.minecraft.util.ChatComponentTranslation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.futa_gtnh.lootassist.LootgamesCompat;

import ru.timeconqueror.lootgames.api.minigame.LootGame;
import ru.timeconqueror.lootgames.common.config.ConfigSudoku;
import ru.timeconqueror.lootgames.minigame.sudoku.GameSudoku;

/**
 * 数独的「无限重试 + 第 N 次失败满奖励结算」。
 *
 * <p>
 * 和扫雷/光之游戏不同，数独的失败上限<b>不是</b>读全局配置，而是开局时从
 * {@code ConfigSudokuSnapshot} 拷贝进每局游戏并随 NBT 序列化（见
 * {@code GameSudoku.configSnapshot}）—— 所以在 preInit 覆写公共配置字段管不到它：
 * 新局会拿到新值，但<b>更新前就已经在进行的旧局</b>还揣着旧上限。
 * 于是这里用 {@code @Redirect} 把 {@code handleEndGameCheck} 里对
 * {@code getAttemptCount()} 的调用整个换成我们的配置值，新旧局行为完全一致
 * （方法里两处调用——判定和「剩余次数」提示——都被替换，语义正好）。
 *
 * <p>
 * win 侧的满奖励拦截同 {@link MixinGameMineSweeper}：真通关时
 * {@code currentLevel} 必为 5（{@code onLevelSuccessfullyFinished} 第 4 关先自增
 * 再 win），带着 2..4 进来的只可能是重试耗尽的「部分奖励」路径。第 1 关耗尽走
 * lose 的路径在基类上拦（数独没有覆写它），见 {@link MixinLootGame}。
 */
@Mixin(value = GameSudoku.class)
public abstract class MixinGameSudoku {

    @Shadow(remap = false)
    public int currentLevel;

    @Shadow(remap = false)
    protected abstract void triggerGameWin();

    @Inject(method = "triggerGameWin", at = @At("HEAD"), remap = false)
    private void futa$fullRewardOnExhaustionWin(CallbackInfo ci) {
        if (currentLevel >= 2 && currentLevel <= 4) {
            currentLevel = 5;
            futa$announceFullReward();
        }
    }

    @Redirect(
        method = "handleEndGameCheck",
        at = @At(
            value = "INVOKE",
            target = "Lru/timeconqueror/lootgames/common/config/ConfigSudoku$ConfigSudokuSnapshot;getAttemptCount()I",
            remap = false),
        remap = false)
    private int futa$redirectAttemptLimit(ConfigSudoku.ConfigSudokuSnapshot snapshot) {
        return LootgamesCompat.fullRewardRetries();
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
