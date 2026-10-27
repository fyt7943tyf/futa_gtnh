package com.futa_gtnh.me;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.futa_gtnh.FutaGtnhMod;
import com.futa_gtnh.ae2.PublicStorageAccess;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.pathing.ControllerState;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IAEStackType;
import appeng.util.item.AEFluidStack;

/** One persisted public AE network, with six ordinary dense controller paths and one forced chunk. */
public final class PublicMeNetwork {

    public static final int PORT_COUNT = 6;
    public static final int CHANNELS_PER_PORT = 32;
    private static final int[][] OFFSETS = { { -1, 0, 0 }, { 1, 0, 0 }, { 0, -1, 0 }, { 0, 1, 0 }, { 0, 0, -1 },
        { 0, 0, 1 } };
    private static final Set<TilePublicMeAccess> ACCESS = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final TilePublicMeCore[] PORTS = new TilePublicMeCore[PORT_COUNT];
    private static ForgeChunkManager.Ticket ticket;
    private static World ticketWorld;
    private static WorldServer world;
    private static Block coreBlock;
    private static UUID storageId;
    private static PublicMeWorldData saved;
    private static IGrid installedGrid;
    private static boolean running;
    private static int preferredX;
    private static int preferredY;
    private static int preferredZ;
    private static int ticks;
    private static String problem = "公共 ME 网络尚未启动";
    private static String loggedProblem;
    private static Boolean fluidIdentityValid;

    private PublicMeNetwork() {}

    /** Register during preInit, before Forge restores saved chunk tickets. */
    public static void initChunkLoading() {
        ForgeChunkManager.setForcedChunkLoadingCallback(FutaGtnhMod.instance, (tickets, loadedWorld) -> {
            for (ForgeChunkManager.Ticket candidate : tickets) {
                if (ticket == null && loadedWorld.provider.dimensionId == 0
                    && candidate.getModData()
                        .getBoolean("futaPublicMe")) {
                    ticket = candidate;
                    ticketWorld = loadedWorld;
                    candidate.setChunkListDepth(1);
                    ForgeChunkManager.forceChunk(
                        candidate,
                        new ChunkCoordIntPair(
                            candidate.getModData()
                                .getInteger("chunkX"),
                            candidate.getModData()
                                .getInteger("chunkZ")));
                } else {
                    ForgeChunkManager.releaseTicket(candidate);
                }
            }
        });
    }

    public static void start(MinecraftServer server, Block block, UUID id, int x, int y, int z) {
        if (running) stop();
        if (server == null || block == null || id == null) throw new IllegalArgumentException("Public ME bootstrap");
        world = server.worldServerForDimension(0);
        coreBlock = block;
        storageId = id;
        preferredX = x;
        preferredY = y;
        preferredZ = z;
        ticks = 0;
        installedGrid = null;
        running = true;
        saved = null;
        problem = "公共 ME 网络正在初始化";
        loggedProblem = null;
        fluidIdentityValid = null;
    }

    public static boolean matchesStorageId(UUID id) {
        return storageId != null && storageId.equals(id);
    }

    public static boolean isRunning() {
        return running;
    }

    /** The physical controller is the stable anchor used by optional wireless integrations. */
    public static TileEntity controller() {
        if (!running || world == null || saved == null || !saved.located) return null;
        TileEntity tile = world.getTileEntity(saved.x, saved.y, saved.z);
        return tile != null && !tile.isInvalid() && isControllerClass(tile.getClass()) ? tile : null;
    }

    public static boolean isControllerLocation(int dimension, int x, int y, int z) {
        return running && world != null
            && saved != null
            && saved.located
            && world.provider.dimensionId == dimension
            && saved.x == x
            && saved.y == y
            && saved.z == z;
    }

    public static TilePublicMeCore core() {
        TilePublicMeCore primary = PORTS[0];
        return primary != null && !primary.isInvalid() && owns(primary) ? primary : null;
    }

    public static boolean owns(TilePublicMeCore tile) {
        if (!running || saved == null || !saved.located || tile == null || tile.getWorldObj() != world) return false;
        for (int i = 0; i < PORT_COUNT; i++) {
            if (tile.xCoord == saved.x + OFFSETS[i][0] && tile.yCoord == saved.y + OFFSETS[i][1]
                && tile.zCoord == saved.z + OFFSETS[i][2]
                && tile.belongsTo(storageId, i)) return true;
        }
        return false;
    }

    /** Always resolve from the live node; AE replaces grids during splits and joins. */
    public static IGrid grid() {
        TilePublicMeCore primary = core();
        IGridNode node = primary == null ? null : primary.getActionableNode();
        return node == null ? null : node.getGrid();
    }

    public static IGrid getGrid() {
        return grid();
    }

    public static boolean isOnline() {
        if (!running || problem != null) return false;
        IGrid grid = grid();
        if (grid == null || grid != installedGrid) return false;
        IEnergyGrid energy = grid.getCache(IEnergyGrid.class);
        IPathingGrid path = grid.getCache(IPathingGrid.class);
        return energy != null && energy.isNetworkPowered()
            && path != null
            && !path.isNetworkBooting()
            && path.getControllerState() == ControllerState.CONTROLLER_ONLINE;
    }

    @SuppressWarnings("unchecked")
    public static <T extends IAEStack<T>> IMEMonitor<T> monitor(IAEStackType<T> type) {
        if (!isOnline() || type == null) return null;
        IStorageGrid storage = grid().getCache(IStorageGrid.class);
        return storage == null ? null : (IMEMonitor<T>) storage.getMEMonitor(type);
    }

    public static <T extends IAEStack<T>> IMEMonitor<T> getMonitor(IAEStackType<T> type) {
        return monitor(type);
    }

    public static MachineSource actionSource() {
        TilePublicMeCore primary = core();
        return primary == null ? null : new MachineSource(primary);
    }

    public static String status() {
        if (problem != null) return problem;
        if (!isOnline()) return "公共 ME 网络正在寻路、缺少控制器或存在控制器冲突";
        StringBuilder text = new StringBuilder("公共 ME 网络在线；端口频道 ");
        for (int i = 0; i < PORT_COUNT; i++) {
            if (i > 0) text.append(" / ");
            text.append(i + 1)
                .append(": ")
                .append(portUsedChannels(i))
                .append("/32");
        }
        return text.toString();
    }

    public static int portUsedChannels(int port) {
        IGridNode node = portNode(port);
        if (node == null) return 0;
        for (IGridConnection connection : node.getConnections()) {
            if (isControllerClass(
                connection.getOtherSide(node)
                    .getMachine()
                    .getClass()))
                return connection.getUsedChannels();
        }
        return 0;
    }

    static IGridNode portNode(int index) {
        if (index < 0 || index >= PORT_COUNT) return null;
        TilePublicMeCore port = PORTS[index];
        return port == null || port.isInvalid() || !owns(port) ? null : port.getActionableNode();
    }

    static boolean isControllerClass(Class<?> type) {
        // TileController inherits optional RF/IC2/RotaryCraft interfaces absent from the compile classpath.
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if ("appeng.tile.networking.TileController".equals(current.getName())) return true;
        }
        return false;
    }

    public static boolean isManagedController(IGridHost host) {
        return running && host instanceof TileEntity controller
            && saved != null
            && saved.located
            && !controller.isInvalid()
            && isControllerClass(controller.getClass())
            && controller.getWorldObj() == world
            && controller.xCoord == saved.x
            && controller.yCoord == saved.y
            && controller.zCoord == saved.z;
    }

    public static boolean isAttachable() {
        return running && problem == null && grid() != null;
    }

    static int choosePort() {
        int[] counts = new int[PORT_COUNT];
        for (TilePublicMeAccess access : ACCESS) {
            int port = access.getPort();
            if (port >= 0 && port < PORT_COUNT && !access.isInvalid()) counts[port]++;
        }
        int best = 0;
        for (int i = 1; i < PORT_COUNT; i++) {
            if (portUsedChannels(i) < portUsedChannels(best)
                || (portUsedChannels(i) == portUsedChannels(best) && counts[i] < counts[best])) best = i;
        }
        return best;
    }

    static void register(TilePublicMeAccess access) {
        ACCESS.add(access);
    }

    static void unregister(TilePublicMeAccess access) {
        ACCESS.remove(access);
    }

    public static void tick() {
        if (!running || world == null) return;
        ticks++;
        if (ticks == 1 || ticks % 20 == 0) {
            if (!bootstrap()) {
                detachRuntime();
                return;
            }
        }
        IGrid live = grid();
        if (live != installedGrid) {
            PublicStorageAccess.install(live);
            installedGrid = live;
        }
        if (ticks % 20 == 0) {
            for (TilePublicMeAccess access : new ArrayList<>(ACCESS)) {
                if (access.isInvalid() || access.getWorldObj() == null) ACCESS.remove(access);
                else access.refreshConnection();
            }
        }
    }

    private static void detachRuntime() {
        if (installedGrid != null) PublicStorageAccess.install(null);
        installedGrid = null;
        for (TilePublicMeAccess access : new ArrayList<>(ACCESS)) access.disconnect();
    }

    private static boolean bootstrap() {
        if (!validateFluidIdentity()) return false;
        if (saved == null) {
            saved = (PublicMeWorldData) world.loadItemData(PublicMeWorldData.class, PublicMeWorldData.NAME);
            if (saved == null) {
                saved = new PublicMeWorldData(PublicMeWorldData.NAME);
                world.setItemData(PublicMeWorldData.NAME, saved);
            }
            if (saved.storageId == null) {
                saved.storageId = storageId;
                saved.markDirty();
            }
        }
        if (!storageId.equals(saved.storageId)) return fail("公共 ME 核心存档标识与共享仓库不一致，已停止自动接入");
        if (saved.located && (saved.y < 3 || saved.y > world.getActualHeight() - 4
            || Math.abs((long) saved.x) > 29_999_800L
            || Math.abs((long) saved.z) > 29_999_800L
            || (saved.x & 15) < 2
            || (saved.x & 15) > 13
            || (saved.z & 15) < 2
            || (saved.z & 15) > 13)) {
            return fail("公共 ME 核心坐标无效，已停止自动修复");
        }
        if (!ensureTicket()) return false;
        if (!saved.located && !findLocation()) return false;
        if (!forceCoreChunk()) return false;
        Block controller = AEApi.instance()
            .definitions()
            .blocks()
            .controller()
            .maybeBlock()
            .orNull();
        if (controller == null) return fail("AE 控制器未启用，无法初始化公共 ME 网络");
        if (!mayRestore(saved.x, saved.y, saved.z, controller)) return fail("公共 ME 控制器位置被其他方块占用，未覆盖该方块");
        for (int i = 0; i < PORT_COUNT; i++) {
            int x = saved.x + OFFSETS[i][0], y = saved.y + OFFSETS[i][1], z = saved.z + OFFSETS[i][2];
            if (!mayRestore(x, y, z, coreBlock)) return fail("公共 ME 端口位置被其他方块占用，未覆盖该方块");
            TileEntity tile = world.getTileEntity(x, y, z);
            if (tile instanceof TilePublicMeCore port && !port.belongsTo(storageId, i)) {
                return fail("公共 ME 端口标识不符，未接管其他核心");
            }
        }
        // Preflight above makes repairs bounded and prevents overwriting any unrelated block.
        if (world.isAirBlock(saved.x, saved.y, saved.z)
            && !world.setBlock(saved.x, saved.y, saved.z, controller, 0, 3)) {
            return fail("公共 ME 控制器方块放置失败，已停止初始化");
        }
        TileEntity controllerTile = world.getTileEntity(saved.x, saved.y, saved.z);
        if (controllerTile == null || !isControllerClass(controllerTile.getClass())) {
            return fail(
                "公共 ME 控制器实体未能创建：" + (controllerTile == null ? "null"
                    : controllerTile.getClass()
                        .getName()));
        }
        for (int i = 0; i < PORT_COUNT; i++) {
            int x = saved.x + OFFSETS[i][0], y = saved.y + OFFSETS[i][1], z = saved.z + OFFSETS[i][2];
            if (world.isAirBlock(x, y, z)) {
                if (!world.setBlock(x, y, z, coreBlock, 0, 3)) return fail("公共 ME 端口方块放置失败，已停止初始化");
                TileEntity placed = world.getTileEntity(x, y, z);
                if (placed instanceof TilePublicMeCore port) port.bind(storageId, i);
            }
            TileEntity loaded = world.getTileEntity(x, y, z);
            if (!(loaded instanceof TilePublicMeCore port)) return fail("公共 ME 端口实体未能创建");
            PORTS[i] = port;
        }
        problem = null;
        loggedProblem = null;
        return true;
    }

    private static boolean mayRestore(int x, int y, int z, Block expected) {
        return world.isAirBlock(x, y, z) || world.getBlock(x, y, z) == expected;
    }

    private static boolean validateFluidIdentity() {
        if (fluidIdentityValid == null) {
            fluidIdentityValid = false;
            try {
                NBTTagCompound firstTag = new NBTTagCompound();
                firstTag.setString("futaPublicMeIdentityProbe", "first");
                NBTTagCompound secondTag = new NBTTagCompound();
                secondTag.setString("futaPublicMeIdentityProbe", "second");
                FluidStack source = new FluidStack(FluidRegistry.WATER, 1, firstTag);
                AEFluidStack first = AEFluidStack.create(source);
                AEFluidStack same = AEFluidStack.create(new FluidStack(FluidRegistry.WATER, 1, firstTag));
                AEFluidStack second = AEFluidStack.create(new FluidStack(FluidRegistry.WATER, 1, secondTag));
                source.tag.setString("futaPublicMeIdentityProbe", "caller changed");
                FluidStack returned = first.getFluidStack();
                if (returned.tag != null) returned.tag.setString("futaPublicMeIdentityProbe", "result changed");
                fluidIdentityValid = firstTag.equals(first.getFluidStack().tag) && first.equals(same)
                    && first.hashCode() == same.hashCode()
                    && !first.isSameType(second)
                    && !first.equals(second);
            } catch (RuntimeException | LinkageError error) {
                FutaGtnhMod.LOG.error("Public ME fluid identity validation failed", error);
            }
        }
        return fluidIdentityValid || fail("AE 流体 NBT 身份校验失败，公共 ME 已停止接入以保护原始仓库；请检查兼容补丁");
    }

    private static boolean ensureTicket() {
        if (ticket != null && ticketWorld != world) {
            ForgeChunkManager.releaseTicket(ticket);
            ticket = null;
        }
        if (ticket == null) {
            ticket = ForgeChunkManager.requestTicket(FutaGtnhMod.instance, world, ForgeChunkManager.Type.NORMAL);
            ticketWorld = world;
        }
        if (ticket == null) return fail("公共 ME 网络无法取得常加载票据；请检查 Forge 区块加载配置");
        ticket.setChunkListDepth(1);
        return true;
    }

    private static boolean findLocation() {
        int y = Math.max(3, Math.min(world.getActualHeight() - 4, preferredY));
        // Every candidate has a complete 5x5x5 air clearance and fits inside one chunk.
        for (int ring = 0; ring <= 2; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    int x = (preferredX >> 4) * 16 + 8 + dx * 16;
                    int z = (preferredZ >> 4) * 16 + 8 + dz * 16;
                    if (Math.abs((long) x) > 29_999_800L || Math.abs((long) z) > 29_999_800L) continue;
                    if (!loadChunk(x >> 4, z >> 4)) return false;
                    boolean clear = true;
                    for (int ox = -2; ox <= 2 && clear; ox++) {
                        for (int oy = -2; oy <= 2 && clear; oy++) {
                            for (int oz = -2; oz <= 2; oz++) {
                                if (!world.isAirBlock(x + ox, y + oy, z + oz)) {
                                    clear = false;
                                    break;
                                }
                            }
                        }
                    }
                    if (!clear) continue;
                    saved.x = x;
                    saved.y = y;
                    saved.z = z;
                    saved.located = true;
                    saved.markDirty();
                    FutaGtnhMod.LOG.info("Public ME controller reserved at overworld {}, {}, {}", x, y, z);
                    return true;
                }
            }
        }
        return fail("配置位置附近没有安全的公共 ME 核心空位；请调整核心坐标，未覆盖任何方块");
    }

    private static boolean forceCoreChunk() {
        ChunkCoordIntPair target = new ChunkCoordIntPair(saved.x >> 4, saved.z >> 4);
        for (ChunkCoordIntPair old : new ArrayList<>(ticket.getChunkList())) {
            if (!old.equals(target)) ForgeChunkManager.unforceChunk(ticket, old);
        }
        ticket.getModData()
            .setBoolean("futaPublicMe", true);
        ticket.getModData()
            .setInteger("chunkX", target.chunkXPos);
        ticket.getModData()
            .setInteger("chunkZ", target.chunkZPos);
        ForgeChunkManager.forceChunk(ticket, target);
        return loadChunk(target.chunkXPos, target.chunkZPos);
    }

    private static boolean loadChunk(int x, int z) {
        // Forge's forceChunk only records the ticket. World.getChunkFromChunkCoords can return
        // an EmptyChunk when loadChunkOnProvideRequest is disabled, so never use it to load our site.
        Chunk chunk = world.theChunkProviderServer.loadChunk(x, z);
        return chunk != null && !(chunk instanceof EmptyChunk) && chunk.xPosition == x && chunk.zPosition == z
            || fail("公共 ME 核心区块未能实际加载，已停止初始化");
    }

    private static boolean fail(String message) {
        problem = message;
        if (!message.equals(loggedProblem)) {
            FutaGtnhMod.LOG.error(message);
            loggedProblem = message;
        }
        return false;
    }

    public static void stop() {
        for (TilePublicMeAccess access : new ArrayList<>(ACCESS)) access.disconnect();
        ACCESS.clear();
        for (int i = 0; i < PORT_COUNT; i++) PORTS[i] = null;
        if (ticket != null) ForgeChunkManager.releaseTicket(ticket);
        ticket = null;
        ticketWorld = null;
        world = null;
        saved = null;
        installedGrid = null;
        coreBlock = null;
        storageId = null;
        running = false;
        fluidIdentityValid = null;
        problem = "公共 ME 网络已停止";
        PublicMeFlowTracking.clear();
    }
}
