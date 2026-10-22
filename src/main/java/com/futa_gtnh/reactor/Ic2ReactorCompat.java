package com.futa_gtnh.reactor;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import ic2.api.item.IC2Items;
import ic2.api.reactor.IReactor;
import ic2.api.reactor.IReactorChamber;

/**
 * IC2 反应堆的统一访问层 —— 本包里所有对 IC2 的触碰都从这走。
 *
 * <p>
 * <b>为什么这一层存在：</b>IC2 是闭源 mod，我们把对它的依赖收窄到三样东西上，
 * 全部是 {@code ic2.api} 里的公开接口（编译期依赖经 GT5-U 的 POM 传递进来，
 * 已在 dependencies.gradle 里显式声明钉死）：
 *
 * <ul>
 * <li>{@link IReactor} —— 反应堆核心。网格读写走 {@code getItemAt(x, y)} /
 * {@code setItemAt(x, y, stack)}，冷却方式走 {@code isFluidCooled()}，
 * 核心坐标走 {@code getPosition()}。这几个方法从 IC2 2.2 就稳定存在，
 * 是官方给自动化 mod 留的门；</li>
 * <li>{@link IReactorChamber} —— 反应堆仓，{@code getReactor()} 指回核心；</li>
 * <li>{@link IC2Items} —— 按名字拿 IC2 的物品（压力容器等）。</li>
 * </ul>
 *
 * <p>
 * <b>网格坐标语义</b>（用 javap 反编译 {@code TileEntityNuclearReactorElectric} 证实）：
 * {@code getItemAt(int x, int y)} 的 {@code x} 是<b>列</b>、范围
 * {@code [0, 3 + 相邻反应堆仓数)}，{@code y} 是<b>行</b>、固定 {@code [0, 6)}；
 * 越界时读返回 null、写直接忽略（静默）。列数（{@code getReactorSize()}）是核心
 * Tile 上的公开方法但<b>不在 API 接口上</b>，所以优先反射按名调用（方法跟着实例的
 * class 走，不写死 IC2 内部类名），反射不可用时退回「数相邻反应堆仓」——两者语义相同。
 *
 * <p>
 * <b>找不到核心怎么办：</b>玩家可能把工具点到反应堆仓（有 API 指回核心），
 * 也可能点到<b>外壳方块</b>（检修孔/流体端口/红石端口——它们没有任何公开途径
 * 指回核心）。后者直接在 5×5×5 邻域里扫一遍 {@link IReactor}：核心到外壳任何一格
 * 的切比雪夫距离都不超过 2，最多 124 次 tile 查找，只在点击那一刻发生一次。
 */
public final class Ic2ReactorCompat {

    private Ic2ReactorCompat() {}

    /** {@link #isAvailable()} 的缓存。GT 硬依赖 IC2，实践中恒为 true，守卫只是防御。 */
    private static Boolean available;

    /** getReactorSize 的按类缓存：同一 Tile 类只解析一次。 */
    private static final Map<Class<?>, Method> SIZE_METHODS = new HashMap<>();
    private static boolean sizeLookupBroken;

    /** 反应堆引用：API 接口 + 核心方块坐标（{@code getPosition()} 解出来的）。 */
    public static final class ReactorRef {

        public final IReactor reactor;
        public final int coreX;
        public final int coreY;
        public final int coreZ;

        ReactorRef(IReactor reactor, int coreX, int coreY, int coreZ) {
            this.reactor = reactor;
            this.coreX = coreX;
            this.coreY = coreY;
            this.coreZ = coreZ;
        }
    }

    // ==================================================================
    // 可用性
    // ==================================================================

    /** IC2 是否在场。用 GT 的 Mods 枚举（与 ShimmerDisassemblyRules 同一判法）。 */
    public static boolean isAvailable() {
        if (available == null) {
            available = gregtech.api.enums.Mods.IndustrialCraft2.isModLoaded();
        }
        return available.booleanValue();
    }

    // ==================================================================
    // 定位反应堆
    // ==================================================================

    /**
     * 把一次点击的坐标解析成反应堆核心。
     *
     * @return 核心引用；这个位置（含 5³ 邻域）没有反应堆时返回 null
     */
    public static ReactorRef resolveReactor(World world, int x, int y, int z) {
        if (!isAvailable() || world == null) return null;

        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof IReactor) return refOf((IReactor) tile);

        // 反应堆仓：API 直接指回核心（链接不上时 getReactor 返回 null）
        if (tile instanceof IReactorChamber) {
            IReactor reactor = ((IReactorChamber) tile).getReactor();
            if (reactor != null) return refOf(reactor);
        }

        // 外壳方块（检修孔/流体端口/红石端口）：没有 API 指回核心，5³ 邻域扫描兜底。
        // 核心到外壳任意一格的切比雪夫距离 ≤ 2，5³ 必然覆盖。
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    TileEntity neighbour = world.getTileEntity(x + dx, y + dy, z + dz);
                    if (neighbour instanceof IReactor) return refOf((IReactor) neighbour);
                }
            }
        }
        return null;
    }

    private static ReactorRef refOf(IReactor reactor) {
        ChunkCoordinates pos = reactor.getPosition();
        if (pos == null) return null;
        return new ReactorRef(reactor, pos.posX, pos.posY, pos.posZ);
    }

    // ==================================================================
    // 网格
    // ==================================================================

    /**
     * 网格列数（{@code getReactorSize()} 的语义）：3 + 相邻反应堆仓数，范围 [3, 9]。
     *
     * <p>
     * 优先反射调 IC2 自己的方法；反射失败（改名/换结构）退回手数相邻反应堆仓，
     * 两者语义一致。任何异常都不往外抛 —— 工具坏了不能把玩家右键炸了。
     */
    public static int getColumns(ReactorRef ref) {
        IReactor reactor = ref.reactor;
        if (reactor instanceof TileEntity) {
            Method method = reactorSizeMethod(reactor.getClass());
            if (method != null) {
                try {
                    return clamp(((Number) method.invoke(reactor)).intValue());
                } catch (Throwable ignored) {
                    // 落到下面的手数路径
                }
            }
        }
        return clamp(countAdjacentChambers(reactor));
    }

    private static int clamp(int columns) {
        return Math.max(3, Math.min(9, columns));
    }

    private static int countAdjacentChambers(IReactor reactor) {
        if (!(reactor instanceof TileEntity)) return 0;
        TileEntity core = (TileEntity) reactor;
        World world = core.getWorldObj();
        if (world == null) return 0;

        int count = 0;
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity neighbour = world.getTileEntity(
                core.xCoord + direction.offsetX,
                core.yCoord + direction.offsetY,
                core.zCoord + direction.offsetZ);
            if (neighbour instanceof IReactorChamber) count++;
        }
        return 3 + count;
    }

    private static Method reactorSizeMethod(Class<?> type) {
        if (sizeLookupBroken) return null;
        Method cached = SIZE_METHODS.get(type);
        if (cached != null) return cached;
        try {
            Method method = type.getMethod("getReactorSize");
            method.setAccessible(true);
            SIZE_METHODS.put(type, method);
            return method;
        } catch (Throwable t) {
            // 这个 Tile 类上没有 getReactorSize：记一次，之后全走手数路径
            sizeLookupBroken = true;
            return null;
        }
    }

    /** 读一格网格。越界/异常一律返回 null（IC2 自己对越界也是静默返回 null）。 */
    public static ItemStack getItemAt(ReactorRef ref, int column, int row) {
        try {
            return ref.reactor.getItemAt(column, row);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 写一格网格。越界直接拒绝（IC2 对越界写是静默忽略，这里显式挡掉并让调用方知道）。
     *
     * @return 是否写入
     */
    public static boolean setItemAt(ReactorRef ref, int column, int row, ItemStack stack) {
        if (column < 0 || column >= getColumns(ref) || row < 0 || row >= ReactorLayout.ROWS) return false;
        try {
            ref.reactor.setItemAt(column, row, stack);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 核心是不是流体冷却（IC2 2.2 起就在接口上）。异常按固体反应堆处理。 */
    public static boolean isFluidCooled(ReactorRef ref) {
        try {
            return ref.reactor.isFluidCooled();
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================
    // 物品
    // ==================================================================

    /**
     * 反应堆压力容器的物品原型（{@code IC2Items.getItem("reactorvessel")}）。
     * 字段名取自 {@code ic2.core.Ic2Items}（javap 证实）；拿不到时返回 null，
     * 调用方给玩家报「IC2 版本不兼容」。
     */
    public static ItemStack getVesselStack() {
        if (!isAvailable()) return null;
        try {
            ItemStack stack = IC2Items.getItem("reactorvessel");
            return stack == null ? null : stack.copy();
        } catch (Throwable t) {
            return null;
        }
    }
}
