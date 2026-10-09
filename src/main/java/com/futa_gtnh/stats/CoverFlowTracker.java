package com.futa_gtnh.stats;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.FutaGtnhMod;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.ICoverable;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 自上而下的电网覆盖版流量统计：枚举 + 采样。
 *
 * <p>
 * <b>枚举源是 GTSWN 自己的 {@code WirelessNodeRegistry}</b>（WorldSavedData
 * {@code gtswn_wireless_nodes}）：覆盖版放置 / 区块加载时自注册，含「打包坐标 → 类型」。
 * 所以这里<b>不做任何区块扫描</b> —— 不遍历 loadedTileEntityList，不把蒸汽机器
 * 之类的非电气设备卷进来；蒸汽机器没有电气覆盖版，天然不在注册表里。
 *
 * <p>
 * <b>采样</b>：每个采样期（200 tick = 10 秒）遍历全部维度的注册表条目；
 * 区块已加载的，现场读覆盖版实例上的 {@link CoverFlowProbe} 计数器（取走并清零），
 * 得到该期真实 EU 流量；区块未加载的保持上一期的值并标记离线。
 * 计数器在区块卸载期间继续累加，重新加载后的第一个样本会偏大 —— 首样本丢弃
 * 的差分保护见 {@link CoverRecord#apply}。
 *
 * <p>
 * <b>线程契约</b>：全部在服务端主线程（tick 推进 + API 读取）。
 * 纯内存、不落盘：这是一个「当前快照」视图，历史曲线由 AE2-Web 侧的账号级
 * 电网监控承担。
 */
public final class CoverFlowTracker {

    private CoverFlowTracker() {}

    /** 采样间隔，与 {@link IoFlowStats} 一致：200 tick = 10 秒。 */
    public static final int SAMPLE_INTERVAL_TICKS = 200;

    private static final Map<String, CoverRecord> records = new HashMap<>();
    private static int rollTimer;

    /** 反射句柄；解析失败（GTSWN 结构变了 / mixin 没应用）= 整个功能静默禁用。 */
    private static Method registryGet;
    private static Method registrySnapshot;
    private static boolean resolved;

    // ==================================================================
    // 生命周期与采样
    // ==================================================================

    public static void onServerStarted() {
        records.clear();
        rollTimer = 0;
    }

    public static void onServerTick() {
        if (--rollTimer > 0) return;
        rollTimer = SAMPLE_INTERVAL_TICKS;
        try {
            sample();
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("覆盖版流量统计：采样出错，这一期跳过", t);
        }
    }

    private static void sample() {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.worldServers == null) return;
        if (!resolveRegistry()) return;

        for (World world : server.worldServers) {
            if (world == null || world.isRemote) continue;
            int dim = world.provider.dimensionId;

            Map<Long, Byte> snapshot = snapshotOf(world);
            if (snapshot == null) continue;
            for (Map.Entry<Long, Byte> entry : snapshot.entrySet()) {
                long packed = entry.getKey();
                int x = unpackX(packed);
                int y = unpackY(packed);
                int z = unpackZ(packed);
                String key = dim + ":" + x + ":" + y + ":" + z;

                CoverRecord record = records.get(key);
                if (record == null) {
                    record = new CoverRecord(dim, x, y, z);
                    records.put(key, record);
                }

                if (!world.blockExists(x, y, z)) {
                    record.loaded = false;
                    continue;
                }
                record.loaded = true;

                TileEntity tile = world.getTileEntity(x, y, z);
                if (!(tile instanceof ICoverable)) {
                    record.missing = true;
                    continue;
                }
                record.missing = false;
                refreshHost(tile, record);

                // 六面扫一遍：一台机器可能同时贴多块无线覆盖版，流量按类型分开累计
                long consumerEu = 0L;
                long generatorEu = 0L;
                boolean configured = false;
                for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                    Object cover;
                    try {
                        cover = ((ICoverable) tile).getCoverAtSide(side);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (!(cover instanceof CoverFlowProbe)) continue;
                    CoverFlowProbe probe = (CoverFlowProbe) cover;
                    long flow = probe.futa$takeFlow();
                    if (isDynamoCover(cover)) generatorEu += flow;
                    else consumerEu += flow;
                    if (probe.futa$isConfigured()) configured = true;
                }
                record.apply(consumerEu, generatorEu, configured);
            }
        }
    }

    private static void refreshHost(TileEntity tile, CoverRecord record) {
        if (!(tile instanceof IGregTechTileEntity)) {
            record.machineKind = "UNKNOWN";
            return;
        }
        IMetaTileEntity meta = null;
        try {
            meta = ((IGregTechTileEntity) tile).getMetaTileEntity();
        } catch (Throwable ignored) {
            // 机器还没初始化完：保留上一次的名字
        }
        if (meta == null) return;
        record.hostClass = meta.getClass()
            .getName();
        record.machineKind = MULTIBLOCK_BASE != null && MULTIBLOCK_BASE.isInstance(meta) ? "MULTIBLOCK"
            : "SINGLE_BLOCK";
        if (record.hostName == null || record.hostName.isEmpty()) {
            try {
                record.hostName = meta.getLocalName();
            } catch (Throwable ignored) {
                record.hostName = "";
            }
        }
    }

    /** GT5U 多方块基类（反射取得；拿不到就当 UNKNOWN，自动聚合会跳过 UNKNOWN）。 */
    private static final Class<?> MULTIBLOCK_BASE = loadClass(
        "gregtech.api.metatileentity.implementations.MTEMultiBlockBase");

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 覆盖版是不是 dynamo 侧：按类名判断（GTSWN 两个子类的命名是稳定的公开结构）。 */
    private static boolean isDynamoCover(Object cover) {
        String name = cover.getClass()
            .getName();
        return name.endsWith("GTswn_Cover_DynamoWireless");
    }

    private static boolean resolveRegistry() {
        if (resolved) return registryGet != null;
        resolved = true;
        try {
            Class<?> registry = Class.forName("com.miaokatze.gtswn.common.covers.WirelessNodeRegistry");
            registryGet = registry.getMethod("get", World.class);
            registrySnapshot = registry.getMethod("snapshot", World.class);
        } catch (Throwable t) {
            registryGet = null;
            registrySnapshot = null;
            FutaGtnhMod.LOG.warn("覆盖版流量统计：找不到 GTSWN 无线节点注册表，功能停用（不影响其它功能）", t);
        }
        return registryGet != null;
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, Byte> snapshotOf(World world) {
        try {
            Object registry = registryGet.invoke(null, world);
            if (registry == null) return null;
            return (Map<Long, Byte>) registrySnapshot.invoke(registry, world);
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("覆盖版流量统计：读取注册表快照失败", t);
            return null;
        }
    }

    // ==================================================================
    // 坐标解包（位布局与 GTSWN WirelessNodeIndexCodec 一致：x@38..63 | y@26..37 | z@0..25）
    // ==================================================================

    static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    static int unpackY(long packed) {
        return (int) ((packed << 26) >> 52);
    }

    static int unpackZ(long packed) {
        return (int) ((packed << 38) >> 38);
    }

    // ==================================================================
    // 读快照（供 CoverPowerStatsApi，服务端主线程）
    // ==================================================================

    public static boolean isAvailable() {
        return resolveRegistry();
    }

    public static Map<String, CoverRecord> records() {
        return records;
    }

    /** 一个覆盖版宿主机器的当前状态。 */
    public static final class CoverRecord {

        public final String key;
        public final int dim;
        public final int x;
        public final int y;
        public final int z;

        public String hostName = "";
        public String hostClass = "";
        public String machineKind = "UNKNOWN";
        public boolean configured;
        public boolean loaded = true;
        public boolean missing;
        /** 最近一个完成采样期的流量（EU）。 */
        public long lastConsumerEu;
        public long lastGeneratorEu;
        /** 平均 EU/t = 采样期流量 / 200。 */
        public double eutIn;
        public double eutOut;
        public long lastSampleMs;

        CoverRecord(int dim, int x, int y, int z) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.key = dim + ":" + x + ":" + y + ":" + z;
        }

        /**
         * 结转一个采样期。计数器在区块卸载期间持续累加，重新加载后的第一个样本
         * 会把整段离线流量算进一期 —— 这种「断崖式恢复」直接丢弃首样本，不产生假峰值。
         */
        void apply(long consumerEu, long generatorEu, boolean configured) {
            boolean resumed = lastSampleMs == 0L;
            long now = System.currentTimeMillis();
            long offlineMs = now - lastSampleMs;
            if (lastSampleMs > 0L && offlineMs > SAMPLE_INTERVAL_TICKS * 100L) resumed = true;
            lastSampleMs = now;
            this.configured = configured;
            if (resumed) {
                lastConsumerEu = 0L;
                lastGeneratorEu = 0L;
                eutIn = 0.0;
                eutOut = 0.0;
                return;
            }
            lastConsumerEu = consumerEu;
            lastGeneratorEu = generatorEu;
            eutIn = consumerEu / (double) SAMPLE_INTERVAL_TICKS;
            eutOut = generatorEu / (double) SAMPLE_INTERVAL_TICKS;
        }

        /** 0 = 耗电（有净输入），1 = 发电（有净输出），-1 = 本期无流量。 */
        public int powerType() {
            if (eutOut > eutIn) return 1;
            if (eutIn > eutOut) return 0;
            return -1;
        }
    }
}
