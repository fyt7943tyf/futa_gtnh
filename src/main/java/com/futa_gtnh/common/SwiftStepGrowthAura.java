package com.futa_gtnh.common;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.IGrowable;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.futa_gtnh.item.ItemSwiftStep;

/** 服务端处理迅步的作物与动物生长光环。 */
public final class SwiftStepGrowthAura {

    /** 作物光环每 5 秒触发一次；动物光环根据强度缩短触发间隔。 */
    private static final int TICK_INTERVAL = 100;

    private SwiftStepGrowthAura() {}

    public static void tick(EntityPlayer player) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) return;

        ItemStack charm = ItemSwiftStep.findEquipped(player);
        if (charm == null) return;

        boolean cropAuraEnabled = ItemSwiftStep.isGrowthAuraEnabled(charm);
        boolean animalAuraEnabled = ItemSwiftStep.isAnimalAuraEnabled(charm);
        if (!cropAuraEnabled && !animalAuraEnabled) return;

        World world = player.worldObj;
        int radius = ItemSwiftStep.getGrowthAuraRadius(charm);
        int growthSpeed = ItemSwiftStep.getGrowthAuraSpeed(charm);
        int playerTickOffset = player.getUniqueID()
            .hashCode();

        if (cropAuraEnabled) {
            int cropTicks = scaledTicksThisTick(player.ticksExisted, growthSpeed, TICK_INTERVAL, playerTickOffset);
            if (cropTicks > 0) {
                // 把每 5 秒的一批更新均匀摊到各个 tick，倍率高时避免集中卡顿。
                tickCrops(world, player, radius, cropTicks);
            }
        }

        if (animalAuraEnabled) {
            int animalInterval = Math.max(1, TICK_INTERVAL / growthSpeed);
            if (isPulse(player.ticksExisted, animalInterval, playerTickOffset)) {
                tickAnimals(world, player, radius, growthSpeed, animalInterval);
            }
        }
    }

    private static boolean isPulse(int playerTicks, int interval, int playerTickOffset) {
        return Math.floorMod(playerTicks, interval) == Math.floorMod(playerTickOffset, interval);
    }

    private static int scaledTicksThisTick(int playerTicks, int speed, int baseInterval, int tickOffset) {
        long current = ((long) playerTicks + tickOffset) * speed;
        long previous = ((long) playerTicks - 1L + tickOffset) * speed;
        return (int) (Math.floorDiv(current, baseInterval) - Math.floorDiv(previous, baseInterval));
    }

    private static void tickCrops(World world, EntityPlayer player, int radius, int growthTicks) {
        int radiusSquared = radius * radius;
        int centerX = MathHelper.floor_double(player.posX);
        int centerY = MathHelper.floor_double(player.posY);
        int centerZ = MathHelper.floor_double(player.posZ);

        for (int dx = -radius; dx <= radius; dx++) {
            int dxSquared = dx * dx;
            if (dxSquared > radiusSquared) continue;

            for (int dz = -radius; dz <= radius; dz++) {
                int remaining = radiusSquared - dxSquared - dz * dz;
                if (remaining < 0) continue;
                int x = centerX + dx;
                int z = centerZ + dz;
                // 只检查一次区块是否已加载，避免每个 y 坐标都重复查区块。
                if (!world.blockExists(x, 0, z)) continue;
                int verticalRadius = (int) Math.sqrt(remaining);

                for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                    int y = centerY + dy;
                    if (y < 0 || y >= 256) continue;

                    Block block = world.getBlock(x, y, z);
                    if (isGrowthBlock(block)) {
                        for (int tick = 0; tick < growthTicks && world.getBlock(x, y, z) == block; tick++) {
                            block.updateTick(world, x, y, z, world.rand);
                        }
                    }
                }
            }
        }
    }

    private static void tickAnimals(World world, EntityPlayer player, int radius, int growthSpeed, int interval) {
        AxisAlignedBB bounds = AxisAlignedBB.getBoundingBox(
            player.posX - radius,
            player.posY - radius,
            player.posZ - radius,
            player.posX + radius + 1.0D,
            player.posY + radius + 1.0D,
            player.posZ + radius + 1.0D);
        List<EntityAnimal> animals = world.getEntitiesWithinAABB(EntityAnimal.class, bounds);
        // 设定的强度表示额外增加的年龄进度：×1 时额外跳过一个自然计时周期，
        // 所以最低档也有约 2 倍速度；更高档继续叠加，避免开启光环但默认值完全没效果。
        int ageBoost = interval * growthSpeed;
        double radiusSquared = (double) radius * radius;

        for (EntityAnimal animal : animals) {
            double dx = animal.posX - player.posX;
            double dy = animal.posY - player.posY;
            double dz = animal.posZ - player.posZ;
            if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;

            // 生崽后的正数年龄是繁殖冷却，幼年动物的负数年龄是成年倒计时；
            // 两者都是 EntityAgeable 的 growingAge，服务端写回后会同步到客户端。
            int age = animal.getGrowingAge();
            if (age < 0) {
                animal.setGrowingAge(Math.min(0, age + ageBoost));
            } else if (age > 0) {
                animal.setGrowingAge(Math.max(0, age - ageBoost));
            }

            // 羊毛没有独立的再生计时器，原版在吃草时恢复；光环按强度定期令剪毛羊恢复羊毛。
            if (animal instanceof EntitySheep) {
                EntitySheep sheep = (EntitySheep) animal;
                if (sheep.getSheared()) sheep.setSheared(false);
            }
        }
    }

    private static boolean isGrowthBlock(Block block) {
        if (block == null || !block.getTickRandomly()) return false;
        // 这些方块也实现了 IGrowable，但随机刻度用于蔓延/外观变化，不属于农作物生长。
        if (block instanceof BlockGrass || block instanceof BlockTallGrass || block instanceof BlockDoublePlant) {
            return false;
        }
        return block instanceof IGrowable || block == Blocks.reeds
            || block == Blocks.cactus
            || block == Blocks.nether_wart;
    }
}
