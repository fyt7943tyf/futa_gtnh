package com.futa_gtnh.client;

import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 往原版背包 / 创造模式界面的 buttonList 里注入一枚「客户端功能设置」齿轮按钮。
 *
 * <p>
 * <b>实现方式</b>与 EZMiner 的 InventoryButtonOverlay 相同，全部走 Forge 事件、
 * 无 mixin 无第三方依赖（<b>不是</b> FTBLib 那一类的侧栏按钮）：
 * <ul>
 * <li>{@code InitGuiEvent.Post}：把 16×16 图标按钮塞进 {@link GuiInventory} /
 * {@link GuiContainerCreative} 的 buttonList；</li>
 * <li>{@code DrawScreenEvent.Pre}：每帧重定位 —— 先按 ServerUtilities 侧栏
 * （{@code serverutils.client.gui.GuiSidebar}，反射定位）的底边对齐作为起点，
 * 再做一轮<b>通用碰撞检测</b>：与 buttonList 里任何其他可见按钮相交就推到其下方。
 * SU 的包围盒是它自己每帧算的、实测未必盖住全部按钮，所以最终位置以碰撞检测
 * 为准，不依赖任何侧栏的内部实现；</li>
 * <li>{@code DrawScreenEvent.Post}：悬停 tooltip（绕开 protected 的
 * drawHoveringText，用 GL11 手画一个原版样式的黑底紫边提示框）；</li>
 * <li>{@code ActionPerformedEvent.Post}：点击打开 {@link GuiClientConfig}。</li>
 * </ul>
 *
 * <p>
 * 反射字段一律 MCP 名优先、SRG 名兜底 —— 生产环境的按钮坐标字段是混淆名，
 * 只写 MCP 名的话这个按钮在正式环境里会定位到错误的地方。
 */
@SideOnly(Side.CLIENT)
public class ClientFeatureButtonOverlay {

    private static final int BTN_ID = 1043;
    private static final int BTN_SIZE = 16;

    /** ServerUtilities 侧栏列相对 guiLeft 的横偏移（GuiSidebar.setButtonLocations 的布局）。 */
    private static final int SU_OFFSET_X = 18;
    private static final int INV_X_SIZE = 176;
    private static final int INV_Y_SIZE = 166;
    private static final int CREATIVE_X_SIZE = 195;
    private static final int CREATIVE_Y_SIZE = 136;
    private static final int FALLBACK_BOTTOM_MARGIN = 4;

    // SRG 名：生产环境（混淆）用，MCP 名（开发环境）优先尝试
    private static final String SRG_BUTTON_LIST = "field_146292_n"; // GuiScreen.buttonList
    private static final String SRG_X_POS = "field_146128_h"; // GuiButton.xPosition
    private static final String SRG_Y_POS = "field_146129_i"; // GuiButton.yPosition
    private static final String SRG_WIDTH = "field_146120_f"; // GuiButton.width
    private static final String SRG_HEIGHT = "field_146121_g"; // GuiButton.height

    /** 1.7.10 会把 ResourceLocation 的 domain 转小写，贴图路径必须全小写。 */
    private static final ResourceLocation SETTINGS_TEXTURE = new ResourceLocation(
        "futa_gtnh",
        "textures/gui/settings_button.png");

    // 反射缓存（一次性解析；SU 不在场上时 suSidebarClass 为 null，测量降级）
    private static boolean suLookupDone;
    private static Class<?> suSidebarClass;
    private static Field buttonListField;
    private static Field suXField;
    private static Field suYField;
    private static Field suWField;
    private static Field suHField;

    private TexturedButton ourButton;

    // ==================================================================
    // 事件
    // ==================================================================

    /** 背包/创造模式界面初始化完成后注入按钮；真实位置每帧由 onDrawScreenPre 校正。 */
    @SubscribeEvent
    public void onInitGui(GuiScreenEvent.InitGuiEvent.Post event) {
        int[] offsets = guiOffsets(event.gui);
        if (offsets == null) return;
        int btnX = offsets[0] - SU_OFFSET_X;
        int btnY = offsets[1] + offsets[2] - BTN_SIZE - FALLBACK_BOTTOM_MARGIN;
        ourButton = new TexturedButton(BTN_ID, btnX, btnY, SETTINGS_TEXTURE);
        event.buttonList.add(ourButton);
    }

    /** 每帧把按钮放到 SU 侧栏下方（SU 不在场时退回面板左下角）。 */
    @SubscribeEvent
    public void onDrawScreenPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (ourButton == null) return;
        int[] offsets = guiOffsets(event.gui);
        if (offsets == null) return;

        Rectangle suArea = getSuArea(event.gui);
        int btnX;
        int btnY;
        if (suArea != null && suArea.width > 0 && suArea.height > 0) {
            // 和 SU 侧栏同列对齐，贴着它测出来的底边往下放（+2 跳过 SU 的包围盒内边距）
            btnX = suArea.x + 2;
            btnY = suArea.y + suArea.height;
        } else {
            btnX = offsets[0] - SU_OFFSET_X;
            btnY = offsets[1] + offsets[2] - BTN_SIZE - FALLBACK_BOTTOM_MARGIN;
        }

        // SU 的包围盒是它自己每帧算的，量出来未必盖住全部按钮（实测 GTNH 整合包里
        // 会和侧栏最底下的按钮叠上）。所以测量结果只当「起点」，最终位置由通用
        // 碰撞检测决定：和任何其他可见按钮相交就推到那个按钮下方，推到不叠为止。
        int[] pos = resolveOverlap(event.gui, btnX, btnY);
        ourButton.xPosition = pos[0];
        ourButton.yPosition = pos[1];
    }

    /** 悬停 tooltip（SU 按钮/物品格都不会被遮：这个框画在最上层）。 */
    @SubscribeEvent
    public void onDrawScreenPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (ourButton == null || !ourButton.visible) return;
        if (guiOffsets(event.gui) == null) return;
        if (event.mouseX >= ourButton.xPosition && event.mouseY >= ourButton.yPosition
            && event.mouseX < ourButton.xPosition + BTN_SIZE
            && event.mouseY < ourButton.yPosition + BTN_SIZE) {
            drawTooltip(
                Minecraft.getMinecraft().fontRenderer,
                net.minecraft.util.StatCollector.translateToLocal("futa_gtnh.clientconfig.inventory_button"),
                event.mouseX,
                event.mouseY,
                event.gui.width);
        }
    }

    /** 点击打开客户端功能设置界面。 */
    @SubscribeEvent
    public void onActionPerformed(GuiScreenEvent.ActionPerformedEvent.Post event) {
        if (guiOffsets(event.gui) == null) return;
        if (event.button.id != BTN_ID) return;
        // 不把背包界面当 ESC 返回目标：displayGuiScreen 会触发 GuiInventory.onGuiClosed
        // （关容器包已发出），再回到那个实例只会得到一份和服务器不同步的死界面
        Minecraft.getMinecraft()
            .displayGuiScreen(new GuiClientConfig());
    }

    /** 注册到 Forge 总线（GUI 事件都在这条总线上）。由 {@code ClientProxy.preInit} 调用。 */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new ClientFeatureButtonOverlay());
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    /**
     * 通用防重叠：从起点开始，只要和 buttonList 里<b>任何其他可见按钮</b>相交，
     * 就推到相交按钮中「最靠下的底边」之下，直到腾出空间（上限 32 轮防死循环）。
     *
     * <p>
     * SU 侧栏的包围盒是它自己每帧算的，实测在这套整合包里未必盖住全部按钮，
     * 所以测量结果只能当起点，最终位置必须以碰撞检测为准 —— 这样无论侧栏是
     * 一列还是两列、有没有别的模组往里塞按钮，都不会叠。
     */
    private int[] resolveOverlap(GuiScreen gui, int x, int y) {
        ensureReflection();
        Rectangle mine = new Rectangle(x, y, BTN_SIZE, BTN_SIZE);
        List<GuiButton> buttons = buttonsOf(gui);
        if (buttons == null) return new int[] { mine.x, mine.y };

        for (int pass = 0; pass < 32; pass++) {
            Rectangle lowest = null;
            for (GuiButton button : buttons) {
                if (button == ourButton || !button.visible) continue;
                Rectangle rect = boundsOf(gui, button);
                if (rect == null || !mine.intersects(rect)) continue;
                // 一次跳过与当前矩形相交的按钮里最靠下的那个，少走几轮
                if (lowest == null || rect.y + rect.height > lowest.y + lowest.height) lowest = rect;
            }
            if (lowest == null) break;
            mine.y = lowest.y + lowest.height;
        }

        // 极端情况（屏幕被按钮塞满）贴屏幕底边收场，不再无限往下
        if (mine.y + BTN_SIZE > gui.height) mine.y = Math.max(0, gui.height - BTN_SIZE);
        return new int[] { mine.x, mine.y };
    }

    /** @return 按钮包围盒；尺寸为 0（还没画过第一帧）或坐标离谱（侧栏全隐藏时的溢出值）返回 null。 */
    private static Rectangle boundsOf(GuiScreen gui, GuiButton button) {
        if (button.width <= 0 || button.height <= 0) return null;
        if (button.xPosition < 0 || button.yPosition < 0) return null;
        if (button.xPosition >= gui.width || button.yPosition >= gui.height) return null;
        return new Rectangle(button.xPosition, button.yPosition, button.width, button.height);
    }

    /** @return 界面的 buttonList（反射，MCP 名优先 SRG 兜底）；拿不到返回 null。 */
    @SuppressWarnings("unchecked")
    private static List<GuiButton> buttonsOf(GuiScreen gui) {
        if (ensureReflection() && buttonListField != null) {
            try {
                return (List<GuiButton>) buttonListField.get(gui);
            } catch (Throwable ignored) {
                // 理论上到不了这里；拿不到就保持起点位置
            }
        }
        return null;
    }

    /** 一次性解析全部反射字段；失败的字段保持 null，对应能力降级。 */
    private static boolean ensureReflection() {
        if (suLookupDone) return suSidebarClass != null || buttonListField != null;
        suLookupDone = true;
        try {
            suSidebarClass = Class.forName("serverutils.client.gui.GuiSidebar");
        } catch (Throwable ignored) {
            // SU 不在 classpath 上：侧栏测量降级为固定位置，碰撞检测照常工作
        }
        buttonListField = getDeclaredFieldMcpSrg(GuiScreen.class, "buttonList", SRG_BUTTON_LIST);
        suXField = getFieldMcpSrg(GuiButton.class, "xPosition", SRG_X_POS);
        suYField = getFieldMcpSrg(GuiButton.class, "yPosition", SRG_Y_POS);
        suWField = getFieldMcpSrg(GuiButton.class, "width", SRG_WIDTH);
        suHField = getFieldMcpSrg(GuiButton.class, "height", SRG_HEIGHT);
        return buttonListField != null;
    }

    /** @return [guiLeft, guiTop, 面板高]，不是受支持的背包类界面返回 null。 */
    private static int[] guiOffsets(GuiScreen gui) {
        if (gui instanceof GuiInventory) {
            return new int[] { (gui.width - INV_X_SIZE) / 2, (gui.height - INV_Y_SIZE) / 2, INV_Y_SIZE };
        }
        if (gui instanceof GuiContainerCreative) {
            return new int[] { (gui.width - CREATIVE_X_SIZE) / 2, (gui.height - CREATIVE_Y_SIZE) / 2, CREATIVE_Y_SIZE };
        }
        return null;
    }

    /** MCP 字段名优先、SRG 兜底地拿 public 字段。 */
    private static Field getFieldMcpSrg(Class<?> clazz, String mcp, String srg) {
        try {
            Field f = clazz.getField(mcp);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            try {
                Field f = clazz.getField(srg);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e2) {
                return null;
            }
        }
    }

    private static Field getDeclaredFieldMcpSrg(Class<?> clazz, String mcp, String srg) {
        try {
            Field f = clazz.getDeclaredField(mcp);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException e) {
            try {
                Field f = clazz.getDeclaredField(srg);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e2) {
                return null;
            }
        }
    }

    /**
     * 反射找 ServerUtilities 侧栏按钮的包围盒；SU 不在场 / 还没画过第一帧（边界为 0）/
     * 侧栏全隐藏（GuiSidebar 的包围盒计算会给出接近 {@code Integer.MAX_VALUE} 的坐标，
     * 见 EZMiner 的同款防御）都返回 null。
     */
    private static Rectangle getSuArea(GuiScreen gui) {
        if (!ensureReflection() || suSidebarClass == null || buttonListField == null) return null;
        try {
            @SuppressWarnings("unchecked")
            List<GuiButton> buttonList = (List<GuiButton>) buttonListField.get(gui);
            for (GuiButton btn : buttonList) {
                if (suSidebarClass.isInstance(btn)) {
                    int w = suWField.getInt(btn);
                    int h = suHField.getInt(btn);
                    if (w <= 0 || h <= 0) return null;
                    int x = suXField.getInt(btn);
                    int y = suYField.getInt(btn);
                    if (x < 0 || y < 0 || x >= gui.width || y >= gui.height) return null;
                    return new Rectangle(x, y, w, h);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 16×16 图标按钮：自绘贴图 + 悬停提亮，风格与 EZMiner 的 TexturedButton 一致。 */
    private static final class TexturedButton extends GuiButton {

        private final ResourceLocation texture;

        TexturedButton(int id, int x, int y, ResourceLocation texture) {
            super(id, x, y, BTN_SIZE, BTN_SIZE, "");
            this.texture = texture;
        }

        @Override
        public void drawButton(Minecraft minecraft, int mouseX, int mouseY) {
            if (!visible) return;
            boolean hovered = mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width
                && mouseY < yPosition + height;

            GL11.glEnable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            if (hovered) {
                // 悬停时铺一层 13% 白（与 EZMiner 的 hover 效果一致）
                drawRect(xPosition, yPosition, xPosition + width, yPosition + height, 0x22FFFFFF);
            }
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, hovered ? 1f : 0.85f);

            minecraft.getTextureManager()
                .bindTexture(texture);
            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawingQuads();
            tessellator.addVertexWithUV(xPosition, yPosition + height, zLevel, 0, 1);
            tessellator.addVertexWithUV(xPosition + width, yPosition + height, zLevel, 1, 1);
            tessellator.addVertexWithUV(xPosition + width, yPosition, zLevel, 1, 0);
            tessellator.addVertexWithUV(xPosition, yPosition, zLevel, 0, 0);
            tessellator.draw();

            GL11.glDisable(GL11.GL_BLEND);
            GL11.glColor4f(1f, 1f, 1f, 1f);
        }
    }

    /** 原版样式的 tooltip 框（黑底紫边），绕开 protected 的 drawHoveringText。 */
    private static void drawTooltip(net.minecraft.client.gui.FontRenderer font, String text, int mx, int my,
        int screenW) {
        int w = font.getStringWidth(net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(text));
        int h = font.FONT_HEIGHT;
        int pad = 3;

        int tx = mx + 10;
        int ty = my - h - pad * 2 - 2;
        if (tx + w + pad * 2 > screenW) tx = screenW - w - pad * 2;
        if (ty < 2) ty = my + 10;

        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);

        // 深底 0xF0100010 + 紫边 0xFF500050（原版 tooltip 配色）
        GL11.glColor4f(16 / 255f, 0f, 16 / 255f, 240 / 255f);
        fillRect(tx - pad, ty - pad, tx + w + pad, ty + h + pad);

        GL11.glColor4f(80 / 255f, 0f, 80 / 255f, 1f);
        fillRect(tx - pad, ty - pad, tx + w + pad, ty - pad + 1);
        fillRect(tx - pad, ty + h + pad - 1, tx + w + pad, ty + h + pad);
        fillRect(tx - pad, ty - pad + 1, tx - pad + 1, ty + h + pad - 1);
        fillRect(tx + w + pad - 1, ty - pad + 1, tx + w + pad, ty + h + pad - 1);

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        font.drawStringWithShadow(text, tx, ty, 0xFFFFFF);

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1f, 1f, 1f, 1f);
    }

    private static void fillRect(int x1, int y1, int x2, int y2) {
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        t.addVertex(x1, y2, 0);
        t.addVertex(x2, y2, 0);
        t.addVertex(x2, y1, 0);
        t.addVertex(x1, y1, 0);
        t.draw();
    }
}
