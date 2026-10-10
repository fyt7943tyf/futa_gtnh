package com.futa_gtnh.ae2;

import java.util.List;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.futa_gtnh.FutaGtnhMod;

/**
 * AE2 共享背包存储元件 —— 把全服共享背包接进 ME 网络的那个「元件」物品本身。
 *
 * <p>
 * <b>这个类刻意不引用任何 AE 类型</b>：元件的存储逻辑全部在
 * {@code SharedCellHandler} / {@code Shared*CellInventory}（那边才会 import appeng），
 * 物品这边只是一个普通 Item。这样 {@code FutaGtnhMod}、{@code CommonProxy}
 * 可以安全地持有它的静态字段引用，不用担心没装 AE2 的环境加载炸。
 *
 * <p>
 * <b>为什么是两个物品而不是一个双通道的</b>：GTNH fork 的 AE2 里，
 * ME 驱动器对每个元件槽位会按注册顺序遍历所有 stack type
 * （{@code AEStackTypeRegistry.getAllTypes()}），拿到第一个非 null 的
 * handler 就 {@code break}（{@code TileDrive.updateState}）。也就是说
 * <b>一个物理元件在一个网络里只能服务一个通道</b> —— 这是 AE2 的通道模型，
 * 不是本模组的选择。所以做成两个：物品通道 + 流体通道，
 * 和 AE2 自家「存储元件 / 流体存储元件」的两件套一致。
 */
public class ItemSharedStorageCell extends Item {

    /** 物品通道元件的注册名 / 贴图名。 */
    public static final String NAME_ITEM = "shared_cell_item";

    /** 流体通道元件的注册名 / 贴图名。 */
    public static final String NAME_FLUID = "shared_cell_fluid";

    private final boolean fluid;

    public ItemSharedStorageCell(boolean fluid) {
        this.fluid = fluid;
        String name = fluid ? NAME_FLUID : NAME_ITEM;
        setUnlocalizedName(FutaGtnhMod.MODID + "." + name);
        setTextureName(FutaGtnhMod.MODID + ":" + name);
        setMaxStackSize(1);
        setCreativeTab(CreativeTabs.tabTools);
    }

    /** @return true = 流体通道元件，false = 物品通道元件 */
    public boolean isFluidCell() {
        return fluid;
    }

    @Override
    @SuppressWarnings({ "unchecked", "rawtypes" })
    public void addInformation(ItemStack stack, EntityPlayer player, List tooltip, boolean advanced) {
        String key = fluid ? "item.futa_gtnh.shared_cell_fluid.tip" : "item.futa_gtnh.shared_cell_item.tip";
        tooltip.add(EnumChatFormatting.DARK_GRAY + StatCollector.translateToLocal(key));
    }
}
