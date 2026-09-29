package com.futa_gtnh.lootassist;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import com.futa_gtnh.mixins.lootgames.LootgameAutoComplete;

import cpw.mods.fml.common.eventhandler.Event.Result;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import ru.timeconqueror.lootgames.api.block.GameMasterBlock;
import ru.timeconqueror.lootgames.api.block.SmartSubordinateBlock;
import ru.timeconqueror.lootgames.api.block.tile.GameMasterTile;
import ru.timeconqueror.lootgames.api.minigame.LootGame;
import ru.timeconqueror.lootgames.utils.future.BlockPos;

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
 * <b>主方块在哪、为什么要提示</b>：三种游戏开局时都会在<b>棋盘边框的西北外角</b>
 * 放一块主方块（{@code FieldManager.trySetupBoard}），但它<b>没有任何自己的贴图</b>
 * —— 和房间地板渲染成同一个样子，扫雷/数独的大棋盘上根本认不出哪块是主方块。
 * 而棋盘格子的「潜行 + 右击」又被真实玩法占着（扫雷是扫弦、数独是减小数字），
 * 不能拿来做跳关。所以这里是<b>发现性提示</b>：玩家潜行右击棋盘格子、
 * 且当前正处于可跳关阶段时，提示一次主方块的位置（每次登录最多一次），
 * 点击本身照常交给游戏。
 *
 * <p>
 * <b>本类只有装了 LootGames 才允许加载</b>（import 了 lootgames 的类型）：
 * 在 {@code CommonProxy.preInit} 里用 {@code LootgamesCompat.isAvailable()} 守卫注册。
 * 事件本身对每个右击都会进来，所以前置判断按「最便宜的先来」排。
 */
public final class LootgameSneakComplete {

    /** 已经收到过跳关位置提示的玩家（弱引用键，玩家对象回收后自动清出）。 */
    private static final Set<UUID> HINTED = Collections.newSetFromMap(new java.util.WeakHashMap<>());

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent event) {
        if (event.action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        if (event.world == null || event.world.isRemote) return;
        if (!event.entityPlayer.isSneaking()) return;
        if (!(event.entityPlayer instanceof EntityPlayerMP)) return;

        // ---- 主方块：真正的跳关入口 ----
        if (event.world.getBlock(event.x, event.y, event.z) instanceof GameMasterBlock) {
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
            return;
        }

        // ---- 棋盘格子：不抢操作，只在「现在点主方块就能跳」时提示一次位置 ----
        if (event.world.getBlock(event.x, event.y, event.z) instanceof SmartSubordinateBlock) {
            EntityPlayerMP player = (EntityPlayerMP) event.entityPlayer;
            if (HINTED.contains(player.getUniqueID())) return;

            TileEntity master = masterTileAt(event.world, event.x, event.y, event.z);
            if (!(master instanceof GameMasterTile)) return;

            LootGame<?, ?> game = ((GameMasterTile<?>) master).getGame();
            if (game == null || !LootgameAutoComplete.canSkip(game)) return;

            HINTED.add(player.getUniqueID());
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.lootgames.skip_hint"));
        }
    }

    /**
     * 从一块棋盘格子出发找主方块的 tile。
     *
     * <p>
     * 直接复用 LootGames 自己的寻路（{@link SmartSubordinateBlock#getMasterPos}）：
     * 沿格子向西走到头、再向北走一格、最后对角挪到外角 —— 主方块就钉在那儿。
     * 我们自己再走一遍，迟早会跟它改布局时对不上。
     */
    private TileEntity masterTileAt(net.minecraft.world.World world, int x, int y, int z) {
        BlockPos masterPos = SmartSubordinateBlock.getMasterPos(world, BlockPos.of(x, y, z));
        return world.getTileEntity(masterPos.getX(), masterPos.getY(), masterPos.getZ());
    }
}
