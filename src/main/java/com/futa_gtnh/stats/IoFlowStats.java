package com.futa_gtnh.stats;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.TerminalIoConfig;

/**
 * IO 节点 / 共享终端的流量统计：采集、采样、快照与生命周期。
 *
 * <p>
 * <b>线程契约</b>：所有方法都只在服务端主线程调用 —— 写入来自搬运引擎的 tick，
 * 读取来自 {@link com.futa_gtnh.api.IoFlowStatsApi}（消费方 AE2-Web 会把自己的
 * HTTP 请求排进服务端任务队列再调 API）。因此这里<b>不加锁</b>，和
 * {@code SharedStorageManager} 的读 API 契约一致。
 *
 * <p>
 * <b>采样模型</b>：引擎每搬一点东西就累加进「当前窗口」；每
 * {@link #SAMPLE_INTERVAL_TICKS}（200 tick = 10 秒）把窗口结转为一个样本，压进
 * {@link #SAMPLE_COUNT}（60）格的环形历史（10 分钟窗口），同时累计「自开启以来的总量」。
 * 物品按「个」、流体按「mB」各自记账，从不混算。
 *
 * <p>
 * <b>资源粒度</b>：每个节点最多跟踪 {@link #MAX_TRACKED_RESOURCES}（64）种资源，
 * 超出时把最久不活跃的记录并入同类型（物品 / 流体）的「其他」聚合桶 —— 总量和历史
 * 都不丢，只是不再单列。IO 节点配了输出白名单时通常只搬几种东西，64 个槽位绰绰有余。
 *
 * <p>
 * <b>持久化</b>：沿用 {@code SharedStorageFile} 的做法（不用 WorldSavedData，
 * 自己写 {@code data/futa_gtnh_io_flow.dat}，原子替换 + 备份），落盘频率为
 * 「有活动时每 {@link #SAVE_EVERY_ROLLS} 个采样期一次」，停服时无条件落一次。
 *
 * <p>
 * <b>节点生命周期</b>：节点记录惰性创建（第一次心跳或第一笔流量），从不主动删除 ——
 * 方块被砸掉或区块卸载只是不再心跳，60 秒后标记为离线，历史保留，
 * 网页上仍能回看（也支持「放回去接着用」）。
 */
public final class IoFlowStats {

    private IoFlowStats() {}

    /** 采样间隔，单位 tick。10 秒一个样本，和 GTSWN 的机器采样节奏一致。 */
    public static final int SAMPLE_INTERVAL_TICKS = 200;

    /** 环形历史容量：60 个样本 = 10 分钟。 */
    public static final int SAMPLE_COUNT = 60;

    /** 每个节点单独跟踪的资源上限，超出并入「其他」聚合桶。 */
    public static final int MAX_TRACKED_RESOURCES = 64;

    /** 节点总数上限。超过时丢弃最久不活跃的离线节点（极端情况才可能触到）。 */
    public static final int MAX_NODES = 2048;

    /** 多久没心跳算离线：60 秒（覆盖区块卸载与正常的服务端卡顿）。 */
    private static final long OFFLINE_GRACE_TICKS = 1200;

    /** 有活动时最多隔几个采样期落一次盘。 */
    private static final int SAVE_EVERY_ROLLS = 6;

    static final int FORMAT_VERSION = 1;

    /** 「其他」聚合桶的哨兵键。ItemKey / FluidKey 永远不会和 Object 相等，可以安全混在一张表里。 */
    static final Object OTHERS_ITEM = new Object();
    static final Object OTHERS_FLUID = new Object();

    private static final Map<String, NodeRecord> nodes = new LinkedHashMap<>();
    private static File worldDirectory;
    private static boolean loaded;
    private static boolean dirty;
    private static long tick;
    private static int rollTimer;
    private static int rollsSinceSave;

    /** 每个服务器会话一份，读 API 用来识别「这是不是同一次启动里的数据」。 */
    private static String generation = UUID.randomUUID()
        .toString();

    // ==================================================================
    // 生命周期
    // ==================================================================

    public static boolean isLoaded() {
        return loaded;
    }

    public static String getGeneration() {
        return generation;
    }

    public static long getTick() {
        return tick;
    }

    /** 服务端启动完成时调用（跟随 {@code SharedStorageManager.onServerStarted} 的时机）。 */
    public static void onServerStarted(MinecraftServer server) {
        nodes.clear();
        loaded = false;
        generation = UUID.randomUUID()
            .toString();
        tick = 0;
        rollTimer = 0;
        rollsSinceSave = 0;
        dirty = false;

        if (server == null || server.worldServers == null
            || server.worldServers.length == 0
            || server.worldServers[0] == null) {
            FutaGtnhMod.LOG.error("IO 流量统计：拿不到主世界，本次以内存模式运行（不写盘）");
            worldDirectory = null;
            loaded = true;
            return;
        }

        worldDirectory = server.worldServers[0].getSaveHandler()
            .getWorldDirectory();
        IoFlowStatsFile.load(worldDirectory, nodes);
        loaded = true;
        FutaGtnhMod.LOG.info("IO 流量统计已就绪：载入 {} 个节点记录", nodes.size());
    }

    /** 服务端停止时调用：无条件落盘。 */
    public static void onServerStopping() {
        if (loaded && worldDirectory != null && dirty) {
            saveNow();
        }
        loaded = false;
        nodes.clear();
        worldDirectory = null;
        generation = UUID.randomUUID()
            .toString();
    }

    /** 服务端每 tick 调用（挂在 {@code ModEventHandler.onServerTick} 的 END 阶段）。 */
    public static void onServerTick() {
        if (!loaded) return;
        tick++;
        if (++rollTimer < SAMPLE_INTERVAL_TICKS) return;
        rollTimer = 0;
        roll();
        rollsSinceSave++;
        if (dirty && rollsSinceSave >= SAVE_EVERY_ROLLS) {
            saveNow();
        }
    }

    private static void saveNow() {
        rollsSinceSave = 0;
        if (IoFlowStatsFile.save(worldDirectory, nodes)) {
            dirty = false;
        }
    }

    // ==================================================================
    // 记账
    // ==================================================================

    /**
     * 拿一个节点的记账手柄；统计关闭时返回 {@code null}。
     *
     * <p>
     * 每次调用都会刷新节点的身份信息（名称 / 口径 / 类型）和心跳时间 ——
     * 配置在界面上改了名字，这里就是同步进来的地方。
     *
     * @param sharedTerminal true = 共享终端，false = IO 节点
     */
    public static IoFlowRecorder recorderFor(World world, int x, int y, int z, boolean sharedTerminal,
        TerminalIoConfig config) {
        if (world == null || world.isRemote || config == null || !config.isStatsEnabled()) return null;

        NodeRecord node = touch(world, x, y, z, sharedTerminal, config);
        return node == null ? null : new IoFlowRecorder(node);
    }

    /**
     * 心跳：方块实体每 tick 报一次「我还开着统计」。
     *
     * <p>
     * 必须放在 {@code updateEntity} 里 <b>hasAnyMode 判断之前</b> ——
     * 一个面都没配的节点同样可能开着统计（先摆好节点再慢慢配面），
     * 那种节点没有流量，但网页上应该能看到它、知道它在线。
     *
     * @return 对应的节点记录；服务器还没就绪等异常情况下为 null
     */
    public static NodeRecord touch(World world, int x, int y, int z, boolean sharedTerminal, TerminalIoConfig config) {
        if (world == null || world.isRemote || config == null || !config.isStatsEnabled()) return null;

        NodeRecord node = nodes.get(key(world, x, y, z));
        if (node == null) {
            node = createNode(world, x, y, z, sharedTerminal);
            if (node == null) return null;
        }
        node.refreshIdentity(sharedTerminal, config);
        node.lastSeenTick = tick;
        node.online = true;
        return node;
    }

    private static NodeRecord createNode(World world, int x, int y, int z, boolean sharedTerminal) {
        if (nodes.size() >= MAX_NODES && !evictStaleNode()) {
            FutaGtnhMod.LOG.warn("IO 流量统计：节点数已达上限 {}，不再记录新节点 {}", MAX_NODES, key(world, x, y, z));
            return null;
        }
        NodeRecord node = new NodeRecord(world.provider.dimensionId, x, y, z, sharedTerminal);
        nodes.put(node.key, node);
        dirty = true;
        return node;
    }

    /** @return 是否成功挤掉了一个「最久没心跳的离线节点」 */
    private static boolean evictStaleNode() {
        NodeRecord stalest = null;
        for (NodeRecord node : nodes.values()) {
            if (node.online) continue;
            if (stalest == null || node.lastSeenTick < stalest.lastSeenTick) stalest = node;
        }
        if (stalest == null) return false;
        nodes.remove(stalest.key);
        FutaGtnhMod.LOG.warn("IO 流量统计：节点数超上限，丢弃最久不活跃的离线节点 {}", stalest.key);
        return true;
    }

    static String key(World world, int x, int y, int z) {
        return world.provider.dimensionId + ":" + x + ":" + y + ":" + z;
    }

    // ==================================================================
    // 采样
    // ==================================================================

    /** 把所有节点的当前窗口结转为一个样本，推进环形历史。 */
    private static void roll() {
        if (nodes.isEmpty()) return;

        boolean activity = false;
        for (NodeRecord node : nodes.values()) {
            activity |= node.roll();

            // 心跳超时 → 离线（区块卸载 / 方块被砸 / 玩家关掉了统计都会走到这里）
            if (node.online && tick - node.lastSeenTick > OFFLINE_GRACE_TICKS) {
                node.online = false;
                dirty = true;
            }
        }
        if (activity) dirty = true;
    }

    // ==================================================================
    // 读快照（供 IoFlowStatsApi 使用，服务端主线程）
    // ==================================================================

    static List<NodeRecord> nodeRecords() {
        return new ArrayList<>(nodes.values());
    }

    static NodeRecord nodeRecord(String key) {
        return nodes.get(key);
    }

    // ==================================================================
    // 节点记录
    // ==================================================================

    /** 一个统计节点的全部状态。只在服务端主线程触碰。 */
    static final class NodeRecord {

        final String key;
        final int dim;
        final int x;
        final int y;
        final int z;

        /** 0 = IO 节点，1 = 共享终端。 */
        byte type;
        String name = "";
        byte mode = (byte) TerminalIoConfig.StatsMode.BOTH.ordinal();
        /** 方向口径的枚举缓存，免得记账热路径上每次 values() 克隆数组。 */
        TerminalIoConfig.StatsMode statsMode = TerminalIoConfig.StatsMode.BOTH;
        boolean enabled = true;
        boolean online;
        long lastSeenTick;

        /** 当前窗口（还没结转成样本）的累计量。 */
        long winItemIn, winItemOut, winFluidIn, winFluidOut;

        /** 环形历史（最近完成的样本），物品按「个」、流体按「mB」。 */
        final long[] ringItemIn = new long[SAMPLE_COUNT];
        final long[] ringItemOut = new long[SAMPLE_COUNT];
        final long[] ringFluidIn = new long[SAMPLE_COUNT];
        final long[] ringFluidOut = new long[SAMPLE_COUNT];

        /** 自开启统计以来的累计总量。 */
        long totalItemIn, totalItemOut, totalFluidIn, totalFluidOut;

        /** 资源粒度记录：键为 ItemKey / FluidKey / 「其他」哨兵。 */
        final Map<Object, ResRecord> resources = new LinkedHashMap<>();

        /** 环形历史的写入位置与已填充数量（所有环共用一套索引）。 */
        int ringIndex;
        int ringCount;

        NodeRecord(int dim, int x, int y, int z, boolean sharedTerminal) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.type = sharedTerminal ? (byte) 1 : (byte) 0;
            this.key = dim + ":" + x + ":" + y + ":" + z;
        }

        void refreshIdentity(boolean sharedTerminal, TerminalIoConfig config) {
            byte newType = sharedTerminal ? (byte) 1 : (byte) 0;
            String newName = config.getStatsName();
            TerminalIoConfig.StatsMode newMode = config.getStatsMode();
            if (type != newType || !name.equals(newName) || mode != newMode.ordinal()) {
                type = newType;
                name = newName;
                mode = (byte) newMode.ordinal();
                statsMode = newMode;
                dirty = true;
            }
            enabled = true;
        }

        /** 记一笔。方向口径不匹配的资源直接不记（总量和资源明细都不记）。 */
        void record(Object resourceKey, boolean fluid, boolean inbound, long amount) {
            if (amount <= 0L) return;
            if (inbound && !statsMode.tracksIn()) return;
            if (!inbound && !statsMode.tracksOut()) return;

            lastSeenTick = tick;
            online = true;
            dirty = true;

            if (fluid) {
                if (inbound) {
                    winFluidIn += amount;
                    totalFluidIn += amount;
                } else {
                    winFluidOut += amount;
                    totalFluidOut += amount;
                }
            } else {
                if (inbound) {
                    winItemIn += amount;
                    totalItemIn += amount;
                } else {
                    winItemOut += amount;
                    totalItemOut += amount;
                }
            }

            ResRecord record = resources.get(resourceKey);
            if (record == null) record = trackResource(resourceKey, fluid);
            record.add(inbound, amount);
        }

        /** 新资源进门；表满时先挤掉最久不活跃的（并入「其他」桶）。 */
        private ResRecord trackResource(Object resourceKey, boolean fluid) {
            if (resources.size() >= MAX_TRACKED_RESOURCES && !resources.containsKey(resourceKey)) {
                evictStaleResource();
            }
            ResRecord record = new ResRecord(fluid);
            resources.put(resourceKey, record);
            return record;
        }

        private void evictStaleResource() {
            Object stalestKey = null;
            long stalest = Long.MAX_VALUE;
            for (Map.Entry<Object, ResRecord> entry : resources.entrySet()) {
                if (entry.getKey() == OTHERS_ITEM || entry.getKey() == OTHERS_FLUID) continue;
                if (entry.getValue().lastActiveMs < stalest) {
                    stalest = entry.getValue().lastActiveMs;
                    stalestKey = entry.getKey();
                }
            }
            if (stalestKey == null) return;

            ResRecord evicted = resources.remove(stalestKey);
            ResRecord others = othersBucket(evicted.fluid);
            others.mergeFrom(evicted);
        }

        private ResRecord othersBucket(boolean fluid) {
            Object sentinel = fluid ? OTHERS_FLUID : OTHERS_ITEM;
            ResRecord others = resources.get(sentinel);
            if (others == null) {
                others = new ResRecord(fluid);
                resources.put(sentinel, others);
            }
            return others;
        }

        /** @return 这一轮有没有任何活动（决定要不要标记脏） */
        boolean roll() {
            boolean activity = winItemIn != 0L || winItemOut != 0L || winFluidIn != 0L || winFluidOut != 0L;

            int index = ringIndex;
            ringItemIn[index] = winItemIn;
            ringItemOut[index] = winItemOut;
            ringFluidIn[index] = winFluidIn;
            ringFluidOut[index] = winFluidOut;
            winItemIn = 0L;
            winItemOut = 0L;
            winFluidIn = 0L;
            winFluidOut = 0L;

            Iterator<Map.Entry<Object, ResRecord>> iterator = resources.entrySet()
                .iterator();
            while (iterator.hasNext()) {
                ResRecord record = iterator.next()
                    .getValue();
                record.roll(index);
                // 长期零流量又早已离线的资源记录：留满一圈零之后清掉，防止表被历史垃圾占满
                if (!online && record.isLongDead(tick)) iterator.remove();
            }

            ringIndex = (index + 1) % SAMPLE_COUNT;
            ringCount = Math.min(ringCount + 1, SAMPLE_COUNT);
            return activity;
        }
    }

    /** 一种资源（物品或流体）在一个节点上的流量记录。 */
    static final class ResRecord {

        final boolean fluid;
        /** 当前窗口累计。 */
        long winIn, winOut;
        /** 环形历史。 */
        final long[] ringIn = new long[SAMPLE_COUNT];
        final long[] ringOut = new long[SAMPLE_COUNT];
        /** 自跟踪以来的累计总量。 */
        long totalIn, totalOut;
        long lastActiveMs = System.currentTimeMillis();

        ResRecord(boolean fluid) {
            this.fluid = fluid;
        }

        void add(boolean inbound, long amount) {
            if (inbound) {
                winIn += amount;
                totalIn += amount;
            } else {
                winOut += amount;
                totalOut += amount;
            }
            lastActiveMs = System.currentTimeMillis();
        }

        void roll(int index) {
            ringIn[index] = winIn;
            ringOut[index] = winOut;
            winIn = 0L;
            winOut = 0L;
        }

        void mergeFrom(ResRecord other) {
            totalIn += other.totalIn;
            totalOut += other.totalOut;
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                ringIn[i] += other.ringIn[i];
                ringOut[i] += other.ringOut[i];
            }
            winIn += other.winIn;
            winOut += other.winOut;
        }

        /** 累计量为零且整个环形历史都是零 —— 数据已经没有任何观察价值。 */
        boolean isLongDead(long currentTick) {
            if (totalIn != 0L || totalOut != 0L) return false;
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                if (ringIn[i] != 0L || ringOut[i] != 0L) return false;
            }
            return winIn == 0L && winOut == 0L;
        }
    }
}
