package com.futa_gtnh.common;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.exchange.StorageActionHandler;
import com.futa_gtnh.shared.SharedStorageManager;

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
    }

    /** 玩家下线时清掉他那份操作频率计数，避免 UUID 表越积越大。 */
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            StorageActionHandler.forget((EntityPlayerMP) event.player);
        }
    }
}
