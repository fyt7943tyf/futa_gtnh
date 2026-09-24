package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.station.SharedStorageInventory;
import com.futa_gtnh.station.StationRef;
import com.futa_gtnh.station.StationViews;

/**
 * 在合成站那块存储区里画出<b>真实数量</b>。
 *
 * <p>
 * 为什么不能直接把真数量塞进物品栈：{@code ItemStack.stackSize} 一旦超过堆叠上限，
 * 原版的「整叠拿到光标」就会把整个数量搬到光标上（凭空多出一叠），所以虚拟槽位一律
 * {@code min(数量, 64)}，原版跟着画出「64」。真实数量只能自己画。
 *
 * <p>
 * 画在 {@code GuiScreenEvent.DrawScreenEvent.Post}（见 {@link StoragePanelEvents}）：
 * 那时原版已经把格子和「64」都画完了，我们
 * <ol>
 * <li>把物品图标<b>重画一遍</b>盖掉原版那个数字（图标不透明，这一下就干净了）；</li>
 * <li>再画上紧凑格式的真实数量（{@code 1.23k} 这种，和终端界面同一套格式）；</li>
 * <li>鼠标正压着的那一格补回高亮 —— 重画图标会把它一起盖掉。</li>
 * </ol>
 *
 * <p>
 * 数量 ≤ 64 时不画：那时候原版的数字就是真数，没必要动它。
 */
public final class StationAmounts {

    private StationAmounts() {}

    private static RenderItem itemRender;

    public static void draw(GuiScreen screen, int mouseX, int mouseY) {
        if (!(screen instanceof GuiContainer)) return;

        StationRef station = TinkersScreens.sharedChestStation(screen);
        if (station == null) return;

        GuiContainer gui = (GuiContainer) screen;
        // 界面位置用反射读一次（protected，见 Accessors），这一帧只读一次
        int guiLeft = Accessors.intField(gui, "guiLeft", "field_147003_i");
        int guiTop = Accessors.intField(gui, "guiTop", "field_147009_r");

        for (int i = 0; i < SharedStorageInventory.SIZE; i++) {
            StorageViewEntry entry = StoragePanel.get()
                .entryAt(i);
            if (entry == null || entry.getAmount() <= SharedStorageInventory.MAX_DISPLAY) continue;

            Slot slot = gui.inventorySlots.getSlot(StationViews.CHEST_FIRST + i);
            if (slot == null || !slot.getHasStack()) continue;

            // 只在这一格显示的确实是这一页那件东西时才画：翻页之后服务端还没把新的
            // 27 格同步过来的那一两个 tick 里，槽位里还是旧物品，那时画新数量就是画错的
            ItemStack shown = slot.getStack();
            if (entry.getItemKey() == null || !entry.getItemKey()
                .equals(ItemKey.of(shown))) {
                continue;
            }

            int x = guiLeft + slot.xDisplayPosition;
            int y = guiTop + slot.yDisplayPosition;

            redrawIcon(shown, x, y);
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                highlight(x, y);
            }
            drawAmount(GuiSharedTerminal.formatShort(entry.getAmount()), x, y);
        }
    }

    /** 盖掉原版画的那个「64」：重画一遍图标即可（只画图标，不画数量）。 */
    private static void redrawIcon(ItemStack stack, int x, int y) {
        if (itemRender == null) itemRender = new RenderItem();
        Minecraft mc = Minecraft.getMinecraft();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        itemRender.renderItemAndEffectIntoGUI(mc.fontRenderer, mc.getTextureManager(), stack, x, y);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
    }

    /** 原版会在鼠标压着的格子上盖一层半透明白，重画图标把它擦掉了，这里补回来。 */
    private static void highlight(int x, int y) {
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.5F);
        Tessellator tess = Tessellator.instance;
        tess.startDrawingQuads();
        tess.addVertex(x, y + 16, 0.0D);
        tess.addVertex(x + 16, y + 16, 0.0D);
        tess.addVertex(x + 16, y, 0.0D);
        tess.addVertex(x, y, 0.0D);
        tess.draw();
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glEnable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    /**
     * 数量文字画在格子右下角，和终端界面（{@code GuiSharedTerminal.drawAmountText}）
     * 同一套做法：位数多了整体缩小，而不是截断。
     */
    private static void drawAmount(String text, int slotX, int slotY) {
        if (text == null || text.isEmpty()) return;

        Minecraft mc = Minecraft.getMinecraft();
        float scale = text.length() > 6 ? 0.5F : (text.length() > 4 ? 0.75F : 1.0F);
        int width = mc.fontRenderer.getStringWidth(text);

        GL11.glPushMatrix();
        GL11.glTranslatef(slotX + 17.0F, slotY + 17.0F, 300.0F);
        GL11.glScalef(scale, scale, 1.0F);
        mc.fontRenderer.drawStringWithShadow(text, -width, -8, 0xFFFFFF);
        GL11.glPopMatrix();
    }
}
