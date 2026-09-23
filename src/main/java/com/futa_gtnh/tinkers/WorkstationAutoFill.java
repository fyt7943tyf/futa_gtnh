package com.futa_gtnh.tinkers;

import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.server.MinecraftServer;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.exchange.DeltaRecorder;
import com.futa_gtnh.network.PacketStorageDelta;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

import tconstruct.smeltery.inventory.SmelteryContainer;
import tconstruct.tools.inventory.CraftingStationContainer;
import tconstruct.tools.inventory.PartCrafterContainer;
import tconstruct.tools.inventory.ToolStationContainer;

/**
 * 匠魂工作站的「自动补料」心跳。服务端每 tick 跑一次，只处理<b>当前开着工作站界面</b>
 * 的玩家。
 *
 * <p>
 * <b>为什么是「服务端轮询 + 直接写容器里的库存」，而不是去 hook 匠魂的点击。</b>
 * 匠魂这几个工作站有个很好用的性质：它们把「往槽里放东西」当成合成触发器 ——
 * {@code ToolStationLogic.setInventorySlotContents} 里会直接调 {@code buildTool}，
 * {@code PartBuilderLogic} 里会调 {@code buildTopPart/buildBottomPart}。也就是说
 * <b>我们把缺的材料写进它的库存，它自己就会把东西做出来</b>，不需要去改它的代码、
 * 也不需要模拟点击（模拟点击那套在网络时序上是不牢靠的，见 {@code CraftFiller} 的说明）。
 *
 * <p>
 * 代价是「意图」只能从<b>玩家已经摆进去的东西</b>里读：工作站里一个部件都没有时我们
 * 不知道他要做什么工具，就不动（宁可不动也不要猜错 —— 匠魂的部件是讲材质的，
 * 猜错会白白消耗掉好材料）。合成站和冶炼炉同理：只补回你自己摆过的那种东西和数量。
 */
final class WorkstationAutoFill {

    /** 匠魂那边出意外时只报一次，免得每 tick 刷屏。 */
    private static boolean failureReported;

    private WorkstationAutoFill() {}

    static void reportFailure(Throwable t) {
        if (failureReported) return;
        failureReported = true;
        FutaGtnhMod.LOG.warn("匠魂工作站自动补料已停用（一次性报错）", t);
    }

    static void tick() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        if (!SharedStorageManager.isLoaded()) return;

        SharedStorage storage = SharedStorageManager.getStorage();
        if (storage == null) return;

        @SuppressWarnings("unchecked")
        List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
        if (players == null || players.isEmpty()) return;

        // 和 StorageActionHandler 一样：这一 tick 里对存储的改动先记在 delta 上，
        // 最后一次性广播增量，避免每个格子一次广播
        PacketStorageDelta delta = new PacketStorageDelta();
        DeltaRecorder recorder = new DeltaRecorder(storage, delta);

        for (EntityPlayerMP player : players) {
            Container open = player.openContainer;
            if (open == null) continue;

            // instanceof 顺序无所谓（互不继承），这里只是分派
            if (open instanceof ToolStationContainer) {
                ToolStationFill.tick(player, (ToolStationContainer) open, storage, recorder);
            } else if (open instanceof PartCrafterContainer) {
                PartBuilderFill.tick(player, (PartCrafterContainer) open, storage, recorder);
            } else if (open instanceof CraftingStationContainer) {
                // 合成站：记住摆好的 3×3，被合成消耗掉的格子补回原样
                KeptLayoutFill.tickGrid(player, open, (CraftingStationContainer) open, storage, recorder);
            } else if (open instanceof SmelteryContainer) {
                // 冶炼炉：记住放进去的待熔物，熔掉多少补回多少（界面关掉就停）
                KeptLayoutFill.tickSmeltery(player, open, (SmelteryContainer) open, storage, recorder);
            }
        }

        if (recorder.isActive()) {
            SharedStorageManager.broadcastDelta(delta);
        }
    }
}
