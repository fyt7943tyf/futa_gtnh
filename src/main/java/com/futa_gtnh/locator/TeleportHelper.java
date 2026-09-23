package com.futa_gtnh.locator;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.Config;

/**
 * 把玩家安全地送到某个方块附近。
 *
 * <p>
 * 「安全」这件事比看上去麻烦：目标方块本身往往<em>就是</em>墙的一部分
 * （比如你找的是「石砖」，那它大概率砌在房子里），直接传送到它的坐标就是
 * 把自己塞进实心方块里 —— 轻则卡住窒息，重则被挤出世界。
 *
 * <p>
 * 所以流程分两步：
 * <ol>
 * <li><b>先找现成的落脚点</b>：以目标为中心搜一块区域，找最近的一格能站人的位置。
 * 判定条件是「脚下一格实心且非液体/火 + 身体占的两格能通过（空气或草/花这类不挡路的）+
 * 不在蜘蛛网里」。搜索范围是水平 ±{@value #HORIZONTAL_RADIUS}、垂直 ±{@value #VERTICAL_RADIUS}
 * —— 垂直方向给得很大，因为「目标在山体里、而头顶几十格外的地表能站」是常见情况，
 * 那种位置仍然是「最近的可落脚处」。</li>
 * <li><b>实在没有就开一小块地方</b>（{@link Config#locatorTeleportCarve}，默认开）：
 * 挖矿时目标十有八九整个埋在实心石头里，周围几十格连一格空气都没有 ——
 * 那时候只在目标附近 ±{@value #CARVE_RADIUS} 格内找一处能开挖的位置，
 * 清掉玩家身体那两格。开洞的约束见 {@link #isCarveable}：脚下本来就得是实心的、
 * 不碰目标方块本身、不碰挖不动或带方块实体的方块、四周有液体就不开。</li>
 * </ol>
 *
 * <p>
 * 用欧氏距离挑最近的一格，所以「站在目标方块顶上」通常会被选中 ——
 * 那也确实是玩家想要的位置。
 */
public final class TeleportHelper {

    private TeleportHelper() {}

    /** 第一圈：目标附近的现成位置（±8 / ±6）。有就不动世界。 */
    private static final int NEAR_HORIZONTAL_RADIUS = 8;
    private static final int NEAR_VERTICAL_RADIUS = 6;
    /** 最后一招：大范围找现成的位置。垂直给得大，因为地表离矿脉往往有几十格。 */
    private static final int HORIZONTAL_RADIUS = 16;
    private static final int VERTICAL_RADIUS = 64;
    /** 开洞时允许离目标多远（只清两格，所以不需要很大）。 */
    private static final int CARVE_RADIUS = 4;

    /** 传送结果，调用方据此决定提示什么。 */
    public enum Result {
        /** 找不到能站人的地方，也不许/没法开洞 */
        FAILED,
        /** 找到了现成的安全位置 */
        NATURAL,
        /** 目标埋在实心方块里，就地清了两格 */
        CARVED
    }

    /**
     * 传送到目标方块附近的安全位置。
     *
     * <p>
     * 三种情况按这个顺序处理，目的是「既不动世界、又能真的落在目标旁边」：
     * <ol>
     * <li>目标附近（±{@value #NEAR_HORIZONTAL_RADIUS}/±{@value #NEAR_VERTICAL_RADIUS}）
     * 就有现成能站的地方 —— 最常见的是矿脉露在洞壁上。直接用，一格方块都不动；</li>
     * <li>附近没有（矿脉整个埋在石头里，这才是挖矿的常态）—— 就地开一小块地方
     * （见 {@link #carveSpot}），人就落在目标旁边，而不是跑到几十格外的某个洞穴里；</li>
     * <li>连开洞都不行（目标在岩浆里、基岩里……）—— 再退到「大范围找一个现成的位置」，
     * 水平 ±{@value #HORIZONTAL_RADIUS}、垂直 ±{@value #VERTICAL_RADIUS}，
     * 头顶几十格外的地表也算。</li>
     * </ol>
     *
     * @return 结果；{@link Result#FAILED} 时调用方应当告知玩家
     */
    public static Result teleportNear(EntityPlayerMP player, int targetX, int targetY, int targetZ) {
        World world = player.worldObj;
        if (world == null) return Result.FAILED;

        // 目标必须还在已加载的区块里。扫描器只会返回已加载区块里的结果，
        // 但玩家点了「传送」和真正执行之间隔了若干个 tick，这期间区块可能被卸载
        if (!isChunkLoaded(world, targetX, targetZ)) return Result.FAILED;

        Result result = Result.NATURAL;
        int[] spot = findSafeSpot(world, targetX, targetY, targetZ, NEAR_HORIZONTAL_RADIUS, NEAR_VERTICAL_RADIUS);

        if (spot == null && Config.locatorTeleportCarve) {
            spot = carveSpot(world, targetX, targetY, targetZ);
            if (spot != null) result = Result.CARVED;
        }

        if (spot == null) {
            // 最后一招：目标附近没有、也开不出来，就去远处找现成的（比如头顶的地表）
            spot = findSafeSpot(world, targetX, targetY, targetZ, HORIZONTAL_RADIUS, VERTICAL_RADIUS);
            result = Result.NATURAL;
        }
        if (spot == null) return Result.FAILED;

        // 落到方块中心，而不是格子角上 —— 角上容易蹭到相邻方块
        player.setPositionAndUpdate(spot[0] + 0.5D, spot[1], spot[2] + 0.5D);
        // 摔落距离必须清掉：不清的话从高空传送到地面会按「掉了一整个高度」结算摔伤
        player.fallDistance = 0.0F;
        player.motionX = 0.0D;
        player.motionY = 0.0D;
        player.motionZ = 0.0D;
        return result;
    }

    /**
     * @return 搜到的最安全的落脚点 {x, y, z}；一个都没有时返回 null
     */
    public static int[] findSafeSpot(World world, int cx, int cy, int cz) {
        return findSafeSpot(world, cx, cy, cz, HORIZONTAL_RADIUS, VERTICAL_RADIUS);
    }

    private static int[] findSafeSpot(World world, int cx, int cy, int cz, int horizontal, int vertical) {
        IChunkProvider provider = world.getChunkProvider();
        if (provider == null) return null;

        int[] best = null;
        long bestDistance = Long.MAX_VALUE;

        for (int dx = -horizontal; dx <= horizontal; dx++) {
            for (int dz = -horizontal; dz <= horizontal; dz++) {
                int x = cx + dx;
                int z = cz + dz;

                // 跳区块要重新查一次 chunkExists，比逐个方块查便宜得多
                if (!provider.chunkExists(x >> 4, z >> 4)) continue;

                for (int dy = -vertical; dy <= vertical; dy++) {
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

    // ==================================================================
    // 兜底：就地开一小块地方
    // ==================================================================

    /**
     * 目标整个埋在实心方块里时，就地清出一个能站人的位置。
     *
     * <p>
     * 最理想的那一格就在目标正上方：清掉它上面两格，人就站在那块矿（或者别的实心方块）上，
     * 而<b>目标方块本身一格都不动</b> —— 你来找的那块矿还在原地。
     *
     * @return 落脚点 {x, y, z}；连开洞都开不出来时返回 null
     */
    private static int[] carveSpot(World world, int cx, int cy, int cz) {
        IChunkProvider provider = world.getChunkProvider();
        if (provider == null) return null;

        int[] best = null;
        long bestDistance = Long.MAX_VALUE;

        for (int dx = -CARVE_RADIUS; dx <= CARVE_RADIUS; dx++) {
            for (int dz = -CARVE_RADIUS; dz <= CARVE_RADIUS; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (!provider.chunkExists(x >> 4, z >> 4)) continue;

                for (int dy = -CARVE_RADIUS; dy <= CARVE_RADIUS; dy++) {
                    int y = cy + dy;
                    if (!isCarveable(world, x, y, z, cx, cy, cz)) continue;

                    long distance = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new int[] { x, y, z };
                    }
                }
            }
        }

        if (best == null) return null;

        // 清掉身体那两格。setBlockToAir 不走「破坏方块」那条路（不会掉东西），
        // 所以这里不会顺手刷出几块矿石
        world.setBlockToAir(best[0], best[1], best[2]);
        world.setBlockToAir(best[0], best[1] + 1, best[2]);
        return best;
    }

    /**
     * 这一格能不能「挖出来站人」。
     *
     * <p>
     * 约束是有意的，每一条都对应一种会把事情弄坏的情况：
     * <ul>
     * <li>脚下那格必须<b>本来就能站</b>（实心、非液体/火）—— 这样只清方块、不放方块，
     * 也不会让人掉下去；</li>
     * <li>身体那两格可以清成空气：不是基岩这类挖不动的（硬度 &lt; 0）、
     * 不是带方块实体的（免得把箱子/机器删成一个幽灵方块）；</li>
     * <li>两格身体的六个方向都没有液体/岩浆/火 —— 否则清开就是让它灌进来；</li>
     * <li><b>不碰目标方块本身</b>：脚或头压在目标那一格上就换一个位置，
     * 免得把你辛苦找来的那块矿清掉。</li>
     * </ul>
     */
    private static boolean isCarveable(World world, int x, int y, int z, int targetX, int targetY, int targetZ) {
        int height = world.getHeight();
        if (y <= 0 || y + 1 >= height) return false;

        // 目标方块本身不能被清掉
        if (x == targetX && z == targetZ && (y == targetY || y + 1 == targetY)) return false;

        if (!canStandOn(world, x, y - 1, z)) return false;
        if (!isClearable(world, x, y, z) || !isClearable(world, x, y + 1, z)) return false;
        return !liquidAround(world, x, y, z);
    }

    /** 这一格能不能被清成空气。 */
    private static boolean isClearable(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block == null || block.isAir(world, x, y, z)) return true;

        // 基岩这类挖不动的：不动
        if (block.getBlockHardness(world, x, y, z) < 0.0F) return false;
        // 带方块实体的（箱子、机器……）：清掉会留下一个幽灵方块，不动
        if (block.hasTileEntity(world.getBlockMetadata(x, y, z))) return false;
        // 液体/火/岩浆本来就不该站在里面
        return !isDangerous(block.getMaterial());
    }

    /** 身体那两格的六个方向有没有液体/岩浆/火（有的话清开就是让它灌进来）。 */
    private static boolean liquidAround(World world, int x, int y, int z) {
        for (int yy = y; yy <= y + 1; yy++) {
            for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                Block block = world.getBlock(x + dir.offsetX, yy + dir.offsetY, z + dir.offsetZ);
                if (block != null && isDangerous(block.getMaterial())) return true;
            }
        }
        return false;
    }

    // ==================================================================
    // 判定
    // ==================================================================

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
