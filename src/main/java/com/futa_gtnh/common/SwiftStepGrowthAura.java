package com.futa_gtnh.common;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.IGrowable;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.item.ItemSwiftStep;

/**
 * 服务端处理迅步的作物与动物生长光环。
 *
 * <p>
 * 作物部分<b>不是</b>每次脉冲把整球扫一遍：扫出来的候选位置会缓存起来，之后只处理
 * 缓存里的那几十格。原来那版是"每个脉冲全扫"，半径 8 的球体就是 2000 多格、每格一次
 * {@code getBlock} 加两次 {@code getTileEntity}；spark 采下来它一个人就占了服务端 tick
 * 的 16%（其中一半花在查方块实体上），而真正的 {@code updateTick} 只有几毫秒。
 * 现在扫描只决定"附近有哪些作物"，成本只跟"多久重扫一次"有关，和光环强度无关。
 */
public final class SwiftStepGrowthAura {

    /** 作物光环每 5 秒触发一次；动物光环根据强度缩短触发间隔。 */
    private static final int TICK_INTERVAL = 100;
    /** CropsNH 的作物杆方块实体（它的生长走自己的刻度，不吃原版随机方块刻度）。 */
    private static final String CROPS_NH_CROP_STICK_API = "com.gtnewhorizon.cropsnh.api.ICropStickTile";
    /** IC2 的作物方块实体（同样是自己在长）。 */
    private static final String IC2_CROP_TILE = "ic2.core.crop.TileEntityCrop";

    /**
     * 扫描缓存的有效期（tick）。
     *
     * <p>
     * 它决定的是"新种下的作物最晚多久能被扫到"，和光环强度没有关系：强度只影响多久
     * 一次脉冲、每次脉冲给几格生长刻度。跑满 5 秒才重扫一次已经比原来的"每脉冲全扫"
     * 便宜两个数量级，再短就没有意义了。
     */
    private static final int SCAN_REFRESH_TICKS = 100;
    /**
     * 两次扫描之间最少隔多少 tick。
     *
     * <p>
     * 玩家高速飞行时每 tick 都会跨出扫描半径，如果照着位移立刻重扫，就退化成"每 tick
     * 全扫"了。下限把重扫频率压到 10 tick 一次，玩家走远时也就多花这些。
     */
    private static final int SCAN_MIN_INTERVAL_TICKS = 10;
    /** 玩家离开上次扫描中心超过这个距离（格）就提前重扫，否则光环跟不上人。 */
    private static final double SCAN_MOVE_DISTANCE_SQUARED = 2.0D * 2.0D;
    /**
     * 不带方块类型门槛的全量扫描间隔（tick）。
     *
     * <p>
     * 常规扫描只对"已知会挂作物方块实体的方块类型"查 {@code getTileEntity}，否则整球
     * 每格都查一遍。IC2 / CropsNH 的方块都不重写 {@code hasTileEntity}（见下面
     * {@code mayHostCropTile} 的注释），所以第一次遇到之前类型是未知的 —— 每隔这么久
     * 放开门槛扫一次，把新类型学进来。一分钟的发现延迟对"新装了一个作物模组"足够了。
     */
    private static final int FULL_SCAN_INTERVAL_TICKS = 1200;

    private static boolean cropsNhApiResolved;
    private static Class<?> cropsNhCropStickType;
    private static Method cropsNhHasCrop;
    private static Method cropsNhIsMature;
    private static Method cropsNhIsSick;
    private static Method cropsNhGrowthReady;
    private static Method cropsNhGrowthTick;
    private static boolean cropsNhIntegrationDisabled;

    private static boolean ic2ApiResolved;
    private static Class<?> ic2CropType;
    private static Method ic2Tick;
    private static Method ic2GrowthRate;
    private static Method ic2GetCrop;
    /** {@code TileEntityCrop.ticker}：它的生长节流计时器（tickRate 一般 256）。 */
    private static Field ic2Ticker;
    private static boolean ic2IntegrationDisabled;

    /**
     * 已知会挂作物方块实体的方块类型，由全量扫描学到。
     *
     * <p>
     * 学的是<b>方块类</b>而不是坐标：IC2 的作物方块和 CropsNH 的作物杆在所有存档里都是
     * 同一个类，学到一次就够所有玩家、所有世界用。
     */
    private static final Set<Class<?>> CROP_TILE_BLOCK_TYPES = new HashSet<>();

    /** 每个玩家上一次的扫描结果；WeakHashMap 让玩家下线后自动回收。 */
    private static final Map<EntityPlayer, CropScan> SCANS = new WeakHashMap<>();

    // CROP_TILE_BLOCK_TYPES / SCANS 只在服务端 tick 线程访问（tick() 在 client 直接返回）。

    private SwiftStepGrowthAura() {}

    public static void tick(EntityPlayer player) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) return;

        ItemStack charm = ItemSwiftStep.findEquipped(player);
        if (charm == null) return;

        SwiftStepBotaniaAura.tick(player, charm);

        boolean cropAuraEnabled = ItemSwiftStep.isGrowthAuraEnabled(charm);
        boolean animalAuraEnabled = ItemSwiftStep.isAnimalAuraEnabled(charm);
        if (!cropAuraEnabled && !animalAuraEnabled) return;

        World world = player.worldObj;
        int radius = ItemSwiftStep.getGrowthAuraRadius(charm);
        int growthSpeed = ItemSwiftStep.getGrowthAuraSpeed(charm);
        int playerTickOffset = player.getUniqueID()
            .hashCode();

        if (cropAuraEnabled) {
            int cropTicks = scaledTicksThisTick(player.ticksExisted, growthSpeed, TICK_INTERVAL, playerTickOffset);
            if (cropTicks > 0) {
                // 把每 5 秒的一批更新均匀摊到各个 tick，倍率高时避免集中卡顿。
                tickCrops(world, player, radius, cropTicks);
            }
        }

        if (animalAuraEnabled) {
            int animalInterval = Math.max(1, TICK_INTERVAL / growthSpeed);
            if (isPulse(player.ticksExisted, animalInterval, playerTickOffset)) {
                tickAnimals(world, player, radius, growthSpeed, animalInterval);
            }
        }
    }

    private static boolean isPulse(int playerTicks, int interval, int playerTickOffset) {
        return Math.floorMod(playerTicks, interval) == Math.floorMod(playerTickOffset, interval);
    }

    private static int scaledTicksThisTick(int playerTicks, int speed, int baseInterval, int tickOffset) {
        long current = ((long) playerTicks + tickOffset) * speed;
        long previous = ((long) playerTicks - 1L + tickOffset) * speed;
        return (int) (Math.floorDiv(current, baseInterval) - Math.floorDiv(previous, baseInterval));
    }

    /**
     * 作物光环的一次脉冲：必要时重扫候选位置，然后只催缓存里的那些格。
     *
     * <p>
     * 重扫的三种触发条件见 {@link #needsRescan}；脉冲本身是每 5 秒（或更密，看强度）一次，
     * 所以正常情况下每一轮脉冲都会在重扫后立刻生长，行为跟原来"脉冲时现场扫一遍"一致。
     */
    private static void tickCrops(World world, EntityPlayer player, int radius, int growthTicks) {
        CropScan scan = SCANS.get(player);
        if (scan == null || needsRescan(scan, world, player, radius)) {
            scan = rescan(world, player, radius, player.ticksExisted, scan);
            SCANS.put(player, scan);
        }
        growCrops(world, scan, growthTicks);
    }

    /**
     * @param scan 上一次的扫描结果
     * @return true 表示该重扫了
     */
    private static boolean needsRescan(CropScan scan, World world, EntityPlayer player, int radius) {
        if (scan.world != world || scan.radius != radius) return true;

        int age = player.ticksExisted - scan.scannedAt;
        // age < 0：玩家实体被复用（换了维度 / 重登），tick 计数比扫描时还小，当作过期。
        if (age < 0 || age >= SCAN_REFRESH_TICKS) return true;
        return age >= SCAN_MIN_INTERVAL_TICKS && scan.movedAway(player);
    }

    /**
     * 扫一遍球体，把"能长"的格记进候选表。
     *
     * <p>
     * 每列只取一次 {@link Chunk}（同一列的所有 y 共用），用 {@code chunk.getBlock} 直读，
     * 省掉每格一次 {@code getChunkFromBlockCoords} 和 {@code World.getBlock} 上的那串
     * mixin。查询前仍然先 {@code blockExists} 挡一下：区块没加载时 {@code getBlock} 会
     * <b>同步加载</b>区块，光环不该有这种副作用。
     *
     * @param previous 上一次的扫描结果，用来决定这次要不要放开方块类型门槛
     */
    private static CropScan rescan(World world, EntityPlayer player, int radius, int now, CropScan previous) {
        // 平时只查学到过的作物方块类型；隔一段时间放开一次，把新类型学进来。
        boolean full = previous == null || now < previous.fullScannedAt
            || now - previous.fullScannedAt >= FULL_SCAN_INTERVAL_TICKS;
        CropScan scan = new CropScan(
            world,
            radius,
            now,
            full ? now : previous.fullScannedAt,
            player.posX,
            player.posY,
            player.posZ);

        // 两边集成都没装的话，这一格根本不会有我们认识的作物方块实体，直接不查。
        boolean tileLookup = ensureCropApis();

        int radiusSquared = radius * radius;
        int centerX = MathHelper.floor_double(player.posX);
        int centerY = MathHelper.floor_double(player.posY);
        int centerZ = MathHelper.floor_double(player.posZ);

        IChunkProvider provider = world.getChunkProvider();
        if (provider == null) return scan;

        Chunk chunk = null;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;

        for (int dx = -radius; dx <= radius; dx++) {
            int dxSquared = dx * dx;
            if (dxSquared > radiusSquared) continue;

            int x = centerX + dx;
            int columnChunkX = x >> 4;

            for (int dz = -radius; dz <= radius; dz++) {
                int remaining = radiusSquared - dxSquared - dz * dz;
                if (remaining < 0) continue;

                int z = centerZ + dz;
                if (!world.blockExists(x, 0, z)) continue;

                int columnChunkZ = z >> 4;
                if (chunk == null || columnChunkX != chunkX || columnChunkZ != chunkZ) {
                    chunk = provider.provideChunk(columnChunkX, columnChunkZ);
                    chunkX = columnChunkX;
                    chunkZ = columnChunkZ;
                    if (chunk == null) continue;
                }

                int localX = x & 15;
                int localZ = z & 15;
                int verticalRadius = (int) Math.sqrt(remaining);

                for (int dy = -verticalRadius; dy <= verticalRadius; dy++) {
                    int y = centerY + dy;
                    if (y < 0 || y >= 256) continue;

                    Block block = chunk.getBlock(localX, y, localZ);
                    if (block.isAir(world, x, y, z)) continue;

                    if (tileLookup && mayHostCropTile(block, full)) {
                        // 每格只查一次方块实体，查到了再分给 CropsNH / IC2 判断。
                        // 原来这里 CropsNH 和 IC2 各查一次，白花一倍。
                        TileEntity tile = world.getTileEntity(x, y, z);
                        if (tile != null && isCropTile(tile)) {
                            scan.add(x, y, z, null);
                            CROP_TILE_BLOCK_TYPES.add(block.getClass());
                            // 归方块实体管的格子不再走原版随机刻度（原来也是这个语义）。
                            continue;
                        }
                    }

                    if (isGrowthBlock(block)) {
                        scan.add(x, y, z, block);
                    }
                }
            }
        }
        return scan;
    }

    /**
     * 这个方块值不值得查一次 {@code getTileEntity}。
     *
     * <p>
     * 常规扫描只认已经学到过的作物方块类型 —— 光这一条就砍掉了绝大部分查询：普通地形、
     * 石头、GT 机器都不再逐格查方块实体。代价是"还没见过"的类型会被漏掉，所以每隔
     * {@link #FULL_SCAN_INTERVAL_TICKS} 要放开一次门槛（{@code full}）。
     *
     * <p>
     * 门槛不能改成 {@code instanceof BlockContainer} 或者 {@code hasTileEntity}：
     * IC2 的 {@code BlockCrop} 继承它自己的 {@code BlockBase}（直接 {@code extends Block}），
     * CropsNH 的作物杆同理，两者 {@code hasTileEntity} 都返回 false —— 那正是原来那版
     * 不敢按类型过滤、只能逐格硬查的原因。所以"哪些方块会挂作物"只能靠实测学，不能靠猜。
     *
     * @param full true 表示这次不设门槛，任何方块都查（用来发现新类型）
     */
    private static boolean mayHostCropTile(Block block, boolean full) {
        return full || CROP_TILE_BLOCK_TYPES.contains(block.getClass());
    }

    private static boolean isCropTile(TileEntity tile) {
        if (!cropsNhIntegrationDisabled && cropsNhCropStickType != null && cropsNhCropStickType.isInstance(tile)) {
            return true;
        }
        return !ic2IntegrationDisabled && ic2CropType != null && ic2CropType.isInstance(tile);
    }

    /** 只处理缓存里的候选格：先校验方块没被换掉，再按原来的语义催生长。 */
    private static void growCrops(World world, CropScan scan, int growthTicks) {
        List<Candidate> candidates = scan.candidates;
        for (int i = 0, size = candidates.size(); i < size; i++) {
            Candidate candidate = candidates.get(i);
            // 区块被卸载（走远了 / 传送）时不要碰：world.getBlock 会把它同步加载回来。
            if (!world.blockExists(candidate.x, 0, candidate.z)) continue;

            if (candidate.block == null) {
                growCropTile(world, candidate, growthTicks);
            } else {
                growBlock(world, candidate, growthTicks);
            }
        }
    }

    private static void growBlock(World world, Candidate candidate, int growthTicks) {
        Block block = candidate.block;
        for (int tick = 0; tick < growthTicks; tick++) {
            // 原版作物可能长成别的方块（比如小麦长高、草丛消失），换了就不再动它。
            if (world.getBlock(candidate.x, candidate.y, candidate.z) != block) return;
            block.updateTick(world, candidate.x, candidate.y, candidate.z, world.rand);
        }
    }

    private static void growCropTile(World world, Candidate candidate, int growthTicks) {
        TileEntity tile = world.getTileEntity(candidate.x, candidate.y, candidate.z);
        if (tile == null) return;

        if (!cropsNhIntegrationDisabled && cropsNhCropStickType != null && cropsNhCropStickType.isInstance(tile)) {
            growCropsNh(tile, growthTicks);
        } else if (!ic2IntegrationDisabled && ic2CropType != null && ic2CropType.isInstance(tile)) {
            growIc2Crop(tile, growthTicks);
        }
    }

    /** CropsNH 的作物长在作物杆的方块实体上，不吃原版随机方块刻度。 */
    private static void growCropsNh(TileEntity tile, int growthTicks) {
        try {
            if (!((Boolean) cropsNhHasCrop.invoke(tile)) || (Boolean) cropsNhIsMature.invoke(tile)
                || (Boolean) cropsNhIsSick.invoke(tile)) {
                return;
            }

            // 光 / 水 / 土壤不满足时，CropsNH 的生长刻度会让作物<b>生病</b>（它的
            // calcGrowthRate() 会返回负数）。加速光环不该承担「催出病害」的后果，
            // 所以条件不满足就跳过这一格。
            if (!((Boolean) cropsNhGrowthReady.invoke(tile))) return;

            for (int tick = 0; tick < growthTicks; tick++) {
                cropsNhGrowthTick.invoke(tile);
                if (!((Boolean) cropsNhHasCrop.invoke(tile)) || (Boolean) cropsNhIsMature.invoke(tile)
                    || (Boolean) cropsNhIsSick.invoke(tile)) {
                    break;
                }
            }
        } catch (IllegalAccessException | InvocationTargetException | ClassCastException e) {
            cropsNhIntegrationDisabled = true;
            FutaGtnhMod.LOG.warn("迅步：CropsNH 作物加速调用失败，已停用本次运行的联动", e);
        }
    }

    /**
     * IC2 的作物：{@code ic2.core.crop.TileEntityCrop.tick()} 就是它的生长刻度。
     *
     * <p>
     * 和 CropsNH 那条路一样，先把「这一下到底会不会长」问清楚再动手：
     * {@code calcGrowthRate()} 在光 / 养分 / 水不够时返回 0，这时候硬催只会让作物长杂草，
     * 不是玩家想要的「加速生长」。
     *
     * <p>
     * 它的 {@code ticker} 字段是个节流计时器（tickRate 一般 256），不清掉的话
     * N 次调用里只有一两次真的做生长判定 —— 正是「开了光环却几乎没效果」的来源。
     * 每 tick 前把它清零，一次调用就等于一次完整生长判定。
     */
    private static void growIc2Crop(TileEntity tile, int growthTicks) {
        try {
            if (ic2GetCrop.invoke(tile) == null) return;
            if (((Number) ic2GrowthRate.invoke(tile)).intValue() <= 0) return;

            for (int tick = 0; tick < growthTicks; tick++) {
                ic2Ticker.setChar(tile, (char) 0);
                ic2Tick.invoke(tile);
                if (ic2GetCrop.invoke(tile) == null) break;
            }
        } catch (IllegalAccessException | InvocationTargetException | ClassCastException e) {
            ic2IntegrationDisabled = true;
            FutaGtnhMod.LOG.warn("迅步：IC2 作物加速调用失败，已停用本次运行的联动", e);
        }
    }

    /** 两个集成都解析一次；都没有就没必要逐格查方块实体。 */
    private static boolean ensureCropApis() {
        if (!cropsNhApiResolved) resolveCropsNhApi();
        if (!ic2ApiResolved) resolveIc2Api();
        return cropsNhCropStickType != null || ic2CropType != null;
    }

    private static void resolveCropsNhApi() {
        if (cropsNhApiResolved) return;
        synchronized (SwiftStepGrowthAura.class) {
            if (cropsNhApiResolved) return;
            try {
                cropsNhCropStickType = Class
                    .forName(CROPS_NH_CROP_STICK_API, false, SwiftStepGrowthAura.class.getClassLoader());
                cropsNhHasCrop = cropsNhCropStickType.getMethod("hasCrop");
                cropsNhIsMature = cropsNhCropStickType.getMethod("isMature");
                cropsNhIsSick = cropsNhCropStickType.getMethod("isSick");
                cropsNhGrowthReady = cropsNhCropStickType.getMethod("areGrowthRequirementsMet");
                cropsNhGrowthTick = cropsNhCropStickType.getMethod("onGrowthTick");
                FutaGtnhMod.LOG.info("迅步：已接管 CropsNH 作物加速（ICropStickTile.onGrowthTick）");
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
                cropsNhCropStickType = null;
                // 缺席是常态（没装 CropsNH），但也可能是 API 改名了 —— 两种都得留一行，
                // 否则「光环对这个模组的作物无效」永远查不出原因
                FutaGtnhMod.LOG.info("迅步：没有可用的 CropsNH 作物接口（{}）", e.toString());
            } finally {
                cropsNhApiResolved = true;
            }
        }
    }

    private static void resolveIc2Api() {
        if (ic2ApiResolved) return;
        synchronized (SwiftStepGrowthAura.class) {
            if (ic2ApiResolved) return;
            try {
                ic2CropType = Class.forName(IC2_CROP_TILE, false, SwiftStepGrowthAura.class.getClassLoader());
                ic2Tick = ic2CropType.getMethod("tick");
                ic2GrowthRate = ic2CropType.getMethod("calcGrowthRate");
                ic2GetCrop = ic2CropType.getMethod("getCrop");
                ic2Ticker = ic2CropType.getField("ticker");
                FutaGtnhMod.LOG.info("迅步：已接管 IC2 作物加速（TileEntityCrop.tick）");
            } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException | LinkageError e) {
                ic2CropType = null;
                FutaGtnhMod.LOG.info("迅步：没有可用的 IC2 作物接口（{}）", e.toString());
            } finally {
                ic2ApiResolved = true;
            }
        }
    }

    private static void tickAnimals(World world, EntityPlayer player, int radius, int growthSpeed, int interval) {
        AxisAlignedBB bounds = AxisAlignedBB.getBoundingBox(
            player.posX - radius,
            player.posY - radius,
            player.posZ - radius,
            player.posX + radius + 1.0D,
            player.posY + radius + 1.0D,
            player.posZ + radius + 1.0D);
        List<EntityAnimal> animals = world.getEntitiesWithinAABB(EntityAnimal.class, bounds);
        // 设定的强度表示额外增加的年龄进度：×1 时额外跳过一个自然计时周期，
        // 所以最低档也有约 2 倍速度；更高档继续叠加，避免开启光环但默认值完全没效果。
        int ageBoost = interval * growthSpeed;
        double radiusSquared = (double) radius * radius;

        for (EntityAnimal animal : animals) {
            double dx = animal.posX - player.posX;
            double dy = animal.posY - player.posY;
            double dz = animal.posZ - player.posZ;
            if (dx * dx + dy * dy + dz * dz > radiusSquared) continue;

            // 生崽后的正数年龄是繁殖冷却，幼年动物的负数年龄是成年倒计时；
            // 两者都是 EntityAgeable 的 growingAge，服务端写回后会同步到客户端。
            int age = animal.getGrowingAge();
            if (age < 0) {
                animal.setGrowingAge(Math.min(0, age + ageBoost));
            } else if (age > 0) {
                animal.setGrowingAge(Math.max(0, age - ageBoost));
            }

            // 羊毛没有独立的再生计时器，原版在吃草时恢复；光环按强度定期令剪毛羊恢复羊毛。
            if (animal instanceof EntitySheep) {
                EntitySheep sheep = (EntitySheep) animal;
                if (sheep.getSheared()) sheep.setSheared(false);
            }
        }
    }

    private static boolean isGrowthBlock(Block block) {
        if (block == null || !block.getTickRandomly()) return false;
        // 这些方块也实现了 IGrowable，但随机刻度用于蔓延/外观变化，不属于农作物生长。
        if (block instanceof BlockGrass || block instanceof BlockTallGrass || block instanceof BlockDoublePlant) {
            return false;
        }
        return block instanceof IGrowable || block == Blocks.reeds
            || block == Blocks.cactus
            || block == Blocks.nether_wart;
    }

    /** 一次扫描的结果：候选格 + 判定缓存是否还新鲜所需的上下文。 */
    private static final class CropScan {

        final World world;
        /** 扫描时的光环半径，半径改了就得重扫（缓存是按球心+半径算出来的）。 */
        final int radius;
        /** 扫描时的 {@code player.ticksExisted}。 */
        final int scannedAt;
        /** 上一次不设方块类型门槛的全量扫描时刻。 */
        final int fullScannedAt;
        final double centerX;
        final double centerY;
        final double centerZ;
        final List<Candidate> candidates = new ArrayList<>();

        CropScan(World world, int radius, int scannedAt, int fullScannedAt, double centerX, double centerY,
            double centerZ) {
            this.world = world;
            this.radius = radius;
            this.scannedAt = scannedAt;
            this.fullScannedAt = fullScannedAt;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
        }

        void add(int x, int y, int z, Block block) {
            candidates.add(new Candidate(x, y, z, block));
        }

        boolean movedAway(EntityPlayer player) {
            double dx = player.posX - centerX;
            double dy = player.posY - centerY;
            double dz = player.posZ - centerZ;
            return dx * dx + dy * dy + dz * dz > SCAN_MOVE_DISTANCE_SQUARED;
        }
    }

    /**
     * 一个候选格。
     *
     * <p>
     * {@code block} 为 null 表示这格是方块实体作物（IC2 / CropsNH），否则就是走原版
     * 随机刻度的作物，{@code block} 是它的方块类型，用来确认这格没被换掉。
     */
    private static final class Candidate {

        final int x;
        final int y;
        final int z;
        final Block block;

        Candidate(int x, int y, int z, Block block) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
        }
    }
}
