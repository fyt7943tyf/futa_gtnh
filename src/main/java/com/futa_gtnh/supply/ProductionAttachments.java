package com.futa_gtnh.supply;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.parts.IPartHost;
import appeng.helpers.IInterfaceHost;
import appeng.parts.misc.PartStorageBus;

/** Inspect only the six faces of a bound interface; never initialize a dimension or load a chunk. */
final class ProductionAttachments {

    private ProductionAttachments() {}

    static Scan scan(SupplySource parent) {
        return scan(DimensionManager.getWorld(parent.dim), parent);
    }

    static Scan scan(World world, SupplySource parent) {
        if (world == null || world.isRemote
            || !parent.hasPosition()
            || parent.kind != SupplySource.AE_MACHINE
            || !world.blockExists(parent.x, parent.y, parent.z)) return null;
        TileEntity tile = world.getTileEntity(parent.x, parent.y, parent.z);
        boolean valid = tile != null && !tile.isInvalid()
            && (parent.side == -1 ? tile instanceof IInterfaceHost
                : tile instanceof IPartHost host
                    && host.getPart(ForgeDirection.getOrientation(parent.side)) instanceof IInterfaceHost);
        Scan result = new Scan(valid);
        if (!valid) return result;
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            if (parent.side >= 0 && parent.side != direction.ordinal()) continue;
            int x = parent.x + direction.offsetX, y = parent.y + direction.offsetY, z = parent.z + direction.offsetZ;
            ForgeDirection face = direction.getOpposite();
            SupplySource candidate = new SupplySource(SupplySource.AE_BUS, parent.dim, x, y, z, face.ordinal(), "");
            if (!candidate.hasPosition() || !world.blockExists(x, y, z)) continue;
            result.checked.add(candidate.id());
            TileEntity adjacent = world.getTileEntity(x, y, z);
            if (adjacent != null && !adjacent.isInvalid()
                && adjacent instanceof IPartHost host
                && host.getPart(face) instanceof PartStorageBus) result.buses.add(candidate);
        }
        return result;
    }

    static final class Scan {

        final boolean interfacePresent;
        final Set<String> checked = new HashSet<>();
        final List<SupplySource> buses = new ArrayList<>();

        Scan(boolean interfacePresent) {
            this.interfacePresent = interfacePresent;
        }
    }
}
