package com.futa_gtnh.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.PacketProductionTerminalUse;

public final class ItemProductionTerminal extends Item {

    public static final String NAME = "production_terminal";

    public ItemProductionTerminal() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":reactor_layout_tool");
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabTools);
    }

    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (player == null || !player.isSneaking()) return false;
        if (world.isRemote) {
            FutaGtnhMod.proxy
                .useProductionTerminal(new PacketProductionTerminalUse(x, y, z, side, hitX, hitY, hitZ), player);
        }
        return true;
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote && !player.isSneaking()) {
            FutaGtnhMod.proxy.useProductionTerminal(new PacketProductionTerminalUse(), player);
        }
        return stack;
    }

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        tooltip.add(StatCollector.translateToLocal("futa_gtnh.productionTerminal.tip.bind"));
        tooltip.add(StatCollector.translateToLocal("futa_gtnh.productionTerminal.tip.open"));
        tooltip.add(StatCollector.translateToLocal("futa_gtnh.productionTerminal.tip.permission"));
    }
}
