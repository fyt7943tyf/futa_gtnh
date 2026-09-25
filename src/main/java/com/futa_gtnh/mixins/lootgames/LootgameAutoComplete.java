package com.futa_gtnh.mixins.lootgames;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentTranslation;

import ru.timeconqueror.lootgames.api.minigame.LootGame;
import ru.timeconqueror.lootgames.minigame.gol.GameOfLight;
import ru.timeconqueror.lootgames.minigame.minesweeper.GameMineSweeper;
import ru.timeconqueror.lootgames.minigame.sudoku.GameSudoku;

/**
 * 潜行右击游戏主方块的「自动完成当前进度」分发逻辑。
 *
 * <p>
 * 只在服务端、且玩家潜行时被 {@link MixinGameMasterBlock} 调用。按游戏类型
 * 找到它当前的「等待玩家输入」阶段，然后走该游戏<b>原版的过关路径</b>：
 *
 * <ul>
 * <li>扫雷：{@code onLevelSuccessfullyFinished()} —— 展示粒子、升下一关棋盘，
 * 第 4 关直接转 win（带满奖励）。</li>
 * <li>数独：同上（方法名一样，public 的，直接调）。</li>
 * <li>光之游戏：{@code onSuccessSequence(player)} —— 把当前一轮记为成功，
 * 升轮/升阶或最终 win。这个方法在内部类里、private，走 {@link InvokerGOLStage}。</li>
 * </ul>
 *
 * <p>
 * 每次只完成<b>一个进度单位</b>（扫雷/数独是一关，光之游戏是一轮），
 * 潜行右击 4 次通关一个 4 关游戏。不在可跳过阶段（例如光之游戏正在播放序列、
 * 扫雷正在引信动画）时给玩家一句提示并吞掉这次点击。
 */
public final class LootgameAutoComplete {

    private LootgameAutoComplete() {}

    /**
     * 尝试完成当前进度。
     *
     * @return true 表示这次点击已被处理（调用方应取消原版逻辑）；false 表示
     *         不是本 mod 认识的游戏类型，放行给原版
     */
    public static boolean tryComplete(LootGame<?, ?> game, EntityPlayerMP player) {
        Object stage = game.getStage();
        if (stage == null) return false;

        if (game instanceof GameMineSweeper) {
            if (!(stage instanceof GameMineSweeper.StageWaiting)) {
                deny(game, player);
                return true;
            }
            ((InvokerGameMineSweeper) game).futa$invokeLevelSuccessfullyFinished();
            return true;
        }

        if (game instanceof GameSudoku) {
            ((GameSudoku) game).onLevelSuccessfullyFinished();
            return true;
        }

        if (game instanceof GameOfLight) {
            if (!(stage instanceof GameOfLight.StageWaitingForSequence)) {
                deny(game, player);
                return true;
            }
            ((InvokerGOLStage) stage).futa$invokeOnSuccessSequence(player);
            return true;
        }

        return false;
    }

    /** 过关路径内部都会广播 stage_complete / win 消息，只有「没跳成」需要额外说明。 */
    private static void deny(LootGame<?, ?> game, EntityPlayerMP player) {
        game.sendTo(player, new ChatComponentTranslation("futa_gtnh.msg.lootgames.level_skip_denied"));
    }
}
