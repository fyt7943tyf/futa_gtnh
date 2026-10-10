package com.futa_gtnh.ae2;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.ISaveProvider;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.api.storage.data.IItemList;

/**
 * 共享背包元件的 {@code IMEInventoryHandler} 实现 —— AE 网络和共享存储之间那层桥。
 * 物品/流体两个通道各有一个子类，差别只在「键怎么算、栈怎么造、调存储的哪组方法」，
 * 存取语义、限流、增量广播这些公共逻辑都在这里。
 *
 * <p>
 * <b>语义要点</b>（对应 AE2 的约定，返回值错了就是刷/丢物品）：
 * <ul>
 * <li>{@code injectItems} 返回<b>没存进去的剩余</b>，null = 全部收下；
 * SIMULATE 绝不写库，只用 {@code itemSpaceLeft} 一类预检算可收量。</li>
 * <li>{@code extractItems} 返回<b>实际取到的</b>，null = 一个都没有；
 * SIMULATE 只读存量。</li>
 * <li>{@code getAvailableItem}（单类型查询）必须覆写成 O(1) 直查 ——
 * 接口的默认实现是全表扫描，那是性能大坑。</li>
 * </ul>
 *
 * <p>
 * 写入走<b>自动化路径</b>（{@code insertItem/insertFluid}，遵守共享背包的
 * 「存量上限」），和 GT 管道 / 漏斗 / 终端主动搬运同一待遇；
 * {@code Config.ae2CellObeyLimits = false} 时切到手动路径（视同玩家放入）。
 * 每次 MODULATE 生效后调 {@link #broadcastChange}，让开着共享终端界面的玩家
 * 同步看到 AE 侧的进出的另一边。
 */
abstract class AbstractSharedCellInventory<StackType extends IAEStack<StackType>, Key>
    implements IMEInventoryHandler<StackType> {

    /** 所属的元件物品。它的对象身份就是注册表里的元件身份。 */
    final ItemStack cellItem;

    /**
     * 宿主（ME 驱动器 / ME 箱子）。
     * {@code Platform.postChanges} 在元件插拔差分时会以 host == null 新建 handler，
     * 所以这里可能为 null —— 那种实例只被问一次内容，然后就被丢弃。
     */
    final ISaveProvider host;

    private final IGrid grid;

    protected AbstractSharedCellInventory(ItemStack cellItem, ISaveProvider host) {
        this.cellItem = cellItem;
        this.host = host;
        this.grid = resolveGrid(host);
    }

    /** 注册表用：这个元件挂在哪个网络上（无宿主或拿不到时为 null）。 */
    @Nullable
    final IGrid grid() {
        return grid;
    }

    private static IGrid resolveGrid(ISaveProvider host) {
        if (host instanceof appeng.api.networking.security.IActionHost actionHost) {
            try {
                return actionHost.getActionableNode() == null ? null
                    : actionHost.getActionableNode()
                        .getGrid();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    // ==================================================================
    // 通道差异：子类实现
    // ==================================================================

    /** @return 这个 handler 服务的通道（ITEM / FLUID stack type） */
    protected abstract IAEStackType<StackType> aeStackType();

    /** AE 栈 → 共享存储键；解析不出（空栈/无效流体）返回 null。 */
    protected abstract Key keyOf(StackType stack);

    /** 该条目当前存量（O(1)）。 */
    protected abstract long storedAmount(Key key);

    /** 按当前上限策略还能收多少（SIMULATE 预检用，绝不写库）。 */
    protected abstract long spaceLeft(Key key);

    /** 真实写入（MODULATE）；遵守上限与否由子类按配置选路径。 */
    protected abstract long insertStorage(Key key, long amount);

    /** 真实取出（MODULATE），返回实际取出的量。 */
    protected abstract long extractStorage(Key key, long amount);

    /** 通知共享终端 GUI 这一条变了（没有观众时是空操作）。 */
    protected abstract void broadcastChange(Key key);

    // ==================================================================
    // IMEInventory
    // ==================================================================

    @Override
    public StackType injectItems(StackType input, Actionable mode, BaseActionSource src) {
        if (input == null || input.getStackSize() <= 0) return input;
        Key key = keyOf(input);
        if (key == null) return input;
        if (!AeCellBridge.serveContent(this)) return input;

        if (mode == Actionable.MODULATE) {
            long accepted = AeCellBridge.suppressed(() -> insertStorage(key, input.getStackSize()));
            if (accepted <= 0) return input;
            broadcastChange(key);
            if (accepted >= input.getStackSize()) return null;
            return copyWithSize(input, input.getStackSize() - accepted);
        }

        long accepted = Math.min(input.getStackSize(), spaceLeft(key));
        if (accepted >= input.getStackSize()) return null;
        return copyWithSize(input, input.getStackSize() - accepted);
    }

    @Override
    public StackType extractItems(StackType request, Actionable mode, BaseActionSource src) {
        if (request == null || request.getStackSize() <= 0) return null;
        Key key = keyOf(request);
        if (key == null) return null;
        if (!AeCellBridge.serveContent(this)) return null;

        if (mode == Actionable.MODULATE) {
            long taken = AeCellBridge.suppressed(() -> extractStorage(key, request.getStackSize()));
            if (taken <= 0) return null;
            broadcastChange(key);
            return copyWithSize(request, taken);
        }

        long available = Math.min(request.getStackSize(), storedAmount(key));
        if (available <= 0) return null;
        return copyWithSize(request, available);
    }

    /**
     * 单类型数量查询。默认实现会全表扫描 —— 这里覆写成 O(1) 直查。
     * NetworkMonitor 的 watcher（发信器等）走的就是这条。
     */
    @Override
    public StackType getAvailableItem(StackType request, int iteration) {
        if (request == null) return null;
        Key key = keyOf(request);
        if (key == null) return null;
        if (!AeCellBridge.serveContent(this)) return null;
        long amount = Math.min(storedAmount(key), AeCellBridge.REPORT_CAP);
        if (amount <= 0) return null;
        return copyWithSize(request, amount);
    }

    /**
     * 全表枚举。重抽象出来逼子类覆写：这条是整个桥里唯一 O(条目数) 的路径，
     * 必须走 {@link AeCellBridge} 的镜像缓存，子类各自把镜像灌进 out。
     */
    @Override
    public abstract IItemList<StackType> getAvailableItems(IItemList<StackType> out, int iteration);

    private static <T extends IAEStack<T>> T copyWithSize(T template, long size) {
        T copy = template.copy();
        copy.setStackSize(size);
        return copy;
    }

    // ==================================================================
    // IMEInventoryHandler 的元数据
    // ==================================================================

    @Override
    public IAEStackType<?> getStackType() {
        return aeStackType();
    }

    @Override
    public AccessRestriction getAccess() {
        return AccessRestriction.READ_WRITE;
    }

    /**
     * pass 1 的「优先」判定按「共享背包里已经有这种东西」来 ——
     * 和 AE2 自家元件的语义一致（已有条目优先吸收同类），也让分区逻辑不用特判。
     */
    @Override
    public boolean isPrioritized(StackType input) {
        Key key = keyOf(input);
        return key != null && storedAmount(key) > 0;
    }

    @Override
    public boolean canAccept(StackType input) {
        // 惰性元件（同网络去重的次要方）拒收，让网络把东西送进别的存储
        return AeCellBridge.serveContent(this);
    }

    /** 优先级交给宿主包装层（驱动器/箱子会 setPriority 覆盖），这里给默认值。 */
    @Override
    public int getPriority() {
        return 0;
    }

    @Override
    public int getSlot() {
        return 0;
    }

    @Override
    public boolean validForPass(int i) {
        return true;
    }
}
