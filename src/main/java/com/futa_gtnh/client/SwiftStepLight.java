package com.futa_gtnh.client;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.futa_gtnh.CommonProxy;
import com.futa_gtnh.item.ItemSwiftStep;

/**
 * 「戴着迅步就自带火把」的客户端半边：每 tick 把隐形光源挪到玩家脚下。
 *
 * <p>
 * 做法和那些「动态光源」模组一样：在玩家所在的那一格<b>放一个会发光的隐形方块</b>，
 * 人走开就把旧的收掉、在新位置放一个。原版光照引擎在自己这份方块数据变化时
 * 会立刻重算光照（{@code World.setBlock} 里那句 {@code func_147451_t}），
 * 所以效果和真的举了个火把一样。
 *
 * <p>
 * <b>只动客户端的世界。</b>服务端那份方块数据一个字节都不改：
 * <ul>
 * <li>不会在存档里留下任何东西（哪怕崩溃在「刚放下」那一刻）；</li>
 * <li>不需要服务端 tick，别人也看不见；</li>
 * <li>代价：<b>不影响服务端的刷怪判定</b>——看得见，但洞里该刷怪还是刷。</li>
 * </ul>
 *
 * <p>
 * <b>只往空气里放</b>，而且收回时只收「原样还是我们那个方块」的格子：
 * 万一服务端后来把那一格改成了真方块（我们在客户端看到的还是自己的光源），
 * 收回时就不会手贱把真方块清掉。
 */
public final class SwiftStepLight {

    private SwiftStepLight() {}

    /** 上一次放光源的世界 + 坐标；没放时为 null。 */
    private static World placedWorld;
    private static int placedX;
    private static int placedY;
    private static int placedZ;
    private static int placedLevel;

    /**
     * 每 tick 调一次（客户端，END 阶段）。
     *
     * @param player 本地玩家
     */
    public static void tick(EntityPlayer player) {
        if (player == null || CommonProxy.swiftLight == null) return;

        World world = player.worldObj;
        if (world == null) {
            forget();
            return;
        }

        // 换了世界（下界/末地/重进存档）：旧那份追踪已经没意义了，直接丢掉。
        // 不去旧世界清方块 —— 每个世界各自算光照，那边不清理也不会亮到这边来
        if (placedWorld != null && placedWorld != world) {
            forget();
        }

        ItemStack charm = ItemSwiftStep.findEquipped(player);
        int level = charm == null ? 0 : ItemSwiftStep.getLightLevel(charm);

        int x = net.minecraft.util.MathHelper.floor_double(player.posX);
        int y = net.minecraft.util.MathHelper.floor_double(player.posY);
        int z = net.minecraft.util.MathHelper.floor_double(player.posZ);

        if (level <= 0) {
            clearPlaced(world);
            return;
        }

        // 先找一格能放的位置：脚下优先，站的地方不是空气（草丛、雪、水……）就往上找一格
        int spotY = findAirY(world, x, y, z);
        if (spotY == Integer.MIN_VALUE) {
            // 位里一格空气都没有（例如泡在水里）：先把手上的收掉，别留个孤零零的光源
            clearPlaced(world);
            return;
        }

        Block light = CommonProxy.swiftLight;
        boolean samePlace = placedWorld == world && placedX == x
            && placedY == spotY
            && placedZ == z
            && placedLevel == level
            && world.getBlock(x, spotY, z) == light
            && (world.getBlockMetadata(x, spotY, z) & 15) == level;
        // 右键交互等服务端方块更新可能覆盖客户端的临时光源；坐标没变时也要核对实际方块，
        // 否则缓存会误以为光源还在，直到玩家移动才重新放置。
        if (samePlace) return;

        // 先放新的再收旧的：中间不会出现「两边都黑」的一帧
        world.setBlock(x, spotY, z, light, level, 3);
        if (placedWorld == world && !(placedX == x && placedY == spotY && placedZ == z)) {
            clearAt(world, placedX, placedY, placedZ);
        }

        placedWorld = world;
        placedX = x;
        placedY = spotY;
        placedZ = z;
        placedLevel = level;
    }

    /** @return 这一列里第一格能放光源的高度；都放不了返回 {@link Integer#MIN_VALUE} */
    private static int findAirY(World world, int x, int y, int z) {
        for (int dy = 0; dy <= 2; dy++) {
            int candidate = y + dy;
            if (candidate <= 0 || candidate >= world.getHeight()) continue;
            Block block = world.getBlock(x, candidate, z);
            // 只认「本来就是空气」：替换草丛/水之类的东西会在客户端留下一个视觉空洞
            if (block == null || block.isAir(world, x, candidate, z)) return candidate;
        }
        return Integer.MIN_VALUE;
    }

    /** 收掉现在放着的那一个。 */
    private static void clearPlaced(World world) {
        if (placedWorld == null) return;
        clearAt(placedWorld, placedX, placedY, placedZ);
        forget();
    }

    /** 只清「还是我们那个方块」的格子：服务端可能已经把那一格改成真方块了。 */
    private static void clearAt(World world, int x, int y, int z) {
        if (world == null || CommonProxy.swiftLight == null) return;
        if (world.getBlock(x, y, z) != CommonProxy.swiftLight) return;
        world.setBlockToAir(x, y, z);
    }

    private static void forget() {
        placedWorld = null;
        placedLevel = 0;
    }

    /** 客户端退出世界时调一次（切存档/断线），把追踪状态清干净。 */
    public static void reset() {
        forget();
    }

    /** 给调试日志用：现在放在哪、多亮。 */
    static String describe() {
        if (placedWorld == null) return "未放置";
        return "(" + placedX + ", " + placedY + ", " + placedZ + ") 亮度 " + placedLevel;
    }
}
