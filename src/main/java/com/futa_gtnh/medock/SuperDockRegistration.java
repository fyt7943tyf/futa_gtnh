package com.futa_gtnh.medock;

import java.io.File;

import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.util.GTModHandler;

/**
 * 「ME 超级对接站」的注册：MTE ID 冲突检查 + 合成配方。
 * 拆解机同款姿势 —— postInit 里注册（构造即写入 METATILEENTITIES）。
 */
public final class SuperDockRegistration {

    private static MTEAeSuperDock prototype;

    private SuperDockRegistration() {}

    public static void register(File configDirectory) {
        if (!Config.enableAeSuperDock) return;
        if (!com.futa_gtnh.ae2.Ae2Compat.isAvailable()) {
            com.futa_gtnh.FutaGtnhMod.LOG.info("没有检测到 Applied Energistics 2，跳过 ME 超级对接站的注册");
            return;
        }
        int id = Config.dockMetaTileId;
        if (id < 1 || id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] != null)
            throw new IllegalStateException(
                "ME super dock MTE ID conflict: " + id
                    + "; change dockMetaTileId in futa_gtnh.cfg before starting the world");
        prototype = new MTEAeSuperDock(id);
    }

    public static ItemStack stack() {
        return prototype == null ? null : prototype.getStackForm(1);
    }

    public static void addCraftingRecipe() {
        if (prototype == null || !Config.enableRecipe) return;
        // 机器壳 + 钢板 + 末影珍珠 + 箱子：一台 LV 级的「容器码头」
        GTModHandler.addCraftingRecipe(
            stack(),
            new Object[] { "PEP", "CHC", "PSP", 'P', "plateSteel", 'E', "enderpearl", 'C', "chestWood", 'H',
                ItemList.Hull_LV.get(1), 'S', "circuitBasic" });
    }
}
