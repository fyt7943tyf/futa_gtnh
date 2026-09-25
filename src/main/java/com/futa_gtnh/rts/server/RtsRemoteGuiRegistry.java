package com.futa_gtnh.rts.server;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;

/**
 * 「远程打开的容器」登记表。
 *
 * <p>
 * 俯瞰模式下远程右键一个箱子/GT 机器，服务端会把容器开起来 —— 但玩家本人
 * 还站在一百格外，下一 tick {@code EntityPlayerMP.onUpdate} 里的
 * {@code openContainer.canInteractWith(this)}（原版 1.7.10 就有的每 tick
 * 距离校验，见 EntityPlayerMP.java:201）就会把它关掉，玩家只看到界面闪一下。
 *
 * <p>
 * 解法和原版 RTSbuilding 的 ServerPlayerRemoteMenuMixin 一样：凡是<b>由俯瞰
 * 远程互动打开</b>的容器，登记进来；mixin（{@code MixinEntityPlayerMP} /
 * {@code MixinEntityPlayer}）在每 tick 校验处问一句
 * {@link #shouldRelax}，命中就放行。玩家<b>自己走过去</b>开的 GUI 不登记、
 * 不放行，行为和原版完全一致。
 *
 * <p>
 * 自清理：玩家换开别的界面、或关掉界面（openContainer 变化）时，
 * {@link #shouldRelax} 顺手把过期记录删掉，不需要独立的 tick 清理。
 */
public final class RtsRemoteGuiRegistry {

    private RtsRemoteGuiRegistry() {}

    /** 玩家 UUID → 远程打开的那个容器。每个玩家同时最多一个远程界面。 */
    private static final Map<UUID, Container> REMOTE_GUIS = new HashMap<>();

    /**
     * 互动执行完之后调用：如果这次互动把玩家的 openContainer 换成了新的
     * （= 远程打开了 GUI），登记之。
     *
     * @param before 互动之前的 openContainer（一般是背包容器）
     */
    public static void capture(EntityPlayerMP player, Container before) {
        if (player == null) return;
        Container now = player.openContainer;
        if (now != null && now != before) {
            REMOTE_GUIS.put(player.getUniqueID(), now);
        }
    }

    /**
     * mixin 每 tick 询问：这个容器是不是俯瞰远程打开的、要不要放宽距离校验？
     *
     * <p>
     * 记录过期（玩家已换开别的界面）时顺手清掉 —— 1.3.0 只在「注册的容器
     * 恰好等于当前传进来的容器」这个分支清理，玩家换开别的 GUI 后旧记录会
     * 一直挂着到登出。
     */
    public static boolean shouldRelax(EntityPlayerMP player, Container container) {
        if (player == null || container == null) return false;
        Container registered = REMOTE_GUIS.get(player.getUniqueID());
        if (registered == null) return false;
        if (registered != player.openContainer || registered != container) {
            // 玩家已经不在这个远程界面上了 —— 记录作废
            REMOTE_GUIS.remove(player.getUniqueID());
            return false;
        }
        return true;
    }

    /** 玩家下线时清掉。 */
    public static void forget(UUID id) {
        if (id != null) REMOTE_GUIS.remove(id);
    }

    /**
     * 俯瞰会话结束时清掉：还开着的远程界面不再放宽距离校验，
     * 玩家走远后原版的每 tick 检查会照常把它关掉。
     */
    public static void clearFor(EntityPlayerMP player) {
        if (player != null) REMOTE_GUIS.remove(player.getUniqueID());
    }
}
