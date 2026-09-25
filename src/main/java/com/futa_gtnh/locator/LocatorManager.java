package com.futa_gtnh.locator;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.MathHelper;

import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketLocatorResult;

/**
 * 服务端的寻物任务调度。
 *
 * <p>
 * 每个玩家同时只允许有一个进行中的扫描：重新选一个方块就把旧的顶掉。
 * 不限制的话，一个连点器就能让服务器同时跑几十个扫描任务。
 *
 * <p>
 * 扫描结果单独记一份（{@link #results}），因为玩家看到结果之后可能过一会儿
 * 才点「传送」—— 那时候扫描任务早就结束了。
 */
public final class LocatorManager {

    private LocatorManager() {}

    /** 一个进行中的任务。 */
    private static final class Job {

        final LocatorScan scan;
        /** 进度包的节流计时，免得每 tick 都往客户端推包。 */
        int progressTimer;

        Job(LocatorScan scan) {
            this.scan = scan;
        }
    }

    private static final Map<UUID, Job> JOBS = new HashMap<>();
    private static final Map<UUID, int[]> RESULTS = new HashMap<>();
    /** 最近一次找到目标的扫描条件，用于目标被挖掉后继续找同类目标。 */
    private static final Map<UUID, LocatorScan> TRACKING = new HashMap<>();
    /**
     * 哪些玩家的<b>当前这个结果</b>已经用掉过一次传送。
     *
     * <p>
     * 和 {@link #RESULTS} 分开：传送成功后结果要留着（客户端靠它画追踪和光束），
     * 但传送本身只允许一次 —— 不分开的话，「传送过」就只能靠「把结果删掉」来表达，
     * 而那正好会让玩家落地之后失去唯一的指路手段。
     * 重新搜索、取消追踪、下线都会把这个标记清掉。
     */
    private static final Set<UUID> TELEPORTED = new HashSet<>();

    /** 进度包最快多少 tick 发一次。 */
    private static final int PROGRESS_INTERVAL = 5;

    // ==================================================================
    // 对外操作
    // ==================================================================

    /**
     * 开始一次新的方块搜索。会先取消该玩家已有的任务和结果。
     *
     * @param target 客户端选中的方块，以物品形式传来（服务端自己解析成 Block + meta）
     */
    public static void start(EntityPlayerMP player, ItemStack target) {
        UUID id = player.getUniqueID();
        JOBS.remove(id);
        RESULTS.remove(id);
        TRACKING.remove(id);
        TELEPORTED.remove(id);

        submit(
            player,
            id,
            new LocatorScan(
                player.worldObj,
                id,
                target,
                MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.posY),
                MathHelper.floor_double(player.posZ)));
    }

    /** 开始在容器库存中搜索某个物品。 */
    public static void startItem(EntityPlayerMP player, ItemStack target) {
        UUID id = player.getUniqueID();
        JOBS.remove(id);
        RESULTS.remove(id);
        TRACKING.remove(id);
        TELEPORTED.remove(id);

        submit(
            player,
            id,
            new LocatorScan(
                player.worldObj,
                id,
                target,
                MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.posY),
                MathHelper.floor_double(player.posZ),
                true));
    }

    /**
     * 开始一次新的矿脉搜索。
     *
     * @param veinKey 矿脉在 GT 里的内部名（{@code WorldgenGTOreLayer#getName()}）。
     *                <b>客户端只报名字</b>，具体是哪条矿脉由服务端自己去目录里查 ——
     *                这样客户端就改不了「要找什么材料」这件事。
     */
    public static void startVein(EntityPlayerMP player, String veinKey) {
        UUID id = player.getUniqueID();
        JOBS.remove(id);
        RESULTS.remove(id);
        TRACKING.remove(id);
        TELEPORTED.remove(id);

        OreVeinCatalog.Entry vein = OreVeinCatalog.byKey(veinKey);
        if (vein == null) {
            // 客户端报了个服务端不认识的矿脉名（模组版本不一致，或者伪造的包）
            send(player, PacketLocatorResult.notFound(0.0F));
            return;
        }

        submit(
            player,
            id,
            new LocatorScan(
                player.worldObj,
                id,
                vein,
                MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.posY),
                MathHelper.floor_double(player.posZ)));
    }

    private static void submit(EntityPlayerMP player, UUID id, LocatorScan scan) {
        if (!scan.isValid()) {
            // 目标类型不支持或矿脉数据已失效。
            // 理论上界面只列可搜的，但客户端不可信。
            send(player, PacketLocatorResult.notFound(0.0F));
            return;
        }

        JOBS.put(id, new Job(scan));
        send(player, PacketLocatorResult.running(0.0F));
    }

    public static void cancel(EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        JOBS.remove(id);
        RESULTS.remove(id);
        TRACKING.remove(id);
        TELEPORTED.remove(id);
        send(player, PacketLocatorResult.cancelled());
    }

    /**
     * 传送到上次找到的位置。
     *
     * <p>
     * <b>成功后不再清掉结果</b>：玩家落地的第一件事就是想知道「矿在哪边」，
     * 那正是追踪和光束的用处（以前这里顺手 cancel 掉，玩家一到就什么都看不见了）。
     * 「同一个结果只允许传送一次」这条规矩改成用 {@link #TELEPORTED} 表达 ——
     * 挡的是「再传送」，不是「继续指路」。
     *
     * @return 结果；失败/开洞的原因由调用方告知玩家
     */
    public static TeleportResult teleport(EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        int[] target = RESULTS.get(id);
        if (target == null) return TeleportResult.NO_RESULT;
        if (TELEPORTED.contains(id)) return TeleportResult.ALREADY_USED;

        TeleportResult result;
        switch (TeleportHelper.teleportNear(player, target[0], target[1], target[2])) {
            case NATURAL:
                result = TeleportResult.OK;
                break;
            case CARVED:
                result = TeleportResult.OK_CARVED;
                break;
            case FAILED_PROTECTED:
                return TeleportResult.NO_SAFE_SPOT_PROTECTED;
            case FAILED:
            default:
                return TeleportResult.NO_SAFE_SPOT;
        }

        // 真送到了才算用掉：失败时结果当然要留着让玩家再试（换个角度、或者自己走过去）
        TELEPORTED.add(id);
        send(player, PacketLocatorResult.arrived());
        return result;
    }

    public enum TeleportResult {
        /** 落在现成的安全位置上 */
        OK,
        /** 目标埋在实心方块里，就地清了两格 */
        OK_CARVED,
        NO_RESULT,
        NO_SAFE_SPOT,
        /**
         * 附近唯一能开洞的位置得清掉矿石（或木头/机器这类不该动的方块），所以没开。
         * 和「找不到」分开，是为了让提示说清楚是<b>不肯挖</b>，不是找不到。
         */
        NO_SAFE_SPOT_PROTECTED,
        /** 这个结果已经传送过一次了（追踪还在，想再传送得重新选目标） */
        ALREADY_USED
    }

    /** 玩家下线时清掉他的任务和结果。 */
    public static void forget(UUID id) {
        JOBS.remove(id);
        RESULTS.remove(id);
        TRACKING.remove(id);
        TELEPORTED.remove(id);
    }

    // ==================================================================
    // 每 tick 推进
    // ==================================================================

    public static void onServerTick() {
        if (!JOBS.isEmpty()) {
            Iterator<Map.Entry<UUID, Job>> iterator = JOBS.entrySet()
                .iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Job> entry = iterator.next();
                EntityPlayerMP player = findPlayer(entry.getKey());
                if (player == null) {
                    iterator.remove();
                    continue;
                }

                Job job = entry.getValue();
                boolean running = job.scan.tick();

                if (running) {
                    if (++job.progressTimer >= PROGRESS_INTERVAL) {
                        job.progressTimer = 0;
                        send(player, PacketLocatorResult.running(job.scan.getProgress()));
                    }
                    continue;
                }

                // 扫完了
                if (job.scan.hasResult()) {
                    int[] position = { job.scan.getBestX(), job.scan.getBestY(), job.scan.getBestZ() };
                    RESULTS.put(entry.getKey(), position);
                    TRACKING.put(entry.getKey(), job.scan);
                    send(
                        player,
                        PacketLocatorResult.found(position[0], position[1], position[2], job.scan.getBestDistance()));
                } else {
                    TRACKING.remove(entry.getKey());
                    send(player, PacketLocatorResult.notFound(1.0F));
                }
                iterator.remove();
            }
        }

        restartDestroyedTargets();
    }

    /** 上次命中的方块被挖掉后，按相同条件从玩家当前位置继续搜索。 */
    private static void restartDestroyedTargets() {
        if (TRACKING.isEmpty()) return;

        Iterator<Map.Entry<UUID, LocatorScan>> iterator = TRACKING.entrySet()
            .iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, LocatorScan> entry = iterator.next();
            UUID id = entry.getKey();
            int[] position = RESULTS.get(id);
            if (position == null) {
                iterator.remove();
                continue;
            }

            EntityPlayerMP player = findPlayer(id);
            if (player == null) {
                iterator.remove();
                RESULTS.remove(id);
                TELEPORTED.remove(id);
                continue;
            }

            LocatorScan previousScan = entry.getValue();
            // 搜索只在原维度有效。玩家去了别的维度时保留结果，回到原维度后再检查。
            if (player.worldObj != previousScan.getWorld()) continue;
            if (previousScan.targetStillAt(position[0], position[1], position[2])) continue;

            iterator.remove();
            RESULTS.remove(id);
            TELEPORTED.remove(id);
            submit(
                player,
                id,
                previousScan.restartAt(
                    MathHelper.floor_double(player.posX),
                    MathHelper.floor_double(player.posY),
                    MathHelper.floor_double(player.posZ)));
        }
    }

    private static EntityPlayerMP findPlayer(UUID id) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return null;

        for (Object object : server.getConfigurationManager().playerEntityList) {
            if (object instanceof EntityPlayerMP && ((EntityPlayerMP) object).getUniqueID()
                .equals(id)) {
                return (EntityPlayerMP) object;
            }
        }
        return null;
    }

    private static void send(EntityPlayerMP player, PacketLocatorResult packet) {
        NetworkHandler.INSTANCE.sendTo(packet, player);
    }
}
