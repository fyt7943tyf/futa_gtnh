package com.futa_gtnh.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.util.ForgeDirection;

import org.lwjgl.opengl.GL11;

import com.futa_gtnh.storagebus.ItemStorageBus;
import com.futa_gtnh.storagebus.StorageBusGeometry;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 手持存储总线时的白色放置预览。
 *
 * <p>
 * 和 AE {@code PartPlacement} 的放置预览同语义：只显示“这次右键真的会放/加在哪一面”，
 * 不会在已有面、无效位置或点击总线自身时画框。位置解析直接用
 * {@link ItemStorageBus#resolve}，所以预览和服务端实际执行不会不一致。
 */
@SideOnly(Side.CLIENT)
public final class StorageBusPlacementPreview {

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null || mc.theWorld == null || mc.currentScreen != null) return;
        if (mc.gameSettings.hideGUI) return;

        EntityPlayer player = mc.thePlayer;
        ItemStack held = player.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemStorageBus)) return;

        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return;
        if (hit.sideHit < 0 || hit.sideHit >= 6) return;
        if (player.getDistanceSq(hit.blockX + 0.5D, hit.blockY + 0.5D, hit.blockZ + 0.5D) > 64.0D) return;

        ItemStorageBus.Placement placement = ItemStorageBus
            .resolve(mc.theWorld, hit.blockX, hit.blockY, hit.blockZ, hit.sideHit);
        if (placement.kind != ItemStorageBus.Kind.PLACE && placement.kind != ItemStorageBus.Kind.ADD) return;

        ForgeDirection target = placement.target;
        double px = player.lastTickPosX + (player.posX - player.lastTickPosX) * event.partialTicks;
        double py = player.lastTickPosY + (player.posY - player.lastTickPosY) * event.partialTicks;
        double pz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * event.partialTicks;

        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_LINE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslated(-px, -py, -pz);
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glDisable(GL11.GL_LIGHTING);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glLineWidth(2.0F);

            Tessellator tessellator = Tessellator.instance;
            tessellator.startDrawing(GL11.GL_LINES);
            tessellator.setColorRGBA_F(1.0F, 1.0F, 1.0F, 0.75F);
            drawBoxLines(tessellator, placement.x, placement.y, placement.z, StorageBusGeometry.baseBox(target));
            drawBoxLines(tessellator, placement.x, placement.y, placement.z, StorageBusGeometry.middleBox(target));
            drawBoxLines(tessellator, placement.x, placement.y, placement.z, StorageBusGeometry.connectorBox(target));
            tessellator.draw();
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    private static void drawBoxLines(Tessellator tessellator, int bx, int by, int bz, AxisAlignedBB box) {
        double x0 = bx + box.minX, y0 = by + box.minY, z0 = bz + box.minZ;
        double x1 = bx + box.maxX, y1 = by + box.maxY, z1 = bz + box.maxZ;

        // 底/顶
        line(tessellator, x0, y0, z0, x1, y0, z0);
        line(tessellator, x1, y0, z0, x1, y0, z1);
        line(tessellator, x1, y0, z1, x0, y0, z1);
        line(tessellator, x0, y0, z1, x0, y0, z0);
        line(tessellator, x0, y1, z0, x1, y1, z0);
        line(tessellator, x1, y1, z0, x1, y1, z1);
        line(tessellator, x1, y1, z1, x0, y1, z1);
        line(tessellator, x0, y1, z1, x0, y1, z0);
        // 立柱
        line(tessellator, x0, y0, z0, x0, y1, z0);
        line(tessellator, x1, y0, z0, x1, y1, z0);
        line(tessellator, x1, y0, z1, x1, y1, z1);
        line(tessellator, x0, y0, z1, x0, y1, z1);
    }

    private static void line(Tessellator tessellator, double x0, double y0, double z0, double x1, double y1,
        double z1) {
        tessellator.addVertex(x0, y0, z0);
        tessellator.addVertex(x1, y1, z1);
    }
}
