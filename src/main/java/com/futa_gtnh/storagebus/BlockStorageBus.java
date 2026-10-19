package com.futa_gtnh.storagebus;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.common.GuiHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 共享存储总线方块。
 *
 * <p>
 * 放在一个容器（箱子、GT 机器、储罐……）旁边，把这个容器接进共享存储：
 * 容器里的东西会出现在共享终端里，从终端取东西会按优先级从它里面拿，
 * 往共享存储放东西也会按优先级先填它。配置语义对齐 AE2 的 ME 存储总线，
 * 见 {@link StorageBusConfig}。
 *
 * <p>
 * 方块本身是满方块，但目标那一面用不同的纹理，玩家一眼能看出它接的是哪个方向。
 * 目标面存在 metadata 里（只用 0..5），右键界面里可以切换。
 */
public class BlockStorageBus extends BlockContainer {

    public static final String NAME = "storage_bus";

    private static final String[] TEXTURES = { "side", "front" };

    @SideOnly(Side.CLIENT)
    private IIcon[] textures;

    public BlockStorageBus() {
        super(Material.iron);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setHardness(3.0F);
        setResistance(8.0F);
        setStepSound(Block.soundTypeMetal);
        setCreativeTab(CreativeTabs.tabMisc);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileEntityStorageBus();
    }

    @Override
    public int getMobilityFlag() {
        return 2;
    }

    // ==================================================================
    // 纹理
    // ==================================================================

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        textures = new IIcon[TEXTURES.length];
        for (int i = 0; i < TEXTURES.length; i++) {
            textures[i] = register.registerIcon(FutaGtnhMod.MODID + ":" + NAME + "_" + TEXTURES[i]);
        }
    }

    /**
     * metadata 就是目标面的 ordinal，所以「对着容器的那一面」用 front 纹理。
     * 物品栏里 metadata 是 0，会显示 front —— 当作一个接口方向提示即可。
     */
    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int metadata) {
        if (textures == null) return null;
        int target = metadata >= 0 && metadata < 6 ? metadata : 0;
        return side == target ? textures[1] : textures[0];
    }

    // ==================================================================
    // 放置 / 交互
    // ==================================================================

    /**
     * 1.7.10 的调用顺序是「先 onBlockPlaced 算 metadata，再 placeBlockAt 真正放方块」，
     * 所以这里只能看邻居、碰不到自己的方块实体；返回的 metadata 会被用来放置方块。
     */
    @Override
    public int onBlockPlaced(World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ,
        int metadata) {
        int result = super.onBlockPlaced(world, x, y, z, side, hitX, hitY, hitZ, metadata);
        if (world == null || world.isRemote) return result;

        // side 是「被点击的那个面」。新方块放在被点击方块的那一侧，
        // 所以目标（被点击的方块）相对新方块就是 side 的反方向。
        ForgeDirection preferred = ForgeDirection.getOrientation(side)
            .getOpposite();
        ForgeDirection target = TileEntityStorageBus.pickTargetSide(world, x, y, z, preferred);
        return target == ForgeDirection.UNKNOWN ? result : target.ordinal();
    }

    /** 方块和方块实体都放好之后再写配置 / 登记。 */
    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, net.minecraft.entity.EntityLivingBase placer,
        net.minecraft.item.ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        if (world == null || world.isRemote) return;

        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityStorageBus)) return;
        TileEntityStorageBus bus = (TileEntityStorageBus) tile;

        int meta = world.getBlockMetadata(x, y, z);
        if (meta >= 0 && meta < 6) {
            bus.getConfig()
                .setTargetSide(ForgeDirection.getOrientation(meta));
        }
        bus.onConfigChanged();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        // 潜行让路给 GT 扳手（和共享终端 / IO 节点一致）
        if (player.isSneaking()) return false;
        if (world.isRemote) return true;

        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityStorageBus)) return false;
        TileEntityStorageBus bus = (TileEntityStorageBus) tile;
        bus.ensureRegistered();
        player.openGui(FutaGtnhMod.instance, GuiHandler.GUI_STORAGE_BUS, world, x, y, z);
        return true;
    }

    // ==================================================================
    // 邻居变化
    // ==================================================================

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbour) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityStorageBus) ((TileEntityStorageBus) tile).requestRefresh();
    }

    @Override
    public void onNeighborChange(IBlockAccess world, int x, int y, int z, int tileX, int tileY, int tileZ) {
        if (!(world instanceof World)) return;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityStorageBus) ((TileEntityStorageBus) tile).requestRefresh();
    }
}
