package com.futa_gtnh.item;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;

/**
 * 小游戏助手：右键打开全服共享的 lootgames 地牢清单。
 *
 * <p>
 * 和 {@link ItemLocatorWand} 一样只是个「触发入口」：右键（客户端）走代理开
 * {@code GuiMinigameHelper}，剩下的都在服务端 —— 候选点按世界种子推算
 * （{@code lootassist.LootgameFinder}），区块验证与共享标记在
 * {@code lootassist.LootassistManager}。潜行右键没有特殊含义
 * （和魔杖不同，这个清单不需要「取消追踪」—— 搜索任务自己会跑完）。
 */
public class ItemMinigameHelper extends Item {

    public static final String NAME = "minigame_helper";

    public ItemMinigameHelper() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabTools);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (world.isRemote) {
            // 走代理而不是直接 new 客户端界面类（见 CommonProxy#openMinigameHelperGui 的说明）
            FutaGtnhMod.proxy.openMinigameHelperGui();
        }
        return stack;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.minigame_helper.tip.gui"));
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.minigame_helper.tip.mark"));
    }
}
