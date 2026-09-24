package com.futa_gtnh.rts.client;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.ChatComponentTranslation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.futa_gtnh.Config;
import com.futa_gtnh.client.KeyHandler;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketRtsBreak;
import com.futa_gtnh.network.PacketRtsInteract;
import com.futa_gtnh.network.PacketRtsQuickBuild;
import com.futa_gtnh.network.PacketRtsRotate;
import com.futa_gtnh.network.PacketRtsUndoRedo;

/**
 * 俯瞰模式的常驻 HUD：顶部操作栏（模式/形状/填充/撤销重做）+ 世界内光标操作，
 * 一个透明全屏界面（不画世界背景，世界在底下可见），负责接管全部鼠标/键盘。
 *
 * <p>
 * <b>为什么用 GuiScreen 而不是去 mixin 输入层：</b>1.7.10 里打开 GuiScreen
 * 就是原版的「接管输入」机制 —— 鼠标释放显示光标、KeyBinding 全部 unPress
 * （玩家移动输入随之归零）、事件全部路由进界面。相机移动键从
 * {@code Keyboard.isKeyDown} 实时轮询（见 {@link RtsCameraController}）。
 *
 * <p>
 * <b>事件契约（1.3.0 的教训）：</b>{@code GuiScreen.handleInput()} 在外层
 * {@code while (Mouse.next())} 里逐事件调用 {@code handleMouseInput()}，
 * 每次调用对应「当前一个事件」。1.3.0 在覆写里又套了一层
 * {@code while (Mouse.next())}，把当前事件永远跳过 —— 每个 tick 的第一个
 * 鼠标事件必丢，单格滚轮/单击经常整个失效。现在严格按契约处理当前事件。
 *
 * <p>
 * <b>鼠标语义（按 {@link RtsMode} 路由）：</b>
 * <ul>
 * <li>互动模式：右键 = 互动/使用/开 GUI，左键 = 破坏；</li>
 * <li>建造模式：右键 = 放置手中方块（Shift = 潜行语义），Ctrl+右键拖 = 连放；</li>
 * <li>破坏模式：左/右键 = 破坏，Ctrl+右键拖 = 连刷破坏；</li>
 * <li>全模式：左键拖 = 连刷破坏，Ctrl+左键 = 形状选点（按下即选），
 * 中键拖 = 旋转相机，右键拖 = 平移相机，滚轮 = 沿视线推拉，
 * 中键单击 = 拾取准星处方块到快捷栏。</li>
 * </ul>
 * 「拖过阈值才算拖拽」：按下后位移超过 {@link #DRAG_THRESHOLD_PIXELS}
 * 像素才转成拖拽，没超过就在松开时算一次单击。
 *
 * <p>
 * <b>顶栏布局</b>照 RTSBuilding 的规格移植（52px 栏、32×24 按钮、四态高亮、
 * 两行状态文字），图标用原版物品代替其自定义贴图。
 */
public class GuiRtsOverlay extends GuiScreen {

    /** 按下后累计位移超过多少像素才算拖拽。 */
    private static final int DRAG_THRESHOLD_PIXELS = 4;

    // 顶栏布局常量（照 RTSBuilding 的 RtsMainlineLayout 规格）
    private static final int TOP_H = 52;
    private static final int BTN_W = 32;
    private static final int BTN_H = 24;
    private static final int BTN_Y = 4;
    private static final int BTN_GAP = 5;
    private static final int GROUP_GAP = 8;
    private static final int TOP_BG = 0xC0101116;

    // 按钮四态配色（照 QuickBuildStyle：active 亮绿边框是最醒目的识别色）
    private static final int COL_IDLE_BG = 0xAA1F2329;
    private static final int COL_IDLE_BORDER = 0xFF5B6673;
    private static final int COL_HOVER_BG = 0xFF1D2530;
    private static final int COL_HOVER_BORDER = 0xFF7A90AA;
    private static final int COL_ACTIVE_BG = 0xFF2D6B47;
    private static final int COL_ACTIVE_BORDER = 0xFF9AD2AE;

    /** 顶栏按钮。 */
    private static final class TopbarButton {

        enum Type {

            MODE_INTERACT,
            MODE_BUILD,
            MODE_DESTROY,
            SHAPE,
            FILL,
            UNDO,
            REDO
        }

        final Type type;
        /** 撤销/重做按钮的 x 依赖屏幕宽度，在 initGui 里重算，所以不能 final。 */
        int x;
        /** 互动/建造/破坏按钮的物品图标；文字按钮为 null。 */
        final ItemStack icon;
        final String langId;

        TopbarButton(Type type, int x, ItemStack icon, String langId) {
            this.type = type;
            this.x = x;
            this.icon = icon;
            this.langId = langId;
        }
    }

    private final List<TopbarButton> buttons = new ArrayList<>();

    // 鼠标状态
    /** 当前按住的鼠标键（-1 = 没按住）。 */
    private int activeButton = -1;
    /** 已经判定为拖拽。 */
    private boolean dragging;
    /** 本次按下已被 UI 消费（顶栏按钮 / 形状选点），松开时不再走世界点击。 */
    private boolean pressConsumed;
    /** 按下时的屏幕坐标（判定拖拽阈值用）。 */
    private int pressX, pressY;
    /** 上一个鼠标事件的位置（算增量用）。 */
    private int lastMouseX, lastMouseY;
    /** 拖动连刷的上一目标格（目标没变就不重复发包）。 */
    private int paintTargetX = Integer.MIN_VALUE, paintTargetY, paintTargetZ;

    public GuiRtsOverlay() {
        // 互动/建造/破坏用固定物品图标（拉杆=互动、砖块=建造、铁镐=破坏），
        // 形状/填充/撤销/重做画文字（形状/填充直接显示当前值，比图标更直观）
        buttons.add(new TopbarButton(TopbarButton.Type.MODE_INTERACT, 8, new ItemStack(Blocks.lever), "interact"));
        buttons.add(
            new TopbarButton(
                TopbarButton.Type.MODE_BUILD,
                8 + BTN_W + BTN_GAP,
                new ItemStack(Blocks.brick_block),
                "build"));
        buttons.add(
            new TopbarButton(
                TopbarButton.Type.MODE_DESTROY,
                8 + (BTN_W + BTN_GAP) * 2,
                new ItemStack(Items.iron_pickaxe),
                "destroy"));
        // 模式组与工具组之间加组间距（RTSBuilding 规格）
        buttons.add(
            new TopbarButton(TopbarButton.Type.SHAPE, 8 + (BTN_W + BTN_GAP) * 2 + GROUP_GAP + BTN_GAP, null, "shape"));
        buttons.add(
            new TopbarButton(
                TopbarButton.Type.FILL,
                8 + (BTN_W + BTN_GAP) * 2 + GROUP_GAP + BTN_GAP * 2 + BTN_W,
                null,
                "fill"));
        // 撤销/重做右对齐（x 在 initGui 里按屏幕宽度算）
        buttons.add(new TopbarButton(TopbarButton.Type.UNDO, 0, null, "undo"));
        buttons.add(new TopbarButton(TopbarButton.Type.REDO, 0, null, "redo"));
    }

    @Override
    public void initGui() {
        // 重算右对齐按钮 + 复位全部鼠标状态（界面被顶掉再重开时可能残留按下态）
        for (TopbarButton b : buttons) {
            if (b.type == TopbarButton.Type.REDO) b.x = this.width - 8 - BTN_W;
            if (b.type == TopbarButton.Type.UNDO) b.x = this.width - 8 - BTN_W * 2 - BTN_GAP;
        }
        activeButton = -1;
        dragging = false;
        pressConsumed = false;
        paintTargetX = Integer.MIN_VALUE;
    }

    @Override
    public boolean doesGuiPauseGame() {
        // 单人里打开界面也不暂停世界：俯瞰模式下挂机/工作时长会很久
        return false;
    }

    // ==================================================================
    // 键盘
    // ==================================================================

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        // 整合包若开启 lwjgl3ify 的 alwaysRepeatKeys，长按会连发事件：
        // 模式/形状切换必须过滤重复键（1.3.0 无防护，「切换有 BUG」的次要来源）
        if (Keyboard.isRepeatEvent()) return;

        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == KeyHandler.getRtsToggleKeyCode()) {
            RtsClientState.exit();
            return;
        }
        switch (keyCode) {
            case Keyboard.KEY_V:
                RtsBuildPlanner.cycleMode();
                return;
            case Keyboard.KEY_B:
            case Keyboard.KEY_TAB:
                RtsBuildPlanner.cycleShape();
                return;
            case Keyboard.KEY_F:
                RtsBuildPlanner.cycleFill();
                return;
            case Keyboard.KEY_C:
                RtsBuildPlanner.clearPoints();
                return;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                confirmQuickBuild();
                return;
            case Keyboard.KEY_Z:
                if (isCtrlDown()) {
                    NetworkHandler.INSTANCE.sendToServer(new PacketRtsUndoRedo(PacketRtsUndoRedo.ACTION_UNDO));
                }
                return;
            case Keyboard.KEY_Y:
                if (isCtrlDown()) {
                    NetworkHandler.INSTANCE.sendToServer(new PacketRtsUndoRedo(PacketRtsUndoRedo.ACTION_REDO));
                }
                return;
            case Keyboard.KEY_R: {
                RtsPickResult pick = RtsCursorPicker.pick();
                if (pick.type == RtsPickResult.Type.BLOCK) {
                    NetworkHandler.INSTANCE.sendToServer(new PacketRtsRotate(pick.blockX, pick.blockY, pick.blockZ));
                }
                return;
            }
            default: {
                // 数字键 1-9 选快捷栏槽位。俯瞰界面开着时原版热键逻辑被
                // allowUserInput 门控跳过，必须自己做（含 C09 同步到服务端）
                int slot = hotbarSlotForKey(keyCode);
                if (slot >= 0) selectHotbarSlot(slot);
                return;
            }
        }
    }

    private static int hotbarSlotForKey(int keyCode) {
        switch (keyCode) {
            case Keyboard.KEY_1:
            case Keyboard.KEY_NUMPAD1:
                return 0;
            case Keyboard.KEY_2:
            case Keyboard.KEY_NUMPAD2:
                return 1;
            case Keyboard.KEY_3:
            case Keyboard.KEY_NUMPAD3:
                return 2;
            case Keyboard.KEY_4:
            case Keyboard.KEY_NUMPAD4:
                return 3;
            case Keyboard.KEY_5:
            case Keyboard.KEY_NUMPAD5:
                return 4;
            case Keyboard.KEY_6:
            case Keyboard.KEY_NUMPAD6:
                return 5;
            case Keyboard.KEY_7:
            case Keyboard.KEY_NUMPAD7:
                return 6;
            case Keyboard.KEY_8:
            case Keyboard.KEY_NUMPAD8:
                return 7;
            case Keyboard.KEY_9:
            case Keyboard.KEY_NUMPAD9:
                return 8;
            default:
                return -1;
        }
    }

    /** 本地切快捷栏槽位并同步服务端（原版放置逻辑用服务端的 currentItem 取物品）。 */
    private static void selectHotbarSlot(int slot) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        mc.thePlayer.inventory.currentItem = slot;
        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(slot));
    }

    /** 中键单击：拾取准星处方块 —— 在快捷栏里找同款并切过去（找不到时提示）。 */
    private static void pickBlock() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        RtsPickResult pick = RtsCursorPicker.pick();
        if (pick.type != RtsPickResult.Type.BLOCK) return;

        Block block = mc.theWorld.getBlock(pick.blockX, pick.blockY, pick.blockZ);
        Item item = block.getItem(mc.theWorld, pick.blockX, pick.blockY, pick.blockZ);
        if (item == null) return;
        int meta = mc.theWorld.getBlockMetadata(pick.blockX, pick.blockY, pick.blockZ);

        // 两遍匹配：先 item+meta 精确，再只按 item（同物品多 meta 的兜底）
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 9; i++) {
                ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
                if (stack == null || stack.getItem() != item) continue;
                if (pass == 0 && stack.getItemDamage() != meta) continue;
                selectHotbarSlot(i);
                return;
            }
        }
        chat("futa_gtnh.rts.msg.pick_none");
    }

    /** 回车：把当前形状/填充/角点作为批量任务提交（含义由模式决定）。 */
    private void confirmQuickBuild() {
        if (!RtsBuildPlanner.isReady()) return;
        if (RtsBuildPlanner.getMode() == RtsMode.INTERACT) {
            chat("futa_gtnh.rts.msg.need_mode");
            return;
        }
        if (!RtsBuildPlanner.canSubmit()) {
            // 本地先拦一道并说清原因（服务端还会再校验一遍）
            chat(
                !RtsBuildPlanner.isWithinLocalLimits() ? "futa_gtnh.rts.msg.too_large_local"
                    : "futa_gtnh.rts.msg.need_itemblock");
            return;
        }
        int[] a = RtsBuildPlanner.getPointA();
        int[] b = RtsBuildPlanner.getPointB();
        NetworkHandler.INSTANCE.sendToServer(
            PacketRtsQuickBuild.of(
                RtsBuildPlanner.getSubmitAction(),
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

    private static void chat(String langKey) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) {
            mc.thePlayer.addChatMessage(new ChatComponentTranslation(langKey));
        }
    }

    // ==================================================================
    // 鼠标（严格按原版契约：本方法只处理「当前这一个事件」，
    // 外层 GuiScreen.handleInput 已经在 while (Mouse.next()) 里循环调用）
    // ==================================================================

    @Override
    public void handleMouseInput() {
        Minecraft mc = Minecraft.getMinecraft();
        int x = Mouse.getEventX() * this.width / Math.max(1, mc.displayWidth);
        int y = this.height - Mouse.getEventY() * this.height / Math.max(1, mc.displayHeight) - 1;
        int button = Mouse.getEventButton();

        if (button == -1) {
            // 移动/滚轮事件：只有在有按键按住时才有拖拽语义
            if (activeButton != -1) {
                if (!dragging && Math.abs(x - pressX) + Math.abs(y - pressY) > DRAG_THRESHOLD_PIXELS) {
                    dragging = true;
                }
                if (dragging) {
                    routeDrag(activeButton, x - lastMouseX, y - lastMouseY);
                }
            }
        } else if (Mouse.getEventButtonState()) {
            onPress(x, y, button);
        } else {
            onRelease(x, y, button);
        }
        lastMouseX = x;
        lastMouseY = y;

        // 滚轮是独立通道（事件本身 button == -1），读当前事件的 dWheel
        int dWheel = Mouse.getEventDWheel();
        if (dWheel != 0) {
            RtsCameraController.addWheel(dWheel);
        }
    }

    private void onPress(int x, int y, int button) {
        activeButton = button;
        dragging = false;
        pressX = x;
        pressY = y;
        pressConsumed = false;

        // 顶栏按钮：按下即生效（原版按钮语义）
        if (y < TOP_H) {
            pressConsumed = true;
            handleTopbarPress(x, y);
            return;
        }

        // Ctrl+左键 = 形状选点：按下即选（松开才选的话，轻微手抖判定成拖拽
        // 就会把选点吞掉）
        if (button == 0 && isCtrlDown()) {
            RtsPickResult pick = RtsCursorPicker.pick();
            if (pick.type == RtsPickResult.Type.BLOCK) {
                RtsBuildPlanner.setPoint(pick.blockX, pick.blockY, pick.blockZ);
            }
            pressConsumed = true;
        }
    }

    private void onRelease(int x, int y, int button) {
        boolean wasDrag = dragging;
        boolean wasConsumed = pressConsumed;
        if (button == activeButton) {
            activeButton = -1;
            dragging = false;
            pressConsumed = false;
            // 松开后连刷目标作废：下一次按住重新从当前光标处开始
            paintTargetX = Integer.MIN_VALUE;
        }
        if (wasDrag || wasConsumed) return;
        onClick(x, y, button);
    }

    /** 单击路由（拖拽阈值内松开才算点击；按模式路由，见类注释）。 */
    private void onClick(int x, int y, int button) {
        if (y < TOP_H) return;
        RtsPickResult pick = RtsCursorPicker.pick();
        RtsMode mode = RtsBuildPlanner.getMode();

        if (button == 0) {
            // 左键：破坏方块 / 攻击实体（全模式一致）
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
            // 右键按模式分流：互动=互动/使用，建造=放置，破坏=破坏
            if (mode == RtsMode.BUILD) {
                if (pick.type == RtsPickResult.Type.BLOCK) {
                    sendPlacePacket(pick);
                } else if (pick.type == RtsPickResult.Type.ENTITY) {
                    sendEntityInteract(pick);
                }
            } else if (mode == RtsMode.DESTROY) {
                if (pick.type == RtsPickResult.Type.BLOCK) {
                    NetworkHandler.INSTANCE.sendToServer(new PacketRtsBreak(pick.blockX, pick.blockY, pick.blockZ));
                } else if (pick.type == RtsPickResult.Type.ENTITY) {
                    sendEntityAttack(pick);
                }
            } else {
                if (pick.type == RtsPickResult.Type.ENTITY) {
                    sendEntityInteract(pick);
                } else if (pick.type == RtsPickResult.Type.BLOCK) {
                    sendPlacePacket(pick);
                }
            }
            return;
        }

        if (button == 2) {
            // 中键单击：拾取方块（中键拖拽是旋转相机，由拖拽阈值区分）
            pickBlock();
        }
    }

    /**
     * 拖拽路由（相机操作与模式无关；连刷按模式/修饰键分流）：
     * 中键拖 = 旋转，右键拖（无修饰）= 平移，左键拖 = 连刷破坏，
     * Ctrl+右键拖 = 建造模式连放 / 破坏模式连刷破坏。
     */
    private void routeDrag(int button, int dx, int dy) {
        // 在顶栏里按下的拖拽不进世界（防止按按钮时手抖转动相机）
        if (pressY < TOP_H) return;

        if (button == 2) {
            RtsCameraController.addRotateDrag(dx, dy);
            return;
        }
        if (button == 1 && !isCtrlDown()) {
            RtsCameraController.addPanDrag(dx, dy);
            return;
        }
        // 连刷：目标格没变就不发包（一次拖动跨半屏也只有几十个新格子，
        // 服务端限频是第二道保险）
        if (button == 0 && !isCtrlDown()) {
            paintBreak();
        } else if (button == 1) {
            if (RtsBuildPlanner.getMode() == RtsMode.DESTROY) {
                paintBreak();
            } else {
                paintPlace();
            }
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

    private void sendEntityInteract(RtsPickResult pick) {
        NetworkHandler.INSTANCE.sendToServer(
            PacketRtsInteract.entity(
                PacketRtsInteract.MODE_INTERACT_ENTITY,
                pick.entity.getEntityId(),
                pick.dirX,
                pick.dirY,
                pick.dirZ));
    }

    private void sendEntityAttack(RtsPickResult pick) {
        NetworkHandler.INSTANCE.sendToServer(
            PacketRtsInteract.entity(
                PacketRtsInteract.MODE_ATTACK_ENTITY,
                pick.entity.getEntityId(),
                pick.dirX,
                pick.dirY,
                pick.dirZ));
    }

    private static boolean isCtrlDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
    }

    private static boolean isShiftDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
    }

    // ==================================================================
    // 顶栏
    // ==================================================================

    private void handleTopbarPress(int x, int y) {
        TopbarButton hit = hitTestTopbar(x, y);
        if (hit == null) return;
        switch (hit.type) {
            case MODE_INTERACT:
                RtsBuildPlanner.setMode(RtsMode.INTERACT);
                return;
            case MODE_BUILD:
                RtsBuildPlanner.setMode(RtsMode.BUILD);
                return;
            case MODE_DESTROY:
                RtsBuildPlanner.setMode(RtsMode.DESTROY);
                return;
            case SHAPE:
                RtsBuildPlanner.cycleShape();
                return;
            case FILL:
                RtsBuildPlanner.cycleFill();
                return;
            case UNDO:
                NetworkHandler.INSTANCE.sendToServer(new PacketRtsUndoRedo(PacketRtsUndoRedo.ACTION_UNDO));
                return;
            case REDO:
                NetworkHandler.INSTANCE.sendToServer(new PacketRtsUndoRedo(PacketRtsUndoRedo.ACTION_REDO));
                return;
        }
    }

    private TopbarButton hitTestTopbar(int x, int y) {
        for (TopbarButton b : buttons) {
            if (x >= b.x && x < b.x + BTN_W && y >= BTN_Y && y < BTN_Y + BTN_H) return b;
        }
        return null;
    }

    // ==================================================================
    // 绘制
    // ==================================================================

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // 拾取要用「这一帧」的插值姿态（世界渲染同款 partialTicks）；
        // 高亮渲染（RtsOverlayRenderer）与点击路由都读 lastPick
        RtsCursorPicker.setPartialTicks(partialTicks);
        RtsCursorPicker.pick();

        drawTopbar(mouseX, mouseY);
        drawModeToast();

        // 底部按键提示（一行一条，小字）
        String[] hints = new String[] { I18n.format("futa_gtnh.rts.hud.move"), I18n.format("futa_gtnh.rts.hud.keys"),
            I18n.format("futa_gtnh.rts.hud.exit") };
        int lineH = this.fontRendererObj.FONT_HEIGHT + 2;
        int boxH = hints.length * lineH + 6;
        drawRect(4, this.height - boxH - 4, 4 + 250, this.height - 4, 0x88103048);
        int ty = this.height - boxH;
        for (String hint : hints) {
            this.fontRendererObj.drawStringWithShadow("§7" + hint, 10, ty, 0xFFFFFF);
            ty += lineH;
        }

        drawTopbarTooltip(mouseX, mouseY);
    }

    private void drawTopbar(int mouseX, int mouseY) {
        drawRect(0, 0, this.width, TOP_H, TOP_BG);

        RtsMode mode = RtsBuildPlanner.getMode();
        ItemStack held = RtsMode.showHeldItem(mode, this.mc.thePlayer);

        for (TopbarButton b : buttons) {
            boolean active;
            switch (b.type) {
                case MODE_INTERACT:
                    active = mode == RtsMode.INTERACT;
                    break;
                case MODE_BUILD:
                    active = mode == RtsMode.BUILD;
                    break;
                case MODE_DESTROY:
                    active = mode == RtsMode.DESTROY;
                    break;
                default:
                    active = false;
                    break;
            }
            boolean hovered = mouseX >= b.x && mouseX < b.x + BTN_W && mouseY >= BTN_Y && mouseY < BTN_Y + BTN_H;
            boolean pressed = hovered && activeButton == 0 && pressConsumed;
            drawTopbarButton(b, hovered, active, pressed);
        }

        // --- 状态行 1：模式 + 手持物品（建造模式） ---------------------------
        String modeText = I18n.format("futa_gtnh.rts.hud.mode1", I18n.format(mode.langKey()));
        this.fontRendererObj.drawStringWithShadow(modeText, 8, 33, 0xFFF2F6FB);
        int iconX = 8 + this.fontRendererObj.getStringWidth(modeText) + 10;
        if (held != null) {
            drawItemIcon(held, iconX, 28);
            String heldText = held.getDisplayName() + " x" + held.stackSize;
            this.fontRendererObj.drawStringWithShadow(heldText, iconX + 18, 33, 0xFFE8F0F8);
        } else if (mode == RtsMode.BUILD) {
            this.fontRendererObj
                .drawStringWithShadow(I18n.format("futa_gtnh.rts.hud.held_none"), iconX, 33, 0xFF8A97A3);
        }

        // --- 状态行 2：选点 / 右键语义 / 半径 --------------------------------
        String selection;
        if (!RtsBuildPlanner.hasFirstPoint()) {
            selection = I18n.format("futa_gtnh.rts.hud.select.none");
        } else if (!RtsBuildPlanner.isReady()) {
            selection = I18n.format("futa_gtnh.rts.hud.select.half");
        } else {
            selection = I18n.format("futa_gtnh.rts.hud.select.ready", RtsBuildPlanner.estimatedVolume());
        }
        String line2 = I18n.format("futa_gtnh.rts.hud.select", selection) + "   "
            + I18n.format("futa_gtnh.rts.hud.rmb", I18n.format(mode.rmbLangKey()))
            + "   "
            + I18n.format("futa_gtnh.rts.hud.radius", Config.rtsMaxActionRadius);
        this.fontRendererObj.drawStringWithShadow(line2, 8, 44, 0xFFC9D8E8);

        // 超出操作半径的目标：右侧红字预警（服务端会拒绝，先说清楚）
        if (RtsCursorPicker.isBeyondPlayerRange(RtsCursorPicker.getLastPick())) {
            String warn = I18n.format("futa_gtnh.rts.hud.range_warning");
            this.fontRendererObj
                .drawStringWithShadow(warn, this.width - 8 - this.fontRendererObj.getStringWidth(warn), 44, 0xFFFF6E6E);
        }
    }

    private void drawTopbarButton(TopbarButton b, boolean hovered, boolean active, boolean pressed) {
        int bg = pressed ? COL_ACTIVE_BG : (active ? COL_ACTIVE_BG : (hovered ? COL_HOVER_BG : COL_IDLE_BG));
        int border = pressed ? COL_ACTIVE_BORDER
            : (active ? COL_ACTIVE_BORDER : (hovered ? COL_HOVER_BORDER : COL_IDLE_BORDER));
        drawRect(b.x, BTN_Y, b.x + BTN_W, BTN_Y + BTN_H, border);
        drawRect(b.x + 1, BTN_Y + 1, b.x + BTN_W - 1, BTN_Y + BTN_H - 1, bg);

        if (b.icon != null) {
            // 16×16 物品图标在 32×24 按钮内居中
            drawItemIcon(b.icon, b.x + 8, BTN_Y + 4);
            return;
        }

        // 文字按钮：形状/填充显示当前值，撤销/重做显示固定标签
        String text;
        switch (b.type) {
            case SHAPE:
                text = I18n.format(
                    RtsBuildPlanner.getShape()
                        .langKey());
                break;
            case FILL:
                text = I18n.format(
                    RtsBuildPlanner.getFill()
                        .langKey());
                break;
            case UNDO:
                text = I18n.format("futa_gtnh.rts.topbar.undo");
                break;
            case REDO:
                text = I18n.format("futa_gtnh.rts.topbar.redo");
                break;
            default:
                text = "";
                break;
        }
        drawScaledCenteredText(text, b.x + BTN_W / 2.0F, BTN_Y + BTN_H / 2.0F - 3, 0.75F, 0xFFD8E3EE);
    }

    /** 模式切换的大字提示：屏幕中上方 2 秒渐隐（1.3.0 切换零反馈的反面）。 */
    private void drawModeToast() {
        if (!RtsBuildPlanner.hasRecentModeToast()) return;
        RtsMode mode = RtsBuildPlanner.getMode();
        long elapsed = System.currentTimeMillis() - RtsBuildPlanner.getModeChangedAt();
        float alpha = elapsed < 1200L ? 1.0F : 1.0F - (elapsed - 1200L) / 800.0F;
        int alphaBits = (int) (alpha * 220.0F) << 24;

        int rgb;
        switch (mode) {
            case BUILD:
                rgb = 0x6EC6FF;
                break;
            case DESTROY:
                rgb = 0xFF7043;
                break;
            default:
                rgb = 0xFFD54F;
                break;
        }

        String text = I18n.format("futa_gtnh.rts.toast.mode", I18n.format(mode.langKey()));
        GL11.glPushMatrix();
        GL11.glTranslatef(this.width / 2.0F, 66.0F, 0.0F);
        GL11.glScalef(2.0F, 2.0F, 1.0F);
        this.fontRendererObj
            .drawStringWithShadow(text, -this.fontRendererObj.getStringWidth(text) / 2, 0, alphaBits | rgb);
        GL11.glPopMatrix();
    }

    private void drawTopbarTooltip(int mouseX, int mouseY) {
        TopbarButton hit = hitTestTopbar(mouseX, mouseY);
        if (hit == null) return;
        String tip = I18n.format("futa_gtnh.rts.topbar." + hit.langId + ".tip");
        drawHoveringText(Collections.singletonList(tip), mouseX, mouseY, this.fontRendererObj);
    }

    /** 16×16 物品图标（GuiContainer 同款 GL 状态顺序）。 */
    private void drawItemIcon(ItemStack stack, int x, int y) {
        RenderHelper.enableGUIStandardItemLighting();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        itemRender.zLevel = 100.0F;
        itemRender.renderItemAndEffectIntoGUI(this.fontRendererObj, this.mc.getTextureManager(), stack, x, y);
        itemRender.renderItemOverlayIntoGUI(this.fontRendererObj, this.mc.getTextureManager(), stack, x, y, null);
        itemRender.zLevel = 0.0F;
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.disableStandardItemLighting();
    }

    /** 缩放文字（水平居中于 centerX），顶栏文字按钮和模式大字提示用。 */
    private void drawScaledCenteredText(String text, float centerX, float y, float scale, int color) {
        if (text == null || text.isEmpty()) return;
        GL11.glPushMatrix();
        GL11.glTranslatef(centerX, y, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        this.fontRendererObj.drawStringWithShadow(text, -this.fontRendererObj.getStringWidth(text) / 2, 0, color);
        GL11.glPopMatrix();
    }
}
