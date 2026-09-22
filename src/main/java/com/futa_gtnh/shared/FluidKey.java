package com.futa_gtnh.shared;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

/**
 * 共享存储中「一种流体」的规范化标识。和 {@link ItemKey} 同样的思路：
 * 把数量从身份里剥离出去，只保留 <b>流体种类 + NBT</b>。
 *
 * <p>
 * 用 {@link FluidRegistry} 的名字（而不是数字 id）做序列化键。流体 id 会随
 * 整合包增删模组而漂移，名字不会；换个存档/换套模组也能读回来。
 */
public final class FluidKey {

    private final Fluid fluid;
    /** 已深拷贝并规范化；可能为 null */
    private final NBTTagCompound nbt;
    private final int hash;

    private FluidKey(Fluid fluid, NBTTagCompound nbt) {
        this.fluid = fluid;
        this.nbt = nbt;

        int h = fluid.hashCode();
        h = h * 31 + (nbt == null ? 0 : nbt.hashCode());
        this.hash = h;
    }

    /**
     * @return 规范化后的键；流体为 null <b>或没有注册名字</b>时返回 null
     */
    public static FluidKey of(FluidStack stack) {
        if (stack == null) return null;
        return of(stack.getFluid(), stack.tag);
    }

    public static FluidKey of(Fluid fluid, NBTTagCompound nbt) {
        if (fluid == null) return null;
        if (fluidNameOf(fluid) == null) return null;
        return new FluidKey(fluid, normalize(nbt));
    }

    /**
     * 取流体的注册名；没注册名字时返回 null。
     *
     * <p>
     * <b>这个检查不是多余的。</b>{@code FluidRegistry.getFluidName(Fluid)} 的实现是
     * {@code fluids.inverse().get(fluid)} —— 一个 Guava BiMap 的反查，
     * 对任何没走过 {@code registerFluid} 的流体实例直接返回 {@code null}。
     *
     * <p>
     * 而 {@code NBTTagCompound.setString(key, null)} 会抛
     * {@code IllegalArgumentException}（{@code NBTTagString} 的构造函数拒绝 null）。
     * 如果放任这种流体进到存储里，等到 {@code writeToNbt} 遍历到它时就会抛异常，
     * <b>整份存档的保存会永久失败</b> —— 不是丢一条，是之后每一次自动保存都写不出去。
     *
     * <p>
     * 所以在入口就把没有名字的流体挡掉：它在重启之后本来也无从还原，
     * 收下来只会变成一个定时炸弹。
     */
    private static String fluidNameOf(Fluid fluid) {
        String name = FluidRegistry.getFluidName(fluid);
        return name == null || name.isEmpty() ? null : name;
    }

    private static NBTTagCompound normalize(NBTTagCompound tag) {
        if (tag == null || tag.hasNoTags()) return null;
        return (NBTTagCompound) tag.copy();
    }

    public Fluid getFluid() {
        return fluid;
    }

    public boolean hasNbt() {
        return nbt != null;
    }

    public NBTTagCompound copyNbt() {
        return nbt == null ? null : (NBTTagCompound) nbt.copy();
    }

    /** @return 数量为 1 的原型流体栈，每次都是新对象 */
    public FluidStack prototype() {
        return new FluidStack(fluid, 1, nbt == null ? null : (NBTTagCompound) nbt.copy());
    }

    /**
     * @param amount 想要的毫巴数，会被夹到 int 范围内
     * @return 指定数量的流体栈
     */
    public FluidStack prototype(long amount) {
        int clamped = (int) Math.min(Math.max(amount, 1L), Integer.MAX_VALUE);
        return new FluidStack(fluid, clamped, nbt == null ? null : (NBTTagCompound) nbt.copy());
    }

    public NBTTagCompound writeToNbt() {
        NBTTagCompound tag = new NBTTagCompound();
        // 冗余保险：of() 已经挡掉了没名字的流体，这里再兜一次。
        // 理由是这个函数会被持久化路径调用，一旦抛异常就是整份存档写不出去，
        // 代价远大于「多判断一次」。空名字会让 readFromNbt 读回 null 从而跳过该条，
        // 属于优雅降级而不是崩溃。
        String name = fluidNameOf(fluid);
        tag.setString("fluid", name == null ? "" : name);
        if (nbt != null) {
            tag.setTag("nbt", nbt.copy());
        }
        return tag;
    }

    /** @return 反序列化结果；流体已不存在（对应模组被移除）时返回 null */
    public static FluidKey readFromNbt(NBTTagCompound tag) {
        if (tag == null) return null;
        if (!tag.hasKey("fluid")) return null;
        String name = tag.getString("fluid");
        if (name == null || name.isEmpty()) return null;
        Fluid fluid = FluidRegistry.getFluid(name);
        if (fluid == null) return null;
        NBTTagCompound nbt = tag.hasKey("nbt") ? (NBTTagCompound) tag.getTag("nbt") : null;
        return new FluidKey(fluid, normalize(nbt));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof FluidKey)) return false;
        FluidKey o = (FluidKey) other;
        if (this.hash != o.hash) return false;
        if (this.fluid != o.fluid) return false;
        if (this.nbt == null) return o.nbt == null;
        return this.nbt.equals(o.nbt);
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "FluidKey[" + FluidRegistry.getFluidName(fluid) + (nbt == null ? "" : "+nbt") + "]";
    }
}
