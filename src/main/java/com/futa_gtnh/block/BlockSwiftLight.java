package com.futa_gtnh.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 「迅步」照明用的隐形光源。
 *
 * <p>
 * 只存在于<b>客户端</b>：迅步戴在身上时，客户端每 tick 把这一格放到玩家所在的位置，
 * 走开就换一格。它是个和空气一样的方块（{@link Material#air}：不挡路、不窒息、
 * {@code isAirBlock} 为真），只是会发光 —— 于是玩家周围被照亮，
 * 就像手里举了火把一样。
 *
 * <p>
 * <b>为什么只在客户端放</b>：
 * <ul>
 * <li>它永远不会进存档 —— 服务端崩在「刚放下」那一刻，世界里也不会留下一个
 * 挖不掉的隐形光源；</li>
 * <li>不需要任何服务端 tick，多人服务器上别人也看不到你脚下的假方块；</li>
 * <li>光照是<b>每个世界各自算</b>的，客户端自己改自己那份方块数据，
 * 光照图立刻跟着重算，效果和真方块完全一样。</li>
 * </ul>
 * 代价是它<b>不影响服务端的刷怪判定</b>（那读的是服务端的光照）：戴着它看得见，
 * 但洞里该刷怪还是刷。要连刷怪一起解决就得在服务端也放方块，
 * 那又会留下「崩溃后世界里多个隐形光源」的隐患，不值得。
 *
 * <p>
 * <b>亮度存在元数据里</b>：{@link Block#getLightValue(IBlockAccess, int, int, int)}
 * 是三点重载，原版光照引擎（{@code World.computeLightValue}）读的正是这个重载，
 * 所以一个方块就能表示 0~15 任意亮度，不需要为每一档注册一个方块。
 */
public class BlockSwiftLight extends Block {

    public static final String NAME = "swift_light";

    public BlockSwiftLight() {
        // 材质用 air：不挡路、不挡光、不会让人窒息，isAirBlock 也返回 true
        super(Material.air);
        setBlockName(com.futa_gtnh.FutaGtnhMod.MODID + "." + NAME);
        setLightLevel(1.0F);
        setLightOpacity(0);
        setHardness(-1.0F);
        setResistance(6000000.0F);
        // 不进创造模式物品栏、也没有对应的 ItemBlock（见 CommonProxy 的注册），
        // 所以它在任何物品列表里都找不到
        setCreativeTab(null);
        disableStats();
    }

    /** 亮度 = 元数据（放置时把亮度当元数据写进去）。 */
    @Override
    public int getLightValue(IBlockAccess world, int x, int y, int z) {
        return world.getBlockMetadata(x, y, z) & 15;
    }

    /**
     * 贴图：<b>必须给一个存在的贴图名</b>。
     *
     * <p>
     * 虽然这个方块永远不渲染（{@link #getRenderType()} 返回 -1），但客户端的贴图图集
     * 会遍历所有注册过的方块调 {@code registerBlockIcons} —— 贴图名为 null 的话
     * 那一步会出问题。所以借共享终端那张贴图用一下，纯占位、不会被看到。
     */
    @Override
    public void registerBlockIcons(net.minecraft.client.renderer.texture.IIconRegister register) {
        this.blockIcon = register.registerIcon(com.futa_gtnh.FutaGtnhMod.MODID + ":shared_terminal_side");
    }

    /** 不渲染：{@code getRenderType() < 0} 的方块会被渲染器直接跳过。 */
    @Override
    public int getRenderType() {
        return -1;
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public boolean renderAsNormalBlock() {
        return false;
    }

    /** 没有碰撞箱 —— 玩家不会被它挡住。 */
    @Override
    public AxisAlignedBB getCollisionBoundingBoxFromPool(World world, int x, int y, int z) {
        return null;
    }

    /** 射线检测也不认它：鼠标点不到、也挖不掉（它本来就不该被当成一个「方块」）。 */
    @Override
    public boolean canCollideCheck(int meta, boolean includeLiquid) {
        return false;
    }

    /** 光照引擎会把不透明度至少当 1 用，这里明确成 0，别让它额外挡光。 */
    @Override
    public int getLightOpacity(IBlockAccess world, int x, int y, int z) {
        return 0;
    }

    @Override
    public boolean isAir(IBlockAccess world, int x, int y, int z) {
        return true;
    }

    /** 真的被破坏（理论上不会发生）也不掉东西。 */
    @Override
    public void dropBlockAsItemWithChance(World world, int x, int y, int z, int meta, float chance, int fortune) {}

    @Override
    public int damageDropped(int meta) {
        return 0;
    }
}
