package com.futa_gtnh.client;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.world.IBlockAccess;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.storagebus.BlockStorageBus;
import com.futa_gtnh.storagebus.StorageBusGeometry;
import com.futa_gtnh.storagebus.TileEntityStorageBus;

import cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 存储总线薄板的 ISBRH。
 *
 * <p>
 * 一个方块格子里最多画六块板，每块由 {@link BlockStorageBus#getFaceMask} 的位决定：
 * 基板用侧纹理，再在露出的那一面叠一张正面纹理。这样同一格里的多条总线各朝各的面，
 * 看起来就是 AE 线缆上挂了多个部件。
 */
@SideOnly(Side.CLIENT)
public class StorageBusRenderer implements ISimpleBlockRenderingHandler {

    @Override
    public void renderInventoryBlock(Block block, int metadata, int modelId, RenderBlocks renderer) {
        // 物品栏用 getIcon 的平面图标（shouldRender3DInInventory=false）
    }

    @Override
    public boolean shouldRender3DInInventory(int modelId) {
        return false;
    }

    @Override
    public int getRenderId() {
        return BlockStorageBus.renderId;
    }

    @Override
    public boolean renderWorldBlock(IBlockAccess world, int x, int y, int z, Block block, int modelId,
        RenderBlocks renderer) {
        if (!(block instanceof BlockStorageBus)) return false;
        int mask = BlockStorageBus.getFaceMask(world, x, y, z);
        if (mask == 0) return false;

        BlockStorageBus bus = (BlockStorageBus) block;
        Tessellator tessellator = Tessellator.instance;
        tessellator.setColorOpaque_F(1.0F, 1.0F, 1.0F);
        tessellator.addTranslation(x, y, z);
        tessellator.setBrightness(world.getLightBrightnessForSkyBlocks(x, y, z, 0));
        try {
            for (int i = 0; i < TileEntityStorageBus.FACES; i++) {
                if ((mask & (1 << i)) == 0) continue;
                ForgeDirection face = ForgeDirection.getOrientation(i);
                // 参考 AE2 PartStorageBus#getBoxes 的三层阶梯：
                // 10×10 底板 → 12×12 中层 → 6×6×2 凸起接口。
                IoNodeRenderer
                    .drawBox(tessellator, StorageBusGeometry.baseBox(face), bus.getSideIcon(), 1.0F, 1.0F, 1.0F);
                IoNodeRenderer
                    .drawBox(tessellator, StorageBusGeometry.middleBox(face), bus.getSideIcon(), 1.0F, 1.0F, 1.0F);
                IoNodeRenderer
                    .drawBox(tessellator, StorageBusGeometry.connectorBox(face), bus.getFrontIcon(), 1.0F, 1.0F, 1.0F);
            }
        } finally {
            tessellator.addTranslation(-x, -y, -z);
        }
        return true;
    }
}
