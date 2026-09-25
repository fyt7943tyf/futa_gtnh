package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** 游戏界面上的可关闭悬浮小地图。 */
public final class RoguelikeMapOverlay {

    private static final int SIZE = 172;

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!RoguelikeMapClient.isMiniMapEnabled()) return;

        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.theWorld == null || minecraft.thePlayer == null || minecraft.currentScreen != null) return;

        try {
            ScaledResolution resolution = new ScaledResolution(
                minecraft,
                minecraft.displayWidth,
                minecraft.displayHeight);
            int left = resolution.getScaledWidth() - SIZE - 6;
            int top = 6;
            Gui.drawRect(left - 2, top - 2, left + SIZE + 2, top + SIZE + 22, 0xC0101010);

            int level = RoguelikeMapClient.state()
                .getCurrentLevel();
            int playerX = (int) Math.floor(minecraft.thePlayer.posX);
            int playerZ = (int) Math.floor(minecraft.thePlayer.posZ);
            RoguelikeMapRenderer.draw(
                RoguelikeMapClient.state()
                    .getFloor(level),
                playerX,
                playerZ,
                left,
                top + 14,
                SIZE,
                SIZE,
                false,
                false);

            FontRenderer font = minecraft.fontRenderer;
            font.drawStringWithShadow("地牢地图 · 第 " + (level + 1) + " 层", left, top, 0xFFFFFF);
            font.drawStringWithShadow("M 全屏  N 关闭", left, top + SIZE + 6, 0xB0B0B0);
        } catch (Throwable ignored) {
            // 小地图属于辅助显示，绘制异常时不应影响主界面和游戏逻辑。
        }
    }
}
