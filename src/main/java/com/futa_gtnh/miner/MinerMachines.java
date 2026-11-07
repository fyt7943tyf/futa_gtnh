package com.futa_gtnh.miner;

import net.minecraft.item.ItemStack;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.util.GTOreDictUnificator;
import gregtech.common.tileentities.machines.multi.MTEOilDrillBase;
import gregtech.common.tileentities.machines.multi.MTEOreDrillingPlantBase;

/**
 * 采矿场 / 油气钻机的机型识别与物料表。
 *
 * <p>
 * GT 的 {@code getCasingBlockItem()} / {@code getFrameMaterial()} / {@code getMinTier()}
 * 全是 protected，这里按具体类维护一张静态表（9 个机型，GT5u 5.09.54 核对过）。
 * 外壳形状见 {@link MinerStructure}。
 */
public final class MinerMachines {

    private MinerMachines() {}

    /** 机型家族：决定仓位布局（采矿场要输入仓+输出总线，油气钻机要输出仓）。 */
    public enum Family {

        /** 采矿场：矿物（物品）产出。 */
        ORE_DRILL,

        /** 油气钻机：油气（流体）产出。 */
        OIL_DRILL;

        public static final Family[] VALUES = values();
    }

    /** 一种机型的建造参数。 */
    public static final class Spec {

        public final Family family;
        public final ItemList casing;
        public final Materials frame;
        /** 能源仓的最低档位（GT 的 getMinTier）。 */
        public final int minTier;

        Spec(Family family, ItemList casing, Materials frame, int minTier) {
            this.family = family;
            this.casing = casing;
            this.frame = frame;
            this.minTier = minTier;
        }
    }

    /**
     * 识别控制器是不是受支持的钻机家族。
     *
     * @return null = 不是采矿场/油气钻机控制器
     */
    public static Spec identify(MetaTileEntity mte) {
        if (mte instanceof MTEOreDrillingPlantBase) {
            return new Spec(Family.ORE_DRILL, casingOfOreDrill(mte), frameOfOreDrill(mte), minTierOfOreDrill(mte));
        }
        if (mte instanceof MTEOilDrillBase) {
            return new Spec(Family.OIL_DRILL, casingOfOilDrill(mte), frameOfOilDrill(mte), minTierOfOilDrill(mte));
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 每机型的静态参数表（按内部名字路由；GT5u 5.09.54 核对过）
    // ------------------------------------------------------------------

    /** 采矿场 1-4 的外壳：SolidSteel / Titanium / TungstenSteel / Osmiridium。 */
    private static ItemList casingOfOreDrill(MetaTileEntity mte) {
        String name = mte.getMetaName();
        if (name.contains("oredrill1")) return ItemList.Casing_SolidSteel;
        if (name.contains("oredrill2")) return ItemList.Casing_StableTitanium;
        if (name.contains("oredrill3")) return ItemList.Casing_RobustTungstenSteel;
        return ItemList.Casing_MiningOsmiridium;
    }

    /** 油气钻机 1-4 + 无尽的外壳：SolidSteel / CleanStainlessSteel / Titanium / TungstenSteel / Neutronium。 */
    private static ItemList casingOfOilDrill(MetaTileEntity mte) {
        String name = mte.getMetaName();
        if (name.contains("oildrill1")) return ItemList.Casing_SolidSteel;
        if (name.contains("oildrill2")) return ItemList.Casing_CleanStainlessSteel;
        if (name.contains("oildrill3")) return ItemList.Casing_StableTitanium;
        if (name.contains("oildrill4")) return ItemList.Casing_RobustTungstenSteel;
        return ItemList.Casing_MiningNeutronium;
    }

    private static Materials frameOfOreDrill(MetaTileEntity mte) {
        String name = mte.getMetaName();
        if (name.contains("oredrill1")) return Materials.Steel;
        if (name.contains("oredrill2")) return Materials.Titanium;
        if (name.contains("oredrill3")) return Materials.TungstenSteel;
        return Materials.Osmiridium;
    }

    private static Materials frameOfOilDrill(MetaTileEntity mte) {
        String name = mte.getMetaName();
        if (name.contains("oildrill1")) return Materials.Steel;
        if (name.contains("oildrill2")) return Materials.StainlessSteel;
        if (name.contains("oildrill3")) return Materials.Titanium;
        if (name.contains("oildrill4")) return Materials.TungstenSteel;
        return Materials.Neutronium;
    }

    private static int minTierOfOreDrill(MetaTileEntity mte) {
        String name = mte.getMetaName();
        if (name.contains("oredrill1")) return 2;
        if (name.contains("oredrill2")) return 3;
        if (name.contains("oredrill3")) return 4;
        return 5;
    }

    private static int minTierOfOilDrill(MetaTileEntity mte) {
        String name = mte.getMetaName();
        if (name.contains("oildrillinfinite")) return 9;
        if (name.contains("oildrill1")) return 2;
        if (name.contains("oildrill2")) return 3;
        if (name.contains("oildrill3")) return 4;
        return 5;
    }

    // ------------------------------------------------------------------
    // 仓体物品查找
    // ------------------------------------------------------------------

    /**
     * 在 GT MTE 注册表里找<b>档位最低且 ≥ minTier</b> 的某类仓体物品。
     *
     * <p>
     * 仓体判定用 {@code instanceof}——第三方（GT++ 等）继承的同类仓体也会命中。
     * 找不到返回 null。
     */
    public static MetaTileEntity findHatch(Class<? extends MetaTileEntity> hatchClass, int minTier) {
        MetaTileEntity best = null;
        int bestTier = Integer.MAX_VALUE;
        IMetaTileEntity[] registry = GregTechAPI.METATILEENTITIES;
        for (int id = 1; id < registry.length; id++) {
            if (!(registry[id] instanceof MetaTileEntity mte)) continue;
            if (!(mte instanceof MTEHatch hatch) || !hatchClass.isInstance(mte)) continue;
            int tier = hatch.getTierForStructure();
            if (tier < minTier || tier >= bestTier) continue;
            best = mte;
            bestTier = tier;
        }
        return best;
    }

    /** @return 仓体原型物品（damage = MTE ID，正是 {@code ItemMachines} 放置时读的东西）。 */
    public static ItemStack stackOf(MetaTileEntity mte) {
        return mte == null ? null : mte.getStackForm(1);
    }

    /** @return 外壳方块物品；外壳 ItemList 尚未注册时为 null。 */
    public static ItemStack casingStack(Spec spec) {
        return spec.casing == null ? null : spec.casing.get(1);
    }

    /** @return 框架方块物品（frameGt 矿辞）。 */
    public static ItemStack frameStack(Spec spec) {
        return GTOreDictUnificator.get(OrePrefixes.frameGt, spec.frame, 1);
    }

    /**
     * 能源仓物品：档位 ≥ minTier 里最低的一档。
     *
     * @return null = 玩家没登记过够档的能源仓（报告缺口）
     */
    public static ItemStack energyHatchStack(int minTier) {
        return stackOf(findHatch(MTEHatchEnergy.class, minTier));
    }

    public static ItemStack inputHatchStack() {
        return stackOf(findHatch(MTEHatchInput.class, 1));
    }

    public static ItemStack inputBusStack() {
        return stackOf(findHatch(MTEHatchInputBus.class, 1));
    }

    public static ItemStack outputBusStack() {
        return stackOf(findHatch(MTEHatchOutputBus.class, 1));
    }

    public static ItemStack outputHatchStack() {
        return stackOf(findHatch(MTEHatchOutput.class, 1));
    }

    /** @return 免料自动维护仓物品（随矿机部署器注册；未启用时 null）。 */
    public static ItemStack autoMaintenanceStack() {
        return MinerRegistration.autoMaintenanceStack();
    }
}
