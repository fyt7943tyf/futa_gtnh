package com.futa_gtnh.lootassist;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketLootassistDelta;
import com.futa_gtnh.network.PacketLootassistProgress;
import com.futa_gtnh.network.PacketLootassistSync;

/**
 * 小游戏助手共享列表的服务端中枢（单例、每服务器一份，跨维度共享）。
 *
 * <p>
 * 职责：
 * <ul>
 * <li><b>共享数据</b>：全部地牢记录（{@link LootassistEntry}）+「已验证不存在」的
 * 区块负缓存，落盘走 {@link LootassistFile}（原子写 + 备份）。</li>
 * <li><b>搜索</b>：种子推算候选（{@link LootgameFinder}，零开销）→ 候选立即以
 * 「待验证」进共享列表 → <b>只验证当前已加载的区块（绝不强制生成）</b>，
 * 未加载的交给 {@link #onChunkLoad} 在区块被正常加载时补验。同一时间只允许一个
 * 搜索任务；请求者中途下线任务也会跑完，只是没人收进度。</li>
 * <li><b>自然发现</b>：区块被正常加载时（任何人走过/生成）顺带验证对应候选点 ——
 * 玩家探索本身就在一点点补全地图。</li>
 * <li><b>同步</b>：打开界面的玩家登记为 viewer，改动（新验证/标记完成）以增量包
 * 广播；打开时发全量快照。全部在服务端主线程，与 {@code SharedStorageManager}
 * 同一套线程模型。</li>
 * </ul>
 *
 * <p>
 * 「未验证候选要不要先展示」：要 —— 玩家点搜索就应该立刻看到一圈候选点
 * （状态待验证），验证结果随后陆续刷新。被证伪的候选会从列表里移除并进负缓存，
 * 之后不会再为它生成区块。
 */
public final class LootassistManager {

    private LootassistManager() {}

    /** 搜索每 tick 的时长预算（纳秒）。搜索的主要开销是按需生成区块，必须限流。 */
    private static final long TICK_BUDGET_NANOS = 25_000_000L;
    /** 进度包的发送间隔（tick）。 */
    private static final int PROGRESS_INTERVAL_TICKS = 10;

    private static final Map<String, LootassistEntry> entries = new LinkedHashMap<>();
    private static final Set<String> absentChunks = new HashSet<>();
    private static final Set<UUID> viewers = new LinkedHashSet<>();

    private static File worldDirectory;
    private static boolean loaded;
    private static boolean dirty;
    private static int autosaveTicker;

    // ---- 搜索任务状态（全局最多一个）----
    private static final ArrayDeque<Candidate> searchQueue = new ArrayDeque<>();
    /** 当前收进度的玩家；中途下线会被置空，任务本身照常跑完。 */
    private static UUID searchRequester;
    private static int searchTotal;
    private static int searchDone;
    private static int searchFound;
    private static int progressTimer;

    /** 一次待验证的候选点。 */
    private static final class Candidate {

        final int dim;
        final int chunkX;
        final int chunkZ;

        Candidate(int dim, int chunkX, int chunkZ) {
            this.dim = dim;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        int x() {
            return LootgameFinder.centerCoord(chunkX);
        }

        int z() {
            return LootgameFinder.centerCoord(chunkZ);
        }

        String key() {
            return dim + ":" + x() + ":" + z();
        }

        String chunkKey() {
            return dim + ":" + chunkX + ":" + chunkZ;
        }
    }

    // ==================================================================
    // 生命周期
    // ==================================================================

    public static void onServerStarted(MinecraftServer server) {
        if (loaded) {
            saveNow();
        }
        resetRuntimeState();

        File dir = resolveWorldDirectory(server);
        if (dir == null) {
            FutaGtnhMod.LOG.error("小游戏助手：拿不到主世界存档目录，本次以内存模式运行（不会写盘）");
            worldDirectory = null;
            loaded = true;
            return;
        }

        worldDirectory = dir;
        Map<String, LootassistEntry> loadedEntries = LootassistFile.loadEntries(dir);
        entries.clear();
        if (loadedEntries != null) {
            entries.putAll(loadedEntries);
        }
        loaded = true;
        dirty = false;
        autosaveTicker = 0;
    }

    public static void onServerStopping() {
        saveNow();
        loaded = false;
        resetRuntimeState();
    }

    private static void resetRuntimeState() {
        viewers.clear();
        searchQueue.clear();
        searchRequester = null;
        searchTotal = searchDone = searchFound = 0;
        absentChunks.clear();
    }

    /** 服务端 tick：推进搜索任务 + 定期落盘。空载时的开销是一次布尔判断。 */
    public static void onServerTick() {
        if (!loaded) return;
        processSearchTick();

        if (worldDirectory == null) return;
        if (++autosaveTicker < Config.autosaveIntervalSeconds * 20) return;
        autosaveTicker = 0;
        if (dirty) {
            saveNow();
        }
    }

    /** 立即落盘（若有改动）。 */
    public static void saveNow() {
        if (worldDirectory == null || !dirty) return;
        if (LootassistFile.save(worldDirectory, entries, absentChunks)) {
            dirty = false;
        }
    }

    private static File resolveWorldDirectory(MinecraftServer server) {
        if (server == null) return null;
        WorldServer[] worlds = server.worldServers;
        if (worlds == null || worlds.length == 0 || worlds[0] == null) return null;
        try {
            return worlds[0].getSaveHandler()
                .getWorldDirectory();
        } catch (Throwable t) {
            FutaGtnhMod.LOG.error("小游戏助手：读取主世界存档目录失败", t);
            return null;
        }
    }

    // ==================================================================
    // viewer 与条目
    // ==================================================================

    public static void addViewer(UUID playerId) {
        viewers.add(playerId);
    }

    public static void removeViewer(UUID playerId) {
        viewers.remove(playerId);
    }

    /** @return 当前在线且登记为 viewer 的玩家（viewer 表随登出清理，不会攒脏引用） */
    private static List<EntityPlayerMP> viewerPlayers() {
        List<EntityPlayerMP> result = new ArrayList<>();
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return result;
        for (Object object : server.getConfigurationManager().playerEntityList) {
            if (object instanceof EntityPlayerMP && viewers.contains(((EntityPlayerMP) object).getUniqueID())) {
                result.add((EntityPlayerMP) object);
            }
        }
        return result;
    }

    /** @return 全部条目（只读视图，按发现顺序） */
    public static Collection<LootassistEntry> getEntries() {
        return Collections.unmodifiableCollection(entries.values());
    }

    private static void upsertEntry(LootassistEntry entry) {
        entries.put(entry.key(), entry);
        dirty = true;
        broadcastDelta(new PacketLootassistDelta(entry));
    }

    private static void removeEntry(String key) {
        if (entries.remove(key) == null) return;
        dirty = true;
        broadcastDelta(PacketLootassistDelta.removal(key));
    }

    /**
     * 标记/取消「已完成」（全服共享）。
     *
     * @return 条目是否存在
     */
    public static boolean markCompleted(String key, String playerName, boolean completed) {
        LootassistEntry entry = entries.get(key);
        if (entry == null) return false;
        if (entry.completed != completed) {
            entry.completed = completed;
            entry.completedBy = completed ? playerName : "";
            entry.completedAt = completed ? System.currentTimeMillis() : 0L;
            dirty = true;
            broadcastDelta(new PacketLootassistDelta(entry));
        }
        return true;
    }

    /** 备份恢复路径用的：把文件里的负缓存灌回内存（见 {@link LootassistFile}）。 */
    static void restoreAbsentChunks(Set<String> chunkKeys) {
        absentChunks.addAll(chunkKeys);
    }

    private static void broadcastDelta(PacketLootassistDelta delta) {
        for (EntityPlayerMP viewer : viewerPlayers()) {
            NetworkHandler.INSTANCE.sendTo(delta, viewer);
        }
    }

    // ==================================================================
    // 搜索
    // ==================================================================

    /**
     * 玩家发起「搜索附近」：种子推算候选 → 全部以「待验证」登记进共享列表
     * （所有人立刻可见）→ 已加载的立即验证，未加载的等自然加载补验。
     * 整个过程不强制生成任何区块，点下去立刻返回。
     */
    public static void startSearch(EntityPlayerMP player) {
        if (!LootgamesCompat.isAvailable()) {
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.lootassist.no_lootgames"));
            return;
        }
        if (!searchQueue.isEmpty()) {
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.lootassist.search_busy"));
            return;
        }

        int dim = player.dimension;
        int centerChunkX = MathHelper.floor_double(player.posX) >> 4;
        int centerChunkZ = MathHelper.floor_double(player.posZ) >> 4;
        List<int[]> candidates = LootgameFinder.findCandidateChunks(
            player.worldObj.getSeed(),
            dim,
            centerChunkX,
            centerChunkZ,
            Config.lootassistSearchRadius);

        searchQueue.clear();
        searchFound = 0;
        for (int[] chunk : candidates) {
            Candidate candidate = new Candidate(dim, chunk[0], chunk[1]);
            if (absentChunks.contains(candidate.chunkKey())) continue;
            LootassistEntry existing = entries.get(candidate.key());
            if (existing != null && existing.verified()) continue;
            if (existing == null) {
                // 先登记为「待验证」，验证结果随后陆续覆盖
                upsertEntry(new LootassistEntry(candidate.dim, candidate.x(), candidate.z()));
            }
            searchQueue.add(candidate);
        }

        searchTotal = searchQueue.size();
        searchDone = 0;
        searchRequester = player.getUniqueID();
        progressTimer = PROGRESS_INTERVAL_TICKS;

        if (searchQueue.isEmpty()) {
            searchQueue.clear(); // 保持不变量：队列为空 = 没有任务
            sendProgress(player, false);
            player.addChatMessage(new ChatComponentTranslation("futa_gtnh.msg.lootassist.search_nothing"));
            searchRequester = null;
        } else {
            sendProgress(player, true);
        }
    }

    /**
     * 每 tick 的搜索推进：在时长预算内逐个验证候选。
     *
     * <p>
     * <b>只验证当前已加载的区块，绝不强制生成。</b>强制生成是初版卡服的根因：
     * GTNH 的世界生成很重，一次同步生成就要几百毫秒到几秒，tick 预算只能限制
     * 「发起几个」、管不住单次的耗时；服务端一卡，所有依赖服务端往返的界面
     * （共享背包、俯瞰视角的服务端确认……）全部超时。未加载的候选保持
     * 「待验证」，由 {@link #onChunkLoad} 在区块被正常加载时补验。
     */
    private static void processSearchTick() {
        if (searchQueue.isEmpty()) return;

        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        while (!searchQueue.isEmpty() && System.nanoTime() < deadline) {
            Candidate candidate = searchQueue.poll();
            searchDone++;

            LootassistEntry existing = entries.get(candidate.key());
            if (existing != null && existing.verified()) continue;
            if (absentChunks.contains(candidate.chunkKey())) continue;

            WorldServer world = MinecraftServer.getServer()
                .worldServerForDimension(candidate.dim);
            if (world == null) continue;

            // 区块没加载就跳过：不生成、也不进「不存在」负缓存（它以后可能真的会生成）。
            // 条目已登记在列表里，保持「待验证」，等自然加载补验。
            if (!LootgameFinder.isChunkLoaded(world, candidate.chunkX, candidate.chunkZ)) continue;

            int masterY = LootgameFinder.findMasterY(world, candidate.x(), candidate.z());
            if (masterY >= 0) {
                LootassistEntry entry = existing != null ? existing
                    : new LootassistEntry(candidate.dim, candidate.x(), candidate.z());
                entry.y = masterY;
                int[] entrance = LootgameFinder.findEntrance(world, candidate.x(), candidate.z(), masterY);
                entry.entranceX = entrance[0];
                entry.entranceY = entrance[1];
                entry.entranceZ = entrance[2];
                upsertEntry(entry);
                searchFound++;
            } else {
                removeEntry(candidate.key());
                absentChunks.add(candidate.chunkKey());
                dirty = true;
            }
        }

        boolean finished = searchQueue.isEmpty();
        if (!finished || searchRequester != null) {
            EntityPlayerMP requester = playerByUuid(searchRequester);
            if (requester != null && (++progressTimer >= PROGRESS_INTERVAL_TICKS || finished)) {
                progressTimer = 0;
                sendProgress(requester, !finished);
            }
        }
        if (finished) {
            searchRequester = null;
        }
    }

    /**
     * 自然发现：任何区块被加载（生成/读盘/传送进来）时顺带做两件事——
     * 补算「已验证但入口未定」条目的地表入口；验证落到本区块的候选点。
     * 挂在 Forge 总线的 {@code ChunkEvent.Load} 上（两端都会触发，这里只管服务端）。
     *
     * <p>
     * 两件事都<b>只读已加载的区块</b>（本区块必然已加载，入口探测列由
     * {@link LootgameFinder#findEntrance} 的红线保证），所以这里的开销就是
     * 「一次纯数学候选判定 + 至多一列方块扫描」，与区块加载本身同级。
     */
    public static void onChunkLoad(Chunk chunk) {
        if (!loaded || chunk == null || chunk.worldObj == null || chunk.worldObj.isRemote) return;

        int dim = chunk.worldObj.provider.dimensionId;
        if (!LootgameFinder.isWorldGenEnabled(dim)) return;

        // 先补算入口：搜索/自然发现验证地牢时，南侧探测列可能还没加载（推不出来），
        // 现在它们随本区块加载而齐全了，把「已验证但入口未定」的条目补全
        backfillEntrances(chunk);

        int chunkX = chunk.xPosition;
        int chunkZ = chunk.zPosition;
        String chunkKey = dim + ":" + chunkX + ":" + chunkZ;
        if (absentChunks.contains(chunkKey)) return;
        if (!LootgameFinder.isCandidateChunk(chunk.worldObj.getSeed(), dim, chunkX, chunkZ)) return;

        int x = LootgameFinder.centerCoord(chunkX);
        int z = LootgameFinder.centerCoord(chunkZ);
        String key = dim + ":" + x + ":" + z;
        LootassistEntry existing = entries.get(key);
        if (existing != null && existing.verified()) return;

        int masterY = LootgameFinder.findMasterY(chunk.worldObj, x, z);
        if (masterY >= 0) {
            LootassistEntry entry = existing != null ? existing : new LootassistEntry(dim, x, z);
            entry.y = masterY;
            int[] entrance = LootgameFinder.findEntrance(chunk.worldObj, x, z, masterY);
            entry.entranceX = entrance[0];
            entry.entranceY = entrance[1];
            entry.entranceZ = entrance[2];
            upsertEntry(entry);
        } else {
            if (existing != null) removeEntry(key);
            absentChunks.add(chunkKey);
            dirty = true;
        }
    }

    /**
     * 给「已验证、但地表入口还没推算出来」的条目补算入口。
     *
     * <p>
     * 入口探测列在条目南侧 11..25 格、横跨最多两个区块；只处理探测列范围
     * 覆盖到本区块的条目，避免每次区块加载都全表扫一遍。条目本身是少量数据，
     * 未命中的开销是一次坐标比较。
     */
    private static void backfillEntrances(Chunk chunk) {
        int dim = chunk.worldObj.provider.dimensionId;
        int chunkX = chunk.xPosition;
        int chunkZ = chunk.zPosition;

        for (LootassistEntry entry : entries.values()) {
            if (!entry.verified() || entry.entranceY >= 0 || entry.dim != dim) continue;
            // 入口探测列在 (entry.x, entry.z + 11..25)：只关心南侧这两三个区块
            if (chunkX != (entry.x >> 4)) continue;
            int minChunkZ = (entry.z + LootgameFinder.CENTER_TO_BORDER + 1) >> 4;
            int maxChunkZ = (entry.z + LootgameFinder.CENTER_TO_BORDER + LootgameFinder.ENTRANCE_MAX_RUN) >> 4;
            if (chunkZ < minChunkZ || chunkZ > maxChunkZ) continue;

            int[] entrance = LootgameFinder.findEntrance(chunk.worldObj, entry.x, entry.z, entry.y);
            if (entrance[1] >= 0) {
                entry.entranceX = entrance[0];
                entry.entranceY = entrance[1];
                entry.entranceZ = entrance[2];
                upsertEntry(entry);
            }
            // 还是推不出来（探测列仍有未加载的 / 15 格内没有出口）：
            // 留着下次南侧区块加载时再试，开销可忽略
        }
    }

    private static EntityPlayerMP playerByUuid(UUID uuid) {
        if (uuid == null) return null;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return null;
        for (Object object : server.getConfigurationManager().playerEntityList) {
            if (object instanceof EntityPlayerMP && ((EntityPlayerMP) object).getUniqueID()
                .equals(uuid)) {
                return (EntityPlayerMP) object;
            }
        }
        return null;
    }

    private static void sendProgress(EntityPlayerMP player, boolean running) {
        NetworkHandler.INSTANCE
            .sendTo(new PacketLootassistProgress(searchDone, searchTotal, searchFound, running), player);
    }

    // ==================================================================
    // 同步
    // ==================================================================

    /** 给一个玩家发全量快照（打开界面 / 主动刷新时）。 */
    public static void sendSnapshotTo(EntityPlayerMP player) {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (LootassistEntry entry : entries.values()) {
            NBTTagCompound entryTag = new NBTTagCompound();
            entry.writeTo(entryTag);
            list.appendTag(entryTag);
        }
        tag.setTag("entries", list);
        PacketLootassistSync.sendTo(player, tag);
    }

    /** @return 是否还有搜索任务在跑 */
    public static boolean isSearching() {
        return !searchQueue.isEmpty();
    }

    /** 玩家下线时的清理钩子（viewer 表 + 进度接收者）。任务本身照常跑完。 */
    public static void forget(UUID playerId) {
        viewers.remove(playerId);
        if (playerId.equals(searchRequester)) {
            searchRequester = null;
        }
    }
}
