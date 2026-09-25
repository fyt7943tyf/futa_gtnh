package com.futa_gtnh.rts.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 远程操作的「临时上下文切换器」：在执行一段原版交互逻辑之前，把玩家
 * <b>临时</b>传送到目标旁边、摆好朝向，执行完立刻恢复。
 *
 * <p>
 * 这是整个远程互动方案的基石。1.7.10 里绝大多数方块/实体交互逻辑
 * （GT 机器的开机检查、容器的距离校验、工具的冷却……）都默认「玩家就在
 * 旁边」—— 与其在每条交互路径上重新实现一遍「远程版本」，不如把玩家
 * 短暂地放到逻辑期望的位置上，让原版和第三方 mod 的代码原样跑通。
 *
 * <p>
 * <b>安全要点（都是刻意的选择）：</b>
 * <ul>
 * <li>只改服务端内存里的位置/朝向字段（{@code setPositionAndRotation}），
 * <b>不调 {@code setPositionAndUpdate}</b> —— 那个会发 S08 同步包，
 * 客户端会被拉过去一下；</li>
 * <li>改动只维持本次动作（try-finally 恢复），不会跨 tick 残留；</li>
 * <li>理论上有极小概率把 1 tick 的位置泄漏给其他玩家（实体跟踪器的周期
 * 同步正好撞上），原版 RTSbuilding 接受了同样的取舍，这里一致接受。</li>
 * </ul>
 */
public final class TemporaryContextSwitcher {

    private TemporaryContextSwitcher() {}

    /** 一次切换的现场快照。 */
    private static final class Snapshot {

        final double posX, posY, posZ;
        final float rotationYaw, rotationPitch, rotationYawHead;

        Snapshot(EntityPlayer player) {
            this.posX = player.posX;
            this.posY = player.posY;
            this.posZ = player.posZ;
            this.rotationYaw = player.rotationYaw;
            this.rotationPitch = player.rotationPitch;
            this.rotationYawHead = player.rotationYawHead;
        }

        void restore(EntityPlayer player) {
            player.setPositionAndRotation(posX, posY, posZ, rotationYaw, rotationPitch);
            player.rotationYawHead = rotationYawHead;
        }
    }

    /**
     * 把玩家临时放到「沿命中点反推 reach 格的虚拟眼位」上执行动作。
     *
     * @param hitX  命中点（方块表面或实体身上的世界坐标）
     * @param dirX  客户端射线的单位方向（服务端只拿它反推眼位，不做别的信任）
     * @param reach 虚拟眼位离命中点的距离（原版交互判定普遍认 4~5 格）
     */
    public static void withVirtualEye(EntityPlayerMP player, double hitX, double hitY, double hitZ, double dirX,
        double dirY, double dirZ, double reach, Runnable action) {
        double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (length < 1.0E-6D) return;

        // 眼位 = 命中点 - 方向 * reach；朝向按方向反算（原版 getLookVec 的逆运算）
        double eyeX = hitX - dirX / length * reach;
        double eyeY = hitY - dirY / length * reach;
        double eyeZ = hitZ - dirZ / length * reach;
        float yaw = (float) Math.toDegrees(Math.atan2(-dirX / length, dirZ / length));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1.0D, Math.min(1.0D, dirY / length))));

        withPose(player, eyeX, eyeY, eyeZ, yaw, pitch, action);
    }

    /**
     * 把玩家临时摆到指定姿态执行动作（恢复由 finally 保证）。
     *
     * <p>
     * {@code setPositionAndRotation} 只写服务端内存字段和碰撞箱，不发任何包。
     */
    public static void withPose(EntityPlayerMP player, double posX, double posY, double posZ, float yaw, float pitch,
        Runnable action) {
        Snapshot snapshot = new Snapshot(player);
        try {
            player.setPositionAndRotation(posX, posY, posZ, yaw, pitch);
            player.rotationYawHead = yaw;
            action.run();
        } finally {
            snapshot.restore(player);
        }
    }

    /**
     * 临时切换快捷栏选中槽执行动作（放置/使用物品时「用什么」由槽位决定）。
     *
     * <p>
     * 配合 {@code player.inventory.currentItem} 使用；恢复时不发
     * {@code updateHeldItem}（服务端的快捷栏槽位本来就是权威的，改回去即可）。
     */
    public static void withSelectedSlot(EntityPlayerMP player, int slot, Runnable action) {
        int previous = player.inventory.currentItem;
        try {
            player.inventory.currentItem = slot;
            player.updateHeldItem();
            action.run();
        } finally {
            player.inventory.currentItem = previous;
            player.updateHeldItem();
        }
    }

    /**
     * 临时切换潜行状态执行动作（有的方块逻辑按潜行分流，比如潜行+右键放方块
     * 而不打开 GUI）。
     */
    public static void withSneaking(EntityPlayerMP player, boolean sneaking, Runnable action) {
        boolean previous = player.isSneaking();
        try {
            player.setSneaking(sneaking);
            action.run();
        } finally {
            player.setSneaking(previous);
        }
    }

    /** 想拿实体引用做日志/调试的调用方用（避免直接依赖 Snapshot 结构）。 */
    static double distanceTo(Entity entity, double x, double y, double z) {
        double dx = entity.posX - x;
        double dy = entity.posY - y;
        double dz = entity.posZ - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
