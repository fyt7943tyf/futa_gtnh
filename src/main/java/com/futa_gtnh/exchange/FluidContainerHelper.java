package com.futa_gtnh.exchange;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidContainerRegistry;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.IFluidContainerItem;

import com.futa_gtnh.shared.FluidKey;

/**
 * 流体容器的读/灌/倒。三条路径依次尝试，覆盖不同年代模组的实现方式：
 *
 * <ol>
 * <li>{@link IFluidContainerItem}（Forge 的现代接口）—— 支持<b>部分灌装</b>，
 * 能把 300 mB 灌进 1000 mB 容量的单元里。GT 的单元、流体槽走这条。</li>
 * <li>{@link FluidContainerRegistry}（Forge 的老式注册表）—— 只能整罐整罐地灌，
 * 要求提供量 ≥ 容器容量。桶和大部分老模组容器走这条。</li>
 * </ol>
 *
 * <p>
 * 所有方法都<b>不动传入的 ItemStack</b>：内部先复制再操作，结果通过返回值给出。
 * 这一点很重要 —— {@code IFluidContainerItem.fill()} 是会就地改 NBT 的，
 * 如果直接拿玩家背包里的那一个去试，试失败了就会把玩家的容器改坏。
 */
public final class FluidContainerHelper {

    private FluidContainerHelper() {}

    /** 灌装结果。 */
    public static final class FillResult {

        /** 灌好之后的容器（堆叠数量与输入一致） */
        public final ItemStack container;
        /** 实际灌进去的毫巴数，恒 &gt; 0 */
        public final long consumed;

        FillResult(ItemStack container, long consumed) {
            this.container = container;
            this.consumed = consumed;
        }
    }

    /** 倒空结果。 */
    public static final class DrainResult {

        /** 容器里原本装着的流体，{@code amount} 是实际倒出来的量 */
        public final FluidStack fluid;
        /** 倒空之后的容器（可能仍带 NBT，例如 GT 单元的空壳） */
        public final ItemStack container;

        DrainResult(FluidStack fluid, ItemStack container) {
            this.fluid = fluid;
            this.container = container;
        }
    }

    /** @return 容器里装着的流体；空容器或不是容器时返回 null */
    public static FluidStack getFluid(ItemStack container) {
        if (container == null || container.getItem() == null) return null;

        if (container.getItem() instanceof IFluidContainerItem) {
            FluidStack fluid = ((IFluidContainerItem) container.getItem()).getFluid(container);
            if (fluid != null && fluid.getFluid() != null && fluid.amount > 0) return fluid;
        }

        FluidStack registered = FluidContainerRegistry.getFluidForFilledItem(container);
        if (registered != null && registered.getFluid() != null && registered.amount > 0) return registered;

        return null;
    }

    /**
     * 试着把共享存储里的流体灌进这个容器。
     *
     * @param container 目标容器（不会被修改）
     * @param fluid     要灌的流体种类
     * @param maxAmount 最多灌多少毫巴
     * @return 成功时的结果；灌不进去（不是容器 / 已装满 / 流体种类不符）返回 null
     */
    public static FillResult fill(ItemStack container, FluidKey fluid, long maxAmount) {
        if (container == null || container.getItem() == null || fluid == null || maxAmount <= 0L) return null;

        // 先看这个容器现有什么、还能装多少，装不下就别白忙
        FluidStack existing = getFluid(container);
        if (existing != null && !existing.isFluidEqual(fluid.prototype())) return null;

        int probeAmount = (int) Math.min(maxAmount, Integer.MAX_VALUE);
        if (probeAmount <= 0) return null;

        // 用 stackSize = 1 的副本去试：容器逻辑只关心「一个容器」，与整叠多少个无关
        ItemStack probe = container.copy();
        probe.stackSize = 1;

        if (probe.getItem() instanceof IFluidContainerItem) {
            IFluidContainerItem item = (IFluidContainerItem) probe.getItem();
            int filled = item.fill(probe, fluid.prototype(probeAmount), true);
            if (filled > 0) {
                probe.stackSize = container.stackSize;
                return new FillResult(probe, filled);
            }
            return null;
        }

        // Forge 老注册表：只能整罐灌，要求提供量 >= 容器容量
        ItemStack filled = FluidContainerRegistry.fillFluidContainer(fluid.prototype(probeAmount), probe);
        if (filled == null) return null;

        FluidStack inFilled = FluidContainerRegistry.getFluidForFilledItem(filled);
        if (inFilled == null || inFilled.amount <= 0) return null;

        filled.stackSize = container.stackSize;
        return new FillResult(filled, inFilled.amount);
    }

    /**
     * 把容器里的流体全部倒出来。
     *
     * @return 结果；容器是空的或不是容器时返回 null
     */
    public static DrainResult drain(ItemStack container) {
        if (container == null || container.getItem() == null) return null;

        ItemStack probe = container.copy();
        probe.stackSize = 1;

        if (probe.getItem() instanceof IFluidContainerItem) {
            IFluidContainerItem item = (IFluidContainerItem) probe.getItem();
            FluidStack drained = item.drain(probe, Integer.MAX_VALUE, true);
            if (drained != null && drained.getFluid() != null && drained.amount > 0) {
                probe.stackSize = container.stackSize;
                return new DrainResult(drained, probe);
            }
        }

        FluidStack registered = FluidContainerRegistry.getFluidForFilledItem(probe);
        if (registered == null || registered.getFluid() == null || registered.amount <= 0) return null;

        ItemStack emptied = FluidContainerRegistry.drainFluidContainer(probe);
        if (emptied == null) return null;

        emptied.stackSize = container.stackSize;
        return new DrainResult(registered, emptied);
    }
}
