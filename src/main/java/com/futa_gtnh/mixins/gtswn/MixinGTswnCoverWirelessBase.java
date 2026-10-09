package com.futa_gtnh.mixins.gtswn;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.futa_gtnh.stats.CoverFlowProbe;

/**
 * 给 GTSWN 无线覆盖版基类挂上 {@link CoverFlowProbe}。
 *
 * <p>
 * <b>为什么用字符串 targets 而不是类字面量</b>：futa_gtnh 对 GTSWN 是纯运行时软依赖，
 * 编译期 classpath 上没有它 —— 类字面量会直接 NoClassDefFoundError。字符串目标在
 * mixin 应用期才解析，配合 {@code FutaGtnhMixinPlugin} 的 gtswn 探测，缺席时整组不应用。
 * {@code @Pseudo} 告诉注解处理器：目标类在编译期不存在是预期情况，不要报错。
 *
 * <p>
 * {@code configured} 是 GTSWN 基类的 protected 字段（公共序列化快照的一部分），
 * {@code @Shadow} 按名匹配即可。
 */
@Pseudo
@Mixin(targets = "com.miaokatze.gtswn.common.covers.GTswnCoverWirelessBase", remap = false)
public abstract class MixinGTswnCoverWirelessBase implements CoverFlowProbe {

    @Shadow(remap = false)
    protected boolean configured;

    @Unique
    private long futa$flowAccumulated;

    @Override
    public void futa$addFlow(long eu) {
        if (eu > 0L) futa$flowAccumulated += eu;
    }

    @Override
    public long futa$takeFlow() {
        long value = futa$flowAccumulated;
        futa$flowAccumulated = 0L;
        return value;
    }

    @Override
    public boolean futa$isConfigured() {
        return configured;
    }
}
