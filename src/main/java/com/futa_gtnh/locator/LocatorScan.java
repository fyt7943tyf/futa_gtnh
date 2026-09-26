package com.futa_gtnh.locator;

import java.util.PriorityQueue;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.block.BlockChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;

/**
 * 一次搜索任务：找方块、矿脉、容器里的物品或生物群系。
 *
 * <p>
 * 设计成<b>可中断的增量任务</b>而不是一次性扫完：一个半径 128 的区域
 * 是 257×257×256 ≈ 1690 万个方块，一口气扫完会让服务器卡住好几秒。
 * 这里每 tick 只花固定的预算，扫不完就留到下一 tick。
 *
 * <p>
 * 普通方块、矿脉和物品扫描的关键优化：
 *
 * <ol>
 * <li><b>按水平环由近到远推进。</b>先用切比雪夫距离一圈圈往外扫，
 * 边扫边记最近的那个。一旦当前环号超过已找到的最近距离，
 * 后面不可能再有更近的了，直接结束 —— 大多数查询根本扫不满整个半径。</li>
 * <li><b>只扫已加载的区块。</b>用 {@code chunkProvider.chunkExists} 挡一下。
 * 不挡的话，扫一个半径 128 的范围会把上千个区块从磁盘上拉起来，
 * 那是比卡顿严重得多的事故。</li>
 * <li><b>从区块的方块数组直接读</b>，不走 {@code World.getBlock} ——
 * 后者要处理光照、邻接、 TileEntity 等一堆东西，在纯读取的场景下是纯开销。</li>
 * </ol>
 *
 * <p>
 * <b>搜索模式：</b>
 *
 * <ul>
 * <li><b>方块模式</b>：认准一个 (Block, 元数据) 组合。</li>
 * <li><b>矿脉模式</b>：认准<b>一种材料</b>。一条矿脉在不同石种里会铺成不同的
 * 方块（GTNH 里石头、花岗岩、深海石头各是一个 {@code GTBlockOre} 实例），
 * 按方块找永远只能找到其中一个石种的那份；按材料找就跨过去了。
 * 顺带还能把 Y 范围收窄到矿脉自己的生成高度，既快又准。</li>
 * <li><b>物品模式</b>：在已加载的箱子、木桶和板条箱库存中查找目标物品。</li>
 * <li><b>生物群系模式</b>：按二维生物群系数据分区增量查询；不扫描高度，也不加载区块。</li>
 * </ul>
 */
public final class LocatorScan {

    private static final int BIOME_REGION_SIZE = 128;

    private static final class BiomeScanRegion implements Comparable<BiomeScanRegion> {

        final int minX;
        final int minZ;
        final int width;
        final int depth;
        final long minDistanceSq;

        BiomeScanRegion(int minX, int minZ, int width, int depth, long minDistanceSq) {
            this.minX = minX;
            this.minZ = minZ;
            this.width = width;
            this.depth = depth;
            this.minDistanceSq = minDistanceSq;
        }

        @Override
        public int compareTo(BiomeScanRegion other) {
            int distanceOrder = Long.compare(minDistanceSq, other.minDistanceSq);
            if (distanceOrder != 0) return distanceOrder;
            int xOrder = Integer.compare(minX, other.minX);
            return xOrder != 0 ? xOrder : Integer.compare(minZ, other.minZ);
        }
    }

    private final UUID playerId;

    /** 方块模式下的目标；矿脉和物品模式下为 null。 */
    private final ItemStack blockTarget;
    /** 箱子物品模式下的目标；其它模式下为 null。 */
    private final ItemStack itemTarget;
    private final boolean inventorySearch;
    private final Block block;
    private final int meta;
    /** 矿脉模式下的目标；方块模式下为 null。 */
    private final OreVeinCatalog.Entry vein;
    /** 生物群系模式下的目标；其它模式下为 null。 */
    private final BiomeGenBase biome;

    /**
     * 目标是不是 GT 的矿石方块（仅方块模式有意义）。
     *
     * <p>
     * GT 把「自然生成 / 玩家放置」编码成元数据里的一个标志位，所以对矿石不能做
     * 精确元数据比较，得比「矿的身份」。见 {@link #metaMatches(int)} 和
     * {@link GtOreSupport} 的类注释。
     */
    private final boolean gtOre;

    /**
     * GT 矿石比较是否可用。{@code null} 表示还没探测过。
     *
     * <p>
     * {@link GtOreSupport} 引用着 {@code GTBlockOre}，而那个类只在较新的 GT5U 里有。
     * 探测失败就全局降级成精确匹配，并且只报一次日志 —— 每开一次搜索就刷一条
     * 堆栈日志没有任何意义。
     */
    private static Boolean gtOreSupport;

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final int maxRadius;
    private final World world;
    private final WorldChunkManager biomeManager;
    private final int biomeRadius;
    private final long biomeRadiusSq;
    private final PriorityQueue<BiomeScanRegion> biomeRegions;
    private final int totalBiomeRegions;

    private BiomeScanRegion currentBiomeRegion;
    private BiomeGenBase[] biomeBuffer;
    private int biomeCellCursor;
    private int scannedBiomeRegions;

    /** 要扫的 Y 范围。方块模式是整个世界高度；矿脉模式收窄到矿脉的生成高度。 */
    private final int scanMinY;
    private final int scanMaxY;

    /** 当前扫到第几环（切比雪夫距离）。 */
    private int ring;
    /** 环内游标：这个环上一共有 8*ring 列要扫（ring == 0 时是 1 列）。 */
    private int ringCursor;
    /** 当前列的 Y 游标。 */
    private int yCursor;

    private long bestDistanceSq = Long.MAX_VALUE;
    private int bestX;
    private int bestY;
    private int bestZ;

    /**
     * 已经跑了多少 tick。
     *
     * <p>
     * 纯粹是兜底：正常情况下扫描会因为「找到更近的」或「超出半径」而结束，
     * 但如果某天有人把半径调到 512、或者某个区块的读取被卡住，
     * 没有这个计数器的话任务会一直挂在服务端 tick 里，永远不结束。
     */
    private int ticks;

    private boolean done;
    private boolean foundAnything;

    // ==================================================================
    // 构造
    // ==================================================================

    /** 方块模式：找最近的这个方块。 */
    public LocatorScan(World world, UUID playerId, ItemStack target, int centerX, int centerY, int centerZ) {
        this(world, playerId, target, centerX, centerY, centerZ, false);
    }

    /** 箱子物品模式：找最近一个库存里含有目标物品的容器。 */
    public LocatorScan(World world, UUID playerId, ItemStack target, int centerX, int centerY, int centerZ,
        boolean inventorySearch) {
        this.world = world;
        this.playerId = playerId;
        this.blockTarget = inventorySearch || target == null ? null : target.copy();
        this.itemTarget = inventorySearch && target != null ? target.copy() : null;
        this.inventorySearch = inventorySearch;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.maxRadius = Math.max(1, Config.locatorSearchRadius);
        this.vein = null;
        this.biome = null;
        this.biomeManager = null;
        this.biomeRadius = 0;
        this.biomeRadiusSq = 0L;
        this.biomeRegions = null;
        this.totalBiomeRegions = 0;

        Item item = inventorySearch || target == null ? null : target.getItem();
        this.block = item == null ? null : Block.getBlockFromItem(item);
        // ItemStack 的 damage 不一定是方块元数据：ItemBlock 会做一次映射，
        // 标准做法就是过一遍 Item#getMetadata
        this.meta = item == null ? 0 : item.getMetadata(target.getItemDamage());
        this.gtOre = detectGtOre(this.block);

        this.scanMinY = 0;
        this.scanMaxY = Math.max(1, world == null ? 256 : world.getHeight());
        this.yCursor = scanMinY;
    }

    /** 矿脉模式：找最近的这条矿脉。 */
    public LocatorScan(World world, UUID playerId, OreVeinCatalog.Entry vein, int centerX, int centerY, int centerZ) {
        this.world = world;
        this.playerId = playerId;
        this.blockTarget = null;
        this.itemTarget = null;
        this.inventorySearch = false;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.maxRadius = Math.max(1, Config.locatorSearchRadius);
        this.vein = vein;
        this.biome = null;
        this.biomeManager = null;
        this.biomeRadius = 0;
        this.biomeRadiusSq = 0L;
        this.biomeRegions = null;
        this.totalBiomeRegions = 0;

        this.block = null;
        this.meta = 0;
        this.gtOre = false;

        // 只在矿脉自己的生成高度里找。
        //
        // 这既是提速（GTNH 的矿脉高度区间通常只有几十格，而世界有 256 格，
        // 少扫一大半），也是提精度：区间之外的同类矿石只可能是玩家自己放的，
        // 而「找矿脉」要的不是那个。
        int worldHeight = Math.max(1, world == null ? 256 : world.getHeight());
        int[] range = OreVeinCatalog.heightRange(vein, world);
        if (range != null && range[1] > range[0]) {
            this.scanMinY = Math.max(0, range[0]);
            this.scanMaxY = Math.min(worldHeight, range[1] + 1);
        } else {
            // 拿不到高度区间（比如维度名取不到）就退回全高度，宁可慢也别漏
            this.scanMinY = 0;
            this.scanMaxY = worldHeight;
        }
        this.yCursor = scanMinY;
    }

    /** 生物群系模式：在本维度的生物群系数据中找最近的位置，不加载区块。 */
    public LocatorScan(World world, UUID playerId, BiomeGenBase biome, int centerX, int centerY, int centerZ) {
        this.world = world;
        this.playerId = playerId;
        this.blockTarget = null;
        this.itemTarget = null;
        this.inventorySearch = false;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.maxRadius = Math.max(1, Config.locatorBiomeSearchRadius);
        this.vein = null;
        this.biome = biome;
        this.block = null;
        this.meta = 0;
        this.gtOre = false;
        this.biomeManager = world == null ? null : world.getWorldChunkManager();
        this.biomeRadius = Math.max(1, Config.locatorBiomeSearchRadius);
        this.biomeRadiusSq = (long) biomeRadius * biomeRadius;
        this.biomeRegions = new PriorityQueue<>();
        this.totalBiomeRegions = buildBiomeRegions();
        this.scanMinY = 0;
        this.scanMaxY = 1;
        this.yCursor = 0;
    }

    private int buildBiomeRegions() {
        int minWorldX = centerX - biomeRadius;
        int maxWorldX = centerX + biomeRadius;
        int minWorldZ = centerZ - biomeRadius;
        int maxWorldZ = centerZ + biomeRadius;
        int firstRegionX = Math.floorDiv(minWorldX, BIOME_REGION_SIZE);
        int lastRegionX = Math.floorDiv(maxWorldX, BIOME_REGION_SIZE);
        int firstRegionZ = Math.floorDiv(minWorldZ, BIOME_REGION_SIZE);
        int lastRegionZ = Math.floorDiv(maxWorldZ, BIOME_REGION_SIZE);
        int count = 0;

        for (int regionX = firstRegionX; regionX <= lastRegionX; regionX++) {
            int regionStartX = regionX * BIOME_REGION_SIZE;
            int regionMinX = Math.max(minWorldX, regionStartX);
            int regionMaxX = Math.min(maxWorldX, regionStartX + BIOME_REGION_SIZE - 1);
            long dx = distanceToRange(centerX, regionMinX, regionMaxX);

            for (int regionZ = firstRegionZ; regionZ <= lastRegionZ; regionZ++) {
                int regionStartZ = regionZ * BIOME_REGION_SIZE;
                int regionMinZ = Math.max(minWorldZ, regionStartZ);
                int regionMaxZ = Math.min(maxWorldZ, regionStartZ + BIOME_REGION_SIZE - 1);
                long dz = distanceToRange(centerZ, regionMinZ, regionMaxZ);
                long minDistanceSq = dx * dx + dz * dz;
                if (minDistanceSq > biomeRadiusSq) continue;

                biomeRegions.add(
                    new BiomeScanRegion(
                        regionMinX,
                        regionMinZ,
                        regionMaxX - regionMinX + 1,
                        regionMaxZ - regionMinZ + 1,
                        minDistanceSq));
                count++;
            }
        }
        return count;
    }

    private static long distanceToRange(int value, int min, int max) {
        if (value < min) return (long) min - value;
        if (value > max) return (long) value - max;
        return 0L;
    }

    /**
     * 判断目标是不是 GT 矿石，顺带探测 GT 的矿石 API 在不在。
     *
     * @return 这个目标方块需不需要走「同一种矿」的比较
     */
    private static boolean detectGtOre(Block block) {
        if (block == null) return false;
        if (!ensureGtOreSupport()) return false;

        try {
            return GtOreSupport.isOre(block);
        } catch (Throwable t) {
            gtOreSupport = Boolean.FALSE;
            return false;
        }
    }

    private static boolean ensureGtOreSupport() {
        if (gtOreSupport != null) return gtOreSupport;

        try {
            // 只为把 GtOreSupport 这个类加载起来；类加载失败会在这里抛出来
            GtOreSupport.ping();
            gtOreSupport = Boolean.TRUE;
        } catch (Throwable t) {
            gtOreSupport = Boolean.FALSE;
            FutaGtnhMod.LOG.warn("拿不到 GT 的矿石元数据 API，寻物魔杖将退回精确元数据匹配" + "（世界自然生成的矿石可能搜不到）", t);
        }
        return gtOreSupport;
    }

    /**
     * 世界里的这个元数据算不算命中目标（仅方块模式）。
     *
     * <p>
     * 先试精确相等 —— 这条对绝大多数方块就是全部逻辑。
     *
     * <p>
     * 对 GT 的矿石再补一条「同一种矿」的比较：GT 用元数据里的一个标志位区分
     * 「世界自然生成」和「玩家放下」，而寻物魔杖能选到的那份（创造标签页）
     * 标志位是「非自然」。只认精确相等的话，就会出现
     * <b>自己放的矿找得到、世界生成的矿怎么都找不到</b>的现象。
     */
    private boolean metaMatches(int worldMeta) {
        if (worldMeta == meta) return true;
        if (!gtOre) return false;

        try {
            return GtOreSupport.sameVariant(block, meta, worldMeta);
        } catch (Throwable t) {
            gtOreSupport = Boolean.FALSE;
            return false;
        }
    }

    // ==================================================================
    // 状态
    // ==================================================================

    public UUID getPlayerId() {
        return playerId;
    }

    /** @return 目标类型不支持，或矿脉数据已失效；任务会直接作废 */
    public boolean isValid() {
        return biome != null || vein != null
            || block != null
            || (inventorySearch && itemTarget != null && itemTarget.getItem() != null);
    }

    public boolean isDone() {
        return done;
    }

    public boolean hasResult() {
        return foundAnything;
    }

    public int getBestX() {
        return bestX;
    }

    public int getBestY() {
        return bestY;
    }

    public int getBestZ() {
        return bestZ;
    }

    public double getBestDistance() {
        return bestDistanceSq == Long.MAX_VALUE ? -1.0D : Math.sqrt((double) bestDistanceSq);
    }

    /** @return 0.0 ~ 1.0 的粗略进度，用来给界面画进度条 */
    public float getProgress() {
        if (done) return 1.0F;
        if (biome != null) {
            return totalBiomeRegions == 0 ? 1.0F : Math.min(1.0F, (float) scannedBiomeRegions / totalBiomeRegions);
        }
        if (maxRadius <= 0) return 1.0F;
        return Math.min(1.0F, (float) ring / (float) maxRadius);
    }

    public int getBiomeId() {
        return biome == null ? -1 : biome.biomeID;
    }

    public boolean isBiomeSearch() {
        return biome != null;
    }

    /** 传送时才生成目标区块，并据地表高度寻找安全落点。 */
    int prepareBiomeTeleportY(int x, int z) {
        if (biome == null || world == null) return centerY;
        IChunkProvider provider = world.getChunkProvider();
        if (provider == null) return -1;

        try {
            Chunk chunk = provider.provideChunk(x >> 4, z >> 4);
            return chunk == null ? -1 : chunk.getHeightValue(x & 15, z & 15);
        } catch (Throwable t) {
            FutaGtnhMod.LOG.warn("寻物魔杖：生成生物群系目标区块失败，无法传送", t);
            return -1;
        }
    }

    public Block getBlock() {
        return block;
    }

    public int getMeta() {
        return meta;
    }

    World getWorld() {
        return world;
    }

    /** 从玩家的新位置再次搜索同一个目标。 */
    LocatorScan restartAt(int x, int y, int z) {
        if (biome != null) return new LocatorScan(world, playerId, biome, x, y, z);
        if (vein != null) return new LocatorScan(world, playerId, vein, x, y, z);
        if (inventorySearch) return new LocatorScan(world, playerId, itemTarget, x, y, z, true);
        return new LocatorScan(world, playerId, blockTarget, x, y, z);
    }

    /**
     * 检查上次命中的坐标是否仍然是目标。
     *
     * <p>
     * 区块未加载时返回 true：此时无法判断方块是否被破坏，应该等区块重新加载后再检查，
     * 而不是因此把玩家带去搜另一个目标。
     */
    boolean targetStillAt(int x, int y, int z) {
        if (biome != null) {
            return world != null && biomeManager != null && biomeManager.getBiomeGenAt(x, z) == biome;
        }
        if (world == null || y < 0 || y >= world.getHeight()) return false;
        IChunkProvider provider = world.getChunkProvider();
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        if (provider == null || !provider.chunkExists(chunkX, chunkZ)) return true;

        Chunk chunk = provider.provideChunk(chunkX, chunkZ);
        return chunk == null || matches(chunk, x & 15, y, z & 15, x, z);
    }

    // ==================================================================
    // 推进
    // ==================================================================

    /**
     * 推进一段扫描。
     *
     * @return true 表示任务还在进行；false 表示已经结束（可以取结果了）
     */
    public boolean tick() {
        if (done) return false;
        if (!isValid()) {
            done = true;
            return false;
        }

        int timeout = biome == null ? Config.locatorScanTimeoutTicks : Config.locatorBiomeScanTimeoutTicks;
        if (++ticks > Math.max(20, timeout)) {
            done = true;
            return false;
        }

        if (biome != null) return tickBiomes();

        int budget = Math.max(1000, Config.locatorBlocksPerTick);
        // 一列要扫多少格。矿脉模式下这个数会比世界高度小得多。
        int columnHeight = Math.max(1, scanMaxY - scanMinY);

        while (budget > 0 && !done) {
            // 提前结束：当前环已经比已知最近距离还远，外面不可能有更近的
            if (foundAnything && (long) ring * (long) ring > bestDistanceSq) {
                done = true;
                break;
            }
            if (ring > maxRadius) {
                done = true;
                break;
            }

            int columnsInRing = ring == 0 ? 1 : 8 * ring;

            if (ringCursor >= columnsInRing) {
                ring++;
                ringCursor = 0;
                yCursor = scanMinY;
                continue;
            }

            int[] offset = ringOffset(ring, ringCursor);
            int wx = centerX + offset[0];
            int wz = centerZ + offset[1];

            // 没加载的区块一律跳过 —— 扫一片空地不值得把上千个区块从磁盘拉起来
            IChunkProvider provider = world.getChunkProvider();
            int cx = wx >> 4;
            int cz = wz >> 4;
            if (provider == null || !provider.chunkExists(cx, cz)) {
                ringCursor++;
                yCursor = scanMinY;
                budget -= columnHeight;
                continue;
            }

            Chunk chunk = provider.provideChunk(cx, cz);
            if (chunk == null) {
                // chunkExists 为真时理论上不会是 null，但别的模组覆盖 IChunkProvider
                // 时不保证这一点。null 检查比崩掉整个服务端便宜得多。
                ringCursor++;
                yCursor = scanMinY;
                budget -= columnHeight;
                continue;
            }
            int lx = wx & 15;
            int lz = wz & 15;

            // 这一列从 yCursor 接着往下扫
            int yEnd = Math.min(scanMaxY, yCursor + Math.max(1, budget / 64));
            for (int y = yCursor; y < yEnd; y++) {
                if (!matches(chunk, lx, y, lz, wx, wz)) continue;

                long dx = wx - centerX;
                long dy = y - centerY;
                long dz = wz - centerZ;
                long distSq = dx * dx + dy * dy + dz * dz;
                if (distSq < bestDistanceSq) {
                    bestDistanceSq = distSq;
                    bestX = wx;
                    bestY = y;
                    bestZ = wz;
                    foundAnything = true;
                }
            }

            int scanned = Math.max(1, yEnd - yCursor);
            budget -= scanned;
            yCursor = yEnd;
            if (yCursor >= scanMaxY) {
                ringCursor++;
                yCursor = scanMinY;
            }
        }

        return !done;
    }

    /** 批量读取实际生物群系图层；不访问方块，也不会加载搜索范围里的区块。 */
    private boolean tickBiomes() {
        if (biomeManager == null) {
            done = true;
            return false;
        }

        int budget = Math.max(1000, Config.locatorBiomeSamplesPerTick);
        while (budget > 0 && !done) {
            if (currentBiomeRegion == null) {
                if (biomeRegions.isEmpty()) {
                    done = true;
                    break;
                }

                BiomeScanRegion next = biomeRegions.peek();
                if (foundAnything && next.minDistanceSq > bestDistanceSq) {
                    done = true;
                    break;
                }

                currentBiomeRegion = biomeRegions.poll();
                biomeBuffer = biomeManager.getBiomeGenAt(
                    biomeBuffer,
                    currentBiomeRegion.minX,
                    currentBiomeRegion.minZ,
                    currentBiomeRegion.width,
                    currentBiomeRegion.depth,
                    false);
                biomeCellCursor = 0;
            }

            int regionCellCount = currentBiomeRegion.width * currentBiomeRegion.depth;
            int end = Math.min(regionCellCount, biomeCellCursor + budget);
            for (int index = biomeCellCursor; index < end; index++) {
                if (biomeBuffer[index] != biome) continue;

                int x = currentBiomeRegion.minX + index % currentBiomeRegion.width;
                int z = currentBiomeRegion.minZ + index / currentBiomeRegion.width;
                long dx = (long) x - centerX;
                long dz = (long) z - centerZ;
                long distanceSq = dx * dx + dz * dz;
                if (distanceSq > biomeRadiusSq || distanceSq >= bestDistanceSq) continue;

                bestDistanceSq = distanceSq;
                bestX = x;
                // A biome covers the full vertical column; use the player's original altitude for the beam.
                bestY = centerY;
                bestZ = z;
                foundAnything = true;
            }

            int scanned = end - biomeCellCursor;
            biomeCellCursor = end;
            budget -= scanned;
            if (biomeCellCursor >= regionCellCount) {
                currentBiomeRegion = null;
                scannedBiomeRegions++;
            }
        }

        return !done;
    }

    /**
     * 这一格是不是我们要找的。
     *
     * <p>
     * 抽成一个方法而不是把两种模式的分支塞进内层循环：这个循环要跑上百万次，
     * 分支预测和 JIT 内联都指望它长得简单。方法很快会被内联掉，写法上的
     * 那点间接开销在运行期并不存在。
     */
    private boolean matches(Chunk chunk, int lx, int y, int lz, int worldX, int worldZ) {
        if (inventorySearch) return inventoryContains(chunk, lx, y, lz, worldX, worldZ);

        Block worldBlock = chunk.getBlock(lx, y, lz);

        if (vein != null) {
            if (!ensureGtOreSupport()) return false;
            try {
                // 先挡一道 instanceof（绝大多数方块在这里就被排除了），
                // 再让 GT 自己去解元数据
                if (!GtOreSupport.isOre(worldBlock)) return false;
                return GtOreSupport.matchesVein(vein, worldBlock, chunk.getBlockMetadata(lx, y, lz));
            } catch (Throwable t) {
                gtOreSupport = Boolean.FALSE;
                return false;
            }
        }

        if (worldBlock != block) return false;
        return metaMatches(chunk.getBlockMetadata(lx, y, lz));
    }

    /** 这个位置的容器库存是否包含目标物品；名称标签等 NBT 不参与匹配。 */
    private boolean inventoryContains(Chunk chunk, int lx, int y, int lz, int worldX, int worldZ) {
        TileEntity tile;
        try {
            tile = world.getTileEntity(worldX, y, worldZ);
        } catch (Throwable ignored) {
            return false;
        }
        if (!(tile instanceof IInventory) || itemTarget == null || itemTarget.getItem() == null) return false;
        if (!isChestLike(chunk.getBlock(lx, y, lz))) return false;

        IInventory inventory = (IInventory) tile;
        try {
            int size = inventory.getSizeInventory();
            for (int slot = 0; slot < size; slot++) {
                ItemStack stack = inventory.getStackInSlot(slot);
                if (stack == null || stack.getItem() != itemTarget.getItem()) continue;

                // 有子类型的物品按元数据区分；普通物品、耐久工具按物品种类匹配。
                if (!itemTarget.getItem()
                    .getHasSubtypes() || itemTarget.getItemDamage() == 32767
                    || stack.getItemDamage() == itemTarget.getItemDamage()) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 模组库存的接口实现可能因自身状态抛错，跳过它不影响其它容器。
        }
        return false;
    }

    /** 兼容原版箱子和注册名标为箱子、木桶、板条箱等的模组容器。 */
    private static boolean isChestLike(Block block) {
        if (block instanceof BlockChest) return true;
        Object registryName = Block.blockRegistry.getNameForObject(block);
        if (registryName == null) return false;
        String name = registryName.toString()
            .toLowerCase(java.util.Locale.ROOT);
        return name.contains("chest") || name.contains("crate")
            || name.contains("barrel")
            || name.contains("locker")
            || name.contains("storage");
    }

    /**
     * 第 {@code ring} 环上的第 {@code index} 个列偏移。
     *
     * <p>
     * 每一环是「切比雪夫距离正好等于 ring」的那一圈，共 {@code 8 * ring} 列
     * （ring == 0 时只有中心一列）。这里按上边、右边、下边、左边的顺序走一圈。
     *
     * <p>
     * 不用「遍历 (2r+1)² 的正方形再挑出边上的」那种写法：ring 到 64 时
     * 正方形是 16641 个格子而边上只有 512 个，32 倍的浪费。
     */
    private static int[] ringOffset(int ring, int index) {
        if (ring == 0) return new int[] { 0, 0 };

        int side = 2 * ring;
        int i = index;
        if (i < side) {
            // 上边：dz = -ring，dx 从 -ring 到 ring-1
            return new int[] { -ring + i, -ring };
        }
        i -= side;
        if (i < side) {
            // 右边：dx = ring，dz 从 -ring 到 ring-1
            return new int[] { ring, -ring + i };
        }
        i -= side;
        if (i < side) {
            // 下边：dz = ring，dx 从 ring 往回走到 -ring+1
            return new int[] { ring - i, ring };
        }
        i -= side;
        // 左边：dx = -ring，dz 从 ring 往回走到 -ring+1
        return new int[] { -ring, ring - i };
    }
}
