package com.futa_gtnh.reactor;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.WorldServer;

import com.futa_gtnh.Config;
import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.network.NetworkHandler;

/**
 * 反应堆布局档案的服务端中枢（单例、每服务器一份，全服共享）。
 *
 * <p>
 * 职责与 {@code LootassistManager} 同构：
 * <ul>
 * <li><b>共享数据</b>：全部布局（{@link ReactorLayout}）落盘走
 * {@link ReactorLayoutFile}（原子写 + 备份），挂在存档目录下；</li>
 * <li><b>同步</b>：布局列表是小的元数据（名字/类型/列数），打开界面的玩家登记为
 * viewer，任何改动后把整份列表重推给所有 viewer —— 布局条数有
 * {@link Config#reactorToolMaxLayouts} 上限，全量重推比做增量省心；
 * 54 格的完整内容只在玩家<b>选中某条</b>时按需发（{@code PREVIEW} 动作），
 * 不随列表常驻；</li>
 * <li><b>记录</b>：服务端权威从反应堆网格读出快照入库 —— 客户端报上来的坐标
 * 只是线索，读网格永远以服务端看到的那台反应堆为准。</li>
 * </ul>
 *
 * <p>
 * 全部方法只在服务端主线程调用（网络 handler 在 1.7.10 实际跑在主线程，见
 * {@code NetworkHandler} 的类注释）。
 */
public final class ReactorLayoutManager {

    private ReactorLayoutManager() {}

    private static final Map<Long, ReactorLayout> layouts = new LinkedHashMap<>();
    private static final Set<UUID> viewers = new LinkedHashSet<>();

    private static File worldDirectory;
    private static long nextId = 1L;
    private static boolean loaded;
    private static boolean dirty;
    private static int autosaveTicker;

    // ==================================================================
    // 生命周期（与 SharedStorageManager / LootassistManager 同一套时机）
    // ==================================================================

    public static void onServerStarted(MinecraftServer server) {
        if (loaded) {
            saveNow();
        }
        viewers.clear();

        File dir = resolveWorldDirectory(server);
        if (dir == null) {
            FutaGtnhMod.LOG.error("反应堆布局档案：拿不到主世界存档目录，本次以内存模式运行（不会写盘）");
            worldDirectory = null;
            loaded = true;
            return;
        }

        worldDirectory = dir;
        ReactorLayoutFile.Loaded loadedData = ReactorLayoutFile.load(dir);
        layouts.clear();
        if (loadedData != null) {
            layouts.putAll(loadedData.layouts);
            nextId = loadedData.nextId;
        }
        loaded = true;
        dirty = false;
        autosaveTicker = 0;
    }

    public static void onServerStopping() {
        saveNow();
        loaded = false;
        viewers.clear();
        layouts.clear();
    }

    /** 服务端 tick：定期落盘。空载开销是一次布尔判断。 */
    public static void onServerTick() {
        if (!loaded || worldDirectory == null) return;
        if (++autosaveTicker < Config.autosaveIntervalSeconds * 20) return;
        autosaveTicker = 0;
        if (dirty) {
            saveNow();
        }
    }

    /** 立即落盘（若有改动）。 */
    public static void saveNow() {
        if (worldDirectory == null || !dirty) return;
        if (ReactorLayoutFile.save(worldDirectory, layouts.values(), nextId)) {
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
            FutaGtnhMod.LOG.error("反应堆布局档案：读取主世界存档目录失败", t);
            return null;
        }
    }

    // ==================================================================
    // viewer 与同步
    // ==================================================================

    public static void addViewer(UUID playerId) {
        viewers.add(playerId);
    }

    public static void removeViewer(UUID playerId) {
        viewers.remove(playerId);
    }

    /** 玩家下线时的清理钩子（viewer 表）。 */
    public static void forget(UUID playerId) {
        viewers.remove(playerId);
    }

    /** 把整份布局列表发给所有开着的界面（任何改动之后调用）。 */
    private static void broadcastList() {
        for (EntityPlayerMP viewer : viewerPlayers()) {
            sendListTo(viewer);
        }
    }

    /** 给一个玩家发整份布局列表（打开界面 / 改动重推）。 */
    public static void sendListTo(EntityPlayerMP player) {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (ReactorLayout layout : layouts.values()) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setLong("id", layout.id);
            entry.setString("name", layout.name);
            entry.setBoolean("fluid", layout.fluidCooled);
            entry.setInteger("columns", layout.columns);
            entry.setLong("created", layout.createdAt);
            entry.setInteger("used", layout.occupiedSlots());
            list.appendTag(entry);
        }
        tag.setTag("layouts", list);
        com.futa_gtnh.network.PacketReactorLayoutSync.sendTo(player, tag);
    }

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

    // ==================================================================
    // 条目操作
    // ==================================================================

    /** @return 全部布局（按保存顺序，只读） */
    public static Collection<ReactorLayout> getLayouts() {
        return Collections.unmodifiableCollection(layouts.values());
    }

    public static ReactorLayout getLayout(long id) {
        return layouts.get(id);
    }

    /**
     * 服务端权威：从反应堆网格读出一份新布局入库。
     *
     * @return 入库后的布局；失败时返回 null（原因已发聊天栏）
     */
    public static ReactorLayout recordLayout(EntityPlayerMP player, Ic2ReactorCompat.ReactorRef ref) {
        int columns = Ic2ReactorCompat.getColumns(ref);
        ReactorLayout layout = ReactorDeployer.capture(ref, columns);
        layout.createdAt = System.currentTimeMillis();
        layout.name = autoName(layout);
        layout.id = 0L;

        if (layouts.size() >= Config.reactorToolMaxLayouts) {
            player.addChatMessage(
                new ChatComponentTranslation("futa_gtnh.reactor.msg.save_full", Config.reactorToolMaxLayouts));
            return null;
        }

        layout.id = nextId++;
        layouts.put(layout.id, layout);
        dirty = true;
        broadcastList();

        player.addChatMessage(
            new ChatComponentTranslation(
                "futa_gtnh.reactor.msg.recorded",
                layout.name,
                layout.columns,
                layout.occupiedSlots()));
        return layout;
    }

    /** 自动命名：类型标记 + 记录时间，玩家随手存不会得到一堆「未命名」。 */
    private static String autoName(ReactorLayout layout) {
        String time = new SimpleDateFormat("MM-dd HH:mm").format(new Date(layout.createdAt));
        return (layout.fluidCooled ? "[流体] " : "[固体] ") + time;
    }

    /** @return 是否真的改了名 */
    public static boolean rename(long id, String name) {
        ReactorLayout layout = layouts.get(id);
        if (layout == null) return false;
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.equals(layout.name)) return true;
        layout.name = trimmed;
        dirty = true;
        broadcastList();
        return true;
    }

    /** @return 是否真的删了 */
    public static boolean delete(long id) {
        if (layouts.remove(id) == null) return false;
        dirty = true;
        broadcastList();
        return true;
    }

    /** @return 布局在共享存储中的只读副本；不存在时返回 null */
    public static ReactorLayout copyOf(long id) {
        ReactorLayout layout = layouts.get(id);
        return layout == null ? null : layout.copy();
    }

    // ==================================================================
    // 预览
    // ==================================================================

    /**
     * 把一条布局的「网格内容 + 需求清单」发给玩家。
     *
     * <p>
     * 需求清单按 (物品, 耐久) 分组聚合，可用量拆成「玩家背包 / 共享背包」两部分、
     * 并标注是否存在 NBT 完全一致的来源 —— 客户端只管画，别的不掺和。
     */
    public static void sendPreviewTo(EntityPlayerMP player, ReactorLayout layout, boolean looseNbt) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("id", layout.id);
        tag.setString("name", layout.name);
        tag.setBoolean("fluid", layout.fluidCooled);
        tag.setInteger("columns", layout.columns);

        NBTTagList slotList = new NBTTagList();
        for (int i = 0; i < layout.slots.length; i++) {
            ItemStack stack = layout.slots[i];
            if (stack == null || stack.getItem() == null) continue;
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("slot", i);
            entry.setInteger("item", net.minecraft.item.Item.getIdFromItem(stack.getItem()));
            entry.setInteger("meta", stack.getItemDamage());
            entry.setInteger("size", stack.stackSize);
            NBTTagCompound nbt = stack.getTagCompound();
            if (nbt != null && !nbt.hasNoTags()) entry.setTag("nbt", (NBTTagCompound) nbt.copy());
            slotList.appendTag(entry);
        }
        tag.setTag("slots", slotList);

        NBTTagList rows = new NBTTagList();
        for (ReactorDeployer.ManifestRow row : ReactorDeployer.buildManifest(player, layout, looseNbt)) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("item", net.minecraft.item.Item.getIdFromItem(row.display.getItem()));
            entry.setInteger("meta", row.display.getItemDamage());
            NBTTagCompound nbt = row.display.getTagCompound();
            if (nbt != null && !nbt.hasNoTags()) entry.setTag("nbt", (NBTTagCompound) nbt.copy());
            entry.setInteger("needed", row.needed);
            entry.setInteger("havePlayer", row.availablePlayer);
            entry.setInteger("haveStorage", row.availableStorage);
            entry.setBoolean("exact", row.exactAvailable);
            entry.setString("displayName", row.display.getDisplayName());
            rows.appendTag(entry);
        }
        tag.setTag("manifest", rows);

        NetworkHandler.INSTANCE.sendTo(new com.futa_gtnh.network.PacketReactorLayoutPreview(tag), player);
    }
}
