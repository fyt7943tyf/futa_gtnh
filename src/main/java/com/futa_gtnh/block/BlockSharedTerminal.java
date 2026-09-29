package com.futa_gtnh.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 「共享终端」方块：右键打开全服共享存储界面。
 *
 * <p>
 * 它同时还是 GT 管道/泵的对接点 —— 见 {@link TileEntitySharedTerminal}。
 */
public class BlockSharedTerminal extends BlockContainer {

    public static final String NAME = "shared_terminal";

    /** 每个面一个材质，顺序就是 {@code side} / {@code ForgeDirection} 的顺序。 */
    private static final String[] FACE_SUFFIX = { "down", "up", "north", "south", "west", "east" };

    /**
     * 六个面的材质。
     *
     * <p>
     * <b>刻意不在字段上初始化</b>（连 {@code final} 都不加）：{@code IIcon} 是客户端专有类型，
     * 而本类是两端共用的。字段上带初始化代码的话，那个初始化会被编进<b>构造函数</b>里，
     * 而构造函数在专用服务端上照样要跑 —— 到时候就得去解析一个已经被剥掉的类，
     * 整个模组都别想加载。所以这里只留一个 {@code @SideOnly(CLIENT)} 的字段，
     * 赋值的活全交给同样是客户端专有的 {@link #registerBlockIcons}。
     */
    @SideOnly(Side.CLIENT)
    private IIcon[] faceIcons;

    public BlockSharedTerminal() {
        super(Material.iron);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setHardness(5.0F);
        setResistance(10.0F);
        setStepSound(Block.soundTypeMetal);
        setCreativeTab(CreativeTabs.tabMisc);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileEntitySharedTerminal();
    }

    /**
     * 右键打开界面。
     *
     * <p>
     * 潜行时直接返回 false：GT 的扳手、以及其他模组的「潜行右键」工具
     * 都是走 {@code onItemUseFirst} / 潜行判定，这里让路它们才能正常工作。
     */
    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (player.isSneaking()) return false;

        if (!world.isRemote) {
            TileEntity tile = world.getTileEntity(x, y, z);
            TileEntitySharedTerminal terminal = tile instanceof TileEntitySharedTerminal
                ? (TileEntitySharedTerminal) tile
                : null;
            CommonProxy.openSharedStorage(player, terminal);
        }
        return true;
    }

    /**
     * 六个面各注册一个材质。
     *
     * <p>
     * <b>为什么每个面都要不一样</b>：配置界面里是拿一个 3D 方块让你点面来配的
     * （见 {@code client/GuiTerminalIo}），而「哪个面是北」在游戏里光看方块是分不出来的。
     * 所以每面都印上与那面一一对应的点数（下 1 点、上 2 点、北 3 点、南 4 点、
     * 西 5 点、东 6 点，和骰子一个数法），界面里那个小方块用的就是同一批材质 ——
     * 世界里看到的和界面里点的是同一套记号。
     *
     * <p>
     * 材质是在原有侧面/顶面贴图上加点生成的（{@code shared_terminal_face_<方向>.png}）。
     */
    @SideOnly(Side.CLIENT)
    @Override
    public void registerBlockIcons(IIconRegister register) {
        faceIcons = new IIcon[6];
        for (int side = 0; side < 6; side++) {
            faceIcons[side] = register.registerIcon(FutaGtnhMod.MODID + ":" + NAME + "_face_" + FACE_SUFFIX[side]);
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IIcon getIcon(int side, int metadata) {
        // side: 0=下, 1=上, 2=北, 3=南, 4=西, 5=东（和 ForgeDirection 的顺序一致）
        return side >= 0 && side < 6 ? faceIcons[side] : faceIcons[3];
    }

    /** 供配置界面画那个 3D 小方块用（拿的就是世界里这套材质）。 */
    @SideOnly(Side.CLIENT)
    public IIcon getFaceIcon(int side) {
        return side >= 0 && side < 6 ? faceIcons[side] : faceIcons[3];
    }
}
