package com.futa_gtnh.miner;

import gregtech.api.enums.SoundResource;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;

/**
 * 「免料自动维护仓」：GT 自动维护仓的免耗材版。
 *
 * <p>
 * GT 原版自动维护仓（meta 111）每次修复要从自己的 4 格里扣 4 胶带 + 2 润滑剂电池 +
 * 4 钢螺丝 + 2 HV 电路，没料就摆烂。本仓把 {@link MTEHatchMaintenance#isRecipeInputEqual}
 * 覆写成「永远成功、从不扣料」——控制器的 {@code checkMaintenance()} 循环对本仓照常
 * 调 {@code autoMaintainance()}（内部就是 isRecipeInputEqual(true)），修好后照常把
 * 六个工具位收割进控制器。其余行为（贴图、对齐、GUI）全部继承原版自动维护仓。
 *
 * <p>
 * 因为 {@code addToMachineList} 判定全是 {@code instanceof MTEHatchMaintenance}，
 * 本仓可以被一切收维护仓的多方块接受，不限于采矿场/油气钻机。
 */
public class MTEAutoMaintenanceHatch extends MTEHatchMaintenance {

    public MTEAutoMaintenanceHatch(int aID, String aName, String aNameRegional) {
        super(aID, aName, aNameRegional, 6, true);
    }

    protected MTEAutoMaintenanceHatch(String aName, int aTier, String[] aDescription, ITexture[][][] aTextures) {
        super(aName, aTier, aDescription, aTextures, true);
    }

    @Override
    public MetaTileEntity newMetaEntity(IGregTechTileEntity aTileEntity) {
        return new MTEAutoMaintenanceHatch(mName, mTier, mDescriptionArray, mTextures);
    }

    /**
     * 免料修复入口。{@code aDecreaseStacksizeBySuccess=true} 时父类语义是「消耗材料并修好」，
     * 这里改成「不消耗任何东西，直接把六个工具位拉满」——控制器随后的收割逻辑照常工作。
     * {@code false} 时是父类 {@code onPostTick} 的「有没有料」显示查询，同样回答成功，
     * 免得仓体上一直挂着缺料状态。
     */
    @Override
    public boolean isRecipeInputEqual(boolean aDecreaseStacksizeBySuccess) {
        if (aDecreaseStacksizeBySuccess) {
            mWrench = mScrewdriver = mSoftMallet = mHardHammer = mSolderingTool = mCrowbar = true;
            setMaintenanceSound(SoundResource.GT_MAINTENANCE_AUTO_HATCH, 1.0F, 1.0F);
        }
        return true;
    }

    @Override
    public String[] getDescription() {
        String[] desc = new String[mDescriptionArray.length + 1];
        System.arraycopy(mDescriptionArray, 0, desc, 0, mDescriptionArray.length);
        desc[mDescriptionArray.length] = "Repairs automatically and consumes nothing.";
        return desc;
    }
}
