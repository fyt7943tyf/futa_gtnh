package com.futa_gtnh.mixins.gtswn;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.futa_gtnh.stats.CoverFlowProbe;

import gregtech.api.metatileentity.BaseMetaTileEntity;

/**
 * 记录「能源无线覆盖版」每 tick 实际注入机器的 EU。
 *
 * <p>
 * GTSWN 的能源覆盖版通过 {@code increaseStoredEnergyUnits} 直灌机器 ——
 * 这条路<b>绕过了</b> GT 的 {@code mAverageEUInput} 计量（那两个 getter 只在
 * {@code injectEnergyUnits} 等网络入口记账），所以现有的任何基于机器侧读数的
 * 采样都测不到它。挂在调用点上取实参，是唯一精确的位置。
 *
 * <p>
 * 只在调用<b>成功返回</b>时记账：{@code increaseStoredEnergyUnits} 返回 false
 * 表示这一次没有真正写入（机器满电且未忽略上限），不计入流量。
 */
@Pseudo
@Mixin(targets = "com.miaokatze.gtswn.common.covers.GTswn_Cover_EnergyWireless", remap = false)
public abstract class MixinGTswnCoverEnergyWireless {

    @Redirect(
        method = "doCoverThings",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/BaseMetaTileEntity;increaseStoredEnergyUnits(JZ)Z"),
        remap = false)
    private boolean futa$recordConsumerFlow(BaseMetaTileEntity bmte, long amount, boolean ignoreTooMuchEnergy) {
        boolean transferred = bmte.increaseStoredEnergyUnits(amount, ignoreTooMuchEnergy);
        if (transferred && amount > 0L) ((CoverFlowProbe) this).futa$addFlow(amount);
        return transferred;
    }
}
