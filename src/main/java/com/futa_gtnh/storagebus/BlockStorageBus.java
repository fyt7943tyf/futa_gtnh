package com.futa_gtnh.storagebus;

import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
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
 * 共享存储总线方块。
 *
 * <p>
 * 一个方块格子里最多可以放 6 条互相独立的总线，分别贴向六个相邻容器；
 * 每条总线是一块 AE 风格的三层阶梯面板（10×10 底板、12×12 中层、6×6×2 凸起接口），
 * 有自己的目标、优先级、访问模式和筛选。外形参考 AE2 PartStorageBus 的 getBoxes，
 * 同时保留「一个格子里挂多个部件」的用法，见 {@link TileEntityStorageBus}。
 *
 * <p>
 * 第一次放下消耗一个物品；之后拿着总线右键另一个相邻容器，会把新的一条总线加到
 * 同一格子的那一面（由 {@link ItemStorageBus} 处理）。左键点某一面的板会只拆下那一条；
 * 整块被破坏时才会把所有面一起拆掉。
 */
public class BlockStorageBus extends BlockContainer {

    public static final String NAME = "storage_bus";

    /** ISBRH 渲染器 id，客户端启动时由 ClientProxy 赋值；服务端保持 0。 */
    public static int renderId;

    /**
     * 直接引用 AE2 自带的存储总线图标（不复制资源）：侧面、物品/正面、背面。
     * AE2 是硬依赖，纹理在同一个贴图册里。
     */
    private static final String[] TEXTURES = { "appliedenergistics2:PartStorageSides",
        "appliedenergistics2:ItemPart.StorageBus", "appliedenergistics2:PartStorageBack" };

    private static final int MISS = Integer.MIN_VALUE;

    @SideOnly(Side.CLIENT)
    private IIcon[] textures;

    public BlockStorageBus() {
        super(Material.iron);
        setBlockName(FutaGtnhMod.MODID + "." + NAME);
        setHardness(3.0F);
        setResistance(8.0F);
        setStepSound(Block.soundTypeMetal);
        setCreativeTab(CreativeTabs.tabMisc);
        setLightOpacity(0);
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
    // 形态 / 渲染
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

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        textures = new IIcon[TEXTURES.length];
        for (int i = 0; i < TEXTURES.length; i++) {
            textures[i] = register.registerIcon(TEXTURES[i]);
        }
    }

    /** 物品栏 / 破坏粒子用的扁平图标。世界里的阶梯面板由 ISBRH 画。 */
    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int metadata) {
        return textures == null ? null : textures[1];
    }

    /** 渲染器用：面板正面（AE 存储总线物品图标）。 */
    @SideOnly(Side.CLIENT)
    public IIcon getFrontIcon() {
        return textures == null ? null : textures[1];
    }

    /** 渲染器用：面板侧面（AE PartStorageSides）。 */
    @SideOnly(Side.CLIENT)
    public IIcon getSideIcon() {
        return textures == null ? null : textures[0];
    }

    // ==================================================================
    // 碰撞 / 点选（逐板）
    // ==================================================================

    @Override
    public void addCollisionBoxesToList(World world, int x, int y, int z, AxisAlignedBB mask, List<AxisAlignedBB> list,
        Entity entity) {
        int faceMask = getFaceMask(world, x, y, z);
        if (faceMask == 0) return;
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            if ((faceMask & (1 << face.ordinal())) == 0) continue;
            addBoxToList(world, x, y, z, mask, list, entity, StorageBusGeometry.plateBox(face));
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

    @Override
    public MovingObjectPosition collisionRayTrace(World world, int x, int y, int z, Vec3 origin, Vec3 direction) {
        int faceMask = getFaceMask(world, x, y, z);
        if (faceMask == 0) return null;

        MovingObjectPosition best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ForgeDirection face : ForgeDirection.VALID_DIRECTIONS) {
            if ((faceMask & (1 << face.ordinal())) == 0) continue;
            MovingObjectPosition hit = rayTraceBox(
                world,
                x,
                y,
                z,
                origin,
                direction,
                StorageBusGeometry.plateBox(face));
            if (hit == null) continue;
            double distance = hit.hitVec.squareDistanceTo(origin);
            if (distance < bestDistance) {
                hit.hitInfo = Integer.valueOf(face.ordinal());
                best = hit;
                bestDistance = distance;
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

    /** 悬停描边只框住视线命中的那块板。 */
    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getSelectedBoundingBoxFromPool(World world, int x, int y, int z) {
        int faceMask = getFaceMask(world, x, y, z);
        if (faceMask == 0) {
            return StorageBusGeometry.plateBox(ForgeDirection.DOWN)
                .getOffsetBoundingBox(x, y, z);
        }

        int face = traceComponent(world, x, y, z, Minecraft.getMinecraft().thePlayer);
        if (face == MISS || face < 0) face = firstFace(faceMask);
        AxisAlignedBB local = StorageBusGeometry.plateBox(ForgeDirection.getOrientation(face));
        return local.contract(0.02, 0.02, 0.02)
            .getOffsetBoundingBox(x, y, z);
    }

    private static int firstFace(int mask) {
        for (int i = 0; i < TileEntityStorageBus.FACES; i++) {
            if ((mask & (1 << i)) != 0) return i;
        }
        return 0;
    }

    /** @return 这个位置有哪几块板；服务端读 TE，客户端读描述包同步过来的掩码 */
    public static int getFaceMask(IBlockAccess world, int x, int y, int z) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityStorageBus) {
            int mask = ((TileEntityStorageBus) tile).getFaceMask();
            if (mask != 0) return mask;
        }
        // 不完整数据（方块实体还没同步 / 旧存档）时退回初始目标面 metadata
        int meta = world.getBlockMetadata(x, y, z);
        return meta >= 0 && meta < TileEntityStorageBus.FACES ? 1 << meta : 0;
    }

    // ==================================================================
    // 放置 / 交互
    // ==================================================================

    /**
     * 1.7.10 是「先 onBlockPlaced 算 metadata，再 placeBlockAt 真正放方块」。
     * metadata 这里只记下第一次贴着的那一面，方块实体放好后再创建第一条总线。
     */
    @Override
    public int onBlockPlaced(World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ,
        int metadata) {
        int result = super.onBlockPlaced(world, x, y, z, side, hitX, hitY, hitZ, metadata);
        if (world == null || world.isRemote) return result;

        ForgeDirection preferred = ForgeDirection.getOrientation(side)
            .getOpposite();
        ForgeDirection target = TileEntityStorageBus.pickTargetSide(world, x, y, z, preferred);
        return target == ForgeDirection.UNKNOWN ? result : target.ordinal();
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, net.minecraft.entity.EntityLivingBase placer,
        net.minecraft.item.ItemStack stack) {
        super.onBlockPlacedBy(world, x, y, z, placer, stack);
        if (world == null || world.isRemote) return;

        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityStorageBus)) return;
        int meta = world.getBlockMetadata(x, y, z);
        if (meta >= 0 && meta < TileEntityStorageBus.FACES) {
            ((TileEntityStorageBus) tile).addFace(ForgeDirection.getOrientation(meta));
        }
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (player.isSneaking()) return false;
        if (world.isRemote) return true;

        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityStorageBus)) return false;
        TileEntityStorageBus bus = (TileEntityStorageBus) tile;
        if (bus.getFaceMask() == 0) return false;

        int face = traceComponent(world, x, y, z, player);
        if (face == MISS || face < 0) face = firstFace(bus.getFaceMask());
        bus.ensureRegistered();
        player.openGui(FutaGtnhMod.instance, GuiHandler.GUI_STORAGE_BUS_BASE + face, world, x, y, z);
        return true;
    }

    /** 左键点某一面的板：只拆这一条；拆掉最后一条时才移除方块。 */
    @Override
    public void onBlockClicked(World world, int x, int y, int z, EntityPlayer player) {
        removeFaceAt(world, x, y, z, player);
    }

    /**
     * 按玩家视线拆掉命中的那一面。
     *
     * <p>
     * 这个方块故意不可被正常挖掉（{@link #getBlockHardness} 返回 -1），拆除只能一面一面来。
     * 生存和创造都由 {@code StorageBusLeftClickHandler} 在左键事件里调用这里并取消整块破坏；
     * {@link #onBlockClicked} 只是没有事件时的兜底。
     */
    public static boolean removeFaceAt(World world, int x, int y, int z, EntityPlayer player) {
        if (world == null || world.isRemote || player == null) return false;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileEntityStorageBus)) return false;
        TileEntityStorageBus bus = (TileEntityStorageBus) tile;

        int face = traceComponent(world, x, y, z, player);
        if (face == MISS || face < 0) return false;
        ForgeDirection side = ForgeDirection.getOrientation(face);
        if (!bus.removeFace(side)) return false;

        if (!player.capabilities.isCreativeMode) dropBusItem(world, x, y, z);
        if (bus.getFaceMask() == 0) {
            world.setBlockToAir(x, y, z);
        } else {
            world.markBlockForUpdate(x, y, z);
        }
        return true;
    }

    /** 不允许整块挖掉；每一下左键只拆一面。爆炸/命令等外部破坏仍走 breakBlock。 */
    @Override
    public float getBlockHardness(World world, int x, int y, int z) {
        return -1.0F;
    }

    @Override
    public void breakBlock(World world, int x, int y, int z, Block block, int metadata) {
        TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileEntityStorageBus) {
            int mask = ((TileEntityStorageBus) tile).getFaceMask();
            for (int i = 0; i < TileEntityStorageBus.FACES; i++) {
                if ((mask & (1 << i)) != 0) dropBusItem(world, x, y, z);
            }
        }
        super.breakBlock(world, x, y, z, block, metadata);
    }

    private static void dropBusItem(World world, int x, int y, int z) {
        if (FutaGtnhMod.blockStorageBus == null) return;
        ItemStack stack = new ItemStack(FutaGtnhMod.blockStorageBus, 1, 0);
        EntityItem item = new EntityItem(world, x + 0.5D, y + 0.5D, z + 0.5D, stack);
        item.delayBeforeCanPickup = 10;
        world.spawnEntityInWorld(item);
    }

    /**
     * 用玩家视线做一次组件级 raytrace。
     *
     * @return 命中板的面 ordinal；什么都没命中 = {@link #MISS}
     */
    private static int traceComponent(World world, int x, int y, int z, EntityPlayer player) {
        if (player == null) return MISS;
        double reach = 6.0;
        Vec3 origin = Vec3.createVectorHelper(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        Vec3 look = player.getLookVec();
        Vec3 end = origin.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);

        MovingObjectPosition hit = world.getBlock(x, y, z)
            .collisionRayTrace(world, x, y, z, origin, end);
        if (hit == null || !(hit.hitInfo instanceof Integer)) return MISS;
        return ((Integer) hit.hitInfo).intValue();
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
