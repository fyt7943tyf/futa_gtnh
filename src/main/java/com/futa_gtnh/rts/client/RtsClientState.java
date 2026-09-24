package com.futa_gtnh.rts.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;

import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketRtsToggle;

/**
 * 俯瞰模式的客户端状态机（进入 / 退出 / 守卫）。
 *
 * <p>
 * <b>乐观进入</b>：按键后先把相机切过去再等服务端答复，省一个网络来回的
 * 手感延迟。代价是被拒绝时（配置关闭）要把界面退回去 —— 由
 * {@code PacketRtsToggleAck} → {@code ClientProxy#onRtsToggleRejected} →
 * {@link #onServerRejected} 这条链完成。
 *
 * <p>
 * <b>界面的层级关系</b>：俯瞰 HUD（{@link GuiRtsOverlay}）会被别的界面顶掉
 * （最典型的是远程互动打开的箱子界面）。被顶掉时<b>俯瞰会话继续</b> ——
 * 相机还在原地，等那个界面关掉后由 {@link #tickGuard} 把 HUD 重新打开。
 * 真正的退出只有两条路：玩家主动按 G/ESC，或守卫强制退出（死亡、世界失效）。
 */
public final class RtsClientState {

    private RtsClientState() {}

    private static boolean active;
    private static int savedThirdPersonView;
    private static boolean savedHideGui;

    public static boolean isActive() {
        return active;
    }

    /** 进入俯瞰模式（G 键 / {@code KeyHandler} 调用）。 */
    public static void enter() {
        Minecraft mc = Minecraft.getMinecraft();
        if (active || mc.thePlayer == null || mc.theWorld == null) return;

        active = true;

        // 先向服务端登记会话（动作包的门控前提），再切本地视角
        try {
            NetworkHandler.INSTANCE.sendToServer(new PacketRtsToggle(true));
        } catch (Throwable t) {
            // 连接已断时发不出去：本地照常进，等服务端自然超时/登出清理
        }

        RtsCameraController.enter();
        if (RtsCameraController.getCameraEntity() == null) {
            active = false;
            return;
        }

        // 强制第一人称：相机实体是「我们自己的眼睛」，第三人称会把镜头绕到
        // 相机实体背后再拉远，没有意义。原值存起来，退出时还回去。
        savedThirdPersonView = mc.gameSettings.thirdPersonView;
        savedHideGui = mc.gameSettings.hideGUI;
        mc.gameSettings.thirdPersonView = 0;
        mc.renderViewEntity = RtsCameraController.getCameraEntity();

        mc.displayGuiScreen(new GuiRtsOverlay());
    }

    /** 退出俯瞰模式（玩家主动、守卫强制、或服务端拒绝）。幂等。 */
    public static void exit() {
        Minecraft mc = Minecraft.getMinecraft();
        if (!active) return;
        active = false;

        try {
            NetworkHandler.INSTANCE.sendToServer(new PacketRtsToggle(false));
        } catch (Throwable t) {
            // 同上：连接断了就只做本地恢复
        }

        RtsCameraController.exit();
        if (mc.renderViewEntity instanceof RtsCameraEntity && mc.thePlayer != null) {
            mc.renderViewEntity = mc.thePlayer;
        }
        mc.gameSettings.thirdPersonView = savedThirdPersonView;
        mc.gameSettings.hideGUI = savedHideGui;

        if (mc.currentScreen instanceof GuiRtsOverlay) {
            mc.displayGuiScreen(null);
        }
    }

    /**
     * 每客户端 tick 的守卫（{@code RtsClientTickHandler} 调）：
     * 死亡、世界失效、相机实体跟丢（换维度时原版会重建玩家实体）都强制退出。
     * 同时负责把被别的界面顶掉的 HUD 重新打开。
     */
    public static void tickGuard() {
        if (!active) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            exit();
            return;
        }
        if (mc.thePlayer.isDead) {
            exit();
            return;
        }
        RtsCameraEntity camera = RtsCameraController.getCameraEntity();
        if (camera == null || camera.worldObj != mc.theWorld || mc.renderViewEntity != camera) {
            exit();
            return;
        }
        // 会话还在但没有任何界面（典型：远程打开的箱子界面刚被关掉）→ 回到俯瞰 HUD
        if (mc.currentScreen == null) {
            mc.displayGuiScreen(new GuiRtsOverlay());
        }
    }

    /**
     * 服务端拒绝开启（{@code ClientProxy#onRtsToggleRejected} 转发）：
     * 退掉乐观进入的界面，并把拒绝原因说给玩家听。
     *
     * @param reasonKey 语言键，null 时只退出不提示
     */
    public static void onServerRejected(String reasonKey) {
        exit();
        Minecraft mc = Minecraft.getMinecraft();
        if (reasonKey != null && mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentTranslation(reasonKey));
        }
    }
}
