package com.futa_gtnh.shared;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.api.SharedStorageReadStatus;
import com.futa_gtnh.api.SharedStorageSnapshot;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageDelta;
import com.futa_gtnh.network.PacketStorageSync;
import com.futa_gtnh.station.StationViews;
import com.futa_gtnh.storagebus.StorageBusRegistry;
import com.futa_gtnh.tinkers.TinkersAutoFill;

/**
 * 共享存储的服务端生命周期与广播中枢。
 *
 * <p>
 * 这是<b>单例、每个服务器实例一份</b>的：所有维度、所有玩家共用同一个池子。
 * 数据文件跟着主世界（维度 0）的存档目录走，所以在下界/末地改的东西和主世界是同一份。
 */
public final class SharedStorageManager {

    private SharedStorageManager() {}

    private static SharedStorage storage = new SharedStorage();
    private static File worldDirectory;
    private static boolean loaded;
    private static boolean savingBlocked;

    /** Published on startup; read API calls must use this server thread. */
    private static volatile Thread readApiThread;
    private static String readApiGeneration = UUID.randomUUID()
        .toString();

    private static NativeMeStorageData nativeData;
    private static WorldServer nativeWorld;
    private static int transferCounter;

    /**
     * 压缩后的全量快照缓存。
     *
     * <p>
     * 共享存储是全服共用的，所以快照字节对所有玩家都一样 —— 只要内容没变，
     * 压一次就能发给所有人。用 {@code cachedRevision} 判断缓存是否还有效，
     * 而不是每次改动都作废（玩家可能一分钟开十次界面，中间只有几次改动）。
     */
    private static byte[] cachedPayload;
    private static int cachedRevision = -1;

    // ==================================================================
    // 生命周期
    // ==================================================================

    /** @return 当前共享存储；服务端尚未就绪时返回一个临时的空存储，不会返回 null */
    public static SharedStorage getStorage() {
        return storage;
    }

    public static File worldDirectory() {
        return worldDirectory;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static boolean hasUnsavedChanges() {
        return nativeData == null ? storage.isDirty() : nativeData.hasUnsavedChanges();
    }

    public static boolean isSavingBlocked() {
        return savingBlocked;
    }

    /** Internal implementation for SharedStorageReadApi; does not copy the warehouse. */
    public static SharedStorageReadStatus getReadApiStatus() {
        checkReadApiThread();
        return new SharedStorageReadStatus(
            loaded && (!storage.usesNetwork() || com.futa_gtnh.me.PublicMeNetwork.isOnline())
                ? SharedStorageReadStatus.State.READY
                : SharedStorageReadStatus.State.NOT_READY,
            readApiGeneration,
            loaded ? storage.getRevision() : 0);
    }

    /** Internal implementation for SharedStorageReadApi; never returns the temporary startup pool. */
    public static SharedStorageSnapshot getReadApiSnapshot() {
        checkReadApiThread();
        if (!loaded || storage.usesNetwork() && !com.futa_gtnh.me.PublicMeNetwork.isOnline()) {
            return new SharedStorageSnapshot(getReadApiStatus(), Collections.emptyList(), Collections.emptyList());
        }
        return storage.snapshotForReadApi(readApiGeneration);
    }

    private static void checkReadApiThread() {
        Thread serverThread = readApiThread;
        if (serverThread != null && Thread.currentThread() != serverThread) {
            throw new IllegalStateException("SharedStorageReadApi must be called on the server thread");
        }
    }

    /**
     * 当前线程是不是服务端主线程。
     *
     * <p>
     * 1.7.10 的 {@code MinecraftServer} 没有现成的「是否主线程」方法，而
     * {@link #readApiThread} 在 {@code onServerStarted} 里记下的就是服务端线程。
     * 还没启动服务端（无头检查 / 单测）时返回 true，避免把测试路径全挡掉。
     */
    public static boolean isServerThread() {
        Thread serverThread = readApiThread;
        return serverThread == null || Thread.currentThread() == serverThread;
    }

    /**
     * 服务端启动完成时调用。重复调用（单人模式换存档）会先把上一份存盘再重新载入。
     */
    public static void onServerStarted(MinecraftServer server) {
        if (loaded) {
            saveNow();
        }

        // 上一个世界/存档遗留的总线登记必须清掉：方块实体会在新区块加载后的
        // 第一次 tick 重新登记，旧 World 的引用不能留在静态表里。
        StorageBusRegistry.clear();

        readApiThread = Thread.currentThread();
        readApiGeneration = UUID.randomUUID()
            .toString();
        loaded = false;
        savingBlocked = false;
        nativeData = null;
        nativeWorld = null;

        File dir = resolveWorldDirectory(server);
        if (dir == null) {
            if (server != null)
                throw new IllegalStateException("Cannot start public ME storage without a persistent world directory");
            FutaGtnhMod.LOG.error("共享存储：拿不到主世界存档目录，本次以内存模式运行（不会写盘）");
            SharedStorage mem = new SharedStorage();
            mem.adoptListenersFrom(storage);
            storage = mem;
            worldDirectory = null;
            nativeData = null;
            nativeWorld = null;
            loaded = true;
            invalidateSnapshot();
            return;
        }

        // A failed load must never leave the previous pool writable at the new world's path.
        worldDirectory = null;
        WorldServer owner = server.worldServerForDimension(0);
        NativeMeStorageData data = NativeMeMigration.load(dir);
        owner.setItemData(NativeMeStorageData.NAME, data);
        nativeData = data;
        nativeWorld = owner;
        SharedStorage fresh = data.access();
        worldDirectory = dir;
        boolean networkAttached = storage.usesNetwork();
        fresh.adoptListenersFrom(storage);
        storage = fresh;
        if (networkAttached) com.futa_gtnh.ae2.PublicStorageAccess.begin();
        loaded = true;
        invalidateSnapshot();

        FutaGtnhMod.LOG.info(
            "公共 ME 原生存储已就绪：{} 种物品 / {} 种流体，元件存档 {}",
            storage.itemTypeCount(),
            storage.fluidTypeCount(),
            NativeMeMigration.dataFile(dir)
                .getPath());
    }

    /** 服务端停止时调用：保存尚未落盘的修改。 */
    public static void onServerStopping() {
        try {
            saveNow();
        } finally {
            // 清掉总线对 World / TileEntity 的引用：服务端停掉后它们不应该继续被静态表持有
            StorageBusRegistry.clear();
            nativeWorld = null;
            nativeData = null;
            loaded = false;
            readApiGeneration = UUID.randomUUID()
                .toString();
            invalidateSnapshot();
        }
    }

    /** Inventory saves follow Minecraft's WorldSavedData lifecycle; no legacy pool autosave remains. */
    public static void onServerTick() {}

    public static UUID storageId() {
        if (nativeData == null) throw new IllegalStateException("Native ME storage is not loaded");
        return nativeData.storageId();
    }

    /** Explicit save/normal shutdown flushes the same native cell world data used by normal world saves. */
    public static synchronized void saveNow() {
        if (savingBlocked || nativeData == null || nativeWorld == null) return;
        nativeWorld.mapStorage.saveAllData();
        // Hodgepodge writes MapStorage asynchronously; explicit save/shutdown must finish that queue.
        nativeWorld.getSaveHandler()
            .flush();
    }

    /**
     * 主世界（维度 0）的存档目录。所有维度共用这一份数据。
     */
    private static File resolveWorldDirectory(MinecraftServer server) {
        if (server == null) return null;
        WorldServer[] worlds = server.worldServers;
        if (worlds == null || worlds.length == 0 || worlds[0] == null) return null;
        try {
            return worlds[0].getSaveHandler()
                .getWorldDirectory();
        } catch (Throwable t) {
            FutaGtnhMod.LOG.error("共享存储：读取主世界存档目录失败", t);
            return null;
        }
    }

    // ==================================================================
    // 快照缓存
    // ==================================================================

    /** 作废全量快照缓存。内容变化时调用。 */
    public static void invalidateSnapshot() {
        cachedPayload = null;
        cachedRevision = -1;
    }

    /** @return 压缩后的全量快照；内容没变时直接复用缓存 */
    public static synchronized byte[] getOrBuildPayload() {
        int revision = storage.getRevision();
        if (cachedPayload != null && cachedRevision == revision) {
            return cachedPayload;
        }
        cachedPayload = PacketStorageSync.createPayload(storage);
        cachedRevision = revision;
        return cachedPayload;
    }

    // ==================================================================
    // 下发
    // ==================================================================

    /**
     * 给一个玩家发整份快照。
     *
     * <p>
     * 这里<b>不要</b>调 {@link #invalidateSnapshot()}：压缩缓存是按
     * {@link SharedStorage#getRevision()} 判定的，内容一改版本号就变了，
     * 缓存自然失效。反过来如果每次发快照都强制作废，
     * 缓存就永远命中不了 —— 每有一个玩家开界面就要把几千个条目重新压一遍。
     */
    public static void sendSnapshotTo(EntityPlayerMP player) {
        // 打开界面时先让存储总线扫描一次目标容器：玩家刚往箱子里放的东西
        // 不应该等到下一个刷新周期才出现在终端里。
        StorageBusRegistry.refreshAll();
        sendSnapshotToRaw(player);
    }

    private static void sendSnapshotToRaw(EntityPlayerMP player) {
        int transferId = ++transferCounter;
        PacketStorageSync.sendTo(player, transferId, getOrBuildPayload());
    }

    /**
     * @return 当前「正在看着共享存储」的所有玩家
     *
     *         <p>
     *         除了开着终端界面的玩家，还包括开着<b>匠魂合成站</b>的玩家 ——
     *         合成站旁边那块存储区显示的就是共享存储本身，别人往里放东西，
     *         开着合成站的玩家也该立刻看到（否则界面上的数量会一直停在打开界面那一刻）。
     */
    public static List<EntityPlayerMP> viewers() {
        List<EntityPlayerMP> result = new ArrayList<>();
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return result;

        // 匠魂是可选的：先探测再引用 StationViews（那里全是 tconstruct 的类型）
        boolean tinkers = TinkersAutoFill.isAvailable();

        for (Object object : server.getConfigurationManager().playerEntityList) {
            if (!(object instanceof EntityPlayerMP)) continue;
            EntityPlayerMP player = (EntityPlayerMP) object;
            if (player.openContainer instanceof ContainerSharedTerminal) {
                result.add(player);
                continue;
            }
            if (tinkers && StationViews.isStationViewer(player)) {
                result.add(player);
            }
        }
        return result;
    }

    /** 把一批增量改动发给所有正在看这个界面的玩家。 */
    public static void broadcastDelta(PacketStorageDelta delta) {
        if (delta == null || delta.isEmpty()) return;
        for (EntityPlayerMP viewer : viewers()) {
            NetworkHandler.INSTANCE.sendTo(delta, viewer);
        }
    }

    /**
     * 单个物品条目变了就广播出去。
     *
     * <p>
     * 给「不经过玩家操作」的改动用的：GT 管道往终端里灌东西，
     * 以及开着「拾取自动入库」的玩家捡东西时，正开着界面的玩家应该立刻看到数量变化。
     *
     * <p>
     * <b>先查有没有观众，再构造包。</b>自动入库是挂在拾取事件上的，
     * 挖矿时一秒能触发好几次；没人在看界面的情况下还去建 NBT、建包，
     * 纯属白白制造垃圾。
     */
    public static void broadcastItemChange(ItemKey key) {
        if (key == null) return;
        List<EntityPlayerMP> viewers = viewers();
        if (viewers.isEmpty()) return;

        PacketStorageDelta delta = new PacketStorageDelta();
        delta.addItem(key, storage.getItemAmount(key));
        for (EntityPlayerMP viewer : viewers) {
            NetworkHandler.INSTANCE.sendTo(delta, viewer);
        }
    }

    public static void broadcastFluidChange(FluidKey key) {
        if (key == null) return;
        List<EntityPlayerMP> viewers = viewers();
        if (viewers.isEmpty()) return;

        PacketStorageDelta delta = new PacketStorageDelta();
        delta.addFluid(key, storage.getFluidAmount(key));
        for (EntityPlayerMP viewer : viewers) {
            NetworkHandler.INSTANCE.sendTo(delta, viewer);
        }
    }

    /**
     * 存储总线报来一批「可读聚合库存变了」的键。
     *
     * <p>
     * 和玩家操作不同，总线变化可能一次动很多键（区块加载/卸载、目标换箱子、
     * 访问模式切换），所以这里合并成一个增量包；装不下就退回全量快照。
     * AE 桥的监听器也在这一步收到通知 —— 它会把这些键攒到自己的 tick 末再推送，
     * 不会在存储锁里回调 AE。
     */
    public static void onExternalStorageChanged(Set<ItemKey> itemKeys, Set<FluidKey> fluidKeys) {
        if (!loaded || storage.usesNetwork()) return;

        SharedStorage current = storage;
        boolean any = false;
        if (itemKeys != null) {
            for (ItemKey key : itemKeys) {
                if (key == null) continue;
                current.notifyExternalItemChanged(key);
                any = true;
            }
        }
        if (fluidKeys != null) {
            for (FluidKey key : fluidKeys) {
                if (key == null) continue;
                current.notifyExternalFluidChanged(key);
                any = true;
            }
        }
        if (!any) return;

        PacketStorageDelta delta = new PacketStorageDelta();
        if (itemKeys != null) {
            for (ItemKey key : itemKeys) {
                if (key != null) delta.addItem(key, current.getItemAmount(key));
            }
        }
        if (fluidKeys != null) {
            for (FluidKey key : fluidKeys) {
                if (key != null) delta.addFluid(key, current.getFluidAmount(key));
            }
        }
        if (delta.isEmpty()) return;
        if (delta.hasOverflowed()) {
            // 一次变化的键太多 / 某个键的 NBT 太大：全量快照是分片的，体积可控
            resyncAll();
            return;
        }
        broadcastDelta(delta);
    }

    /** 让所有正看着界面的玩家重新拉一份全量快照。 */
    public static void resyncAll() {
        // 一次刷新足够；每个观众各刷一遍会随总线数量放大
        StorageBusRegistry.refreshAll();
        for (EntityPlayerMP viewer : viewers()) {
            sendSnapshotToRaw(viewer);
        }
    }

    /**
     * 从磁盘重新读取并替换内存内容（{@code /futashared reload}）。
     *
     * <p>
     * <b>刻意不先 saveNow()。</b>这个指令存在的意义就是「让磁盘上的内容生效」，
     * 如果先写一遍盘，管理员手工改过的文件会立刻被内存里的旧数据盖掉，
     * 那这个指令就永远是个空操作了。代价是内存里尚未自动保存的改动会被丢弃 ——
     * 所以调用方必须先向管理员确认。
     *
     * @return 读到的条目数；尚未载入或读取失败时返回 -1
     */
    public static int reloadFromDisk() {
        if (!loaded || worldDirectory == null || nativeWorld == null) return -1;

        SharedStorage fresh;
        try {
            nativeWorld.getSaveHandler()
                .flush();
            NativeMeStorageData replacement = NativeMeMigration.load(worldDirectory);
            if (com.futa_gtnh.me.PublicMeNetwork.isRunning()
                && !com.futa_gtnh.me.PublicMeNetwork.matchesStorageId(replacement.storageId())) {
                throw new IllegalStateException("Reload cannot replace the public ME warehouse identity");
            }
            fresh = replacement.access();
            nativeData = replacement;
            nativeWorld.setItemData(NativeMeStorageData.NAME, replacement);
        } catch (IllegalStateException error) {
            savingBlocked = true;
            if (nativeData != null) nativeData.blockSaving(true);
            FutaGtnhMod.LOG.error("公共 ME 原生存储：重新读取失败，保留当前内存库存并暂停保存；修复原文件后重新 reload 可恢复保存", error);
            return -1;
        }
        savingBlocked = false;
        boolean networkAttached = storage.usesNetwork();
        fresh.adoptListenersFrom(storage);
        storage = fresh;
        if (networkAttached) com.futa_gtnh.ae2.PublicStorageAccess.begin();
        readApiGeneration = UUID.randomUUID()
            .toString();
        invalidateSnapshot();
        resyncAll();
        return fresh.itemTypeCount() + fresh.fluidTypeCount();
    }
}
