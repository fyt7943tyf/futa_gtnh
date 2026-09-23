package com.futa_gtnh.locator;

import java.util.Locale;

import net.minecraft.block.Block;
import net.minecraft.block.BlockOre;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.oredict.OreDictionary;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

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
 * 那时候只在目标附近 ±{@value #CARVE_RADIUS} 格内找一处能开挖的位置
 * （全被矿石占着就扩到 ±{@value #WIDE_CARVE_RADIUS}），
 * 清掉玩家身体那两格。开洞的约束见 {@link #canClear}：脚下本来就得是实心的、
 * 不碰目标方块本身、不碰挖不动或带方块实体的方块、四周有液体就不开，
 * 而且<b>只清「没用的方块」—— 矿石一格都不许动</b>，木头/玻璃/金属/机器一律不碰。</li>
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
    /**
     * ±{@value #CARVE_RADIUS} 格内全被矿石占着时的第二圈。
     *
     * <p>
     * GT 的矿脉很粗（横截面常常 7 格以上），人在矿脉正中间时，附近每一格的「身体位置」
     * 都可能是矿 —— 那时候不许动矿石就意味着开不出洞来。往外多找一圈仍然不许动矿，
     * 只是落脚点离目标远了几格。<b>宁可站远一点，也不拆玩家的矿。</b>
     */
    private static final int WIDE_CARVE_RADIUS = 8;

    /** 传送结果，调用方据此决定提示什么。 */
    public enum Result {
        /** 找不到能站人的地方，也不许/没法开洞 */
        FAILED,
        /**
         * 找不到能站人的地方，而唯一能开洞的位置得清掉矿石/木头/机器这类不该动的方块，
         * 所以放弃开洞。和 {@link #FAILED} 分开，是为了让玩家知道「不是找不到，是我不肯挖」。
         */
        FAILED_PROTECTED,
        /** 找到了现成的安全位置 */
        NATURAL,
        /** 目标埋在实心方块里，就地清了两格 */
        CARVED
    }

    /**
     * GT 的矿石 API 在不在；null = 还没探过。
     *
     * <p>
     * 和 {@code LocatorScan} 里的三态缓存同一个道理：{@code GtOreSupport} 直接引用了
     * {@code gregtech.common.blocks.GTBlockOre}，GT 不在时<b>第一次</b>访问会抛
     * {@code NoClassDefFoundError}。类加载失败这件事本身很贵（要填栈），
     * 而开洞扫描会问它几百次，所以结果必须记住。
     */
    private static Boolean gtOreApi;

    /**
     * 传送到目标方块附近的安全位置。
     *
     * <p>
     * 四种情况按这个顺序处理，目的是「能不动世界就不动，但一定要真的落在目标旁边」：
     * <ol>
     * <li>目标附近（±{@value #NEAR_HORIZONTAL_RADIUS}/±{@value #NEAR_VERTICAL_RADIUS}）
     * 就有现成能站的地方 —— 最常见的是矿脉露在洞壁上。直接用，一格方块都不动；</li>
     * <li>附近没有（矿脉整个埋在石头里，这才是挖矿的常态）—— 就地开一小块地方
     * （见 {@link #carveSpot}），人就落在目标旁边，而不是跑到几十格外的某个洞穴里；</li>
     * <li>连开洞都不行（目标在岩浆里、基岩里……）—— 退到远处找现成的位置：
     * 水平 ±{@value #HORIZONTAL_RADIUS}、垂直 ±{@value #VERTICAL_RADIUS}，
     * 头顶几十格外的地表也算。这一步同样一格方块都不动，所以排在
     * 「往外开洞」前面；</li>
     * <li>远处也没有，而附近之所以开不了洞<b>只是因为不许动那几格</b>
     * （人在粗矿脉正中间时就是这样）—— 才把开洞半径放到 ±{@value #WIDE_CARVE_RADIUS}
     * 再试一次。这一圈同样一格矿都不动，代价只是落脚点离目标远了几格。</li>
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

        // 「本来能开洞，只是那几格是矿石/木头/机器，所以没开」—— 只在最后一步失败时才有用，
        // 用来给玩家一个准确的理由（否则只会看到「找不到能站的地方」，然后怀疑是不是坏了）
        boolean refusedForProtection = false;

        if (spot == null && Config.locatorTeleportCarve) {
            CarveResult carved = carveSpot(world, targetX, targetY, targetZ, CARVE_RADIUS);
            spot = carved.spot;
            refusedForProtection = carved.refusedForProtection;
            if (spot != null) result = Result.CARVED;
        }

        if (spot == null) {
            // 倒数第二招：去远处找现成的（比如头顶的地表）。
            // 这一步一格方块都不动，所以排在「往外开洞」前面 —— 能不动世界就不动
            spot = findSafeSpot(world, targetX, targetY, targetZ, HORIZONTAL_RADIUS, VERTICAL_RADIUS);
            result = Result.NATURAL;
        }

        if (spot == null && Config.locatorTeleportCarve && refusedForProtection) {
            // 最后一招：附近能开洞的位置全是矿（人在粗矿脉里就是这种情况）—— 往外再找一圈。
            // 这一圈同样一格矿都不动，代价只是落脚点离目标远了几格：
            // 宁可站远一点，也不拆玩家的矿
            CarveResult carved = carveSpot(world, targetX, targetY, targetZ, WIDE_CARVE_RADIUS);
            spot = carved.spot;
            if (spot != null) {
                result = Result.CARVED;
            } else {
                refusedForProtection = carved.refusedForProtection;
            }
        }

        if (spot == null) return refusedForProtection ? Result.FAILED_PROTECTED : Result.FAILED;

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
     * <p>
     * <b>「不许动矿石」是硬约束</b>（见 {@link #canClear}）：正上方那两格如果本身也是矿
     * （矿脉很厚的时候就是这样），这个位置直接作废，换旁边一格 —— 结果是玩家落在矿脉
     * <em>边上</em>的石头里，目标矿就在眼前。比「顺手把两块矿扬了」好得多。
     *
     * @param radius 只在这个半径内找（先 {@value #CARVE_RADIUS}，实在不行再 {@value #WIDE_CARVE_RADIUS}）
     * @return 开洞结果；开不出来时 {@code spot} 为 null，{@code refusedForProtection}
     *         说明是不是「因为不许动那几格才没开成」
     */
    private static CarveResult carveSpot(World world, int cx, int cy, int cz, int radius) {
        IChunkProvider provider = world.getChunkProvider();
        if (provider == null) return CarveResult.NONE;

        int[] best = null;
        long bestDistance = Long.MAX_VALUE;
        boolean refusedForProtection = false;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                if (!provider.chunkExists(x >> 4, z >> 4)) continue;

                for (int dy = -radius; dy <= radius; dy++) {
                    int y = cy + dy;

                    switch (judgeCarve(world, x, y, z, cx, cy, cz)) {
                        case NO:
                            continue;
                        case PROTECTED:
                            // 位置本身没问题，是我自己不肯挖那几格 —— 记一笔，
                            // 万一最后哪都去不了，聊天栏里能说清楚为什么
                            refusedForProtection = true;
                            continue;
                        default:
                            break;
                    }

                    long distance = (long) dx * dx + (long) dy * dy + (long) dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new int[] { x, y, z };
                    }
                }
            }
        }

        if (best == null) return new CarveResult(null, refusedForProtection);

        // 只清「挡着身体」的那几格。花草、雪层、火把这类本来就不挡路的留着别动 ——
        // 反正人站得进去，没必要顺手把玩家门口的东西删掉
        clearIfBlocking(world, best[0], best[1], best[2]);
        clearIfBlocking(world, best[0], best[1] + 1, best[2]);
        return new CarveResult(best, false);
    }

    /** 清掉这一格；只在它真的挡着身体时才动手。 */
    private static void clearIfBlocking(World world, int x, int y, int z) {
        if (isPassable(world, x, y, z)) return;

        // setBlockToAir 不走「破坏方块」那条路（不会掉东西），
        // 所以这里不会顺手刷出几块矿石
        world.setBlockToAir(x, y, z);
    }

    /**
     * 这一格适不适合「挖出来站人」。
     *
     * <p>
     * 分两关，<b>顺序是有意的</b>：先看几何（脚下站得住吗、边界内吗、会不会灌进液体），
     * 再看「允许不允许动那几格」。这样 {@link Carve#PROTECTED} 的含义才是干净的
     * —— 「几何上完全能开洞，只是那几格是我不肯挖的东西」，而不是「随便哪一格不行」。
     */
    private static Carve judgeCarve(World world, int x, int y, int z, int targetX, int targetY, int targetZ) {
        int height = world.getHeight();
        if (y <= 0 || y + 1 >= height) return Carve.NO;

        // 目标方块本身不能被清掉
        if (x == targetX && z == targetZ && (y == targetY || y + 1 == targetY)) return Carve.NO;

        // 脚下那格必须本来就能站（实心、非液体/火）—— 只清方块、不放方块，也不会让人掉下去
        if (!canStandOn(world, x, y - 1, z)) return Carve.NO;
        // 身体那两格的六个方向有液体/岩浆/火就换一处（清开就是让它灌进来）
        if (liquidAround(world, x, y, z)) return Carve.NO;

        if (!canClear(world, x, y, z) || !canClear(world, x, y + 1, z)) return Carve.PROTECTED;
        return Carve.YES;
    }

    /**
     * 这一格允许被清成空气吗。
     *
     * <p>
     * <b>只认「没用的方块」</b>（{@link #isUseless}），而且<b>矿石一格都不许动</b>
     * （{@link #isOre}）—— 玩家拿魔杖找的就是矿，开洞顺手把矿清了是最不能接受的结果。
     * 除此之外还延续原来的三条硬约束：挖不动的（基岩那类硬度 &lt; 0 的）、
     * 带方块实体的（箱子、机器 —— 清掉会留下一个幽灵方块）、液体和火。
     */
    private static boolean canClear(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (block == null || block.isAir(world, x, y, z)) return true;

        // 液体/火/岩浆本来就不该站在里面
        if (isDangerous(block.getMaterial())) return false;
        // 基岩这类挖不动的：不动
        if (block.getBlockHardness(world, x, y, z) < 0.0F) return false;
        // 带方块实体的（箱子、机器……）：清掉会留下一个幽灵方块，不动
        if (block.hasTileEntity(world.getBlockMetadata(x, y, z))) return false;

        if (isOre(world, x, y, z, block)) return false;
        return isUseless(block);
    }

    /**
     * 这一格是不是矿石。
     *
     * <p>
     * 四道判断，任意一道命中就算（顺序按「便宜 + 覆盖广」排）：
     * <ol>
     * <li><b>GT 矿石</b>：整个 GTNH 的矿都是同一个 {@code GTBlockOre} 方块、靠元数据区分材料，
     * 一个方块 id 代表上千种矿 —— 只能问 GT 自己的 API（{@link GtOreSupport}）；</li>
     * <li>{@code BlockOre}：原版和一部分模组的矿石类。<b>注意红石矿不是这个类</b>
     * （它是 {@code BlockRedstoneOre}），靠后两条兜住；</li>
     * <li><b>矿石词典</b>：名字以 {@code ore} 开头的条目。覆盖面最广的一条，
     * 绝大多数模组的矿都会注册成 {@code oreXxx}（原版红石矿就是 {@code oreRedstone}）；</li>
     * <li>方块自己的名字里有 {@code ore} —— 有些模组的矿既不继承 {@code BlockOre}
     * 也没注册进词典，这条是纯兜底。</li>
     * </ol>
     *
     * <p>
     * 判断失误的两个方向代价不对等：<b>把不是矿的东西当成矿，最多是这处开不了洞，
     * 换个位置或者提示一声；漏掉一块矿，那块矿就永久没了</b>。所以这里宁可多拦。
     *
     * <p>
     * 拿不到 GT 的矿石 API 时第一条退化成 false，后三条照常工作。
     */
    private static boolean isOre(World world, int x, int y, int z, Block block) {
        if (isGtOre(block)) return true;
        if (block instanceof BlockOre) return true;
        if (isOreDictOre(block, world.getBlockMetadata(x, y, z))) return true;
        return isNamedOre(block);
    }

    private static boolean isGtOre(Block block) {
        if (gtOreApi == null) {
            try {
                // 只为把 GtOreSupport 这个类加载起来；类加载失败会在这里抛出来
                GtOreSupport.ping();
                gtOreApi = Boolean.TRUE;
            } catch (Throwable t) {
                gtOreApi = Boolean.FALSE;
                FutaGtnhMod.LOG.warn("拿不到 GT 的矿石 API，「开洞不许动矿石」这一条会退回矿石词典判断" + "（绝大多数矿仍然认得出来，但个别自定义矿石可能漏掉）", t);
            }
        }
        if (!gtOreApi) return false;

        try {
            return GtOreSupport.isOre(block);
        } catch (Throwable t) {
            gtOreApi = Boolean.FALSE;
            return false;
        }
    }

    /**
     * 矿石词典里有没有 {@code oreXxx} 这样的名字。
     *
     * <p>
     * 一次 {@code getOreIDs} 就够：它内部查两张键 —— 一张是「不看元数据」的
     * （对应注册时用 {@code WILDCARD_VALUE} 注册的条目），一张是「物品 + 具体元数据」的，
     * 两种注册方式都覆盖到了。（自己再补一次通配查询反而会算出一个谁都没注册过的键：
     * {@code (WILDCARD_VALUE + 1) << 16} 会整型溢出成负数，而注册那一侧遇到通配时
     * 根本不加这个偏移。）
     *
     * <p>
     * 元数据敏感这一点很关键：GTNH 里同一个方块的不同元数据是<b>不同东西</b>
     * （GT 的矿、木板的树种、石头的石种），所以不能只按方块判断。
     *
     * <p>
     * 查询很便宜：走的是词典自己的索引表（{@code stackToId}），不是遍历整本词典 ——
     * 开洞扫描会问几百次，这个量级是有区别的。
     */
    private static boolean isOreDictOre(Block block, int meta) {
        try {
            for (int id : OreDictionary.getOreIDs(new ItemStack(block, 1, meta))) {
                String name = OreDictionary.getOreName(id);
                // 大小写都认：规范写法是 oreXxx，但模组作者写 OreXxx 的也有
                if (name != null && name.regionMatches(true, 0, "ore", 0, 3)) return true;
            }
        } catch (Throwable t) {
            // 词典是别的模组在填，条目本身可能有毛病；这里只是「顺手多挡一层」，
            // 判断不出来就当它不是矿，绝不能让传送因此崩掉
        }
        return false;
    }

    /** 兜底：方块自己的名字里有 {@code ore}（少数模组的矿两条主路都不走）。 */
    private static boolean isNamedOre(Block block) {
        String name = block.getUnlocalizedName();
        return name != null && name.toLowerCase(Locale.ROOT)
            .contains("ore");
    }

    /**
     * 这一格是不是「没用的方块」—— 挖了不心疼的那些。
     *
     * <p>
     * 名单分两类：<b>天然岩石/泥土</b>（石头、花岗岩、大理石、泥土、沙子、砂砾、黏土、
     * 草方块、地狱岩……它们的材质都落在 {@code rock/ground/sand/grass/clay} 里），
     * 加上<b>本来就不该算障碍的杂物</b>（草、花、藤、蜘蛛网、雪层）。
     *
     * <p>
     * 反过来说，木头、树叶、玻璃、羊毛、金属块、冰、机器外壳…… 都<b>不在</b>名单里。
     * 这份名单是「默认拒绝」的：以后哪个新方块的材质不在名单里，就一律不碰 ——
     * <b>宁可开不了洞，也不要在别人的房子里凿一个洞出来</b>。
     *
     * <p>
     * 代价说清楚：石砖、砖块、石英块这些建筑方块材质同样是 {@code rock}，
     * 所以挡在路上的话仍然会被清掉。想连这个都不允许，只能把
     * {@link Config#locatorTeleportCarve} 关掉。
     */
    private static boolean isUseless(Block block) {
        Material material = block.getMaterial();
        return material == Material.rock || material == Material.ground
            || material == Material.sand
            || material == Material.grass
            || material == Material.clay
            || material == Material.plants
            || material == Material.vine
            || material == Material.snow
            || block == Blocks.web;
    }

    /** {@link #carveSpot} 的结果。 */
    private static final class CarveResult {

        static final CarveResult NONE = new CarveResult(null, false);

        /** 落脚点；没开成就 null */
        final int[] spot;
        /** 有没有「几何上能开洞，只是那几格是矿石/木头/机器」的候选 */
        final boolean refusedForProtection;

        CarveResult(int[] spot, boolean refusedForProtection) {
            this.spot = spot;
            this.refusedForProtection = refusedForProtection;
        }
    }

    /** {@link #judgeCarve} 的三种答案。 */
    private enum Carve {
        /** 开不了：越界 / 脚下不实心 / 会灌进液体 / 要动目标方块本身 */
        NO,
        /** 几何上能开，但那几格里有矿石或别的「不该清掉」的方块 */
        PROTECTED,
        /** 可以开 */
        YES
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
