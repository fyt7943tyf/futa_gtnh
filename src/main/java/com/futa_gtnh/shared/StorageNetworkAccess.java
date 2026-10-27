package com.futa_gtnh.shared;

import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;

/**
 * Network operations are server-thread-only. Native AE cells own spendable stock; the facade retains service metadata
 * only.
 */
public interface StorageNetworkAccess {

    long itemAmount(ItemKey key);

    long fluidAmount(FluidKey key);

    long insertItem(ItemKey key, long amount, boolean manual, boolean direct);

    long insertFluid(FluidKey key, long amount, boolean manual, boolean direct);

    long extractItem(ItemKey key, long amount, TileEntity excluded);

    long extractFluid(FluidKey key, long amount, TileEntity excluded);

    long itemSpace(ItemKey key, boolean manual);

    long fluidSpace(FluidKey key, boolean manual);

    List<Map.Entry<ItemKey, Long>> items();

    List<Map.Entry<FluidKey, Long>> fluids();

    int itemRevision();

    int fluidRevision();
}
