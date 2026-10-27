package com.futa_gtnh.me.logistics;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

import appeng.api.parts.IFacadeContainer;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.parts.LayerFlags;
import appeng.api.parts.SelectedPart;
import appeng.api.util.AEColor;
import appeng.api.util.DimensionalCoord;
import appeng.parts.AEBasePart;

/** A sealed host for one real AE part inside a legacy block; virtual parts cannot be removed or dropped. */
final class NativePartHost implements IPartHost {

    private final TileEntity tile;
    private final AEBasePart part;
    private final ForgeDirection side;
    private final Set<LayerFlags> flags = EnumSet.noneOf(LayerFlags.class);

    NativePartHost(TileEntity tile, ForgeDirection side, AEBasePart part) {
        this.tile = tile;
        this.side = side;
        this.part = part;
        part.setPartHostInfo(side, this, tile);
    }

    @Override
    public IFacadeContainer getFacadeContainer() {
        return null;
    }

    @Override
    public boolean canAddPart(ItemStack stack, ForgeDirection direction) {
        return false;
    }

    @Override
    public ForgeDirection addPart(ItemStack stack, ForgeDirection direction, EntityPlayer player) {
        return null;
    }

    @Override
    public IPart getPart(ForgeDirection direction) {
        return side == direction ? part : null;
    }

    @Override
    public void removePart(ForgeDirection direction, boolean suppressUpdate) {}

    @Override
    public void markForUpdate() {}

    @Override
    public DimensionalCoord getLocation() {
        return new DimensionalCoord(tile);
    }

    @Override
    public TileEntity getTile() {
        return tile;
    }

    @Override
    public AEColor getColor() {
        return AEColor.Transparent;
    }

    @Override
    public void clearContainer() {}

    @Override
    public boolean isBlocked(ForgeDirection direction) {
        return false;
    }

    @Override
    public SelectedPart selectPart(Vec3 position) {
        return new SelectedPart();
    }

    @Override
    public void markForSave() {
        tile.markDirty();
    }

    @Override
    public void partChanged() {
        tile.markDirty();
    }

    @Override
    public boolean hasRedstone(ForgeDirection direction) {
        return false;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public Set<LayerFlags> getLayerFlags() {
        return flags;
    }

    @Override
    public void cleanup() {}

    @Override
    public void notifyNeighbors() {}

    @Override
    public boolean isInWorld() {
        return tile.getWorldObj() != null && !tile.isInvalid();
    }
}
