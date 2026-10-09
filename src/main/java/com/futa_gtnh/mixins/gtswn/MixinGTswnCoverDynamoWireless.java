package com.futa_gtnh.mixins.gtswn;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.futa_gtnh.stats.CoverFlowProbe;

import gregtech.api.metatileentity.BaseMetaTileEntity;

/**
 * 记录「动力无线覆盖版」每 tick 实际从机器抽走的 EU。
 *
 * <p>
 * 和能源侧同理：{@code decreaseStoredEU} 这条路绕过 GT 的输出计量，
 * 只有调用点上的实参才是真实流量。返回 false 表示这次没抽成，不计。
 */
@Pseudo
@Mixin(targets = "com.miaokatze.gtswn.common.covers.GTswn_Cover_DynamoWireless", remap = false)
public abstract class MixinGTswnCoverDynamoWireless {

    @Redirect(
        method = "doCoverThings",
        at = @At(value = "INVOKE", target = "Lgregtech/api/metatileentity/BaseMetaTileEntity;decreaseStoredEU(JZ)Z"),
        remap = false)
    private boolean futa$recordGeneratorFlow(BaseMetaTileEntity bmte, long amount, boolean ignoreTooLessEnergy) {
        boolean transferred = bmte.decreaseStoredEU(amount, ignoreTooLessEnergy);
        if (transferred && amount > 0L) ((CoverFlowProbe) this).futa$addFlow(amount);
        return transferred;
    }
}
