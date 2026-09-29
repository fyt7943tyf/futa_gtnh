package com.futa_gtnh.client;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Container;

import org.lwjgl.input.Keyboard;

/**
 * 本模组所有 {@code GuiContainer} 类界面的公共基类。
 *
 * <p>
 * 以前每个界面直接继承原版 {@code GuiContainer}，公共行为只能靠复制粘贴；
 * 现在收拢到这里，新的容器类界面一律继承本类，NEI 联动（通过
 * {@link NeiAwareGui} 的默认实现）和输入兜底自动就有，不用再逐个适配：
 *
 * <ul>
 * <li>{@link NeiAwareGui}：统一 NEI 面板可见性策略（默认读
 * {@code Config#terminalNeiPanel}）与遮罩声明口子；</li>
 * <li>IME 兜底：旧输入层（LWJGL2 + InputFix 一类）的「只有字符、没有按键」
 * 事件转发，见下。</li>
 * </ul>
 */
public abstract class FutaGuiContainer extends GuiContainer implements NeiAwareGui {

    protected FutaGuiContainer(Container inventorySlotsIn) {
        super(inventorySlotsIn);
    }

    /**
     * 旧输入层（LWJGL2 + InputFix 一类）的兜底：把「只有字符、没有按键」的事件也转给
     * {@link #keyTyped}。原版 {@code GuiScreen#handleKeyboardInput()} 只在
     * {@code Keyboard.getEventKeyState()} 为真时才转发字符，而那些辅助层送来的正是
     * keyState 为假的事件 —— 汉字就是这么没的。
     *
     * <p>
     * <b>lwjgl3ify 环境下不需要也不走这条路</b>（见 {@link ImeCompat}）：它把输入法
     * 提交的文字镜像成 keyState 为真、key 为 0 的事件塞进传统队列，原版路径自己就能
     * 收到。这种情况下这段兜底必须闭嘴，否则同一批字符会被送进去两遍。
     */
    @Override
    public void handleKeyboardInput() {
        if (!ImeCompat.hasNativeIme()) {
            char injected = Keyboard.getEventCharacter();
            if (!Keyboard.getEventKeyState() && injected > 255) {
                this.keyTyped(injected, 0);
            }
        }
        super.handleKeyboardInput();
    }
}
