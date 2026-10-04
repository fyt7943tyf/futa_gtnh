package com.futa_gtnh.block;

import java.util.List;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.IIcon;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.common.GuiHandler;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * 「IO 节点」方块：共享存储的自动化输入输出口，外观参考 EnderIO 的导管 ——
 * 中心一个小节点，向有输入输出能力的邻面伸出连接臂，<b>不占满整格</b>。
 *
 * <p>
 * 点选和碰撞都是<b>逐组件</b>的（EIO {@code BlockConduitBundle} 的做法）：
 * 中心核和每根臂各自有自己的碰撞盒；{@link #collisionRayTrace} 会把命中的组件
 * 写进 {@code MovingObjectPosition.hitInfo}（面 ordinal，核 = -1），
 * 于是<b>右键点到哪根臂就配置哪个面</b> —— 和 EIO 点外部连接器打开对应面的设置一个意思。
 */
public class BlockIoNode extends BlockContainer {

    public static final String NAME = "io_node";

    /** 贴图后缀：中心核 / 连接臂 / 端帽。 */
    private static final String[] TEXTURES = { "core", "arm", "cap" };

    /**
     * 自定义渲染器（ISBRH）的 id，客户端启动时由 ClientProxy 赋值。
     * 服务端永远不渲染这个方块，保持 0 无所谓。
     */
    public static int renderId;

    /** {@link #traceComponent} 的「什么都没点到」。 */
    private static final int MISS = Integer.MIN_VALUE;

    /**
     * 贴图（core / arm / cap 各一张）。
     *
     * <p>
     * 和 {@code BlockSharedTerminal} 同一条铁律：客户端专有类型，字段上不初始化，
     * 赋值全交给 {@code @SideOnly} 的 {@link #registerBlockIcons}，专用服务端才不会炸。
     */
    @SideOnly(Side.CLIENT)
    private IIcon[] textures;

    public BlockIoNode() {
        super(Material.iron);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setHardness(3.0F);
        setResistance(8.0F);
        setStepSound(Block.soundTypeMetal);
        setCreativeTab(CreativeTabs.tabMisc);
        // 默认碰撞 = 中心核那一小块；真正的逐组件碰撞在 addCollisionBoxesToList 里
        setBlockBounds(
            IoNodeGeometry.CORE_MIN,
            IoNodeGeometry.CORE_MIN,
            IoNodeGeometry.CORE_MIN,
            IoNodeGeometry.CORE_MAX,
            IoNodeGeometry.CORE_MAX,
            IoNodeGeometry.CORE_MAX);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileEntityIoNode();
    }

    // ==================================================================
    // 形态：不占满整格、自定义渲染
    // ==================================================================

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
        return renderId;
    }

    /** 活塞推不动：里面的面配置推掉就没了，还可能把别的模组的搬运循环搞乱。 */
    @Override
    public int getMobilityFlag() {
        return 2;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        textures = new IIcon[TEXTURES.length];
        for (int i = 0; i < TEXTURES.length; i++) {
            textures[i] = register.registerIcon(FutaGtnhMod.MODID + ":" + NAME + "_" + TEXTURES[i]);
        }
    }

    /** 物品栏里的图标（渲染走 ISBRH 的 renderInventoryBlock，这里只是兜底/NEI 用）。 */
    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int metadata) {
        return textures != null && textures.length > 0 ? textures[0] : super.getIcon(side, metadata);
    }

    /** 渲染器用：中心核贴图。 */
    @SideOnly(Side.CLIENT)
    public IIcon getCoreIcon() {
        return textures[0];
    }

    /** 渲染器用：连接臂贴图。 */
    @SideOnly(Side.CLIENT)
    public IIcon getArmIcon() {
        return textures[1];
    }

    /** 渲染器用：端帽贴图（按模式着色）。 */
    @SideOnly(Side.CLIENT)
    public IIcon getCapIcon() {
        return textures[2];
    }

    // ==================================================================
    // 碰撞 / 点选（逐组件）
    // ==================================================================

    @Override
    public void addCollisionBoxesToList(World world, int x, int y, int z, AxisAlignedBB clipBox,
        List<AxisAlignedBB> list, Entity entity) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityIoNode)) {
            super.addCollisionBoxesToList(world, x, y, z, clipBox, list, entity);
            return;
        }
        TileEntityIoNode node = (TileEntityIoNode) tile;

        // 中心核 + 每根连接臂（臂 + 端帽）：各自设一次碰撞箱再让原版收集
        addBoxToList(world, x, y, z, clipBox, list, entity, IoNodeGeometry.coreBox());
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            if (!node.hasItemConnection(face) && !node.hasFluidConnection(face)) continue;
            addBoxToList(world, x, y, z, clipBox, list, entity, IoNodeGeometry.armBox(face));
            addBoxToList(world, x, y, z, clipBox, list, entity, IoNodeGeometry.capBox(face));
        }
    }

    private void addBoxToList(World world, int x, int y, int z, AxisAlignedBB clipBox, List<AxisAlignedBB> list,
        Entity entity, AxisAlignedBB localBox) {
        setBlockBounds(
            (float) localBox.minX,
            (float) localBox.minY,
            (float) localBox.minZ,
            (float) localBox.maxX,
            (float) localBox.maxY,
            (float) localBox.maxZ);
        super.addCollisionBoxesToList(world, x, y, z, clipBox, list, entity);
    }

    /**
     * 逐组件的射线检测：命中哪根臂，{@code hitInfo} 就是那个面的 ordinal；命中中心核是 -1。
     * 这是「右键点到哪根臂就配置哪个面」的基础。
     */
    @Override
    public MovingObjectPosition collisionRayTrace(World world, int x, int y, int z, Vec3 origin, Vec3 direction) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityIoNode)) return super.collisionRayTrace(world, x, y, z, origin, direction);
        TileEntityIoNode node = (TileEntityIoNode) tile;

        MovingObjectPosition best = null;
        double bestDistance = Double.MAX_VALUE;

        MovingObjectPosition hit = rayTraceBox(world, x, y, z, origin, direction, IoNodeGeometry.coreBox());
        if (hit != null) {
            hit.hitInfo = -1;
            best = hit;
            bestDistance = hit.hitVec.squareDistanceTo(origin);
        }

        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            if (!node.hasItemConnection(face) && !node.hasFluidConnection(face)) continue;
            MovingObjectPosition armHit = rayTraceBox(world, x, y, z, origin, direction, IoNodeGeometry.armBox(face));
            if (armHit != null) {
                double distance = armHit.hitVec.squareDistanceTo(origin);
                if (distance < bestDistance) {
                    armHit.hitInfo = face.ordinal();
                    best = armHit;
                    bestDistance = distance;
                }
            }
            MovingObjectPosition capHit = rayTraceBox(world, x, y, z, origin, direction, IoNodeGeometry.capBox(face));
            if (capHit != null) {
                double distance = capHit.hitVec.squareDistanceTo(origin);
                if (distance < bestDistance) {
                    capHit.hitInfo = face.ordinal();
                    best = capHit;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private MovingObjectPosition rayTraceBox(World world, int x, int y, int z, Vec3 origin, Vec3 direction,
        AxisAlignedBB localBox) {
        setBlockBounds(
            (float) localBox.minX,
            (float) localBox.minY,
            (float) localBox.minZ,
            (float) localBox.maxX,
            (float) localBox.maxY,
            (float) localBox.maxZ);
        return super.collisionRayTrace(world, x, y, z, origin, direction);
    }

    /** 悬停描边：只框住视线命中的那个组件，而不是整个方块。 */
    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getSelectedBoundingBoxFromPool(World world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityIoNode)) return super.getSelectedBoundingBoxFromPool(world, x, y, z);
        TileEntityIoNode node = (TileEntityIoNode) tile;

        AxisAlignedBB local = IoNodeGeometry.coreBox();
        EntityPlayer player = Minecraft.getMinecraft().thePlayer;
        if (player != null) {
            int component = traceComponent(world, x, y, z, player);
            if (component == -1) local = IoNodeGeometry.coreBox();
            else if (component >= 0) local = IoNodeGeometry.armBox(ForgeDirection.getOrientation(component));
        }
        // 视觉上稍微收紧一点，避免 z-fighting 抖动
        return local.contract(0.02, 0.02, 0.02)
            .offset(x, y, z);
    }

    // ==================================================================
    // 交互
    // ==================================================================

    /**
     * 右键打开配置界面：<b>点到哪根连接臂就配置哪个面</b>（EIO 的做法）；
     * 点到中心核时打开第一个有连接的面 —— 界面里还有左右切面按钮。
     *
     * <p>
     * 潜行时返回 false 让路给 GT 扳手（和共享终端一致）。
     */
    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (player.isSneaking()) return false;
        if (world.isRemote) return true;

        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityIoNode)) return false;
        TileEntityIoNode node = (TileEntityIoNode) tile;

        int face = traceComponent(world, x, y, z, player);
        if (face == MISS || face == -1) {
            // 没点到臂（点到核 / 视线已经移开了）：开第一个有连接的面；一面都没连就开右键点的那个面
            int connected = firstConnectedFace(node);
            face = connected >= 0 ? connected : (side >= 0 && side < 6 ? side : ForgeDirection.SOUTH.ordinal());
        }
        player.openGui(FutaGtnhMod.instance, GuiHandler.GUI_IO_NODE_BASE + face, world, x, y, z);
        return true;
    }

    private static int firstConnectedFace(TileEntityIoNode node) {
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            if (node.hasItemConnection(face) || node.hasFluidConnection(face)) return face.ordinal();
        }
        return -1;
    }

    /**
     * 用玩家视线做一次组件级 raytrace。
     *
     * @return 命中臂的面 ordinal；命中中心核 = -1；什么都没命中 = {@link #MISS}
     */
    private int traceComponent(World world, int x, int y, int z, EntityPlayer player) {
        // 1.7.10 服务端拿不到「玩家实际交互距离」，给一个略大于 4.5/5.0 的定值即可 ——
        // 这只是「点的是哪个组件」的判定，安全边界在配置上行包的距离校验那里
        double reach = 6.0;
        // 1.7.10 没有「给我眼位」的现成方法，自己按眼睛高度拼一个
        Vec3 origin = Vec3.createVectorHelper(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 look = player.getLookVec();
        Vec3 end = origin.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);

        MovingObjectPosition hit = collisionRayTrace(world, x, y, z, origin, end);
        if (hit == null || hit.hitInfo == null || !(hit.hitInfo instanceof Integer)) return MISS;
        return (Integer) hit.hitInfo;
    }

    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block neighbour) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityIoNode) ((TileEntityIoNode) tile).neighbourChanged();
    }

    /** 无邻居方块通知的兜底（比如邻居的方块实体能力变了但方块没换）。 */
    @Override
    public void updateTick(World world, int x, int y, int z, Random random) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityIoNode) ((TileEntityIoNode) tile).refreshConnections();
    }

    /** 让 Forge 的邻居方块实体通知也走一遍连接重算（{@code onNeighborChange} 是 TE 级的）。 */
    @Override
    public void onNeighborChange(IBlockAccess world, int x, int y, int z, int tileX, int tileY, int tileZ) {
        if (world instanceof World) {
            TileEntity tile = world.getTileEntity(x, y, z);
            if (tile instanceof TileEntityIoNode) ((TileEntityIoNode) tile).neighbourChanged();
        }
    }
}
