package com.futa_gtnh.stats;

/**
 * 由 mixin 注入到 GTSWN 无线覆盖版基类上的「流量探针」。
 *
 * <p>
 * mixin 把每个覆盖版每 tick 实际搬运的 EU 累加进一个计数器，
 * {@link CoverFlowTracker} 每个采样期来「取走并清零」—— 差分天然成立，
 * 不需要存上一拍的值。接口本身放在普通包里，mixin 类和追踪器都引用它。
 */
public interface CoverFlowProbe {

    /** 记一笔流量（EU）。 */
    void futa$addFlow(long eu);

    /** 取走自上次调用以来累计的流量并清零计数器。 */
    long futa$takeFlow();

    /** 覆盖版是否已完成配置（GTSWN 语义：未配置的覆盖版不参与输电）。 */
    boolean futa$isConfigured();
}
