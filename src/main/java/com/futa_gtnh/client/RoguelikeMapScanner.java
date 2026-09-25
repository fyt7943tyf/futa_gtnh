package com.futa_gtnh.client;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.futa_gtnh.Config;

/**
 * 按 Roguelike Dungeons 的来源方块扫描已加载区块。
 *
 * <p>
 * 扫描器不把空气、碰撞箱边缘或可通行区域当作边界。每个二维格只有在对应层的
 * 主题墙体、地板、楼梯、柱子或生成器直接使用的装饰方块出现时才会进入地图。
 * 这样即使房间被矿洞、其他模组结构或玩家挖掘破坏，也不会把外部地形误并入地牢。
 * </p>
 *
 * <p>
 * 方块读取必须留在 Minecraft 主线程，因此这里不使用后台线程，而是把扫描拆成
 * 每个客户端 tick 的小批次，并按楼层和方块柱缓存已经读取过的结果。
 * </p>
 */
public final class RoguelikeMapScanner {

    /** 每个客户端 tick 最多读取的方块柱数量。 */
    private static final int COLUMNS_PER_TICK = 256;
    /** 非当前楼层的再次尝试间隔。 */
    private static final int OTHER_SCAN_INTERVAL = 100;
    /** 非当前楼层每次读取的半径。 */
    private static final int OTHER_SCAN_RADIUS = 48;
    /** 未加载区块的再次尝试间隔。 */
    private static final int UNLOADED_RETRY_INTERVAL = 40;

    private final Map<Long, ColumnEvidence>[] evidenceByLevel;
    private final Set<Long>[] scannedColumnsByLevel;

    private int tick;
    private int lastDimension = Integer.MIN_VALUE;
    private int lastLevel = Integer.MIN_VALUE;
    private int lastPlayerX = Integer.MIN_VALUE;
    private int lastPlayerZ = Integer.MIN_VALUE;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int nextOtherLevel;
    private int nextOtherScanTick;
    private int nextCurrentRetryTick = Integer.MAX_VALUE;
    private ScanTask currentTask;

    @SuppressWarnings("unchecked")
    public RoguelikeMapScanner() {
        evidenceByLevel = new Map[RoguelikeMapSource.LEVEL_COUNT];
        scannedColumnsByLevel = new Set[RoguelikeMapSource.LEVEL_COUNT];
        for (int level = 0; level < RoguelikeMapSource.LEVEL_COUNT; level++) {
            evidenceByLevel[level] = new HashMap<Long, ColumnEvidence>();
            scannedColumnsByLevel[level] = new HashSet<Long>();
        }
    }

    public void tick(RoguelikeMapState state, World world, EntityPlayer player) {
        if (world == null || player == null) return;

        int playerX = floor(player.posX);
        int playerZ = floor(player.posZ);
        if (!state.isInitialized() || state.getDimension() != world.provider.dimensionId) {
            state.reset(world.provider.dimensionId, playerX, playerZ);
            clear();
        }

        int currentLevel = RoguelikeMapSource.levelForY(floor(player.posY));
        state.setCurrentLevel(currentLevel);

        if (playerX != lastPlayerX || playerZ != lastPlayerZ || currentLevel != lastLevel) {
            state.markVisited(currentLevel, playerX, playerZ);
            lastPlayerX = playerX;
            lastPlayerZ = playerZ;
        }

        tick++;
        int playerChunkX = playerX >> 4;
        int playerChunkZ = playerZ >> 4;
        boolean contextChanged = world.provider.dimensionId != lastDimension || currentLevel != lastLevel
            || playerChunkX != lastPlayerChunkX
            || playerChunkZ != lastPlayerChunkZ;

        if (contextChanged) {
            // 玩家跨区块或楼层时先提交旧任务，避免已读取的来源方块因任务被替换而丢失。
            if (currentTask != null) {
                finishTask(state, currentTask);
            }
            lastDimension = world.provider.dimensionId;
            lastLevel = currentLevel;
            lastPlayerChunkX = playerChunkX;
            lastPlayerChunkZ = playerChunkZ;
            currentTask = new ScanTask(currentLevel, playerX, playerZ, Config.roguelikeMapScanRadius, true);
            nextCurrentRetryTick = Integer.MAX_VALUE;
            nextOtherLevel = (currentLevel + 1) % RoguelikeMapSource.LEVEL_COUNT;
            nextOtherScanTick = tick + OTHER_SCAN_INTERVAL;
        }

        if (currentTask == null && tick >= nextCurrentRetryTick) {
            currentTask = new ScanTask(currentLevel, playerX, playerZ, Config.roguelikeMapScanRadius, true);
            nextCurrentRetryTick = Integer.MAX_VALUE;
        }

        if (currentTask == null && tick >= nextOtherScanTick) {
            int otherLevel = nextOtherLevel;
            nextOtherLevel = (nextOtherLevel + 1) % RoguelikeMapSource.LEVEL_COUNT;
            if (otherLevel == currentLevel) {
                otherLevel = nextOtherLevel;
                nextOtherLevel = (nextOtherLevel + 1) % RoguelikeMapSource.LEVEL_COUNT;
            }
            currentTask = new ScanTask(otherLevel, playerX, playerZ, OTHER_SCAN_RADIUS, false);
            nextOtherScanTick = tick + OTHER_SCAN_INTERVAL;
        }

        if (currentTask != null && currentTask.process(world, COLUMNS_PER_TICK)) {
            ScanTask completedTask = currentTask;
            currentTask = null;
            finishTask(state, completedTask);
            if (completedTask.currentLevelScan && completedTask.hadUnloadedChunks) {
                nextCurrentRetryTick = tick + UNLOADED_RETRY_INTERVAL;
            }
        }
    }

    /** 清空当前世界的扫描缓存，并让下一次 tick 重新建立上下文。 */
    public void clear() {
        tick = 0;
        lastDimension = Integer.MIN_VALUE;
        lastLevel = Integer.MIN_VALUE;
        lastPlayerX = Integer.MIN_VALUE;
        lastPlayerZ = Integer.MIN_VALUE;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        nextOtherLevel = 0;
        nextOtherScanTick = 0;
        nextCurrentRetryTick = Integer.MAX_VALUE;
        currentTask = null;
        for (int level = 0; level < RoguelikeMapSource.LEVEL_COUNT; level++) {
            evidenceByLevel[level].clear();
            scannedColumnsByLevel[level].clear();
        }
    }

    private void finishTask(RoguelikeMapState state, ScanTask task) {
        Map<Long, ColumnEvidence> evidence = evidenceByLevel[task.level];
        for (ColumnEvidence changed : task.changedEvidence) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    ColumnEvidence candidate = evidence.get(pack(changed.x + dx, changed.z + dz));
                    if (candidate != null && hasNeighbor(evidence, candidate.x, candidate.z)) {
                        state.merge(task.level, candidate.x, candidate.z, candidate.flags, candidate.score);
                    }
                }
            }
        }

        for (RoguelikeMapState.Marker marker : task.markers) {
            if (hasLocalEvidence(evidence, marker.x, marker.z, 5)) {
                state.addMarker(task.level, marker);
            }
        }
    }

    private static boolean hasNeighbor(Map<Long, ColumnEvidence> evidence, int x, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                ColumnEvidence nearby = evidence.get(pack(x + dx, z + dz));
                if (nearby != null && nearby.score >= 4) return true;
            }
        }
        return false;
    }

    private static boolean hasLocalEvidence(Map<Long, ColumnEvidence> evidence, int x, int z, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ColumnEvidence nearby = evidence.get(pack(x + dx, z + dz));
                if (nearby != null && nearby.score >= 4) return true;
            }
        }
        return false;
    }

    private ColumnEvidence scanColumn(World world, int floorY, int x, int z, List<RoguelikeMapState.Marker> markers) {
        int score = 0;
        byte flags = 0;

        int minY = Math.max(0, floorY - 3);
        int maxY = Math.min(255, floorY + 8);
        for (int y = minY; y <= maxY; y++) {
            Block block = world.getBlock(x, y, z);

            if (block == Blocks.chest || block == Blocks.trapped_chest) {
                String kind = block == Blocks.trapped_chest ? "陷阱战利品箱" : "战利品箱";
                markers.add(new RoguelikeMapState.Marker(x, y, z, kind, kind));
                continue;
            }
            if (block == Blocks.mob_spawner) {
                String mob = readSpawnerMob(world, x, y, z);
                markers.add(new RoguelikeMapState.Marker(x, y, z, "刷怪箱", mob));
                continue;
            }

            int metadata = block == Blocks.stone ? world.getBlockMetadata(x, y, z) : 0;
            RoguelikeMapSource.Classification classification = RoguelikeMapSource.classify(block, metadata);
            if (classification.weight <= 0) continue;

            score += classification.weight;
            if ((classification.roles & RoguelikeMapSource.ROLE_STAIR) != 0) flags |= RoguelikeMapState.CELL_STAIR;
            if ((classification.roles & RoguelikeMapSource.ROLE_PILLAR) != 0) flags |= RoguelikeMapState.CELL_PILLAR;
            if ((classification.roles & RoguelikeMapSource.ROLE_DECORATION) != 0) {
                flags |= RoguelikeMapState.CELL_DECORATION;
            }

            // Roguelike 的房间壳和隧道地板都以层基准为参照：地板通常在 y-1，
            // 其它来源方块是墙、柱或房间装饰。这里仅根据来源高度分类，不读取可通行性。
            if (y == floorY - 1) {
                flags |= RoguelikeMapState.CELL_FLOOR;
            } else if ((classification.roles & RoguelikeMapSource.ROLE_DECORATION) == 0 || y >= floorY) {
                flags |= RoguelikeMapState.CELL_WALL;
            }
        }

        // 低权重的装饰块单独出现时不构成边界；至少需要一个中等强度来源证据。
        return score >= 4 ? new ColumnEvidence(x, z, score, flags) : null;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static String readSpawnerMob(World world, int x, int y, int z) {
        try {
            TileEntity tileEntity = world.getTileEntity(x, y, z);
            if (tileEntity == null) return "未知生物";

            Object logic = invokeNoArg(tileEntity, "func_145881_a");
            if (logic == null) logic = invokeNoArg(tileEntity, "getSpawnerLogic");
            if (logic == null) return "未知生物";

            Object name = invokeNoArg(logic, "getEntityNameToSpawn");
            if (name == null) name = invokeNoArg(logic, "func_98281_h");
            if (name == null) return "未知生物";
            return localizeMob(String.valueOf(name));
        } catch (Throwable ignored) {
            return "未知生物";
        }
    }

    private static Object invokeNoArg(Object object, String methodName) {
        try {
            Method method = object.getClass()
                .getMethod(methodName);
            return method.invoke(object);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String localizeMob(String name) {
        if ("Zombie".equalsIgnoreCase(name)) return "僵尸";
        if ("Skeleton".equalsIgnoreCase(name)) return "骷髅";
        if ("Spider".equalsIgnoreCase(name)) return "蜘蛛";
        if ("CaveSpider".equalsIgnoreCase(name)) return "洞穴蜘蛛";
        if ("Enderman".equalsIgnoreCase(name)) return "末影人";
        if ("Blaze".equalsIgnoreCase(name)) return "烈焰人";
        if ("Silverfish".equalsIgnoreCase(name)) return "蠹虫";
        if ("Witch".equalsIgnoreCase(name)) return "女巫";
        if ("PigZombie".equalsIgnoreCase(name)) return "僵尸猪人";
        return name;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private final class ScanTask {

        private final int level;
        private final boolean currentLevelScan;
        private final List<ChunkArea> areas = new ArrayList<ChunkArea>();
        private final List<ColumnEvidence> changedEvidence = new ArrayList<ColumnEvidence>();
        private final List<RoguelikeMapState.Marker> markers = new ArrayList<RoguelikeMapState.Marker>();
        private int nextArea;
        private boolean hadUnloadedChunks;

        private ScanTask(int level, int centerX, int centerZ, int radius, boolean currentLevelScan) {
            this.level = level;
            this.currentLevelScan = currentLevelScan;

            int minX = centerX - radius;
            int maxX = centerX + radius;
            int minZ = centerZ - radius;
            int maxZ = centerZ + radius;
            int minChunkX = minX >> 4;
            int maxChunkX = maxX >> 4;
            int minChunkZ = minZ >> 4;
            int maxChunkZ = maxZ >> 4;

            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    areas.add(
                        new ChunkArea(
                            chunkX,
                            chunkZ,
                            Math.max(minX, chunkX << 4),
                            Math.min(maxX, (chunkX << 4) + 15),
                            Math.max(minZ, chunkZ << 4),
                            Math.min(maxZ, (chunkZ << 4) + 15)));
                }
            }
        }

        private boolean process(World world, int columnBudget) {
            int inspectedColumns = 0;
            int scannedColumnsThisTick = 0;
            int inspectionLimit = Math.max(columnBudget * 4, 512);
            Set<Long> scannedColumns = scannedColumnsByLevel[level];
            Map<Long, ColumnEvidence> evidence = evidenceByLevel[level];
            int floorY = RoguelikeMapSource.levelY(level);

            while (nextArea < areas.size() && inspectedColumns < inspectionLimit
                && scannedColumnsThisTick < columnBudget) {
                ChunkArea area = areas.get(nextArea);
                if (!area.checked) {
                    area.checked = true;
                    area.loaded = world.getChunkProvider()
                        .chunkExists(area.chunkX, area.chunkZ);
                    if (!area.loaded) {
                        hadUnloadedChunks = true;
                        nextArea++;
                        continue;
                    }
                }

                if (!area.loaded) {
                    nextArea++;
                    continue;
                }

                while (area.hasNext() && inspectedColumns < inspectionLimit) {
                    int x = area.nextX;
                    int z = area.nextZ;
                    area.advance();
                    inspectedColumns++;

                    long key = pack(x, z);
                    if (scannedColumns.contains(key)) continue;

                    ColumnEvidence column = scanColumn(world, floorY, x, z, markers);
                    scannedColumns.add(key);
                    scannedColumnsThisTick++;
                    if (column != null) {
                        evidence.put(key, column);
                        changedEvidence.add(column);
                    }
                }

                if (area.hasNext()) return false;
                nextArea++;
            }

            return nextArea >= areas.size();
        }
    }

    private static final class ChunkArea {

        private final int chunkX;
        private final int chunkZ;
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private int nextX;
        private int nextZ;
        private boolean checked;
        private boolean loaded;

        private ChunkArea(int chunkX, int chunkZ, int minX, int maxX, int minZ, int maxZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.nextX = minX;
            this.nextZ = minZ;
        }

        private boolean hasNext() {
            return nextX <= maxX;
        }

        private void advance() {
            nextZ++;
            if (nextZ > maxZ) {
                nextZ = minZ;
                nextX++;
            }
        }
    }

    private static final class ColumnEvidence {

        private final int x;
        private final int z;
        private final int score;
        private final byte flags;

        private ColumnEvidence(int x, int z, int score, byte flags) {
            this.x = x;
            this.z = z;
            this.score = score;
            this.flags = flags;
        }
    }
}
