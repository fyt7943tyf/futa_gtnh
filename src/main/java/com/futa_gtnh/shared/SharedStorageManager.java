package com.futa_gtnh.shared;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.inventory.ContainerSharedTerminal;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageDelta;
import com.futa_gtnh.network.PacketStorageSync;
import com.futa_gtnh.station.StationViews;
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

    private static int autosaveTicker;
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

    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * 服务端启动完成时调用。重复调用（单人模式换存档）会先把上一份存盘再重新载入。
     */
    public static void onServerStarted(MinecraftServer server) {
        if (loaded) {
            saveNow();
        }

        File dir = resolveWorldDirectory(server);
        if (dir == null) {
            FutaGtnhMod.LOG.error("共享存储：拿不到主世界存档目录，本次以内存模式运行（不会写盘）");
            storage = new SharedStorage();
            worldDirectory = null;
            loaded = true;
            invalidateSnapshot();
            return;
        }

        worldDirectory = dir;
        storage = SharedStorageFile.load(dir);
        loaded = true;
        autosaveTicker = 0;
        invalidateSnapshot();

        FutaGtnhMod.LOG.info(
            "共享存储已就绪：{} 种物品 / {} 种流体，数据文件 {}",
            storage.itemTypeCount(),
            storage.fluidTypeCount(),
            SharedStorageFile.fileFor(dir)
                .getPath());
    }

    /** 服务端停止时调用：无条件落盘。 */
    public static void onServerStopping() {
        saveNow();
        loaded = false;
    }

    /**
     * 服务端 tick。按 {@link Config#autosaveIntervalSeconds} 定期落盘。
     *
     * <p>
     * 只在「有改动」时才真正写文件，所以空闲服务器不会反复 IO。
     */
    public static void onServerTick() {
        if (!loaded || worldDirectory == null) return;
        if (++autosaveTicker < Config.autosaveIntervalSeconds * 20) return;
        autosaveTicker = 0;
        if (storage.isDirty()) {
            saveNow();
        }
    }

    /** 立即落盘（若内容有改动）。 */
    public static void saveNow() {
        if (worldDirectory == null || !storage.isDirty()) return;
        long start = System.currentTimeMillis();
        if (SharedStorageFile.save(worldDirectory, storage)) {
            storage.markClean();
            FutaGtnhMod.LOG.info(
                "共享存储已保存（{} 种物品 / {} 种流体，耗时 {} ms）",
                storage.itemTypeCount(),
                storage.fluidTypeCount(),
                System.currentTimeMillis() - start);
        }
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

    /** 让所有正看着界面的玩家重新拉一份全量快照。 */
    public static void resyncAll() {
        for (EntityPlayerMP viewer : viewers()) {
            sendSnapshotTo(viewer);
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
        if (!loaded || worldDirectory == null) return -1;

        SharedStorage fresh = SharedStorageFile.load(worldDirectory);
        storage = fresh;
        invalidateSnapshot();
        resyncAll();
        return fresh.itemTypeCount() + fresh.fluidTypeCount();
    }
}
