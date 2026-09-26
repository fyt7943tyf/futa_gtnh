package com.futa_gtnh.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.common.GuiHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 「自选抽奖机」方块：右键打开抽奖界面。
 *
 * <p>
 * 玩法见 {@link TileEntityLootMachine}：放入战利品袋 + 时运附魔书，模拟开袋、
 * 最多 roll 三次，不满意可以花技术员代币重置次数，确定后领取。
 * 潜行右键不打开界面，让路给 GT 扳手等工具（和共享终端同一个约定）。
 */
public class BlockLootMachine extends BlockContainer {

    public static final String NAME = "loot_machine";

    @SideOnly(Side.CLIENT)
    private IIcon iconSide;
    @SideOnly(Side.CLIENT)
    private IIcon iconTop;
    @SideOnly(Side.CLIENT)
    private IIcon iconFront;

    public BlockLootMachine() {
        super(Material.iron);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setHardness(5.0F);
        setResistance(10.0F);
        setStepSound(Block.soundTypeMetal);
        setCreativeTab(CreativeTabs.tabMisc);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileEntityLootMachine();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (player.isSneaking()) return false;

        if (!world.isRemote) {
            player.openGui(FutaGtnhMod.instance, GuiHandler.GUI_LOOT_MACHINE, world, x, y, z);
        }
        return true;
    }

    /**
     * 挖掉时把槽内的袋子/书退给玩家。未领取的会话结果作废 ——
     * 结果是「这一个袋子的模拟产出」，袋子都退回了，白送结果等于无限刷。
     */
    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityLootMachine && !world.isRemote) {
            TileEntityLootMachine machine = (TileEntityLootMachine) tile;
            for (int slot = 0; slot < machine.getSizeInventory(); slot++) {
                ItemStack stack = machine.getStackInSlot(slot);
                if (stack != null) {
                    machine.setInventorySlotContents(slot, null);
                    dropBlockAsItem(world, x, y, z, stack);
                }
            }
        }
        super.breakBlock(world, x, y, z, block, meta);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void registerBlockIcons(IIconRegister register) {
        iconSide = register.registerIcon(FutaGtnhMod.MODID + ":" + NAME + "_side");
        iconTop = register.registerIcon(FutaGtnhMod.MODID + ":" + NAME + "_top");
        iconFront = register.registerIcon(FutaGtnhMod.MODID + ":" + NAME + "_front");
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IIcon getIcon(int side, int metadata) {
        // side: 0=下, 1=上, 其余=四个水平面。
        // 方块没有朝向元数据，四个水平面都用「出货口正面」贴图，看起来才像台机器
        if (side == 1) return iconTop;
        if (side == 0) return iconSide;
        return iconFront;
    }
}
