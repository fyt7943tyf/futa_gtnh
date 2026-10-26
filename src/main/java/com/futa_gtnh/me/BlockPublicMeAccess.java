package com.futa_gtnh.me;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;

/** Connect ordinary ME cables on any face; sneaking cycles the actual public-controller port. */
public final class BlockPublicMeAccess extends BlockContainer {

    public static final String NAME = "public_me_access";

    public BlockPublicMeAccess() {
        super(Material.iron);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setBlockTextureName(FutaGtnhMod.MODID + ":ae_signal_transmitter");
        setHardness(5F);
        setResistance(10F);
        setStepSound(Block.soundTypeMetal);
        setCreativeTab(CreativeTabs.tabMisc);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TilePublicMeAccess();
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        if (!world.isRemote && placer instanceof EntityPlayer player
            && world.getTileEntity(x, y, z) instanceof TilePublicMeAccess access) {
            access.getProxy()
                .setOwner(player);
        }
    }

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbor) {
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof TilePublicMeAccess access
            && access.getActionableNode() != null)
            access.getActionableNode()
                .updateState();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (!world.isRemote && world.getTileEntity(x, y, z) instanceof TilePublicMeAccess access) {
            if (player.isSneaking()) access.nextPort();
            player.addChatMessage(new ChatComponentText(access.status()));
        }
        return true;
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
        if (world.getTileEntity(x, y, z) instanceof TilePublicMeAccess access) {
            access.disconnect();
            PublicMeNetwork.unregister(access);
        }
        super.breakBlock(world, x, y, z, block, metadata);
    }
}
