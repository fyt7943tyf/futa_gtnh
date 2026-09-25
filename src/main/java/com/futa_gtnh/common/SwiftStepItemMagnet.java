package com.futa_gtnh.common;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.futa_gtnh.item.ItemSwiftStep;

/** 服务端处理迅步的掉落物吸附。 */
public final class SwiftStepItemMagnet {

    /** 大范围配置下也避免每个玩家每 tick 都遍历当前维度的实体。 */
    private static final int SCAN_INTERVAL_TICKS = 5;

    private static final double MIN_PULL_SPEED = 0.12D;
    private static final double MAX_PULL_SPEED = 0.8D;
    private static final double PULL_SPEED_PER_BLOCK = 0.00068D;

    private SwiftStepItemMagnet() {}

    public static void tick(EntityPlayer player) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote || !player.isEntityAlive()) return;
        if (player.ticksExisted % SCAN_INTERVAL_TICKS != 0) return;

        ItemStack charm = ItemSwiftStep.findEquipped(player);
        if (charm == null || !ItemSwiftStep.isItemMagnetEnabled(charm)) return;

        World world = player.worldObj;
        int radius = ItemSwiftStep.getItemMagnetRadius(charm);
        double radiusSquared = (double) radius * radius;
        double centerX = player.posX;
        double centerY = player.posY + player.height * 0.5D;
        double centerZ = player.posZ;

        // 只遍历当前已加载实体，不通过大半径 AABB 查询区块，也不会加载远处区块。
        for (Entity entity : world.loadedEntityList) {
            if (!(entity instanceof EntityItem) || entity.isDead) continue;

            double dx = centerX - entity.posX;
            double dy = centerY - entity.posY;
            double dz = centerZ - entity.posZ;
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (distanceSquared <= 1.0E-6D || distanceSquared > radiusSquared) continue;

            double distance = Math.sqrt(distanceSquared);
            double speed = Math.min(MAX_PULL_SPEED, MIN_PULL_SPEED + distance * PULL_SPEED_PER_BLOCK);
            EntityItem item = (EntityItem) entity;
            item.motionX = dx / distance * speed;
            item.motionY = dy / distance * speed;
            item.motionZ = dz / distance * speed;
        }
    }
}
