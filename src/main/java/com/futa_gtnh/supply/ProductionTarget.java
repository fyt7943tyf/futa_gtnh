package com.futa_gtnh.supply;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.ae2.AeSupplyTracking;
import com.futa_gtnh.block.BlockIoNode;
import com.futa_gtnh.block.TileEntityIoNode;
import com.futa_gtnh.block.TileEntitySharedTerminal;

import appeng.api.networking.security.IActionHost;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.PartItemStack;
import appeng.api.parts.SelectedPart;
import appeng.helpers.IInterfaceHost;
import appeng.me.helpers.IGridProxyable;
import appeng.parts.automation.PartBaseExportBus;
import appeng.parts.automation.PartBaseImportBus;
import appeng.parts.misc.PartStorageBus;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Resolves a physical IO without scanning a grid or requiring an earlier transfer. */
public final class ProductionTarget {

    public final SupplySource source;
    public final TileEntity tile;
    public final Object identity;
    public final String name;
    public final int face;

    private ProductionTarget(SupplySource source, TileEntity tile, Object identity, String name, int face) {
        this.source = source;
        this.tile = tile;
        this.identity = identity;
        this.name = name == null ? "" : name.substring(0, Math.min(96, name.length()));
        this.face = face;
    }

    public static ProductionTarget hit(World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ,
        EntityPlayer player) {
        if (world == null || !world.blockExists(x, y, z) || side < 0 || side >= 6) return null;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityIoNode && world.getBlock(x, y, z) instanceof BlockIoNode node)
            side = node.interactionFace(world, x, y, z, player, side);
        else if (tile instanceof IPartHost host) {
            SelectedPart selected = host.selectPart(Vec3.createVectorHelper(hitX, hitY, hitZ));
            if (selected == null || selected.facade != null
                || selected.part == null
                || selected.side == null
                || selected.side == ForgeDirection.UNKNOWN) return null;
            side = selected.side.ordinal();
        }
        return atFace(world, x, y, z, side);
    }

    public static ProductionTarget atFace(World world, int x, int y, int z, int side) {
        if (world == null || !world.blockExists(x, y, z) || side < 0 || side >= 6) return null;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile == null || tile.isInvalid()) return null;
        ForgeDirection face = ForgeDirection.getOrientation(side);
        String name = world.getBlock(x, y, z)
            .getLocalizedName();
        if (tile instanceof TileEntityIoNode || tile instanceof TileEntitySharedTerminal)
            return new ProductionTarget(SupplySource.at(SupplySource.IO, tile, face), tile, tile, name, side);
        if (tile instanceof IPartHost host) {
            IPart part = host.getPart(face);
            if (!(part instanceof IActionHost action)
                || !(part instanceof PartBaseImportBus || part instanceof PartBaseExportBus
                    || part instanceof PartStorageBus
                    || part instanceof IInterfaceHost))
                return null;
            ItemStack representation = part.getItemStack(PartItemStack.Network);
            if (representation != null) name = representation.getDisplayName();
            SupplySource source = AeSupplyTracking.sourceFor(action);
            return source.hasPosition() ? new ProductionTarget(source, tile, part, name, side) : null;
        }
        if (tile instanceof IInterfaceHost && tile instanceof IActionHost action) {
            SupplySource source = AeSupplyTracking.sourceFor(action);
            return source.hasPosition() ? new ProductionTarget(source, tile, tile, name, side) : null;
        }
        if (tile instanceof IGregTechTileEntity gt && gt.getMetaTileEntity() instanceof IGridProxyable) {
            ItemStack representation = gt.getMetaTileEntity()
                .getStackForm(1);
            if (representation != null) name = representation.getDisplayName();
            return new ProductionTarget(
                SupplySource.at(SupplySource.AE_MACHINE, tile, null),
                tile,
                gt.getMetaTileEntity(),
                name,
                side);
        }
        return null;
    }

    public boolean matches(World world) {
        ProductionTarget current = atFace(world, source.x, source.y, source.z, face);
        return current != null && current.tile == tile && current.identity == identity && current.source.equals(source);
    }
}
