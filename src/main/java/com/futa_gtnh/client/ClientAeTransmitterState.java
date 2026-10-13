package com.futa_gtnh.client;

import com.futa_gtnh.network.PacketAeTransmitterSync;

/**
 * 「AE 无线发信端」界面的客户端镜像：只存「怎么画」。
 * 服务端的 {@link PacketAeTransmitterSync} 是唯一写入口。
 */
public final class ClientAeTransmitterState {

    private ClientAeTransmitterState() {}

    private static volatile PacketAeTransmitterSync latest;

    public static void apply(PacketAeTransmitterSync snapshot) {
        latest = snapshot;
    }

    /** 当前界面应展示的快照（可能为 null —— 还没收到）。 */
    public static PacketAeTransmitterSync snapshot() {
        return latest;
    }

    public static String name() {
        PacketAeTransmitterSync snapshot = latest;
        return snapshot == null ? "" : snapshot.getName();
    }

    public static String stateKey(PacketAeTransmitterSync snapshot) {
        return "futa_gtnh.aewireless.state." + (snapshot == null ? "offline"
            : snapshot.getState()
                .name()
                .toLowerCase());
    }
}
