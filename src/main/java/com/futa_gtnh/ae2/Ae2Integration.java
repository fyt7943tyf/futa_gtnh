package com.futa_gtnh.ae2;

import com.futa_gtnh.FutaGtnhMod;

/**
 * AE2 联动的装配点 —— <b>只有确认 AE2 在场之后才会被加载</b>
 * （{@code CommonProxy.registerAe2Cells} 里的 {@code Ae2Compat.isAvailable()} 守卫）。
 * 本模组声明了 {@code after:appliedenergistics2}，preInit 一定排在 AE2 之后，
 * 它的注册表此刻已经就绪。
 */
public final class Ae2Integration {

    private Ae2Integration() {}

    /**
     * 安装全部 AE2 侧设施：cell handler ×2（物品/流体通道）、
     * 共享存储变更监听器、以及 tick / 停服两个钩子。
     * 幂等，重复调用无副作用。
     */
    public static void install() {
        AeCellBridge.install();
        SharedCellHandler.registerBoth();
        Ae2Compat.installHooks(AeCellBridge::onServerTickEnd, AeCellBridge::reset);
        FutaGtnhMod.LOG.info("AE2 共享背包存储元件已注册（物品 + 流体通道）");
    }
}
