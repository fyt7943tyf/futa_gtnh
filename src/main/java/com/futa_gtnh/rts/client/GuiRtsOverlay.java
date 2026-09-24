package com.futa_gtnh.rts.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import com.futa_gtnh.client.KeyHandler;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketRtsBreak;
import com.futa_gtnh.network.PacketRtsInteract;
import com.futa_gtnh.network.PacketRtsQuickBuild;
import com.futa_gtnh.network.PacketRtsRotate;
import com.futa_gtnh.network.PacketRtsUndoRedo;

/**
 * 俯瞰模式的常驻 HUD：一个透明全屏界面（不画背景，世界在底下可见），
 * 负责接管全部鼠标/键盘。
 *
 * <p>
 * <b>为什么用 GuiScreen 而不是去 mixin 输入层：</b>1.7.10 里打开 GuiScreen
 * 就是原版的「接管输入」机制 —— 鼠标释放显示光标、KeyBinding 全部 unPress
 * （玩家移动输入随之归零）、键盘事件全部路由进界面。我们要的 RTS 操作方式
 * （光标点击世界、WASD 开着界面还能动相机）正好都要这些副作用，
 * 只是相机移动键改从 {@code Keyboard.isKeyDown} 实时轮询（见
 * {@link RtsCameraController} 的类注释）。
 *
 * <p>
 * <b>鼠标语义</b>：
 * <ul>
 * <li>左键单击：破坏方块 / 攻击实体；左键拖拽：连续破坏（划线式）；</li>
 * <li>右键单击：对方块使用手中物品 / 互动（放置就是它）、对实体互动；
 * Shift+右键 = 潜行语义；Ctrl+右键拖拽：连续放置；</li>
 * <li>中键拖拽：旋转相机（yaw/pitch）；右键拖拽（无修饰）：平移相机；</li>
 * <li>滚轮：沿视线推拉相机；Ctrl+左键：批量形状选点（A→B）。</li>
 * </ul>
 * 「拖过阈值才算拖拽」：按下后位移超过 {@link #DRAG_THRESHOLD_PIXELS}
 * 像素才转成拖拽，没超过就在松开时算一次单击 —— 不然每次点放都会有
 * 一两像素的抖动被当成平移。
 */
public class GuiRtsOverlay extends GuiScreen {

    /** 按下后累计位移超过多少像素才算拖拽。 */
    private static final int DRAG_THRESHOLD_PIXELS = 4;

    /** 当前按住的鼠标键（-1 = 没按住）。 */
    private int activeButton = -1;
    /** 已经判定为拖拽。 */
    private boolean dragging;
    /** 按下时的屏幕坐标（判定拖拽阈值用）。 */
    private int pressX, pressY;
    /** 上一个鼠标事件的位置（算增量用）。 */
    private int lastMouseX, lastMouseY;
    /** 拖动连刷的上一目标格（目标没变就不重复发包）。-1 表示还没有。 */
    private int paintTargetX = Integer.MIN_VALUE, paintTargetY, paintTargetZ;

    @Override
    public boolean doesGuiPauseGame() {
        // 单人里打开界面也不暂停世界：俯瞰模式下挂机/工作时长会很久
        return false;
    }

    @Override
    public void initGui() {
        activeButton = -1;
        dragging = false;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == KeyHandler.getRtsToggleKeyCode()) {
            RtsClientState.exit();
            return;
        }
        switch (keyCode) {
            case Keyboard.KEY_B:
            case Keyboard.KEY_TAB:
                RtsBuildPlanner.cycleShape();
                return;
            case Keyboard.KEY_F:
                RtsBuildPlanner.cycleFill();
                return;
            case Keyboard.KEY_V:
                RtsBuildPlanner.toggleAction();
                return;
            case Keyboard.KEY_C:
                RtsBuildPlanner.clearPoints();
                return;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                confirmQuickBuild();
                return;
            case Keyboard.KEY_Z:
                if (isCtrlDown())
                    NetworkHandler.INSTANCE.sendToServer(new PacketRtsUndoRedo(PacketRtsUndoRedo.ACTION_UNDO));
                return;
            case Keyboard.KEY_Y:
                if (isCtrlDown())
                    NetworkHandler.INSTANCE.sendToServer(new PacketRtsUndoRedo(PacketRtsUndoRedo.ACTION_REDO));
                return;
            case Keyboard.KEY_R: {
                RtsPickResult pick = RtsCursorPicker.pick();
                if (pick.type == RtsPickResult.Type.BLOCK) {
                    NetworkHandler.INSTANCE.sendToServer(new PacketRtsRotate(pick.blockX, pick.blockY, pick.blockZ));
                }
                return;
            }
            default:
                return;
        }
    }

    /** 回车：把当前形状/填充/角点作为批量任务提交。 */
    private void confirmQuickBuild() {
        if (!RtsBuildPlanner.isReady()) return;
        if (!RtsBuildPlanner.canSubmit()) {
            // 本地先拦一道并说清原因（服务端还会再校验一遍）
            String key = !RtsBuildPlanner.isWithinLocalLimits() ? "futa_gtnh.rts.msg.too_large_local"
                : "futa_gtnh.rts.msg.need_itemblock";
            if (mc != null && mc.thePlayer != null) {
                mc.thePlayer.addChatMessage(new net.minecraft.util.ChatComponentTranslation(key));
            }
            return;
        }
        int[] a = RtsBuildPlanner.getPointA();
        int[] b = RtsBuildPlanner.getPointB();
        NetworkHandler.INSTANCE.sendToServer(
            PacketRtsQuickBuild.of(
                RtsBuildPlanner.getAction(),
                RtsBuildPlanner.getShape(),
                RtsBuildPlanner.getFill(),
                a[0],
                a[1],
                a[2],
                b[0],
                b[1],
                b[2]));
        // 提交后清掉角点（形状/填充/模式保留，连续盖房顺手）
        RtsBuildPlanner.clearPoints();
    }

    /**
     * 自己消费全部鼠标事件（不调 super：原版的循环是为按钮/插槽设计的，
     * 这里需要的是原始的按下/拖动/释放/滚轮四态路由）。
     */
    @Override
    public void handleMouseInput() {
        Minecraft mc = Minecraft.getMinecraft();
        while (Mouse.next()) {
            // 事件坐标 → GUI 坐标（GuiScreen 惯例：Y 轴翻转）
            int x = Mouse.getEventX() * this.width / Math.max(1, mc.displayWidth);
            int y = this.height - Mouse.getEventY() * this.height / Math.max(1, mc.displayHeight) - 1;
            int button = Mouse.getEventButton();

            if (button == -1) {
                // 移动事件：只在有按键按住时才有意义
                if (activeButton != -1) {
                    if (!dragging && Math.abs(x - pressX) + Math.abs(y - pressY) > DRAG_THRESHOLD_PIXELS) {
                        dragging = true;
                    }
                    if (dragging) {
                        routeDrag(activeButton, x - lastMouseX, y - lastMouseY);
                    }
                }
            } else if (Mouse.getEventButtonState()) {
                activeButton = button;
                dragging = false;
                pressX = x;
                pressY = y;
            } else {
                if (!dragging) {
                    onClick(x, y, button);
                }
                if (button == activeButton) {
                    activeButton = -1;
                    dragging = false;
                    // 松开后连刷目标作废：下一次按住重新从当前光标处开始
                    paintTargetX = Integer.MIN_VALUE;
                }
            }

            lastMouseX = x;
            lastMouseY = y;

            // 滚轮是独立通道（不带按键状态），每个事件都处理
            int dWheel = Mouse.getEventDWheel();
            if (dWheel != 0) {
                RtsCameraController.addWheel(dWheel);
            }
        }
    }

    /**
     * 拖拽路由：
     * <ul>
     * <li>中键拖拽 → 旋转相机；</li>
     * <li>右键拖拽（无修饰）→ 平移相机；</li>
     * <li>左键拖拽 → 连续破坏（光标扫过的每个新方块发一个破坏包）；</li>
     * <li>Ctrl+右键拖拽 → 连续放置（当前手持物品，划线式铺方块）。</li>
     * </ul>
     */
    private void routeDrag(int button, int dx, int dy) {
        if (button == 2) {
            RtsCameraController.addRotateDrag(dx, dy);
            return;
        }
        if (button == 1 && !isCtrlDown()) {
            RtsCameraController.addPanDrag(dx, dy);
            return;
        }
        // 连刷：目标格没变就不发包（一次拖动跨半屏也只有几十个新格子，
        // 服务端限频是第二道保险）。Ctrl 按住时左键拖拽让位给形状选点
        if (button == 0 && !isCtrlDown()) {
            paintBreak();
        } else if (button == 1) {
            paintPlace();
        }
    }

    /** 左键拖动：连续破坏。 */
    private void paintBreak() {
        RtsPickResult pick = RtsCursorPicker.pick();
        if (pick.type != RtsPickResult.Type.BLOCK) return;
        if (pick.blockX == paintTargetX && pick.blockY == paintTargetY && pick.blockZ == paintTargetZ) return;
        paintTargetX = pick.blockX;
        paintTargetY = pick.blockY;
        paintTargetZ = pick.blockZ;
        NetworkHandler.INSTANCE.sendToServer(new PacketRtsBreak(pick.blockX, pick.blockY, pick.blockZ));
    }

    /** Ctrl+右键拖动：连续放置（用当前手持物品，Shift 可再叠加潜行语义）。 */
    private void paintPlace() {
        RtsPickResult pick = RtsCursorPicker.pick();
        if (pick.type != RtsPickResult.Type.BLOCK) return;
        if (pick.blockX == paintTargetX && pick.blockY == paintTargetY && pick.blockZ == paintTargetZ) return;
        paintTargetX = pick.blockX;
        paintTargetY = pick.blockY;
        paintTargetZ = pick.blockZ;
        sendPlacePacket(pick);
    }

    /** 单击路由（拖拽阈值内松开才算点击）。 */
    private void onClick(int x, int y, int button) {
        RtsPickResult pick = RtsCursorPicker.pick();

        if (button == 0) {
            // Ctrl+左键 = 批量选点（A → B），不破坏
            if (isCtrlDown()) {
                if (pick.type == RtsPickResult.Type.BLOCK) {
                    RtsBuildPlanner.setPoint(pick.blockX, pick.blockY, pick.blockZ);
                }
                return;
            }
            // 左键：破坏方块 / 攻击实体
            if (pick.type == RtsPickResult.Type.ENTITY) {
                NetworkHandler.INSTANCE.sendToServer(
                    PacketRtsInteract.entity(
                        PacketRtsInteract.MODE_ATTACK_ENTITY,
                        pick.entity.getEntityId(),
                        pick.dirX,
                        pick.dirY,
                        pick.dirZ));
            } else if (pick.type == RtsPickResult.Type.BLOCK) {
                NetworkHandler.INSTANCE.sendToServer(new PacketRtsBreak(pick.blockX, pick.blockY, pick.blockZ));
            }
            return;
        }

        if (button == 1) {
            // 右键：对方块使用手中物品 / 互动（放置就是它 —— 原版
            // activateBlockOrUseItem 走 ItemBlock 路径，生存自动扣一个、
            // 创造不扣）。Shift = 潜行右键（跳过方块互动直接用物品）
            if (pick.type == RtsPickResult.Type.ENTITY) {
                NetworkHandler.INSTANCE.sendToServer(
                    PacketRtsInteract.entity(
                        PacketRtsInteract.MODE_INTERACT_ENTITY,
                        pick.entity.getEntityId(),
                        pick.dirX,
                        pick.dirY,
                        pick.dirZ));
            } else if (pick.type == RtsPickResult.Type.BLOCK) {
                sendPlacePacket(pick);
            }
        }
        // 中键无单击语义（只有拖拽旋转）
    }

    /** 发一次「对方块使用手中物品」包（放置/互动共用）。 */
    private void sendPlacePacket(RtsPickResult pick) {
        NetworkHandler.INSTANCE.sendToServer(
            PacketRtsInteract.useBlock(
                pick.blockX,
                pick.blockY,
                pick.blockZ,
                pick.side,
                (float) (pick.worldHitX - pick.blockX),
                (float) (pick.worldHitY - pick.blockY),
                (float) (pick.worldHitZ - pick.blockZ),
                pick.dirX,
                pick.dirY,
                pick.dirZ,
                isShiftDown()));
    }

    private static boolean isCtrlDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
    }

    private static boolean isShiftDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 拾取要用「这一帧」的插值姿态（世界渲染同款 partialTicks），
        // 先存再拾取；高亮渲染（RtsOverlayRenderer）读 lastPick
        RtsCursorPicker.setPartialTicks(partialTicks);
        RtsCursorPicker.pick();

        // 不调 drawDefaultBackground：世界必须在底下可见。半透明底板让文字在
        // 任何场景（雪地/沙漠/夜晚）上都读得清。行按需增减，底板跟着撑高
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("§e" + I18n.format("futa_gtnh.rts.hud.title"));
        lines.add("§7" + I18n.format("futa_gtnh.rts.hud.move"));
        lines.add("§7" + I18n.format("futa_gtnh.rts.hud.mouse"));
        lines.add("§7" + I18n.format("futa_gtnh.rts.hud.keys"));
        lines.add(
            "§7" + I18n.format(
                "futa_gtnh.rts.hud.shape",
                I18n.format(
                    RtsBuildPlanner.getShape()
                        .langKey()),
                I18n.format(
                    RtsBuildPlanner.getFill()
                        .langKey()),
                I18n.format(
                    RtsBuildPlanner.getAction() == RtsBuildPlanner.ACTION_BUILD ? "futa_gtnh.rts.mode.build"
                        : "futa_gtnh.rts.mode.destroy")));
        String selection;
        if (!RtsBuildPlanner.hasFirstPoint()) {
            selection = I18n.format("futa_gtnh.rts.hud.select.none");
        } else if (!RtsBuildPlanner.isReady()) {
            selection = I18n.format("futa_gtnh.rts.hud.select.half");
        } else {
            selection = I18n.format("futa_gtnh.rts.hud.select.ready", RtsBuildPlanner.estimatedVolume());
        }
        lines.add("§7" + I18n.format("futa_gtnh.rts.hud.select", selection));
        lines.add("§8" + I18n.format("futa_gtnh.rts.hud.exit"));

        int lineHeight = this.fontRendererObj.FONT_HEIGHT + 2;
        drawRect(4, 4, 4 + 220, 6 + lines.size() * lineHeight + 4, 0x88103048);
        int y = 8;
        for (String line : lines) {
            this.fontRendererObj.drawStringWithShadow(line, 10, y, 0xFFFFFF);
            y += lineHeight;
        }
    }
}
