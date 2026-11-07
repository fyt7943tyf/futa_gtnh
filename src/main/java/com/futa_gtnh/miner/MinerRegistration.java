package com.futa_gtnh.miner;

import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.covers.CoverPlacer;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.ITexture;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTModHandler;
import gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer;

/**
 * 矿机一站式部署的注册：部署器物品 + 免料自动维护仓 MTE（ID 冲突检查）+
 * AE 采矿枢纽覆盖板 + 合成配方。postInit 里注册（构造即写入 METATILEENTITIES），
 * 姿势与 {@code SuperDockRegistration} / {@code registerAeSignalWireless} 相同。
 */
public final class MinerRegistration {

    private static ItemMinerDeployer deployer;
    private static ItemCoverMinerIo cover;
    private static MTEAutoMaintenanceHatch maintenance;

    private MinerRegistration() {}

    public static void register() {
        if (!Config.enableMinerDeployer) return;
        if (!com.futa_gtnh.ae2.Ae2Compat.isAvailable()) {
            FutaGtnhMod.LOG.info("没有检测到 Applied Energistics 2，跳过矿机一站式部署的注册");
            return;
        }

        deployer = new ItemMinerDeployer();
        GameRegistry.registerItem(deployer, ItemMinerDeployer.NAME);

        if (Config.enableMinerAutoMaintenance) {
            int id = Config.minerAutoMaintenanceMetaTileId;
            if (id < 1 || id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] != null) {
                throw new IllegalStateException(
                    "Free auto maintenance hatch MTE ID conflict: " + id
                        + "; change minerAutoMaintenanceMetaTileId in futa_gtnh.cfg before starting the world");
            }
            maintenance = new MTEAutoMaintenanceHatch(
                id,
                "futa_gtnh.free_maintenance.lv",
                "Free Auto Maintenance Hatch");
        }

        cover = new ItemCoverMinerIo();
        GameRegistry.registerItem(cover, ItemCoverMinerIo.NAME);
        ITexture coverTexture = TextureFactory.of(GTCustomBlockIconContainer.create("futa_gtnh", "miner_io_cover"));
        CoverRegistry.registerCover(
            new ItemStack(cover),
            coverTexture,
            context -> new CoverMinerIo(context, coverTexture),
            CoverPlacer.builder()
                .onlyPlaceIf(CoverMinerIo::isCoverPlaceable)
                .build());

        FutaGtnhMod.LOG.info("已注册矿机一站式部署（部署器 + 免料自动维护仓 + AE 采矿枢纽覆盖板）");
    }

    public static void addCraftingRecipe() {
        if (deployer == null || !Config.enableRecipe) return;

        // 部署器：钢板 + 胶带 + 末影珍珠（跨空间取料）+ 基础电路 + 钢螺丝
        GTModHandler.addCraftingRecipe(
            new ItemStack(deployer),
            new Object[] { "PDP", "ECE", "PSP", 'P', "plateSteel", 'D', "craftingDuctTape", 'E', "enderpearl", 'C',
                "circuitBasic", 'S', "screwSteel" });

        if (cover != null) {
            // 采矿枢纽覆盖板：末影珍珠 + 红石 + 铁板
            GTModHandler.addCraftingRecipe(
                new ItemStack(cover),
                new Object[] { " E ", "RPR", 'E', "enderpearl", 'R', "dustRedstone", 'P', "plateIron" });
        }

        if (maintenance != null) {
            // 免料自动维护仓：钢板 + 胶带 + 螺丝 + MV 机器壳
            GTModHandler.addCraftingRecipe(
                maintenance.getStackForm(1),
                new Object[] { "PTP", "SHS", "PTP", 'P', "plateSteel", 'T', "craftingDuctTape", 'S', "screwSteel", 'H',
                    ItemList.Hull_MV.get(1) });
        }
    }

    /** @return AE 采矿枢纽覆盖板物品；功能未启用时 null。 */
    public static ItemStack minerIoStack() {
        return cover == null ? null : new ItemStack(cover);
    }

    /** @return 免料自动维护仓物品；功能未启用时 null。 */
    public static ItemStack autoMaintenanceStack() {
        return maintenance == null ? null : maintenance.getStackForm(1);
    }

    /** @return 免料自动维护仓原型 MTE；功能未启用时 null。 */
    public static MTEAutoMaintenanceHatch maintenanceMte() {
        return maintenance;
    }
}
