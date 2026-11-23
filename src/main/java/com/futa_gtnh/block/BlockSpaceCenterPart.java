package com.futa_gtnh.block;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;

import com.futa_gtnh.FutaGtnhMod;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 「深空采矿指挥中心」的结构方块：一个方块三种形态（meta）。
 *
 * <ul>
 * <li>meta 0 —— 采掘桁架（外壳主体）；
 * <li>meta 1 —— 全息投影核心（结构中柱，客户端带旋转星环动画）；
 * <li>meta 2 —— 信号天线阵列（顶盖部件，客户端带呼吸灯动画）。
 * </ul>
 *
 * <p>
 * 与 {@link BlockIoNode} 同一条铁律：客户端专有的贴图字段不提前初始化，
 * 全部交给 {@code @SideOnly} 的 {@link #registerBlockIcons}，专用服务端才不会炸。
 * 动画渲染（meta 1/2 的自定义渲染 id）在客户端由 ClientProxy 赋值，
 * 服务端永远 {@code renderId = 0} 无所谓。
 */
public class BlockSpaceCenterPart extends Block {

    public static final String NAME = "space_center_part";

    /** 形态 meta。 */
    public static final int META_TRUSS = 0;
    public static final int META_HOLO_CORE = 1;
    public static final int META_ANTENNA = 2;

    /** 贴图名（textures/blocks/ 下）。 */
    private static final String[] TEXTURES = { "space_center_truss", "space_center_holo", "space_center_antenna" };

    /** 本地化 key 后缀。 */
    private static final String[] NAMES = { "truss", "holo_core", "antenna" };

    /** 自定义渲染器（ISBRH）的 id，客户端启动时由 ClientProxy 赋值；0 = 原版方块渲染。 */
    public static int renderId;

    @SideOnly(Side.CLIENT)
    private IIcon[] textures;

    public BlockSpaceCenterPart() {
        super(Material.iron);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setHardness(6.0F);
        setResistance(60.0F);
        setStepSound(Block.soundTypeMetal);
        setCreativeTab(CreativeTabs.tabMisc);
        setHarvestLevel("wrench", 2);
    }

    /** 全息核心与天线不满格（自定义渲染画小几何体），桁架是普通外壳。 */
    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    @Override
    public int getRenderType() {
        return renderId != 0 ? renderId : super.getRenderType();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        textures = new IIcon[TEXTURES.length];
        for (int i = 0; i < TEXTURES.length; i++) {
            textures[i] = register.registerIcon(FutaGtnhMod.MODID + ":" + TEXTURES[i]);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int metadata) {
        if (textures == null || textures.length == 0) return super.getIcon(side, metadata);
        int index = metadata >= 0 && metadata < textures.length ? metadata : META_TRUSS;
        return textures[index];
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings("unchecked")
    public void getSubBlocks(Item item, CreativeTabs tab, List list) {
        for (int meta = 0; meta < TEXTURES.length; meta++) {
            list.add(new ItemStack(item, 1, meta));
        }
    }

    @Override
    public int damageDropped(int metadata) {
        return metadata;
    }

    /** 渲染器用：按 meta 取贴图。 */
    @SideOnly(Side.CLIENT)
    public IIcon iconForMeta(int metadata) {
        return getIcon(0, metadata);
    }

    /** 本地化 key 的形态后缀。 */
    public static String suffixForMeta(int metadata) {
        int index = metadata >= 0 && metadata < NAMES.length ? metadata : META_TRUSS;
        return NAMES[index];
    }
}
