package com.futa_gtnh.ae2;

/**
 * AE2 联动的门卫类 —— <b>不引用任何 AE 类型</b>。
 *
 * <p>
 * 模式照抄 Tinkers/Baubles 的既有做法：探测守卫和实际实现分开，
 * 这样没装 AE2 的环境加载这个类不会有任何问题，真正的实现
 * （{@code Ae2Integration} 往后的整包）只在确认 AE2 在场之后才被碰到。
 *
 * <p>
 * 这里有三个静态钩子（tick / 停服），是给公共代码（{@code ModEventHandler}、
 * {@code FutaGtnhMod}）调用的 —— 那两处不能直接引用 AE 包里的类型。
 * 钩子由 {@code Ae2Integration.install()} 注入，没装 AE2 时恒为 null，
 * 调用开销一次判空。
 */
public final class Ae2Compat {

    private Ae2Compat() {}

    private static Boolean available;
    private static Runnable tickHook;
    private static Runnable serverStopHook;

    /** @return AE2（appliedenergistics2）是否在场；结果缓存 */
    public static boolean isAvailable() {
        if (available == null) {
            available = cpw.mods.fml.common.Loader.isModLoaded("appliedenergistics2");
        }
        return available;
    }

    /** 只允许 {@code Ae2Integration.install()} 调用（它本身已在 AE2 在场守卫之内）。 */
    static void installHooks(Runnable tick, Runnable serverStop) {
        tickHook = tick;
        serverStopHook = serverStop;
    }

    /** 服务端 tick 末尾调用（ModEventHandler 转发）。没装 AE2 时是空操作。 */
    public static void onServerTickEnd() {
        Runnable hook = tickHook;
        if (hook != null) hook.run();
    }

    /** 服务端停止时调用（FutaGtnhMod.serverStopping 转发）。没装 AE2 时是空操作。 */
    public static void onServerStopping() {
        Runnable hook = serverStopHook;
        if (hook != null) hook.run();
    }
}
