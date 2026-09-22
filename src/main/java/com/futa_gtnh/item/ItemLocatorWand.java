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
 * 寻物魔杖：右键选一个方块，告诉你最近的它在哪，还能传送过去。
 *
 * <p>
 * 这个物品类本身很薄 —— 它只是个「触发入口」。真正的逻辑分在几个地方，
 * 因为它们跑在不同的端上：
 *
 * <ul>
 * <li>{@code client.BlockIndex}：客户端把所有方块编号成一个可搜索的列表
 * （几万条，分帧构建）；</li>
 * <li>{@code client.GuiLocatorWand}：选择界面；</li>
 * <li>{@code locator.LocatorScan} + {@code locator.LocatorManager}：<b>服务端</b>
 * 的增量扫描，客户端只负责发「我要找这个」；</li>
 * <li>{@code locator.TeleportHelper}：服务端的安全落点搜索；</li>
 * <li>{@code client.LocatorBeamRenderer}：客户端的世界渲染，画出那道光束。</li>
 * </ul>
 *
 * <p>
 * 为什么扫描非要放服务端：客户端只有自己视野附近的区块，想找 64 格外的方块
 * 根本看不到。而且「最近的在哪」这件事必须由权威方回答，否则改个客户端
 * 就能让魔杖指向一个不存在的坐标，然后传送到虚空里去。
 */
public class ItemLocatorWand extends Item {

    public static final String NAME = "locator_wand";

    public ItemLocatorWand() {
        setUnlocalizedName(FutaGtnhMod.MODID + "." + NAME);
        setTextureName(FutaGtnhMod.MODID + ":" + NAME);
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabTools);
    }

    @Override
    public ItemStack onItemRightClick(ItemStack stack, World world, EntityPlayer player) {
        if (player.isSneaking()) {
            // 潜行右键 = 清掉当前追踪，不用特地去开界面。
            //
            // 这件事<b>整个交给客户端</b>：要清的本地状态（光束）在客户端，
            // 要让服务端停下来的取消包也只能由客户端发（服务端 sendToServer 是没意义的）。
            // 所以走代理，客户端做两件事，服务端这里什么都不做 ——
            // 服务端那侧的状态会在取消包到达时同步清掉。
            if (world.isRemote) {
                FutaGtnhMod.proxy.clearLocatorTracking();
            }
            return stack;
        }

        if (world.isRemote) {
            // 走代理而不是直接 new 客户端界面类，见 CommonProxy#openLocatorGui 的说明
            FutaGtnhMod.proxy.openLocatorGui();
        }
        return stack;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        tooltip
            .add(EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.locator_wand.tip.gui"));
        tooltip.add(
            EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal("item.futa_gtnh.locator_wand.tip.clear"));
    }
}
