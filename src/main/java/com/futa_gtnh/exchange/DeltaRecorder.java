package com.futa_gtnh.exchange;

import com.futa_gtnh.network.PacketStorageDelta;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;

/**
 * 把「存储变了哪些条目」记进增量包。
 *
 * <p>
 * 记的是变更后的<b>绝对值</b>而不是增减量：绝对值是幂等的，
 * 客户端漏收一条也会被下一条同名条目纠正回来。
 */
public final class DeltaRecorder {

    private final SharedStorage storage;
    private final PacketStorageDelta delta;

    public DeltaRecorder(SharedStorage storage, PacketStorageDelta delta) {
        this.storage = storage;
        this.delta = delta;
    }

    public void item(ItemKey key) {
        if (key == null || delta == null) return;
        delta.addItem(key, storage.getItemAmount(key));
    }

    public void fluid(FluidKey key) {
        if (key == null || delta == null) return;
        delta.addFluid(key, storage.getFluidAmount(key));
    }

    public boolean isActive() {
        return delta != null;
    }
}
