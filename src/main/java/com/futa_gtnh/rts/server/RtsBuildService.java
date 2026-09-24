package com.futa_gtnh.rts.server;

import java.util.Collections;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

/**
 * 远程建造的服务端执行器（单块部分；批量引擎逐格复用这里）。
 *
 * <p>
 * <b>放置</b>没有独立的服务：单块放置就是远程互动（原版
 * {@code activateBlockOrUseItem} 会走 ItemBlock.onItemUse —— 生存模式自动
 * 从手上扣一个，创造模式自动不扣），见 {@link RtsInteractionService}。
 * 批量放置走 {@link RtsBatchEngine} 的直接 setBlock 路径（性能 + 材料联动）。
 *
 * <p>
 * <b>破坏</b>是「简化生存」：即时完成，不模拟挖掘进度，但把原版
 * {@code tryHarvestBlock} 的其它部分全保留 —— 破坏事件（领地保护在这）、
 * 掉落物（含精准/时运）、工具耐久、统计。创造模式走原版创造分支（即破坏、
 * 不掉落）。
 *
 * <p>
 * 额外的一道闸：{@code rtsRequireCorrectToolForBreak}（默认开）—— 生存模式
 * 下工具挖掘等级不够就拒绝，而不是像原版那样「挖掉了但什么都不掉」。
 * 俯瞰模式点错一下就毁掉一整面墙却什么都没捞到，这个体验太伤；宁可拒绝
 * 并提示。批量模式同一条闸（notify=false 时静默跳过并计数）。
 */
public final class RtsBuildService {

    private RtsBuildService() {}

    /** 一次破坏尝试的结果（批量引擎按类别统计，单块路径只关心能不能过）。 */
    public enum BreakResult {

        /** 前置校验全过，可以破坏。 */
        PASS,
        /** 目标是空气（批量时按跳过计）。 */
        SKIP_AIR,
        /** 挖不动的方块（基岩、传送门框架…）。 */
        REJECT_UNBREAKABLE,
        /** 工具挖掘等级不够（rtsRequireCorrectToolForBreak）。 */
        REJECT_TOOL,
        /** 世界不可用等异常。 */
        FAIL
    }

    /**
     * 远程破坏一个方块（即时），并记入撤销历史。
     *
     * @return true 表示破坏成功
     */
    public static boolean handleBreakBlock(EntityPlayerMP player, int x, int y, int z) {
        World world = player.worldObj;
        if (preCheck(player, x, y, z, true) != BreakResult.PASS) return false;

        // 撤销快照必须在破坏之前拍
        RtsHistoryManager.Change change = RtsHistoryManager.snapshotBefore(world, x, y, z, Blocks.air, 0);
        boolean broke = breakNow(player, x, y, z);
        if (broke) {
            RtsHistoryManager.record(player, Collections.singletonList(change));
            return true;
        }
        return false;
    }

    /**
     * 破坏前置校验（不碰世界）：空气 / 挖不动 / 工具等级三道闸。
     *
     * <p>
     * 和 {@link #breakNow} 分开是为了批量路径：先便宜地筛掉不要动的格子，
     * 再对真正要破坏的格子拍撤销快照（读方块实体 NBT 有成本，不能白做）。
     *
     * @param notify 被闸拦下时是否给玩家发提示（单块要提示；批量静默跳过并计数）
     */
    public static BreakResult preCheck(EntityPlayerMP player, int x, int y, int z, boolean notify) {
        World world = player.worldObj;
        if (world == null) return BreakResult.FAIL;

        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        if (block.isAir(world, x, y, z)) return BreakResult.SKIP_AIR;

        if (block.getBlockHardness(world, x, y, z) < 0.0F) {
            if (notify) FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.rts.msg.unbreakable");
            return BreakResult.REJECT_UNBREAKABLE;
        }

        if (!player.capabilities.isCreativeMode && Config.rtsRequireCorrectToolForBreak) {
            // canHarvestBlock 内部会看玩家手上的工具（徒手可挖的方块本来就返回 true）
            if (!ForgeHooks.canHarvestBlock(block, player, meta)) {
                if (notify) FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.rts.msg.wrong_tool");
                return BreakResult.REJECT_TOOL;
            }
        }
        return BreakResult.PASS;
    }

    /**
     * 执行破坏（原版 tryHarvestBlock：破坏事件 → 移除方块 → 掉落/经验 →
     * 耐久 → 统计，全是原版逻辑，不需要临时上下文切换 —— 它按坐标操作，
     * 不校验玩家位置）。
     *
     * @return 是否真的破坏了（保护事件取消、冒险模式编辑限制时为 false）
     */
    public static boolean breakNow(EntityPlayerMP player, int x, int y, int z) {
        return player.theItemInWorldManager.tryHarvestBlock(x, y, z);
    }

    /**
     * 远程旋转一个方块（原版 rotateBlock：原木轴向、活塞朝向等），记入撤销。
     *
     * <p>
     * 撤销快照先拍「改前」，旋转后读一次「改后」再组记录 —— rotateBlock 的
     * 改后元数据没有别的途径提前知道。
     */
    public static void handleRotateBlock(EntityPlayerMP player, int x, int y, int z) {
        World world = player.worldObj;
        if (world == null) return;

        RtsHistoryManager.Change before = RtsHistoryManager
            .snapshotBefore(world, x, y, z, world.getBlock(x, y, z), world.getBlockMetadata(x, y, z));
        // Forge 版 rotateBlock 带轴向参数；UNKNOWN = 让方块自己挑最合理的转法
        // （原版原木/活塞按 Y 轴转，AE2 机器也认这个入口）
        boolean rotated = world.getBlock(x, y, z)
            .rotateBlock(world, x, y, z, net.minecraftforge.common.util.ForgeDirection.UNKNOWN);
        if (!rotated) {
            FutaGtnhMod.proxy.notifyPlayer(player, "futa_gtnh.rts.msg.cannot_rotate");
            return;
        }

        RtsHistoryManager.Change change = new RtsHistoryManager.Change(
            x,
            y,
            z,
            before.beforeBlock,
            before.beforeMeta,
            before.beforeTile,
            world.getBlock(x, y, z),
            world.getBlockMetadata(x, y, z));
        RtsHistoryManager.record(player, Collections.singletonList(change));
    }
}
