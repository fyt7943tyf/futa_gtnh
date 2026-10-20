package com.futa_gtnh.storagebus;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketStorageBusUse;

/**
 * 存储总线的物品形态。
 *
 * <p>
 * 放置语义参考 AE2 PartStorageBus：
 * <ul>
 * <li>一面一个部件；同一个格子里可以有多个面；</li>
 * <li>右键一个方块时，先找它周围已有的总线格，加面到那个格子；</li>
 * <li>周围没有总线格才在新位置放第一块；</li>
 * <li>放置/加面不自动打开 GUI，需要右键已有面板才打开。</li>
 * </ul>
 *
 * <p>
 * 1.7.10 的右键顺序是「方块 GUI → onItemUse」；容器会吃掉右键。所以这里在
 * {@code onItemUseFirst} 里处理：客户端只发一个 C2S 包并返回 true，阻止目标容器 GUI；
 * 服务端真正放置/加面。这样普通右键和 Shift+右键都不会弹目标 GUI。
 */
public class ItemStorageBus extends ItemBlock {

    public ItemStorageBus(Block block) {
        super(block);
        setMaxStackSize(64);
    }

    /** 一次右键最终会做什么。 */
    public enum Kind {
        NONE,
        /** 新放一个总线方块。 */
        PLACE,
        /** 给已有的总线格加一面。 */
        ADD,
        /** 这一面已经有总线；不重复加。 */
        FACE
    }

    /** placement 解析结果；客户端预览和服务端执行共用。 */
    public static final class Placement {

        public static final Placement NONE = new Placement(Kind.NONE, null, 0, 0, 0, ForgeDirection.UNKNOWN);

        public final Kind kind;
        /** ADD 时是已有总线；PLACE/NONE 时为 null。 */
        public final TileEntityStorageBus bus;
        public final int x;
        public final int y;
        public final int z;
        /** 从总线格指向被右键方块的面。 */
        public final ForgeDirection target;

        private Placement(Kind kind, TileEntityStorageBus bus, int x, int y, int z, ForgeDirection target) {
            this.kind = kind;
            this.bus = bus;
            this.x = x;
            this.y = y;
            this.z = z;
            this.target = target;
        }
    }

    /**
     * 解析一次右键会落在哪个总线格、哪一面。客户端预览和服务端执行必须用同一套规则。
     *
     * @param x,y,z 被右键的方块
     * @param side  被右键的面
     */
    public static Placement resolve(World world, int x, int y, int z, int side) {
        if (world == null || side < 0 || side >= 6) return Placement.NONE;
        Block busBlock = FutaGtnhMod.blockStorageBus;
        if (busBlock == null) return Placement.NONE;

        // 点在已有的总线板上：不放置，交给方块 GUI。
        if (world.getBlock(x, y, z) == busBlock) return Placement.NONE;

        ForgeDirection clicked = ForgeDirection.getOrientation(side);

        // 先看右键方向那一格；再看被右键方块周围其它五格。
        Placement preferred = busAt(
            world,
            x + clicked.offsetX,
            y + clicked.offsetY,
            z + clicked.offsetZ,
            clicked.getOpposite());
        if (preferred != null) return preferred;

        Placement free = null;
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            if (direction == clicked) continue;
            Placement candidate = busAt(
                world,
                x + direction.offsetX,
                y + direction.offsetY,
                z + direction.offsetZ,
                direction.getOpposite());
            if (candidate == null) continue;
            if (candidate.kind == Kind.FACE) return candidate;
            if (free == null) free = candidate;
        }
        if (free != null) return free;

        // 周围没有总线格：把第一块放到被右键方块外侧。
        int busX = x + clicked.offsetX;
        int busY = y + clicked.offsetY;
        int busZ = z + clicked.offsetZ;
        Block at = world.getBlock(busX, busY, busZ);
        if (!at.isAir(world, busX, busY, busZ) && !at.isReplaceable(world, busX, busY, busZ)) return Placement.NONE;
        if (!busBlock.canPlaceBlockAt(world, busX, busY, busZ)) return Placement.NONE;
        return new Placement(Kind.PLACE, null, busX, busY, busZ, clicked.getOpposite());
    }

    private static Placement busAt(World world, int busX, int busY, int busZ, ForgeDirection target) {
        if (world.getBlock(busX, busY, busZ) != FutaGtnhMod.blockStorageBus) return null;
        TileEntity tile = world.getTileEntity(busX, busY, busZ);
        if (!(tile instanceof TileEntityStorageBus)) return null;
        TileEntityStorageBus bus = (TileEntityStorageBus) tile;
        Kind kind = bus.hasFace(target) ? Kind.FACE : Kind.ADD;
        return new Placement(kind, bus, busX, busY, busZ, target);
    }

    @Override
    public boolean onItemUseFirst(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        if (player == null) return false;

        if (world.isRemote) {
            // 点在已有的总线板上：交给方块 GUI（右键面板配置）。
            if (world.getBlock(x, y, z) == FutaGtnhMod.blockStorageBus) return false;
            // 其它情况一律由服务端重新解析：不在这里判断“能不能放”，
            // 避免客户端 TE 掩码未同步时误判；同时挡住目标容器 GUI、原生 C08 也不发。
            NetworkHandler.INSTANCE.sendToServer(new PacketStorageBusUse(x, y, z, side));
            return true;
        }

        Placement placement = resolve(world, x, y, z, side);
        return applyServerUse(player, world, stack, placement);
    }

    /** 服务端执行解析结果；不打开 GUI。 */
    public static boolean applyServerUse(EntityPlayer player, World world, ItemStack stack, Placement placement) {
        if (player == null || world == null || world.isRemote || placement == null) return false;
        if (placement.kind == Kind.ADD) {
            if (placement.bus == null || !placement.bus.addFace(placement.target)) return false;
            consume(player, stack);
            return true;
        }
        if (placement.kind != Kind.PLACE) return false;

        Block busBlock = FutaGtnhMod.blockStorageBus;
        if (busBlock == null) return false;
        if (!world.setBlock(placement.x, placement.y, placement.z, busBlock, placement.target.ordinal(), 3)) {
            return false;
        }
        TileEntity tile = world.getTileEntity(placement.x, placement.y, placement.z);
        if (!(tile instanceof TileEntityStorageBus)) {
            world.setBlockToAir(placement.x, placement.y, placement.z);
            return false;
        }
        TileEntityStorageBus bus = (TileEntityStorageBus) tile;
        if (!bus.addFace(placement.target)) {
            world.setBlockToAir(placement.x, placement.y, placement.z);
            return false;
        }
        consume(player, stack);
        return true;
    }

    /** 服务端包处理：重新解析一次，客户端结果不作数。 */
    public static boolean applyServerUse(EntityPlayer player, World world, ItemStack stack, int x, int y, int z,
        int side) {
        return applyServerUse(player, world, stack, resolve(world, x, y, z, side));
    }

    /**
     * Shift+右键时客户端会先检查“目标旁边能不能放方块”；加第二个面时旁边就是已有总线，
     * 原版会认为被占用而不发包。这里始终放行，由服务端和上面的 C2S 包决定。
     */
    @Override
    public boolean func_150936_a(World world, int x, int y, int z, int side, EntityPlayer player, ItemStack stack) {
        return true;
    }

    /** 放置/加面全部由 onItemUseFirst + C2S 包完成，禁用原版二次放置。 */
    @Override
    public boolean onItemUse(ItemStack stack, EntityPlayer player, World world, int x, int y, int z, int side,
        float hitX, float hitY, float hitZ) {
        return false;
    }

    private static void consume(EntityPlayer player, ItemStack stack) {
        if (player.capabilities.isCreativeMode || stack == null) return;
        --stack.stackSize;
        player.inventory.markDirty();
    }
}
