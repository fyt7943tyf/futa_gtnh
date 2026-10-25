package com.futa_gtnh.supply;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

/** Stable inlet identity. No world, grid, tile or player references survive in the ledger. */
public final class SupplySource {

    public static final int UNKNOWN = 0, AE_BUS = 1, IO = 2, AE_MACHINE = 3, PLAYER = 4;
    public static final SupplySource UNIDENTIFIED = new SupplySource(UNKNOWN, 0, 0, 0, 0, -1, "");
    public final int kind, dim, x, y, z, side;
    public final String player;
    private final String id;

    public SupplySource(int kind, int dim, int x, int y, int z, int side, String player) {
        this.kind = kind;
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
        this.player = player == null ? "" : player;
        id = kind + ":" + dim + ":" + x + ":" + y + ":" + z + ":" + side + ":" + this.player;
    }

    public static SupplySource at(int kind, TileEntity tile, ForgeDirection side) {
        if (tile == null || tile.getWorldObj() == null) return UNIDENTIFIED;
        return new SupplySource(
            kind,
            tile.getWorldObj().provider.dimensionId,
            tile.xCoord,
            tile.yCoord,
            tile.zCoord,
            side == null ? -1 : side.ordinal(),
            "");
    }

    public String id() {
        return id;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("kind", kind);
        tag.setInteger("dim", dim);
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        tag.setInteger("side", side);
        tag.setString("player", player);
        return tag;
    }

    public static SupplySource read(NBTTagCompound tag) {
        return new SupplySource(
            tag.getInteger("kind"),
            tag.getInteger("dim"),
            tag.getInteger("x"),
            tag.getInteger("y"),
            tag.getInteger("z"),
            tag.getInteger("side"),
            tag.getString("player"));
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SupplySource && id.equals(((SupplySource) other).id);
    }
}
