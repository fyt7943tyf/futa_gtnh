package com.futa_gtnh.client;

/**
 * 输入法（IME）能力探测。
 *
 * <p>
 * GTNH 的目标运行时带 <b>lwjgl3ify</b>（LWJGL3 / SDL / 新 Java），它给原版
 * {@code GuiTextField} 打了 mixin：搜索框一聚焦就 {@code SDL_StartTextInput}，
 * 系统输入法由此被激活；提交的文字先缓冲在
 * {@code me.eigenraven.lwjgl3ify.client.TextFieldHandler.textBuffer} 里，
 * 同时把每个字符镜像成「keyState 为真、key 为 0」的事件塞进传统键盘队列 ——
 * 原版 {@code GuiScreen#handleKeyboardInput} 本来就会转发 keyState 为真的事件，
 * 所以文字能顺着原版路径走进 {@code textboxKeyTyped}，再被 lwjgl3ify 的重定向
 * 换成整段缓冲写入。<b>也就是说：对原版 GuiTextField 来说，中文直打是免费的。</b>
 *
 * <p>
 * 但这段机制是较新的 SDL 版 lwjgl3ify 才有的；更老的版本（以及不带 lwjgl3ify 的
 * 纯 LWJGL2 环境）没有它，汉字要靠 InputFix 一类辅助层以「keyState 为假、
 * 只有字符」的事件送进来 —— 那种事件会被原版直接丢掉，得自己补一刀
 * （见 {@code GuiSharedTerminal#handleKeyboardInput} 里的兜底分支）。
 *
 * <p>
 * 两条路径互斥：lwjgl3ify 在场时再补那一刀会把同一批字符送两遍。
 * 所以用「探测 TextFieldHandler 这个类在不在」来分辨环境 —— 只按名字查类、
 * 不加载不初始化，对两个环境都没有副作用。
 */
public final class ImeCompat {

    private static final boolean NATIVE_IME = probe();

    private ImeCompat() {}

    private static boolean probe() {
        try {
            Class.forName("me.eigenraven.lwjgl3ify.client.TextFieldHandler", false, ImeCompat.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            // 没有 lwjgl3ify（纯 LWJGL2 dev 环境）或版本太老没有 IME 支持
            return false;
        }
    }

    /** @return 输入法文字是否由 lwjgl3ify 自动送进原版路径（此时不要再做手动转发） */
    public static boolean hasNativeIme() {
        return NATIVE_IME;
    }
}
