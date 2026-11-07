package com.futa_gtnh.miner;

import net.minecraft.item.Item;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 「AE 采矿枢纽」覆盖板物品：右键贴到采矿场/油气钻机的<b>主控制器</b>侧面
 * （正面除外），潜行空手右键打开发信器选择界面。
 *
 * <p>
 * 物品本身不引用任何 AE / GT cover 类型 —— 行为注册在
 * {@code MinerRegistration} 的 {@code CoverRegistry.registerCover} 里。
 */
public class ItemCoverMinerIo extends Item {

    public static final String NAME = "miner_io_cover";

    public ItemCoverMinerIo() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        setMaxStackSize(16);
        setCreativeTab(net.minecraft.creativetab.CreativeTabs.tabMisc);
    }
}
