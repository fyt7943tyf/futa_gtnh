package com.futa_gtnh.rts.client;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;

import com.futa_gtnh.Config;
import com.futa_gtnh.rts.shape.ShapeFill;
import com.futa_gtnh.rts.shape.ShapeGenerator;
import com.futa_gtnh.rts.shape.ShapeType;

/**
 * 批量建造的客户端规划器：形状/填充/建造-破坏模式的选择、A/B 角点、
 * 幽灵预览坐标（缓存）。
 *
 * <p>
 * 操作流：B/Tab 换形状、F 换填充、V 换建造/破坏、Ctrl+左键选两个角点
 * （A→B→再点就从头来）、回车提交、C 清空。预览坐标用
 * {@link ShapeGenerator} 生成并缓存（只有参数变了才重算），服务端提交后用
 * 同一份生成器再生成一遍 —— 预览即所得。
 */
public final class RtsBuildPlanner {

    private RtsBuildPlanner() {}

    /** 批量动作（建造 / 破坏），包里用 RtsBatchEngine 的常量序号。 */
    public static final byte ACTION_BUILD = 0;
    public static final byte ACTION_DESTROY = 1;

    private static ShapeType shape = ShapeType.BOX;
    private static ShapeFill fill = ShapeFill.HOLLOW;
    private static byte action = ACTION_BUILD;

    private static boolean hasA, hasB;
    private static int ax, ay, az;
    private static int bx, by, bz;

    /** 预览缓存：参数变化时置脏，取用时重算。 */
    private static List<int[]> cachedPreview = Collections.emptyList();
    private static boolean dirty;

    public static void cycleShape() {
        ShapeType[] values = ShapeType.values();
        shape = values[(shape.ordinal() + 1) % values.length];
        dirty = true;
    }

    public static void cycleFill() {
        ShapeFill[] values = ShapeFill.values();
        fill = values[(fill.ordinal() + 1) % values.length];
        dirty = true;
    }

    public static void toggleAction() {
        action = (action == ACTION_BUILD) ? ACTION_DESTROY : ACTION_BUILD;
    }

    public static void clearPoints() {
        hasA = false;
        hasB = false;
        dirty = true;
    }

    /** Ctrl+左键选点：A → B → 两点都有再点就重新从 A 开始。 */
    public static void setPoint(int x, int y, int z) {
        if (!hasA || (hasA && hasB)) {
            hasA = true;
            hasB = false;
            ax = x;
            ay = y;
            az = z;
        } else {
            hasB = true;
            bx = x;
            by = y;
            bz = z;
        }
        dirty = true;
    }

    public static ShapeType getShape() {
        return shape;
    }

    public static ShapeFill getFill() {
        return fill;
    }

    public static byte getAction() {
        return action;
    }

    public static boolean isReady() {
        return hasA && hasB;
    }

    public static boolean hasFirstPoint() {
        return hasA;
    }

    /** 预览坐标（缓存；≤ 预览上限格才真的生成，超限给空表让渲染画包围框）。 */
    public static List<int[]> getPreview() {
        if (!isReady()) return Collections.emptyList();
        if (dirty) {
            if (!isTooLargeToPreview()) {
                cachedPreview = ShapeGenerator.generate(shape, fill, ax, ay, az, bx, by, bz);
            } else {
                cachedPreview = Collections.emptyList();
            }
            dirty = false;
        }
        return cachedPreview;
    }

    /** 预览的渲染上限：超过就只画角点包围框（渲染 3 万个半透明方块没有意义）。 */
    private static boolean isTooLargeToPreview() {
        return estimatedVolume() > 8000;
    }

    /** 估算体积（角点包围盒），HUD 与预览降级都用它。 */
    public static long estimatedVolume() {
        if (!hasA) return 0;
        int ex = hasB ? ShapeGenerator.extentX(ax, bx) : 1;
        int ey = hasB ? ShapeGenerator.extentY(ay, by) : 1;
        int ez = hasB ? ShapeGenerator.extentZ(az, bz) : 1;
        return (long) ex * ey * ez;
    }

    /** 服务端校验的同一套上限（提交前本地先拦一道，省一个来回）。 */
    public static boolean isWithinLocalLimits() {
        return ShapeGenerator.extentX(ax, bx) <= Config.rtsMaxShapeDimension
            && ShapeGenerator.extentY(ay, by) <= Config.rtsMaxShapeDimension
            && ShapeGenerator.extentZ(az, bz) <= Config.rtsMaxShapeDimension
            && estimatedVolume() <= Config.rtsMaxSelectionVolume;
    }

    /**
     * 建造模式还要求当前手持是 ItemBlock（服务端也会查，这里先拦一道
     * 提升手感）。
     */
    public static boolean canSubmit() {
        if (!isReady() || !isWithinLocalLimits()) return false;
        if (action == ACTION_BUILD) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null) return false;
            ItemStack held = mc.thePlayer.getCurrentEquippedItem();
            return held != null && held.getItem() instanceof ItemBlock;
        }
        return true;
    }

    /** HUD 用的角点坐标文案素材。 */
    public static int[] getPointA() {
        return new int[] { ax, ay, az };
    }

    public static int[] getPointB() {
        return new int[] { bx, by, bz };
    }
}
