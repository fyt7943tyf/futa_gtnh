package com.futa_gtnh.me;

import java.util.EnumSet;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;

/** Optional GTSWN hooks contain no references to GTSWN classes and are safe when it is absent. */
public final class GtswnQuantumCompat {

    public static final String PORT_NBT = "futaPublicMeQuantumPort";

    private GtswnQuantumCompat() {}

    public static boolean isPublicTarget(TileEntity tile) {
        return tile instanceof TilePublicMeAccess || tile instanceof TilePublicMeCore port && PublicMeNetwork.owns(port)
            || tile instanceof IGridHost host && PublicMeNetwork.isManagedController(host);
    }

    public static boolean canBind(TileEntity tile) {
        if (!PublicMeNetwork.isOnline()) return false;
        if (tile instanceof TilePublicMeAccess access) {
            IGridNode node = access.getActionableNode();
            return node != null && node.getGrid() == PublicMeNetwork.grid();
        }
        return isPublicTarget(tile);
    }

    /** Preserve only genuine managed ports; ordinary quantum controllers keep GTSWN's original filter. */
    public static void preservePorts(TileEntity tile, EnumSet<ForgeDirection> allowed) {
        if (!(tile instanceof IGridHost host) || !PublicMeNetwork.isManagedController(host)) return;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity neighbor = tile.getWorldObj()
                .getTileEntity(tile.xCoord + side.offsetX, tile.yCoord + side.offsetY, tile.zCoord + side.offsetZ);
            if (neighbor instanceof TilePublicMeCore port && PublicMeNetwork.owns(port)) allowed.add(side);
        }
    }

    public static int selectPort(int previous) {
        return previous >= 0 && previous < PublicMeNetwork.PORT_COUNT ? previous : PublicMeNetwork.choosePort();
    }

    public static IGridNode portNode(int port) {
        return PublicMeNetwork.isAttachable() ? PublicMeNetwork.portNode(port) : null;
    }

    public static boolean hasForeignController(IGridNode node) {
        return node != null && TilePublicMeAccess.hasForeignController(node);
    }
}
