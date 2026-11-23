package com.futa_gtnh.miner;

import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.covers.CoverPlacer;
import gregtech.api.covers.CoverRegistry;
import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.render.TextureFactory;
import gregtech.api.util.GTModHandler;
import gregtech.client.iconContainers.blocks.GTCustomBlockIconContainer;

/** 注册矿机部署器、AE 采矿枢纽覆盖板与配方，并为旧存档保留维护仓 MTE ID。 */
public final class MinerRegistration {

    private static ItemMinerDeployer deployer;
    private static ItemCoverMinerIo cover;

    private MinerRegistration() {}

    public static void register() {
        registerLegacyMaintenanceHatch();
        if (!Config.enableMinerDeployer) return;
        if (!com.futa_gtnh.ae2.Ae2Compat.isAvailable()) {
            FutaGtnhMod.LOG.info("没有检测到 Applied Energistics 2，跳过矿机一站式部署的注册");
            return;
        }

        deployer = new ItemMinerDeployer();
        GameRegistry.registerItem(deployer, ItemMinerDeployer.NAME);

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

        FutaGtnhMod.LOG.info("已注册矿机一站式部署（部署器 + AE 采矿枢纽覆盖板）");
    }

    /** 保留旧 ID 和本地化键，旧仓体由 GT 按普通维护仓加载。 */
    private static void registerLegacyMaintenanceHatch() {
        int id = Config.minerAutoMaintenanceMetaTileId;
        if (id < 1 || id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] != null) {
            throw new IllegalStateException(
                "Legacy maintenance hatch MTE ID conflict: " + id
                    + "; check minerAutoMaintenanceMetaTileId in futa_gtnh.cfg before starting the world");
        }
        new MTEHatchMaintenance(id, "futa_gtnh.free_maintenance.lv", "Legacy Maintenance Hatch", 1);
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
    }

    /** @return AE 采矿枢纽覆盖板物品；功能未启用时 null。 */
    public static ItemStack minerIoStack() {
        return cover == null ? null : new ItemStack(cover);
    }
}
