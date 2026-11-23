package com.futa_gtnh.spacecenter;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.BlockSpaceCenterPart;

import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.util.GTModHandler;

/** 注册深空采矿指挥中心：结构方块 + 控制器 MTE + 星球种子捕获事件 + 配方。 */
public final class SpaceCenterRegistration {

    private static BlockSpaceCenterPart partBlock;
    private static MTESpaceMiningCenter controller;

    private SpaceCenterRegistration() {}

    /** preInit：方块注册（必须在 preInit，稳定方块 id）。 */
    public static void registerBlocks() {
        if (!Config.enableSpaceMiningCenter) return;
        partBlock = new BlockSpaceCenterPart();
        GameRegistry.registerBlock(partBlock, BlockSpaceCenterPart.NAME);
    }

    /** postInit：MTE 注册（构造即写入 GregTechAPI.METATILEENTITIES，仓库既有范式）。 */
    public static void register() {
        if (!Config.enableSpaceMiningCenter) return;
        if (!GcCompat.isAvailable()) {
            FutaGtnhMod.LOG.info("没有检测到 Galacticraft，跳过深空采矿指挥中心的注册");
            return;
        }
        int id = Config.spaceMiningCenterMetaTileId;
        if (id < 1 || id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] != null) {
            throw new IllegalStateException(
                "Space mining center MTE ID conflict: " + id
                    + "; check spaceMiningCenterMetaTileId in futa_gtnh.cfg before starting the world");
        }
        controller = new MTESpaceMiningCenter(
            id,
            "futa_gtnh.space_mining_center.uv",
            "Deep Space Mining Command Center");
        // 星球种子捕获（WorldEvent.Load）
        PlanetSeeds.register();
        FutaGtnhMod.LOG.info("已注册深空采矿指挥中心（7x7xN 多方块 + 虚拟采矿/钻机）");
    }

    public static void addCraftingRecipe() {
        if (partBlock == null || !Config.enableRecipe) return;

        // 控制器（UV 级）：UV 机器外壳 + UV 电路 + 铱板 + 天线 + 全息核心
        if (controller != null) {
            GTModHandler.addCraftingRecipe(
                controller.getStackForm(1),
                new Object[] { "PHP", "CKA", "IUI", 'P', "plateIridium", 'H',
                    gregtech.api.enums.ItemList.Hull_UV.get(1), 'C', "circuitUV", 'K',
                    new ItemStack(partBlock, 1, BlockSpaceCenterPart.META_HOLO_CORE), 'A',
                    new ItemStack(partBlock, 1, BlockSpaceCenterPart.META_ANTENNA), 'I', "plateTungstenSteel", 'U',
                    "circuitUV" });
        }

        // 采掘桁架：钢 + 钨钢
        GTModHandler.addCraftingRecipe(
            new ItemStack(partBlock, 4, BlockSpaceCenterPart.META_TRUSS),
            new Object[] { "STS", "T T", "STS", 'S', "plateSteel", 'T', "plateTungstenSteel" });

        // 全息投影核心：末影珍珠 + 蓝宝石玻璃 + 桁架
        GTModHandler.addCraftingRecipe(
            new ItemStack(partBlock, 1, BlockSpaceCenterPart.META_HOLO_CORE),
            new Object[] { "GEG", "ECE", "GEG", 'G', "blockGlass", 'E', "enderpearl", 'C',
                new ItemStack(partBlock, 1, BlockSpaceCenterPart.META_TRUSS) });

        // 信号天线阵列：钨钢杆 + 桁架
        GTModHandler.addCraftingRecipe(
            new ItemStack(partBlock, 1, BlockSpaceCenterPart.META_ANTENNA),
            new Object[] { " R ", " R ", "RCR", 'R', "stickTungstenSteel", 'C',
                new ItemStack(partBlock, 1, BlockSpaceCenterPart.META_TRUSS) });
    }

    /** @return 结构方块；未启用时 null。 */
    public static Block partBlockOrNull() {
        return partBlock;
    }

    static BlockSpaceCenterPart partBlock() {
        if (partBlock == null) throw new IllegalStateException("space center part block not registered");
        return partBlock;
    }
}
