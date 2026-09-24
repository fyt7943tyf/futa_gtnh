package com.futa_gtnh.rts.server;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityXPOrb;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.projectile.EntityArrow;
import net.minecraft.item.ItemStack;

/**
 * 远程互动的服务端执行器：把「俯瞰光标右键/左键到了什么」翻译成
 * <b>原版交互逻辑</b>的一次执行。
 *
 * <p>
 * 所有动作都套在 {@link TemporaryContextSwitcher} 里跑：先把玩家临时摆到
 * 目标旁（虚拟眼位，reach 由配置控制），再调原版入口，跑完恢复。这保证
 * GT 机器、第三方容器这些「不认识我们」的代码按最熟悉的方式工作。
 *
 * <p>
 * 用到的原版入口（都核对过 1.7.10 源码）：
 * <ul>
 * <li>方块右键：{@code ItemInWorldManager.activateBlockOrUseItem} ——
 * 它会先跑方块的 onBlockActivated（开 GUI 就在这里发生），失败再用手上
 * 物品的 onItemUse。创造模式自动不消耗物品（原版内部处理）。</li>
 * <li>实体右键：{@code player.interactWith(entity)}（EntityInteractEvent
 * 由 Forge 在这里面的补丁触发）。</li>
 * <li>实体左键：{@code player.attackTargetEntityWithCurrentItem}（冷却、
 * 击退、暴击都是原版逻辑）。注意原版 NetHandler 对「攻击掉落物/经验球/
 * 箭」是直接踢出服务器的 —— 我们在 {@link #handleAttackEntity} 里直接
 * 拒绝，绝不让这种包走到任何踢人路径上。</li>
 * </ul>
 *
 * <p>
 * 已知的取舍：绕过 NetHandlerPlayServer 意味着 Forge 的 PlayerInteractEvent
 * （RightClickBlock 等）不会触发 —— 依赖该事件做保护的 mod（领地类）拦不住
 * 俯瞰操作。单人/自建服务器场景接受这一点；将来要做保护联动时在这里补。
 */
public final class RtsInteractionService {

    private RtsInteractionService() {}

    /**
     * 远程右键方块（用手上物品）。
     *
     * @param side           命中的面（0-5）
     * @param hitX/hitY/hitZ 命中点在方块内的局部坐标（0~1）
     * @param dirX/dirY/dirZ 客户端射线方向（用于反推虚拟眼位）
     * @param sneak          按住 Shift 的「潜行右键」语义（原版里潜行右键会跳过方块
     *                       互动、直接用物品 —— 比如潜行右键箱子放火把）
     */
    public static void handleUseBlock(EntityPlayerMP player, int x, int y, int z, int side, float hitX, float hitY,
        float hitZ, double dirX, double dirY, double dirZ, boolean sneak) {
        // 命中点世界坐标：方块原点 + 面内偏移
        double worldHitX = x + hitX;
        double worldHitY = y + hitY;
        double worldHitZ = z + hitZ;

        ContainerSnapshot containers = new ContainerSnapshot(player);
        ItemStack held = player.getCurrentEquippedItem();

        TemporaryContextSwitcher.withVirtualEye(
            player,
            worldHitX,
            worldHitY,
            worldHitZ,
            dirX,
            dirY,
            dirZ,
            com.futa_gtnh.Config.rtsInteractReach,
            new Runnable() {

                @Override
                public void run() {
                    TemporaryContextSwitcher.withSneaking(player, sneak, new Runnable() {

                        @Override
                        public void run() {
                            player.theItemInWorldManager
                                .activateBlockOrUseItem(player, player.worldObj, held, x, y, z, side, hitX, hitY, hitZ);
                        }
                    });
                }
            });
        containers.captureOpened(player);
    }

    /** 远程右键实体（原版 interactWith）。 */
    public static void handleInteractEntity(EntityPlayerMP player, Entity target, double dirX, double dirY,
        double dirZ) {
        if (target == null) return;
        ContainerSnapshot containers = new ContainerSnapshot(player);

        TemporaryContextSwitcher.withVirtualEye(
            player,
            target.posX,
            target.posY + target.height * 0.5D,
            target.posZ,
            dirX,
            dirY,
            dirZ,
            com.futa_gtnh.Config.rtsInteractReach,
            new Runnable() {

                @Override
                public void run() {
                    player.interactWith(target);
                }
            });
        containers.captureOpened(player);
    }

    /** 远程左键实体（原版攻击流程；掉落物/经验球/箭直接拒绝，见类注释）。 */
    public static void handleAttackEntity(EntityPlayerMP player, Entity target) {
        if (target == null) return;
        if (target instanceof EntityItem || target instanceof EntityXPOrb || target instanceof EntityArrow) return;

        TemporaryContextSwitcher.withPose(
            player,
            target.posX,
            target.posY + 1.0D,
            target.posZ,
            player.rotationYaw,
            player.rotationPitch,
            new Runnable() {

                @Override
                public void run() {
                    player.attackTargetEntityWithCurrentItem(target);
                }
            });
    }

    /**
     * 互动前后的 openContainer 快照：变了就说明远程打开了 GUI，
     * 交给 {@link RtsRemoteGuiRegistry} 登记放宽距离校验。
     */
    private static final class ContainerSnapshot {

        final net.minecraft.inventory.Container before;

        ContainerSnapshot(EntityPlayerMP player) {
            this.before = player.openContainer;
        }

        void captureOpened(EntityPlayerMP player) {
            RtsRemoteGuiRegistry.capture(player, before);
        }
    }
}
