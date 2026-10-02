package com.futa_gtnh.client;

/**
 * 客户端记住的一点点界面状态。
 *
 * <p>
 * 这里的东西都<b>只为了画界面</b>，不参与任何判定 —— 权威值全在服务端。
 * 自动入库开关是「每个玩家各自一份」，所以没有跟着全服共用的那份存储快照走，
 * 而是由单独的小包在开界面时下发、在状态变化时更新。
 */
public final class ClientTerminalState {

    private ClientTerminalState() {}

    /** 「拾取自动入库」开关（每个玩家各自的状态）。 */
    private static boolean autoStore;

    public static boolean isAutoStore() {
        return autoStore;
    }

    public static void setAutoStore(boolean enabled) {
        autoStore = enabled;
    }

    public static void clear() {
        autoStore = false;
    }
}
