package com.futa_gtnh.storagebus;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.futa_gtnh.Config;
import com.futa_gtnh.me.logistics.NativeStorageRuntime;

/**
 * 共享存储总线方块实体。
 *
 * <p>
 * 一个方块格子里最多可以装 6 条互相独立的总线，分别贴在六个面：
 * 每条都有自己的目标容器、优先级、访问模式和筛选，渲染成各自面上一块薄板。
 * 这对应 AE 线缆上「同一个格子挂多个部件」的用法。
 *
 * <p>
 * 它自己不存任何东西，只做三件事：
 * <ol>
 * <li>把每条总线的配置持久化；</li>
 * <li>每面托管原生物品和流体存储总线，由 AE 负责适配、读写和库存通知；</li>
 * <li>区块加载后接入公共 ME，卸载时断开原生节点。</li>
 * </ol>
 *
 * <p>
 * 登记<b>不在 {@code validate()} 里做</b>：validate 发生在区块实体加载过程中，
 * 这时访问相邻区块会触发同步区块加载（和 IO 节点踩过的 StackOverflow 同一个坑）。
 * 这里只做记号，等第一个 {@code updateEntity()} 再准备原生部件。
 */
public class TileEntityStorageBus extends TileEntity {

    public static final int FACES = 6;

    private final StorageBusConfig[] configs = new StorageBusConfig[FACES];
    private final StorageBusLink[] links = new StorageBusLink[FACES];
    private final NativeStorageRuntime nativeBus = new NativeStorageRuntime(this);

    private boolean pendingRegister = true;
    private boolean refreshRequested;
    private int refreshTimer;
    /** 六面哪几面有总线；服务端算出来，客户端靠描述包同步（metadata 只有 4 位，装不下 6 位掩码）。 */
    private int faceMask;

    // ==================================================================
    // 面 / 配置
    // ==================================================================

    /** @return 这一面有总线时返回配置，否则 null */
    public StorageBusConfig getConfig(int side) {
        return side >= 0 && side < FACES ? configs[side] : null;
    }

    public StorageBusConfig getConfig(ForgeDirection side) {
        return side == null ? null : getConfig(side.ordinal());
    }

    /** @return 这一面有总线时返回运行时链接，否则 null */
    public StorageBusLink getLink(int side) {
        return side >= 0 && side < FACES ? links[side] : null;
    }

    public StorageBusLink getLink(ForgeDirection side) {
        return side == null ? null : getLink(side.ordinal());
    }

    public boolean hasFace(ForgeDirection side) {
        return side != null && side != ForgeDirection.UNKNOWN && hasFace(side.ordinal());
    }

    /** 客户端只有描述包同步来的掩码，没有配置对象；所以这里读掩码而不是 configs。 */
    public boolean hasFace(int side) {
        return side >= 0 && side < FACES && (faceMask & (1 << side)) != 0;
    }

    /** @return 六面里有总线的位掩码，位 i = ForgeDirection.getOrientation(i) */
    public int getFaceMask() {
        return faceMask;
    }

    private void recomputeFaceMask() {
        int mask = 0;
        for (int i = 0; i < FACES; i++) {
            if (configs[i] != null) mask |= 1 << i;
        }
        faceMask = mask;
    }

    /**
     * 在 {@code side} 面增加一条总线。
     *
     * @return 这一面原来没有总线并成功加入时返回 true
     */
    public boolean addFace(ForgeDirection side) {
        if (side == null || side == ForgeDirection.UNKNOWN) return false;
        int index = side.ordinal();
        if (configs[index] != null) return false;

        StorageBusConfig config = new StorageBusConfig();
        config.setTargetSide(side);
        configs[index] = config;
        links[index] = new StorageBusLink(this, side);
        recomputeFaceMask();
        markDirty();
        if (worldObj != null && !worldObj.isRemote) {
            ensureRegistered();
            refreshRequested = true;
            refreshTimer = 0;
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
        return true;
    }

    /** 移除 {@code side} 面的总线。 */
    public boolean removeFace(ForgeDirection side) {
        if (side == null || side == ForgeDirection.UNKNOWN) return false;
        int index = side.ordinal();
        if (configs[index] == null) return false;

        StorageBusRegistry.unregister(links[index]);
        links[index] = null;
        configs[index] = null;
        nativeBus.update();
        recomputeFaceMask();
        markDirty();
        if (worldObj != null && !worldObj.isRemote) worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        return true;
    }

    /** 界面打开 / 配置改动时调用；把所有已配置但未登记的面登记上。 */
    public void ensureRegistered() {
        if (worldObj == null || worldObj.isRemote) return;
        nativeBus.update();
    }

    public boolean isRegistered() {
        return failure().isEmpty() && com.futa_gtnh.me.PublicMeNetwork.isAttachable();
    }

    public NativeStorageRuntime nativeBus() {
        return nativeBus;
    }

    public String failure() {
        return nativeBus.failure();
    }

    public void onConfigChanged(ForgeDirection side) {
        if (worldObj == null || worldObj.isRemote) return;
        markDirty();
        nativeBus.update();
        nativeBus.changed(side);
        worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    /** 邻居变了：下个 tick 立刻重扫所有面，别等完整刷新周期。 */
    public void requestRefresh() {
        if (worldObj == null || worldObj.isRemote) return;
        refreshRequested = true;
    }

    @Override
    public void validate() {
        super.validate();
        // 只做记号，不在区块加载过程中碰相邻区块（见类注释）
        if (worldObj != null && !worldObj.isRemote) pendingRegister = true;
    }

    @Override
    public void invalidate() {
        for (StorageBusLink link : links) if (link != null) StorageBusRegistry.unregister(link);
        nativeBus.close();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        for (StorageBusLink link : links) if (link != null) StorageBusRegistry.unregister(link);
        nativeBus.close();
        super.onChunkUnload();
    }

    @Override
    public void updateEntity() {
        if (worldObj == null || worldObj.isRemote) return;

        if (pendingRegister) {
            pendingRegister = false;
            // 老存档 / 别的模组放置时可能只有 metadata，没有总线配置：用 metadata 兜底
            if (getFaceMask() == 0) {
                int meta = 0;
                try {
                    meta = worldObj.getBlockMetadata(xCoord, yCoord, zCoord);
                } catch (Throwable ignored) {
                    // 保持空
                }
                if (meta >= 0 && meta < FACES) {
                    addFace(ForgeDirection.getOrientation(meta));
                }
            }
            recomputeFaceMask();
            if (worldObj != null && !worldObj.isRemote) worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
            ensureRegistered();
            return;
        }

        nativeBus.update();
        if (refreshRequested || ++refreshTimer >= Math.max(1, Config.storageBusRefreshTicks)) {
            refreshRequested = false;
            refreshTimer = 0;
            nativeBus.neighborsChanged();
        }
    }

    /**
     * metadata 只有 4 位，装不下六面掩码；用方块实体描述包把「哪几面有板」同步到客户端。
     * 渲染、碰撞和选中都读客户端这份掩码。
     */
    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("mask", faceMask);
        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 1, tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        faceMask = pkt.func_148857_g()
            .getInteger("mask");
        if (worldObj != null) worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
    }

    // ==================================================================
    // 存档
    // ==================================================================

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        super.writeToNBT(tag);
        nativeBus.write(tag);
        NBTTagList buses = new NBTTagList();
        for (int i = 0; i < FACES; i++) {
            if (configs[i] == null) continue;
            NBTTagCompound entry = configs[i].writeToNbt();
            entry.setByte("side", (byte) i);
            buses.appendTag(entry);
        }
        tag.setTag("buses", buses);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        nativeBus.read(tag);
        for (int i = 0; i < FACES; i++) {
            configs[i] = null;
            links[i] = null;
        }

        NBTTagList buses = tag.getTagList("buses", 10);
        if (buses.tagCount() > 0) {
            for (int i = 0; i < buses.tagCount(); i++) {
                NBTTagCompound entry = buses.getCompoundTagAt(i);
                int side = entry.hasKey("side") ? entry.getByte("side") : -1;
                if (side < 0 || side >= FACES) continue;
                StorageBusConfig config = new StorageBusConfig();
                config.readFromNbt(entry);
                config.setTargetSide(ForgeDirection.getOrientation(side));
                configs[side] = config;
                links[side] = new StorageBusLink(this, ForgeDirection.getOrientation(side));
            }
        } else if (tag.hasKey("bus")) {
            // 1.31.0 及更早的单面存档
            StorageBusConfig config = new StorageBusConfig();
            config.readFromNbt(tag.getCompoundTag("bus"));
            ForgeDirection side = config.getTargetSide();
            if (side == null || side == ForgeDirection.UNKNOWN) {
                side = ForgeDirection.DOWN;
            }
            config.setTargetSide(side);
            configs[side.ordinal()] = config;
            links[side.ordinal()] = new StorageBusLink(this, side);
        }
        recomputeFaceMask();
        pendingRegister = true;
    }

    // ==================================================================
    // 放置辅助
    // ==================================================================

    /**
     * 选目标面：优先玩家点击的那一面（也就是「贴着放」的方向），
     * 那里不是容器时退而求其次找第一个相邻容器。
     */
    public static ForgeDirection pickTargetSide(World world, int x, int y, int z, ForgeDirection preferred) {
        ForgeDirection fallback = preferred == null || preferred == ForgeDirection.UNKNOWN ? ForgeDirection.DOWN
            : preferred;
        if (isContainerAt(world, x, y, z, fallback)) return fallback;
        for (ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
            if (side == fallback) continue;
            if (isContainerAt(world, x, y, z, side)) return side;
        }
        return fallback;
    }

    /** @return 总线方块 {@code (x,y,z)} 的 {@code side} 面隔壁是不是可接的容器 */
    public static boolean isContainerAt(World world, int x, int y, int z, ForgeDirection side) {
        if (world == null || side == null || side == ForgeDirection.UNKNOWN) return false;
        TileEntity tile;
        try {
            if (!world.blockExists(x + side.offsetX, y + side.offsetY, z + side.offsetZ)) return false;
            tile = world.getTileEntity(x + side.offsetX, y + side.offsetY, z + side.offsetZ);
        } catch (Throwable t) {
            return false;
        }
        if (tile == null) return false;
        ForgeDirection face = side.getOpposite();
        return ExternalInventoryOps.isItemTarget(tile, face) || ExternalInventoryOps.isFluidTarget(tile, face);
    }
}
