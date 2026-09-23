package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

/**
 * 高度可以任意的按钮。
 *
 * <p>
 * 原版 {@code GuiButton} 采样 widgets.png 按钮贴图时永远从贴图区段<b>顶部</b>开始取：
 * {@code drawTexturedModalRect(x, y, 0, 46 + state*20, w, height)}。贴图区段高 20px，
 * 于是高度不足 20 的按钮会把<b>底边的斜面切掉</b> —— 14px 高的按钮看起来下边缘
 * 直接和背景边框「长在一起」，这正是终端界面里「按钮和边界重合」的来源。
 *
 * <p>
 * 这里把按钮图形分成上下两半分别采样：上半对齐贴图区段顶部、下半对齐底部，
 * 任意高度都能画出完整的上下斜面；左右方向沿用原版的「左半贴左边、右半贴右边」
 * 采样，横向表现与原版完全一致。
 *
 * <p>
 * 另外图形整体在按钮矩形内<b>上下各缩进 1px</b>：本模组的界面纹理在按钮行
 * 上下都画了边框线，按钮贴着矩形边画会和边框线严丝合缝地重合，
 * 留 1px 让两者看起来是「分开的两层」。点击判定仍用完整矩形。
 */
public class GuiSmallButton extends GuiButton {

    /** 和原版 GuiButton 用的是同一张贴图。 */
    private static final ResourceLocation BUTTON_TEXTURES = new ResourceLocation("textures/gui/widgets.png");

    /** 贴图里按钮三态区段的起始 v 和区段高度。状态值沿用原版：0=禁用 1=普通 2=悬停。 */
    private static final int TEXTURE_V_BASE = 46;
    private static final int TEXTURE_SECTION_HEIGHT = 20;

    public GuiSmallButton(int id, int x, int y, int width, int height, String label) {
        super(id, x, y, width, height, label);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;

        // 悬停判定和原版一致；必须写回 field_146123_n，
        // 因为别的代码（比如终端界面的自动入库 tooltip）靠 func_146115_a() 读它
        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width
            && mouseY < this.yPosition + this.height;
        int state = this.getHoverState(this.field_146123_n);

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager()
            .bindTexture(BUTTON_TEXTURES);

        int textureV = TEXTURE_V_BASE + state * TEXTURE_SECTION_HEIGHT;

        // 图形比矩形上下各缩进 1px（见类注释）；太矮时退回占满整个矩形
        int graphicY = this.yPosition + 1;
        int graphicHeight = this.height - 2;
        if (graphicHeight < 3) {
            graphicY = this.yPosition;
            graphicHeight = this.height;
        }

        int topHalf = graphicHeight / 2;
        int bottomHalf = graphicHeight - topHalf;
        int bottomV = textureV + TEXTURE_SECTION_HEIGHT - bottomHalf;
        int leftHalf = this.width / 2;
        int rightHalf = this.width - leftHalf;

        // 上半段贴着贴图区段顶部采样
        this.drawTexturedModalRect(this.xPosition, graphicY, 0, textureV, leftHalf, topHalf);
        this.drawTexturedModalRect(this.xPosition + leftHalf, graphicY, 200 - leftHalf, textureV, rightHalf, topHalf);
        // 下半段贴着贴图区段底部采样 —— 这就是被原版切掉的那条底边
        this.drawTexturedModalRect(this.xPosition, graphicY + topHalf, 0, bottomV, leftHalf, bottomHalf);
        this.drawTexturedModalRect(
            this.xPosition + leftHalf,
            graphicY + topHalf,
            200 - leftHalf,
            bottomV,
            rightHalf,
            bottomHalf);

        this.mouseDragged(mc, mouseX, mouseY);

        int textColor = 0xE0E0E0;
        if (!this.enabled) {
            textColor = 0xA0A0A0;
        } else if (this.field_146123_n) {
            textColor = 0xFFFFA0;
        }
        this.drawCenteredString(
            mc.fontRenderer,
            this.displayString,
            this.xPosition + this.width / 2,
            this.yPosition + (this.height - 8) / 2,
            textColor);
    }
}
