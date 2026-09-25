package com.futa_gtnh.mixins.lootgames;

import net.minecraft.entity.player.EntityPlayerMP;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import ru.timeconqueror.lootgames.minigame.gol.GameOfLight;

/**
 * 把光之游戏「等待玩家复述序列」阶段私有的 {@code onSuccessSequence(EntityPlayerMP)}
 * 暴露给 {@link MixinGameMasterBlock} 的潜行右键自动完成逻辑。
 *
 * <p>
 * 这个方法在目标类里做的是一整轮/一整关的推进：播过关音效、记
 * {@code maxReachedStage}、升轮或升阶，最后一关直接转 win 发满奖励 ——
 * 和玩家亲手按对最后一位符号走的是同一条路。
 *
 * <p>
 * 注意目标是 GameOfLight 的<b>内部类</b>：cast 的是 stage 实例本身
 * （{@code game.getStage()}），它运行时就是
 * {@code GameOfLight$StageWaitingForSequence}，mixin 合并后自然实现了本接口。
 */
@Mixin(value = GameOfLight.StageWaitingForSequence.class)
public interface InvokerGOLStage {

    @Invoker(value = "onSuccessSequence", remap = false)
    void futa$invokeOnSuccessSequence(EntityPlayerMP player);
}
