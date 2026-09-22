package com.futa_gtnh.locator;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * 把玩家安全地送到某个方块附近。
 *
 * <p>
 * 「安全」这件事比看上去麻烦：目标方块本身往往<em>就是</em>墙的一部分
 * （比如你找的是「石砖」，那它大概率砌在房子里），直接传送到它的坐标就是
 * 把自己塞进实心方块里 —— 轻则卡住窒息，重则被挤出世界。
 *
 * <p>
 * 所以流程是：以目标为中心搜一小块区域，找<b>最近的一格能站人的位置</b>：
 *
 * <ul>
 * <li>脚下一格必须能站（实心、非液体、非火）；</li>
 * <li>身体占的两格必须能通过（空气或草/花这类不挡路的）；</li>
 * <li>不能在液体、火、岩浆、仙人掌里。</li>
 * </ul>
 *
 * <p>
 * 用欧氏距离挑最近的一格，所以「站在目标方块顶上」通常会被选中 ——
 * 那也确实是玩家想要的位置。
 */
public final class TeleportHelper {

    private TeleportHelper() {}

    /** 水平搜索半径。 */
    private static final int HORIZONTAL_RADIUS = 8;
    /** 垂直搜索半径。 */
    private static final int VERTICAL_RADIUS = 6;

    /**
     * 传送到目标方块附近的安全位置。
     *
     * @return 成功返回 true；找不到任何能站的地方返回 false（调用方应当告知玩家）
     */
    public static boolean teleportNear(EntityPlayerMP player, int targetX, int targetY, int targetZ) {
        World world = player.worldObj;
        if (world == null) return false;

        // 目标必须还在已加载的区块里。扫描器只会返回已加载区块里的结果，
        // 但玩家点了「传送」和真正执行之间隔了若干个 tick，这期间区块可能被卸载
        if (!isChunkLoaded(world, targetX, targetZ)) return false;

        int[] spot = findSafeSpot(world, targetX, targetY, targetZ);
        if (spot == null) return false;

        // 落到方块中心，而不是格子角上 —— 角上容易蹭到相邻方块
        double x = spot[0] + 0.5D;
        double y = spot[1];
        double z = spot[2] + 0.5D;

        player.setPositionAndUpdate(x, y, z);
        // 摔落距离必须清掉：不清的话从高空传送到地面会按「掉了一整个高度」结算摔伤
        player.fallDistance = 0.0F;
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;
        return true;
    }

    /**
     * @return 搜到的最安全的落脚点 {x, y, z}；一个都没有时返回 null
     */
    public static int[] findSafeSpot(World world, int cx, int cy, int cz) {
        IChunkProvider provider = world.getChunkProvider();
        if (provider == null) return null;

        int[] best = null;
        long bestDistance = Long.MAX_VALUE;

        for (int dx = -HORIZONTAL_RADIUS; dx <= HORIZONTAL_RADIUS; dx++) {
            for (int dz = -HORIZONTAL_RADIUS; dz <= HORIZONTAL_RADIUS; dz++) {
                int x = cx + dx;
                int z = cz + dz;

                // 跳区块要重新查一次 chunkExists，比逐个方块查便宜得多
                if (!provider.chunkExists(x >> 4, z >> 4)) continue;

                for (int dy = -VERTICAL_RADIUS; dy <= VERTICAL_RADIUS; dy++) {
                    int y = cy + dy;
                    if (!isSafeStandingSpot(world, x, y, z)) continue;

                    long distance = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new int[] { x, y, z };
                    }
                }
            }
        }
        return best;
    }

    private static boolean isChunkLoaded(World world, int x, int z) {
        IChunkProvider provider = world.getChunkProvider();
        return provider != null && provider.chunkExists(x >> 4, z >> 4);
    }

    /**
     * 判断 (x, y, z) 是不是一个能站人的落脚点（脚在 y，头在 y+1）。
     */
    private static boolean isSafeStandingSpot(World world, int x, int y, int z) {
        int height = world.getHeight();
        // 身体占两格，都要在世界高度以内；y 是脚的位置，所以最低也得是 1（0 层是基岩）
        if (y <= 0 || y + 1 >= height) return false;

        if (!canStandOn(world, x, y - 1, z)) return false;
        return isPassable(world, x, y, z) && isPassable(world, x, y + 1, z);
    }

    /** 脚下那格能不能站 —— 必须实心，而且不能是液体/火/岩浆。 */
    private static boolean canStandOn(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block == null || block.isAir(world, x, y, z)) return false;

        Material material = block.getMaterial();
        if (isDangerous(material)) return false;

        // blocksMovement 覆盖大部分实心方块；isSideSolid 补上「能站在栅栏/台阶上」
        // 这类碰撞箱与材质不一致的情况
        return material.blocksMovement() || block.isSideSolid(world, x, y, z, ForgeDirection.UP);
    }

    /** 这一格能不能把身体放进去。 */
    private static boolean isPassable(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block == null || block.isAir(world, x, y, z)) return true;

        Material material = block.getMaterial();
        if (isDangerous(material)) return false;
        // 蜘蛛网不挡路但会把人粘住，也不算「能站」
        if (block == Blocks.web) return false;

        return !material.blocksMovement();
    }

    private static boolean isDangerous(Material material) {
        return material == Material.lava || material == Material.fire
            || material == Material.water
            || material.isLiquid();
    }
}
