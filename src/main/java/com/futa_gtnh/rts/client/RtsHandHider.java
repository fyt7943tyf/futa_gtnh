package com.futa_gtnh.rts.client;

import net.minecraftforge.client.event.RenderHandEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/**
 * 俯瞰模式下隐藏第一人称手臂。
 *
 * <p>
 * 1.7.10 的 {@code ItemRenderer.renderItemInFirstPerson} 全程使用
 * {@code mc.thePlayer}（不看 renderViewEntity），所以换成相机实体后手臂
 * 照样被画出来 —— 上机测试确认。隐藏手段是 GTNH 的 Forge fork 反向移植的
 * {@link RenderHandEvent}（原版 1.7.10 Forge 没有，GTNH 加的；Et-Futurum
 * Requiem 的旁观模式就是用它隐藏手部的）：取消事件时
 * {@code ForgeHooksClient.renderFirstPersonHand} 返回 true，整个 renderHand
 * （含深度清屏）都被跳过。
 *
 * <p>
 * 注册在 {@code MinecraftForge.EVENT_BUS}（渲染事件所在总线），
 * 见 {@code ClientProxy#preInit}。
 */
public class RtsHandHider {

    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(new RtsHandHider());
    }

    @SubscribeEvent
    public void onRenderHand(RenderHandEvent event) {
        if (RtsClientState.isActive()) {
            event.setCanceled(true);
        }
    }
}
