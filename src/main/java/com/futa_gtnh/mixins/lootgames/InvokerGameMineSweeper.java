package com.futa_gtnh.mixins.lootgames;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import ru.timeconqueror.lootgames.minigame.minesweeper.GameMineSweeper;

/**
 * 把扫雷私有的 {@code onLevelSuccessfullyFinished()} 暴露给
 * {@link MixinGameMasterBlock} 的潜行右键自动完成逻辑。
 *
 * <p>
 * 接口 mixin 的 {@code @Invoker} 会在目标类里生成接口实现，所以拿到
 * {@code GameMineSweeper} 实例后直接强转成本接口就能调私有方法 ——
 * 目标类无需任何 public 放行。
 */
@Mixin(value = GameMineSweeper.class)
public interface InvokerGameMineSweeper {

    @Invoker(value = "onLevelSuccessfullyFinished", remap = false)
    void futa$invokeLevelSuccessfullyFinished();
}
