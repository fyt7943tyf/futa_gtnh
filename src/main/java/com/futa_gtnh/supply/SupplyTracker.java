package com.futa_gtnh.supply;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.NetworkHandler;
import com.futa_gtnh.network.PacketSupplySnapshot;
import com.futa_gtnh.network.Utf8Text;
import com.futa_gtnh.shared.FluidKey;
import com.futa_gtnh.shared.ItemKey;
import com.futa_gtnh.shared.SharedStorage;
import com.futa_gtnh.shared.SharedStorageManager;
import com.futa_gtnh.storagebus.StorageBusRegistry;

/** Server-only bounded telemetry. Historical rings are ephemeral; names and warning thresholds are saved. */
public final class SupplyTracker {

    public static final int MAX_WATCHES = 128;
    public static final int PAGE_SIZE = 8;
    public static final SupplyLedger LEDGER = new SupplyLedger();
    private static final ProductionLines PRODUCTION = new ProductionLines();
    private static final Map<String, String> NAMES = new LinkedHashMap<>();
    private static final Map<Object, Watch> WATCHES = new LinkedHashMap<>();
    private static final Map<Object, StockTrend> TRENDS = new LinkedHashMap<>();
    private static final Map<TileEntity, SupplySource[]> IO_SOURCES = new WeakHashMap<>();
    private static List<Map.Entry<Object, Watch>> watchList = new ArrayList<>();
    private static File file;
    private static boolean loaded, writable = true, dirty;
    private static long started, changedAt, lastMinute = -1;
    private static long clock;
    private static int tick, watchCursor;

    private SupplyTracker() {}

    public static boolean enabled() {
        return loaded && Config.enableSupplyTracking && SharedStorageManager.isServerThread();
    }

    public static long minute() {
        return clock / 60_000L;
    }

    public static long clock() {
        return clock;
    }

    public static void record(Object key, SupplySource source, long amount, boolean incoming) {
        if (enabled()) LEDGER.record(key, source, amount, incoming, minute());
    }

    /** Device flows describe warehouse issuance/returns, never inferred machine consumption. */
    public static NBTTagCompound productionSnapshot(String lineId, String sourceId, int minutes, int linePage,
        int sourcePage, int resourcePage) {
        if (!SharedStorageManager.isServerThread()) throw new IllegalStateException("Server thread required");
        NBTTagCompound tag = PRODUCTION
            .snapshot(LEDGER, lineId, sourceId, minutes, linePage, sourcePage, resourcePage, clock);
        tag.setBoolean("enabled", enabled());
        tag.setBoolean("writable", loaded && writable);
        return tag;
    }

    /** Shared command/GUI entry point. Permission is checked here even if a caller already checked it. */
    public static NBTTagCompound productionMutation(ICommandSender sender, NBTTagCompound request) {
        if (sender == null || !sender.canCommandSenderUseCommand(4, "futalines"))
            return ProductionLines.result(false, "修改产线需要管理员权限", "");
        if (!SharedStorageManager.isServerThread() || !loaded || !writable)
            return ProductionLines.result(false, "产线配置未就绪或读取失败，暂时不能修改", "");
        if (request == null) return ProductionLines.result(false, "缺少产线操作", "");
        SupplySource selected = PRODUCTION.source(request, LEDGER);
        NBTTagCompound result = PRODUCTION.mutate(request, LEDGER);
        if (result.getBoolean("ok")) {
            PRODUCTION.refreshRelated(selected, ProductionAttachments::scan);
            if ("unbind".equals(request.getString("action")) && selected != null
                && PRODUCTION.binding(selected)
                    .getBoolean("autoBound"))
                result.setString("message", "已取消手动绑定；总线恢复跟随相邻接口的产线");
            changed();
            save();
            result.setBoolean("saved", file == null || !dirty);
            if (dirty) result.setString("message", result.getString("message") + "；写盘失败，将重试，请查看日志");
        }
        return result;
    }

    public static NBTTagCompound productionBindingSnapshot(ProductionTarget target, int minutes, int linePage) {
        if (!SharedStorageManager.isServerThread()) throw new IllegalStateException("Server thread required");
        if (loaded && writable) PRODUCTION.refreshRelated(target.source, ProductionAttachments::scan);
        NBTTagCompound tag = PRODUCTION.bindingPage(linePage);
        tag.setBoolean("enabled", enabled());
        tag.setBoolean("writable", loaded && writable);
        tag.setTag("bindingTarget", target.source.write());
        tag.setString("targetName", target.name);
        tag.setTag("currentBinding", PRODUCTION.binding(target.source));
        return tag;
    }

    /** Called after actual insertion into the neighboring inventory/tank, never for the provisional extraction. */
    public static void recordIo(Object key, TileEntity destination, ForgeDirection targetFace, long amount,
        boolean incoming) {
        if (!enabled() || amount <= 0
            || destination == null
            || destination.getWorldObj() == null
            || destination.getWorldObj().isRemote
            || targetFace == null
            || targetFace == ForgeDirection.UNKNOWN) return;
        if (StorageBusRegistry.accountsFor(destination, key)) return;
        SupplySource[] sides = IO_SOURCES.computeIfAbsent(destination, ignored -> new SupplySource[6]);
        int side = targetFace.ordinal();
        SupplySource source = sides[side];
        int x = destination.xCoord + targetFace.offsetX;
        int y = destination.yCoord + targetFace.offsetY;
        int z = destination.zCoord + targetFace.offsetZ;
        int dim = destination.getWorldObj().provider.dimensionId;
        if (source == null || source.x != x || source.y != y || source.z != z || source.dim != dim) {
            source = new SupplySource(
                SupplySource.IO,
                dim,
                x,
                y,
                z,
                targetFace.getOpposite()
                    .ordinal(),
                "");
            sides[side] = source;
        }
        LEDGER.record(key, source, amount, incoming, minute());
    }

    public static NBTTagCompound writeKey(Object key) {
        NBTTagCompound tag = new NBTTagCompound();
        if (key instanceof ItemKey) {
            tag.setByte("kind", (byte) 1);
            tag.setTag("key", ((ItemKey) key).writeToNbt());
        } else if (key instanceof FluidKey) {
            tag.setByte("kind", (byte) 2);
            tag.setTag("key", ((FluidKey) key).writeToNbt());
        }
        return tag;
    }

    public static Object readKey(NBTTagCompound tag) {
        if (tag == null) return null;
        if (tag.getByte("kind") == 1) return ItemKey.readFromNbt(tag.getCompoundTag("key"));
        if (tag.getByte("kind") == 2) return FluidKey.readFromNbt(tag.getCompoundTag("key"));
        return null;
    }

    public static boolean rename(String id, String name) {
        if (!enabled() || !writable || LEDGER.source(id) == null) return false;
        String text = Utf8Text.limit(name == null ? "" : name.trim(), 96);
        if (text.isEmpty()) NAMES.remove(id);
        else {
            if (!NAMES.containsKey(id) && NAMES.size() >= SupplyLedger.MAX_SOURCES) return false;
            NAMES.put(id, text);
        }
        changed();
        return true;
    }

    public static boolean watch(Object key, long threshold) {
        if (!enabled() || !writable || key == null) return false;
        if (threshold >= 0 && !boundedKey(key)) return false;
        if (threshold < 0) WATCHES.remove(key);
        else {
            if (!WATCHES.containsKey(key) && WATCHES.size() >= MAX_WATCHES) return false;
            WATCHES.put(key, new Watch(threshold));
        }
        watchList = new ArrayList<>(WATCHES.entrySet());
        watchCursor = 0;
        changed();
        return true;
    }

    private static void changed() {
        dirty = true;
        changedAt = clock;
    }

    static boolean boundedKey(Object key) {
        try {
            NBTTagCompound tag = writeKey(key);
            return tag.toString()
                .length() <= 4096 && CompressedStreamTools.compress(tag).length <= 2048;
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    public static String name(SupplySource source) {
        return NAMES.getOrDefault(source.id(), "");
    }

    /** Only selected/watched keys are sampled, and never the whole warehouse. */
    public static long amount(Object key) {
        SharedStorage storage = SharedStorageManager.getStorage();
        return key instanceof ItemKey ? storage.getItemAmount((ItemKey) key)
            : key instanceof FluidKey ? storage.getFluidAmount((FluidKey) key) : 0;
    }

    public static NBTTagCompound snapshot(Object key, int minutes, int sourcePage, int watchPage) {
        return snapshot(key, minutes, sourcePage, watchPage, 1);
    }

    public static NBTTagCompound snapshot(Object key, int minutes, int sourcePage, int watchPage, int listMode) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("enabled", enabled());
        tag.setBoolean("writable", writable);
        tag.setBoolean("teleportEnabled", enabled() && Config.enableSupplyTeleport);
        tag.setLong("age", clock);
        long observed = SupplyLedger.observedMillis(minutes, clock);
        tag.setInteger("minutes", SupplyLedger.window(minutes));
        tag.setLong("observedMillis", observed);
        tag.setBoolean("hasRate", observed > 0);
        tag.setBoolean("bucketed", true);
        Watch watch = WATCHES.get(key);
        tag.setLong("threshold", watch == null ? -1 : watch.threshold);
        if (key != null) {
            long stock = amount(key);
            tag.setLong("stock", stock);
            StockTrend trend = TRENDS.get(key);
            if (trend == null && TRENDS.size() < 256) {
                trend = new StockTrend();
                TRENDS.put(key, trend);
            }
            if (trend != null) {
                trend.sample(clock, stock);
                tag.setDouble("perMinute", trend.perMinute(clock, stock));
                tag.setBoolean("hasTrend", trend.hasBaseline(clock));
            }
            SupplyLedger.Snapshot snapshot = LEDGER.snapshot(key, minutes, minute());
            tag.setLong("incoming", snapshot.incoming);
            tag.setLong("outgoing", snapshot.outgoing);
            tag.setDouble("incomingPerMinute", observed <= 0 ? 0 : snapshot.incoming * 60_000d / observed);
            tag.setDouble("outgoingPerMinute", observed <= 0 ? 0 : snapshot.outgoing * 60_000d / observed);
            tag.setLong("dropped", snapshot.dropped);
            int page = clampPage(sourcePage, snapshot.rows.size());
            tag.setInteger("sourcePage", page);
            tag.setInteger("sourcePages", pages(snapshot.rows.size()));
            NBTTagList rows = new NBTTagList();
            for (int i = page * PAGE_SIZE; i < Math.min(snapshot.rows.size(), (page + 1) * PAGE_SIZE); i++) {
                SupplyLedger.Row row = snapshot.rows.get(i);
                NBTTagCompound entry = row.source.write();
                entry.setString("id", row.source.id());
                entry.setString("name", name(row.source));
                entry.setLong("out", row.outgoing);
                entry.setLong("in", row.incoming);
                entry.setDouble("outPerMinute", observed <= 0 ? 0 : row.outgoing * 60_000d / observed);
                entry.setDouble("inPerMinute", observed <= 0 ? 0 : row.incoming * 60_000d / observed);
                rows.appendTag(entry);
            }
            tag.setTag("sources", rows);
        }
        int size = listMode == 2 ? LEDGER.keyCount() : listMode == 1 ? watchList.size() : 0;
        int page = clampPage(watchPage, size);
        tag.setInteger("listMode", listMode);
        tag.setInteger("watchPage", page);
        tag.setInteger("watchPages", pages(size));
        NBTTagList watched = new NBTTagList();
        if (listMode == 2) {
            for (Object entry : LEDGER.catalog(page, PAGE_SIZE)) {
                if (!boundedKey(entry)) continue;
                watched.appendTag(writeKey(entry));
            }
        } else if (listMode == 1) {
            for (int i = page * PAGE_SIZE; i < Math.min(watchList.size(), (page + 1) * PAGE_SIZE); i++) {
                Map.Entry<Object, Watch> entry = watchList.get(i);
                NBTTagCompound item = writeKey(entry.getKey());
                item.setLong("threshold", entry.getValue().threshold);
                watched.appendTag(item);
            }
        }
        tag.setTag("watches", watched);
        return tag;
    }

    private static int pages(int size) {
        return Math.max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private static int clampPage(int page, int size) {
        return Math.max(0, Math.min(page, pages(size) - 1));
    }

    public static void onServerTick() {
        if (!loaded || ++tick % 20 != 0) return;
        clock = Math.max(0, (System.nanoTime() - started) / 1_000_000L);
        if (writable) PRODUCTION.refreshAttachments(ProductionAttachments::scan, 32);
        if (dirty && clock - changedAt >= 5000) save();
        if (!Config.enableSupplyTracking) return;
        long minute = minute();
        if (minute != lastMinute) {
            LEDGER.expire(minute);
            lastMinute = minute;
            Iterator<StockTrend> trends = TRENDS.values()
                .iterator();
            while (trends.hasNext()) if (clock - trends.next().lastRead > 600_000L) trends.remove();
        }
        // At most 16 O(1) inventory lookups per second, irrespective of warehouse size.
        int alerts = 0;
        for (int i = 0; i < Math.min(16, watchList.size()); i++) {
            if (watchCursor >= watchList.size()) watchCursor = 0;
            Map.Entry<Object, Watch> entry = watchList.get(watchCursor++);
            Object key = entry.getKey();
            Watch watch = entry.getValue();
            long stock = amount(key);
            StockTrend trend = TRENDS.get(key);
            if (trend == null && TRENDS.size() < 256) {
                trend = new StockTrend();
                TRENDS.put(key, trend);
            }
            if (trend != null) trend.sample(clock, stock);
            if (alerts < 4 && watch.shouldAlert(stock, clock)) {
                alert(key, stock, watch.threshold);
                alerts++;
            }
        }
    }

    private static void alert(Object key, long stock, long threshold) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;
        NBTTagCompound alert = writeKey(key);
        alert.setLong("stock", stock);
        alert.setLong("threshold", threshold);
        NBTTagCompound payload = new NBTTagCompound();
        payload.setTag("alert", alert);
        for (Object entry : server.getConfigurationManager().playerEntityList) {
            EntityPlayerMP player = (EntityPlayerMP) entry;
            if (!com.futa_gtnh.network.SupplyClients.supports(player.getUniqueID(), player.playerNetServerHandler))
                continue;
            NetworkHandler.INSTANCE.sendTo(new PacketSupplySnapshot(-1, 0, payload), player);
        }
    }

    public static void start(MinecraftServer server) {
        File directory = server == null || server.worldServers == null || server.worldServers.length == 0 ? null
            : server.worldServers[0].getSaveHandler()
                .getWorldDirectory();
        start(directory);
    }

    /** Separate metadata file; a failed read blocks writes instead of overwriting the original. */
    public static void start(File worldDirectory) {
        if (loaded) save();
        clear();
        loaded = true;
        started = System.nanoTime();
        file = worldDirectory == null ? null : new File(worldDirectory, "data/futa_gtnh_supply_watch.dat");
        if (file == null || !file.isFile()) return;
        try (FileInputStream input = new FileInputStream(file)) {
            readSettings(CompressedStreamTools.readCompressed(input));
        } catch (Exception error) {
            writable = false;
            FutaGtnhMod.LOG.error("原料追踪：读取配置失败，已暂停写入以保留原文件", error);
        }
    }

    public static NBTTagCompound settings() {
        NBTTagCompound root = new NBTTagCompound();
        root.setInteger("version", 2);
        root.setTag("production", PRODUCTION.write());
        NBTTagList names = new NBTTagList();
        for (Map.Entry<String, String> entry : NAMES.entrySet()) {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("id", entry.getKey());
            tag.setString("name", entry.getValue());
            names.appendTag(tag);
        }
        root.setTag("names", names);
        NBTTagList watches = new NBTTagList();
        for (Map.Entry<Object, Watch> entry : WATCHES.entrySet()) {
            NBTTagCompound tag = writeKey(entry.getKey());
            tag.setLong("threshold", entry.getValue().threshold);
            watches.appendTag(tag);
        }
        root.setTag("watches", watches);
        return root;
    }

    public static void readSettings(NBTTagCompound root) {
        int version = root.getInteger("version");
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported supply settings version");
        if (version == 2 && !root.hasKey("production", 10))
            throw new IllegalArgumentException("Missing production line metadata");
        if (version == 2 && (!root.getCompoundTag("production")
            .hasKey("lines", 9)
            || !root.getCompoundTag("production")
                .hasKey("bindings", 9)))
            throw new IllegalArgumentException("Incomplete production line metadata");
        Map<String, String> names = new LinkedHashMap<>();
        Map<Object, Watch> watches = new LinkedHashMap<>();
        NBTTagList list = root.getTagList("names", 10);
        for (int i = 0; i < Math.min(list.tagCount(), SupplyLedger.MAX_SOURCES); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            String id = tag.getString("id");
            if (id.length() <= 160) names.put(id, Utf8Text.limit(tag.getString("name"), 96));
        }
        list = root.getTagList("watches", 10);
        for (int i = 0; i < Math.min(list.tagCount(), MAX_WATCHES); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            Object key = readKey(tag);
            if (key != null && tag.getLong("threshold") >= 0 && boundedKey(key))
                watches.put(key, new Watch(tag.getLong("threshold")));
        }
        PRODUCTION.read(version == 2 ? root.getCompoundTag("production") : new NBTTagCompound());
        NAMES.clear();
        NAMES.putAll(names);
        WATCHES.clear();
        WATCHES.putAll(watches);
        watchList = new ArrayList<>(WATCHES.entrySet());
        watchCursor = 0;
    }

    public static void save() {
        if (!loaded || !dirty || !writable || file == null) return;
        File temp = new File(file.getPath() + ".tmp");
        try {
            Files.createDirectories(
                file.toPath()
                    .getParent());
            try (FileOutputStream output = new FileOutputStream(temp)) {
                CompressedStreamTools.writeCompressed(settings(), output);
            }
            if (file.isFile()) Files
                .copy(file.toPath(), new File(file.getPath() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(
                    temp.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (Exception error) {
            FutaGtnhMod.LOG.error("原料追踪：保存配置失败", error);
        }
    }

    public static void stop() {
        save();
        clear();
    }

    private static void clear() {
        loaded = false;
        writable = true;
        dirty = false;
        file = null;
        clock = 0;
        tick = 0;
        watchCursor = 0;
        lastMinute = -1;
        LEDGER.clear();
        PRODUCTION.clear();
        NAMES.clear();
        WATCHES.clear();
        TRENDS.clear();
        IO_SOURCES.clear();
        watchList.clear();
    }

    static final class Watch {

        final long threshold;
        boolean armed = true;
        long lastAlert = -300_000;

        Watch(long threshold) {
            this.threshold = threshold;
        }

        boolean shouldAlert(long stock, long now) {
            long recovery = SupplyLedger.add(threshold, Math.max(1, threshold / 10));
            if (stock >= recovery) armed = true;
            if (!armed || stock > threshold || now - lastAlert < 300_000L) return false;
            armed = false;
            lastAlert = now;
            return true;
        }
    }

    static final class StockTrend {

        final long[] times = new long[6], amounts = new long[6];
        final boolean[] valid = new boolean[6];
        long lastRead;

        void sample(long now, long amount) {
            int slot = (int) (now / 60_000L % 6);
            times[slot] = now;
            amounts[slot] = amount;
            valid[slot] = true;
            lastRead = now;
        }

        int baseline(long now) {
            int selected = -1;
            for (int i = 0; i < 6; i++) if (valid[i] && now - times[i] >= 60_000L
                && now - times[i] <= 360_000L
                && (selected < 0 || times[i] < times[selected])) selected = i;
            return selected;
        }

        boolean hasBaseline(long now) {
            return baseline(now) >= 0;
        }

        double perMinute(long now, long amount) {
            int i = baseline(now);
            return i < 0 ? 0 : ((double) amount - amounts[i]) * 60_000d / (now - times[i]);
        }
    }
}
