package com.futa_gtnh.aewireless;

import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.common.GuiHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 「AE 无线发信端」方块：一个 ME 网络的无线信号出口。
 *
 * <p>
 * 放在 AE 线缆旁边加入 ME 网络（每个网络只能有一个生效的发信端，多放的那个进入
 * 「冲突」状态、不响应查询），可以命名；接收端覆盖板按名字/坐标选中它之后，
 * 就能隔着任意距离查询这个 ME 网络里物品/流体的存量，并据此控制机器启停。
 *
 * <p>
 * 本类不引用任何 AE 类型 —— AE 相关实现全部在 {@link TileAeSignalTransmitter} 里，
 * 注册由 {@code CommonProxy} 在确认 AE2 在场之后才执行。
 */
public class BlockAeSignalTransmitter extends BlockContainer {

    public static final String NAME = "ae_signal_transmitter";

    /** 客户端专有；专用服务端不会加载这个字段上的类型。 */
    @SideOnly(Side.CLIENT)
    private IIcon icon;

    public BlockAeSignalTransmitter() {
        super(Material.iron);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setHardness(5.0F);
        setResistance(10.0F);
        setStepSound(Block.soundTypeMetal);
        setCreativeTab(CreativeTabs.tabMisc);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileAeSignalTransmitter();
    }

    /** 记录放置者为发信端的所有者 —— 接收端按「同队伍」过滤可选的发信端。 */
    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, net.minecraft.entity.EntityLivingBase placer,
        net.minecraft.item.ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        if (!world.isRemote && placer instanceof EntityPlayer player) {
            TileEntity tile = world.getTileEntity(x, y, z);
            if (tile instanceof TileAeSignalTransmitter transmitter) {
                UUID owner = placer.getUniqueID();
                if (owner != null) {
                    transmitter.setOwner(owner);
                    transmitter.getProxy()
                        .setOwner(player);
                }
            }
        }
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) return true;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileAeSignalTransmitter)) return false;
        player.openGui(FutaGtnhMod.instance, GuiHandler.GUI_AE_SIGNAL_TRANSMITTER, world, x, y, z);
        return true;
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int meta) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileAeSignalTransmitter transmitter) transmitter.onRemoved();
        super.breakBlock(world, x, y, z, block, meta);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        icon = register.registerIcon(FutaGtnhMod.MODID + ":" + NAME);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int meta) {
        return icon;
    }
}
