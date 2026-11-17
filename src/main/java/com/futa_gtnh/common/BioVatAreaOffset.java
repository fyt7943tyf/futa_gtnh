package com.futa_gtnh.common;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Y offset of the Bacterial Vat fluid area: 2 blocks behind the controller, which is vertical for UP/DOWN. */
public final class BioVatAreaOffset {

    private BioVatAreaOffset() {}

    public static short shiftedY(IGregTechTileEntity tile) {
        return (short) (tile.getYCoord() + tile.getBackFacing().offsetY * 2);
    }
}
