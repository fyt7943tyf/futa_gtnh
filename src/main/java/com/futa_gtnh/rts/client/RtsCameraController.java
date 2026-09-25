package com.futa_gtnh.rts.client;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;

import org.lwjgl.input.Keyboard;

import com.futa_gtnh.Config;

/**
 * 俯瞰相机的姿态管理与输入积分（纯客户端）。
 *
 * <p>
 * 相机姿态<b>完全不经过服务端</b>：玩家在俯瞰模式下原地不动，锚点恒等于玩家
 * 脚下位置，服务端的每张动作包只按「目标在玩家周围半径内」校验。所以这里的
 * 移动、旋转、钳制都是本地表现 —— 客户端把自己钳在同一份半径配置里，
 * 只是让画面别跑到服务端会拒绝的范围外面去（两端的配置值不同也无妨：
 * 差异最多表现为客户端多显示一点，操作时仍会被服务端拦下）。
 *
 * <p>
 * 输入分两路汇到这里，都在 tick 里统一消费：
 * <ul>
 * <li>键盘（WASD/Q/E/空格/Shift）由 {@link RtsClientTickHandler} 每 tick
 * 轮询 —— 俯瞰界面开着时原版 KeyBinding 不会再更新（键都已被 unPress），
 * 所以必须直接读 {@code Keyboard.isKeyDown} 的实时状态；</li>
 * <li>鼠标（拖拽旋转/平移、滚轮）由 {@link GuiRtsOverlay} 在鼠标事件里
 * 攒成「待处理像素数」，避免和帧率绑死。</li>
 * </ul>
 *
 * <p>
 * 平滑：每 tick 把上一 tick 姿态存进 prev 系列字段，当前姿态存进实体坐标与
 * rotation 字段，原版渲染器用 prev + partialTicks 插值，相机移动天然是平滑的，
 * 不需要自己做帧间追随。
 */
public final class RtsCameraController {

    private RtsCameraController() {}

    /** 进入时的俯角。70° 而不是 90°：留一点地平线，方便看远处的高低差。 */
    private static final float ENTER_PITCH = 70.0F;
    /** 进入时相机离锚点（玩家脚下）的距离。 */
    private static final double ENTER_DISTANCE = 20.0D;
    /** 俯仰角钳制。不到 ±90 是为了避免视线方向退化成纯竖直（后续的光标拾取要靠它）。 */
    private static final float PITCH_LIMIT = 89.0F;
    /** 拖拽平移的速度按「相机离玩家的高度」缩放，这两个值是缩放因子的上下限。 */
    private static final double PAN_SCALE_MIN = 0.5D;
    private static final double PAN_SCALE_MAX = 6.0D;

    private static RtsCameraEntity cameraEntity;

    // 当前 tick 姿态
    private static double camX, camY, camZ;
    private static float camYaw, camPitch;
    // 上一 tick 姿态（渲染插值用）
    private static double prevX, prevY, prevZ;
    private static float prevYaw, prevPitch;

    // 输入累积（帧事件攒起来、tick 统一消费）
    private static float pendingRotateDx, pendingRotateDy;
    private static float pendingPanDx, pendingPanDy;
    private static double pendingWheel;

    public static RtsCameraEntity getCameraEntity() {
        return cameraEntity;
    }

    /** 当前 tick 的相机坐标（非插值）。 */
    public static double[] getCameraPosition() {
        return new double[] { camX, camY, camZ };
    }

    /** 按渲染插值取相机坐标（拾取射线要与画面上看到的一致，必须用插值姿态）。 */
    public static double[] getCameraPosition(float partialTicks) {
        return new double[] { prevX + (camX - prevX) * partialTicks, prevY + (camY - prevY) * partialTicks,
            prevZ + (camZ - prevZ) * partialTicks };
    }

    /** 按渲染插值取相机朝向 yaw。 */
    public static float getCameraYaw(float partialTicks) {
        return prevYaw + (camYaw - prevYaw) * partialTicks;
    }

    /** 按渲染插值取相机朝向 pitch。 */
    public static float getCameraPitch(float partialTicks) {
        return prevPitch + (camPitch - prevPitch) * partialTicks;
    }

    /** 进入俯瞰模式时定初始姿态：俯角 70°、朝向吸附到玩家朝向最近的 90° 倍数。 */
    public static void enter() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null || mc.theWorld == null) return;

        cameraEntity = new RtsCameraEntity(mc.theWorld);

        camYaw = snapToRightAngle(player.rotationYaw);
        camPitch = ENTER_PITCH;

        // 相机放在锚点（玩家脚下）沿视线<b>反</b>方向 ENTER_DISTANCE 处：
        // 这样开局玩家正好在画面中心，是 RTS 的标准开局视角。
        double[] look = lookVector(camYaw, camPitch);
        camX = player.posX - look[0] * ENTER_DISTANCE;
        camY = player.posY - look[1] * ENTER_DISTANCE;
        camZ = player.posZ - look[2] * ENTER_DISTANCE;

        // prev 必须同值初始化，否则第一帧会从世界原点插值过来闪一下
        prevX = camX;
        prevY = camY;
        prevZ = camZ;
        prevYaw = camYaw;
        prevPitch = camPitch;

        applyToEntity();
    }

    /** 退出俯瞰模式：只清引用，视角恢复由 {@code RtsClientState#exit} 负责。 */
    public static void exit() {
        cameraEntity = null;
        pendingRotateDx = 0.0F;
        pendingRotateDy = 0.0F;
        pendingPanDx = 0.0F;
        pendingPanDy = 0.0F;
        pendingWheel = 0.0D;
    }

    /** 每客户端 tick 调一次：消费输入、积分移动、钳制、写实体字段。 */
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (cameraEntity == null || player == null || mc.theWorld == null) return;
        if (cameraEntity.worldObj != mc.theWorld) cameraEntity.worldObj = mc.theWorld;

        prevX = camX;
        prevY = camY;
        prevZ = camZ;
        prevYaw = camYaw;
        prevPitch = camPitch;

        double yawRad = Math.toRadians(camYaw);
        // 水平前进方向（原版 getLookVec 的水平分量）
        double fwdX = -Math.sin(yawRad);
        double fwdZ = Math.cos(yawRad);
        // 屏幕右方向：fwd 绕 Y 轴转 90°（右手系 cross(look, up) 的结果）
        double rightX = -Math.cos(yawRad);
        double rightZ = -Math.sin(yawRad);

        // --- 键盘平移 / 升降 / 旋转 ----------------------------------------
        double panSpeed = Config.rtsCameraPanSpeed;
        if (isKeyDown(Keyboard.KEY_W)) {
            camX += fwdX * panSpeed;
            camZ += fwdZ * panSpeed;
        }
        if (isKeyDown(Keyboard.KEY_S)) {
            camX -= fwdX * panSpeed;
            camZ -= fwdZ * panSpeed;
        }
        if (isKeyDown(Keyboard.KEY_D)) {
            camX += rightX * panSpeed;
            camZ += rightZ * panSpeed;
        }
        if (isKeyDown(Keyboard.KEY_A)) {
            camX -= rightX * panSpeed;
            camZ -= rightZ * panSpeed;
        }
        if (isKeyDown(Keyboard.KEY_SPACE)) {
            camY += Config.rtsCameraVerticalSpeed;
        }
        if (isKeyDown(Keyboard.KEY_LSHIFT)) {
            camY -= Config.rtsCameraVerticalSpeed;
        }
        // 朝向：本 mod 约定「向右转 = yaw 增大」（南 0° → 西 90°），Q 向左、E 向右
        if (isKeyDown(Keyboard.KEY_Q)) {
            camYaw -= Config.rtsCameraRotateSpeed;
        }
        if (isKeyDown(Keyboard.KEY_E)) {
            camYaw += Config.rtsCameraRotateSpeed;
        }

        // --- 鼠标拖拽旋转（FPS 式：拖右向右转、拖下向下看）-------------------
        camYaw += pendingRotateDx * Config.rtsMouseRotateSensitivity;
        camPitch = clampFloat(camPitch + pendingRotateDy * Config.rtsMouseRotateSensitivity, -PITCH_LIMIT, PITCH_LIMIT);

        // --- 鼠标拖拽平移（抓地式：像拖地图，拖右 → 相机左移）-----------------
        // 速度按相机高度缩放：拉得越高，一格像素代表的世界距离越大
        double panScale = clamp((camY - player.posY) / 16.0D, PAN_SCALE_MIN, PAN_SCALE_MAX)
            * Config.rtsMousePanSensitivity;
        camX += (-rightX * pendingPanDx + fwdX * pendingPanDy) * panScale;
        camZ += (-rightZ * pendingPanDx + fwdZ * pendingPanDy) * panScale;

        // --- 滚轮：沿视线推拉（向上滚 = 拉近）--------------------------------
        if (pendingWheel != 0.0D) {
            double[] look = lookVector(camYaw, camPitch);
            double dolly = pendingWheel * Config.rtsCameraZoomSpeed;
            camX += look[0] * dolly;
            camY += look[1] * dolly;
            camZ += look[2] * dolly;
        }

        pendingRotateDx = 0.0F;
        pendingRotateDy = 0.0F;
        pendingPanDx = 0.0F;
        pendingPanDy = 0.0F;
        pendingWheel = 0.0D;

        // --- 钳制在玩家周围的操作范围内 --------------------------------------
        double radius = Config.rtsMaxActionRadius;
        camX = clamp(camX, player.posX - radius, player.posX + radius);
        camZ = clamp(camZ, player.posZ - radius, player.posZ + radius);
        camY = clamp(camY, player.posY + Config.rtsHeightMinOffset, player.posY + Config.rtsHeightMaxOffset);

        // yaw 不做常规归一化：prev/current 一起插值时 180↔-180 跳变会原地转一圈。
        // 只在漂得太远时把 prev 和 current 一起搬回来，插值不受影响。
        if (Math.abs(camYaw) > 720.0F) {
            float wrapped = wrapDegrees(camYaw);
            prevYaw += wrapped - camYaw;
            camYaw = wrapped;
        }

        applyToEntity();
    }

    /** 鼠标拖拽旋转输入（像素，由 GuiRtsOverlay 的中键拖拽路由过来）。 */
    public static void addRotateDrag(float dxPixels, float dyPixels) {
        pendingRotateDx += dxPixels;
        pendingRotateDy += dyPixels;
    }

    /** 鼠标拖拽平移输入（像素，由 GuiRtsOverlay 的右键拖拽路由过来）。 */
    public static void addPanDrag(float dxPixels, float dyPixels) {
        pendingPanDx += dxPixels;
        pendingPanDy += dyPixels;
    }

    /**
     * 滚轮输入（内部换算成「格数」）。
     *
     * <p>
     * 两套语义双兼容：lwjgl3ify（GTNH 必装）的 {@code getEventDWheel()}
     * 一格返回 ±1（GLFW 风格，不是 LWJGL2 的 ±120）—— 上机反馈「滚轮推拉
     * 失效」的根因之一就是拿 ±1 去除以 120 得到 0.008。规则：绝对值 ≥ 120
     * 视为 LWJGL2 风格除以 120，否则按原值当格数用。
     */
    public static void addWheel(int dWheel) {
        double notches = Math.abs(dWheel) >= 120 ? dWheel / 120.0D : dWheel;
        pendingWheel += clamp(notches, -3.0D, 3.0D);
    }

    /** 把当前与上一 tick 的姿态写进相机实体，渲染器从这里读。 */
    private static void applyToEntity() {
        if (cameraEntity == null) return;
        cameraEntity.prevPosX = prevX;
        cameraEntity.prevPosY = prevY;
        cameraEntity.prevPosZ = prevZ;
        cameraEntity.lastTickPosX = prevX;
        cameraEntity.lastTickPosY = prevY;
        cameraEntity.lastTickPosZ = prevZ;
        // setPosition 而不是直写 posX/Y/Z：它还会同步碰撞箱 ——
        // 原版 isEntityInsideOpaqueBlock（相机贴墙判定）和一些光影/迷雾
        // mod 会读 renderViewEntity 的碰撞箱，留在世界原点会出怪事
        cameraEntity.setPosition(camX, camY, camZ);
        cameraEntity.prevRotationYaw = prevYaw;
        cameraEntity.prevRotationPitch = prevPitch;
        cameraEntity.rotationYaw = camYaw;
        cameraEntity.rotationPitch = camPitch;
    }

    /** 原版 getLookVec 同款公式：由 yaw/pitch 得单位视线向量。 */
    private static double[] lookVector(float yaw, float pitch) {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        return new double[] { -Math.sin(yawRad) * Math.cos(pitchRad), -Math.sin(pitchRad),
            Math.cos(yawRad) * Math.cos(pitchRad) };
    }

    /** 把任意朝向吸附到最近的 90° 倍数（RTS 开局习惯：正方向朝下）。 */
    private static float snapToRightAngle(float yaw) {
        return (float) (Math.round(Math.toRadians(yaw) / (Math.PI / 2)) * 90);
    }

    /** 归一化到 [-180, 180)。 */
    private static float wrapDegrees(float angle) {
        angle = angle % 360.0F;
        if (angle >= 180.0F) angle -= 360.0F;
        if (angle < -180.0F) angle += 360.0F;
        return angle;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static float clampFloat(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static boolean isKeyDown(int keyCode) {
        try {
            return Keyboard.isKeyDown(keyCode);
        } catch (Throwable t) {
            return false;
        }
    }
}
