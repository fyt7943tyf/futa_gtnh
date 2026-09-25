package com.futa_gtnh.network;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

/**
 * 本模组的网络通道。
 *
 * <p>
 * 1.7.10 的 {@code SimpleNetworkWrapper} 在 netty 线程上直接调用 {@code onMessage}，
 * 不像 1.8+ 会自动切回主线程。好在 1.7.10 原版的 {@code NetworkManager} 会把收到的包
 * 放进队列、在主线程的 tick 里处理，所以服务端和客户端的 handler <b>实际都在主线程执行</b>。
 * 这一条是 {@code SharedStorage} 用 {@code synchronized} 而不是锁无关结构的前提。
 */
public final class NetworkHandler {

    private NetworkHandler() {}

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(FutaGtnhMod.MODID);

    /** 每个分片的最大字节数。留出余量，避免超过 1.7.10 的自定义包长度上限。 */
    public static final int CHUNK_SIZE = 28000;

    public static void init() {
        // 服务端 -> 客户端
        INSTANCE.registerMessage(PacketStorageSync.Handler.class, PacketStorageSync.class, 0, Side.CLIENT);
        INSTANCE.registerMessage(PacketStorageDelta.Handler.class, PacketStorageDelta.class, 1, Side.CLIENT);
        INSTANCE.registerMessage(PacketTerminalFluid.Handler.class, PacketTerminalFluid.class, 4, Side.CLIENT);
        INSTANCE.registerMessage(PacketAutoStoreSync.Handler.class, PacketAutoStoreSync.class, 6, Side.CLIENT);
        INSTANCE.registerMessage(PacketLocatorResult.Handler.class, PacketLocatorResult.class, 8, Side.CLIENT);

        // 客户端 -> 服务端
        INSTANCE.registerMessage(PacketStorageAction.Handler.class, PacketStorageAction.class, 2, Side.SERVER);
        INSTANCE.registerMessage(PacketOpenGui.Handler.class, PacketOpenGui.class, 3, Side.SERVER);
        INSTANCE.registerMessage(PacketAutoStore.Handler.class, PacketAutoStore.class, 5, Side.SERVER);
        INSTANCE.registerMessage(PacketSetSwiftStep.Handler.class, PacketSetSwiftStep.class, 7, Side.SERVER);
        INSTANCE.registerMessage(PacketLocatorAction.Handler.class, PacketLocatorAction.class, 9, Side.SERVER);
        // 匠魂合成站旁边那块共享存储区：客户端算好「这一页显示哪些东西」推给服务端
        INSTANCE.registerMessage(PacketStationView.Handler.class, PacketStationView.class, 10, Side.SERVER);
        // 俯瞰建筑：会话开关（C2S）与开启答复（S2C）
        INSTANCE.registerMessage(PacketRtsToggle.Handler.class, PacketRtsToggle.class, 11, Side.SERVER);
        INSTANCE.registerMessage(PacketRtsToggleAck.Handler.class, PacketRtsToggleAck.class, 12, Side.CLIENT);
        INSTANCE.registerMessage(PacketRtsInteract.Handler.class, PacketRtsInteract.class, 13, Side.SERVER);
        INSTANCE.registerMessage(PacketRtsBreak.Handler.class, PacketRtsBreak.class, 14, Side.SERVER);
        INSTANCE.registerMessage(PacketRtsQuickBuild.Handler.class, PacketRtsQuickBuild.class, 15, Side.SERVER);
        INSTANCE.registerMessage(PacketRtsUndoRedo.Handler.class, PacketRtsUndoRedo.class, 16, Side.SERVER);
        INSTANCE.registerMessage(PacketRtsRotate.Handler.class, PacketRtsRotate.class, 17, Side.SERVER);
        // 小游戏助手（lootgames 联动）：操作 / 快照 / 增量 / 进度
        INSTANCE.registerMessage(PacketLootassistAction.Handler.class, PacketLootassistAction.class, 18, Side.SERVER);
        INSTANCE.registerMessage(PacketLootassistSync.Handler.class, PacketLootassistSync.class, 19, Side.CLIENT);
        INSTANCE.registerMessage(PacketLootassistDelta.Handler.class, PacketLootassistDelta.class, 20, Side.CLIENT);
        INSTANCE
            .registerMessage(PacketLootassistProgress.Handler.class, PacketLootassistProgress.class, 21, Side.CLIENT);
    }
}
