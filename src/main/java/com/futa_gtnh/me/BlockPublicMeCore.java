package com.futa_gtnh.me;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;

/** Managed infrastructure; it is placed only by the server bootstrap and has no item drops. */
public final class BlockPublicMeCore extends BlockContainer {

    public static final String NAME = "public_me_core";

    public BlockPublicMeCore() {
        super(Material.iron);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setBlockTextureName(FutaGtnhMod.MODID + ":ae_signal_transmitter");
        setBlockUnbreakable();
        setResistance(6_000_000F);
        setStepSound(Block.soundTypeMetal);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TilePublicMeCore();
    }

    @Override
    public int quantityDropped(java.util.Random random) {
        return 0;
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (!world.isRemote) player.addChatMessage(new ChatComponentText(PublicMeNetwork.status()));
        return true;
    }
}
