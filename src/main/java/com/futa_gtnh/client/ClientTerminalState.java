package com.futa_gtnh.client;

import com.futa_gtnh.shared.FluidKey;

/**
 * 客户端记住的一点点界面状态。
 *
 * <p>
 * 这里的东西都<b>只为了画界面</b>，不参与任何判定 —— 权威值全在服务端。
 * 两个字段都是「每个玩家各自一份」，所以没有跟着全服共用的那份存储快照走，
 * 而是由单独的小包在开界面时下发、在状态变化时更新。
 */
public final class ClientTerminalState {

    private ClientTerminalState() {}

    /** 当前这个方块终端在往外输出哪种流体（每个终端各自的状态）。 */
    private static FluidKey outputFluid;

    /** 「拾取自动入库」开关（每个玩家各自的状态）。 */
    private static boolean autoStore;

    public static FluidKey getOutputFluid() {
        return outputFluid;
    }

    public static void setOutputFluid(FluidKey key) {
        outputFluid = key;
    }

    public static boolean isAutoStore() {
        return autoStore;
    }

    public static void setAutoStore(boolean enabled) {
        autoStore = enabled;
    }

    public static void clear() {
        outputFluid = null;
        autoStore = false;
    }
}
