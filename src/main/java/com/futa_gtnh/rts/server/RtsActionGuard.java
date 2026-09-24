package com.futa_gtnh.rts.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

import com.futa_gtnh.Config;

/**
 * 俯瞰动作包的公共校验器：操作范围 + 每 tick 限频。
 *
 * <p>
 * 每张 C2S 动作包（互动 / 放置 / 破坏 / 批量…）进门都先过这里，规则只有两条：
 * <ol>
 * <li><b>范围</b>：目标必须在玩家周围 {@code rtsMaxActionRadius} 的立方体里。
 * 玩家在俯瞰模式下原地不动（锚点恒为玩家位置），所以这就是「相机看得到的
 * 地方」—— 也是相机被客户端钳制的同款范围。</li>
 * <li><b>限频</b>：每 tick 最多 {@code rtsOpsPerTickPerPlayer} 次单块操作。
 * 没有它，改过的客户端可以在一个 tick 里灌几百张破坏包，把服务器当凿子使。
 * 批量操作不走这个计数（它们由批量引擎按自己的每 tick 预算限速）。</li>
 * </ol>
 *
 * <p>
 * 计数表在服务端 tick 的 END 阶段清零（{@code ModEventHandler} 调
 * {@link #tick()}），玩家下线时由登出事件清掉。
 */
public final class RtsActionGuard {

    private RtsActionGuard() {}

    private static final Map<UUID, Integer> OPS_THIS_TICK = new HashMap<>();

    /** 服务端 tick END 调：清零本 tick 的操作计数。 */
    public static void tick() {
        OPS_THIS_TICK.clear();
    }

    /** 玩家下线时清掉，避免 UUID 表泄漏。 */
    public static void forget(UUID id) {
        if (id != null) OPS_THIS_TICK.remove(id);
    }

    /** 目标坐标是否在玩家的操作范围内（立方体判定，三轴同半径）。 */
    public static boolean isWithinRange(EntityPlayerMP player, double x, double y, double z) {
        if (player == null) return false;
        double r = Config.rtsMaxActionRadius;
        return Math.abs(x - player.posX) <= r && Math.abs(y - player.posY) <= r && Math.abs(z - player.posZ) <= r;
    }

    /**
     * 消费一次操作额度：没开俯瞰会话、或本 tick 已用完额度时返回 false。
     *
     * <p>
     * 会话门控也放这里（而不是只放在包 handler 里），让「必须开着俯瞰模式
     * 才能发动作包」这条规则只有一份实现、不会被哪张新包漏掉。
     */
    public static boolean tryConsume(EntityPlayerMP player) {
        if (player == null) return false;
        if (!com.futa_gtnh.rts.RtsSessionManager.isActive(player)) return false;

        int used = OPS_THIS_TICK.getOrDefault(player.getUniqueID(), 0);
        if (used >= Config.rtsOpsPerTickPerPlayer) return false;
        OPS_THIS_TICK.put(player.getUniqueID(), used + 1);
        return true;
    }
}
