package com.futa_gtnh.mixins;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.futa_gtnh.common.BioVatAreaOffset;

import bartworks.common.tileentities.multis.MTEBioVat;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/**
 * 细菌培养缸（Bacterial Vat）控制器朝上/朝下时，正面玻璃会在能源仓放下后被清掉
 * （GT-New-Horizons-Modpack#22498）。
 *
 * <p>
 * 原版的流体渲染区在控制器「背后方向」偏 2 格，偏移量由 {@code getXDir()/getZDir()}
 * （{@code getBackFacing().offsetX/Z * 2}）给出，却没有 Y 分量。朝上/朝下时背后方向是竖直的，
 * 这 2 格就丢了，渲染区整体向正面平移 2 格，覆盖到正面那层玻璃，周期性的
 * {@code setBlockToAir} 清场把玻璃一并删掉。水平朝向时 {@code offsetY == 0}，行为不变。
 *
 * <p>
 * 这里把这些方法里所有「渲染区坐标」用到的 {@code getYCoord()} 补上 {@code getBackFacing().offsetY * 2}。
 * 只在原版仍缺 Y 偏移时由 {@link FutaGtnhMixinPlugin} 放行，避免上游修复后重复偏移。
 */
@Mixin(value = MTEBioVat.class, remap = false)
public abstract class MixinBioVatVerticalFacing {

    @Redirect(
        method = { "sendPackagesOrRenewRenderer", "placeFluid", "removeFluid", "sendRenderPackets",
            "doAllVisualThings" },
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/interfaces/tileentity/IGregTechTileEntity;getYCoord()S",
            remap = false),
        require = 5,
        remap = false)
    private short futa$shiftVatAreaY(IGregTechTileEntity tile) {
        return BioVatAreaOffset.shiftedY(tile);
    }
}
