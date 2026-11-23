package com.futa_gtnh.miner;

import java.util.function.ToIntFunction;

import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;

/** Drains pending products from a compatible GT ME output bus. */
public interface MinerMeOutputBus {

    int futa$drainPendingItems(int budget, ToIntFunction<IAEItemStack> insert);

    interface PendingCache<T extends IAEStack<T>> {

        int futa$drainPending(int budget, ToIntFunction<T> insert);
    }
}
