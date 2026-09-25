package com.futa_gtnh.common;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.item.ItemSwiftStep;

/** 服务端处理迅步的生命与饱食度恢复。 */
public final class SwiftStepRecovery {

    private static final float HEALTH_PER_PULSE = 1.0F;
    private static final int FOOD_PER_PULSE = 1;
    private static final float SATURATION_MODIFIER = 0.25F;

    private SwiftStepRecovery() {}

    public static void tick(EntityPlayer player) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote || !player.isEntityAlive()) return;
        if (player.capabilities.isCreativeMode) return;

        ItemStack charm = ItemSwiftStep.findEquipped(player);
        if (charm == null) return;

        boolean healthEnabled = ItemSwiftStep.isHealthRecoveryEnabled(charm);
        boolean foodEnabled = ItemSwiftStep.isFoodRecoveryEnabled(charm);
        if (!healthEnabled && !foodEnabled) return;

        int interval = ItemSwiftStep.getRecoveryIntervalTicks(charm);
        if (player.ticksExisted % interval != 0) return;

        if (healthEnabled && player.getHealth() < player.getMaxHealth()) {
            player.heal(HEALTH_PER_PULSE);
        }
        if (foodEnabled) {
            player.getFoodStats()
                .addStats(FOOD_PER_PULSE, SATURATION_MODIFIER);
        }
    }
}
