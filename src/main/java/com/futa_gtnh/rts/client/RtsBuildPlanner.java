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
 * 批量建造的客户端规划器：模式/形状/填充的选择、A/B 角点、幽灵预览坐标。
 *
 * <p>
 * 1.3.1 起模式（{@link RtsMode}）<b>真实路由鼠标行为</b>（见该枚举的注释），
 * 不再只是 HUD 文字；回车提交的含义由模式决定。
 *
 * <p>
 * 操作流：V 或顶栏按钮切模式、B/Tab 换形状、F 换填充、Ctrl+左键选两个角点
 * （A→B→再点就从头来）、回车提交、C 清空。预览坐标用
 * {@link ShapeGenerator} 生成并缓存（只有参数变了才重算），服务端提交后用
 * 同一份生成器再生成一遍 —— 预览即所得。
 *
 * <p>
 * <b>会话重置</b>：角点/模式是跨会话的静态量，1.3.0 里上次退出时的模式会带进
 * 下一次会话（上机反馈「切换有 BUG」的另一半原因）。进入俯瞰时由
 * {@link RtsClientState#enter} 调 {@link #resetForSession()} 重置模式与角点；
 * 形状/填充保留（个人偏好，连续盖房顺手）。
 */
public final class RtsBuildPlanner {

    private RtsBuildPlanner() {}

    /** 批量动作（提交给服务端的语义），由当前模式映射。 */
    public static final byte ACTION_BUILD = 0;
    public static final byte ACTION_DESTROY = 1;

    private static RtsMode mode = RtsMode.INTERACT;
    private static ShapeType shape = ShapeType.BOX;
    private static ShapeFill fill = ShapeFill.HOLLOW;

    private static boolean hasA, hasB;
    private static int ax, ay, az;
    private static int bx, by, bz;

    /** 模式切换时间戳（顶栏大字提示用，0 = 无提示）。 */
    private static long modeChangedAt;

    /** 预览缓存：参数变化时置脏，取用时重算。 */
    private static List<int[]> cachedPreview = Collections.emptyList();
    private static boolean dirty;

    // ------------------------------------------------------------------
    // 模式
    // ------------------------------------------------------------------

    public static RtsMode getMode() {
        return mode;
    }

    /** 设置模式（V 键 / 顶栏按钮）。同模式重复设置不刷新提示。 */
    public static void setMode(RtsMode newMode) {
        if (newMode == null || newMode == mode) return;
        mode = newMode;
        modeChangedAt = System.currentTimeMillis();
    }

    public static void cycleMode() {
        RtsMode[] values = RtsMode.values();
        setMode(values[(mode.ordinal() + 1) % values.length]);
    }

    /** 最近一次模式切换的时间戳（{@link #hasRecentModeToast} 用）。 */
    public static long getModeChangedAt() {
        return modeChangedAt;
    }

    /** 模式提示是否还在展示期内（2 秒）。 */
    public static boolean hasRecentModeToast() {
        return modeChangedAt > 0 && System.currentTimeMillis() - modeChangedAt < 2000L;
    }

    /** 进入俯瞰会话时重置：模式回互动、角点清空（形状/填充保留）。 */
    public static void resetForSession() {
        mode = RtsMode.INTERACT;
        modeChangedAt = 0L;
        clearPoints();
    }

    // ------------------------------------------------------------------
    // 形状 / 填充 / 角点
    // ------------------------------------------------------------------

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

    public static boolean isReady() {
        return hasA && hasB;
    }

    public static boolean hasFirstPoint() {
        return hasA;
    }

    // ------------------------------------------------------------------
    // 预览
    // ------------------------------------------------------------------

    /** 预览坐标（缓存；超预览上限时给空表，让渲染只画角点包围框）。 */
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
     * 当前模式下能否提交形状任务。互动模式不能（这是 1.3.0「模式没作用」
     * 观感的反面：现在回车的行为跟着模式走）。
     */
    public static boolean canSubmit() {
        if (!isReady() || !isWithinLocalLimits()) return false;
        if (mode == RtsMode.INTERACT) return false;
        if (mode == RtsMode.BUILD) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null) return false;
            ItemStack held = mc.thePlayer.getCurrentEquippedItem();
            return held != null && held.getItem() instanceof ItemBlock;
        }
        return true;
    }

    /** 当前模式对应的提交动作（仅 BUILD/DESTROY 有意义）。 */
    public static byte getSubmitAction() {
        return mode == RtsMode.DESTROY ? ACTION_DESTROY : ACTION_BUILD;
    }

    /** HUD 用的角点坐标。 */
    public static int[] getPointA() {
        return new int[] { ax, ay, az };
    }

    public static int[] getPointB() {
        return new int[] { bx, by, bz };
    }
}
