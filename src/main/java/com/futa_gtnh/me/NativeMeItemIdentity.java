package com.futa_gtnh.me;

import appeng.api.storage.data.IAEItemStack;

/** A private mutable definition with the same canonical AE identity, independent of the caller's definition. */
public interface NativeMeItemIdentity {

    IAEItemStack futa$copyIdentity();
}
