package com.futa_gtnh.locator;

import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;

import com.futa_gtnh.Config;

/**
 * 一次「找最近的某种方块」的扫描任务。
 *
 * <p>
 * 设计成<b>可中断的增量任务</b>而不是一次性扫完：一个半径 64 的球形区域
 * 是 129×129×256 ≈ 426 万个方块，一口气扫完会让服务器卡住半秒以上。
 * 这里每 tick 只花固定的预算，扫不完就留到下一 tick。
 *
 * <p>
 * 三个关键优化：
 *
 * <ol>
 * <li><b>按水平环由近到远推进。</b>先用切比雪夫距离一圈圈往外扫，
 * 边扫边记最近的那个。一旦当前环号超过已找到的最近距离，
 * 后面不可能再有更近的了，直接结束 —— 大多数查询根本扫不满整个半径。</li>
 * <li><b>只扫已加载的区块。</b>用 {@code chunkProvider.chunkExists} 挡一下。
 * 不挡的话，扫一个半径 64 的范围会把上千个区块从磁盘上加载出来，
 * 那是比卡顿严重得多的事故。</li>
 * <li><b>从区块的方块数组直接读</b>，不走 {@code World.getBlock} ——
 * 后者要处理光照、邻接、 TileEntity 等一堆东西，在纯读取的场景下是纯开销。</li>
 * </ol>
 */
public final class LocatorScan {

    private final UUID playerId;
    private final Block block;
    private final int meta;

    private final int centerX;
    private final int centerY;
    private final int centerZ;
    private final int maxRadius;
    private final World world;

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

    public LocatorScan(World world, UUID playerId, ItemStack target, int centerX, int centerY, int centerZ) {
        this.world = world;
        this.playerId = playerId;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.maxRadius = Math.max(1, Config.locatorSearchRadius);

        Item item = target == null ? null : target.getItem();
        this.block = item == null ? null : Block.getBlockFromItem(item);
        // ItemStack 的 damage 不一定是方块元数据：ItemBlock 会做一次映射，
        // 标准做法就是过一遍 Item#getMetadata
        this.meta = item == null ? 0 : item.getMetadata(target.getItemDamage());
    }

    public UUID getPlayerId() {
        return playerId;
    }

    /** @return 这个目标压根不是方块（选了个纯物品），任务直接作废 */
    public boolean isValid() {
        return block != null;
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
        if (maxRadius <= 0) return 1.0F;
        return Math.min(1.0F, (float) ring / (float) maxRadius);
    }

    public Block getBlock() {
        return block;
    }

    public int getMeta() {
        return meta;
    }

    /**
     * 推进一段扫描。
     *
     * @return true 表示任务还在进行；false 表示已经结束（可以取结果了）
     */
    public boolean tick() {
        if (done) return false;
        if (block == null) {
            done = true;
            return false;
        }

        if (++ticks > Math.max(20, Config.locatorScanTimeoutTicks)) {
            done = true;
            return false;
        }

        int budget = Math.max(1000, Config.locatorBlocksPerTick);
        int maxY = Math.max(1, world.getHeight());

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
                yCursor = 0;
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
                yCursor = 0;
                budget -= maxY;
                continue;
            }

            Chunk chunk = provider.provideChunk(cx, cz);
            if (chunk == null) {
                ringCursor++;
                yCursor = 0;
                budget -= maxY;
                continue;
            }
            int lx = wx & 15;
            int lz = wz & 15;

            // 这一列从 yCursor 接着往下扫
            int yEnd = Math.min(maxY, yCursor + Math.max(1, budget / 64));
            for (int y = yCursor; y < yEnd; y++) {
                if (chunk.getBlock(lx, y, lz) != block) continue;
                if (chunk.getBlockMetadata(lx, y, lz) != meta) continue;

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
            if (yCursor >= maxY) {
                ringCursor++;
                yCursor = 0;
            }
        }

        return !done;
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
