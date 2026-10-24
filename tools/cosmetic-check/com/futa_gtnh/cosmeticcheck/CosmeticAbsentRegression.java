package com.futa_gtnh.cosmeticcheck;

import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.mixins.FutaGtnhMixinPlugin;
import com.futa_gtnh.network.PacketCosmeticArmorIdentity;

/** No CAR jar and no client hook loading is required by the server-side integration. */
public final class CosmeticAbsentRegression {

    public static void main(String[] args) {
        if (new FutaGtnhMixinPlugin().shouldApplyMixin("lain.mods.cos.client.InventoryManagerClient",
            "com.futa_gtnh.mixins.MixinCosmeticArmorInventoryClient")) {
            throw new AssertionError("CAR hook must be skipped when its optional target is absent");
        }
        new CommonProxy().onCosmeticArmorIdentity(null, java.util.UUID.randomUUID());
        new PacketCosmeticArmorIdentity.Handler();
        System.out.println("Cosmetic armor optional dependency: absent-mod guard and common-side linkage passed.");
    }
}
