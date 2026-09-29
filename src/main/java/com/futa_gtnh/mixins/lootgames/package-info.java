/**
 * 针对 LootGames（{@code ru.timeconqueror.lootgames.*}）的 mixin。
 *
 * <p>
 * 这个包里的所有类都是<b>可选联动</b>：{@link com.futa_gtnh.mixins.FutaGtnhMixinPlugin}
 * 会先探测 lootgames 在不在，不在就整包跳过（所以 lootgames 缺席时行为零影响）。
 *
 * <p>
 * 两组功能：
 * <ol>
 * <li><b>无限重试 + 第 N 次失败满奖励结算 + 取消失败惩罚</b>：
 * {@link MixinGameMineSweeper} / {@link MixinGameOfLight} / {@link MixinGameSudoku}
 * / {@link MixinLootGame}。原理是利用「真通关时游戏进度必为满级」这个不变量：
 * {@code triggerGameWin} 被调用时进度在中间档，只可能来自「重试次数耗尽、
 * 按已达到进度折算部分奖励」的失败路径 —— 把进度抬到满级再放行，就变成了满奖励；
 * {@code triggerGameLose}（第 1 关耗尽的爆炸/刷怪/岩浆路径）整个拦掉，同样转为满奖励。</li>
 * <li><b>潜行右击游戏主方块自动完成当前关</b>：{@link LootgameAutoComplete} 按游戏类型
 * 调到对应的「过关」私有方法上
 * （{@link InvokerGameMineSweeper} / {@link InvokerGOLStage}）。只对进行中的游戏生效，
 * PuzzleMasterBlock（未开局）不经过这里。
 *
 * <p>
 * 这一条<b>不走 mixin</b>：原来是一个拦 GameMasterBlock 右键的 mixin，现在改成
 * {@code lootassist.LootgameSneakComplete} 里的 Forge 事件处理器 —— 要在「没跳过」
 * 的时候给玩家一句解释、还要顺带做发现性提示，用事件比注入方块类干净得多。</li>
 * </ol>
 *
 * <p>
 * 写法注意（和根包里的匠魂 mixin 一致）：lootgames 的类名/方法名<b>不参与原版混淆</b>，
 * 所有 {@code @Shadow} / {@code @Inject} / {@code @At} 都要写 {@code remap = false}，
 * 否则注解处理器会拿这些名字去查原版混淆表然后报「找不到映射」。
 */
package com.futa_gtnh.mixins.lootgames;
