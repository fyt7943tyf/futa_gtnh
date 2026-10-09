package com.futa_gtnh.stats;

import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;

/**
 * 搬运引擎一轮 tick 用的记账手柄。
 *
 * <p>
 * {@link IoFlowStats#recorderFor} 在统计关闭时返回 {@code null}，引擎里全部是
 * {@code if (recorder != null)} 式的判空 —— 关掉统计的节点一次方法调用的开销都不多付。
 *
 * <p>
 * 只在服务端主线程使用（引擎 tick 和 HTTP 侧的读快照都在主线程，见
 * {@link com.futa_gtnh.api.IoFlowStatsApi} 的线程契约）。
 */
public final class IoFlowRecorder {

    private final IoFlowStats.NodeRecord node;

    IoFlowRecorder(IoFlowStats.NodeRecord node) {
        this.node = node;
    }

    /** 记一笔物品搬运。{@code inbound} = 抽进共享存储。 */
    public void item(ItemKey key, boolean inbound, long amount) {
        node.record(key, false, inbound, amount);
    }

    /** 记一笔流体搬运（单位 mB）。{@code inbound} = 抽进共享存储。 */
    public void fluid(FluidKey key, boolean inbound, long amount) {
        node.record(key, true, inbound, amount);
    }
}
