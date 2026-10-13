package com.futa_gtnh.aewireless;

import net.minecraft.item.Item;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 「AE 无线接收端」的覆盖板物品：右键贴到机器侧面（单方块机器 / 多方块主控制器，
 * 正面除外），潜行空手右键覆盖板打开配置界面。
 *
 * <p>
 * 物品本身不引用任何 AE / GT cover 类型 —— 行为注册在
 * {@code CommonProxy.postInit} 的 {@code CoverRegistry.registerCover} 里。
 */
public class ItemAeSignalReceiverCover extends Item {

    public static final String NAME = "ae_signal_receiver_cover";

    public ItemAeSignalReceiverCover() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        setMaxStackSize(16);
        setCreativeTab(net.minecraft.creativetab.CreativeTabs.tabMisc);
    }
}
