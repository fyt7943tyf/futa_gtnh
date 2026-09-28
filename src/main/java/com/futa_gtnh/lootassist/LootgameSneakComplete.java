package com.futa_gtnh.lootassist;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import com.futa_gtnh.mixins.lootgames.LootgameAutoComplete;

import cpw.mods.fml.common.eventhandler.Event.Result;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import ru.timeconqueror.lootgames.api.block.tile.GameMasterTile;
import ru.timeconqueror.lootgames.api.minigame.LootGame;

/**
 * 潜行右击小游戏主方块 = 自动完成当前进度（逻辑在 {@link LootgameAutoComplete}）。
 *
 * <p>
 * <b>为什么挂在 Forge 的 {@link PlayerInteractEvent} 上，而不是像初版那样 mixin
 * {@code GameMasterBlock.onBlockActivated}</b> —— 那条路有两个绕不过去的坑：
 *
 * <ol>
 * <li><b>原版会把「潜行 + 手持物品」的右键直接挡掉</b>：服务端的
 * {@code ItemInWorldManager.activateBlockOrUseItem} 在「潜行且手里有东西」时
 * 根本不调用 {@code onBlockActivated}（除非物品声明
 * {@code doesSneakBypassUse}），而是去用手里那个物品。玩家十有八九手里拿着东西，
 * 于是跳关功能在实机上「按了没反应」。</li>
 * <li><b>mixin 目标在生产环境找不到</b>：{@code onBlockActivated} 是原版方法的覆写，
 * LootGames 的正式 jar 里它已被 reobf 成 SRG 名（{@code func_149727_a}）。
 * {@code remap = false} 的注入在开发环境（MCP 名）一切正常，到了正式包必失败。</li>
 * </ol>
 *
 * <p>
 * {@code PlayerInteractEvent} 由 Forge 补丁挂在 {@code activateBlockOrUseItem}
 * 的<b>最开头</b>、上述两道关卡之前，任何右击都会到达这里。处理完这一次点击后
 * 取消事件，把原版「用物品 / 激活方块」的两条后续路径都关掉。
 *
 * <p>
 * <b>本类只有装了 LootGames 才允许加载</b>（import 了 lootgames 的类型）：
 * 在 {@code CommonProxy.preInit} 里用 {@code LootgamesCompat.isAvailable()} 守卫注册。
 * 事件本身对每个右击都会进来，所以前置判断按「最便宜的先来」排。
 */
public final class LootgameSneakComplete {

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        if (event.world == null || event.world.isRemote) return;
        if (!event.entityPlayer.isSneaking()) return;
        if (!(event.entityPlayer instanceof EntityPlayerMP)) return;

        TileEntity tile = event.world.getTileEntity(event.x, event.y, event.z);
        // 入口那块 PuzzleMaster 的 tile 不继承 GameMasterTile，天然不受影响
        if (!(tile instanceof GameMasterTile)) return;

        LootGame<?, ?> game = ((GameMasterTile<?>) tile).getGame();
        if (game == null) return;

        if (LootgameAutoComplete.tryComplete(game, (EntityPlayerMP) event.entityPlayer)) {
            // 这次点击已经被跳关逻辑消费：原版的「激活方块」和「使用手里物品」都拦下，
            // 免得跳关的同时还往主方块上放了个方块 / 触发了手里物品的交互
            event.useBlock = Result.DENY;
            event.useItem = Result.DENY;
            event.setCanceled(true);
        }
    }
}
