package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;

import org.lwjgl.input.Keyboard;

import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketOpenGui;

import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

/**
 * 「随时随地打开共享背包」的按键绑定。
 *
 * <p>
 * 按键只是<b>请求</b>：客户端发一个包给服务端，由服务端决定开不开
 * （受 {@code allowRemoteAccess} 配置控制）并真正调 {@code openGui}。
 * 客户端自己直接 {@code displayGuiScreen} 是不行的 —— 那样服务端不会建容器，
 * 界面里什么东西都动不了。
 *
 * <p>
 * 注意事件注册在 {@code FMLCommonHandler.instance().bus()} 上，而不是
 * {@code MinecraftForge.EVENT_BUS}。1.7.10 里 FML 自己的
 * {@code InputEvent} / {@code TickEvent} / {@code PlayerEvent} 走的是前者的
 * 独立 EventBus，注册错地方会<b>静默失效</b>（不报错，但永远不触发）。
 */
public final class KeyHandler {

    private KeyHandler() {}

    public static final String KEY_CATEGORY = "key.categories.futa_gtnh";
    public static final String KEY_OPEN_TERMINAL = "key.futa_gtnh.shared_terminal";
    public static final String KEY_RTS_TOGGLE = "key.futa_gtnh.rts_toggle";

    private static KeyBinding openTerminal;
    private static KeyBinding rtsToggle;

    public static void register() {
        openTerminal = new KeyBinding(KEY_OPEN_TERMINAL, Keyboard.KEY_B, KEY_CATEGORY);
        ClientRegistry.registerKeyBinding(openTerminal);
        rtsToggle = new KeyBinding(KEY_RTS_TOGGLE, Keyboard.KEY_G, KEY_CATEGORY);
        ClientRegistry.registerKeyBinding(rtsToggle);
        FMLCommonHandler.instance()
            .bus()
            .register(new Listener());
    }

    /**
     * 俯瞰模式开关键的键码。俯瞰 HUD 开着时 KeyBinding 不会再触发
     * （键盘事件全被 GuiScreen 截走），HUD 需要拿键码自己比对 keyTyped，
     * 这样「界面里再按一次 G 退出」和键位设置里改的键始终是同一个。
     */
    public static int getRtsToggleKeyCode() {
        return rtsToggle == null ? Keyboard.KEY_G : rtsToggle.getKeyCode();
    }

    public static final class Listener {

        @SubscribeEvent
        public void onKeyInput(InputEvent.KeyInputEvent event) {
            Minecraft minecraft = Minecraft.getMinecraft();
            // 已经开着别的界面（聊天、背包、容器）时不要抢按键
            if (minecraft.thePlayer == null || minecraft.currentScreen != null) return;

            if (openTerminal != null && openTerminal.isPressed()) {
                NetworkHandler.INSTANCE.sendToServer(new PacketOpenGui());
            }

            // 俯瞰 HUD 开着时 G 键由 HUD 自己处理（keyTyped），这里只管「没开界面」的进入
            if (rtsToggle != null && rtsToggle.isPressed()) {
                com.futa_gtnh.rts.client.RtsClientState.enter();
            }
        }
    }
}
