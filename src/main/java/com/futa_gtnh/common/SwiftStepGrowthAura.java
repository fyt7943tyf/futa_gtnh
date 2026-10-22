package com.futa_gtnh.common;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockGrass;
import net.minecraft.block.BlockTallGrass;
import net.minecraft.block.IGrowable;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.item.ItemSwiftStep;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketSwiftStepAuraStatus;

/** Incremental discovery and fair, bounded growth/breeding work on the server world tick. */
public final class SwiftStepGrowthAura {

    private static final int TICK_INTERVAL = 100;
    private static final String CROPS_NH_CROP_STICK_API = "com.gtnewhorizon.cropsnh.api.ICropStickTile";
    private static final String IC2_CROP_TILE = "ic2.core.crop.TileEntityCrop";
    private static final Map<EntityPlayer, CropScan> SCANS = new WeakHashMap<>();
    private static final Map<World, Integer> PLAYER_CURSORS = new WeakHashMap<>();
    private static final Map<World, List<AuraRequest>> REQUESTS = new WeakHashMap<>();
    private static final Map<Integer, List<int[]>> OFFSETS = new HashMap<>();
    private static Method cropsNhRate;
    private static Method cropsNhCross;
    private static Method cropsNhCanBreed;
    private static Method cropsNhCanCross;
    private static Method cropsNhBreed;
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

    private SwiftStepGrowthAura() {}

    /** Animals retain their existing timing; plant work is gathered at WorldTick END. */
    public static void tick(EntityPlayer player) {
        if (player == null || player.worldObj == null || player.worldObj.isRemote) return;
        ItemStack charm = ItemSwiftStep.findEquipped(player);
        if (charm == null || !ItemSwiftStep.isAnimalAuraEnabled(charm)) return;
        int speed = ItemSwiftStep.getGrowthAuraSpeed(charm);
        int interval = Math.max(1, TICK_INTERVAL / speed);
        if (Math.floorMod(player.ticksExisted, interval) == Math.floorMod(
            player.getUniqueID()
                .hashCode(),
            interval)) {
            tickAnimals(player.worldObj, player, ItemSwiftStep.getGrowthAuraRadius(charm), speed, interval);
        }
    }

    public static void tickWorld(World world) {
        if (world == null || world.isRemote) return;
        List<?> players = new ArrayList<>(world.playerEntities);
        int size = players.size();
        if (size == 0) {
            REQUESTS.remove(world);
            return;
        }
        List<AuraRequest> requests = new ArrayList<>();
        for (Object entry : players) {
            EntityPlayer wearer = (EntityPlayer) entry;
            ItemStack equipped = ItemSwiftStep.findEquipped(wearer);
            if (equipped != null) requests.add(new AuraRequest(wearer, equipped));
        }
        REQUESTS.put(world, requests);
        int start = Math.floorMod(PLAYER_CURSORS.getOrDefault(world, 0), size);
        PLAYER_CURSORS.put(world, (start + 1) % size);
        for (int i = 0; i < size; i++) {
            EntityPlayer player = (EntityPlayer) players.get((start + i) % size);
            ItemStack charm = ItemSwiftStep.findEquipped(player);
            if (charm == null
                || !(ItemSwiftStep.isGrowthAuraEnabled(charm) || ItemSwiftStep.isBreedingAuraEnabled(charm)
                    || ItemSwiftStep.isBotaniaAuraEnabled(charm))) {
                SCANS.remove(player);
                continue;
            }
            CropScan scan = SCANS.computeIfAbsent(player, ignored -> new CropScan());
            scan.stats.targetsReset();
            boolean botaniaFirst = (world.getTotalWorldTime() & 1L) != 0;
            if (botaniaFirst) processBotania(world, player, charm, scan.stats);
            process(world, player, charm, scan);
            if (!botaniaFirst) processBotania(world, player, charm, scan.stats);
            scan.stats.limited |= SwiftStepAuraBudget.get(world).limited;
            if (player instanceof EntityPlayerMP && player.ticksExisted % 20 == 0) {
                NetworkHandler.INSTANCE.sendTo(new PacketSwiftStepAuraStatus(scan.stats), (EntityPlayerMP) player);
                scan.stats.windowReset();
            }
        }
    }

    private static void processBotania(World world, EntityPlayer player, ItemStack charm, SwiftStepAuraStats stats) {
        SwiftStepBotaniaAura.accelerate(
            world,
            player.posX,
            player.posY,
            player.posZ,
            ItemSwiftStep.getGrowthAuraRadius(charm),
            ItemSwiftStep.getBotaniaAuraSpeed(charm),
            ItemSwiftStep.isBotaniaAuraEnabled(charm),
            stats);
    }

    private static void process(World world, EntityPlayer player, ItemStack charm, CropScan scan) {
        if (!ItemSwiftStep.isGrowthAuraEnabled(charm) && !ItemSwiftStep.isBreedingAuraEnabled(charm)) return;
        int radius = ItemSwiftStep.getGrowthAuraRadius(charm);
        int cx = MathHelper.floor_double(player.posX);
        int cy = MathHelper.floor_double(player.posY);
        int cz = MathHelper.floor_double(player.posZ);
        long now = world.getTotalWorldTime();
        if (scan.world.get() != world) scan.candidates.clear();
        if (scan.world.get() != world || scan.radius != radius || scan.x != cx || scan.y != cy || scan.z != cz) {
            scan.world = new WeakReference<>(world);
            scan.radius = radius;
            scan.x = cx;
            scan.y = cy;
            scan.z = cz;
            scan.discoverAt = 0;
            scan.nextDiscovery = now;
        }
        SwiftStepAuraBudget budget = SwiftStepAuraBudget.get(world);
        if (ItemSwiftStep.isGrowthAuraEnabled(charm) || ItemSwiftStep.isBreedingAuraEnabled(charm)) {
            discover(world, scan, now, budget);
        }
        List<Candidate> candidates = new ArrayList<>(scan.candidates.values());
        int size = candidates.size();
        if (size == 0) return;
        int start = Math.floorMod(scan.cursor, size);
        // Every target gets one turn before any target receives its next turn.
        java.util.Set<Long> skipped = new java.util.HashSet<>();
        for (int round = 0; round < 10 && budget.available(); round++) {
            for (int i = 0; i < size && budget.available(); i++) {
                int index = (start + i) % size;
                Candidate target = candidates.get(index);
                if (skipped.contains(target.key)) continue;
                scan.cursor = index + 1;
                int dx = target.x - cx, dy = target.y - cy, dz = target.z - cz;
                if (dx * dx + dy * dy + dz * dz > radius * radius || !world.blockExists(target.x, target.y, target.z)) {
                    scan.candidates.remove(target.key);
                    continue;
                }
                TileEntity tile = target.block == null ? world.getTileEntity(target.x, target.y, target.z) : null;
                boolean cross = isEmptyCross(tile);
                int speed = cross
                    ? (ItemSwiftStep.isBreedingAuraEnabled(charm) ? ItemSwiftStep.getBreedingAuraSpeed(charm) : 0)
                    : (ItemSwiftStep.isGrowthAuraEnabled(charm) ? ItemSwiftStep.getGrowthAuraSpeed(charm) : 0);
                if (speed > 0) speed = highestSpeed(world, target.x, target.y, target.z, cross ? 1 : 0, speed);
                int extra = SwiftStepBotaniaAura.extraTicks(now, speed);
                if (round == 0 && speed > 0) {
                    if (cross) scan.stats.crosses++;
                    else scan.stats.crops++;
                }
                if (extra == 0 || !budget.claim(target.key, extra)) continue;
                boolean worked;
                if (cross) {
                    worked = breed(tile);
                } else if (tile != null) {
                    worked = boostCrop(tile);
                } else {
                    worked = world.getBlock(target.x, target.y, target.z) == target.block
                        && isGrowthBlock(target.block);
                    if (worked && target.block instanceof IGrowable) {
                        worked = ((IGrowable) target.block).func_149851_a(world, target.x, target.y, target.z, false);
                    }
                    if (worked) target.block.updateTick(world, target.x, target.y, target.z, world.rand);
                }
                if (worked) scan.stats.updates++;
                else {
                    scan.stats.blocked++;
                    skipped.add(target.key);
                }
            }
        }
    }

    private static void discover(World world, CropScan scan, long now, SwiftStepAuraBudget budget) {
        if (now < scan.nextDiscovery) return;
        List<int[]> positions = OFFSETS.computeIfAbsent(scan.radius, SwiftStepGrowthAura::offsets);
        int checked = 0;
        while (scan.discoverAt < positions.size() && checked < 512 && budget.scan()) {
            int[] offset = positions.get(scan.discoverAt++);
            checked++;
            inspect(world, scan, scan.x + offset[0], scan.y + offset[1], scan.z + offset[2]);
        }
        if (scan.discoverAt == positions.size()) {
            scan.discoverAt = 0;
            scan.nextDiscovery = now + 10;
        }
    }

    static List<int[]> offsets(int radius) {
        List<int[]> result = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            for (int y = -radius; y <= radius; y++) {
                if (x * x + y * y + z * z <= radius * radius) result.add(new int[] { x, y, z });
            }
        }
        result.sort(Comparator.comparingInt(v -> v[0] * v[0] + v[1] * v[1] + v[2] * v[2]));
        return result;
    }

    private static void inspect(World world, CropScan scan, int x, int y, int z) {
        if (y < 0 || y >= 256 || !world.blockExists(x, y, z)) return;
        Long key = SwiftStepAuraBudget.position(x, y, z);
        Block block = world.getBlock(x, y, z);
        // Lookup once per non-air candidate, with no hasTileEntity assumption for IC2/CropsNH.
        if (block.isAir(world, x, y, z)) {
            scan.candidates.remove(key);
            return;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        if (ensureCropApis() && isCropTile(tile)) {
            scan.candidates.putIfAbsent(key, new Candidate(x, y, z, null));
        } else if (isGrowthBlock(block)) {
            Candidate old = scan.candidates.get(key);
            if (old == null || old.block != block) scan.candidates.put(key, new Candidate(x, y, z, block));
        } else scan.candidates.remove(key);
    }

    /** Block events make newly placed plants visible without waiting for a complete rescan. */
    public static void changed(World world, int x, int y, int z) {
        if (world == null || world.isRemote || !world.blockExists(x, y, z)) return;
        SwiftStepBotaniaAura.changed(world, x, y, z);
        for (CropScan scan : SCANS.values()) {
            if (scan.world.get() != world) continue;
            int dx = scan.x - x, dy = scan.y - y, dz = scan.z - z;
            if (dx * dx + dy * dy + dz * dz <= scan.radius * scan.radius) inspect(world, scan, x, y, z);
        }
    }

    public static void forget(EntityPlayer player) {
        SCANS.remove(player);
    }

    public static void forget(World world) {
        SCANS.values()
            .removeIf(scan -> scan.world.get() == world);
        PLAYER_CURSORS.remove(world);
        REQUESTS.remove(world);
        SwiftStepAuraBudget.forget(world);
        SwiftStepBotaniaAura.forget(world);
    }

    public static void clear() {
        SCANS.clear();
        PLAYER_CURSORS.clear();
        REQUESTS.clear();
        SwiftStepAuraBudget.clear();
        SwiftStepBotaniaAura.clear();
    }

    private static boolean ensureCropApis() {
        if (!cropsNhApiResolved) resolveCropsNhApi();
        if (!ic2ApiResolved) resolveIc2Api();
        return cropsNhCropStickType != null || ic2CropType != null;
    }

    private static boolean isCropTile(TileEntity tile) {
        return tile != null
            && ((!cropsNhIntegrationDisabled && cropsNhCropStickType != null && cropsNhCropStickType.isInstance(tile))
                || (!ic2IntegrationDisabled && ic2CropType != null && ic2CropType.isInstance(tile)));
    }

    private static boolean isEmptyCross(TileEntity tile) {
        if (!ensureCropApis() || tile == null || cropsNhCropStickType == null || !cropsNhCropStickType.isInstance(tile))
            return false;
        try {
            return !((Boolean) cropsNhHasCrop.invoke(tile)) && (Boolean) cropsNhCross.invoke(tile);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Shared with Agricarnation; it never treats an empty cross stick as a bonemealable plant. */
    public static boolean canBoostCrop(TileEntity tile) {
        if (tile == null || tile.isInvalid()
            || tile.getWorldObj() == null
            || tile.getWorldObj().isRemote
            || !ensureCropApis()) return false;
        try {
            if (!cropsNhIntegrationDisabled && cropsNhCropStickType != null && cropsNhCropStickType.isInstance(tile)) {
                return (Boolean) cropsNhHasCrop.invoke(tile) && !((Boolean) cropsNhIsMature.invoke(tile))
                    && !((Boolean) cropsNhIsSick.invoke(tile))
                    && (Boolean) cropsNhGrowthReady.invoke(tile)
                    && ((Number) cropsNhRate.invoke(tile)).intValue() > 0;
            }
            return !ic2IntegrationDisabled && ic2CropType != null
                && ic2CropType.isInstance(tile)
                && ic2GetCrop.invoke(tile) != null
                && ((Number) ic2GrowthRate.invoke(tile)).intValue() > 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static boolean boostCrop(TileEntity tile) {
        if (!canBoostCrop(tile)) return false;
        if (cropsNhCropStickType != null && cropsNhCropStickType.isInstance(tile)) {
            growCropsNh(tile, 1);
            return !cropsNhIntegrationDisabled;
        }
        growIc2Crop(tile, 1);
        return !ic2IntegrationDisabled;
    }

    private static boolean breed(TileEntity tile) {
        if (!isEmptyCross(tile) || cropsNhBreed == null) return false;
        World world = tile.getWorldObj();
        int[][] directions = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } };
        try {
            boolean parent = false;
            for (int[] direction : directions) {
                int x = tile.xCoord + direction[0], z = tile.zCoord + direction[1];
                if (!world.blockExists(x, tile.yCoord, z)) continue;
                TileEntity neighbour = world.getTileEntity(x, tile.yCoord, z);
                if (neighbour != null && cropsNhCropStickType.isInstance(neighbour)
                    && ((Boolean) cropsNhCanBreed.invoke(neighbour) || (Boolean) cropsNhCanCross.invoke(neighbour)))
                    parent = true;
            }
            if (!parent) return false;
            // The original method retains recipe checks, random chances and stat inheritance.
            // Do not invoke the full empty-stick growth tick: that would accelerate weed spawning too.
            cropsNhBreed.invoke(tile);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            FutaGtnhMod.LOG.warn("迅步：独立杂交调用失败，停止额外杂交", e);
            cropsNhBreed = null;
            return false;
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
            if (!((Boolean) cropsNhGrowthReady.invoke(tile)) || ((Number) cropsNhRate.invoke(tile)).intValue() <= 0)
                return;

            for (int tick = 0; tick < growthTicks; tick++) {
                if (!((Boolean) cropsNhGrowthReady.invoke(tile)) || ((Number) cropsNhRate.invoke(tile)).intValue() <= 0)
                    break;
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
                cropsNhRate = cropsNhCropStickType.getMethod("calcGrowthRate");
                cropsNhCross = cropsNhCropStickType.getMethod("isCrossCrop");
                cropsNhCanBreed = cropsNhCropStickType.getMethod("canBreed");
                cropsNhCanCross = cropsNhCropStickType.getMethod("canCross");
                try {
                    cropsNhBreed = Class.forName("com.gtnewhorizon.cropsnh.tileentity.TileEntityCropSticks")
                        .getDeclaredMethod("attemptBreeding");
                    cropsNhBreed.setAccessible(true);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    FutaGtnhMod.LOG.warn("迅步：当前 CropsNH 不提供独立杂交入口，生长加速仍可使用", e);
                }
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

    static int highestSpeed(World world, double x, double y, double z, int channel, int fallback) {
        int result = fallback;
        List<AuraRequest> requests = REQUESTS.get(world);
        if (requests == null) return result;
        for (AuraRequest request : requests) {
            double dx = x - (channel == 2 ? request.x : Math.floor(request.x));
            double dy = y - (channel == 2 ? request.y : Math.floor(request.y));
            double dz = z - (channel == 2 ? request.z : Math.floor(request.z));
            if (dx * dx + dy * dy + dz * dz <= request.radius * request.radius)
                result = Math.max(result, request.speeds[channel]);
        }
        return result;
    }

    private static final class AuraRequest {

        final double x, y, z;
        final int radius;
        final int[] speeds;

        AuraRequest(EntityPlayer player, ItemStack charm) {
            x = player.posX;
            y = player.posY;
            z = player.posZ;
            radius = ItemSwiftStep.getGrowthAuraRadius(charm);
            speeds = new int[] { ItemSwiftStep.isGrowthAuraEnabled(charm) ? ItemSwiftStep.getGrowthAuraSpeed(charm) : 0,
                ItemSwiftStep.isBreedingAuraEnabled(charm) ? ItemSwiftStep.getBreedingAuraSpeed(charm) : 0,
                ItemSwiftStep.isBotaniaAuraEnabled(charm) ? ItemSwiftStep.getBotaniaAuraSpeed(charm) : 0 };
        }
    }

    private static final class CropScan {

        WeakReference<World> world = new WeakReference<>(null);
        int x, y, z, radius, discoverAt, cursor;
        long nextDiscovery;
        final Map<Long, Candidate> candidates = new LinkedHashMap<>();
        final SwiftStepAuraStats stats = new SwiftStepAuraStats();
    }

    private static final class Candidate {

        final int x, y, z;
        final Block block;
        final Long key;

        Candidate(int x, int y, int z, Block block) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
            this.key = SwiftStepAuraBudget.position(x, y, z);
        }
    }
}
