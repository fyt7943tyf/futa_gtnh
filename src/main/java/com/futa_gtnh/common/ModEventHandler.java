package com.futa_gtnh.common;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.exchange.StorageActionHandler;
import com.futa_gtnh.item.ItemSwiftStep;
import com.futa_gtnh.locator.LocatorManager;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.tinkers.TinkersAutoFill;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 服务端生命周期挂钩。
 *
 * <p>
 * 注册在 {@code FMLCommonHandler.instance().bus()} 上：1.7.10 里 FML 自己的
 * {@code TickEvent} / {@code PlayerEvent} 不在 {@code MinecraftForge.EVENT_BUS} 上，
 * 注册错了不会报错，只会永远收不到事件。
 *
 * <p>
 * 服务端的启动/停止不用这里的事件，走 {@code @Mod.EventHandler}
 * （{@code FMLServerStartedEvent} / {@code FMLServerStoppingEvent}）更直接。
 */
public class ModEventHandler {

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new ModEventHandler());
    }

    /** 定期自动保存。默认 5 分钟一次，只在真的有改动时才写盘。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        SharedStorageManager.onServerTick();
        // 推进寻物扫描。没有任务时它第一件事就是返回，开销是一次 isEmpty()
        LocatorManager.onServerTick();
        // 匠魂工作站的自动补料（没装匠魂、或配置关掉时，第一步就返回）
        TinkersAutoFill.onServerTick();
        // 俯瞰模式的单块操作限频计数清零（同样是空表时一次 isEmpty 的开销）
        com.futa_gtnh.rts.server.RtsActionGuard.tick();
        // 推进俯瞰批量任务（同上，空表即返回）
        com.futa_gtnh.rts.server.RtsBatchEngine.onServerTick();
        // 撤销/重做的方块写回也按预算推进（同上）
        com.futa_gtnh.rts.server.RtsHistoryManager.onServerTick();
        // 小游戏助手的渐进搜索（没装 lootgames / 没有任务时一次布尔判断的开销）
        com.futa_gtnh.lootassist.LootassistManager.onServerTick();
    }

    /** 玩家下线时清掉他那份操作频率计数，避免 UUID 表越积越大。 */
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            EntityPlayerMP player = (EntityPlayerMP) event.player;
            StorageActionHandler.forget(player);
            // 寻物任务和结果也一起清掉：任务里存着 World 引用，
            // 玩家走了还留着的话，那个 World 就没法被回收了
            LocatorManager.forget(player.getUniqueID());
            // 俯瞰会话的登记也一样要清
            com.futa_gtnh.rts.RtsSessionManager.forget(player.getUniqueID());
            com.futa_gtnh.rts.server.RtsActionGuard.forget(player.getUniqueID());
            com.futa_gtnh.rts.server.RtsRemoteGuiRegistry.forget(player.getUniqueID());
            // 批量任务持有玩家引用（进而持有 World），不清就泄漏
            com.futa_gtnh.rts.server.RtsBatchEngine.forget(player.getUniqueID());
            com.futa_gtnh.rts.server.RtsHistoryManager.forget(player.getUniqueID());
            // 助手的 viewer 表和搜索进度接收者
            com.futa_gtnh.lootassist.LootassistManager.forget(player.getUniqueID());
        }
    }

    /**
     * 每 tick 把迅步的移动速度修饰符调整成该有的样子。
     *
     * <p>
     * 这里<b>刻意不按端分流</b>：这个事件两端都会触发，而方法本身是幂等的
     * （倍率没变就什么都不做），所以服务端负责权威、客户端负责即时生效，
     * 一份代码全包了。而且客户端也需要它 —— 属性的同步毕竟要等一个来回，
     * 不本地也挂一份的话，戴上迅步要过一小会儿才有感觉。
     *
     * <p>
     * 代价是每 tick 每个玩家要遍历一遍饰品栏（几格）加一次属性查表，
     * 可以忽略。
     */
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ItemSwiftStep.applyWalkSpeedModifier(event.player);
        // 空中前进速度也要跟着放大，否则「走着 5 倍、一跳起来掉回原版」。
        //
        // 这里必须是 END 阶段：EntityPlayer.onLivingUpdate 先在第 612 行做移动、
        // 第 620 行才把 jumpMovementFactor 从 speedInAir 重置回来，而 END 事件在那之后。
        // 放到 START 反而会被第 620 行盖掉。
        ItemSwiftStep.applyAirSpeedModifier(event.player);
    }
}
