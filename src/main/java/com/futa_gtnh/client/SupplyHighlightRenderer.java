package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.supply.SupplySource;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;

/** One through-wall device outline, working-face marker and eye guide, only within 256 blocks. */
public final class SupplyHighlightRenderer {

    @SubscribeEvent
    public void render(RenderWorldLastEvent event) {
        SupplySource target = SupplyHighlight.target();
        if (target == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.thePlayer;
        if (player == null || mc.theWorld == null
            || player.dimension != target.dim
            || mc.gameSettings.hideGUI
            || player.getDistanceSq(target.x + 0.5D, target.y + 0.5D, target.z + 0.5D) > 256D * 256D) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(
                target.x - RenderManager.renderPosX,
                target.y - RenderManager.renderPosY,
                target.z - RenderManager.renderPosZ);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glLineWidth(2F);
            GL11.glColor4f(0.15F, 1F, 1F, 0.9F);
            RenderGlobal
                .drawOutlinedBoundingBox(AxisAlignedBB.getBoundingBox(-0.01D, -0.01D, -0.01D, 1.01D, 1.01D, 1.01D), -1);
            Tessellator t = Tessellator.instance;
            t.startDrawing(GL11.GL_LINES);
            t.addVertex(0.5D, 0.5D, 0.5D);
            t.addVertex(0.5D, 2.5D, 0.5D);
            double eyeX = player.prevPosX + (player.posX - player.prevPosX) * event.partialTicks - target.x;
            double eyeY = player.prevPosY + (player.posY - player.prevPosY) * event.partialTicks
                + player.getEyeHeight()
                - target.y;
            double eyeZ = player.prevPosZ + (player.posZ - player.prevPosZ) * event.partialTicks - target.z;
            t.addVertex(eyeX, eyeY - 0.15D, eyeZ);
            t.addVertex(0.5D, 0.5D, 0.5D);
            t.draw();
            if (target.side >= 0) {
                ForgeDirection side = ForgeDirection.getOrientation(target.side);
                double x = 0.5D + side.offsetX * 0.51D, y = 0.5D + side.offsetY * 0.51D,
                    z = 0.5D + side.offsetZ * 0.51D;
                double hx = side.offsetX == 0 ? 0.4D : 0.015D, hy = side.offsetY == 0 ? 0.4D : 0.015D,
                    hz = side.offsetZ == 0 ? 0.4D : 0.015D;
                GL11.glColor4f(1F, 0.7F, 0.1F, 1F);
                RenderGlobal.drawOutlinedBoundingBox(
                    AxisAlignedBB.getBoundingBox(x - hx, y - hy, z - hz, x + hx, y + hy, z + hz),
                    -1);
            }
            GL11.glTranslated(0.5D, 2.7D, 0.5D);
            GL11.glRotatef(-RenderManager.instance.playerViewY, 0F, 1F, 0F);
            GL11.glRotatef(RenderManager.instance.playerViewX, 1F, 0F, 0F);
            GL11.glScalef(-0.025F, -0.025F, 0.025F);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            String label = SupplyHighlight.label();
            mc.fontRenderer.drawStringWithShadow(label, -mc.fontRenderer.getStringWidth(label) / 2, 0, 0x66FFFF);
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }
}
