package com.futa_gtnh.rts.client;

import net.minecraft.client.Minecraft;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * 俯瞰模式的客户端 tick 驱动（注册在 {@code FMLCommonHandler} 总线上，
 * 1.7.10 里 TickEvent 不在 Forge 的 EVENT_BUS 上，挂错会静默失效）。
 *
 * <p>
 * 三件事，顺序有意：
 * <ol>
 * <li>{@code RtsClientState.tickGuard}：死亡/换世界强制退出，以及把被别的
 * 界面顶掉的 HUD 重新打开 —— 必须最先跑，退出后本 tick 就不该再动相机；</li>
 * <li>玩家移动输入兜底清零：打开 GuiScreen 时原版会
 * {@code KeyBinding.unPressAllKeys()}，移动输入按理已经归零；但保险起见
 * 再显式清一次（有的 mod 会在 tick 里写 movementInput）。</li>
 * <li>{@code RtsCameraController.tick}：消费输入、推进相机。</li>
 * </ol>
 */
public class RtsClientTickHandler {

    public static void register() {
        FMLCommonHandler.instance()
            .bus()
            .register(new RtsClientTickHandler());
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            if (RtsClientState.isActive()) RtsClientState.exit();
            return;
        }
        if (!RtsClientState.isActive()) return;

        RtsClientState.tickGuard();
        if (!RtsClientState.isActive()) return;

        // 兜底清零：俯瞰模式下玩家本体不接受移动指令（物理照常，被推/坠落都算）
        mc.thePlayer.movementInput.moveForward = 0.0F;
        mc.thePlayer.movementInput.moveStrafe = 0.0F;
        mc.thePlayer.movementInput.jump = false;
        mc.thePlayer.movementInput.sneak = false;

        RtsCameraController.tick();
    }
}
