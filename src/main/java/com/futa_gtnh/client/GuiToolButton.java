package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * 只画图标的按钮（仓库面板上那排「一键取 GT 工具」）。
 *
 * <p>
 * 原版 {@code GuiButton} 一定会画一段文字，而这里按钮只有 13px 见方、放不下字 ——
 * 工具认的是图标，名字走悬停提示。所以自绘：一块底色 + 缩放进来的物品图标。
 *
 * <p>
 * <b>画物品图标不是 {@code drawTexturedModalRect} 能做的事</b>：图标要走
 * {@code RenderItem}（它才会处理物品图集、光照和附魔闪光）。原版槽位是 16×16 所以直接
 * 画，这里要缩小，于是自己套一层缩放矩阵 —— 并且画完必须把光照/深度/颜色恢复回去，
 * 否则后面那一层（数量文字、tooltip）会跟着一起变暗或者不显示。
 */
public class GuiToolButton extends GuiButton {

    /** 单例：和原版槽位用的是同一个渲染器。 */
    private static final RenderItem ITEM_RENDERER = RenderItem.getInstance();

    /** 图标四周留的边（13px 按钮画 11×11 图标，免得贴着边框）。 */
    private static final int ICON_PADDING = 1;

    /** 「仓库里没有」时盖在图标上的那层黑（越黑越像禁用）。 */
    private static final int DISABLED_OVERLAY = 0xB0000000;
    /** 悬停时的底色。 */
    private static final int HOVER_BACKGROUND = 0x60FFFFFF;
    /** 平时（以及禁用时）的底色，比悬停暗一些。 */
    private static final int BACKGROUND = 0x40000000;

    private CraftingToolShortcuts.Entry entry;

    public GuiToolButton(int id, int x, int y, int size, CraftingToolShortcuts.Entry entry) {
        super(id, x, y, size, size, "");
        this.entry = entry;
    }

    public void setEntry(CraftingToolShortcuts.Entry entry) {
        this.entry = entry;
        // 「仓库没有就提取不了」：不可用时按钮直接置灰，玩家不用点一下才知道
        this.enabled = entry != null && entry.isAvailable();
    }

    public CraftingToolShortcuts.Entry entry() {
        return entry;
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY) {
        if (!this.visible) return;

        this.field_146123_n = mouseX >= this.xPosition && mouseY >= this.yPosition
            && mouseX < this.xPosition + this.width
            && mouseY < this.yPosition + this.height;

        boolean hovered = this.enabled && this.field_146123_n;
        if (hovered) {
            drawRect(
                this.xPosition,
                this.yPosition,
                this.xPosition + this.width,
                this.yPosition + this.height,
                HOVER_BACKGROUND);
        } else {
            drawRect(
                this.xPosition,
                this.yPosition,
                this.xPosition + this.width,
                this.yPosition + this.height,
                BACKGROUND);
        }

        ItemStack icon = entry == null ? null : entry.icon;
        if (icon != null) {
            drawScaledIcon(mc, icon);
        }

        if (entry != null && !entry.isAvailable()) {
            // 图标留着（玩家要看得出缺的是哪一把），压一层黑表示取不了
            drawRect(
                this.xPosition + 1,
                this.yPosition + 1,
                this.xPosition + this.width - 1,
                this.yPosition + this.height - 1,
                DISABLED_OVERLAY);
        }
    }

    private void drawScaledIcon(Minecraft mc, ItemStack icon) {
        int inner = this.width - ICON_PADDING * 2;
        float scale = inner / 16.0F;

        GL11.glPushMatrix();
        GL11.glTranslatef(this.xPosition + ICON_PADDING, this.yPosition + ICON_PADDING, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);

        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        ITEM_RENDERER.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), icon, 0, 0);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        GL11.glPopMatrix();

        // renderItem 会把颜色/深度状态留在原地：不还原的话，后面画的文字和 tooltip 会发暗
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glDisable(GL11.GL_LIGHTING);
    }
}
