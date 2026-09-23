package com.futa_gtnh.locator;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
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
            // 选中的东西没法搜（不是方块，或者矿脉数据没了）。
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
        send(player, PacketLocatorResult.cancelled());
    }

    /**
     * 传送到上次找到的位置。
     *
     * @return 结果；失败/开洞的原因由调用方告知玩家
     */
    public static TeleportResult teleport(EntityPlayerMP player) {
        int[] target = RESULTS.get(player.getUniqueID());
        if (target == null) return TeleportResult.NO_RESULT;

        switch (TeleportHelper.teleportNear(player, target[0], target[1], target[2])) {
            case NATURAL:
                return TeleportResult.OK;
            case CARVED:
                return TeleportResult.OK_CARVED;
            case FAILED:
            default:
                return TeleportResult.NO_SAFE_SPOT;
        }
    }

    public enum TeleportResult {
        /** 落在现成的安全位置上 */
        OK,
        /** 目标埋在实心方块里，就地清了两格 */
        OK_CARVED,
        NO_RESULT,
        NO_SAFE_SPOT
    }

    /** 玩家下线时清掉他的任务和结果。 */
    public static void forget(UUID id) {
        JOBS.remove(id);
        RESULTS.remove(id);
    }

    // ==================================================================
    // 每 tick 推进
    // ==================================================================

    public static void onServerTick() {
        if (JOBS.isEmpty()) return;

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
                send(
                    player,
                    PacketLocatorResult.found(position[0], position[1], position[2], job.scan.getBestDistance()));
            } else {
                send(player, PacketLocatorResult.notFound(1.0F));
            }
            iterator.remove();
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
