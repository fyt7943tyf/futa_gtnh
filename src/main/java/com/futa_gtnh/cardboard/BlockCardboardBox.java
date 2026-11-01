package com.futa_gtnh.cardboard;

import java.util.ArrayList;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 纸箱方块。meta 0 = 空箱（普通纸板箱贴图），meta 1 = 已装箱（带胶带十字贴图）。
 *
 * <p>
 * 交互只有一条：<b>潜行右键已装箱 = 拆箱</b>（恢复原方块 + 弹出空纸箱，见
 * {@link CardboardBoxOps#unpack}）。左键破坏按 Forge 的「延迟收获」流程走：
 * {@link #removedByPlayer} 在 willHarvest 时只返回 true 不动方块，
 * {@link #getDrops} 在方块实体还在的窗口里把数据搬进掉落物，最后才真正移除 ——
 * 这套是带数据方块掉落的标准写法，爆炸路径（先 drop 后移除）也顺带正确。
 *
 * <p>
 * {@link #testingPlace} 是拆箱流程的协作开关：原方块要 setBlock 回到「纸箱现在的
 * 位置」，而 {@code canPlaceBlockAt} 默认问的是「占着位置的东西是否可被替换」。
 * 窗口内让纸箱临时报告自己可替换，放置检查就能通过（setBlock 本身会顶掉它）。
 */
public class BlockCardboardBox extends BlockContainer {

    public static final String NAME = "cardboard_box";

    /** 拆箱期间由 {@link CardboardBoxOps} 开关；窗口内 {@link #isReplaceable} 返回 true。 */
    static boolean testingPlace;

    @SideOnly(Side.CLIENT)
    private IIcon iconSide;

    @SideOnly(Side.CLIENT)
    private IIcon iconTop;

    @SideOnly(Side.CLIENT)
    private IIcon iconStorage;

    public BlockCardboardBox() {
        super(Material.cloth);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setHardness(0.5F);
        setResistance(1.0F);
        setStepSound(Block.soundTypeWood);
        setCreativeTab(CreativeTabs.tabMisc);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileEntityCardboardBox();
    }

    // ==================================================================
    // 贴图
    // ==================================================================

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        iconSide = register.registerIcon(FutaGtnhMod.MODID + ":cardboard_box_side");
        iconTop = register.registerIcon(FutaGtnhMod.MODID + ":cardboard_box_top");
        iconStorage = register.registerIcon(FutaGtnhMod.MODID + ":cardboard_box_storage");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int metadata) {
        if (side == 1 || side == 0) return iconTop;
        return metadata == 1 ? iconStorage : iconSide;
    }

    // ==================================================================
    // 交互 / 破坏
    // ==================================================================

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        // 客户端返回 true：不闪任何别的界面；真正拆箱在服务端做完同步回来
        if (world.isRemote) return true;

        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityCardboardBox)) return true;
        TileEntityCardboardBox box = (TileEntityCardboardBox) tile;

        if (player.isSneaking() && box.hasData()) {
            CardboardBoxOps.unpack(world, x, y, z, box, player);
        }
        return true;
    }

    /**
     * 生存破坏：willHarvest 时只报告「可以移除」，方块和方块实体原封不动 ——
     * {@link #getDrops} 要在方块实体还活着的窗口里取数据，取完由
     * {@link #harvestBlock} 真正移除（Forge 的延迟收获约定）。创造破坏
     * （不收获）直接走父类；顺手把落盘数据清掉，免得创造拆箱留垃圾文件。
     */
    @Override
    public boolean removedByPlayer(World world, EntityPlayer player, int x, int y, int z, boolean willHarvest) {
        if (willHarvest) return true;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityCardboardBox) ((TileEntityCardboardBox) tile).clearStoredData();
        return super.removedByPlayer(world, player, x, y, z, willHarvest);
    }

    /** 父类收获完掉落物之后真正移除方块（延迟收获模式的收尾）。 */
    @Override
    public void harvestBlock(World world, EntityPlayer player, int x, int y, int z, int meta) {
        super.harvestBlock(world, player, x, y, z, meta);
        world.setBlockToAir(x, y, z);
    }

    /** 唯一的掉落物：纸箱物品本身，已装箱时带上完整数据标签。 */
    @Override
    public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int metadata, int fortune) {
        ArrayList<ItemStack> drops = new ArrayList<>();
        ItemStack stack = new ItemStack(this, 1, 0);
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityCardboardBox) {
            TileEntityCardboardBox box = (TileEntityCardboardBox) tile;
            if (box.hasData()) {
                ItemBlockCardboardBox.attachData(stack, box.getStoredDataTag());
                stack.setItemDamage(1);
            }
        }
        drops.add(stack);
        return drops;
    }

    @Override
    public int quantityDropped(Random random) {
        return 0;
    }

    /** 爆炸不掉东西（数据已在 getDrops 路径外，走爆炸时同样会掉出带数据的物品）。 */
    @Override
    public void onBlockDestroyedByExplosion(World world, int x, int y, int z, Explosion explosion) {
        // 掉落由 World.destroyBlock(dropBlock=true) -> getDrops 负责，这里不重复
    }

    /** 创造模式中键取方块：带上箱里的数据，方便搬运测试。 */
    @Override
    @SideOnly(Side.CLIENT)
    public ItemStack getPickBlock(MovingObjectPosition target, World world, int x, int y, int z) {
        ItemStack stack = new ItemStack(this, 1, 0);
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityCardboardBox) {
            TileEntityCardboardBox box = (TileEntityCardboardBox) tile;
            if (box.hasData()) {
                ItemBlockCardboardBox.attachData(stack, box.getStoredDataTag());
                stack.setItemDamage(1);
            }
        }
        return stack;
    }

    // ==================================================================
    // 拆箱协作
    // ==================================================================

    /** {@link CardboardBoxOps} 拆箱的放置检查窗口内为 true。 */
    @Override
    public boolean isReplaceable(net.minecraft.world.IBlockAccess world, int x, int y, int z) {
        return testingPlace;
    }
}
