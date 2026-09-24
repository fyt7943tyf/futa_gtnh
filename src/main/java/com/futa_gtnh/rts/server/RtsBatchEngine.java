package com.futa_gtnh.rts.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.futa_gtnh.Config;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;

/**
 * 批量操作引擎：按「每 tick 每玩家 {@code rtsBuildBatchBlocksPerTick} 格」的
 * 预算把一个批量任务逐格执行完（参照 {@code LocatorManager} 的 Job 模式）。
 *
 * <p>
 * <b>为什么逐 tick 而不是一口气</b>：32768 格的 setBlock/tryHarvestBlock
 * 一次跑完会卡服整整几秒；按预算切片后每 tick 的工作量有上界，任务在
 * 玩家眼皮底下平滑推进（也顺便自带了「进度感」）。
 *
 * <p>
 * <b>材料的取用顺序</b>（建造，生存模式）：先玩家背包，背包没有再从本模组的
 * 共享背包取（{@code rtsUseSharedStorage}，参照 {@code InventoryExchange} 的
 * 服务端权威模式 —— 全程主线程）。共享背包的改动在任务收尾时统一广播一次，
 * 避免每格一个同步包把网路刷爆。取不到材料 = 任务中止（已放好的保留）。
 *
 * <p>
 * <b>退出俯瞰不清任务</b>：Job 挂在玩家上而不是会话上，玩家退出俯瞰界面后
 * 任务继续跑完（和原版 RTSbuilding 的工作流理念一致）；玩家下线才取消。
 *
 * <p>
 * 撤销历史：整个任务累积改动、收尾时作为<b>一条</b>记录入栈。
 */
public final class RtsBatchEngine {

    private RtsBatchEngine() {}

    public static final byte ACTION_BUILD = 0;
    public static final byte ACTION_DESTROY = 1;

    /** 幽灵预览/提交都走同一份生成器，任务里的坐标由服务端自己重新生成。 */
    private static final class Job {

        final EntityPlayerMP player;
        final int dimension;
        final byte action;
        final List<int[]> positions;
        int index;
        // 建造：放什么
        final Block block;
        final int meta;
        final Item item;
        final int itemMeta;
        // 统计与撤销
        final List<RtsHistoryManager.Change> changes = new ArrayList<>();
        final Set<ItemKey> usedFromStorage = new HashSet<>();
        int doneCount;
        int skippedCount;
        boolean aborted;

        Job(EntityPlayerMP player, int dimension, byte action, List<int[]> positions, Block block, int meta, Item item,
            int itemMeta) {
            this.player = player;
            this.dimension = dimension;
            this.action = action;
            this.positions = positions;
            this.block = block;
            this.meta = meta;
            this.item = item;
            this.itemMeta = itemMeta;
        }
    }

    /** 每玩家的任务队列（同一玩家可以连续提交多个，按顺序执行）。 */
    private static final Map<UUID, Deque<Job>> QUEUES = new HashMap<>();

    /** 提交一个批量建造任务（held 必须是 ItemBlock，调用方已校验）。 */
    public static void submitBuild(EntityPlayerMP player, List<int[]> positions, ItemStack held) {
        ItemBlock itemBlock = (ItemBlock) held.getItem();
        Job job = new Job(
            player,
            player.dimension,
            ACTION_BUILD,
            positions,
            itemBlock.field_150939_a,
            held.getItemDamage(),
            held.getItem(),
            held.getItemDamage());
        enqueue(player, job);
        notify(player, "futa_gtnh.rts.msg.batch_started_build", positions.size());
    }

    /** 提交一个批量破坏任务。 */
    public static void submitDestroy(EntityPlayerMP player, List<int[]> positions) {
        Job job = new Job(player, player.dimension, ACTION_DESTROY, positions, null, 0, null, 0);
        enqueue(player, job);
        notify(player, "futa_gtnh.rts.msg.batch_started_destroy", positions.size());
    }

    private static void enqueue(EntityPlayerMP player, Job job) {
        Deque<Job> queue = QUEUES.get(player.getUniqueID());
        if (queue == null) {
            queue = new ArrayDeque<>();
            QUEUES.put(player.getUniqueID(), queue);
        }
        queue.addLast(job);
    }

    /** 服务端 tick END 调（ModEventHandler）。空表时一次 isEmpty 的开销。 */
    public static void onServerTick() {
        if (QUEUES.isEmpty()) return;

        Iterator<Map.Entry<UUID, Deque<Job>>> playerIt = QUEUES.entrySet()
            .iterator();
        while (playerIt.hasNext()) {
            Deque<Job> queue = playerIt.next()
                .getValue();
            int budget = Config.rtsBuildBatchBlocksPerTick;
            while (budget > 0 && !queue.isEmpty()) {
                Job job = queue.peekFirst();
                if (step(job)) {
                    // 返回 true = 任务收尾（完成/中止/失效）
                    queue.removeFirst();
                    finalizeJob(job);
                } else {
                    budget--;
                }
            }
            if (queue.isEmpty()) playerIt.remove();
        }
    }

    /**
     * 推进一步（一格）。
     *
     * @return true 表示任务结束（本次这格已消耗预算）
     */
    private static boolean step(Job job) {
        // 玩家下线/死亡/换维度：任务作废（不做收尾统计，直接丢弃）
        if (job.player.isDead || job.dimension != job.player.dimension) return true;
        World world = job.player.worldObj;
        if (world == null) return true;

        if (job.index >= job.positions.size() || job.aborted) return true;

        int[] pos = job.positions.get(job.index);
        job.index++;

        if (job.action == ACTION_BUILD) {
            stepBuild(job, world, pos[0], pos[1], pos[2]);
        } else {
            stepDestroy(job, world, pos[0], pos[1], pos[2]);
        }
        return job.aborted || job.index >= job.positions.size();
    }

    private static void stepBuild(Job job, World world, int x, int y, int z) {
        if (y < 0 || y > 255) {
            job.skippedCount++;
            return;
        }
        Block current = world.getBlock(x, y, z);
        // 只放进空气/可替换方块（草、雪层…），绝不覆盖实体方块
        if (!current.isAir(world, x, y, z) && !current.getMaterial()
            .isReplaceable()) {
            job.skippedCount++;
            return;
        }

        // 材料：先背包，后共享背包，两处都空 = 中止
        if (!job.player.capabilities.isCreativeMode) {
            if (!consumeOne(job)) {
                job.aborted = true;
                return;
            }
        }

        RtsHistoryManager.Change change = RtsHistoryManager.snapshotBefore(world, x, y, z, job.block, job.meta);
        world.setBlock(x, y, z, job.block, job.meta, 3);
        job.changes.add(change);
        job.doneCount++;
    }

    private static void stepDestroy(Job job, World world, int x, int y, int z) {
        // 先便宜地筛（空气/挖不动/工具不够），只对真要动的格子拍撤销快照
        // （读方块实体 NBT 有成本）
        RtsBuildService.BreakResult check = RtsBuildService.preCheck(job.player, x, y, z, false);
        if (check != RtsBuildService.BreakResult.PASS) {
            job.skippedCount++;
            return;
        }

        RtsHistoryManager.Change change = RtsHistoryManager.snapshotBefore(world, x, y, z, Blocks.air, 0);
        if (RtsBuildService.breakNow(job.player, x, y, z)) {
            job.changes.add(change);
            job.doneCount++;
        } else {
            // 保护事件取消之类：按跳过计
            job.skippedCount++;
        }
    }

    /**
     * 从背包或共享背包取一个建造材料（只被生存模式调用）。
     *
     * @return false = 两处都拿不到了（调用方应中止任务）
     */
    private static boolean consumeOne(Job job) {
        ItemStack[] inventory = job.player.inventory.mainInventory;
        for (int i = 0; i < inventory.length; i++) {
            ItemStack stack = inventory[i];
            if (stack != null && stack.stackSize > 0
                && stack.getItem() == job.item
                && stack.getItemDamage() == job.itemMeta) {
                stack.stackSize--;
                if (stack.stackSize <= 0) inventory[i] = null;
                job.player.inventory.markDirty();
                return true;
            }
        }

        if (!Config.rtsUseSharedStorage) return false;
        SharedStorage storage = SharedStorageManager.getStorage();
        if (storage == null) return false;

        ItemKey key = ItemKey.of(new ItemStack(job.item, 1, job.itemMeta));
        long got = storage.extractItem(key, 1);
        if (got <= 0) return false;
        job.usedFromStorage.add(key);
        return true;
    }

    /** 任务收尾：历史入栈、共享背包广播、结果播报。 */
    private static void finalizeJob(Job job) {
        if (!job.changes.isEmpty()) {
            RtsHistoryManager.record(job.player, job.changes);
        }
        for (ItemKey key : job.usedFromStorage) {
            SharedStorageManager.broadcastItemChange(key);
        }
        if (job.aborted) {
            notify(job.player, "futa_gtnh.rts.msg.batch_out_of_material", job.doneCount);
        } else if (job.action == ACTION_BUILD) {
            notify(job.player, "futa_gtnh.rts.msg.batch_done_build", job.doneCount, job.skippedCount);
        } else {
            notify(job.player, "futa_gtnh.rts.msg.batch_done_destroy", job.doneCount, job.skippedCount);
        }
    }

    private static void notify(EntityPlayerMP player, String key, Object... args) {
        if (player != null) player.addChatMessage(new ChatComponentTranslation(key, args));
    }

    /** 玩家下线时清队列（Job 里持有玩家引用，不清就泄漏 World）。 */
    public static void forget(UUID id) {
        if (id != null) QUEUES.remove(id);
    }
}
