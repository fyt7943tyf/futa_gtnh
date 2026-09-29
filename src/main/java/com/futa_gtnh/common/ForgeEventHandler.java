package com.futa_gtnh.common;

import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.EntityItemPickupEvent;

import com.futa_gtnh.exchange.AutoStore;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorageManager;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * Forge 事件总线上的服务端逻辑。
 *
 * <p>
 * 和 {@link ModEventHandler} 分开放，是因为它们注册在<b>两条不同的总线</b>上：
 * FML 自己的 {@code TickEvent} / {@code PlayerEvent} 走
 * {@code FMLCommonHandler.instance().bus()}，而 Forge 的
 * {@code net.minecraftforge.event.*} 走 {@code MinecraftForge.EVENT_BUS}。
 * 注册错地方不会报错，只会永远收不到事件 —— 所以刻意用两个类把这件事写死。
 */
public class ForgeEventHandler {

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new ForgeEventHandler());
    }

    /**
     * 拾取拦截：开着「自动入库」的玩家捡东西时，物品直接进共享存储，不进背包。
     *
     * <p>
     * 用 {@link EntityItemPickupEvent} 而不是「先让原版捡进背包、再把背包里的搬走」，
     * 关键在于这个事件的时序：它是在 {@code EntityItem.onCollideWithPlayer} 里、
     * <b>调用 {@code addItemStackToInventory} 之前</b>触发的，而且取消之后
     * 整个拾取流程直接 {@code return}。于是有两个好处：
     *
     * <ul>
     * <li><b>背包满了也照样入库。</b>走「先捡后搬」的话，背包满 → 原版捡不起来 →
     * 连事件都不会有，而这个功能要的恰恰是「我背包里什么都不想留」。</li>
     * <li>物品压根不会在背包里闪一下。</li>
     * </ul>
     *
     * <p>
     * 注意这个事件只在服务端触发（{@code onCollideWithPlayer} 开头就有
     * {@code !worldObj.isRemote} 判断），所以这里不需要再分端。
     */
    @SubscribeEvent
    public void onItemPickup(EntityItemPickupEvent event) {
        EntityPlayer player = event.entityPlayer;
        if (player == null || !AutoStore.isEnabled(player)) return;

        EntityItem entityItem = event.item;
        if (entityItem == null || entityItem.isDead) return;

        ItemStack stack = entityItem.getEntityItem();
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return;

        ItemKey key = ItemKey.of(stack);
        if (key == null) return;

        long stored = SharedStorageManager.getStorage()
            .insertItem(key, stack.stackSize);
        if (stored <= 0L) return;

        if (stored >= stack.stackSize) {
            // 整叠都进仓库了：让掉落物消失，并取消原版拾取。
            // setDead 之后 onCollideWithPlayer 里那个遍历玩家的循环如果还有下一个玩家，
            // 会撞上上面那个 isDead 判断 —— 否则同一份掉落物会被两个玩家各存一次。
            entityItem.setDead();
            event.setCanceled(true);
        } else {
            // 存储顶到 long 上限了（理论上不可能）：只收下能收的部分，
            // 剩下的减掉数量后交给原版继续捡，不凭空吃掉
            stack.stackSize -= (int) stored;
        }

        SharedStorageManager.broadcastItemChange(key);
    }

    /**
     * 小游戏助手的「自然发现」：任何区块被加载时顺带验证一次 lootgames 候选点
     * （见 {@code LootassistManager#onChunkLoad}）。
     *
     * <p>
     * {@code ChunkEvent.Load} 两端都会触发，方法内部第一件事就是按 {@code isRemote}
     * 分流，客户端直接返回。验证的第一步是纯数学的候选判定（一次取模哈希），
     * 不是候选区块的开销只有这点判断。
     */
    @SubscribeEvent
    public void onChunkLoad(net.minecraftforge.event.world.ChunkEvent.Load event) {
        com.futa_gtnh.lootassist.LootassistManager.onChunkLoad(event.getChunk());
    }

    /**
     * 玩家破坏方块 —— 寻物魔杖「自动追下一处」的扳机（见
     * {@link com.futa_gtnh.locator.LocatorManager#noteBlockBroken}）。
     *
     * <p>
     * 为什么用这个事件而不是「每隔几 tick 看看目标还在不在」：那个巡检只能回答
     * 「方块没了」，回答不了「是谁弄没的」。而自动传送这种事，只有玩家<b>自己</b>
     * 挖掉追踪的那一格才该发生 —— 别人挖了、爆炸炸了都只该让光束换个目标。
     *
     * <p>
     * 事件在方块真正消失<b>之前</b>触发（而且可被取消），所以这里只记一笔账，
     * 真正的重搜留给下一 tick 的巡检去做。这里也不做任何过滤以外的判断：
     * 配置、是否戴着魔杖、挖的是不是当前追踪那一格，全在
     * {@code LocatorManager} 里判断。
     */
    @SubscribeEvent
    public void onBlockBreak(net.minecraftforge.event.world.BlockEvent.BreakEvent event) {
        if (event.getPlayer() == null) return;
        if (!(event.getPlayer() instanceof net.minecraft.entity.player.EntityPlayerMP)) return;

        com.futa_gtnh.locator.LocatorManager
            .noteBlockBroken((net.minecraft.entity.player.EntityPlayerMP) event.getPlayer(), event.x, event.y, event.z);
    }
}
