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
import net.minecraft.world.biome.BiomeGenBase;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.item.ItemLocatorWand;
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
        /**
         * 扫完之后要不要自动把玩家送过去。
         *
         * <p>
         * 只有「<b>玩家自己</b>把上次命中的那个方块挖掉、于是按同一条件自动重搜」
         * 这条路上的任务才为 true，而且要求他<b>戴着</b>魔杖
         * （见 {@link #noteBlockBroken}）。手动点一次目标永远是手动传送 ——
         * 自动传送只应该是「我在用魔杖扫矿」的副产品，不能因为别人挖了那块矿
         * 就把人凭空挪走。
         */
        final boolean autoAdvance;
        /** 进度包的节流计时，免得每 tick 都往客户端推包。 */
        int progressTimer;

        Job(LocatorScan scan, boolean autoAdvance) {
            this.scan = scan;
            this.autoAdvance = autoAdvance;
        }
    }

    private static final Map<UUID, Job> JOBS = new HashMap<>();
    private static final Map<UUID, int[]> RESULTS = new HashMap<>();
    /** 最近一次找到目标的扫描条件，用于目标被挖掉后继续找同类目标。 */
    private static final Map<UUID, LocatorScan> TRACKING = new HashMap<>();
    /**
     * 「玩家<b>自己</b>刚挖掉了当前追踪的那一格」。
     *
     * <p>
     * 由方块破坏事件写入（{@link #noteBlockBroken}），由下一次重搜消费掉。
     * 之所以要这么绕一圈：方块「没了」这件事本身是被动的（可能是别的玩家挖的、
     * 爆炸炸的、甚至是区块没加载），而只有玩家自己动手那一次才该触发自动传送。
     * 破坏事件在方块真正消失<b>之前</b>触发，所以这里先记一笔，
     * 等下一 tick 的巡检发现那一格真的空了再去搜。
     */
    private static final Set<UUID> SELF_BROKEN = new HashSet<>();
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
        SELF_BROKEN.remove(id);

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
        SELF_BROKEN.remove(id);

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
        SELF_BROKEN.remove(id);

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

    /** 开始搜索当前维度中的指定生物群系。 */
    public static void startBiome(EntityPlayerMP player, int biomeId) {
        UUID id = player.getUniqueID();
        JOBS.remove(id);
        RESULTS.remove(id);
        TRACKING.remove(id);
        TELEPORTED.remove(id);
        SELF_BROKEN.remove(id);

        BiomeGenBase[] biomes = BiomeGenBase.getBiomeGenArray();
        if (biomeId < 0 || biomeId >= biomes.length || biomes[biomeId] == null) {
            send(player, PacketLocatorResult.notFound(0.0F));
            return;
        }

        submit(
            player,
            id,
            new LocatorScan(
                player.worldObj,
                id,
                biomes[biomeId],
                MathHelper.floor_double(player.posX),
                MathHelper.floor_double(player.posY),
                MathHelper.floor_double(player.posZ)));
    }

    private static void submit(EntityPlayerMP player, UUID id, LocatorScan scan) {
        submit(player, id, scan, false);
    }

    /**
     * @param autoAdvance 扫到结果之后要不要自动把玩家送过去（见 {@link Job#autoAdvance}）
     */
    private static void submit(EntityPlayerMP player, UUID id, LocatorScan scan, boolean autoAdvance) {
        if (!scan.isValid()) {
            // 目标类型不支持或矿脉数据已失效。
            // 理论上界面只列可搜的，但客户端不可信。
            send(player, PacketLocatorResult.notFound(0.0F));
            return;
        }

        JOBS.put(id, new Job(scan, autoAdvance));
        send(player, PacketLocatorResult.running(0.0F));
    }

    public static void cancel(EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        JOBS.remove(id);
        RESULTS.remove(id);
        TRACKING.remove(id);
        TELEPORTED.remove(id);
        SELF_BROKEN.remove(id);
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

        TeleportResult result = teleportTo(player, target, TRACKING.get(id));
        if (result != TeleportResult.OK && result != TeleportResult.OK_CARVED) return result;

        // 真送到了才算用掉：失败时结果当然要留着让玩家再试（换个角度、或者自己走过去）
        TELEPORTED.add(id);
        send(player, PacketLocatorResult.arrived());
        return result;
    }

    /**
     * 传送的公共部分：手动点「传送」和自动追下一处走的是同一段。
     *
     * <p>
     * 抽出来是为了保证两边<b>规矩完全一致</b>：生物群系目标的落点要先按地表高度算，
     * 安全落点由 {@link TeleportHelper} 找（包括「找不到就就地开两格、
     * 但绝不碰矿石」那一套），这些一条都不能因为「是自动的」就少。
     */
    private static TeleportResult teleportTo(EntityPlayerMP player, int[] target, LocatorScan scan) {
        int targetY = target[1];
        if (scan != null && scan.isBiomeSearch()) {
            if (scan.getWorld() != player.worldObj) return TeleportResult.NO_RESULT;
            targetY = scan.prepareBiomeTeleportY(target[0], target[2]);
            if (targetY < 0) return TeleportResult.NO_SAFE_SPOT;
        }

        switch (TeleportHelper.teleportNear(player, target[0], targetY, target[2])) {
            case NATURAL:
                return TeleportResult.OK;
            case CARVED:
                return TeleportResult.OK_CARVED;
            case FAILED_PROTECTED:
                return TeleportResult.NO_SAFE_SPOT_PROTECTED;
            case FAILED:
            default:
                return TeleportResult.NO_SAFE_SPOT;
        }
    }

    /**
     * 玩家<b>自己</b>破坏了一个方块。挂在这个位置的是「自动追下一处」的扳机。
     *
     * <p>
     * 只有四件事同时成立才算数：
     * <ol>
     * <li>他正追踪着某个结果（追踪已经结束的不算）；</li>
     * <li>挖掉的<b>就是那一格</b>（挖别的方块不算）；</li>
     * <li>配置里开着 {@link Config#locatorAutoAdvance}；</li>
     * <li>他<b>戴着</b>魔杖（{@link ItemLocatorWand#isWornBy}）——
     * 拿在手上挖矿是常态，戴着才是「我正在用它扫矿」的表态。</li>
     * </ol>
     *
     * <p>
     * 这里<b>只记一笔</b>，不立刻搜：事件是在方块真正消失之前触发的
     * （而且可能被别的模组取消）。真正的重搜由 {@link #restartDestroyedTargets}
     * 在下一 tick 巡检到「那一格真的空了」时发起，那时候才把这一笔记账消费掉。
     * 结果是：别人挖掉那块矿、或者被爆炸炸掉，都只会让光束换目标，不会把人挪走。
     */
    public static void noteBlockBroken(EntityPlayerMP player, int x, int y, int z) {
        if (player == null || !Config.locatorAutoAdvance) return;

        UUID id = player.getUniqueID();
        int[] target = RESULTS.get(id);
        if (target == null || target[0] != x || target[1] != y || target[2] != z) return;
        if (TRACKING.get(id) == null) return;
        if (!ItemLocatorWand.isWornBy(player)) return;

        SELF_BROKEN.add(id);
    }

    /**
     * 自动追到刚搜出来的下一处。
     *
     * <p>
     * 和手动传送共用 {@link #teleportTo}，所以安全落点、就地开洞、不碰矿石这些
     * 规矩一条不少。三点不同：
     * <ul>
     * <li>成功<b>不吭声</b>：每挖掉一块矿就说一句会把聊天栏刷爆，位置变了玩家自己看得见；</li>
     * <li>失败要说清楚，而且<b>不消耗这次传送机会</b>（{@link #TELEPORTED} 不动）——
     * 结果和坐标都留着，玩家可以自己点「传送」再试一次；</li>
     * <li>目标离得太近就不传送，只把追踪切过去。挖矿时「下一个」常常就在隔壁一两格，
     * 为这个把人挪一下既没意义又晃眼；阈值见
     * {@link Config#locatorAutoAdvanceMinDistance}。</li>
     * </ul>
     */
    private static void autoAdvance(EntityPlayerMP player, UUID id, int[] target, LocatorScan scan) {
        if (!farEnoughToBother(player, target)) return;

        switch (teleportTo(player, target, scan)) {
            case OK:
            case OK_CARVED:
                TELEPORTED.add(id);
                send(player, PacketLocatorResult.arrived());
                break;
            case NO_SAFE_SPOT:
                FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.locator.msg.auto_failed");
                break;
            case NO_SAFE_SPOT_PROTECTED:
                FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.locator.msg.auto_failed_protected");
                break;
            default:
                // NO_RESULT：换维度之类的情况，什么都不做（结果还在，玩家可以手动传送）
                break;
        }
    }

    /** @return 新目标离玩家够不够远，值得为它传送一次 */
    private static boolean farEnoughToBother(EntityPlayerMP player, int[] target) {
        int minimum = Config.locatorAutoAdvanceMinDistance;
        if (minimum <= 0) return true;

        double dx = player.posX - (target[0] + 0.5D);
        double dy = player.posY - (target[1] + 0.5D);
        double dz = player.posZ - (target[2] + 0.5D);
        return dx * dx + dy * dy + dz * dz >= (double) minimum * (double) minimum;
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
        SELF_BROKEN.remove(id);
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
                    iterator.remove();

                    // 自动追下一处：先把任务摘掉再动（传送会改 RESULTS/TELEPORTED）
                    if (job.autoAdvance) {
                        autoAdvance(player, entry.getKey(), position, job.scan);
                    }
                    continue;
                }

                TRACKING.remove(entry.getKey());
                send(player, PacketLocatorResult.notFound(1.0F));
                if (job.autoAdvance) {
                    // 是自动追的目标却一处都没搜到：得说一声，否则玩家只会看到
                    // 光束突然没了、人也没动，不知道发生了什么
                    FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.locator.msg.auto_no_more");
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

            // 这一笔记账只有在「玩家自己挖掉了那一格」时才会有（见 noteBlockBroken），
            // 消费掉它，然后把「扫完自动送过去」交给这次重搜
            boolean autoAdvance = SELF_BROKEN.remove(id);
            submit(
                player,
                id,
                previousScan.restartAt(
                    MathHelper.floor_double(player.posX),
                    MathHelper.floor_double(player.posY),
                    MathHelper.floor_double(player.posZ)),
                autoAdvance);
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
