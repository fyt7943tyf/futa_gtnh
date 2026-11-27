package com.futa_gtnh.spacecenter;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.block.BlockSpaceCenterPart;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.util.GTModHandler;

/** 注册深空采矿指挥中心：结构方块 + 控制器 MTE + 星球种子捕获事件 + 配方。 */
public final class SpaceCenterRegistration {

    /** Galacticraft 的 modid。 */
    public static final String GC_MOD_ID = "GalacticraftCore";

    private static BlockSpaceCenterPart partBlock;
    private static MTESpaceMiningCenter controller;

    private SpaceCenterRegistration() {}

    /**
     * Galacticraft 是否在场。整个功能组唯一的门。
     *
     * <p>
     * <b>这个门必须放在不引用任何 {@code micdoodle8.*} 类型的类里。</b>JVM 链接 {@link GcCompat}
     * 时就会校验它的全部方法，而 {@code listReachableBodies} 把 {@code Planet} 传给形参为
     * {@code CelestialBody} 的方法，校验器为了判子类关系会立刻加载这两个类——GC 缺席时连
     * {@code GcCompat.isAvailable()} 自己都会抛 {@code NoClassDefFoundError}（实测），根本走不到
     * 「跳过」。所以先在这里用 modid 判断，确认在场之后才允许碰 {@link GcCompat}。
     */
    public static boolean isGalacticraftPresent() {
        return Loader.isModLoaded(GC_MOD_ID);
    }

    /** 功能组是否启用：配置开关 + Galacticraft 在场。 */
    public static boolean isEnabled() {
        return Config.enableSpaceMiningCenter && isGalacticraftPresent();
    }

    /** preInit：方块注册（必须在 preInit，稳定方块 id）。 */
    public static void registerBlocks() {
        if (!isEnabled()) return;
        partBlock = new BlockSpaceCenterPart();
        GameRegistry.registerBlock(partBlock, BlockSpaceCenterPart.NAME);
    }

    /** postInit：MTE 注册（构造即写入 GregTechAPI.METATILEENTITIES，仓库既有范式）。 */
    public static void register() {
        if (!Config.enableSpaceMiningCenter) return;
        if (!isGalacticraftPresent()) {
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
        for (String ore : new String[] { "plateIridium", "plateTungstenSteel", "plateSteel", "blockGlass", "enderpearl",
            "stickTungstenSteel" }) {
            warnIfMissingOre(ore);
        }

        // 控制器（UV 级）：UV 机器外壳 + 电路 + 铱板 + 天线 + 全息核心。
        //
        // 电路照 GT 自家 UV 机器配方的写法取「低一档」的 ZPM 电路，并且直接用 OrePrefixes 取物品：
        // 这个版本的 GT 没有 "circuitUV" 这个矿辞（启动告警实测为空），照字面写只会得到一条
        // 永远合不出来的配方。
        // OrePrefixes.get 的返回类型就是 Object：有对应物品给 ItemStack，没有就给矿辞名，配方数组原样收
        Object circuit = OrePrefixes.circuit.get(Materials.ZPM);
        ItemStack hull = ItemList.Hull_UV.get(1);
        if (controller != null) {
            if (circuit == null || hull == null) {
                FutaGtnhMod.LOG.warn("深空采矿指挥中心：取不到 UV 机器外壳或 ZPM 级电路，控制器配方未注册");
            } else {
                GTModHandler.addCraftingRecipe(
                    controller.getStackForm(1),
                    new Object[] { "PHP", "CKA", "IUI", 'P', "plateIridium", 'H', hull, 'C', circuit, 'K',
                        new ItemStack(partBlock, 1, BlockSpaceCenterPart.META_HOLO_CORE), 'A',
                        new ItemStack(partBlock, 1, BlockSpaceCenterPart.META_ANTENNA), 'I', "plateTungstenSteel", 'U',
                        circuit });
            }
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

    /**
     * 配方引用的矿辞名若在当前整合包里没人注册，配方不会报错、只会永远合不出来。
     * 启动时点名报出来，免得要到玩家合成时才发现。
     */
    private static void warnIfMissingOre(String oreName) {
        if (OreDictionary.getOres(oreName)
            .isEmpty()) {
            FutaGtnhMod.LOG.warn("深空采矿指挥中心：矿辞 {} 为空，引用它的合成配方当前不可用", oreName);
        }
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
