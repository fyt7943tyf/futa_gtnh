package com.futa_gtnh.rts.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

/**
 * 俯瞰操作的撤销/重做历史（服务端权威）。
 *
 * <p>
 * 每条记录是一次动作产生的全部方块改动（单块破坏一条、整个批量任务一条），
 * 同时存「改前」和「改后」两个状态：撤销回滚到改前、重做再写成改后。
 *
 * <p>
 * <b>方块实体的取舍</b>：改前状态的方块实体（箱子里的东西、机器的设置）
 * 整个存进 NBT、恢复时原样还回；改后状态只存 block+meta，恢复时由方块自己
 * 重建（批量放置的都是新方块，本来就没有内容）。撤销「破坏一个装着东西的
 * 箱子」时物品能全回来 —— 因为改前 NBT 是破坏前快照的。
 *
 * <p>
 * <b>不回退材料</b>：撤销放置不会把方块变回物品、撤销破坏也不会把掉落物
 * 收走（掉落物早就散在地上了）。历史只管方块，这是和原版 RTSbuilding 一致
 * 的取舍。
 *
 * <p>
 * 上限：每栈 {@code rtsHistoryMaxEntries}（默认 3）条、每条
 * {@code rtsHistoryRetentionSeconds}（默认 600 秒）后过期；过期在入栈/出栈时
 * 惰性清理，不需要 tick 巡检。
 */
public final class RtsHistoryManager {

    private RtsHistoryManager() {}

    /** 一格的一次改动：改前/改后的方块状态。 */
    public static final class Change {

        public final int x, y, z;
        public final Block beforeBlock;
        public final int beforeMeta;
        public final NBTTagCompound beforeTile;
        public final Block afterBlock;
        public final int afterMeta;

        public Change(int x, int y, int z, Block beforeBlock, int beforeMeta, NBTTagCompound beforeTile,
            Block afterBlock, int afterMeta) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.beforeBlock = beforeBlock;
            this.beforeMeta = beforeMeta;
            this.beforeTile = beforeTile;
            this.afterBlock = afterBlock;
            this.afterMeta = afterMeta;
        }
    }

    /** 一条历史 = 一次动作的全部改动 + 创建时间。 */
    private static final class Entry {

        final List<Change> changes;
        final long createdAt = System.currentTimeMillis();

        Entry(List<Change> changes) {
            this.changes = changes;
        }
    }

    private static final Map<UUID, Deque<Entry>> UNDO_STACKS = new HashMap<>();
    private static final Map<UUID, Deque<Entry>> REDO_STACKS = new HashMap<>();

    /** 拍一格的「改前」快照（方块 + meta + 方块实体 NBT）。 */
    public static Change snapshotBefore(World world, int x, int y, int z, Block afterBlock, int afterMeta) {
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        NBTTagCompound tileNbt = null;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile != null) {
            try {
                tileNbt = new NBTTagCompound();
                tile.writeToNBT(tileNbt);
            } catch (Throwable t) {
                // 有的 mod 方块实体写 NBT 会炸：宁可丢细节也不能让整个动作失败
                tileNbt = null;
            }
        }
        return new Change(x, y, z, block, meta, tileNbt, afterBlock, afterMeta);
    }

    /**
     * 记录一次动作（撤销入栈、重做清空 —— 新动作让旧的重做分支作废，
     * 和所有编辑器一致）。
     */
    public static void record(EntityPlayerMP player, List<Change> changes) {
        if (player == null || changes == null || changes.isEmpty()) return;
        UUID id = player.getUniqueID();
        Deque<Entry> stack = UNDO_STACKS.get(id);
        if (stack == null) {
            stack = new ArrayDeque<>();
            UNDO_STACKS.put(id, stack);
        }
        expireOld(stack);
        stack.addLast(new Entry(new ArrayList<>(changes)));
        while (stack.size() > Config.rtsHistoryMaxEntries) {
            stack.removeFirst();
        }
        REDO_STACKS.remove(id);
    }

    /**
     * 撤销：回滚最近一条。返回撤销的格子数（0 = 没有可撤销的）。
     *
     * <p>
     * 实际的方块写回不是当场一口气做完，而是挂进恢复队列按 tick 预算推进
     * （见 {@link #onServerTick}）—— 撤销一整个 3 万格的批量任务时，
     * 一 tick 内做完同样会卡服。
     */
    public static int undo(EntityPlayerMP player) {
        if (player == null) return 0;
        UUID id = player.getUniqueID();
        Deque<Entry> stack = UNDO_STACKS.get(id);
        if (stack == null || stack.isEmpty()) return 0;
        expireOld(stack);
        if (stack.isEmpty()) return 0;

        Entry entry = stack.removeLast();
        queueRestore(player, entry.changes, true);

        Deque<Entry> redo = REDO_STACKS.get(id);
        if (redo == null) {
            redo = new ArrayDeque<>();
            REDO_STACKS.put(id, redo);
        }
        redo.addLast(entry);
        return entry.changes.size();
    }

    /** 重做：把最近撤销的一条再写回去。返回重做的格子数（0 = 没有可重做的）。 */
    public static int redo(EntityPlayerMP player) {
        if (player == null) return 0;
        UUID id = player.getUniqueID();
        Deque<Entry> redo = REDO_STACKS.get(id);
        if (redo == null || redo.isEmpty()) return 0;

        Entry entry = redo.removeLast();
        queueRestore(player, entry.changes, false);

        Deque<Entry> undo = UNDO_STACKS.get(id);
        if (undo == null) {
            undo = new ArrayDeque<>();
            UNDO_STACKS.put(id, undo);
        }
        undo.addLast(entry);
        return entry.changes.size();
    }

    // ------------------------------------------------------------------
    // 恢复队列：按 tick 预算逐格写回
    // ------------------------------------------------------------------

    /** 一次撤销/重做的写回任务。 */
    private static final class RestoreTask {

        final EntityPlayerMP player;
        final List<Change> changes;
        final boolean undoBack;
        int index;

        RestoreTask(EntityPlayerMP player, List<Change> changes, boolean undoBack) {
            this.player = player;
            this.changes = changes;
            this.undoBack = undoBack;
        }
    }

    /** 全部玩家的恢复任务（共享一个每 tick 预算，简单且足够）。 */
    private static final Deque<RestoreTask> RESTORE_QUEUE = new ArrayDeque<>();

    private static void queueRestore(EntityPlayerMP player, List<Change> changes, boolean undoBack) {
        RESTORE_QUEUE.addLast(new RestoreTask(player, changes, undoBack));
    }

    /** 服务端 tick END 调（ModEventHandler），按预算推进恢复队列。 */
    public static void onServerTick() {
        if (RESTORE_QUEUE.isEmpty()) return;
        int budget = Config.rtsBuildBatchBlocksPerTick;
        while (budget > 0 && !RESTORE_QUEUE.isEmpty()) {
            RestoreTask task = RESTORE_QUEUE.peekFirst();
            // 玩家失效（死亡/换维度/下线清栈晚了一步）就丢任务
            if (task.player.isDead || task.player.worldObj == null) {
                RESTORE_QUEUE.removeFirst();
                continue;
            }
            if (task.index >= task.changes.size()) {
                RESTORE_QUEUE.removeFirst();
                continue;
            }
            applyOne(task.player.worldObj, task.changes.get(task.index), task.undoBack);
            task.index++;
            budget--;
        }
    }

    /** 恢复一格：undoBack=true 写改前状态，false 写改后状态。 */
    private static void applyOne(World world, Change change, boolean undoBack) {
        Block block = undoBack ? change.beforeBlock : change.afterBlock;
        int meta = undoBack ? change.beforeMeta : change.afterMeta;
        if (block == null) return;
        try {
            world.setBlock(change.x, change.y, change.z, block, meta, 3);
            if (undoBack && change.beforeTile != null) {
                TileEntity restored = TileEntity.createAndLoadEntity(change.beforeTile);
                if (restored != null) {
                    // createAndLoadEntity 已带坐标，再校准一次保险
                    restored.xCoord = change.x;
                    restored.yCoord = change.y;
                    restored.zCoord = change.z;
                    world.setTileEntity(change.x, change.y, change.z, restored);
                }
            }
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("俯瞰建筑：恢复历史记录时失败 @ ({},{},{})", change.x, change.y, change.z, t);
        }
    }

    /** 惰性清理：把超过保留时长的记录从栈底丢掉。 */
    private static void expireOld(Deque<Entry> stack) {
        long retentionMs = Config.rtsHistoryRetentionSeconds * 1000L;
        long now = System.currentTimeMillis();
        while (!stack.isEmpty() && now - stack.peekFirst().createdAt > retentionMs) {
            stack.removeFirst();
        }
    }

    /** 玩家下线时清栈（正在跑的恢复任务由 tick 里的 isDead 检查兜底）。 */
    public static void forget(UUID id) {
        if (id != null) {
            UNDO_STACKS.remove(id);
            REDO_STACKS.remove(id);
        }
    }
}
