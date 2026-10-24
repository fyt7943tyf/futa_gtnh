package com.futa_gtnh.common;

import com.futa_gtnh.mixins.FutaGtnhMixinPlugin;

/** Optional integrations must not link Botania or CropsNH classes on a server without them. */
public final class GrowthAbsentRegression {
    public static void main(String[] args) throws Exception {
        Thread.currentThread().setContextClassLoader(net.minecraft.launchwrapper.Launch.classLoader);
        for(String name:new String[]{"vazkii.botania.common.Botania","com.gtnewhorizon.cropsnh.api.ICropStickTile"}) {
            try { Class.forName(name); throw new AssertionError("optional dependency leaked: "+name); }
            catch(ClassNotFoundException expected) {}
        }
        if(new FutaGtnhMixinPlugin().shouldApplyMixin("vazkii.botania.common.block.subtile.functional.SubTileAgricarnation",
            "com.futa_gtnh.mixins.MixinAgricarnation")) throw new AssertionError("absent Botania Mixin must be skipped");
        SwiftStepGrowthAura.tickWorld(null);
        if(SwiftStepGrowthAura.canBoostCrop(null)) throw new AssertionError("absent target cannot grow");
        SwiftStepGrowthAura.clear();
        System.out.println("Swift Step optional-dependency regression passed");
    }
}
