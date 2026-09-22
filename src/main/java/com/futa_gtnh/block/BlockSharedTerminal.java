package com.futa_gtnh.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 「共享终端」方块：右键打开全服共享存储界面。
 *
 * <p>
 * 它同时还是 GT 管道/泵的对接点 —— 见 {@link TileEntitySharedTerminal}。
 */
public class BlockSharedTerminal extends BlockContainer {

    public static final String NAME = "shared_terminal";

    @SideOnly(Side.CLIENT)
    private IIcon iconSide;
    @SideOnly(Side.CLIENT)
    private IIcon iconTop;

    public BlockSharedTerminal() {
        super(Material.iron);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setHardness(5.0F);
        setResistance(10.0F);
        setStepSound(Block.soundTypeMetal);
        setCreativeTab(CreativeTabs.tabMisc);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileEntitySharedTerminal();
    }

    /**
     * 右键打开界面。
     *
     * <p>
     * 潜行时直接返回 false：GT 的扳手、以及其他模组的「潜行右键」工具
     * 都是走 {@code onItemUseFirst} / 潜行判定，这里让路它们才能正常工作。
     */
    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (player.isSneaking()) return false;

        if (!world.isRemote) {
            TileEntity tile = world.getTileEntity(x, y, z);
            TileEntitySharedTerminal terminal = tile instanceof TileEntitySharedTerminal
                ? (TileEntitySharedTerminal) tile
                : null;
            CommonProxy.openSharedStorage(player, terminal);
        }
        return true;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void registerBlockIcons(IIconRegister register) {
        iconSide = register.registerIcon(FutaGtnhMod.MODID + ":" + NAME + "_side");
        iconTop = register.registerIcon(FutaGtnhMod.MODID + ":" + NAME + "_top");
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IIcon getIcon(int side, int metadata) {
        // side: 0=下, 1=上, 2=北, 3=南, 4=西, 5=东
        return side == 1 ? iconTop : iconSide;
    }
}
