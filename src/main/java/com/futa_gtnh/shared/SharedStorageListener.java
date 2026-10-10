package com.futa_gtnh.shared;

/**
 * 共享存储的内容变更监听器。
 *
 * <p>
 * 为 AE2 存储元件的桥接引入：共享背包可能被很多条路径改动（终端界面、GT 管道、
 * 拾取自动入库、AE 网络自己……），AE 那边的桥需要知道「哪些条目变了」，
 * 才能把增量推给 ME 网络、让终端和发信器显示正确的数量 ——
 * 否则只能靠全表对拍，那正是这个功能想避免的开销。
 *
 * <p>
 * <b>回调发生在 {@link SharedStorage} 的锁内</b>，因此实现只允许做 O(1) 的
 * 入队动作：绝不能回调进任何可能再获取其它锁的逻辑（死锁风险），
 * 也不能做 IO、发包或任何昂贵的计算。真正的消费必须留到锁外的合适时机
 * （AE 桥的实现是每 tick 末批量冲洗）。
 *
 * <p>
 * 没有监听器时存储侧的开销是一次 {@code isEmpty()} 判断；不装 AE2 时
 * 监听器列表恒为空，本机制对现有行为零影响。
 */
public interface SharedStorageListener {

    /**
     * 某个物品条目的存量变了（存入或取出，且实际生效量大于 0）。
     *
     * @param key 变更的条目；实现方需要当前数量时应另行现查
     *            （{@link SharedStorage#getItemAmount}），这里不传数量
     *            是为了保持回调足够便宜
     */
    void onItemChanged(ItemKey key);

    /** 某个流体条目的存量变了，语义同 {@link #onItemChanged}。 */
    void onFluidChanged(FluidKey key);

    /**
     * 存储被<b>结构性替换</b>：换存档、{@code /futashared reload}、清空。
     * 旧实例上的所有条目视图全部作废，实现方应做一次全量重同步，
     * 而不是试图对拍出增量。
     */
    void onStorageReplaced();
}
