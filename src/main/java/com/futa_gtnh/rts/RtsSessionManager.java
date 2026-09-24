package com.futa_gtnh.rts;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 服务端的「俯瞰建筑」会话登记表。
 *
 * <p>
 * 会话本身<b>没有任何服务端状态需要推进</b>：玩家在俯瞰模式下原地不动
 * （没有寻路 / 移动功能），相机锚点恒等于玩家脚下位置，所以服务端不需要
 * 记相机姿态 —— 所有动作包都只按「目标是否在玩家周围
 * {@code rtsMaxActionRadius} 方形范围内」这一条规则校验（见后续的各动作服务）。
 *
 * <p>
 * 这张表存在的意义是<b>门控</b>：只有声明自己开着俯瞰模式的玩家，才被允许发
 * 远程互动 / 放置 / 破坏 / 批量包。没有它，一个改过的客户端可以直接对着
 * 任何坐标发包，等于免掉了「开着 GUI」这个前提条件。
 *
 * <p>
 * 信任程度说明：客户端说「我开着」服务端就记「开着」，这看起来很宽松，
 * 但它不是安全边界 —— 真正的安全边界是每张动作包自己的范围 / 权限 / 限频
 * 校验（那些校验对开着会话的玩家同样生效）。这张表只是把「完全没开过俯瞰
 * 模式的玩家发的动作包」在门口就丢掉。
 */
public final class RtsSessionManager {

    private RtsSessionManager() {}

    /** 正在俯瞰模式里的玩家。UUID 键，玩家下线时由登出事件清掉。 */
    private static final Set<UUID> ACTIVE_SESSIONS = new HashSet<>();

    /**
     * 记录会话开关。开关本身总是成功：开启的合法性（配置开关等）由
     * {@code PacketRtsToggle} 的 handler 先判好再进来。
     */
    public static void setActive(EntityPlayerMP player, boolean enable) {
        if (player == null) return;
        if (enable) {
            ACTIVE_SESSIONS.add(player.getUniqueID());
        } else {
            ACTIVE_SESSIONS.remove(player.getUniqueID());
        }
    }

    /** 动作包的门控查询：没开会话的玩家发的动作包一律丢弃。 */
    public static boolean isActive(EntityPlayerMP player) {
        return player != null && ACTIVE_SESSIONS.contains(player.getUniqueID());
    }

    /** 玩家下线时清掉，避免 UUID 表越积越大。 */
    public static void forget(UUID id) {
        if (id != null) ACTIVE_SESSIONS.remove(id);
    }
}
